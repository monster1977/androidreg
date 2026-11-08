package com.example.dashcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Решение кольцевой перезаписи с учётом защищённых Г-сенсором записей (v1.45).
 * {@link StorageUtils#ringPlan} — чистая функция без MediaStore, поэтому её можно
 * проверить на синтетических наборах файлов; на телефоне такой подбор не повторить.
 */
public class RingPlanTest {

    private static final long MB = 1024L * 1024L;
    /** Лимит времени отключён: решает только объём. */
    private static final long NO_TIME_RING = 0L;
    private static final String KEEP_NONE = null;

    @Test
    public void protectedMarkerRoundTrip() {
        String name = "DASHCAM_20261108_114444.mp4";
        String marked = StorageUtils.withProtectedMarker(name);
        assertEquals("DASHCAM_20261108_114444_УШИБ.mp4", marked);
        assertTrue(StorageUtils.isProtected(marked));
        assertFalse(StorageUtils.isProtected(name));
        // Повторное «защищение» не должно плодить метку.
        assertEquals(marked, StorageUtils.withProtectedMarker(marked));
    }

    @Test
    public void underLimitsDeletesNothing() {
        List<Recording> files = files(
                file("DASHCAM_20261108_120000.mp4", 100 * MB, 300_000L),
                file("DASHCAM_20261108_115000.mp4", 100 * MB, 300_000L));
        assertTrue(ringPlan(files, 400 * MB, NO_TIME_RING, KEEP_NONE).isEmpty());
    }

    @Test
    public void oldestUnprotectedGoesFirst() {
        List<Recording> files = files(
                file("DASHCAM_20261108_120000.mp4", 100 * MB, 300_000L),
                file("DASHCAM_20261108_115000.mp4", 100 * MB, 300_000L),
                file("DASHCAM_20261108_114000.mp4", 100 * MB, 300_000L),
                file("DASHCAM_20261108_113000.mp4", 100 * MB, 300_000L));
        List<Recording> doomed = ringPlan(files, 300 * MB, NO_TIME_RING, KEEP_NONE);
        assertEquals(1, doomed.size());
        assertEquals("DASHCAM_20261108_113000.mp4", doomed.get(0).displayName);
    }

    @Test
    public void protectedFileSurvivesRing() {
        // Самый старый файл защищён ударом — кольцо должно съесть следующий по старшинству.
        List<Recording> files = files(
                file("DASHCAM_20261108_120000.mp4", 100 * MB, 300_000L),
                file("DASHCAM_20261108_115000.mp4", 100 * MB, 300_000L),
                protectedFile("DASHCAM_20261108_114000.mp4", 100 * MB, 300_000L),
                protectedFile("DASHCAM_20261108_113000.mp4", 100 * MB, 300_000L));
        List<Recording> doomed = ringPlan(files, 300 * MB, NO_TIME_RING, KEEP_NONE);
        assertEquals(1, doomed.size());
        assertEquals("DASHCAM_20261108_115000.mp4", doomed.get(0).displayName);
    }

    @Test
    public void protectedFilesAreNeverDoomed() {
        // Защищённые записи не влезают в лимит: решение «удалять нечего» — переполнение
        // кольца видит prune(), а не ringPlan().
        List<Recording> files = files(
                protectedFile("DASHCAM_20261108_120000.mp4", 100 * MB, 300_000L),
                protectedFile("DASHCAM_20261108_115000.mp4", 100 * MB, 300_000L),
                protectedFile("DASHCAM_20261108_114000.mp4", 100 * MB, 300_000L));
        assertTrue(ringPlan(files, 200 * MB, NO_TIME_RING, KEEP_NONE).isEmpty());
    }

    @Test
    public void currentSegmentIsNeverDoomed() {
        List<Recording> files = files(
                file("DASHCAM_20261108_120000.mp4", 100 * MB, 300_000L),
                file("DASHCAM_20261108_115000.mp4", 100 * MB, 300_000L),
                file("DASHCAM_20261108_114000.mp4", 100 * MB, 300_000L));
        List<Recording> doomed = ringPlan(files, 200 * MB, NO_TIME_RING,
                "DASHCAM_20261108_114000.mp4");
        assertEquals(1, doomed.size());
        assertEquals("DASHCAM_20261108_115000.mp4", doomed.get(0).displayName);
    }

    @Test
    public void keepMatchesAlreadyMarkedName() {
        // Сервис передаёт имя до защиты, файл в MediaStore уже с меткой УШИБ.
        List<Recording> files = files(
                file("DASHCAM_20261108_120000.mp4", 100 * MB, 300_000L),
                protectedFile("DASHCAM_20261108_114000.mp4", 100 * MB, 300_000L));
        List<Recording> doomed = ringPlan(files, 100 * MB, NO_TIME_RING,
                "DASHCAM_20261108_114000.mp4");
        assertEquals(1, doomed.size());
        assertEquals("DASHCAM_20261108_120000.mp4", doomed.get(0).displayName);
    }

    @Test
    public void timeRingDeletesOldestByDuration() {
        // Объём разрешён большой — решает только лимит времени кольца.
        List<Recording> files = files(
                file("DASHCAM_20261108_120000.mp4", 10 * MB, 600_000L),
                file("DASHCAM_20261108_115000.mp4", 10 * MB, 600_000L));
        List<Recording> doomed = ringPlan(files, 100 * MB, 900L, KEEP_NONE);
        assertEquals(1, doomed.size());
        assertEquals("DASHCAM_20261108_115000.mp4", doomed.get(0).displayName);
    }

    @Test
    public void timeRingKeepsProtectedAndFreesUnprotected() {
        // Защищённый файл из кольца выкинуть нельзя — место освобождается другими файлами.
        List<Recording> files = files(
                file("DASHCAM_20261108_121000.mp4", 10 * MB, 300_000L),
                file("DASHCAM_20261108_120000.mp4", 10 * MB, 300_000L),
                protectedFile("DASHCAM_20261108_114000.mp4", 10 * MB, 600_000L));
        List<Recording> doomed = ringPlan(files, 100 * MB, 900L, KEEP_NONE);
        assertEquals(1, doomed.size());
        Recording doomed1 = doomed.get(0);
        assertFalse("защищённую запись удалять нельзя", doomed1.isProtected());
        assertEquals("DASHCAM_20261108_120000.mp4", doomed1.displayName);
    }

    // ------------------------------------------------------------------ помощники

    private static List<Recording> files(Recording... freshestFirst) {
        // ringPlan ожидает порядок «свежие сверху», как в списке галереи.
        return new ArrayList<>(Arrays.asList(freshestFirst));
    }

    private static Recording file(String name, long sizeBytes, long durationMs) {
        return new Recording(null, name, sizeBytes, 0L, durationMs);
    }

    private static Recording protectedFile(String name, long sizeBytes, long durationMs) {
        return new Recording(null, StorageUtils.withProtectedMarker(name),
                sizeBytes, 0L, durationMs);
    }

    private static List<Recording> ringPlan(List<Recording> files, long sizeLimit,
                                            long ringSeconds, String keepDisplayName) {
        long totalSize = 0L;
        long totalDurationMs = 0L;
        for (Recording recording : files) {
            totalSize += recording.sizeBytes;
            totalDurationMs += recording.durationMs;
        }
        return StorageUtils.ringPlan(files, totalSize, totalDurationMs,
                sizeLimit, ringSeconds, keepDisplayName);
    }
}
