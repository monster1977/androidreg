package com.example.dashcam.effects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Профили ночного режима: значение по умолчанию и монотонность ступеней. */
public class NightModeTest {

    @Test
    public void defaultRepeatsAutoBrightOf146() {
        NightMode mode = NightMode.of(NightMode.DEFAULT);
        assertEquals(NightMode.NORMAL, NightMode.DEFAULT);
        assertEquals(70f, mode.targetY, 0.001f);
        assertEquals(60f, mode.onY, 0.001f);
        assertEquals(88f, mode.offY, 0.001f);
        assertEquals(3.0f, mode.maxGain, 0.001f);
        assertEquals(0.25f, mode.stepUp, 0.001f);
        assertEquals(0.5f, mode.stepDown, 0.001f);
    }

    @Test
    public void outOfRangeClampsToNearestLevel() {
        assertSame(NightMode.of(NightMode.SOFT), NightMode.of(-5));
        assertSame(NightMode.of(NightMode.MAXIMUM), NightMode.of(99));
    }

    @Test
    public void higherLevelIsMoreAggressive() {
        NightMode previous = NightMode.of(0);
        for (int level = 1; level < NightMode.COUNT; level++) {
            NightMode mode = NightMode.of(level);
            assertEquals(level, mode.level);
            // Хочет светлее, начинает раньше и вытягивает сильнее.
            assertTrue(mode.targetY > previous.targetY);
            assertTrue(mode.onY > previous.onY);
            assertTrue(mode.offY > previous.offY);
            assertTrue(mode.maxGain > previous.maxGain);
            assertTrue(mode.stepUp >= previous.stepUp);
            assertTrue(mode.stepDown >= previous.stepDown);
            previous = mode;
        }
    }

    @Test
    public void profileValuesAreConsistent() {
        for (int level = 0; level < NightMode.COUNT; level++) {
            NightMode mode = NightMode.of(level);
            // Гистерезис: выходим из осветления позже, чем входим.
            assertTrue(mode.onY < mode.offY);
            // Цель выше порога включения, иначе усиление было бы меньше 1×.
            assertTrue(mode.targetY > mode.onY);
            assertTrue(mode.maxGain >= 1f);
            assertTrue(mode.stepUp > 0f);
            assertTrue(mode.stepDown > 0f);
        }
    }
}
