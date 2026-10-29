package com.example.dashcam;

/**
 * Стоянка: следит, меняются ли координаты. Если телефон не отъехал дальше
 * {@link #MOVE_THRESHOLD_M} метров от опорной точки, считается, что движения нет.
 *
 * <p>Логика намеренно не зависит от Android API (кроме форматирования чисел), чтобы её
 * можно было проверить обычными unit-тестами. Время передаётся снаружи в миллисекундах
 * ({@code SystemClock.elapsedRealtime()}).</p>
 */
final class StationaryWatcher {

    /** Сдвиг больше этого расстояния — движение; GPS на стоянке «гуляет» на 5–15 м. */
    static final float MOVE_THRESHOLD_M = 30f;
    /** Фиксы с точностью хуже этой не учитываем: по ним нельзя судить о стоянке. */
    static final float MAX_ACCURACY_M = 200f;
    /** Без свежего фикса дольше этого времени не останавливаем: тоннель, подземный паркинг. */
    static final long FIX_FRESH_MS = 3 * 60_000L;

    private double anchorLat;
    private double anchorLon;
    private boolean hasAnchor;
    private long lastMoveAt;
    private long lastFixAt;

    /** Начинает отсчёт заново — при старте новой записи. */
    void reset(long now) {
        hasAnchor = false;
        lastMoveAt = now;
        lastFixAt = 0L;
    }

    /**
     * Учитывает новый фикс.
     *
     * @return true — телефон заметно сдвинулся, отсчёт стоянки начат заново
     */
    boolean onLocation(double lat, double lon, float accuracy, long now) {
        if (accuracy > MAX_ACCURACY_M) {
            return false;
        }
        lastFixAt = now;
        if (!hasAnchor) {
            anchorLat = lat;
            anchorLon = lon;
            hasAnchor = true;
            lastMoveAt = now;
            return false;
        }
        if (distanceMeters(anchorLat, anchorLon, lat, lon) < MOVE_THRESHOLD_M) {
            return false;
        }
        anchorLat = lat;
        anchorLon = lon;
        lastMoveAt = now;
        return true;
    }

    /** Есть ли фикс не старше {@link #FIX_FRESH_MS}; без него судить о стоянке нельзя. */
    boolean hasFreshFix(long now) {
        return lastFixAt > 0L && now - lastFixAt <= FIX_FRESH_MS;
    }

    /** Сколько миллисекунд координаты не менялись заметно. */
    long idleMs(long now) {
        return Math.max(0L, now - lastMoveAt);
    }

    /** Пора останавливать запись? */
    boolean shouldStop(long now, long timeoutMs) {
        return hasAnchor && hasFreshFix(now) && idleMs(now) >= timeoutMs;
    }

    /** Расстояние по большому кругу (формула гаверсинуса), метры. */
    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * earthRadius * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
