package com.example.dashcam;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.security.Key;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Хранение секретов (пароль Wi-Fi камеры) в шифрованном виде.
 *
 * <p>Ключ создаётся в Android Keystore и из устройства никогда не выходит: даже вытащив бэкап
 * или файлы приложения, получить из шифротекста пароль без устройства невозможно.
 * Значения без префикса {@link #PREFIX} считаются старыми (лежали открытым текстом)
 * и возвращаются как есть — они зашифруются при следующем сохранении.</p>
 */
final class SecureStore {

    private static final String TAG = "SecureStore";

    private static final String KEYSTORE_NAME = "AndroidKeyStore";
    private static final String KEY_ALIAS = "dashcam_secrets";
    /** Версионированный префикс: потом можно сменить схему, не ломая старые значения. */
    private static final String PREFIX = "enc:v1:";
    private static final int GCM_TAG_BITS = 128;

    private SecureStore() {
    }

    /** Возвращает зашифрованную строку; при сбое — исходную (лучше сохранить, чем потерять). */
    static String protect(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            return PREFIX
                    + Base64.encodeToString(iv, Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "Не удалось зашифровать значение — сохраняем как есть", e);
            return plain;
        }
    }

    /** Расшифровывает значение, защищённое {@link #protect}; старые значения возвращает как есть. */
    static String unprotect(String value) {
        if (value == null || value.isEmpty() || !value.startsWith(PREFIX)) {
            return value;
        }
        try {
            String payload = value.substring(PREFIX.length());
            String[] parts = payload.split(":", 2);
            if (parts.length != 2) {
                return value;
            }
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Ключ мог устареть (например, после восстановления из бэкапа старых версий) —
            // пароль в таком случае придётся ввести заново, лучше вернуть мусор, чем упасть.
            Log.w(TAG, "Не удалось расшифровать значение", e);
            return "";
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_NAME);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            Key key = keyStore.getKey(KEY_ALIAS, null);
            if (key instanceof SecretKey) {
                return (SecretKey) key;
            }
        }
        KeyGenerator generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_NAME);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
