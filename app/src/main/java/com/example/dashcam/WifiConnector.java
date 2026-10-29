package com.example.dashcam;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.util.Collections;

/**
 * Подключение к Wi-Fi сети камеры заднего хода.
 *
 * <p>Основной путь — {@link WifiNetworkSuggestion}: система один раз спрашивает разрешение и дальше
 * подключается к камере сама, как только она появится в эфире. Для кнопки «Подключиться сейчас»
 * используется {@link WifiNetworkSpecifier} — он поднимает соединение немедленно.</p>
 */
public final class WifiConnector {

    private static final String TAG = "WifiConnector";

    /** Держим ссылку, иначе система отменит запрос на подключение. */
    private static volatile ConnectivityManager.NetworkCallback activeCallback;
    private static volatile ConnectivityManager activeManager;

    private WifiConnector() {
    }

    /**
     * Отписывает последний callback подключения. Вызывается при остановке экрана настроек и
     * перед новым запросом: без этого статический callback удерживал контекст ушедшей
     * активности (утечка, K8).
     */
    public static void cancelConnect() {
        ConnectivityManager.NetworkCallback callback = activeCallback;
        ConnectivityManager manager = activeManager;
        activeCallback = null;
        activeManager = null;
        unregisterQuietly(manager, callback);
    }

    private static void unregisterQuietly(ConnectivityManager manager,
            ConnectivityManager.NetworkCallback callback) {
        if (manager == null || callback == null) {
            return;
        }
        try {
            manager.unregisterNetworkCallback(callback);
        } catch (Exception ignored) {
            // Уже отписан.
        }
    }

    public interface ResultListener {
        void onResult(String message);
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /** Имя текущей Wi-Fi сети или {@code null}. */
    public static String currentSsid(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                return null;
            }
            WifiInfo info = wifi.getConnectionInfo();
            if (info == null) {
                return null;
            }
            return normalize(info.getSSID());
        } catch (Exception e) {
            Log.w(TAG, "Не удалось получить имя сети", e);
            return null;
        }
    }

    public static boolean isConnectedTo(Context context, String ssid) {
        String current = currentSsid(context);
        return current != null && ssid != null && current.equalsIgnoreCase(ssid);
    }

    private static String normalize(String ssid) {
        if (ssid == null) {
            return null;
        }
        String value = ssid.trim();
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isEmpty() || "<unknown ssid>".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }

    /**
     * Запоминает сеть камеры в системе: дальше Android подключается к ней сам.
     *
     * @return текст результата для показа пользователю
     */
    public static String suggest(Context context, String ssid, String password) {
        if (!isSupported()) {
            return context.getString(R.string.wifi_requires_android10);
        }
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            return context.getString(R.string.wifi_unavailable);
        }
        try {
            WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid);
            if (password != null && !password.isEmpty()) {
                builder.setWpa2Passphrase(password);
            }
            int status = wifi.addNetworkSuggestions(
                    Collections.singletonList(builder.build()));
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                return context.getString(R.string.wifi_suggestion_ok);
            }
            return context.getString(R.string.wifi_suggestion_failed, String.valueOf(status));
        } catch (Exception e) {
            Log.w(TAG, "Не удалось добавить сеть в предложения", e);
            return context.getString(R.string.wifi_suggestion_failed, e.getClass().getSimpleName());
        }
    }

    /**
     * Убирает сеть из предложений: Android перестаёт подключаться к камере автоматически.
     * Предложение строится так же, как при добавлении, — система сравнивает их по равенству,
     * включая пароль.
     *
     * @return текст ошибки или {@code null}, если всё в порядке (или удалять нечего)
     */
    public static String removeSuggestion(Context context, String ssid, String password) {
        if (!isSupported() || ssid == null || ssid.isEmpty()) {
            return null;
        }
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            return context.getString(R.string.wifi_unavailable);
        }
        try {
            WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid);
            if (password != null && !password.isEmpty()) {
                builder.setWpa2Passphrase(password);
            }
            int status = wifi.removeNetworkSuggestions(
                    Collections.singletonList(builder.build()));
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                return null;
            }
            return context.getString(R.string.wifi_suggestion_failed, String.valueOf(status));
        } catch (Exception e) {
            Log.w(TAG, "Не удалось убрать сеть из предложений", e);
            return context.getString(R.string.wifi_suggestion_failed, e.getClass().getSimpleName());
        }
    }

    /** Немедленное подключение (система покажет диалог подтверждения). */
    public static void connectNow(Context context, String ssid, String password,
            ResultListener listener) {
        if (!isSupported()) {
            deliver(listener, context.getString(R.string.wifi_requires_android10));
            return;
        }
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            deliver(listener, context.getString(R.string.wifi_unavailable));
            return;
        }
        try {
            WifiNetworkSpecifier.Builder specifier = new WifiNetworkSpecifier.Builder()
                    .setSsid(ssid);
            if (password != null && !password.isEmpty()) {
                specifier.setWpa2Passphrase(password);
            }
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(specifier.build())
                    .build();
            // Предыдущий запрос (если был) отписываем — иначе он утекает (K8).
            cancelConnect();
            ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    finishConnect(manager, this);
                    deliver(listener, context.getString(R.string.wifi_connected));
                }

                @Override
                public void onUnavailable() {
                    finishConnect(manager, this);
                    deliver(listener, context.getString(R.string.wifi_connect_failed));
                }
            };
            activeCallback = callback;
            activeManager = manager;
            manager.requestNetwork(request, callback);
        } catch (Exception e) {
            Log.w(TAG, "Не удалось подключиться к сети камеры", e);
            deliver(listener, context.getString(R.string.wifi_connect_failed));
        }
    }

    /** Результат получен — отписываем callback и чистим статическую ссылку. */
    private static void finishConnect(ConnectivityManager manager,
            ConnectivityManager.NetworkCallback callback) {
        if (activeCallback == callback) {
            activeCallback = null;
            activeManager = null;
        }
        unregisterQuietly(manager, callback);
    }

    private static void deliver(ResultListener listener, String message) {
        if (listener == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> listener.onResult(message));
    }
}

