package com.example.dashcam;

import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Геокодирование адреса через 2GIS Geocoder API и запуск навигации в 2ГИС.
 *
 * <p>Публичный Geocoder API 2ГИС принимает произвольную строку адреса и возвращает координаты
 * ({@code items.point}). Запрос бесплатный в рамках лимитов, но требует ключ, который
 * создаётся в личном кабинете dev.2gis.com. Ключ хранится в настройках
 * ({@link Prefs#twoGisKey(android.content.Context)}), чтобы его можно было заменить без
 * пересборки.</p>
 */
public final class TwoGisGeocoder {

    private static final String TAG = "TwoGis";

    /** Пакет приложения 2ГИС, которому отдаём координаты. */
    public static final String PACKAGE = "ru.dublgis.dgismobile";

    /**
     * Ключ по умолчанию из local.properties (TWOGIS_KEY, в репозиторий не коммитится).
     * Пусто — ключ задаётся в настройках.
     */
    public static final String DEFAULT_KEY = BuildConfig.TWOGIS_KEY;

    private static final String ENDPOINT = "https://catalog.api.2gis.com/3.0/items/geocode";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 7000;

    /** Найденная точка: координаты и, если API их вернул, полный адрес. */
    public static final class Point {
        public final double lon;
        public final double lat;
        public final String address;

        Point(double lon, double lat, String address) {
            this.lon = lon;
            this.lat = lat;
            this.address = address != null ? address : "";
        }
    }

    /** API ответил, но с ошибкой (например, 403 — ключ не принят). */
    public static final class ApiException extends IOException {
        public final int code;

        ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private TwoGisGeocoder() {
    }

    /**
     * Ищет координаты по адресу. {@code null} — 2ГИС не нашёл ни одного объекта.
     * Сетевая ошибка, отказ ключа и некорректный ответ — {@link IOException}.
     */
    public static Point geocode(String query, String key) throws IOException {
        String q = query != null ? query.trim() : "";
        if (q.isEmpty()) {
            return null;
        }
        if (q.length() > 500) {
            q = q.substring(0, 500);
        }
        Uri uri = Uri.parse(ENDPOINT).buildUpon()
                .appendQueryParameter("q", q)
                // point — координаты объекта, centroid — на случай если у объекта нет точки.
                .appendQueryParameter("fields", "items.point,items.geometry.centroid,"
                        + "items.full_address_name")
                .appendQueryParameter("locale", "ru_RU")
                .appendQueryParameter("page_size", "1")
                .appendQueryParameter("key", key != null ? key.trim() : "")
                .build();
        String body = get(uri.toString());
        return parse(body);
    }

    /** Разбирает ответ Geocoder API; выделен отдельно, чтобы логика была видна целиком. */
    static Point parse(String body) throws IOException {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject meta = root.optJSONObject("meta");
            if (meta != null) {
                int code = meta.optInt("code", 200);
                if (code != 200) {
                    JSONObject error = meta.optJSONObject("error");
                    String message = error != null
                            ? error.optString("message", "") : "";
                    Log.w(TAG, "2ГИС вернул код " + code
                            + (message.isEmpty() ? "" : ": " + message));
                    throw new ApiException(code, "2GIS code " + code
                            + (message.isEmpty() ? "" : ": " + message));
                }
            }
            JSONObject result = root.optJSONObject("result");
            JSONArray items = result != null ? result.optJSONArray("items") : null;
            if (items == null || items.length() == 0) {
                return null;
            }
            JSONObject item = items.getJSONObject(0);
            JSONObject point = item.optJSONObject("point");
            if (point == null) {
                JSONObject geometry = item.optJSONObject("geometry");
                point = geometry != null ? geometry.optJSONObject("centroid") : null;
            }
            if (point == null) {
                return null;
            }
            double lon = point.optDouble("lon", Double.NaN);
            double lat = point.optDouble("lat", Double.NaN);
            if (Double.isNaN(lon) || Double.isNaN(lat)) {
                return null;
            }
            return new Point(lon, lat, item.optString("full_address_name", ""));
        } catch (JSONException e) {
            throw new IOException("Некорректный ответ 2ГИС", e);
        }
    }

    /** URI навигации 2ГИС: сразу строит маршрут на автомобиле до точки и включает его. */
    public static Uri routeUri(double lon, double lat) {
        return Uri.parse("dgis://2gis.ru/routeSearch/rsType/car/to/"
                + String.format(Locale.US, "%.6f", lon) + ","
                + String.format(Locale.US, "%.6f", lat) + "/go");
    }

    /** Выбранное в настройках приложение — 2ГИС? */
    public static boolean isTwoGis(android.content.ComponentName component) {
        return component != null && PACKAGE.equals(component.getPackageName());
    }

    private static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (status < 200 || status >= 300) {
                Log.w(TAG, "2ГИС HTTP " + status + ": " + body);
                if (status == 401 || status == 403) {
                    throw new ApiException(status, "HTTP " + status);
                }
                throw new IOException("HTTP " + status);
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line);
            }
        }
        return out.toString();
    }
}
