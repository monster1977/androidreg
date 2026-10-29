package com.example.dashcam;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Администратор устройства ради одного права — {@code lockNow()}: при стоянке приложение
 * останавливает запись и блокирует экран. Пользователь включает его один раз в настройках.
 */
public class LockAdminReceiver extends DeviceAdminReceiver {

    private static final String TAG = "LockAdmin";

    public static ComponentName component(Context context) {
        return new ComponentName(context, LockAdminReceiver.class);
    }

    /** Включён ли приложению доступ администратора устройства. */
    public static boolean isActive(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(component(context));
    }

    /**
     * Блокирует экран.
     *
     * @return false — приложение не администратор устройства или система запретила блокировку
     */
    public static boolean lock(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.isAdminActive(component(context))) {
            return false;
        }
        try {
            dpm.lockNow();
            Log.i(TAG, "Экран заблокирован (стоянка)");
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "lockNow запрещён", e);
            return false;
        }
    }
}
