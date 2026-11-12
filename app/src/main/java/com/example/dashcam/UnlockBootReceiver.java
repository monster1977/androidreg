package com.example.dashcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Поднимает службу автооткрытия после перезагрузки телефона и после обновления приложения.
 *
 * <p>{@code BOOT_COMPLETED} и {@code MY_PACKAGE_REPLACED} входят в исключения для
 * implicit-широковещаний, поэтому доставляются манифест-приёмнику даже приложению,
 * которое давно не запускалось.</p>
 */
public class UnlockBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            UnlockWatcherService.sync(context);
        }
    }
}
