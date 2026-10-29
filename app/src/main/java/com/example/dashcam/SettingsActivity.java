package com.example.dashcam;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Настройки записи: качество, длина файла, лимиты хранения, метки на видео, автозапись. */
public class SettingsActivity extends AppCompatActivity {

    private static final int[] SEGMENT_VALUES = {0, 1, 3, 5, 10};
    private static final String[] SEGMENT_LABELS =
            {"Без разбиения", "1 минута", "3 минуты", "5 минут", "10 минут"};

    private static final int[] STORAGE_VALUES = {1, 2, 5, 10};
    private static final String[] STORAGE_LABELS = {"1 ГБ", "2 ГБ", "5 ГБ", "10 ГБ"};

    private static final int[] RING_VALUES = {0, 15, 30, 60, 120};
    private static final String[] RING_LABELS =
            {"Без ограничения по времени", "Последние 15 минут", "Последние 30 минут",
                    "Последний 1 час", "Последние 2 часа"};

    private static final int[] STATIONARY_VALUES = {1, 5, 10, 15, 30};
    private static final String[] STATIONARY_LABELS =
            {"1 минута", "5 минут", "10 минут", "15 минут", "30 минут"};

    private Spinner spinnerQuality;
    /** Качества, реально поддерживаемые текущей камерой (заполняется в onCreate). */
    private String[] qualityValues;
    private Spinner spinnerSegment;
    private Spinner spinnerStorage;
    private Spinner spinnerRing;
    private MaterialSwitch switchAudio;
    private MaterialSwitch switchAutoStart;
    private MaterialSwitch switchTimestamp;
    private MaterialSwitch switchAutoBright;
    private MaterialSwitch switchGps;
    private MaterialSwitch switchStationary;
    private Spinner spinnerStationary;
    private MaterialButton buttonLockAdmin;
    private MaterialSwitch switchCharge;
    private MaterialSwitch switchSdCard;
    private TextView textSdCardInfo;
    private MaterialButton buttonPalmApp;
    private MaterialButton buttonTwoGisKey;

    /**
     * Разрешения, соответствующие включённым свитчам, запрашиваем сразу при сохранении —
     * иначе пользователь включает GPS/микрофон и не понимает, почему ничего не работает (K1).
     */
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        // Ничего не сохраняем повторно: результат применится при возврате
                        // на главный экран — сервис переподпишется на GPS там.
                    });

    @Override
    protected void onResume() {
        super.onResume();
        ScreenOn.apply(this);
        // Пользователь мог включить администратора устройства в системном окне.
        updateLockAdminButton();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenOrientation.apply(this);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        spinnerQuality = findViewById(R.id.spinnerQuality);
        spinnerSegment = findViewById(R.id.spinnerSegment);
        spinnerStorage = findViewById(R.id.spinnerStorage);
        spinnerRing = findViewById(R.id.spinnerRing);
        switchAudio = findViewById(R.id.switchAudio);
        switchAutoStart = findViewById(R.id.switchAutoStart);
        switchTimestamp = findViewById(R.id.switchTimestamp);
        switchAutoBright = findViewById(R.id.switchAutoBright);
        switchGps = findViewById(R.id.switchGps);
        switchCharge = findViewById(R.id.switchCharge);
        switchSdCard = findViewById(R.id.switchSdCard);
        textSdCardInfo = findViewById(R.id.textSdCardInfo);
        buttonPalmApp = findViewById(R.id.buttonPalmApp);
        buttonPalmApp.setOnClickListener(v -> pickPalmApp());
        updatePalmButton();
        buttonTwoGisKey = findViewById(R.id.buttonTwoGisKey);
        buttonTwoGisKey.setOnClickListener(v -> editTwoGisKey());
        updateTwoGisKeyButton();

        // Подписи задаём кодом, чтобы индексы гарантированно совпадали со значениями.
        // Качества берём у CameraX асинхронно: 4K предлагаем только если запись его реально
        // поддерживает. До ответа показываем безопасный минимум HD/FHD.
        applyQualityList(Arrays.asList(CameraQualities.HD, CameraQualities.FHD));
        CameraQualities.load(this, qualities -> {
            if (!isFinishing() && !isDestroyed()) {
                applyQualityList(qualities);
            }
        });
        spinnerSegment.setAdapter(adapter(SEGMENT_LABELS));
        spinnerStorage.setAdapter(adapter(STORAGE_LABELS));
        spinnerRing.setAdapter(adapter(RING_LABELS));

        spinnerSegment.setSelection(indexOf(SEGMENT_VALUES, Prefs.segmentMinutes(this)));
        spinnerStorage.setSelection(indexOf(STORAGE_VALUES, Prefs.maxStorageGb(this)));
        spinnerRing.setSelection(indexOf(RING_VALUES, Prefs.ringMinutes(this)));
        switchAudio.setChecked(Prefs.audio(this));
        switchAutoStart.setChecked(Prefs.autoStart(this));
        switchTimestamp.setChecked(Prefs.timestamp(this));
        switchAutoBright.setChecked(Prefs.autoBright(this));
        switchGps.setChecked(Prefs.gps(this));
        spinnerStationary = findViewById(R.id.spinnerStationary);
        spinnerStationary.setAdapter(adapter(STATIONARY_LABELS));
        spinnerStationary.setSelection(indexOf(STATIONARY_VALUES, Prefs.stationaryMinutes(this)));
        switchStationary = findViewById(R.id.switchStationary);
        switchStationary.setChecked(Prefs.stationaryStop(this));
        buttonLockAdmin = findViewById(R.id.buttonLockAdmin);
        buttonLockAdmin.setOnClickListener(v -> enableLockAdmin());
        updateLockAdminButton();
        switchCharge.setChecked(Prefs.autoStartOnCharge(this));

        // SD-карта: если её нет, переключатель недоступен и запись идёт во внутреннюю память.
        String sdVolume = StorageUtils.sdVolumeName(this);
        if (sdVolume != null) {
            switchSdCard.setEnabled(true);
            switchSdCard.setChecked(Prefs.recordToSdCard(this));
            long free = StorageUtils.sdCardFreeBytes(this);
            textSdCardInfo.setText(getString(R.string.settings_sd_card_found, sdVolume,
                    free > 0 ? StorageUtils.formatSize(free) : "?"));
        } else {
            switchSdCard.setChecked(false);
            switchSdCard.setEnabled(false);
            textSdCardInfo.setText(R.string.settings_sd_card_missing);
        }

        MaterialButton buttonSave = findViewById(R.id.buttonSave);
        buttonSave.setOnClickListener(v -> save());


        // Прошивки часто блокируют фоновые запуски — даём быстрый доступ к исключениям батареи.
        findViewById(R.id.buttonBattery).setOnClickListener(v -> openBatterySettings());
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(
                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, R.string.battery_settings_unavailable, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Выбор приложения, которое открывает жест «открытая ладонь» (список — в фоне). */
    private void pickPalmApp() {
        buttonPalmApp.setEnabled(false);
        new Thread(() -> {
            List<AppEntry> apps = loadLaunchableApps();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                buttonPalmApp.setEnabled(true);
                showPalmAppDialog(apps);
            });
        }, "palm-apps").start();
    }

    /**
     * Все приложения с иконкой в лаунчере, по одному на пакет. Без {@code <queries>} в манифесте
     * на Android 11 список был бы пуст (пакеты скрыты).
     */
    private List<AppEntry> loadLaunchableApps() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(intent, 0);
        Map<String, AppEntry> byPackage = new HashMap<>();
        for (ResolveInfo info : resolved) {
            ActivityInfo activity = info.activityInfo;
            if (activity == null || getPackageName().equals(activity.packageName)
                    || byPackage.containsKey(activity.packageName)) {
                continue;
            }
            CharSequence label = info.loadLabel(getPackageManager());
            byPackage.put(activity.packageName, new AppEntry(
                    new ComponentName(activity.packageName, activity.name),
                    label != null && label.length() > 0 ? label.toString() : activity.packageName));
        }
        List<AppEntry> apps = new ArrayList<>(byPackage.values());
        apps.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    private void showPalmAppDialog(List<AppEntry> apps) {
        String[] items = new String[apps.size() + 1];
        items[0] = getString(R.string.settings_palm_none);
        for (int i = 0; i < apps.size(); i++) {
            items[i + 1] = apps.get(i).label;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_palm_pick)
                .setItems(items, (dialog, which) -> {
                    Prefs.setPalmApp(this, which == 0
                            ? "" : apps.get(which - 1).component.flattenToString());
                    updatePalmButton();
                })
                .show();
    }

    /** Показывает на кнопке текущий выбор; удалённое приложение остаётся именем пакета. */
    private void updatePalmButton() {
        String saved = Prefs.palmApp(this);
        ComponentName component = saved.isEmpty()
                ? null : ComponentName.unflattenFromString(saved);
        if (component == null) {
            buttonPalmApp.setText(R.string.settings_palm_none);
            return;
        }
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(component, 0);
            CharSequence label = info.loadLabel(getPackageManager());
            buttonPalmApp.setText(label != null && label.length() > 0
                    ? label : component.getPackageName());
        } catch (Exception e) {
            // Приложение удалили или отключили — настройку не трогаем, показываем пакет.
            buttonPalmApp.setText(component.getPackageName());
        }
    }

    private static final class AppEntry {
        final ComponentName component;
        final String label;

        AppEntry(ComponentName component, String label) {
            this.component = component;
            this.label = label;
        }
    }

    /** Ключ 2ГИС вводится в диалоге: так его можно заменить без пересборки. */
    private void editTwoGisKey() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.two_gis_key_hint);
        input.setText(Prefs.twoGisKey(this));
        input.setSelection(input.length());
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.two_gis_key_title)
                .setMessage(R.string.two_gis_key_help)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Prefs.setTwoGisKey(this, input.getText().toString());
                    updateTwoGisKeyButton();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateTwoGisKeyButton() {
        buttonTwoGisKey.setText(Prefs.twoGisKey(this).isEmpty()
                ? R.string.two_gis_key_empty : R.string.two_gis_key_set);
    }

    /**
     * Блокировка экрана в Android — только через администратора устройства. Показываем
     * системное окно активации; результат подхватит onResume (кнопка сменит текст).
     */
    private void enableLockAdmin() {
        if (LockAdminReceiver.isActive(this)) {
            Toast.makeText(this, R.string.settings_lock_admin_on, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, LockAdminReceiver.component(this));
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_desc));
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.lock_admin_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateLockAdminButton() {
        if (buttonLockAdmin == null) {
            return;
        }
        buttonLockAdmin.setText(LockAdminReceiver.isActive(this)
                ? R.string.settings_lock_admin_on : R.string.settings_lock_admin);
    }

    private ArrayAdapter<String> adapter(String[] labels) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Индекс сохранённого качества; если такого варианта у камеры нет (например, раньше
     * было выбрано 4K), показываем самое высокое доступное, а не сбрасываем на 720p.
     */
    private static int indexOfOrHighest(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return Math.max(0, values.length - 1);
    }

    /** Перестраивает список качеств и выбирает сохранённое (или самое высокое доступное). */
    private void applyQualityList(List<String> qualities) {
        qualityValues = qualities.toArray(new String[0]);
        if (qualityValues.length == 0) {
            qualityValues = new String[]{CameraQualities.HD, CameraQualities.FHD};
        }
        String[] labels = new String[qualityValues.length];
        for (int i = 0; i < qualityValues.length; i++) {
            labels[i] = CameraQualities.label(qualityValues[i]);
        }
        spinnerQuality.setAdapter(adapter(labels));
        spinnerQuality.setSelection(indexOfOrHighest(qualityValues, Prefs.quality(this)));
    }

    private void save() {
        Prefs.setQuality(this, qualityValues[spinnerQuality.getSelectedItemPosition()]);
        Prefs.setSegmentMinutes(this, SEGMENT_VALUES[spinnerSegment.getSelectedItemPosition()]);
        Prefs.setMaxStorageGb(this, STORAGE_VALUES[spinnerStorage.getSelectedItemPosition()]);
        Prefs.setRingMinutes(this, RING_VALUES[spinnerRing.getSelectedItemPosition()]);
        Prefs.setAudio(this, switchAudio.isChecked());
        Prefs.setAutoStart(this, switchAutoStart.isChecked());
        Prefs.setTimestamp(this, switchTimestamp.isChecked());
        Prefs.setAutoBright(this, switchAutoBright.isChecked());
        Prefs.setGps(this, switchGps.isChecked());
        Prefs.setStationaryStop(this, switchStationary.isChecked());
        Prefs.setStationaryMinutes(this,
                STATIONARY_VALUES[spinnerStationary.getSelectedItemPosition()]);
        Prefs.setAutoStartOnCharge(this, switchCharge.isChecked());
        boolean toSd = switchSdCard.isEnabled() && switchSdCard.isChecked();
        if (switchSdCard.isChecked() && !switchSdCard.isEnabled()) {
            Toast.makeText(this, R.string.sd_card_unavailable, Toast.LENGTH_SHORT).show();
        }
        Prefs.setRecordToSdCard(this, toSd);

        // Лимиты могли уменьшиться — сразу подчищаем старое (в фоне: обход MediaStore
        // на главном потоке — кандидат на ANR, K4).
        new Thread(() -> StorageUtils.prune(getApplicationContext(), null),
                "settings-prune").start();

        requestPermissionsForToggles();

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    /** Разрешения, без которых только что включённые опции не заработают. */
    private void requestPermissionsForToggles() {
        Set<String> needed = new LinkedHashSet<>();
        // GPS-метка и стоянка обеим нужна геолокация — не просим дважды.
        boolean wantsLocation = switchGps.isChecked() || switchStationary.isChecked();
        if (wantsLocation
                && !granted(Manifest.permission.ACCESS_FINE_LOCATION)
                && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (switchAudio.isChecked() && !granted(Manifest.permission.RECORD_AUDIO)) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && switchCharge.isChecked()
                && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            // Без уведомления подсказка об автозаписи на Android 13+ не показывается (K6).
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            permissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}

