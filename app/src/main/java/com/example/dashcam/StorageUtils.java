package com.example.dashcam;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Записи живут в общей галерее телефона: {@code DCIM/Dashcam} через MediaStore, поэтому их
 * видно в системной галерее, Google Фото и файловых менеджерах.
 */
public final class StorageUtils {

    private static final String TAG = "StorageUtils";

    /** Папка записей в общей галерее. */
    public static final String DIR_NAME = "Dashcam";

    /** Все файлы приложения начинаются с этого префикса — по нему их и находим. */
    public static final String NAME_PREFIX = "DASHCAM_";

    public static final String MIME_TYPE = "video/mp4";

    private StorageUtils() {
    }

    public static Uri collection() {
        return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    /** Имя съёмного тома (SD-карты) или {@code null}, если её нет. */
    public static String sdVolumeName(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        try {
            for (String volume : MediaStore.getExternalVolumeNames(context)) {
                if (!MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume)) {
                    return volume;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Не удалось получить список томов", e);
        }
        return null;
    }

    public static boolean hasSdCard(Context context) {
        return sdVolumeName(context) != null;
    }

    /** Куда пишутся новые записи: SD-карта, если она выбрана и доступна, иначе внутренняя память. */
    public static Uri recordingCollection(Context context) {
        if (Prefs.recordToSdCard(context)) {
            String volume = sdVolumeName(context);
            if (volume != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.i(TAG, "Пишем на SD-карту: " + volume);
                return MediaStore.Video.Media.getContentUri(volume);
            }
            Log.w(TAG, "SD-карта недоступна — пишем во внутреннюю память");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        }
        return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    /** Все тома, где могут лежать записи приложения: внутренняя память и SD-карта. */
    private static List<Uri> allCollections(Context context) {
        List<Uri> collections = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                for (String volume : MediaStore.getExternalVolumeNames(context)) {
                    collections.add(MediaStore.Video.Media.getContentUri(volume));
                }
            } catch (Exception e) {
                Log.w(TAG, "Не удалось получить список томов", e);
            }
        }
        if (collections.isEmpty()) {
            collections.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        }
        return collections;
    }

    /** Свободное место на SD-карте в байтах или -1, если карта недоступна. */
    public static long sdCardFreeBytes(Context context) {
        File[] dirs = androidx.core.content.ContextCompat.getExternalFilesDirs(context, null);
        if (dirs != null && dirs.length > 1 && dirs[1] != null) {
            try {
                return dirs[1].getUsableSpace();
            } catch (Exception e) {
                Log.w(TAG, "Не удалось узнать свободное место", e);
            }
        }
        return -1L;
    }

    public static String relativePath() {
        return Environment.DIRECTORY_DCIM + "/" + DIR_NAME;
    }

    /** ContentValues для новой записи (использует и сервис записи, и перенос старых файлов). */
    public static ContentValues newVideoValues(String displayName, long dateTaken) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE);
        values.put(MediaStore.Video.Media.DATE_TAKEN, dateTaken);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, relativePath());
        }
        return values;
    }

    /** Список записей приложения, свежие сверху. */
    public static List<Recording> listRecordings(Context context) {
        List<Recording> result = new ArrayList<>();
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DURATION,
        };

        StringBuilder selection = new StringBuilder(MediaStore.Video.Media.DISPLAY_NAME + " LIKE ?");
        List<String> args = new ArrayList<>();
        args.add(NAME_PREFIX + "%");

        String active = RecordingService.getActiveRecordingName();
        if (active != null) {
            selection.append(" AND ").append(MediaStore.Video.Media.DISPLAY_NAME).append(" <> ?");
            args.add(active);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Пока CameraX не закончила файл, запись помечена как pending и не готова к проигрыванию.
            selection.append(" AND ").append(MediaStore.Video.Media.IS_PENDING).append(" = 0");
        }

        // Записи могут лежать и во внутренней памяти, и на SD-карте — обходим все тома.
        for (Uri uri : allCollections(context)) {
            try (Cursor cursor = context.getContentResolver().query(
                    uri,
                    projection,
                    selection.toString(),
                    args.toArray(new String[0]),
                    MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                if (cursor == null) {
                    continue;
                }
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
                int durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String name = cursor.getString(nameColumn);
                    long size = cursor.isNull(sizeColumn) ? 0L : cursor.getLong(sizeColumn);
                    long dateSeconds = cursor.isNull(dateColumn) ? 0L : cursor.getLong(dateColumn);
                    long duration = 0L;
                    if (durationColumn >= 0 && !cursor.isNull(durationColumn)) {
                        duration = cursor.getLong(durationColumn);
                    }
                    result.add(new Recording(
                            ContentUris.withAppendedId(uri, id),
                            name,
                            size,
                            dateSeconds * 1000L,
                            duration));
                }
            } catch (Exception e) {
                Log.w(TAG, "Не удалось прочитать список записей", e);
            }
        }

        result.sort((first, second) -> Long.compare(second.createdAt, first.createdAt));
        return result;
    }

    public static long totalSize(Context context) {
        long total = 0;
        for (Recording recording : listRecordings(context)) {
            total += recording.sizeBytes;
        }
        return total;
    }

    /**
     * Удаляет «осиротевшие» записи: если процесс умер до финализации, файл навсегда остаётся
     * с {@code IS_PENDING=1} — его не видит галерея и не трогает кольцо. Сиротами считаем
     * pending-записи старше суток (столько однозначно не пишется); текущую запись не трогаем.
     */
    public static void cleanupStalePending(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        cleanupOrphanFiles(context);
        long cutoffSeconds = System.currentTimeMillis() / 1000L - 24L * 60L * 60L;
        String active = RecordingService.getActiveRecordingName();
        String[] projection = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME};
        String selection = MediaStore.Video.Media.DISPLAY_NAME + " LIKE ? AND "
                + MediaStore.Video.Media.IS_PENDING + " = 1 AND "
                + MediaStore.Video.Media.DATE_ADDED + " < ?";
        String[] args = {NAME_PREFIX + "%", String.valueOf(cutoffSeconds)};

        for (Uri uri : allCollections(context)) {
            try (Cursor cursor = context.getContentResolver().query(
                    uri, projection, selection, args, null)) {
                if (cursor == null) {
                    continue;
                }
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(
                        MediaStore.Video.Media.DISPLAY_NAME);
                while (cursor.moveToNext()) {
                    String name = cursor.getString(nameColumn);
                    if (name != null && name.equals(active)) {
                        continue;
                    }
                    long id = cursor.getLong(idColumn);
                    context.getContentResolver().delete(
                            ContentUris.withAppendedId(uri, id), null, null);
                    Log.i(TAG, "Удалили незавершённую запись-сироту: " + name);
                }
            } catch (Exception e) {
                Log.w(TAG, "Не удалось почистить pending-записи", e);
            }
        }
    }

    /**
     * Убирает файлы незавершённых записей, оставшиеся после смерти процесса.
     *
     * <p>CameraX пишет текущий сегмент как {@code .pending-<время>-DASHCAM_...mp4}, а при
     * финализации переименовывает. Если процесс убили (переустановка, crash), файл остаётся
     * навсегда: имя начинается с точки, поэтому MediaStore его не индексирует, галерея и
     * кольцо его не видят, а чистка по строкам MediaStore до него не достаёт. За день таких
     * файлов накопилось 2,2 ГБ. Удаляем старше 2 часов: сегмент пишется максимум 10 минут,
     * поэтому это точно мусор, а не активная запись.</p>
     */
    private static void cleanupOrphanFiles(Context context) {
        long cutoff = System.currentTimeMillis() - 2L * 60L * 60L * 1000L;
        for (File dir : recordingDirs(context)) {
            File[] files = dir.listFiles();
            if (files == null) {
                Log.w(TAG, "Каталог записей недоступен для чтения: " + dir
                        + ", canRead=" + dir.canRead());
                continue;
            }
            Log.i(TAG, "Проверяю осиротевшие записи: " + dir + ", файлов " + files.length);
            for (File file : files) {
                if (!file.isFile() || !file.getName().startsWith(".pending-")
                        || file.lastModified() >= cutoff) {
                    continue;
                }
                long sizeMb = file.length() / (1024L * 1024L);
                if (file.delete()) {
                    Log.i(TAG, "Удалили осиротевший файл незавершённой записи: "
                            + file.getName() + " (" + sizeMb + " МБ)");
                } else {
                    Log.w(TAG, "Не удалось удалить осиротевший файл: " + file.getName());
                }
            }
        }
    }

    /** Каталоги записей на диске: внутренняя память и, если есть, SD-карта. */
    private static List<File> recordingDirs(Context context) {
        List<File> dirs = new ArrayList<>();
        dirs.add(new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), DIR_NAME));
        File[] appDirs = androidx.core.content.ContextCompat.getExternalFilesDirs(context, null);
        if (appDirs != null) {
            for (int i = 1; i < appDirs.length; i++) {
                File appDir = appDirs[i];
                if (appDir == null) {
                    continue;
                }
                String path = appDir.getAbsolutePath();
                int cut = path.indexOf("/Android/");
                if (cut > 0) {
                    File volume = new File(path.substring(0, cut));
                    dirs.add(new File(new File(volume, Environment.DIRECTORY_DCIM), DIR_NAME));
                }
            }
        }
        return dirs;
    }

    public static long storageLimitBytes(Context context) {
        return (long) Prefs.maxStorageGb(context) * 1024L * 1024L * 1024L;
    }

    /** Минимум свободного места, без которого новую запись начинать бессмысленно. */
    public static final long MIN_FREE_BYTES = 500L * 1024L * 1024L;

    /**
     * Свободное место на томе, куда пойдёт запись (SD-карта, если она выбрана, иначе
     * внутренняя память). Возвращает -1, если узнать не удалось.
     */
    public static long freeBytesFor(Context context) {
        if (Prefs.recordToSdCard(context)) {
            long sdFree = sdCardFreeBytes(context);
            if (sdFree >= 0) {
                return sdFree;
            }
        }
        try {
            // Том приложения совпадает с томом DCIM: тот же файловый блок.
            File dir = context.getExternalFilesDir(null);
            if (dir != null) {
                return dir.getUsableSpace();
            }
        } catch (Exception e) {
            Log.w(TAG, "Не удалось узнать свободное место", e);
        }
        return -1L;
    }

    /**
     * Кольцевая запись: удаляет самые старые файлы, пока не выполнятся оба условия —
     * лимит объёма и лимит времени хранения (иначе файлы копятся бесконечно).
     *
     * @param keepDisplayName имя файла, который сейчас пишется и не должен быть удалён
     */
    public static void prune(Context context, String keepDisplayName) {
        long sizeLimit = storageLimitBytes(context);
        long ringSeconds = (long) Prefs.ringMinutes(context) * 60L;

        List<Recording> all = listRecordings(context);
        long totalSize = 0;
        long totalDurationMs = 0;
        for (Recording recording : all) {
            totalSize += recording.sizeBytes;
            totalDurationMs += durationOf(context, recording);
        }

        boolean overSize = totalSize > sizeLimit;
        boolean overTime = ringSeconds > 0 && totalDurationMs > ringSeconds * 1000L;
        if (!overSize && !overTime) {
            return;
        }

        // Список отсортирован по дате убывания — удаляем с конца, то есть самые старые.
        for (int i = all.size() - 1; i >= 0; i--) {
            if (totalSize <= sizeLimit && (ringSeconds <= 0 || totalDurationMs <= ringSeconds * 1000L)) {
                break;
            }
            Recording recording = all.get(i);
            if (keepDisplayName != null && keepDisplayName.equals(recording.displayName)) {
                continue;
            }
            long duration = durationOf(context, recording);
            if (delete(context, recording)) {
                Log.i(TAG, "Кольцо: удаляем " + recording.displayName);
                totalSize -= recording.sizeBytes;
                totalDurationMs -= duration;
            }
        }
    }

    /** Длительность записи: сначала из MediaStore, при отсутствии — через MediaMetadataRetriever. */
    private static long durationOf(Context context, Recording recording) {
        if (recording.durationMs > 0L) {
            return recording.durationMs;
        }
        long duration = readDurationMs(context, recording.uri);
        recording.durationMs = duration;
        return duration;
    }

    public static boolean delete(Context context, Recording recording) {
        try {
            return context.getContentResolver().delete(recording.uri, null, null) > 0;
        } catch (SecurityException e) {
            Log.w(TAG, "Нет прав на удаление записи", e);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Не удалось удалить запись", e);
            return false;
        }
    }

    public static long readDurationMs(Context context, Uri uri) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String value = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value != null ? Long.parseLong(value) : 0L;
        } catch (Exception e) {
            return 0L;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Уже освобождён.
            }
        }
    }

    /**
     * Переносит записи из приватной папки прежних версий ({@code Android/data/.../Movies/Dashcam})
     * в общую галерею, чтобы старые файлы не потерялись.
     */
    public static void migrateLegacyRecordings(Context context) {
        File legacyDir = legacyDir(context);
        File[] files = legacyDir.listFiles((dir, name) -> name.toLowerCase(Locale.US).endsWith(".mp4"));
        if (files == null || files.length == 0) {
            return;
        }
        Log.i(TAG, "Переносим в галерею файлов: " + files.length);
        for (File file : files) {
            migrateOne(context, file);
        }
        File[] left = legacyDir.listFiles();
        if (left != null && left.length == 0) {
            //noinspection ResultOfMethodCallIgnored
            legacyDir.delete();
        }
    }

    private static File legacyDir(Context context) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) {
            base = context.getFilesDir();
        }
        return new File(base, DIR_NAME);
    }

    private static void migrateOne(Context context, File source) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            migrateOnePreQ(context, source);
            return;
        }
        Uri uri = null;
        try {
            ContentValues values = newVideoValues(source.getName(), source.lastModified());
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            uri = context.getContentResolver().insert(collection(), values);
            if (uri == null) {
                return;
            }
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    return;
                }
                copy(in, out);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, done, null, null);
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        } catch (Exception e) {
            Log.w(TAG, "Не удалось перенести " + source.getName(), e);
            if (uri != null) {
                try {
                    context.getContentResolver().delete(uri, null, null);
                } catch (Exception ignored) {
                    // Ничего не поделать.
                }
            }
        }
    }

    private static void migrateOnePreQ(Context context, File source) {
        File destDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), DIR_NAME);
        File dest = new File(destDir, source.getName());
        try {
            //noinspection ResultOfMethodCallIgnored
            destDir.mkdirs();
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(dest)) {
                copy(in, out);
            }
            MediaScannerConnection.scanFile(context, new String[]{dest.getAbsolutePath()}, null, null);
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        } catch (Exception e) {
            Log.w(TAG, "Не удалось перенести " + source.getName(), e);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " Б";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.0f КБ", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f МБ", bytes / 1048576.0);
        }
        return String.format(Locale.US, "%.2f ГБ", bytes / 1073741824.0);
    }

    public static String formatDuration(long millis) {
        if (millis <= 0) {
            return "00:00";
        }
        long totalSeconds = millis / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
