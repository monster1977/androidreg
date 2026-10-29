package com.example.dashcam;

import android.app.Activity;
import android.content.pm.ActivityInfo;

/**
 * Приложение — автомобильный регистратор, поэтому всегда работает в ландшафте.
 *
 * <p>Переключатель в меню выбирает прямой или обратный ландшафт: это позволяет расположить
 * телефон так, чтобы камера и разъём зарядки были слева или справа по желанию.</p>
 */
public final class ScreenOrientation {

    private ScreenOrientation() {
    }

    public static void apply(Activity activity) {
        activity.setRequestedOrientation(Prefs.landscapeReversed(activity)
                ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }
}
