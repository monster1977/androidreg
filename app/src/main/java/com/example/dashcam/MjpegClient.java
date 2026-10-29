package com.example.dashcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Клиент MJPEG-потока (multipart/x-mixed-replace) — самая частая выдача недорогих WiFi-камер
 * заднего хода. Читает JPEG-кадры из HTTP-ответа и отдаёт готовые {@link Bitmap}.
 *
 * <p>Работает в отдельном потоке, сам переподключается при обрыве.</p>
 */
public class MjpegClient extends Thread {

    public interface FrameListener {
        void onFrame(Bitmap frame);

        void onError(String message);
    }

    private static final String TAG = "MjpegClient";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
    private static final long RETRY_DELAY_MS = 2000L;

    private final String url;
    private final FrameListener listener;
    private volatile boolean running = true;
    /** Текущее соединение — его нужно рвать, чтобы выйти из блокирующегося read(). */
    private volatile HttpURLConnection activeConnection;

    public MjpegClient(String url, FrameListener listener) {
        super("mjpeg-client");
        this.url = url;
        this.listener = listener;
    }

    public void stopClient() {
        running = false;
        // interrupt() не пробивает блокирующийся read() в сокете — закрываем соединение явно (K7).
        HttpURLConnection connection = activeConnection;
        activeConnection = null;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception ignored) {
                // Уже закрыт.
            }
        }
        interrupt();
    }

    @Override
    public void run() {
        while (running) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("Connection", "close");
                activeConnection = connection;
                connection.connect();

                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    notifyError("HTTP " + code);
                    pause(RETRY_DELAY_MS);
                    continue;
                }
                try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                    readFrames(input);
                }
                // Камера закрыла поток (EOF): раньше цикл тут переподключался мгновенно —
                // получался горячий цикл на 100% CPU. Пауза обязательна (K7).
                if (running) {
                    pause(RETRY_DELAY_MS);
                }
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "Поток оборвался", e);
                    notifyError(e.getClass().getSimpleName()
                            + (e.getMessage() != null ? ": " + e.getMessage() : ""));
                    pause(RETRY_DELAY_MS);
                }
            } finally {
                if (connection != null) {
                    if (activeConnection == connection) {
                        activeConnection = null;
                    }
                    connection.disconnect();
                }
            }
        }
    }

    /** Ищет в потоке JPEG-кадры по маркерам SOI (FF D8) и EOI (FF D9). */
    private void readFrames(InputStream input) throws Exception {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(64 * 1024);
        int previous = -1;
        boolean inside = false;
        int current;
        while (running && (current = input.read()) != -1) {
            if (!inside) {
                if (previous == 0xFF && current == 0xD8) {
                    inside = true;
                    frame.reset();
                    frame.write(0xFF);
                    frame.write(0xD8);
                }
            } else if (previous == 0xFF && current == 0xD9) {
                frame.write(current);
                byte[] data = frame.toByteArray();
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap != null) {
                    if (listener != null) {
                        listener.onFrame(bitmap);
                    }
                } else {
                    Log.w(TAG, "Кадр не декодировался, размер=" + data.length);
                }
                inside = false;
                frame.reset();
            } else {
                frame.write(current);
                if (frame.size() > MAX_FRAME_BYTES) {
                    inside = false;
                    frame.reset();
                }
            }
            previous = current;
        }
    }

    private void notifyError(String message) {
        if (listener != null) {
            listener.onError(message);
        }
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

