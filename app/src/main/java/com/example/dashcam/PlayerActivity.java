package com.example.dashcam;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.google.android.material.appbar.MaterialToolbar;

/**
 * Просмотр одной записи из общей галереи.
 *
 * <p>Воспроизведение через ExoPlayer (Media3): он тянет больше форматов и контейнеров, чем
 * системный {@code VideoView}, и отдаёт понятный код ошибки, если файл действительно битый.</p>
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_NAME = "extra_name";
    public static final String EXTRA_SIZE = "extra_size";
    private static final String STATE_POSITION = "state_position";

    private ExoPlayer player;
    private PlayerView playerView;
    private ImageButton buttonPlayPause;
    private TextView textError;
    private Uri uri;
    private String displayName;
    private long savedPosition;

    @Override
    protected void onResume() {
        super.onResume();
        ScreenOn.apply(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenOrientation.apply(this);
        setContentView(R.layout.activity_player);

        if (savedInstanceState != null) {
            savedPosition = savedInstanceState.getLong(STATE_POSITION, 0L);
        }

        String uriString = getIntent().getStringExtra(EXTRA_URI);
        if (uriString == null) {
            finish();
            return;
        }
        uri = Uri.parse(uriString);
        displayName = getIntent().getStringExtra(EXTRA_NAME);
        if (displayName == null) {
            displayName = uri.getLastPathSegment();
        }
        long size = getIntent().getLongExtra(EXTRA_SIZE, 0L);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        textError = findViewById(R.id.textError);
        TextView textInfo = findViewById(R.id.textInfo);
        textInfo.setText(size > 0
                ? displayName + " · " + StorageUtils.formatSize(size)
                : displayName);

        buttonPlayPause = findViewById(R.id.buttonPlayPause);
        buttonPlayPause.setOnClickListener(v -> togglePlayback());

        playerView = findViewById(R.id.playerView);
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseIcon(isPlaying);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                showPlaybackError(error.getErrorCodeName());
            }
        });

        player.setMediaItem(MediaItem.fromUri(uri));
        player.setPlayWhenReady(true);
        if (savedPosition > 0) {
            player.seekTo(savedPosition);
        }
        player.prepare();

        findViewById(R.id.buttonExternal).setOnClickListener(v -> openInExternalPlayer());
        findViewById(R.id.buttonShare).setOnClickListener(v -> share());
        findViewById(R.id.buttonDelete).setOnClickListener(v -> confirmDelete());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (player != null) {
            outState.putLong(STATE_POSITION, player.getCurrentPosition());
        }
    }

    @Override
    protected void onStop() {
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (playerView != null) {
            playerView.setPlayer(null);
        }
        super.onDestroy();
    }

    private void togglePlayback() {
        if (player == null) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
        } else {
            if (player.getPlaybackState() == Player.STATE_IDLE) {
                player.prepare();
            }
            player.play();
        }
    }

    private void updatePlayPauseIcon(boolean playing) {
        buttonPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        buttonPlayPause.setContentDescription(
                getString(playing ? R.string.action_pause : R.string.action_play));
    }

    private void showPlaybackError(String code) {
        String message = getString(R.string.playback_error, code);
        textError.setText(message + "\n" + getString(R.string.playback_error_hint));
        textError.setVisibility(View.VISIBLE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        updatePlayPauseIcon(false);
    }

    private void openInExternalPlayer() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, StorageUtils.MIME_TYPE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.open_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_external_player, Toast.LENGTH_SHORT).show();
        }
    }

    private void share() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(StorageUtils.MIME_TYPE);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, getString(R.string.share_chooser)));
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, displayName))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    try {
                        if (getContentResolver().delete(uri, null, null) > 0) {
                            Toast.makeText(this, getString(R.string.deleted, displayName),
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show();
                    }
                    finish();
                })
                .show();
    }
}
