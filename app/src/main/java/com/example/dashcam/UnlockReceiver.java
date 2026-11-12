package com.example.dashcam;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Открывает регистратор после разблокировки телефона и при включении экрана без блокировки.
 *
 * <p>Приёмник не объявлен в манифесте: начиная с Android 8 система не доставляет
 * implicit-широковещание {@code ACTION_USER_PRESENT} манифест-приёмникам приложения,
 * которое не находится на переднем плане (в логе — «Background execution not allowed»,
 * у интента нет флага {@code FLAG_RECEIVER_INCLUDE_BACKGROUND}). Поэтому экземпляр этого
 * класса регистрируется динамически из {@link UnlockWatcherService}: пока служба жива,
 * широковещание приходит даже при свёрнутом приложении.</p>
 *
 * <p>{@code ACTION_SCREEN_ON} обрабатывается для телефонов с отключённым экраном блокировки:
 * тогда нажатие кнопки питания сразу показывает последний экран, но регистратор должен
 * выйти на передний план сам. Если блокировка включена, ждём {@code USER_PRESENT}.</p>
 *
 * <p>Запуск активности из фона Android 10+ разрешает только приложениям с доступом
 * «Поверх других приложений» ({@code SYSTEM_ALERT_WINDOW}). Без него вместо открытия
 * показываем уведомление: один тап открывает приложение.</p>
 */
public class UnlockReceiver extends BroadcastReceiver {

    private static final String TAG = "UnlockReceiver";
    private static final String CHANNEL_ID = "dashcam_unlock";
    private static final int NOTIFICATION_ID = 203;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        if (!Prefs.autoOpenOnUnlock(context)) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_USER_PRESENT.equals(action)) {
            Log.i(TAG, "Телефон разблокирован — открываем регистратор");
            open(context);
            return;
        }
        if (Intent.ACTION_SCREEN_ON.equals(action)) {
            KeyguardManager keyguard =
                    (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguard != null && keyguard.isKeyguardLocked()) {
                // Блокировка ещё на экране — приложение откроется по USER_PRESENT.
                return;
            }
            Log.i(TAG, "Экран включён без блокировки — открываем регистратор");
            open(context);
        }
    }

    /** Открывает главный экран либо, если система не разрешает, показывает подсказку. */
    static void open(Context context) {
        Intent open = new Intent(context, MainActivity.class);
        // CLEAR_TOP возвращает главный экран, даже если сверху были настройки или галерея.
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(open);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Не удалось открыть приложение напрямую", e);
            }
        }
        // Без overlay-разрешения система всё равно заблокирует запуск — предлагаем тап.
        showOpenPrompt(context, open);
    }

    private static void showOpenPrompt(Context context, Intent open) {
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Нет разрешения на уведомления — подсказка об открытии не видна");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.channel_unlock),
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription(context.getString(R.string.channel_unlock_desc));
                manager.createNotificationChannel(channel);
            }

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context, 1, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_recording)
                            .setContentTitle(context.getString(R.string.unlock_notification_title))
                            .setContentText(context.getString(R.string.unlock_notification_text))
                            .setStyle(new NotificationCompat.BigTextStyle()
                                    .bigText(context.getString(R.string.unlock_notification_text)))
                            .setAutoCancel(true)
                            .setContentIntent(pendingIntent);
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Log.w(TAG, "Не удалось показать подсказку об открытии", e);
        }
    }
}
