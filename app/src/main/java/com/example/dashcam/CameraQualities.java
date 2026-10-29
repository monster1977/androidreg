package com.example.dashcam;

import android.content.Context;
import android.util.Log;

import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.DynamicRange;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.Quality;
import androidx.camera.video.Recorder;
import androidx.camera.video.VideoCapabilities;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Доступные варианты качества записи для выбранной камеры.
 *
 * <p>Список берём у CameraX ({@link Recorder#getVideoCapabilities}) — это тот же источник,
 * по которому потом выбирается качество записи. Проверка через Camera2
 * ({@code StreamConfigurationMap.getOutputSizes(MediaRecorder.class)}) для этого не годится:
 * она отдаёт и 4:3-размеры вроде 4160×3120, которых CameraX для видео не использует,
 * и 4K попадал в список даже там, где запись всё равно откатывалась к 1080p.
 *
 * <p>Загрузка асинхронная: {@link ProcessCameraProvider} может инициализироваться не сразу.
 * До результата показываем безопасный минимум HD/FHD; при ошибке он же и остаётся.
 */
public final class CameraQualities {

    private static final String TAG = "CameraQualities";

    public static final String HD = "HD";
    public static final String FHD = "FHD";
    public static final String UHD = "UHD";

    private static final List<String> FALLBACK = Arrays.asList(HD, FHD);

    /**
     * Опрос возможностей камеры — это IPC к camera-сервису, и он может подвиснуть на секунды,
     * если камера занята записью. Держим его подальше от главного потока: иначе открытие
     * настроек во время записи блокирует UI и приводит к ANR.
     */
    private static final ExecutorService QUERY_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "camera-qualities");
        thread.setDaemon(true);
        return thread;
    });

    /** Сколько ждём ответа camera-сервиса, прежде чем показать безопасный список. */
    private static final long QUERY_TIMEOUT_SEC = 3;

    /** Последний удачный ответ: настройки открываются мгновенно даже во время записи. */
    private static volatile List<String> cached;
    private static volatile String cachedMode;

    private CameraQualities() {
    }

    /** Колбэк вызывается на главном потоке. */
    public interface Callback {
        void onAvailable(List<String> qualities);
    }

    /**
     * Асинхронно узнаёт, какие качества поддерживает текущая камера.
     *
     * <p>Весь опрос идёт в фоновом потоке. Если камера занята записью, обращения к camera-сервису
     * могут подвиснуть на секунды; раньше это блокировало главный поток и открытие настроек
     * во время записи заканчивалось ANR («does not have a focused window»). Главный поток здесь
     * только получает готовый список, поэтому настройки открываются мгновенно.</p>
     */
    public static void load(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        Executor mainExecutor = ContextCompat.getMainExecutor(app);
        String mode = Prefs.cameraMode(app);
        List<String> known = cached;
        if (known != null && mode.equals(cachedMode)) {
            mainExecutor.execute(() -> callback.onAvailable(known));
            return;
        }
        QUERY_EXECUTOR.execute(() -> {
            List<String> result = query(app);
            Log.i(TAG, "Качества для камеры " + mode + ": " + result);
            if (result == null) {
                result = new ArrayList<>(FALLBACK);
            } else {
                cached = result;
                cachedMode = mode;
            }
            List<String> answer = result;
            mainExecutor.execute(() -> callback.onAvailable(answer));
        });
    }

    /** @return поддерживаемые качества или {@code null}, если камера не ответила вовремя. */
    private static List<String> query(Context app) {
        try {
            ProcessCameraProvider provider = ProcessCameraProvider.getInstance(app)
                    .get(QUERY_TIMEOUT_SEC, TimeUnit.SECONDS);
            int wantedFacing = "FRONT".equals(Prefs.cameraMode(app))
                    ? CameraSelector.LENS_FACING_FRONT
                    : CameraSelector.LENS_FACING_BACK;
            for (CameraInfo info : provider.getAvailableCameraInfos()) {
                if (info.getLensFacing() != wantedFacing) {
                    continue;
                }
                VideoCapabilities capabilities = Recorder.getVideoCapabilities(info);
                List<Quality> supported =
                        capabilities.getSupportedQualities(DynamicRange.SDR);
                List<String> mapped = new ArrayList<>();
                // Порядок — по возрастанию, как в настройках.
                if (supported.contains(Quality.HD)) {
                    mapped.add(HD);
                }
                if (supported.contains(Quality.FHD)) {
                    mapped.add(FHD);
                }
                if (supported.contains(Quality.UHD)) {
                    mapped.add(UHD);
                }
                return mapped.isEmpty() ? null : mapped;
            }
        } catch (Exception e) {
            Log.w(TAG, "Не удалось узнать качества камеры", e);
        }
        return null;
    }

    /** Подпись для настроек. */
    public static String label(String quality) {
        switch (quality) {
            case HD:
                return "HD 720p";
            case UHD:
                return "4K 2160p";
            default:
                return "Full HD 1080p";
        }
    }
}
