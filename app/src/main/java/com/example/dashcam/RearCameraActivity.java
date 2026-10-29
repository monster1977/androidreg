package com.example.dashcam;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/**
 * Экран камеры заднего хода.
 *
 * <p>Поддерживает два типовых варианта WiFi-камер: MJPEG по HTTP (читаем поток сами) и RTSP
 * (отдаём Media3 ExoPlayer). Адрес задаётся в настройках.</p>
 */
@OptIn(markerClass = UnstableApi.class)
public class RearCameraActivity extends AppCompatActivity implements MjpegClient.FrameListener {

    private ImageView imageStream;
    private PlayerView playerStream;
    private TextView textStatus;

    private MjpegClient client;
    private ExoPlayer player;
    private String url;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onResume() {
        super.onResume();
        ScreenOn.apply(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenOrientation.apply(this);
        setContentView(R.layout.activity_rear_camera);

        imageStream = findViewById(R.id.imageStream);
        playerStream = findViewById(R.id.playerStream);
        textStatus = findViewById(R.id.textStatus);
        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());

        url = Prefs.rearCameraUrl(this);
        showStatus(getString(R.string.rear_camera_connecting, url));
        if (url.toLowerCase().startsWith("rtsp")) {
            startRtsp();
        } else {
            startMjpeg();
        }
    }

    @Override
    protected void onStop() {
        stopStreams();
        super.onStop();
    }

    private void startMjpeg() {
        client = new MjpegClient(url, this);
        client.start();
    }

    private void startRtsp() {
        playerStream.setVisibility(View.VISIBLE);
        imageStream.setVisibility(View.GONE);
        player = new ExoPlayer.Builder(this).build();
        playerStream.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    textStatus.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                showStatus(getString(R.string.rear_camera_error, error.getErrorCodeName()));
            }
        });
        player.setMediaItem(MediaItem.fromUri(url));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private void stopStreams() {
        if (client != null) {
            client.stopClient();
            client = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (playerStream != null) {
            playerStream.setPlayer(null);
        }
    }

    private void showStatus(String text) {
        textStatus.setText(text);
        textStatus.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------- MjpegClient callbacks

    @Override
    public void onFrame(Bitmap frame) {
        handler.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            imageStream.setImageBitmap(frame);
            textStatus.setVisibility(View.GONE);
        });
    }

    @Override
    public void onError(String message) {
        handler.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (imageStream.getDrawable() == null) {
                showStatus(getString(R.string.rear_camera_error, message)
                        + "\n\n" + getString(R.string.rear_camera_hint));
            }
        });
    }
}

