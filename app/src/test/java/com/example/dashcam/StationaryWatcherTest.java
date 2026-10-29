package com.example.dashcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StationaryWatcherTest {

    private static final long MIN = 60_000L;
    private static final double LAT = 57.2047;
    private static final double LON = 65.5626;
    /** ~111 м по широте. */
    private static final double DEG_111M = 0.001;
    private static final long TIMEOUT = 5 * MIN;

    @Test
    public void noFixNeverStops() {
        StationaryWatcher watcher = new StationaryWatcher();
        watcher.reset(0L);
        assertFalse(watcher.shouldStop(30 * MIN, TIMEOUT));
    }

    @Test
    public void stationaryStopsAfterTimeout() {
        StationaryWatcher watcher = new StationaryWatcher();
        watcher.reset(0L);
        assertFalse(watcher.onLocation(LAT, LON, 5f, 0L));
        // Фиксы продолжают приходить, координаты не меняются.
        watcher.onLocation(LAT, LON, 5f, 4 * MIN);
        assertFalse(watcher.shouldStop(4 * MIN, TIMEOUT));
        watcher.onLocation(LAT, LON, 5f, 5 * MIN);
        assertTrue(watcher.shouldStop(5 * MIN, TIMEOUT));
    }

    @Test
    public void movementRestartsCountdown() {
        StationaryWatcher watcher = new StationaryWatcher();
        watcher.reset(0L);
        watcher.onLocation(LAT, LON, 5f, 0L);
        assertTrue(watcher.onLocation(LAT + 0.0005, LON, 5f, 2 * MIN)); // ~55 м
        watcher.onLocation(LAT + 0.0005, LON, 5f, 4 * MIN);
        assertFalse(watcher.shouldStop(4 * MIN, TIMEOUT));
        watcher.onLocation(LAT + 0.0005, LON, 5f, 7 * MIN);
        assertTrue(watcher.shouldStop(7 * MIN, TIMEOUT));
    }

    @Test
    public void smallDriftIsNotMovement() {
        StationaryWatcher watcher = new StationaryWatcher();
        watcher.reset(0L);
        watcher.onLocation(LAT, LON, 10f, 0L);
        assertFalse(watcher.onLocation(LAT + 0.00009, LON, 10f, 2 * MIN)); // ~10 м
        watcher.onLocation(LAT, LON, 10f, 5 * MIN);
        assertTrue(watcher.shouldStop(5 * MIN, TIMEOUT));
    }

    @Test
    public void staleFixDoesNotStop() {
        StationaryWatcher watcher = new StationaryWatcher();
        watcher.reset(0L);
        watcher.onLocation(LAT, LON, 5f, 0L);
        // GPS пропал (тоннель): фикс старше FIX_FRESH_MS, судить о стоянке нельзя.
        assertFalse(watcher.shouldStop(9 * MIN, TIMEOUT));
    }

    @Test
    public void inaccurateFixIsIgnored() {
        StationaryWatcher watcher = new StationaryWatcher();
        watcher.reset(0L);
        watcher.onLocation(LAT, LON, 500f, 0L);
        assertFalse(watcher.shouldStop(30 * MIN, TIMEOUT));
    }

    @Test
    public void resetStartsCountdownAgain() {
        StationaryWatcher watcher = new StationaryWatcher();
        watcher.reset(0L);
        watcher.onLocation(LAT, LON, 5f, 0L);
        watcher.onLocation(LAT, LON, 5f, TIMEOUT);
        assertTrue(watcher.shouldStop(TIMEOUT, TIMEOUT));
        watcher.reset(TIMEOUT);
        assertFalse(watcher.shouldStop(TIMEOUT + 30 * MIN, TIMEOUT));
    }

    @Test
    public void distanceIsReasonable() {
        double meters = StationaryWatcher.distanceMeters(LAT, LON, LAT + DEG_111M, LON);
        assertEquals(111.0, meters, 1.5);
    }
}
