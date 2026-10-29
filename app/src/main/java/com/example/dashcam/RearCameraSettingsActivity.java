package com.example.dashcam;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Настройки WiFi-камеры заднего хода: сеть камеры, автоподключение, автопоказ и адрес потока.
 */
public class RearCameraSettingsActivity extends AppCompatActivity {

    private EditText editSsid;
    private EditText editPassword;
    private EditText editStreamUrl;
    private MaterialSwitch switchAutoConnect;
    private MaterialSwitch switchAutoOpen;
    private TextView textWifiState;
    private TextView textResult;

    private final ActivityResultLauncher<String> nearbyPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> applySuggestion());

    /** Имя сети без разрешения Android скрывает — просим его для показа текущей сети. */
    private final ActivityResultLauncher<String> ssidPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> updateWifiState());

    private boolean ssidPermissionRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenOrientation.apply(this);
        setContentView(R.layout.activity_rear_camera_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        editSsid = findViewById(R.id.editSsid);
        editPassword = findViewById(R.id.editPassword);
        editStreamUrl = findViewById(R.id.editStreamUrl);
        switchAutoConnect = findViewById(R.id.switchAutoConnect);
        switchAutoOpen = findViewById(R.id.switchAutoOpen);
        textWifiState = findViewById(R.id.textWifiState);
        textResult = findViewById(R.id.textResult);

        editSsid.setText(Prefs.rearSsid(this));
        editPassword.setText(Prefs.rearPassword(this));
        editStreamUrl.setText(Prefs.rearCameraUrl(this));
        switchAutoConnect.setChecked(Prefs.rearAutoConnect(this));
        switchAutoOpen.setChecked(Prefs.rearAutoOpen(this));

        MaterialButton buttonSave = findViewById(R.id.buttonSave);
        buttonSave.setOnClickListener(v -> save());
        MaterialButton buttonConnect = findViewById(R.id.buttonConnect);
        buttonConnect.setOnClickListener(v -> connectNow());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ScreenOn.apply(this);
        ensureSsidPermission();
        updateWifiState();
    }

    @Override
    protected void onStop() {
        // Пока экран открыт, запрос на подключение удерживает статический callback (K8) —
        // при уходе с экрана обязательно отписываемся.
        WifiConnector.cancelConnect();
        super.onStop();
    }

    /** Запрашивает разрешение читать SSID: нужно для показа текущей сети и автопоказа. */
    private void ensureSsidPermission() {
        if (ssidPermissionRequested || hasSsidPermission()) {
            return;
        }
        ssidPermissionRequested = true;
        ssidPermissionLauncher.launch(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean hasSsidPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void updateWifiState() {
        if (!hasSsidPermission()) {
            textWifiState.setText(R.string.settings_rear_no_ssid_permission);
            return;
        }
        String current = WifiConnector.currentSsid(this);
        textWifiState.setText(current != null
                ? getString(R.string.settings_rear_current_wifi, current)
                : getString(R.string.settings_rear_no_wifi));
    }

    private void save() {
        // Прежние значения нужны, чтобы убрать устаревшее предложение сети из Android.
        String previousSsid = Prefs.rearSsid(this);
        String previousPassword = Prefs.rearPassword(this);
        String ssid = editSsid.getText().toString().trim();
        String password = editPassword.getText().toString();
        boolean autoConnect = switchAutoConnect.isChecked();
        Prefs.setRearSsid(this, ssid);
        Prefs.setRearPassword(this, password);
        Prefs.setRearCameraUrl(this, editStreamUrl.getText().toString());
        Prefs.setRearAutoConnect(this, autoConnect);
        Prefs.setRearAutoOpen(this, switchAutoOpen.isChecked());
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();

        if (ssid.isEmpty() || !autoConnect) {
            // Переключатель выключен — снимаем прежнее предложение, иначе Android
            // продолжит подключаться к камере и переключатель останется мёртвым.
            String error = WifiConnector.removeSuggestion(this, previousSsid, previousPassword);
            textResult.setText(error != null
                    ? error
                    : getString(R.string.settings_rear_saved_only));
            return;
        }
        if (!ssid.equals(previousSsid) || !password.equals(previousPassword)) {
            // Сеть изменилась — старое предложение больше не актуально.
            WifiConnector.removeSuggestion(this, previousSsid, previousPassword);
        }
        if (needsNearbyPermission()) {
            nearbyPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES);
        } else {
            applySuggestion();
        }
    }

    private boolean needsNearbyPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void applySuggestion() {
        String ssid = Prefs.rearSsid(this);
        if (ssid.isEmpty()) {
            textResult.setText(R.string.settings_rear_saved_only);
            return;
        }
        String result = WifiConnector.suggest(this, ssid, Prefs.rearPassword(this));
        textResult.setText(result);
    }

    private void connectNow() {
        String ssid = editSsid.getText().toString().trim();
        if (ssid.isEmpty()) {
            textResult.setText(R.string.settings_rear_ssid_required);
            return;
        }
        Prefs.setRearSsid(this, ssid);
        Prefs.setRearPassword(this, editPassword.getText().toString());
        textResult.setText(R.string.settings_rear_connecting);
        WifiConnector.connectNow(this, ssid, editPassword.getText().toString(),
                message -> textResult.setText(message));
    }
}
