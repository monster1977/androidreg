package com.example.dashcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Дежурное распознавание жестов фронтальной камерой.
 *
 * <p>BV6600Pro не поддерживает одновременную работу двух камер
 * ({@code CameraManager.getConcurrentCameraIds()} возвращает пустой набор), поэтому следить
 * за жестами можно только пока основная камера не пишет дорогу. Главный экран держит этот
 * «сторож» вместо превью дороги: пользователь видит себя и может показать жест «V», чтобы
 * запустить запись.</p>
 *
 * <p>Распознавание — MediaPipe Hand Landmarker (21 точка кисти), жест считается по числу
 * разогнутых пальцев ({@link GestureClassifier}). Кадры берутся из {@code ImageAnalysis}
 * 640×480 только фронтальной камеры, поэтому разрешение записи основной камеры не затрагивается.</p>
 *
 * <p>Один экземпляр живёт один сеанс дежурства: после {@link #stop()} его нельзя запускать
 * снова, создавайте новый — так проще не смешивать потоки и модель между сеансами.</p>
 */
final class GestureWatcher {

    /** Куда сообщать о стабильных жестах (вызывается в главном потоке). */
    interface Listener {
        /** Жест держится несколько кадров подряд; {@link GestureClassifier#NONE} — рука не видна. */
        void onStableGesture(String gesture);

        /** Камеру так и не удалось открыть. */
        void onCameraError();
    }

    private static final String TAG = "Gestures";
    private static final String MODEL_ASSET = "hand_landmarker.task";

    /** Сколько одинаковых кадров подряд нужно, чтобы поверить жесту. */
    private static final int STABLE_FRAMES = 2;
    /** Пауза перед первым открытием камеры: даём сервису закрыть основную камеру. */
    private static final long FIRST_BIND_DELAY_MS = 400;
    private static final long BIND_RETRY_MS = 600;
    private static final int MAX_BIND_ATTEMPTS = 7;
    /** Если результат не пришёл дольше этого, считаем кадр потерянным и берём следующий. */
    private static final long INFERENCE_STUCK_MS = 1500;
    private static final long FPS_LOG_PERIOD_MS = 5000;

    private final LifecycleOwner owner;
    private final Context context;
    private final PreviewView previewView;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ExecutorService executor;
    private ProcessCameraProvider cameraProvider;
    private volatile HandLandmarker landmarker;
    private volatile boolean released;
    private volatile boolean modelLoading;
    private volatile boolean handSeen;
    private volatile String lastResult = GestureClassifier.NONE;
    /**
     * Один кадр в работе. Без этого MediaPipe в LIVE_STREAM копит очередь: анализ зовёт
     * {@code detectAsync} каждые ~33 мс, а модель успевает раз в ~200 мс, и решение приходит
     * по кадру, снятому секунду назад. Ждём результат, потом берём следующий свежий кадр.
     */
    private volatile boolean inferenceBusy;
    private volatile long lastSubmitAt;
    private volatile long lastResultLatencyMs = -1;
    /**
     * GPU-делегат на BV6600Pro (PowerVR GE8320) проверен и оказался хуже CPU:
     * создание модели ~39 с (компиляция шейдеров), дальше 2–3 кадра/с против 4–5 на CPU.
     * Поэтому по умолчанию CPU; код GPU оставлен как аварийный путь для других устройств.
     */
    private volatile boolean gpuDelegate = false;

    private int bindAttempts;
    private long lastTimestampMs;
    private String candidate = GestureClassifier.NONE;
    private int candidateFrames;
    private String shown = GestureClassifier.NONE;

    private long frames;
    private long fpsWindowStart;
    private long conversionMs;
    private int convertedFrames;
    private boolean loggedGeometry;

    GestureWatcher(LifecycleOwner owner, PreviewView previewView, Listener listener) {
        this.owner = owner;
        this.context = previewView.getContext();
        this.previewView = previewView;
        this.listener = listener;
    }

    boolean isRunning() {
        return executor != null;
    }

    /** Запуск дежурства. Вызывать из главного потока. */
    void start() {
        if (executor != null) {
            return;
        }
        released = false;
        bindAttempts = 0;
        loggedGeometry = false;
        inferenceBusy = false;
        lastSubmitAt = 0;
        lastResultLatencyMs = -1;
        candidate = GestureClassifier.NONE;
        candidateFrames = 0;
        shown = GestureClassifier.NONE;
        frames = 0;
        fpsWindowStart = 0;
        conversionMs = 0;
        convertedFrames = 0;
        lastTimestampMs = 0;
        executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "gestures"));
        handler.postDelayed(this::openCamera, FIRST_BIND_DELAY_MS);
    }

    /** Остановка: отпускает фронтальную камеру и модель. Повторный вызов безопасен. */
    void stop() {
        released = true;
        handler.removeCallbacksAndMessages(null);
        ProcessCameraProvider provider = cameraProvider;
        cameraProvider = null;
        if (provider != null) {
            try {
                provider.unbindAll();
            } catch (Exception e) {
                Log.w(TAG, "unbindAll", e);
            }
        }
        // Модель закрываем в потоке анализа: там уже не останется кадров, а главный поток
        // не ждёт, пока MediaPipe доработает последний кадр.
        HandLandmarker current = landmarker;
        landmarker = null;
        ExecutorService exec = executor;
        executor = null;
        if (exec != null) {
            try {
                exec.execute(() -> closeQuietly(current));
            } catch (Exception ignored) {
                // Очередь уже закрыта — закроем здесь.
                closeQuietly(current);
            }
            exec.shutdown();
        } else {
            closeQuietly(current);
        }
    }

    private void closeQuietly(HandLandmarker model) {
        if (model == null) {
            return;
        }
        try {
            model.close();
        } catch (Exception e) {
            Log.w(TAG, "Не удалось закрыть модель жестов", e);
        }
    }

    // ---------------------------------------------------------------- камера

    private void openCamera() {
        if (released) {
            return;
        }
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            if (released) {
                return;
            }
            try {
                cameraProvider = future.get();
                bindFrontCamera();
            } catch (Exception e) {
                Log.e(TAG, "ProcessCameraProvider недоступен", e);
                retryBind();
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void bindFrontCamera() {
        try {
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetResolution(new Size(640, 480))
                    .build();
            analysis.setAnalyzer(executor, this::analyze);

            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(
                    owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
            bindAttempts = 0;
            Log.i(TAG, "Фронтальная камера привязана");
            ensureModel();
        } catch (Exception e) {
            Log.w(TAG, "Не удалось открыть фронтальную камеру", e);
            retryBind();
        }
    }

    private void retryBind() {
        if (released) {
            return;
        }
        if (++bindAttempts >= MAX_BIND_ATTEMPTS) {
            Log.e(TAG, "Фронтальная камера недоступна после " + bindAttempts + " попыток");
            listener.onCameraError();
            return;
        }
        Log.i(TAG, "Повторное открытие камеры: " + bindAttempts);
        handler.postDelayed(this::openCamera, BIND_RETRY_MS);
    }

    // ---------------------------------------------------------------- модель

    private void ensureModel() {
        if (landmarker != null || modelLoading || executor == null) {
            return;
        }
        modelLoading = true;
        ExecutorService exec = executor;
        exec.execute(() -> {
            if (released) {
                modelLoading = false;
                return;
            }
            HandLandmarker created = createModel(gpuDelegate ? Delegate.GPU : Delegate.CPU);
            if (created == null && gpuDelegate) {
                // На этой прошивке GPU-делегат может не создаться — тогда честно считаем на CPU.
                Log.w(TAG, "GPU-делегат не создался — переключаемся на CPU");
                gpuDelegate = false;
                created = createModel(Delegate.CPU);
            }
            if (released) {
                closeQuietly(created);
                modelLoading = false;
                return;
            }
            if (created != null) {
                landmarker = created;
                Log.i(TAG, "Модель жестов загружена: " + (gpuDelegate ? "GPU" : "CPU"));
            }
            modelLoading = false;
        });
    }

    /** Создаёт модель на выбранном делегате; при ошибке возвращает {@code null}. */
    private HandLandmarker createModel(Delegate delegate) {
        try {
            long started = SystemClock.uptimeMillis();
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .setDelegate(delegate)
                    .build();
            HandLandmarker.HandLandmarkerOptions options =
                    HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setNumHands(1)
                            // Ниже штатных 0.5: на слабом CPU важно быстрее поймать руку
                            // и дольше удерживать трекинг — иначе на каждом кадре снова
                            // запускается дорогой детектор ладони (~0,4 с).
                            .setMinHandDetectionConfidence(0.3f)
                            .setMinHandPresenceConfidence(0.3f)
                            .setMinTrackingConfidence(0.3f)
                            .setResultListener(this::onHandResult)
                            .setErrorListener(this::onModelError)
                            .build();
            HandLandmarker created = HandLandmarker.createFromOptions(context, options);
            Log.i(TAG, "Модель создана на " + delegate + " за "
                    + (SystemClock.uptimeMillis() - started) + " мс");
            return created;
        } catch (Exception e) {
            Log.e(TAG, "Не удалось создать модель на " + delegate, e);
            return null;
        }
    }

    /** Ошибка графа MediaPipe: если виноват GPU, пересоздаём модель на CPU. */
    private void onModelError(RuntimeException e) {
        Log.e(TAG, "Ошибка распознавания", e);
        if (!gpuDelegate || released) {
            return;
        }
        gpuDelegate = false;
        Log.w(TAG, "Ошибка GPU-делегата — пересоздаём модель на CPU");
        handler.post(() -> {
            if (released) {
                return;
            }
            ExecutorService exec = executor;
            if (exec == null) {
                return;
            }
            exec.execute(() -> {
                HandLandmarker old = landmarker;
                landmarker = null;
                closeQuietly(old);
                if (released) {
                    return;
                }
                HandLandmarker created = createModel(Delegate.CPU);
                if (created != null && !released) {
                    landmarker = created;
                    Log.i(TAG, "Модель пересобрана на CPU");
                }
            });
        });
    }

    /** Колбэк MediaPipe приходит не в главном потоке. */
    private void onHandResult(HandLandmarkerResult result, MPImage input) {
        inferenceBusy = false;
        if (released) {
            return;
        }
        lastResultLatencyMs = SystemClock.uptimeMillis() - lastSubmitAt;
        List<List<NormalizedLandmark>> hands = result.landmarks();
        boolean hasHand = hands != null && !hands.isEmpty();
        String gesture = hasHand ? GestureClassifier.classify(hands.get(0))
                : GestureClassifier.NONE;
        handSeen = hasHand;
        lastResult = gesture;
        handler.post(() -> onFrame(gesture));
    }

    // ---------------------------------------------------------------- анализ

    private void analyze(@NonNull ImageProxy image) {
        HandLandmarker current = landmarker;
        if (released || current == null) {
            image.close();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (inferenceBusy) {
            if (now - lastSubmitAt > INFERENCE_STUCK_MS) {
                // Граф отбросил кадр и не позвал колбэк — не залипаем навсегда.
                inferenceBusy = false;
            } else {
                image.close();
                return;
            }
        }
        inferenceBusy = true;
        lastSubmitAt = now;
        long convertStart = now;
        if (!loggedGeometry) {
            loggedGeometry = true;
            Log.i(TAG, "Кадр: " + image.getWidth() + "x" + image.getHeight()
                    + ", поворот=" + image.getImageInfo().getRotationDegrees());
        }
        Bitmap bitmap = null;
        try {
            bitmap = toBitmap(image);
        } catch (Exception e) {
            Log.w(TAG, "Не удалось разобрать кадр", e);
        } finally {
            image.close();
        }
        reportConversion(convertStart);
        if (bitmap == null) {
            inferenceBusy = false;
            return;
        }
        long timestamp = SystemClock.uptimeMillis();
        if (timestamp <= lastTimestampMs) {
            timestamp = lastTimestampMs + 1;
        }
        lastTimestampMs = timestamp;
        if (released) {
            inferenceBusy = false;
            return;
        }
        try {
            MPImage mpImage = new BitmapImageBuilder(bitmap).build();
            current.detectAsync(mpImage, timestamp);
        } catch (Exception e) {
            inferenceBusy = false;
            Log.w(TAG, "detectAsync", e);
        }
    }

    /** Диагностика: сколько времени уходит на YUV→ARGB и поворот (раз в 30 кадров). */
    private void reportConversion(long started) {
        conversionMs += SystemClock.uptimeMillis() - started;
        convertedFrames++;
        if (convertedFrames >= 30) {
            Log.i(TAG, "Конвертация кадра: " + (conversionMs / convertedFrames) + " мс");
            conversionMs = 0;
            convertedFrames = 0;
        }
    }

    private void onFrame(String gesture) {
        if (released) {
            return;
        }
        frames++;
        long now = SystemClock.elapsedRealtime();
        if (fpsWindowStart == 0) {
            fpsWindowStart = now;
        }
        if (now - fpsWindowStart >= FPS_LOG_PERIOD_MS) {
            Log.i(TAG, "Кадров/с=" + (frames * 1000 / (now - fpsWindowStart))
                    + ", задержка=" + lastResultLatencyMs + " мс"
                    + ", рука=" + (handSeen ? "да" : "нет")
                    + ", жест=" + lastResult);
            frames = 0;
            fpsWindowStart = now;
        }

        if (gesture.equals(candidate)) {
            candidateFrames++;
        } else {
            candidate = gesture;
            candidateFrames = 1;
        }
        if (candidateFrames < STABLE_FRAMES) {
            return;
        }
        if (!gesture.equals(shown)) {
            shown = gesture;
            Log.i(TAG, "Устойчивый жест: " + gesture);
            listener.onStableGesture(gesture);
        }
    }

    // ---------------------------------------------------------------- кадры

    /**
     * Кадр YUV_420_888 → ARGB-битмап, повёрнутый в нормальную ориентацию.
     *
     * <p>Модель чувствительна к повороту кисти, поэтому кадр разворачиваем по
     * {@code ImageInfo.getRotationDegrees()}.</p>
     */
    private static Bitmap toBitmap(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes.length < 3) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] yBytes = copy(planes[0]);
        byte[] uBytes = copy(planes[1]);
        byte[] vBytes = copy(planes[2]);
        int yRow = planes[0].getRowStride();
        int uvRow = planes[1].getRowStride();
        int uvPixel = planes[1].getPixelStride();

        int[] argb = new int[width * height];
        for (int row = 0; row < height; row++) {
            int yBase = row * yRow;
            int uvBase = (row >> 1) * uvRow;
            int outBase = row * width;
            for (int col = 0; col < width; col++) {
                int y = (yBytes[yBase + col] & 0xFF) - 16;
                int uvIndex = uvBase + (col >> 1) * uvPixel;
                int u = (uBytes[uvIndex] & 0xFF) - 128;
                int v = (vBytes[uvIndex] & 0xFF) - 128;
                if (y < 0) {
                    y = 0;
                }
                int r = (298 * y + 409 * v + 128) >> 8;
                int g = (298 * y - 100 * u - 208 * v + 128) >> 8;
                int b = (298 * y + 516 * u + 128) >> 8;
                argb[outBase + col] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
        return rotate(bitmap, image.getImageInfo().getRotationDegrees());
    }

    private static byte[] copy(ImageProxy.PlaneProxy plane) {
        ByteBuffer buffer = plane.getBuffer();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return data;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    private static Bitmap rotate(Bitmap source, int degrees) {
        if (degrees % 360 == 0) {
            return source;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(
                source, 0, 0, source.getWidth(), source.getHeight(), matrix, false);
        if (rotated != source) {
            source.recycle();
        }
        return rotated;
    }
}
