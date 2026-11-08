package com.example.dashcam;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Г-сенсор: следит за датчиком ускорения и сообщает о резком ударе (авария, наезд на
 * препятствие, толчок на стоянке). Задача — не измерить перегрузку точно, а не дать
 * кольцевой перезаписи стереть записи момента ДТП.
 *
 * <p>Как именно считаем удар вынесено в статический метод {@link #detect}, чтобы логику
 * можно было проверить обычными unit-тестами на синтетических данных (см.
 * {@code ImpactDetectorTest}).</p>
 */
final class ImpactDetector {

    private static final String TAG = "ImpactDetector";

    /**
     * Сколько g должно быть сверх покоящегося ускорения (1 g ≈ 9.81 м/с²), чтобы считать
     * это ударом: 2.5 g — примерно обычный удар при несильном ДТП. Настройка в пределах
     * 1.5–4 g (мягкий толчок — жёсткий удар).
     */
    static final float DEFAULT_THRESHOLD_G = 2.5f;
    static final float MIN_THRESHOLD_G = 1.0f;
    static final float MAX_THRESHOLD_G = 8.0f;

    /** Между событиями датчика не накапливаем больше этой разницы — защита от выброса. */
    static final float MAX_IGNORED_DELTA_G = 20f;

    /** Минимум секунд между двумя срабатываниями: один удар не должен «наслать» десяток. */
    static final long DEBOUNCE_MS = 1500L;

    /** Столько последних отсчётов держим, чтобы отличить удар от простой тряски. */
    private static final int WINDOW = 6;

    /** Сколько отсчётов уходит на определение покоящегося ускорения телефона. */
    private static final int CALIBRATION_SAMPLES = 5;

    /**
     * Покоящееся ускорение. Пока телефон лежит, датчик показывает модуль ≈ 1 g; у разных
     * моделей это от 9.78 до 9.83 м/с², поэтому берём среднее по первым отсчётам, а не
     * константу. {@link #detect} опирается именно на него.
     */
    static final float GRAVITY = 9.80665f;

    interface OnImpact {
        /**
         * @param deltaG   превышение покоящегося ускорения, g
         * @param samples  сколько отсчётов подряд было выше порога
         */
        void onImpact(float deltaG, int samples);
    }

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Handler handler;
    private final OnImpact listener;

    private float thresholdG = DEFAULT_THRESHOLD_G;
    private float restAcc = GRAVITY;
    private int calibrated;
    private boolean listening;
    /** Машина решения «удар это или тряска»: вынесена в {@link Gate}, чтобы её тестировали без датчика. */
    private final Gate gate = new Gate(WINDOW, DEBOUNCE_MS);

    ImpactDetector(Context context, Handler handler, OnImpact listener) {
        this.sensorManager = (SensorManager) context.getApplicationContext()
                .getSystemService(Context.SENSOR_SERVICE);
        this.accelerometer = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        this.handler = handler;
        this.listener = listener;
    }

    /** Есть ли у телефона датчик ускорения — без него Г-сенсор бессмысленен. */
    boolean isAvailable() {
        return accelerometer != null;
    }

    /**
     * Порог в g. Значение из настроек ограничивается разумным диапазоном: слишком низкий
     * даёт постоянные ложные срабатывания на кочках, слишком высокий — молчит при ДТП.
     */
    void setThresholdG(float value) {
        thresholdG = clamp(value);
    }

    float thresholdG() {
        return thresholdG;
    }

    void start() {
        if (!isAvailable() || listening) {
            return;
        }
        calibrated = 0;
        gate.reset();
        int rate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? SensorManager.SENSOR_DELAY_NORMAL
                : SensorManager.SENSOR_DELAY_GAME;
        listening = sensorManager.registerListener(listenerProxy, accelerometer, rate, handler);
        if (!listening) {
            Log.w(TAG, "Не удалось подписаться на датчик ускорения");
        } else {
            Log.i(TAG, "Г-сенсор включён: порог " + String.format(java.util.Locale.US, "%.1f", thresholdG)
                    + " g, нужно " + WINDOW + " отсчётов подряд, пауза " + DEBOUNCE_MS + " мс");
        }
    }

    void stop() {
        if (!listening) {
            return;
        }
        listening = false;
        try {
            sensorManager.unregisterListener(listenerProxy);
        } catch (Exception e) {
            Log.w(TAG, "Не удалось отписаться от датчика ускорения", e);
        }
    }

    boolean isListening() {
        return listening;
    }

    private final SensorEventListener listenerProxy = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            float[] v = event.values;
            if (v == null || v.length < 3) {
                return;
            }
            // TYPE_ACCELEROMETER отдаёт ускорение без гравитации, но на большинстве
            // устройств в покое всё равно виден остаток ≈ 1 g; TYPE_LINEAR_ACCELERATION
            // отдал бы чистое ускорение. Считаем норму и сравниваем с покоящемся уровнем.
            handleSample(v[0], v[1], v[2], System.currentTimeMillis());
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Нам важна только величина ускорения, точность не меняем.
        }
    };

    /** Один отсчёт датчика: накопление превышения порога и само срабатывание. */
    void handleSample(float x, float y, float z, long nowMs) {
        float acc = (float) Math.sqrt(x * x + y * y + z * z);
        if (calibrated < CALIBRATION_SAMPLES) {
            // Несколько первых отсчётов дают покоящийся уровень: без него нельзя отличить
            // удар от простого «телефон лежит под наклоном».
            restAcc = restAcc * 0.7f + acc * 0.3f;
            calibrated++;
            return;
        }

        float deltaG = (acc - restAcc) / GRAVITY;
        if (!gate.feed(deltaG, thresholdG, nowMs)) {
            return;
        }
        Log.i(TAG, "УШИБ: превышение " + String.format(java.util.Locale.US, "%.2f", deltaG)
                + " g при пороге " + String.format(java.util.Locale.US, "%.2f", thresholdG) + " g");
        if (listener != null) {
            listener.onImpact(deltaG, WINDOW);
        }
    }

    static float clamp(float thresholdG) {
        if (Float.isNaN(thresholdG) || thresholdG < MIN_THRESHOLD_G) {
            return MIN_THRESHOLD_G;
        }
        return Math.min(thresholdG, MAX_THRESHOLD_G);
    }

    /**
     * pure-часть детектора: сколько отсчётов подряд превысило порог.
     *
     * <p>Одиночный выброс ударом не считается: срабатывание происходит, когда накоплено
     * не меньше {@code required} отсчётов подряд (настоящий удар длится 50–100 мс, то есть
     * несколько отсчётов). Счёт не переполняется: он упирается в {@code required}, поэтому
     * длинная серия превышений остаётся одним событием, а не десятком.</p>
     *
     * @param deltaG     превышение покоящегося ускорения в g (может быть отрицательным)
     * @param thresholdG порог срабатывания в g
     * @param required   сколько отсчётов подряд нужно для срабатывания
     * @param carried    сколько превышающих отсчётов уже накоплено
     * @return новое накопленное количество (0 — отсчёт ниже порога, накопление сброшено)
     */
    static int detect(float deltaG, float thresholdG, int required, int carried) {
        if (deltaG < thresholdG) {
            return 0;
        }
        return Math.min(carried + 1, required);
    }

    /**
     * Состояние детектора: накопление превышений порога, «взвод» и защита от повторов.
     *
     * <p>Никаких Android-зависимостей: класс проверяется unit-тестами на синтетических
     * сериях отсчётов (см. {@code ImpactDetectorTest}) — на телефоне такую проверку
     * сделать нельзя, потому что датчик нельзя «попросить» дать ровно 6 отсчётов по 3 g.</p>
     */
    static final class Gate {

        private final int required;
        private final long debounceMs;

        private int pending;
        private boolean armed = true;
        private boolean impactSeen;
        private long lastImpactAt;

        Gate(int required, long debounceMs) {
            this.required = required;
            this.debounceMs = debounceMs;
        }

        /** Новое подписывание на датчик: накопление забываем, удар помним (не повторяем). */
        void reset() {
            pending = 0;
            armed = true;
        }

        int pendingSamples() {
            return pending;
        }

        /**
         * @param deltaG     превышение покоящегося ускорения в g
         * @param thresholdG текущий порог срабатывания в g
         * @param nowMs      время отсчёта
         * @return true, если этот отсчёт завершился ударом
         */
        boolean feed(float deltaG, float thresholdG, long nowMs) {
            if (deltaG > MAX_IGNORED_DELTA_G) {
                // Единичный выброс (артефакт драйвера): сам ударом не считается, но и
                // накопленное не обнуляем — реальный удар мог начаться в предыдущих отсчётах.
                return false;
            }
            if (deltaG < thresholdG) {
                pending = 0;
                armed = true;
                return false;
            }
            pending = detect(deltaG, thresholdG, required, pending);
            if (pending < required) {
                return false;
            }
            if (!armed) {
                // Удар уже зафиксирован, тишины между превышениями не было: длинная серия
                // (грунтовка, непрерывная тряска) не должна превращаться в вереницу «ДТП».
                return false;
            }
            if (impactSeen && nowMs - lastImpactAt < debounceMs) {
                // Слишком близко к предыдущему удару — это то же событие.
                armed = false;
                return false;
            }
            armed = false;
            pending = 0;
            impactSeen = true;
            lastImpactAt = nowMs;
            return true;
        }
    }
}
