package com.example.dashcam;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;

/** Галерея записей: список файлов из общей галереи телефона, удаление, переход к просмотру. */
public class RecordingsActivity extends AppCompatActivity {

    private TextView textSummary;
    private TextView textEmpty;
    private RecordingAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Номер текущей загрузки: устаревший фоновой результат не должен перетереть свежий. */
    private int loadSequence;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenOrientation.apply(this);
        setContentView(R.layout.activity_recordings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        textSummary = findViewById(R.id.textSummary);
        textEmpty = findViewById(R.id.textEmpty);

        adapter = new RecordingAdapter(this::openPlayer, this::confirmDelete);
        RecyclerView recycler = findViewById(R.id.recyclerRecordings);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ScreenOn.apply(this);
        loadRecordings();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /**
     * Список и суммарный размер считаем в фоне: полный обход MediaStore на главном потоке
     * при открытии галереи — прямой путь к ANR на десятках файлов (K4).
     */
    private void loadRecordings() {
        final int sequence = ++loadSequence;
        new Thread(() -> {
            List<Recording> recordings = StorageUtils.listRecordings(getApplicationContext());
            long sum = 0;
            for (Recording recording : recordings) {
                sum += recording.sizeBytes;
            }
            final long total = sum;
            handler.post(() -> {
                if (sequence != loadSequence || isFinishing() || isDestroyed()) {
                    return;
                }
                applyRecordings(recordings, total);
            });
        }, "recordings-loader").start();
    }

    private void applyRecordings(List<Recording> recordings, long total) {
        textSummary.setText(getString(R.string.recordings_summary,
                recordings.size(), StorageUtils.formatSize(total)));

        if (recordings.isEmpty()) {
            textEmpty.setText(RecordingService.getActiveRecordingName() != null
                    ? R.string.recordings_active_hint
                    : R.string.recordings_empty);
            textEmpty.setVisibility(View.VISIBLE);
            adapter.submit(new ArrayList<>());
            return;
        }
        textEmpty.setVisibility(View.GONE);
        adapter.submit(recordings);
        loadMissingDurations(recordings);
    }

    /** MediaStore не всегда знает длительность — досчитываем её через MediaMetadataRetriever. */
    private void loadMissingDurations(List<Recording> recordings) {
        List<Recording> unknown = new ArrayList<>();
        for (Recording recording : recordings) {
            if (recording.durationMs <= 0L) {
                unknown.add(recording);
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        new Thread(() -> {
            for (Recording recording : unknown) {
                recording.durationMs = StorageUtils.readDurationMs(getApplicationContext(),
                        recording.uri);
            }
            handler.post(adapter::notifyDataSetChanged);
        }, "duration-loader").start();
    }

    private void openPlayer(Recording recording) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_URI, recording.uri.toString());
        intent.putExtra(PlayerActivity.EXTRA_NAME, recording.displayName);
        intent.putExtra(PlayerActivity.EXTRA_SIZE, recording.sizeBytes);
        startActivity(intent);
    }

    private void confirmDelete(Recording recording) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, recording.displayName))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    if (StorageUtils.delete(this, recording)) {
                        adapter.remove(recording);
                        Toast.makeText(this,
                                getString(R.string.deleted, recording.displayName),
                                Toast.LENGTH_SHORT).show();
                        loadRecordings();
                    } else {
                        Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }
}
