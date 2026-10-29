package com.example.dashcam;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Автозапись при подключении зарядки.
 *
 * <p>Приёмник объявлен и в манифесте, и регистрируется динамически, пока приложение живо:
 * часть прошивок не доставляет {@code ACTION_POWER_CONNECTED} манифест-приёмникам, и второй
 * канал повышает шансы, что запись действительно начнётся.</p>
 *
 * <p>На Android 11 и ниже сервис записи можно поднять прямо отсюда. Начиная с Android 12
 * запускать foreground-сервис с типом camera из фона запрещено, поэтому показываем уведомление:
 * тап по нему открывает приложение и сразу включает запись.</p>
 */
public class PowerConnectionReceiver extends BroadcastReceiver {

    private static final String TAG = "PowerReceiver";
    private static final String CHANNEL_ID = "dashcam_power";
    private static final int NOTIFICATION_ID = 202;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
            return;
        }
        handle(context, false);
    }

    /**
     * @param appVisible true — вызов из работающего приложения (можно запускать сервис напрямую)
     */
    public static void handle(Context context, boolean appVisible) {
        if (!Prefs.autoStartOnCharge(context)) {
            Log.i(TAG, "Автозапись от зарядки выключена в настройках");
            return;
        }
        Log.i(TAG, "Подключено питание — включаем автозапись (appVisible=" + appVisible + ")");

        // Экран в дежурном режиме держит фронтальную камеру: основная не откроется, пока её
        // не отпустят. Просим активность сама остановить дежурство и запустить запись.
        if (MainActivity.isGestureIdle()) {
            Log.i(TAG, "Экран ждёт жест — просим активность начать запись");
            Intent ask = new Intent(MainActivity.ACTION_START_FROM_IDLE);
            ask.setPackage(context.getPackageName());
            context.sendBroadcast(ask);
            return;
        }

        boolean canStartDirectly = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || appVisible;
        if (canStartDirectly) {
            // Без разрешения на камеру сервис либо упадёт, либо повиснет в ожидании (K5):
            // вместо этого открываем приложение, где разрешения запросят.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Нет разрешения на камеру — запись невозможна, показываем подсказку");
                showStartPrompt(context);
                return;
            }
            Intent start = new Intent(context, RecordingService.class);
            start.setAction(RecordingService.ACTION_START);
            try {
                ContextCompat.startForegroundService(context, start);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Не удалось запустить запись напрямую", e);
            }
        }
        showStartPrompt(context);
    }

    private static void showStartPrompt(Context context) {
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            // На Android 13+ без POST_NOTIFICATIONS уведомление просто не показывается —
            // фиксируем в логе, иначе автозапись молча «теряется» (K6).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Нет разрешения на уведомления (POST_NOTIFICATIONS): подсказка "
                        + "об автозаписи не будет видна. Разрешите уведомления при первом "
                        + "открытии приложения.");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.channel_power),
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription(context.getString(R.string.channel_power_desc));
                manager.createNotificationChannel(channel);
            }

            Intent open = new Intent(context, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            open.putExtra(MainActivity.EXTRA_AUTO_RECORD, true);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_recording)
                    .setContentTitle(context.getString(R.string.power_notification_title))
                    .setContentText(context.getString(R.string.power_notification_text))
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent);
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Log.w(TAG, "Не удалось показать уведомление о зарядке", e);
        }
    }
}
