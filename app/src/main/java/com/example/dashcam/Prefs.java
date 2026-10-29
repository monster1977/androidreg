package com.example.dashcam;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки приложения-видеорегистратора, хранятся в SharedPreferences.
 */
public final class Prefs {

    private static final String FILE = "dashcam_settings";

    /** Типовой адрес MJPEG-потока у недорогих WiFi-камер; правится в настройках. */
    public static final String DEFAULT_REAR_CAMERA_URL = "http://192.168.4.1/video";

    public static final String KEY_QUALITY = "quality";            // HD | FHD | UHD
    public static final String KEY_SEGMENT_MIN = "segment_min";    // 0 = без разбиения
    public static final String KEY_MAX_STORAGE_GB = "max_storage_gb";
    public static final String KEY_AUDIO = "audio";
    public static final String KEY_AUTO_START = "auto_start";
    public static final String KEY_LENS = "lens";                  // BACK | FRONT
    public static final String KEY_RING_MINUTES = "ring_minutes";  // 0 = без ограничения по времени
    public static final String KEY_TIMESTAMP = "timestamp";        // дата/время на видео
    public static final String KEY_GPS = "gps";                    // координаты на видео
    public static final String KEY_AUTO_BRIGHT = "auto_bright";    // автояркость в сумерках
    public static final String KEY_AUTO_START_CHARGE = "auto_start_charge";
    public static final String KEY_LANDSCAPE_REVERSED = "landscape_reversed";
    public static final String KEY_CAMERA_MODE = "camera_mode";     // BACK | FRONT
    public static final String KEY_REAR_CAMERA_URL = "rear_camera_url";
    public static final String KEY_REAR_SSID = "rear_ssid";
    public static final String KEY_REAR_PASSWORD = "rear_password";
    public static final String KEY_REAR_AUTO_CONNECT = "rear_auto_connect";
    public static final String KEY_REAR_AUTO_OPEN = "rear_auto_open";
    public static final String KEY_RECORD_TO_SD = "record_to_sd";
    /** Компонент приложения, которое открывает жест «открытая ладонь» (пусто — выключено). */
    public static final String KEY_PALM_APP = "palm_app";
    public static final String KEY_TWO_GIS_KEY = "two_gis_key";
    /** Останавливать запись и блокировать экран, когда координаты не меняются. */
    public static final String KEY_STATIONARY_STOP = "stationary_stop";
    public static final String KEY_STATIONARY_MIN = "stationary_min";

    /**
     * Служебный флаг (не настройка): пользователь хочет, чтобы запись шла. Нужен, чтобы
     * после убийства процесса системой sticky-сервис мог восстановить запись, а после
     * осознанной остановки — не поднимался снова.
     */
    public static final String KEY_RECORDING_DESIRED = "recording_desired";

    private Prefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String quality(Context context) {
        return get(context).getString(KEY_QUALITY, "FHD");
    }

    public static void setQuality(Context context, String value) {
        get(context).edit().putString(KEY_QUALITY, value).apply();
    }

    public static int segmentMinutes(Context context) {
        return get(context).getInt(KEY_SEGMENT_MIN, 3);
    }

    public static void setSegmentMinutes(Context context, int minutes) {
        get(context).edit().putInt(KEY_SEGMENT_MIN, minutes).apply();
    }

    public static int maxStorageGb(Context context) {
        return get(context).getInt(KEY_MAX_STORAGE_GB, 2);
    }

    public static void setMaxStorageGb(Context context, int gb) {
        get(context).edit().putInt(KEY_MAX_STORAGE_GB, gb).apply();
    }

    public static boolean audio(Context context) {
        return get(context).getBoolean(KEY_AUDIO, true);
    }

    public static void setAudio(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_AUDIO, value).apply();
    }

    public static boolean autoStart(Context context) {
        return get(context).getBoolean(KEY_AUTO_START, false);
    }

    public static void setAutoStart(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_AUTO_START, value).apply();
    }

    public static String lens(Context context) {
        return get(context).getString(KEY_LENS, "BACK");
    }

    public static void setLens(Context context, String value) {
        get(context).edit().putString(KEY_LENS, value).apply();
    }

    /**
     * Отпечаток настроек, влияющих на привязку камеры: активность перезапускает
     * камеру, только если отпечаток изменился. GPS и автояркость входят в него,
     * потому что меняют состав оверлея/эффекта на кадре (нужен ли GL-эффект вообще).
     */
    public static String cameraSignature(Context context) {
        return quality(context) + "|" + cameraMode(context) + "|" + timestamp(context)
                + "|" + gps(context) + "|" + autoBright(context);
    }

    /** Какая камера используется: BACK — основная, FRONT — фронтальная. */
    public static String cameraMode(Context context) {
        String mode = get(context).getString(KEY_CAMERA_MODE, null);
        if (mode != null) {
            // SECONDARY/THERMAL остались от версий 1.8–1.9 с кнопкой доп. камеры.
            if ("SECONDARY".equals(mode) || "THERMAL".equals(mode)) {
                return "BACK";
            }
            return mode;
        }
        // Переносим прежнюю настройку «камера».
        return "FRONT".equals(lens(context)) ? "FRONT" : "BACK";
    }

    public static void setCameraMode(Context context, String mode) {
        get(context).edit().putString(KEY_CAMERA_MODE, mode).apply();
    }

    /** Сколько минут записей хранить по кругу; 0 — только лимит объёма. */
    public static int ringMinutes(Context context) {
        return get(context).getInt(KEY_RING_MINUTES, 60);
    }

    public static void setRingMinutes(Context context, int minutes) {
        get(context).edit().putInt(KEY_RING_MINUTES, minutes).apply();
    }

    /** Дата и время на видео (вжигается в кадр). */
    public static boolean timestamp(Context context) {
        return get(context).getBoolean(KEY_TIMESTAMP, true);
    }

    public static void setTimestamp(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_TIMESTAMP, value).apply();
    }

    /** GPS-координаты и скорость на видео. */
    public static boolean gps(Context context) {
        return get(context).getBoolean(KEY_GPS, true);
    }

    public static void setGps(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_GPS, value).apply();
    }

    /** Авто-повышение экспозиции в сумерках/ночь по датчику освещённости телефона. */
    public static boolean autoBright(Context context) {
        return get(context).getBoolean(KEY_AUTO_BRIGHT, true);
    }

    public static void setAutoBright(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_AUTO_BRIGHT, value).apply();
    }

    /** Начинать запись автоматически при подключении зарядки. */
    public static boolean autoStartOnCharge(Context context) {
        return get(context).getBoolean(KEY_AUTO_START_CHARGE, true);
    }

    public static void setAutoStartOnCharge(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_AUTO_START_CHARGE, value).apply();
    }

    /** true — обратный ландшафт: экран перевёрнут на 180°, камера и зарядка меняются местами. */
    public static boolean landscapeReversed(Context context) {
        return get(context).getBoolean(KEY_LANDSCAPE_REVERSED, false);
    }

    public static void setLandscapeReversed(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_LANDSCAPE_REVERSED, value).apply();
    }

    /** Адрес потока WiFi-камеры заднего хода (http://… для MJPEG или rtsp://…). */
    public static String rearCameraUrl(Context context) {
        String url = get(context).getString(KEY_REAR_CAMERA_URL, null);
        return url != null ? url.trim() : DEFAULT_REAR_CAMERA_URL;
    }

    public static void setRearCameraUrl(Context context, String url) {
        get(context).edit().putString(KEY_REAR_CAMERA_URL, url != null ? url.trim() : "").apply();
    }

    /** Имя Wi-Fi сети камеры заднего хода. */
    public static String rearSsid(Context context) {
        String value = get(context).getString(KEY_REAR_SSID, null);
        return value != null ? value.trim() : "";
    }

    public static void setRearSsid(Context context, String ssid) {
        get(context).edit().putString(KEY_REAR_SSID, ssid != null ? ssid.trim() : "").apply();
    }

    /** Пароль Wi-Fi камеры: хранится зашифрованным (см. {@link SecureStore}). */
    public static String rearPassword(Context context) {
        String value = get(context).getString(KEY_REAR_PASSWORD, null);
        if (value == null) {
            return "";
        }
        // Старые версии хранили пароль открытым текстом — unprotect их вернёт как есть.
        return SecureStore.unprotect(value);
    }

    public static void setRearPassword(Context context, String password) {
        String value = password != null ? password : "";
        get(context).edit()
                .putString(KEY_REAR_PASSWORD, value.isEmpty() ? "" : SecureStore.protect(value))
                .apply();
    }

    /** Подключаться к сети камеры автоматически, когда она появляется. */
    public static boolean rearAutoConnect(Context context) {
        return get(context).getBoolean(KEY_REAR_AUTO_CONNECT, true);
    }

    public static void setRearAutoConnect(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_REAR_AUTO_CONNECT, value).apply();
    }

    /** Открывать задний вид автоматически, когда телефон подключён к сети камеры. */
    public static boolean rearAutoOpen(Context context) {
        return get(context).getBoolean(KEY_REAR_AUTO_OPEN, true);
    }

    public static void setRearAutoOpen(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_REAR_AUTO_OPEN, value).apply();
    }

    /** Писать записи на SD-карту вместо внутренней памяти. */
    public static boolean recordToSdCard(Context context) {
        return get(context).getBoolean(KEY_RECORD_TO_SD, false);
    }

    public static void setRecordToSdCard(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_RECORD_TO_SD, value).apply();
    }

    /** Компонент приложения для жеста «ладонь» в виде «пакет/активность»; пусто — не выбрано. */
    public static String palmApp(Context context) {
        String value = get(context).getString(KEY_PALM_APP, null);
        return value != null ? value.trim() : "";
    }

    public static void setPalmApp(Context context, String component) {
        get(context).edit().putString(KEY_PALM_APP, component != null ? component.trim() : "").apply();
    }

    /**
     * Ключ 2GIS Geocoder API. Пусто — геокодирование не работает, приложение подсказывает,
     * что ключ надо задать в настройках. Значение по умолчанию — {@link TwoGisGeocoder#DEFAULT_KEY}.
     */
    public static String twoGisKey(Context context) {
        String value = get(context).getString(KEY_TWO_GIS_KEY, TwoGisGeocoder.DEFAULT_KEY);
        return value != null ? value.trim() : "";
    }

    public static void setTwoGisKey(Context context, String key) {
        get(context).edit().putString(KEY_TWO_GIS_KEY, key != null ? key.trim() : "").apply();
    }

    /**
     * Останавливать запись, когда координаты не меняются, и блокировать экран.
     * По умолчанию включено — так просил пользователь; выключается в настройках.
     */
    public static boolean stationaryStop(Context context) {
        return get(context).getBoolean(KEY_STATIONARY_STOP, true);
    }

    public static void setStationaryStop(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_STATIONARY_STOP, value).apply();
    }

    /** Сколько минут без движения считать стоянкой. */
    public static int stationaryMinutes(Context context) {
        return get(context).getInt(KEY_STATIONARY_MIN, 5);
    }

    public static void setStationaryMinutes(Context context, int minutes) {
        get(context).edit().putInt(KEY_STATIONARY_MIN, minutes).apply();
    }

    /** true — запись должна продолжаться (в т.ч. после перезапуска сервиса системой). */
    public static boolean recordingDesired(Context context) {
        return get(context).getBoolean(KEY_RECORDING_DESIRED, false);
    }

    public static void setRecordingDesired(Context context, boolean value) {
        // commit(), а не apply(): флаг должен пережить внезапное убийство процесса.
        get(context).edit().putBoolean(KEY_RECORDING_DESIRED, value).commit();
    }
}



