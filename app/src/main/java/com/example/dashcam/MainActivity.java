package com.example.dashcam;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Главный экран: изображение с камеры, кнопка-бургер, быстрая кнопка записи и таймер.
 * Полное меню (записи, камера, настройки, старт/стоп) прячется в бургер, чтобы не закрывать кадр.
 */
public class MainActivity extends AppCompatActivity
        implements RecordingService.Listener, GestureWatcher.Listener, RecognitionListener {

    private static final String TAG = "MainActivity";

    /** Команда от фонового приёмника зарядки: экран уже держит фронтальную камеру. */
    public static final String ACTION_START_FROM_IDLE =
            "com.example.dashcam.action.START_FROM_IDLE";
    /** Пауза между отпусканием фронтальной камеры и открытием основной. */
    private static final long CAMERA_HANDOFF_MS = 300L;
    /** Сколько ждём фактического старта записи перед открытием 2ГИС. */
    private static final long TWO_GIS_RECORD_TIMEOUT_MS = 30_000L;
    /** Пауза перед переходом в дежурный режим: сервис должен успеть финализировать файл. */
    private static final long IDLE_AFTER_STOP_MS = 400L;

    /** Открыть экран и сразу начать запись (например, после подключения зарядки). */
    public static final String EXTRA_AUTO_RECORD = "auto_record";

    private PreviewView previewView;
    private PreviewView gesturePreviewView;
    private View gesturePanel;
    private TextView textGestureHint;
    private TextView textGestureNow;
    private TextView textTimer;
    private TextView textTimestamp;
    private TextView textStatus;
    private TextView textStorage;
    private TextView textDiagnostics;
    private ImageButton buttonMenu;
    private ImageButton buttonRecord;
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;

    /** Дежурный режим: фронтальная камера ждёт жест «V», пока запись остановлена. */
    private GestureWatcher gestureWatcher;
    private boolean idleGestures;
    /** Уже начали переход «жест → запись», повторный жест игнорируем. */
    private boolean startingFromIdle;
    /** Момент команды на запуск записи — для замера «жест → запись» в логе. */
    private long gestureStartAt;
    private boolean visible;
    /** Распознавание речи по жесту «коза»: одна команда за раз. */
    private SpeechRecognizer speechRecognizer;
    private boolean listening;
    private boolean listeningForAddress;
    private final Runnable voiceHintResetRunnable = this::resetVoiceHint;
    /**
     * Найденный маршрут 2ГИС ждёт фактического старта записи: интент открываем только из
     * {@link #onRecordingStarted}, иначе 2ГИС забирает экран раньше, чем камера начнёт писать.
     */
    private TwoGisGeocoder.Point pendingTwoGisPoint;
    /** Запись так и не началась — маршрут не открываем, чтобы 2ГИС не всплыл позже. */
    private final Runnable clearPendingTwoGisRunnable = () -> {
        if (pendingTwoGisPoint != null) {
            pendingTwoGisPoint = null;
            Log.w(TAG, "2ГИС: запись не началась за "
                    + (TWO_GIS_RECORD_TIMEOUT_MS / 1000) + " с — маршрут не открываю");
            Toast.makeText(this, R.string.two_gis_no_record, Toast.LENGTH_LONG).show();
        }
    };
    /** Для приёмника зарядки: экран прямо сейчас держит фронтальную камеру. */
    private static volatile boolean gestureIdle;

    public static boolean isGestureIdle() {
        return gestureIdle;
    }

    private RecordingService service;
    private boolean bound;
    private boolean bindingRequested;
    /** Автозапись запрошена, но сервис ещё не подключён — выполним в onServiceConnected. */
    private boolean pendingAutoRecord;
    private boolean autoStartHandled;
    private boolean permissionRequested;
    /** Уже предлагали открыть настройки приложения — чтобы не навязываться каждый старт. */
    private boolean settingsOfferShown;
    private boolean powerReceiverRegistered;
    /** Последний посчитанный объём записей: полный обход MediaStore не делаем чаще раза в 10 с. */
    private long totalSizeCache = -1L;
    private long lastTotalSizeAt;
    private static final long STORAGE_REFRESH_MS = 10_000L;
    /** Сеть камеры, для которой задний вид уже открывали автоматически. */
    private String autoOpenedSsid;

    /**
     * Второй канал автозаписи от зарядки: манифест-приёмник часть прошивок не будит,
     * а динамический работает, пока приложение живо.
     */
    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                PowerConnectionReceiver.handle(context.getApplicationContext(), true);
            }
        }
    };

    /**
     * Зарядку подключили, пока экран держит фронтальную камеру. Фоновый приёмник не может
     * просто поднять сервис — основная камера занята фронтальной, поэтому он присылает сюда
     * команду, а активность сама отпускает камеру и запускает запись.
     */
    private final BroadcastReceiver startFromIdleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (idleGestures) {
                startRecordingByGesture();
            }
        }
    };

    private final Runnable enterIdleRunnable = this::enterIdleGestures;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat overlayFormat =
            new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault());

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (service != null && service.isRecording()) {
                textTimer.setText(getString(R.string.timer_recording,
                        StorageUtils.formatDuration(service.getRecordingElapsedMs())));
            }
            updateDiagnostics();
            updateTimestampOverlay();
            // Пока идёт запись, экран не должен гаснуть.
            ScreenOn.apply(MainActivity.this);
            checkRearCameraAutoOpen();
            handler.postDelayed(this, 1000L);
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        permissionRequested = true;
                        if (hasCameraPermission()) {
                            if (pendingAutoRecord || (Prefs.autoStart(this) && !autoStartHandled)
                                    || RecordingService.isRecordingActive()) {
                                bindToService();
                            } else {
                                enterIdleGestures();
                            }
                        } else {
                            // Без камеры запись не начнётся — маршрут 2ГИС не ждём.
                            pendingTwoGisPoint = null;
                            handler.removeCallbacks(clearPendingTwoGisRunnable);
                        }
                        updateUi();
                        // Пользователь что-то отклонил — если нас больше не спросят,
                        // предлагаем открыть настройки приложения.
                        offerSettingsForDenied();
                    });

    /**
     * Имя Wi-Fi сети Android отдаёт только с разрешением: до API 33 — на геолокацию,
     * начиная с API 33 — NEARBY_WIFI_DEVICES. Без него автопоказ задней камеры не работает.
     */
    private final ActivityResultLauncher<String> ssidPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> updateDiagnostics());

    private boolean ssidPermissionRequested;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RecordingService.LocalBinder) binder).getService();
            bound = true;
            bindingRequested = false;
            service.setListener(MainActivity.this);
            attachPreview();
            updateUi();
            // Автозапись могла прийти, пока сервис ещё не был подключён (onNewIntent).
            boolean autoRecord = pendingAutoRecord || consumeAutoRecordExtra();
            pendingAutoRecord = false;
            if (autoRecord) {
                if (!service.isRecording()) {
                    startRecording();
                }
            } else if (Prefs.autoStart(MainActivity.this) && !autoStartHandled
                    && !service.isRecording()) {
                autoStartHandled = true;
                startRecording();
            } else if (Prefs.recordingDesired(MainActivity.this) && !service.isRecording()) {
                // Процесс убили во время записи (переустановка, смерть процесса): флаг
                // «запись нужна» остался, а сервис уже не пишет. Доводим восстановление
                // до конца, иначе экран остаётся с превью, но без записи и без дежурства.
                Log.i(TAG, "Восстанавливаю запись после перезапуска процесса");
                startRecording();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Регистратор всегда работает в ландшафте; меню переключает прямой/обратный.
        ScreenOrientation.apply(this);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        gesturePreviewView = findViewById(R.id.gesturePreviewView);
        gesturePanel = findViewById(R.id.gesturePanel);
        textGestureHint = findViewById(R.id.textGestureHint);
        textGestureNow = findViewById(R.id.textGestureNow);
        textTimer = findViewById(R.id.textTimer);
        textTimestamp = findViewById(R.id.textTimestamp);
        buttonMenu = findViewById(R.id.buttonMenu);
        buttonRecord = findViewById(R.id.buttonRecord);
        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);

        // Статус и занятое место живут в шапке меню — берём их из уже надутого header view.
        View header = navigationView.getHeaderView(0);
        textStatus = header.findViewById(R.id.textStatus);
        textStorage = header.findViewById(R.id.textStorage);
        textDiagnostics = header.findViewById(R.id.textDiagnostics);

        buttonMenu.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        buttonRecord.setOnClickListener(v -> toggleRecording());
        // Запасной путь к голосовым командам: если жест «коза» не поймался, можно коснуться подсказки.
        gesturePanel.setOnClickListener(v -> {
            Log.i(TAG, "Нажата подсказка жестов — голосовая команда вручную");
            startVoiceCommand();
        });
        // Запасной путь к приложению «ладони»: если жест не поймался, можно удержать подсказку.
        gesturePanel.setOnLongClickListener(v -> {
            Log.i(TAG, "Долгое нажатие на подсказку — открываю приложение для жеста «ладонь»");
            openPalmApp();
            return true;
        });
        navigationView.setNavigationItemSelectedListener(this::onMenuItemSelected);
        syncAutostartMenu();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        // Старые записи из приватной папки прежних версий переносим в общую галерею,
        // заодно убираем «осиротевшие» записи, не финализировавшиеся после смерти процесса.
        new Thread(() -> {
            StorageUtils.migrateLegacyRecordings(getApplicationContext());
            StorageUtils.cleanupStalePending(getApplicationContext());
        }, "legacy-migration").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!consumeAutoRecordExtra()) {
            return;
        }
        // Если сервис ещё не подключён, запомним запрос: onServiceConnected его выполнит.
        // Иначе флаг терялся и автозапись не начиналась (известная проблема).
        if (service != null) {
            if (!service.isRecording()) {
                startRecording();
            }
        } else if (idleGestures) {
            startRecordingByGesture();
        } else {
            pendingAutoRecord = true;
            bindToService();
        }
    }

    private boolean consumeAutoRecordExtra() {
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra(EXTRA_AUTO_RECORD, false)) {
            intent.removeExtra(EXTRA_AUTO_RECORD);
            return true;
        }
        return false;
    }

    private boolean onMenuItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_record) {
            toggleRecording();
        } else if (id == R.id.action_gallery) {
            startActivity(new Intent(this, RecordingsActivity.class));
        } else if (id == R.id.action_rear_camera) {
            startActivity(new Intent(this, RearCameraActivity.class));
        } else if (id == R.id.action_rear_settings) {
            startActivity(new Intent(this, RearCameraSettingsActivity.class));
        } else if (id == R.id.action_switch_camera) {
            switchCamera();
        } else if (id == R.id.action_autostart) {
            toggleAutostart();
        } else if (id == R.id.action_orientation) {
            toggleOrientation();
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        }
        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    /**
     * Переключает автозапись при открытии приложения. Настройки в {@link SettingsActivity}
     * остаются вторым входом в то же значение, поэтому меню синхронизируется при каждом
     * возврате на экран.
     */
    private void toggleAutostart() {
        boolean on = !Prefs.autoStart(this);
        Prefs.setAutoStart(this, on);
        syncAutostartMenu();
        String state = getString(on ? R.string.menu_state_on : R.string.menu_state_off);
        Toast.makeText(this, getString(R.string.menu_state_pair,
                getString(R.string.action_autostart), state), Toast.LENGTH_SHORT).show();
    }

    /** Отмечает пункт меню и дописывает состояние в название — видно даже без галочки. */
    private void syncAutostartMenu() {
        if (navigationView == null) {
            return;
        }
        MenuItem startItem = navigationView.getMenu().findItem(R.id.action_autostart);
        applyAutostartState(startItem, R.string.action_autostart, Prefs.autoStart(this));
    }

    private void applyAutostartState(MenuItem item, int titleRes, boolean on) {
        if (item == null) {
            return;
        }
        item.setChecked(on);
        String state = getString(on ? R.string.menu_state_on : R.string.menu_state_off);
        item.setTitle(getString(R.string.menu_state_pair, getString(titleRes), state));
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        if (hasCameraPermission()) {
            // Автозапись важнее дежурного режима: если она включена или уже идёт запись,
            // держим основную камеру; иначе освобождаем её и ждём жест «V».
            if (pendingAutoRecord || (Prefs.autoStart(this) && !autoStartHandled)
                    || RecordingService.isRecordingActive()) {
                bindToService();
            } else {
                enterIdleGestures();
            }
        }
        updateUi();
        // Разрешения запрашиваем при каждом старте, пока чего-то не хватает: раньше об этом
        // «забывали» навсегда, как только камера была получена (K1).
        ensureRuntimePermissions();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        if (!powerReceiverRegistered) {
            ContextCompat.registerReceiver(this, powerReceiver,
                    new IntentFilter(Intent.ACTION_POWER_CONNECTED),
                    ContextCompat.RECEIVER_EXPORTED);
            ContextCompat.registerReceiver(this, startFromIdleReceiver,
                    new IntentFilter(ACTION_START_FROM_IDLE),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            powerReceiverRegistered = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ScreenOn.apply(this);
        if (service != null) {
            service.reloadCameraIfNeeded();
            updateUi();
        }
        // Автопоказ задней камеры сравнивает SSID — без разрешения Android отдаёт «unknown ssid».
        ensureSsidPermission();
        // Настройки могли поменять автозапуск — вернём меню в актуальное состояние.
        syncAutostartMenu();
    }

    @Override
    protected void onStop() {
        visible = false;
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(enterIdleRunnable);
        // Маршрут, ждавший старта записи, при уходе с экрана не открываем задним числом.
        pendingTwoGisPoint = null;
        handler.removeCallbacks(clearPendingTwoGisRunnable);
        leaveIdleGestures();
        stopVoiceCommand();
        gesturePanel.setVisibility(View.GONE);
        if (powerReceiverRegistered) {
            try {
                unregisterReceiver(powerReceiver);
            } catch (IllegalArgumentException ignored) {
                // Приёмник уже отключён.
            }
            try {
                unregisterReceiver(startFromIdleReceiver);
            } catch (IllegalArgumentException ignored) {
                // Приёмник уже отключён.
            }
            powerReceiverRegistered = false;
        }
        detachPreview();
        releaseService();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {
                // Сервис распознавания уже недоступен.
            }
            speechRecognizer = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- дежурный режим жестов

    /**
     * Включает дежурный режим: основная камера отпускается, фронтальная смотрит на водителя
     * и ждёт жест «V». Вызывается, когда запись остановлена (или её не было при запуске).
     */
    private void enterIdleGestures() {
        if (idleGestures || !visible || isFinishing() || isDestroyed()
                || !hasCameraPermission()) {
            return;
        }
        if (RecordingService.isRecordingActive()) {
            // Запись идёт: фронтальная камера заняла бы основную, а микрофон — голос.
            Log.i(TAG, "Дежурный режим не нужен: запись идёт");
            return;
        }
        if (Prefs.recordingDesired(this)) {
            // Запись была запрошена, но сервис ещё/уже не работает (например, приложение
            // переустановили во время записи). Восстанавливаем запись, а не забираем камеру.
            Log.i(TAG, "Дежурный режим не нужен: восстанавливаю запись");
            bindToService();
            return;
        }
        idleGestures = true;
        gestureIdle = true;
        Log.i(TAG, "Дежурный режим: ждём жест «V» с фронтальной камеры");
        detachPreview();
        releaseService();
        textGestureHint.setText(idleHintRes());
        textGestureNow.setText("");
        textGestureNow.setVisibility(View.GONE);
        gesturePanel.setVisibility(View.VISIBLE);
        gesturePreviewView.setVisibility(View.VISIBLE);
        gestureWatcher = new GestureWatcher(this, gesturePreviewView, this);
        gestureWatcher.start();
        updateUi();
    }

    /** Гасит дежурный режим и отпускает фронтальную камеру. Повторный вызов безопасен. */
    private void leaveIdleGestures() {
        if (!idleGestures) {
            return;
        }
        idleGestures = false;
        gestureIdle = false;
        handler.removeCallbacks(voiceHintResetRunnable);
        stopVoiceCommand();
        if (gestureWatcher != null) {
            gestureWatcher.stop();
            gestureWatcher = null;
        }
        gesturePanel.setVisibility(View.GONE);
        gesturePreviewView.setVisibility(View.GONE);
        Log.i(TAG, "Дежурный режим выключен");
    }

    /**
     * Жест «V» (или кнопка записи в дежурном режиме): сначала отпускаем фронтальную камеру
     * и только потом поднимаем сервис — иначе основная камера не откроется.
     */
    private void startRecordingByGesture() {
        startRecordingFromIdle("Жест «V»");
    }

    /**
     * Запускает запись из дежурного режима. {@code reason} — что инициировало запуск, для лога.
     * Фактический старт записи подтверждает сервис через {@link #onRecordingStarted}.
     */
    private void startRecordingFromIdle(String reason) {
        if (startingFromIdle) {
            return;
        }
        startingFromIdle = true;
        gestureStartAt = SystemClock.elapsedRealtime();
        Log.i(TAG, reason + ": отпускаем фронтальную камеру и запускаем запись");
        leaveIdleGestures();
        // Пока камера передаётся сервису, показываем подсказку поверх чёрного кадра.
        textGestureHint.setText(R.string.gesture_starting);
        textGestureNow.setText("");
        textGestureNow.setVisibility(View.GONE);
        gesturePanel.setVisibility(View.VISIBLE);
        handler.postDelayed(() -> {
            startingFromIdle = false;
            if (!visible) {
                // Ушли с экрана за эти полсекунды: запись начнётся при возвращении.
                pendingAutoRecord = true;
                return;
            }
            if (!hasCameraPermission()) {
                pendingAutoRecord = true;
                permissionLauncher.launch(requiredPermissions());
                return;
            }
            pendingAutoRecord = true;
            bindToService();
        }, CAMERA_HANDOFF_MS);
    }

    @Override
    public void onStableGesture(String gesture) {
        if (!idleGestures) {
            return;
        }
        textGestureNow.setText(getString(R.string.gesture_now, gestureLabel(gesture)));
        textGestureNow.setVisibility(View.VISIBLE);
        if (GestureClassifier.VICTORY.equals(gesture)) {
            startRecordingByGesture();
        } else if (GestureClassifier.HORNS.equals(gesture)) {
            // «Коза» — включить голосовые команды (одна фраза за раз).
            startVoiceCommand();
        } else if (GestureClassifier.PALM.equals(gesture)) {
            openPalmApp();
        }
    }

    /**
     * Жест «открытая ладонь»: открывает приложение, выбранное в настройках.
     * Если выбрано 2ГИС — вместо запуска просит назвать адрес: координаты находятся через
     * 2GIS Geocoder API, после чего 2ГИС сразу строит маршрут. Приложение запускается только
     * при остановленной записи (жесты вообще доступны только тогда), фронтальная камера
     * перед этим отпускается.
     */
    private void openPalmApp() {
        String saved = Prefs.palmApp(this);
        if (saved.isEmpty()) {
            textGestureHint.setText(R.string.palm_not_set);
            scheduleVoiceHintReset();
            return;
        }
        ComponentName component = ComponentName.unflattenFromString(saved);
        if (component == null) {
            textGestureHint.setText(R.string.palm_not_found);
            scheduleVoiceHintReset();
            return;
        }
        if (TwoGisGeocoder.isTwoGis(component)) {
            startAddressVoice();
            return;
        }
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (getPackageManager().resolveActivity(launch, 0) == null) {
            Log.w(TAG, "Ладонь: приложение не найдено: " + component.flattenToShortString());
            textGestureHint.setText(R.string.palm_not_found);
            scheduleVoiceHintReset();
            return;
        }
        try {
            // Сначала стартуем, потом гасим дежурство: если приложение исчезло между
            // проверкой и запуском, останемся в дежурном режиме и покажем сообщение.
            startActivity(launch);
            Log.i(TAG, "Ладонь: открываю " + component.flattenToShortString());
        } catch (Exception e) {
            Log.e(TAG, "Ладонь: не удалось открыть приложение", e);
            textGestureHint.setText(R.string.palm_not_found);
            scheduleVoiceHintReset();
            return;
        }
        leaveIdleGestures();
    }

    /** Подсказка дежурства: если выбрано приложение для «ладони», упоминаем и его. */
    private int idleHintRes() {
        String saved = Prefs.palmApp(this);
        if (saved.isEmpty()) {
            return R.string.gesture_wait_hint;
        }
        return TwoGisGeocoder.isTwoGis(ComponentName.unflattenFromString(saved))
                ? R.string.gesture_wait_hint_2gis : R.string.gesture_wait_hint_palm;
    }

    // ---------------------------------------------------------------- голосовые команды

    /**
     * Включает распознавание одной голосовой команды (жест «коза», тап по подсказке).
     * Доступно только в дежурном режиме: микрофон свободен, запись не идёт
     * (во время записи его занимает сервис).
     */
    private void startVoiceCommand() {
        startVoice(false);
    }

    /** Ввод адреса для 2ГИС: без ключа слушать бессмысленно — сразу подсказываем. */
    private void startAddressVoice() {
        if (Prefs.twoGisKey(this).isEmpty()) {
            textGestureHint.setText(R.string.voice_address_no_key);
            scheduleVoiceHintReset();
            return;
        }
        startVoice(true);
    }

    /**
     * Распознаёт одну фразу: {@code address=true} — адрес для 2ГИС, иначе — команда.
     * Ограничения те же: дежурный режим, свободный микрофон, нет другой сессии распознавания.
     */
    private void startVoice(boolean address) {
        Log.i(TAG, "Запрос " + (address ? "адреса" : "голосовой команды")
                + ": дежурство=" + idleGestures + ", слушаем=" + listening);
        if (!idleGestures || listening) {
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            textGestureHint.setText(R.string.voice_unavailable);
            scheduleVoiceHintReset();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            textGestureHint.setText(R.string.voice_no_mic);
            scheduleVoiceHintReset();
            return;
        }
        handler.removeCallbacks(voiceHintResetRunnable);
        listening = true;
        listeningForAddress = address;
        textGestureHint.setText(address
                ? R.string.voice_address_listening : R.string.voice_listening);
        textGestureNow.setText(address ? R.string.voice_address_title : R.string.voice_commands);
        textGestureNow.setVisibility(View.VISIBLE);

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        try {
            speechRecognizer().startListening(intent);
            Log.i(TAG, address ? "Слушаю адрес" : "Слушаю голосовую команду");
        } catch (Exception e) {
            listening = false;
            listeningForAddress = false;
            Log.e(TAG, "Не удалось запустить распознавание речи", e);
            textGestureHint.setText(R.string.voice_unavailable);
            scheduleVoiceHintReset();
        }
    }

    /** Отпускает микрофон: перед записью и при уходе с экрана. */
    private void stopVoiceCommand() {
        listening = false;
        listeningForAddress = false;
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {
                // Сервис распознавания уже отключён.
            }
        }
    }

    private SpeechRecognizer speechRecognizer() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(this);
        }
        return speechRecognizer;
    }

    private void handleVoiceResult(List<String> results) {
        if (!idleGestures) {
            return;
        }
        String heard = results == null || results.isEmpty() ? "" : results.get(0);
        int command = VoiceCommands.match(heard);
        Log.i(TAG, "Голосовая команда: «" + heard + "» → " + command);
        textGestureNow.setText(getString(R.string.voice_heard, heard));
        textGestureNow.setVisibility(View.VISIBLE);
        switch (command) {
            case VoiceCommands.START_RECORDING:
                startRecordingByGesture();
                break;
            case VoiceCommands.OPEN_GALLERY:
                textGestureHint.setText(R.string.voice_opening_gallery);
                scheduleVoiceHintReset();
                startActivity(new Intent(this, RecordingsActivity.class));
                break;
            case VoiceCommands.OPEN_SETTINGS:
                textGestureHint.setText(R.string.voice_opening_settings);
                scheduleVoiceHintReset();
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            case VoiceCommands.ALREADY_STOPPED:
                textGestureHint.setText(R.string.voice_already_stopped);
                scheduleVoiceHintReset();
                break;
            default:
                textGestureHint.setText(R.string.voice_unknown);
                scheduleVoiceHintReset();
                break;
        }
    }

    /**
     * Адрес распознан: ищем координаты через 2GIS Geocoder API в фоне (сеть может отвечать
     * несколько секунд, главный поток не занимаем) и открываем навигацию в 2ГИС.
     */
    private void handleAddressResult(String heard) {
        if (!idleGestures) {
            return;
        }
        String address = heard != null ? heard.trim() : "";
        if (address.isEmpty()) {
            textGestureHint.setText(R.string.voice_address_no_match);
            scheduleVoiceHintReset();
            return;
        }
        textGestureNow.setText(getString(R.string.voice_address_heard, address));
        textGestureNow.setVisibility(View.VISIBLE);
        String key = Prefs.twoGisKey(this);
        if (key.isEmpty()) {
            textGestureHint.setText(R.string.voice_address_no_key);
            scheduleVoiceHintReset();
            return;
        }
        textGestureHint.setText(R.string.voice_address_searching);
        handler.removeCallbacks(voiceHintResetRunnable);
        new Thread(() -> {
            TwoGisGeocoder.Point point = null;
            Exception failure = null;
            try {
                point = TwoGisGeocoder.geocode(address, key);
            } catch (Exception e) {
                failure = e;
            }
            TwoGisGeocoder.Point found = point;
            Exception error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !idleGestures) {
                    return;
                }
                if (found == null) {
                    Log.w(TAG, "2ГИС: адрес «" + address + "» не найден"
                            + (error != null ? " (" + error.getMessage() + ")" : ""));
                    textGestureHint.setText(addressErrorRes(error));
                    scheduleVoiceHintReset();
                    return;
                }
                // Приложения нет — остаёмся в дежурстве и ничего не записываем зря.
                Intent nav = new Intent(Intent.ACTION_VIEW,
                        TwoGisGeocoder.routeUri(found.lon, found.lat))
                        .setPackage(TwoGisGeocoder.PACKAGE);
                if (getPackageManager().resolveActivity(nav, 0) == null) {
                    Log.w(TAG, "2ГИС не установлен");
                    textGestureHint.setText(R.string.two_gis_missing);
                    scheduleVoiceHintReset();
                    return;
                }
                if (service != null && service.isRecording()) {
                    // Запись уже идёт (на всякий случай) — можно открывать сразу.
                    launchTwoGis(found);
                    return;
                }
                // Сначала запись, и только после её фактического старта — 2ГИС:
                // иначе навигатор забирает экран раньше, чем камера начнёт писать.
                pendingTwoGisPoint = found;
                handler.removeCallbacks(clearPendingTwoGisRunnable);
                handler.postDelayed(clearPendingTwoGisRunnable, TWO_GIS_RECORD_TIMEOUT_MS);
                Log.i(TAG, "2ГИС: адрес найден (" + found.lon + "," + found.lat
                        + "), сначала запускаем запись");
                startRecordingFromIdle("Адрес для 2ГИС");
                // Панель на время передачи камеры уже показана в startRecordingFromIdle.
                textGestureHint.setText(R.string.voice_address_starting_record);
            });
        }, "two-gis-geocode").start();
    }

    /** Подсказка для неудачного геокодирования: отдельно про отклонённый ключ. */
    private int addressErrorRes(Exception error) {
        if (error == null) {
            return R.string.voice_address_not_found;
        }
        if (error instanceof TwoGisGeocoder.ApiException) {
            int code = ((TwoGisGeocoder.ApiException) error).code;
            if (code == 401 || code == 403) {
                return R.string.voice_address_bad_key;
            }
        }
        return R.string.voice_address_error;
    }

    /**
     * Открывает 2ГИС с готовым маршрутом. Вызывается только после фактического старта записи
     * (из {@link #onRecordingStarted}), чтобы навигатор не помешал камере начать писать.
     */
    private void launchTwoGis(TwoGisGeocoder.Point point) {
        pendingTwoGisPoint = null;
        handler.removeCallbacks(clearPendingTwoGisRunnable);
        Intent intent = new Intent(Intent.ACTION_VIEW,
                TwoGisGeocoder.routeUri(point.lon, point.lat))
                .setPackage(TwoGisGeocoder.PACKAGE);
        try {
            startActivity(intent);
            Log.i(TAG, "2ГИС: запись идёт, открываю навигацию к " + point.lon + "," + point.lat
                    + (point.address.isEmpty() ? "" : " («" + point.address + "»)"));
        } catch (Exception e) {
            Log.e(TAG, "2ГИС: не удалось открыть навигацию", e);
            Toast.makeText(this, R.string.two_gis_missing, Toast.LENGTH_LONG).show();
        }
    }

    private void scheduleVoiceHintReset() {
        handler.removeCallbacks(voiceHintResetRunnable);
        handler.postDelayed(voiceHintResetRunnable, 2500L);
    }

    private void resetVoiceHint() {
        if (!idleGestures || listening) {
            return;
        }
        textGestureHint.setText(idleHintRes());
        textGestureNow.setText("");
        textGestureNow.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- RecognitionListener

    @Override
    public void onReadyForSpeech(Bundle params) {
        // Подсказка «Слушаю команду…» уже показана.
    }

    @Override
    public void onBeginningOfSpeech() {
        if (idleGestures) {
            textGestureHint.setText(R.string.voice_hearing);
        }
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        // Уровень звука не показываем.
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
        // Промежуточные буферы не нужны.
    }

    @Override
    public void onEndOfSpeech() {
        if (idleGestures) {
            textGestureHint.setText(R.string.voice_thinking);
        }
    }

    @Override
    public void onError(int error) {
        listening = false;
        listeningForAddress = false;
        if (!idleGestures) {
            return;
        }
        Log.w(TAG, "Распознавание речи, ошибка " + error);
        textGestureHint.setText(error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ? R.string.voice_no_match
                : R.string.voice_error);
        scheduleVoiceHintReset();
    }

    @Override
    public void onResults(Bundle results) {
        boolean address = listeningForAddress;
        listening = false;
        listeningForAddress = false;
        List<String> heard = results == null ? null
                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (address) {
            handleAddressResult(heard == null || heard.isEmpty() ? "" : heard.get(0));
        } else {
            handleVoiceResult(heard);
        }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        // Итогового результата достаточно.
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        // Служебные события сервиса распознавания не нужны.
    }

    @Override
    public void onCameraError() {
        if (idleGestures) {
            textGestureHint.setText(R.string.gesture_camera_error);
        }
    }

    private String gestureLabel(String gesture) {
        if (GestureClassifier.VICTORY.equals(gesture)) {
            return getString(R.string.gesture_victory);
        }
        if (GestureClassifier.HORNS.equals(gesture)) {
            return getString(R.string.gesture_horns);
        }
        if (GestureClassifier.PALM.equals(gesture)) {
            return getString(R.string.gesture_palm);
        }
        if (GestureClassifier.FIST.equals(gesture)) {
            return getString(R.string.gesture_fist);
        }
        return getString(R.string.gesture_none);
    }

    /** Отпускает сервис и его камеру: при уходе с экрана и перед дежурным режимом. */
    private void releaseService() {
        if (service != null) {
            service.setListener(null);
        }
        if (bound || bindingRequested) {
            try {
                unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Сервис уже отвязан системой.
            }
        }
        bound = false;
        bindingRequested = false;
        service = null;
    }

    // ---------------------------------------------------------------- permissions

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String[] requiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        if (Prefs.audio(this)) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Prefs.gps(this)) {
            // Без разрешения запись работает, просто без координат на кадре.
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // На Android 9 и ниже запись в общую галерею требует этого разрешения.
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return permissions.toArray(new String[0]);
    }

    /** Каких из требуемых разрешений не хватает прямо сейчас. */
    private String[] missingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing.toArray(new String[0]);
    }

    /**
     * Запрашивает все недостающие разрешения — не только камеру. Пока пользователь сам не
     * выключил «спрашивать всегда», системный диалог может появиться повторно; если же больше
     * не спросят (отказ «навсегда»), предлагаем открыть настройки приложения.
     */
    private void ensureRuntimePermissions() {
        if (missingPermissions().length == 0) {
            return;
        }
        if (permissionRequested) {
            offerSettingsForDenied();
            return;
        }
        permissionRequested = true;
        permissionLauncher.launch(requiredPermissions());
    }

    /** Показывает «откройте настройки», когда хотя бы одно разрешение отклонено навсегда. */
    private void offerSettingsForDenied() {
        if (settingsOfferShown) {
            return;
        }
        boolean permanentlyDenied = false;
        for (String permission : missingPermissions()) {
            if (!shouldShowRequestPermissionRationale(permission)) {
                permanentlyDenied = true;
                break;
            }
        }
        if (!permanentlyDenied) {
            return;
        }
        settingsOfferShown = true;
        new AlertDialog.Builder(this)
                .setMessage(R.string.permission_required)
                .setPositiveButton(R.string.permission_settings,
                        (dialog, which) -> startActivity(new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null))))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- service

    private void bindToService() {
        if (bound || bindingRequested) {
            return;
        }
        bindingRequested = true;
        Intent intent = new Intent(this, RecordingService.class);
        if (!bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            // Иначе флаг залипнет и сервис больше не привяжется до перезапуска активности.
            bindingRequested = false;
            Log.w(TAG, "bindService вернул false — сервис не подключён");
        }
    }

    private void toggleRecording() {
        if (service != null && service.isRecording()) {
            stopRecording();
        } else if (idleGestures) {
            startRecordingByGesture();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (!hasCameraPermission()) {
            permissionLauncher.launch(requiredPermissions());
            return;
        }
        Intent intent = new Intent(this, RecordingService.class);
        intent.setAction(RecordingService.ACTION_START);
        ContextCompat.startForegroundService(this, intent);
        updateUi();
    }

    private void stopRecording() {
        Intent intent = new Intent(this, RecordingService.class);
        intent.setAction(RecordingService.ACTION_STOP);
        startService(intent);
    }

    /**
     * Переключает прямой/обратный ландшафт: телефон можно положить так, чтобы камера и разъём
     * зарядки были слева или справа.
     */
    private void toggleOrientation() {
        boolean reversed = !Prefs.landscapeReversed(this);
        Prefs.setLandscapeReversed(this, reversed);
        Toast.makeText(this,
                reversed ? R.string.orientation_reversed : R.string.orientation_normal,
                Toast.LENGTH_SHORT).show();
        // Пересоздаёт активность в новой ориентации; запись продолжается в сервисе.
        ScreenOrientation.apply(this);
    }

    private void switchCamera() {
        String next = "FRONT".equals(Prefs.cameraMode(this)) ? "BACK" : "FRONT";
        Prefs.setCameraMode(this, next);
        if (service != null) {
            if (service.isRecording()) {
                Toast.makeText(this, getString(R.string.camera_switch_after_stop),
                        Toast.LENGTH_SHORT).show();
            } else {
                service.reloadCameraIfNeeded();
            }
        }
        updateUi();
    }

    private void attachPreview() {
        if (service != null && service.getPreview() != null && previewView != null) {
            service.getPreview().setSurfaceProvider(previewView.getSurfaceProvider());
        }
    }

    private void detachPreview() {
        if (service != null && service.getPreview() != null) {
            service.getPreview().setSurfaceProvider(null);
        }
    }

    // ---------------------------------------------------------------- ui

    private void updateUi() {
        boolean recording = service != null && service.isRecording();

        buttonRecord.setImageResource(recording ? R.drawable.ic_stop : R.drawable.ic_record);
        buttonRecord.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
                this, recording ? R.color.record_red : R.color.record_idle)));
        buttonRecord.setContentDescription(getString(
                recording ? R.string.action_stop_record : R.string.action_record));

        MenuItem recordItem = navigationView.getMenu().findItem(R.id.action_record);
        if (recordItem != null) {
            recordItem.setTitle(recording ? R.string.action_stop_record : R.string.action_record);
            recordItem.setIcon(recording ? R.drawable.ic_stop : R.drawable.ic_record);
        }

        MenuItem orientationItem = navigationView.getMenu().findItem(R.id.action_orientation);
        if (orientationItem != null) {
            orientationItem.setChecked(Prefs.landscapeReversed(this));
        }

        textTimer.setText(recording
                ? getString(R.string.timer_recording,
                        StorageUtils.formatDuration(service.getRecordingElapsedMs()))
                : getString(R.string.timer_idle));

        if (recording) {
            textStatus.setText(R.string.status_recording);
        } else if (idleGestures) {
            textStatus.setText(R.string.status_waiting_gesture);
        } else if (service != null && service.isCameraReady()) {
            textStatus.setText(R.string.status_ready);
        } else {
            textStatus.setText(hasCameraPermission()
                    ? R.string.status_preparing
                    : R.string.status_no_permission);
        }

        updateStorageInfo();

        updateDiagnostics();
        updateTimestampOverlay();
        ScreenOn.apply(this);
    }

    /**
     * Занятое место: полный обход MediaStore дорогой, поэтому считаем в фоне и не чаще
     * раза в 10 секунд, а между пересчётами показываем кэшированное значение (K4).
     */
    private void updateStorageInfo() {
        long now = SystemClock.elapsedRealtime();
        if (totalSizeCache < 0 || now - lastTotalSizeAt >= STORAGE_REFRESH_MS) {
            lastTotalSizeAt = now;
            new Thread(() -> {
                long total = StorageUtils.totalSize(getApplicationContext());
                handler.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    totalSizeCache = total;
                    updateStorageText();
                });
            }, "storage-stats").start();
        }
        updateStorageText();
    }

    private void updateStorageText() {
        textStorage.setText(getString(R.string.storage_info,
                totalSizeCache < 0 ? "…" : StorageUtils.formatSize(totalSizeCache),
                Prefs.maxStorageGb(this) + " ГБ"));
    }

    /** Диагностика в шапке меню — обновляется каждую секунду, пока экран открыт. */
    private void updateDiagnostics() {
        if (textDiagnostics == null) {
            return;
        }
        String error = service != null ? service.getOverlayError() : "";
        if (Prefs.timestamp(this) && error != null && !error.isEmpty()) {
            textDiagnostics.setText(getString(R.string.diagnostics_error, error));
            return;
        }
        boolean burnedIn = service != null && service.isTimestampBurnedIn();
        String gpsState = !Prefs.gps(this)
                ? getString(R.string.diag_off)
                : (hasLocationPermission() ? getString(R.string.diag_on)
                : getString(R.string.diag_no_permission));
        textDiagnostics.setText(getString(R.string.diagnostics_text,
                getString(burnedIn ? R.string.diag_overlay_video : R.string.diag_overlay_screen),
                gpsState));
    }

    /**
     * Сам открывает задний вид, когда телефон подключился к сети камеры. Дёргается раз в секунду
     * и сбрасывается, как только сеть сменилась, — чтобы экран не открывался повторно.
     */
    private void checkRearCameraAutoOpen() {
        if (!Prefs.rearAutoOpen(this)) {
            return;
        }
        String configured = Prefs.rearSsid(this);
        if (configured.isEmpty()) {
            return;
        }
        if (!WifiConnector.isConnectedTo(this, configured)) {
            autoOpenedSsid = null;
            return;
        }
        if (configured.equalsIgnoreCase(autoOpenedSsid)) {
            return;
        }
        autoOpenedSsid = configured;
        startActivity(new Intent(this, RearCameraActivity.class));
    }

    /**
     * Метка на экране показывается всегда (если включена), кроме случая, когда CameraX реально
     * вжигает её в кадр — тогда она уже видна в самом превью.
     */
    private void updateTimestampOverlay() {
        // В дежурном режиме метка не нужна: записи нет, а на фронтальном кадре она мешает.
        if (idleGestures) {
            textTimestamp.setVisibility(View.GONE);
            return;
        }
        boolean enabled = Prefs.timestamp(this) || Prefs.gps(this);
        boolean burnedIn = service != null && service.isTimestampBurnedIn();
        if (!enabled || burnedIn) {
            textTimestamp.setVisibility(View.GONE);
            return;
        }
        StringBuilder text = new StringBuilder();
        if (Prefs.timestamp(this)) {
            text.append(overlayFormat.format(new Date()));
        }
        if (Prefs.gps(this)) {
            String gps = service != null ? service.getLocationText() : "";
            if (gps != null && !gps.isEmpty()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(gps);
            } else if (text.length() > 0) {
                text.append('\n').append(getString(hasLocationPermission()
                        ? R.string.gps_waiting
                        : R.string.gps_no_permission));
            }
        }
        textTimestamp.setText(text);
        textTimestamp.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
    }

    private boolean hasLocationPermission() {        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Есть ли разрешение читать имя текущей Wi-Fi сети. */
    private boolean hasSsidPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Запрашивает разрешение на имя сети, если включён автопоказ задней камеры. */
    private void ensureSsidPermission() {
        if (ssidPermissionRequested || hasSsidPermission()) {
            return;
        }
        if (!Prefs.rearAutoOpen(this) || Prefs.rearSsid(this).isEmpty()) {
            return;
        }
        ssidPermissionRequested = true;
        ssidPermissionLauncher.launch(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION);
    }

    // ---------------------------------------------------------------- service callbacks

    @Override
    public void onCameraReady() {
        attachPreview();
        updateUi();
    }

    @Override
    public void onRecordingStarted(String displayName) {
        handler.removeCallbacks(enterIdleRunnable);
        if (idleGestures) {
            // Запись могли запустить извне (зарядка, шторка) — дежурный режим больше не нужен.
            leaveIdleGestures();
        }
        gesturePanel.setVisibility(View.GONE);
        if (gestureStartAt != 0) {
            Log.i(TAG, "От команды до записи: "
                    + (SystemClock.elapsedRealtime() - gestureStartAt) + " мс");
            gestureStartAt = 0;
        }
        updateUi();
        // Запись реально пошла — только теперь отдаём экран 2ГИС (сценарий «ладонь → адрес»).
        TwoGisGeocoder.Point route = pendingTwoGisPoint;
        if (route != null) {
            pendingTwoGisPoint = null;
            handler.removeCallbacks(clearPendingTwoGisRunnable);
            launchTwoGis(route);
        }
        Toast.makeText(this, getString(R.string.recording_started, displayName),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRecordingProgress(long elapsedMs) {
        textTimer.setText(getString(R.string.timer_recording,
                StorageUtils.formatDuration(elapsedMs)));
    }

    @Override
    public void onRecordingFinished(Uri uri, String displayName, long durationMs, boolean error) {
        updateUi();
        if (error) {
            Toast.makeText(this, R.string.recording_failed, Toast.LENGTH_LONG).show();
        } else if (displayName != null) {
            Toast.makeText(this, getString(R.string.recording_saved,
                    displayName, StorageUtils.formatDuration(durationMs)),
                    Toast.LENGTH_SHORT).show();
        }
        // Остановка по кнопке: сервис больше не нужен, освобождаем камеру и ждём жест.
        // При смене сегмента recordingDesired остаётся true — в дежурный режим не уходим.
        if (!Prefs.recordingDesired(this) && !RecordingService.isRecordingActive()) {
            handler.removeCallbacks(enterIdleRunnable);
            handler.postDelayed(enterIdleRunnable, IDLE_AFTER_STOP_MS);
        }
    }

    @Override
    public void onRecordingError(String message) {
        gesturePanel.setVisibility(View.GONE);
        updateUi();
        // Запись не пошла — 2ГИС с маршрутом не открываем.
        pendingTwoGisPoint = null;
        handler.removeCallbacks(clearPendingTwoGisRunnable);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
