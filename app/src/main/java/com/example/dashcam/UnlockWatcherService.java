package com.example.dashcam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Держит приёмник разблокировки зарегистрированным, пока включена настройка
 * «Открывать при разблокировке».
 *
 * <p>Манифест-приёмник для {@code ACTION_USER_PRESENT} бесполезен: Android 8+ не доставляет
 * это implicit-широковещание приложению в фоне. Динамически зарегистрированный приёмник
 * такого ограничения не имеет, но живёт только вместе с процессом. Поэтому функция
 * реализована как foreground-служба: система держит её процесс живым и перезапускает
 * после смерти (START_STICKY), а после перезагрузки или обновления приложения службу
 * поднимает {@link UnlockBootReceiver}.</p>
 */
public class UnlockWatcherService extends Service {

    private static final String TAG = "UnlockWatcher";
    private static final String CHANNEL_ID = "dashcam_unlock_watch";
    private static final int NOTIFICATION_ID = 204;

    private BroadcastReceiver unlockReceiver;

    /** Приводит состояние службы в соответствие с настройкой. */
    public static void sync(Context context) {
        if (Prefs.autoOpenOnUnlock(context)) {
            start(context);
        } else {
            stop(context);
        }
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, UnlockWatcherService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Не удалось запустить службу разблокировки", e);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, UnlockWatcherService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        unlockReceiver = new UnlockReceiver();
        // Регистрация вручную: регистрировать приёмник нужно в живом процессе,
        // а широковещание приходит от системы, поэтому экспорт не требуется.
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        if (!Prefs.autoOpenOnUnlock(this)) {
            // Настройку выключили, пока служба перезапускалась системой.
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (unlockReceiver != null) {
            try {
                unregisterReceiver(unlockReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Приёмник уже отписан", e);
            }
            unlockReceiver = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_unlock_watch),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_unlock_watch_desc));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_recording)
                .setContentTitle(getString(R.string.unlock_watch_title))
                .setContentText(getString(R.string.unlock_watch_text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }
}
