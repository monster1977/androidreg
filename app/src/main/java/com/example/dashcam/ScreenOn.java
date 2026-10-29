package com.example.dashcam;

import android.app.Activity;
import android.view.WindowManager;

/**
 * Управление подсветкой экрана: пока идёт запись, экран не гасится.
 *
 * <p>Флаг навешивается только на время записи, чтобы в простое приложение не жгло батарею.
 * Исключение — дежурный режим жестов: там фронтальная камера ждёт «V», и если экран погаснет,
 * активность остановится и жест не сработает.</p>
 */
public final class ScreenOn {

    private ScreenOn() {
    }

    public static void apply(Activity activity) {
        if (activity == null) {
            return;
        }
        if (RecordingService.isRecordingActive() || MainActivity.isGestureIdle()) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }
}
