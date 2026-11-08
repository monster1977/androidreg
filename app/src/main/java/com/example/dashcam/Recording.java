package com.example.dashcam;

import android.net.Uri;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Одна запись регистратора из общей галереи (MediaStore). */
public class Recording {

    private static final SimpleDateFormat NAME_FORMAT =
            new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault());

    public final Uri uri;
    public final String displayName;
    public final long sizeBytes;
    public final long createdAt;
    public long durationMs;

    public Recording(Uri uri, String displayName, long sizeBytes, long createdAt, long durationMs) {
        this.uri = uri;
        this.displayName = displayName;
        this.sizeBytes = sizeBytes;
        this.createdAt = createdAt;
        this.durationMs = durationMs;
    }

    public String title() {
        if (createdAt <= 0L) {
            return displayName;
        }
        return timeString(createdAt);
    }

    /** Время в том же виде, что и заголовок записи — для строк состояния (v1.45). */
    public static synchronized String timeString(long timeMs) {
        return NAME_FORMAT.format(new Date(timeMs));
    }

    /** Защищена ли запись от кольцевой перезаписи (метка УШИБ в имени файла). */
    public boolean isProtected() {
        return StorageUtils.isProtected(displayName);
    }

    public String subtitle() {
        String size = StorageUtils.formatSize(sizeBytes);
        if (durationMs <= 0L) {
            return size;
        }
        return StorageUtils.formatDuration(durationMs) + " · " + size;
    }
}
