package com.example.dashcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Проверка «машины решения» Г-сенсора без датчика: на телефоне акселерометр нельзя
 * попросить выдать ровно шесть отсчётов по 3 g, поэтому решётка {@link ImpactDetector.Gate}
 * проверяется на синтетических сериях отсчётов.
 */
public class ImpactDetectorTest {

    private static final float THRESHOLD = ImpactDetector.DEFAULT_THRESHOLD_G;
    private static final int REQUIRED = 6;
    private static final long DEBOUNCE = ImpactDetector.DEBOUNCE_MS;
    /** Один отсчёт примерно каждые 100 мс (SENSOR_DELAY_NORMAL на BV6600Pro). */
    private static final long STEP = 100L;

    // ------------------------------------------------------------------ накопление

    @Test
    public void singleOverloadIsNotImpact() {
        // Одиночный выброс (телефон уронили на стол) ударом считаться не должен.
        assertEquals(0, ImpactDetector.detect(0.4f, THRESHOLD, REQUIRED, 0));
        ImpactDetector.Gate gate = newGate();
        assertFalse(gate.feed(3.0f, THRESHOLD, 0L));
        assertEquals(1, gate.pendingSamples());
    }

    @Test
    public void impactNeedsConsecutiveSamples() {
        ImpactDetector.Gate gate = newGate();
        for (int i = 0; i < REQUIRED - 1; i++) {
            assertFalse("отсчёт " + i + " не должен быть ударом",
                    gate.feed(3.0f, THRESHOLD, i * STEP));
        }
        assertTrue(gate.feed(3.0f, THRESHOLD, (REQUIRED - 1) * STEP));
    }

    @Test
    public void quietSampleResetsAccumulation() {
        ImpactDetector.Gate gate = newGate();
        for (int i = 0; i < REQUIRED - 1; i++) {
            assertFalse(gate.feed(3.0f, THRESHOLD, i * STEP));
        }
        // Тряска оборвалась — накопление обнуляем, иначе обычный поезд стал бы «ДТП».
        assertFalse(gate.feed(0.2f, THRESHOLD, 5 * STEP));
        assertEquals(0, gate.pendingSamples());
        assertFalse(gate.feed(3.0f, THRESHOLD, 6 * STEP));
        assertEquals(1, gate.pendingSamples());
    }

    @Test
    public void sampleCounterSaturates() {
        // Длинная серия превышений (грунтовка) не должна превращаться в вереницу «ДТП».
        assertEquals(REQUIRED, ImpactDetector.detect(3.0f, THRESHOLD, REQUIRED, REQUIRED));
        assertEquals(REQUIRED, ImpactDetector.detect(3.0f, THRESHOLD, REQUIRED, REQUIRED + 5));
    }

    // ------------------------------------------------------------------ повтор удара

    @Test
    public void continuousShakeTriggersOnce() {
        ImpactDetector.Gate gate = newGate();
        boolean first = false;
        int triggers = 0;
        for (int i = 0; i < 3 * REQUIRED; i++) {
            boolean triggered = gate.feed(3.0f, THRESHOLD, i * STEP);
            if (triggered) {
                triggers++;
                first = (i == REQUIRED - 1);
            }
        }
        assertEquals("серия тряски должна дать ровно одно событие", 1, triggers);
        assertTrue(first);
    }

    @Test
    public void secondImpactIsBlockedByDebounce() {
        ImpactDetector.Gate gate = newGate();
        assertTrue(runOverload(gate, 0L));
        // Тишина «взвела» детектор, но новый удар слишком близок к предыдущему.
        assertFalse(gate.feed(0.1f, THRESHOLD, REQUIRED * STEP));
        assertFalse(runOverload(gate, REQUIRED * STEP));
    }

    @Test
    public void impactAfterDebounceCountsAgain() {
        ImpactDetector.Gate gate = newGate();
        assertTrue(runOverload(gate, 0L));
        assertFalse(gate.feed(0.1f, THRESHOLD, REQUIRED * STEP));
        long start = DEBOUNCE + STEP;
        assertTrue(runOverload(gate, start));
    }

    // ------------------------------------------------------------------ выбросы

    @Test
    public void sensorOutlierIsIgnored() {
        ImpactDetector.Gate gate = newGate();
        for (int i = 0; i < 3; i++) {
            assertFalse(gate.feed(3.0f, THRESHOLD, i * STEP));
        }
        // Единичный артефакт драйвера (20+ g) сам ударом не считается...
        assertFalse(gate.feed(60.0f, THRESHOLD, 3 * STEP));
        // ...и не обнуляет накопленное: реальный удар мог начаться в предыдущих отсчётах.
        assertEquals(3, gate.pendingSamples());
        assertTrue(runOverload(gate, 4 * STEP));
    }

    @Test
    public void outlierKeepsDetectorDisarmed() {
        ImpactDetector.Gate gate = newGate();
        assertTrue(runOverload(gate, 0L));
        // Выброс после удара не должен «взводить» детектор обратно.
        assertFalse(gate.feed(60.0f, THRESHOLD, REQUIRED * STEP));
        assertFalse(runOverload(gate, REQUIRED * STEP));
    }

    // ------------------------------------------------------------------ порог

    @Test
    public void thresholdClamp() {
        assertEquals(ImpactDetector.MIN_THRESHOLD_G, ImpactDetector.clamp(0.2f), 0.001f);
        assertEquals(ImpactDetector.MIN_THRESHOLD_G,
                ImpactDetector.clamp(Float.NaN), 0.001f);
        assertEquals(2.5f, ImpactDetector.clamp(2.5f), 0.001f);
        assertEquals(ImpactDetector.MAX_THRESHOLD_G, ImpactDetector.clamp(99f), 0.001f);
    }

    @Test
    public void higherThresholdNeedsStrongerImpact() {
        ImpactDetector.Gate gate = newGate();
        // 2 g — удар для мягкого порога, но не для «жёсткого» в 4 g.
        assertFalse(runOverload(gate, 0L, 4.0f));
        assertTrue(runOverload(gate, 0L, 1.5f));
    }

    @Test
    public void defaultThresholdMatchesDocs() {
        assertEquals(2.5f, ImpactDetector.DEFAULT_THRESHOLD_G, 0.001f);
        assertEquals(1500L, ImpactDetector.DEBOUNCE_MS, 0.001f);
    }

    // ------------------------------------------------------------------ помощники

    private static ImpactDetector.Gate newGate() {
        return new ImpactDetector.Gate(REQUIRED, DEBOUNCE);
    }

    private static boolean runOverload(ImpactDetector.Gate gate, long startMs) {
        return runOverload(gate, startMs, THRESHOLD);
    }

    /** REQUIRED отсчётов в 3 g, начиная с startMs; true, если серия завершилась ударом. */
    private static boolean runOverload(ImpactDetector.Gate gate, long startMs, float threshold) {
        boolean triggered = false;
        for (int i = 0; i < REQUIRED; i++) {
            triggered |= gate.feed(3.0f, threshold, startMs + i * STEP);
        }
        return triggered;
    }
}
