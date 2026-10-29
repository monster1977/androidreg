package com.example.dashcam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExtendableBuilder;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.example.dashcam.effects.BrightnessOverlayEffect;
import com.example.dashcam.effects.OverlayFrame;
import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground-сервис, который держит камеру CameraX и пишет видео.
 *
 * <p>Сервис живёт, пока к нему привязана активность, и продолжает писать, когда приложение
 * уходит в фон: именно поэтому запись оформлена как foreground service с типом
 * camera/microphone.</p>
 *
 * <p>Дата/время и GPS-координаты вжигаются прямо в кадр через {@link BrightnessOverlayEffect}, поэтому
 * они остаются и в сохранённом файле, а не только на экране.</p>
 */
public class RecordingService extends Service implements LifecycleOwner, LocationListener {

    private static final String TAG = "RecordingService";

    public static final String ACTION_START = "com.example.dashcam.action.START";
    public static final String ACTION_STOP = "com.example.dashcam.action.STOP";

    private static final String CHANNEL_ID = "dashcam_recording";
    private static final int NOTIFICATION_ID = 101;

    /** Сколько раз пробуем привязать камеру, если она ещё занята (например, экраном жестов). */
    private static final int MAX_CAMERA_BIND_RETRIES = 4;
    private static final long CAMERA_BIND_RETRY_MS = 700;

    /**
     * Имя файла, который пишется прямо сейчас. Галерея его не показывает: MP4 без записанного
     * индекса (moov) не воспроизводится, пока запись не остановлена.
     */
    private static volatile String activeRecordingName;

    /** @return имя незавершённого файла или {@code null} */
    public static String getActiveRecordingName() {
        return activeRecordingName;
    }

    /** Идёт ли запись прямо сейчас — по этому флагу активности держат экран включённым. */
    private static volatile boolean recordingActive;

    public static boolean isRecordingActive() {
        return recordingActive;
    }

    /** Колбэки состояния для активности. */
    public interface Listener {
        void onCameraReady();

        void onRecordingStarted(String displayName);

        void onRecordingProgress(long elapsedMs);

        void onRecordingFinished(Uri uri, String displayName, long durationMs, boolean error);

        /** Запись не удалось начать (например, мало места) — показываем причину. */
        void onRecordingError(String message);
    }

    public class LocalBinder extends Binder {
        public RecordingService getService() {
            return RecordingService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * Тяжёлое I/O после финализации файла (чтение метаданных, обход MediaStore для кольца)
     * уходит с главного потока — иначе это кандидат на ANR (K4).
     */
    private final ExecutorService storageExecutor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "dashcam-storage"));

    /**
     * Собственный жизненный цикл: сервис сразу считается RESUMED, иначе CameraX не откроет
     * камеру до первого onStartCommand и превью осталось бы чёрным.
     */
    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);

    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private VideoCapture<Recorder> videoCapture;
    private BrightnessOverlayEffect overlayEffect;

    /** Текущая привязанная камера. */
    private Camera camera;

    private androidx.camera.video.Recording activeRecording;
    private Listener listener;

    private boolean cameraReady;
    private boolean recording;
    /** Сколько раз уже отчитались о фактическом режиме фокуса (логируем первые два). */
    private int focusResultsLogged;
    /** Сколько попыток привязки камеры уже сделано после неудачи. */
    private int cameraBindRetries;
    private boolean pendingStart;
    private boolean rotateRequested;
    private boolean overlayBurnedIn;
    private String currentName;
    private long recordingStartedAt;
    private String boundSignature;
    private String lastBindError = "";

    // Метка на кадре: дата/время и GPS-координаты.
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Частичный wakelock: не даём процессору уснуть, пока пишется видео. */
    private PowerManager.WakeLock recordingWakeLock;

    /**
     * Таймаут удержания CPU: блокировка снимается системой сама, если процесс подавлен
     * и продлить её некому. Пока запись идёт, мы снимаем её и берём снова — таймер
     * при повторном acquire сбрасывается (см. PowerManager.WakeLock.acquire(long)).
     */
    private static final long WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L;
    /** Как часто продлеваем блокировку (вдвое чаще таймаута — запас на задержки). */
    private static final long WAKELOCK_RENEW_MS = WAKELOCK_TIMEOUT_MS / 2;
    /** Как часто проверяем стоянку: фиксы и так приходят раз в секунду. */
    private static final long STATIONARY_CHECK_MS = 30_000L;

    private final SimpleDateFormat overlayFormat =
            new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault());
    private volatile String locationText = "";
    private LocationManager locationManager;
    private boolean locationUpdatesRequested;

    /**
     * Многие прошивки не заполняют скорость в GPS-фиксе (`hasSpeed() == false`), поэтому
     * считаем её сами по перемещению между соседними фиксами.
     */
    private Location lastGpsFix;
    private volatile float speedKmh;
    /** Время (elapsedRealtime), когда скорость обновлена последний раз — для свежести. */
    private volatile long lastSpeedAt;
    private volatile int locationUpdateCount;

    /** Стоянка: нет заметного движения — останавливаем запись и блокируем экран. */
    private final StationaryWatcher stationaryWatcher = new StationaryWatcher();
    /** Отсчёт стоянки уже начат для текущей записи (переживает ротацию сегментов). */
    private boolean stationaryWatchArmed;
    /** Остановка по стоянке уже выполнена — не повторяем до следующей записи. */
    private boolean stationaryTriggered;

    /** Сколько кадров реально прошло через наложение. 0 — вжигание не работает. */
    private volatile int overlayDrawCount;

    /**
     * Сколько сегментов подряд завершилось ошибкой. Сбрасывается после успешного файла.
     * Нужно, чтобы битый энкодер/камера не зациклили бесконечные перезапуски.
     */
    private int consecutiveSegmentErrors;
    /** Максимум попыток перезапустить запись после ошибки сегмента. */
    private static final int MAX_SEGMENT_RETRIES = 3;
    /** Пауза перед повторным запуском сегмента после ошибки. */
    private static final long SEGMENT_RETRY_DELAY_MS = 2000L;
    /** Сервис уже уничтожен: колбэки камеры больше не должны запускать запись. */
    private volatile boolean destroyed;

    /** Текст последней ошибки наложения — его видно в меню приложения. */
    private volatile String overlayError = "";
    private volatile boolean gpsEnabled = true;
    private HandlerThread overlayThread;
    private Handler overlayHandler;

    private final Runnable rotationRunnable = new Runnable() {
        @Override
        public void run() {
            if (recording && activeRecording != null) {
                Log.i(TAG, "Сегмент закончился — начинаем следующий файл");
                rotateRequested = true;
                activeRecording.stop();
            }
        }
    };

    /** Раз в полминуты проверяет, не пора ли остановиться из-за стоянки. */
    private final Runnable stationaryCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!recording || destroyed) {
                return;
            }
            checkStationary();
            handler.postDelayed(this, STATIONARY_CHECK_MS);
        }
    };

    /** Повторный запуск сегмента после ошибки: только если запись всё ещё нужна. */
    private final Runnable retryRecordingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!destroyed && Prefs.recordingDesired(RecordingService.this) && !recording) {
                Log.i(TAG, "Повторный запуск записи после ошибки");
                startRecording();
            }
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
        overlayPaint.setColor(Color.WHITE);
        overlayPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        overlayPaint.setShadowLayer(6f, 2f, 2f, Color.BLACK);
        createNotificationChannel();
        bindCameraAsync();
        // Координаты нужны и на экране до начала записи, поэтому подписываемся сразу.
        startLocationUpdates();
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        // Запуск из фона без нужных разрешений падает SecurityException/FSE — гасим здесь,
        // чтобы команда от зарядки не убивала процесс (K5).
        try {
            if (ACTION_START.equals(action)) {
                consecutiveSegmentErrors = 0;
                Prefs.setRecordingDesired(this, true);
                startForegroundCompat();
                startRecording();
            } else if (ACTION_STOP.equals(action)) {
                Prefs.setRecordingDesired(this, false);
                stopRecording(false);
            } else if (intent == null) {
                // Система перезапустила процесс (START_STICKY). Продолжаем запись, только
                // если пользователь её не останавливал и права камеры на месте.
                if (Prefs.recordingDesired(this) && hasCameraPermission()) {
                    Log.i(TAG, "Восстанавливаем запись после перезапуска сервиса");
                    consecutiveSegmentErrors = 0;
                    startForegroundCompat();
                    startRecording();
                } else {
                    Prefs.setRecordingDesired(this, false);
                    stopSelf();
                    return START_NOT_STICKY;
                }
            }
        } catch (SecurityException | IllegalStateException e) {
            Log.e(TAG, "Команда " + action + " не выполнена", e);
            Prefs.setRecordingDesired(this, false);
            try {
                stopForegroundCompat();
            } catch (Exception ignored) {
                // Foreground и так не поднялся.
            }
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(rotationRunnable);
        handler.removeCallbacks(retryRecordingRunnable);
        handler.removeCallbacks(stationaryCheckRunnable);
        stationaryWatchArmed = false;
        stationaryTriggered = false;
        storageExecutor.shutdownNow();
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        recording = false;
        activeRecordingName = null;
        recordingActive = false;
        releaseWakeLock();
        stopLocationUpdates();
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception e) {
                Log.w(TAG, "unbindAll failed", e);
            }
        }
        closeOverlayIfNeeded();
        listener = null;
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- camera

    private void bindCameraAsync() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                logConcurrentCameras();
                bindCamera();
                if (!cameraReady && !destroyed && cameraBindRetries < MAX_CAMERA_BIND_RETRIES) {
                    // Камера могла ещё не освободиться после экрана жестов.
                    cameraBindRetries++;
                    Log.i(TAG, "Камера занята, повтор привязки " + cameraBindRetries);
                    handler.postDelayed(this::bindCamera, CAMERA_BIND_RETRY_MS);
                }
            } catch (Exception e) {
                Log.e(TAG, "Не удалось получить ProcessCameraProvider", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** Диагностика: какие камеры телефон умеет держать одновременно (Android 11+). */
    private void logConcurrentCameras() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            Log.i(TAG, "Одновременные камеры: "
                    + (manager == null ? "нет CameraManager" : manager.getConcurrentCameraIds()));
        } catch (Exception e) {
            Log.w(TAG, "Не удалось узнать одновременные камеры", e);
        }
    }

    private void bindCamera() {
        if (cameraProvider == null || destroyed) {
            return;
        }
        Quality quality;
        switch (Prefs.quality(this)) {
            case "HD":
                quality = Quality.HD;
                break;
            case "UHD":
                quality = Quality.UHD;
                break;
            default:
                quality = Quality.FHD;
                break;
        }
        QualitySelector selector =
                QualitySelector.from(quality, FallbackStrategy.higherQualityOrLowerThan(quality));
        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(selector)
                .build();

        int lensFacing = "FRONT".equals(Prefs.cameraMode(this))
                ? CameraSelector.LENS_FACING_FRONT
                : CameraSelector.LENS_FACING_BACK;
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();

        // Автофокус основной камеры выключаем: в дороге он периодически «дышит»
        // и на несколько секунд уводит кадр в расфокус. Дашкам снимает дорогу,
        // поэтому фокус жёстко ставим на бесконечность (гиперфокал 0,33 м —
        // резко всё дальше ~30 см). Фронтальную камеру не трогаем.
        boolean fixedFocus = lensFacing == CameraSelector.LENS_FACING_BACK
                && supportsManualFocus(lensFacing);
        focusResultsLogged = 0;

        Preview.Builder previewBuilder = new Preview.Builder();
        VideoCapture.Builder<Recorder> videoBuilder = new VideoCapture.Builder<>(recorder);
        if (fixedFocus) {
            applyFixedFocus(previewBuilder);
            applyFixedFocus(videoBuilder);
        }
        preview = previewBuilder.build();
        videoCapture = videoBuilder.build();
        Log.i(TAG, fixedFocus
                ? "Автофокус выключен: фокус зафиксирован на бесконечности"
                : "Автофокус оставлен: камера без ручного фокуса или выбрана фронтальная");

        closeOverlayIfNeeded();
        overlayDrawCount = 0;
        overlayError = "";
        gpsEnabled = Prefs.gps(this);
        BrightnessOverlayEffect effect = null;
        if (Prefs.timestamp(this) || Prefs.gps(this) || Prefs.autoBright(this)) {
            // Эффект нужен и для метки, и для цифрового осветления кадра.
            effect = createOverlayEffect();
            if (effect != null) {
                // Яркость измеряем и осветляем прямо в эффекте: отдельный поток анализа
                // (ImageAnalysis) на MTK отнимал полосу и ронял разрешение записи.
                effect.setAutoBrightEnabled(Prefs.autoBright(this));
            }
        }

        boolean bound = tryBind(cameraSelector, effect);
        if (bound) {
            overlayEffect = effect;
            overlayBurnedIn = effect != null;
        } else if (effect != null) {
            // Эффект не поддержан — пишем без вжигания, метку покажем поверх превью.
            Log.w(TAG, "Эффект яркости/метки недоступен: " + lastBindError);
            if (overlayError.isEmpty()) {
                overlayError = lastBindError;
            }
            closeEffect(effect);
            overlayBurnedIn = false;
            bound = tryBind(cameraSelector, null);
        }

        cameraReady = bound;
        if (bound) {
            cameraBindRetries = 0;
            boundSignature = Prefs.cameraSignature(this);
            if (listener != null) {
                listener.onCameraReady();
            }
            if (pendingStart) {
                pendingStart = false;
                startRecording();
            }
        }
    }

    private boolean tryBind(CameraSelector cameraSelector, @Nullable BrightnessOverlayEffect effect) {
        try {
            camera = null;
            cameraProvider.unbindAll();
            if (effect != null) {
                // Эффект подключается к сессии через UseCaseGroup.
                UseCaseGroup group = new UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(videoCapture)
                        .addEffect(effect)
                        .build();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, group);
            } else {
                camera = cameraProvider.bindToLifecycle(this, cameraSelector,
                        preview, videoCapture);
            }
            // Уровень цифрового осветления живёт в эффекте; на свежем эффекте он уже задан.
            Log.i(TAG, "Камера привязана: автояркость "
                    + (Prefs.autoBright(this) ? "вкл" : "выкл")
                    + ", эффект=" + (effect != null));
            return true;
        } catch (Exception e) {
            camera = null;
            lastBindError = describe(e);
            Log.e(TAG, "Не удалось привязать камеру", e);
            return false;
        }
    }

    /**
     * Проверяет, что у выбранной камеры есть ручной фокус: режим {@code AF_MODE_OFF}
     * и ненулевая минимальная дистанция фокусировки (0 — камера с фиксированным объективом,
     * ей управлять нечем).
     */
    private boolean supportsManualFocus(int lensFacing) {
        ProcessCameraProvider provider = cameraProvider;
        if (provider == null) {
            return false;
        }
        try {
            for (CameraInfo info : provider.getAvailableCameraInfos()) {
                Camera2CameraInfo camera2Info = Camera2CameraInfo.from(info);
                Integer facing = camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != lensFacing) {
                    continue;
                }
                int[] afModes = camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                Float minDistance = camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                boolean afOff = false;
                if (afModes != null) {
                    for (int mode : afModes) {
                        if (mode == CaptureRequest.CONTROL_AF_MODE_OFF) {
                            afOff = true;
                            break;
                        }
                    }
                }
                return afOff && minDistance != null && minDistance > 0f;
            }
        } catch (Exception e) {
            Log.w(TAG, "Не удалось прочитать параметры фокуса камеры", e);
        }
        return false;
    }

    /** Отключает автофокус в конфигурации use case: {@code AF OFF} + дистанция 0 (бесконечность). */
    private void applyFixedFocus(ExtendableBuilder<?> builder) {
        new Camera2Interop.Extender<>(builder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                .setSessionCaptureCallback(focusReportCallback);
    }

    /**
     * Первые кадры после привязки печатают фактический режим фокуса из результата камеры:
     * так видно, принял ли HAL ручную фокусировку (AF=0) и на какой дистанции (0 — бесконечность).
     */
    private final CameraCaptureSession.CaptureCallback focusReportCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    if (focusResultsLogged >= 2) {
                        return;
                    }
                    focusResultsLogged++;
                    try {
                        Integer mode = result.get(CaptureResult.CONTROL_AF_MODE);
                        Float distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                        Log.i(TAG, "Фокус кадра: AF=" + mode + ", дистанция=" + distance);
                    } catch (Exception e) {
                        Log.w(TAG, "Не удалось прочитать режим фокуса из результата", e);
                    }
                }
            };

    /** Метка даты/времени и GPS + цифровое осветление кадра — в превью и в записанном файле. */
    private BrightnessOverlayEffect createOverlayEffect() {
        try {
            overlayThread = new HandlerThread("dashcam-overlay");
            overlayThread.start();
            overlayHandler = new Handler(overlayThread.getLooper());
            BrightnessOverlayEffect effect = new BrightnessOverlayEffect(
                    CameraEffect.PREVIEW | CameraEffect.VIDEO_CAPTURE,
                    overlayHandler,
                    (Consumer<Throwable>) throwable -> {
                        overlayError = describe(throwable);
                        Log.e(TAG, "Ошибка наложения метки на кадр", throwable);
                    });
            effect.setOnDrawListener(frame -> {
                Canvas canvas = frame.getOverlayCanvas();
                if (canvas == null) {
                    return false;
                }
                drawOverlay(canvas, frame);
                overlayDrawCount++;
                return true;
            });
            return effect;
        } catch (Exception e) {
            overlayError = describe(e);
            Log.w(TAG, "Не удалось создать эффект яркости/метки", e);
            return null;
        }
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        String message = throwable.getClass().getSimpleName();
        if (throwable.getMessage() != null && !throwable.getMessage().isEmpty()) {
            message += ": " + throwable.getMessage();
        }
        return message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }

    /**
     * Рисует метку внутри <b>видимой</b> части кадра.
     *
     * <p>Кадр с сенсора обычно шире/выше итогового видео: CameraX отдаёт буфер и прямоугольник
     * кадрирования. Если рисовать от края буфера, метка уходит в вырезанную полосу и не попадает
     * ни в превью, ни в файл — поэтому координаты считаем от {@code getCropRect()}, а поворот и
     * зеркалирование компенсируем, чтобы текст читался нормально.</p>
     */
    private void drawOverlay(Canvas canvas, OverlayFrame frame) {
        Rect crop = frame.getCropRect();
        if (crop == null || crop.width() <= 0 || crop.height() <= 0) {
            return;
        }
        int rotation = frame.getRotationDegrees();
        boolean mirror = frame.isMirroring();

        float centerX = crop.exactCenterX();
        float centerY = crop.exactCenterY();
        float halfWidth = crop.width() / 2f;
        float halfHeight = crop.height() / 2f;
        if (rotation == 90 || rotation == 270) {
            float swap = halfWidth;
            halfWidth = halfHeight;
            halfHeight = swap;
        }

        canvas.save();
        if (mirror) {
            canvas.scale(-1f, 1f, centerX, centerY);
        }
        if (rotation != 0) {
            canvas.rotate(-rotation, centerX, centerY);
        }

        // CameraX отдаёт буфер сенсора (например, 4:3), а превью и видео бывают уже (16:9):
        // края буфера в кадр не попадают. Поэтому держим метку в «безопасной» зоне —
        // примерно на 60% вниз от центра, а не у самого края буфера.
        float safeBottom = centerY + halfHeight * 0.62f;

        float shortSide = Math.min(halfWidth, halfHeight) * 2f;
        float textSize = Math.max(18f, shortSide / 26f);
        overlayPaint.setTextSize(textSize);
        float padding = textSize * 0.9f;
        float lineHeight = textSize * 1.45f;

        String gps = gpsEnabled ? locationText : "";
        String time = Prefs.timestamp(this) ? overlayFormat.format(new Date()) : "";

        if (gps.isEmpty() && time.isEmpty()) {
            canvas.restore();
            return;
        }

        // Ставим текст по центру видимой области: направление поворота на разных устройствах
        // отличается, а центрирование остаётся корректным в любом случае.
        float widest = time.isEmpty() ? 0f : overlayPaint.measureText(time);
        if (!gps.isEmpty()) {
            widest = Math.max(widest, overlayPaint.measureText(gps));
        }
        float x = centerX - widest / 2f;
        float baseline = safeBottom - padding;
        if (!gps.isEmpty()) {
            drawOverlayLine(canvas, gps, x, baseline, textSize);
            baseline -= lineHeight;
        }
        if (!time.isEmpty()) {
            drawOverlayLine(canvas, time, x, baseline, textSize);
        }

        canvas.restore();

        if (overlayDrawCount % 60 == 0) {
            Log.i(TAG, "Метка: canvas=" + canvas.getWidth() + "x" + canvas.getHeight()
                    + " crop=" + crop + " rotation=" + rotation + " mirror=" + mirror
                    + " center=" + centerX + "," + centerY
                    + " half=" + halfWidth + "x" + halfHeight
                    + " safeBottom=" + safeBottom
                    + " x=" + x + " baseline=" + baseline + " textSize=" + textSize);
        }
    }

    /** Строка метки с полупрозрачной подложкой — читается на любом фоне. */
    private void drawOverlayLine(Canvas canvas, String text, float x, float baseline, float textSize) {
        float width = overlayPaint.measureText(text);
        float pad = textSize * 0.35f;
        overlayBackgroundPaint.setColor(0x99000000);
        canvas.drawRoundRect(
                x - pad,
                baseline - textSize * 0.95f,
                x + width + pad,
                baseline + textSize * 0.3f,
                textSize * 0.25f,
                textSize * 0.25f,
                overlayBackgroundPaint);
        canvas.drawText(text, x, baseline, overlayPaint);
    }

    private void closeEffect(BrightnessOverlayEffect effect) {
        if (effect == null) {
            return;
        }
        try {
            effect.clearOnDrawListener();
            effect.close();
        } catch (Exception e) {
            Log.w(TAG, "Не удалось закрыть эффект яркости/метки", e);
        }
    }

    private void closeOverlayIfNeeded() {
        closeEffect(overlayEffect);
        overlayEffect = null;
        if (overlayThread != null) {
            overlayThread.quitSafely();
            overlayThread = null;
            overlayHandler = null;
        }
    }

    /**
     * Пересоздаёт сессию камеры, если пользователь сменил качество, камеру или метку на видео.
     *
     * <p>Переключатель GPS действует на живом сервисе сразу (K3): включили — подписываемся
     * на координаты, выключили — отписываемся; привязку камеры трогаем только когда состав
     * оверлея действительно изменился.</p>
     */
    public void reloadCameraIfNeeded() {
        if (cameraProvider == null) {
            return;
        }
        boolean gpsWanted = Prefs.gps(this);
        gpsEnabled = gpsWanted;
        // Координаты нужны и для метки на кадре, и для определения стоянки.
        boolean locationWanted = gpsWanted || Prefs.stationaryStop(this);
        if (locationWanted) {
            if (!locationUpdatesRequested) {
                startLocationUpdates();
            }
        } else if (locationUpdatesRequested) {
            stopLocationUpdates();
        }
        // Автояркость переключается «на лету»: эффект уже создан, если его требует метка
        // времени/GPS или сама автояркость. Если автояркость включили с нуля (без метки),
        // отпечаток настроек изменится, и ниже камера перепривяжется с новым эффектом.
        if (overlayEffect != null) {
            overlayEffect.setAutoBrightEnabled(Prefs.autoBright(this));
        }
        if (recording) {
            return;
        }
        if (Prefs.cameraSignature(this).equals(boundSignature)) {
            return;
        }
        bindCamera();
    }

    public Preview getPreview() {
        return preview;
    }

    public boolean isCameraReady() {
        return cameraReady;
    }

    public boolean isRecording() {
        return recording;
    }

    /** true — метка реально вжигается в кадр; false — показываем её только поверх превью. */
    public boolean isTimestampBurnedIn() {
        return overlayDrawCount > 0;
    }

    /** Текст последней ошибки наложения (пусто, если ошибок не было). */
    public String getOverlayError() {
        return overlayError;
    }

    public long getRecordingElapsedMs() {
        return recording ? System.currentTimeMillis() - recordingStartedAt : 0L;
    }

    public String getLocationText() {
        return locationText;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Сообщает активности, почему запись не стартовала. */
    private void notifyRecordingError(String message) {
        if (listener != null) {
            listener.onRecordingError(message);
        }
    }

    // ---------------------------------------------------------------- recording

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void startRecording() {
        if (destroyed || recording) {
            return;
        }
        if (!cameraReady || videoCapture == null) {
            pendingStart = true;
            return;
        }
        if (!hasCameraPermission()) {
            Log.w(TAG, "Нет разрешения на камеру");
            pendingStart = true;
            return;
        }

        // Свободное место проверяем заранее: пишем в 1080p примерно 100–150 МБ в минуту,
        // и лучше честно предупредить, чем получить битый файл через пару минут.
        long free = StorageUtils.freeBytesFor(this);
        if (free >= 0 && free < StorageUtils.MIN_FREE_BYTES) {
            String message = getString(R.string.error_no_space, StorageUtils.formatSize(free));
            Log.e(TAG, message);
            Prefs.setRecordingDesired(this, false);
            stopForegroundCompat();
            stopSelf();
            notifyRecordingError(message);
            return;
        }

        String name = StorageUtils.NAME_PREFIX
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".mp4";
        currentName = name;

        ContentValues values = StorageUtils.newVideoValues(name, System.currentTimeMillis());
        MediaStoreOutputOptions outputOptions = new MediaStoreOutputOptions.Builder(
                getContentResolver(), StorageUtils.recordingCollection(this))
                .setContentValues(values)
                .build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, outputOptions);
        boolean audio = Prefs.audio(this)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (audio) {
            pending = pending.withAudioEnabled();
        }

        try {
            activeRecording = pending.start(ContextCompat.getMainExecutor(this), this::onVideoEvent);
        } catch (Exception e) {
            Log.e(TAG, "Не удалось начать запись", e);
            currentName = null;
            activeRecordingName = null;
            consecutiveSegmentErrors++;
            // Транзиентный сбой камеры/энкодера не должен убивать запись: пробуем ещё раз,
            // но не бесконечно и только если пользователь запись не отменял.
            if (Prefs.recordingDesired(this) && consecutiveSegmentErrors <= MAX_SEGMENT_RETRIES) {
                Log.w(TAG, "Повтор запуска записи через " + SEGMENT_RETRY_DELAY_MS
                        + " мс, попытка " + consecutiveSegmentErrors);
                handler.postDelayed(retryRecordingRunnable, SEGMENT_RETRY_DELAY_MS);
            } else {
                Prefs.setRecordingDesired(this, false);
                stopForegroundCompat();
                stopSelf();
                notifyRecordingError(getString(R.string.recording_failed));
            }
            return;
        }
        recording = true;
        recordingActive = true;
        recordingStartedAt = System.currentTimeMillis();
        rotateRequested = false;
        activeRecordingName = name;
        // Первый старт в этой записи — начинаем отсчёт стоянки. Ротация сегментов
        // (и повтор после ошибки) не должна сбрасывать его, иначе таймаут не наступит.
        if (!stationaryWatchArmed) {
            stationaryWatcher.reset(SystemClock.elapsedRealtime());
            stationaryWatchArmed = true;
        }
        stationaryTriggered = false;
        acquireWakeLock();
        scheduleRotation();
        startLocationUpdates();
        handler.removeCallbacks(stationaryCheckRunnable);
        handler.postDelayed(stationaryCheckRunnable, STATIONARY_CHECK_MS);
        if (listener != null) {
            listener.onRecordingStarted(name);
        }
    }

    /**
     * @param rotate true — после финализации файла сразу начинается следующий сегмент
     */
    public void stopRecording(boolean rotate) {
        rotateRequested = rotate;
        if (!rotate) {
            // Осознанная остановка: sticky-сервис не должен поднимать запись заново.
            Prefs.setRecordingDesired(this, false);
            consecutiveSegmentErrors = 0;
            handler.removeCallbacks(retryRecordingRunnable);
            handler.removeCallbacks(stationaryCheckRunnable);
            stationaryWatchArmed = false;
            stationaryTriggered = false;
            stationaryWatcher.reset(SystemClock.elapsedRealtime());
        }
        if (!recording || activeRecording == null) {
            if (rotate) {
                startRecording();
            } else {
                releaseWakeLock();
                stopForegroundCompat();
                stopSelf();
            }
            return;
        }
        handler.removeCallbacks(rotationRunnable);
        activeRecording.stop();
    }

    private void scheduleRotation() {
        handler.removeCallbacks(rotationRunnable);
        int minutes = Prefs.segmentMinutes(this);
        if (minutes > 0) {
            handler.postDelayed(rotationRunnable, minutes * 60_000L);
        }
    }

    private void onVideoEvent(VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Status) {
            long elapsed = ((VideoRecordEvent.Status) event)
                    .getRecordingStats().getRecordedDurationNanos() / 1_000_000L;
            if (listener != null) {
                listener.onRecordingProgress(elapsed);
            }
            return;
        }
        if (!(event instanceof VideoRecordEvent.Finalize)) {
            return;
        }

        VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
        boolean error = finalize.hasError();
        String finishedName = currentName;
        Uri finishedUri = finalize.getOutputResults() != null
                ? finalize.getOutputResults().getOutputUri()
                : null;
        long duration = finalize.getRecordingStats().getRecordedDurationNanos() / 1_000_000L;

        handler.removeCallbacks(rotationRunnable);
        activeRecording = null;
        recording = false;
        recordingActive = false;
        activeRecordingName = null;

        boolean retry = false;
        if (error) {
            Log.e(TAG, "Запись завершилась с ошибкой: код " + finalize.getError(), finalize.getCause());
            if (finishedUri != null) {
                // Битый (часто пустой) файл в галерее не нужен.
                try {
                    getContentResolver().delete(finishedUri, null, null);
                } catch (Exception e) {
                    Log.w(TAG, "Не удалось удалить битую запись", e);
                }
            }
            consecutiveSegmentErrors++;
            // Одна ошибка не должна обрывать регистрацию: пробуем поднять запись снова,
            // но ограниченно, чтобы не зациклиться на сломанной камере.
            retry = !destroyed && Prefs.recordingDesired(this)
                    && consecutiveSegmentErrors <= MAX_SEGMENT_RETRIES;
        } else {
            consecutiveSegmentErrors = 0;
            // isPlayable читает метаданные, prune обходит MediaStore — переносим в фон (K4).
            storageExecutor.execute(() -> {
                if (finishedUri != null && !isPlayable(finishedUri)) {
                    // Файл записан, но индекс MP4 не читается — предупредим в логе.
                    Log.w(TAG, "Файл не читается как видео: " + finishedName);
                }
                // Кольцо: удаляем самые старые сегменты по лимиту времени и объёма.
                // Только что закрытый файл не трогаем: пользователь мог нажать «стоп» сразу.
                StorageUtils.prune(RecordingService.this, finishedName);
            });
        }

        boolean continueRotation = rotateRequested && !error && !destroyed;
        rotateRequested = false;
        currentName = null;

        if (listener != null) {
            listener.onRecordingFinished(finishedUri, finishedName, duration, error);
        }

        if (retry) {
            Log.w(TAG, "Перезапуск записи через " + SEGMENT_RETRY_DELAY_MS
                    + " мс, попытка " + consecutiveSegmentErrors);
            handler.postDelayed(retryRecordingRunnable, SEGMENT_RETRY_DELAY_MS);
            return;
        }

        if (continueRotation) {
            startRecording();
        } else {
            releaseWakeLock();
            stopForegroundCompat();
            stopSelf();
        }
    }

    /** Держим процессор активным, пока идёт запись (экран при этом может быть погашен). */
    private void acquireWakeLock() {
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null) {
                return;
            }
            if (recordingWakeLock == null) {
                recordingWakeLock = power.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "dashcam:recording");
                recordingWakeLock.setReferenceCounted(false);
            }
            if (!recordingWakeLock.isHeld()) {
                recordingWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                Log.i(TAG, "Удерживаем CPU во время записи");
            }
            // Продлеваем по таймеру: если этот runnable не выполнится (убили сервис),
            // блокировка снимется сама через таймаут и не утечёт.
            handler.removeCallbacks(wakeLockRenewRunnable);
            handler.postDelayed(wakeLockRenewRunnable, WAKELOCK_RENEW_MS);
        } catch (Exception e) {
            Log.w(TAG, "Не удалось удержать CPU во время записи", e);
        }
    }

    private final Runnable wakeLockRenewRunnable = new Runnable() {
        @Override
        public void run() {
            if (!recording || recordingWakeLock == null) {
                return;
            }
            try {
                // Повторный acquire сбрасывает таймер блокировки.
                recordingWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                handler.postDelayed(this, WAKELOCK_RENEW_MS);
            } catch (Exception e) {
                Log.w(TAG, "Не удалось продлить удержание CPU", e);
            }
        }
    };

    private void releaseWakeLock() {
        handler.removeCallbacks(wakeLockRenewRunnable);
        try {
            if (recordingWakeLock != null && recordingWakeLock.isHeld()) {
                recordingWakeLock.release();
                Log.i(TAG, "Отпустили CPU после записи");
            }
        } catch (Exception e) {
            Log.w(TAG, "Не удалось отпустить CPU", e);
        }
    }

    /** Проверяет, что запись читается как видео: индекс MP4 (moov) записан полностью. */    private boolean isPlayable(Uri uri) {
        if (uri == null) {
            return false;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return duration != null && Long.parseLong(duration) > 0L;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Уже освобождён.
            }
        }
    }

    // ---------------------------------------------------------------- gps

    private void startLocationUpdates() {
        // Стоянка работает даже с выключенной GPS-меткой на видео.
        if ((!Prefs.gps(this) && !Prefs.stationaryStop(this)) || locationUpdatesRequested) {
            return;
        }
        boolean fine = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            Log.i(TAG, "GPS-метка выключена: нет разрешения на геолокацию");
            return;
        }
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            return;
        }
        // Точные координаты — GPS, приблизительные (или если GPS выключен) — по сети.
        String[] providers = fine
                ? new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}
                : new String[]{LocationManager.NETWORK_PROVIDER};
        for (String provider : providers) {
            try {
                if (!locationManager.isProviderEnabled(provider)) {
                    continue;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    locationManager.requestLocationUpdates(provider, 1000L, 0f, this,
                            Looper.getMainLooper());
                } else {
                    locationManager.requestLocationUpdates(provider, 1000L, 0f, this);
                }
                locationUpdatesRequested = true;
                Log.i(TAG, "Подписались на " + provider);
            } catch (SecurityException e) {
                Log.w(TAG, "Нет доступа к провайдеру " + provider, e);
            } catch (Exception e) {
                Log.w(TAG, "Не удалось подписаться на " + provider, e);
            }
        }
        if (!locationUpdatesRequested) {
            Log.i(TAG, "GPS выключен в настройках телефона");
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null && locationUpdatesRequested) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception e) {
                Log.w(TAG, "Не удалось отписаться от GPS", e);
            }
        }
        locationUpdatesRequested = false;
        locationText = "";
        lastGpsFix = null;
        speedKmh = 0f;
        lastSpeedAt = 0L;
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        long now = SystemClock.elapsedRealtime();
        // Стоянку считаем даже при выключенной GPS-метке на видео.
        if (recording && !stationaryTriggered && Prefs.stationaryStop(this)) {
            if (stationaryWatcher.onLocation(location.getLatitude(), location.getLongitude(),
                    location.getAccuracy(), now)) {
                Log.i(TAG, "Стоянка: координаты изменились, отсчёт заново");
            }
        }
        if (!Prefs.gps(this)) {
            locationText = "";
            return;
        }
        boolean fromGps = LocationManager.GPS_PROVIDER.equals(location.getProvider());
        boolean speedFresh = false;

        if (location.hasSpeed() && location.getSpeed() >= 0f) {
            // Провайдер сам отдал скорость (GPS на части прошивок это умеет).
            speedKmh = location.getSpeed() * 3.6f;
            speedFresh = true;
        } else if (fromGps && lastGpsFix != null) {
            // Скорости в фиксе нет — считаем по перемещению между соседними GPS-фиксами.
            long dtMs = (location.getElapsedRealtimeNanos()
                    - lastGpsFix.getElapsedRealtimeNanos()) / 1_000_000L;
            if (dtMs >= 1000L && dtMs <= 30_000L) {
                float speedMs = lastGpsFix.distanceTo(location) / (dtMs / 1000f);
                speedKmh = Math.max(0f, speedMs) * 3.6f;
                speedFresh = true;
            }
        }
        if (fromGps) {
            lastGpsFix = location;
        }
        if (speedFresh) {
            lastSpeedAt = now;
            speedKmh = Math.round(speedKmh); // округляем, чтобы строка не дёргалась
        }

        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.US, "%.5f, %.5f",
                location.getLatitude(), location.getLongitude()));
        // Скорость свежая (GPS обновляется раз в секунду) — дописываем; сетевой фикс
        // скорости не даёт, но и не затирает последнюю GPS-скорость.
        if (now - lastSpeedAt <= 15_000L) {
            text.append(String.format(Locale.US, "   %.0f км/ч", speedKmh));
        }
        locationText = text.toString();

        // Диагностика: раз в 20 обновлений пишем провайдера и скорость в лог.
        if (++locationUpdateCount % 20 == 0) {
            Log.i(TAG, "GPS fix: provider=" + location.getProvider()
                    + " hasSpeed=" + location.hasSpeed()
                    + " speedKmh=" + speedKmh + " text=" + locationText);
        }
    }

    /**
     * Стоянка: координаты не меняются дольше выбранного времени. Останавливаем запись
     * (файл финализируется в фоне) и блокируем экран, если приложение — админ устройства.
     */
    private void checkStationary() {
        if (!recording || stationaryTriggered || !Prefs.stationaryStop(this)) {
            return;
        }
        int minutes = Math.max(1, Prefs.stationaryMinutes(this));
        long now = SystemClock.elapsedRealtime();
        if (!stationaryWatcher.shouldStop(now, minutes * 60_000L)) {
            return;
        }
        stationaryTriggered = true;
        Log.i(TAG, "Стоянка: координат нет движения " + minutes
                + " мин — останавливаю запись и блокирую экран");
        stopRecording(false);
        if (!LockAdminReceiver.lock(this)) {
            Log.w(TAG, "Стоянка: экран не заблокирован — нет прав администратора (Настройки)");
            Toast.makeText(this, R.string.stationary_lock_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- notification

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_recording),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.channel_recording_desc));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, RecordingService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_recording)
                .setContentTitle(getString(R.string.notification_recording))
                .setContentText(getString(R.string.notification_recording_text))
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentIntent)
                .addAction(0, getString(R.string.action_stop), stopPendingIntent)
                .build();
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // На Android 11+ тип нужно указать явно, на 10 и ниже система берёт его из манифеста.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType());
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    /** Тип foreground-сервиса: микрофон добавляем только если он реально нужен и разрешён. */
    @SuppressLint("InlinedApi")
    private int foregroundServiceType() {
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        boolean audio = Prefs.audio(this)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (audio) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        return type;
    }

    private void stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }
}

