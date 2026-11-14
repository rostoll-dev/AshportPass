package com.rostoll.ashportpda;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class SecureKeyStore {
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "AshportPDA_MasterKey_v1";
    private static final String PREFS_NAME = "AshportPDA_SecureVault";
    private static final String PREF_CIPHERTEXT = "api_key_cipher";
    private static final String PREF_IV = "api_key_iv";

    private final SharedPreferences prefs;

    public SecureKeyStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ensureKeyExists();
    }

    private void ensureKeyExists() {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
            ks.load(null);
            if (!ks.containsAlias(KEY_ALIAS)) {
                KeyGenerator keyGenerator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build();
                keyGenerator.init(spec);
                keyGenerator.generateKey();
            }
        } catch (Exception ignored) {}
    }

    private SecretKey getSecretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);
        return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
    }

    public void saveApiKey(String plainKey) {
        if (plainKey == null || plainKey.trim().isEmpty()) {
            clearApiKey();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(plainKey.trim().getBytes(StandardCharsets.UTF_8));

            prefs.edit()
                    .putString(PREF_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            // Fallback obfuscation if device KeyStore is unavailable
            prefs.edit()
                    .putString(PREF_CIPHERTEXT, Base64.encodeToString(plainKey.trim().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP))
                    .remove(PREF_IV)
                    .apply();
        }
    }

    public String getApiKey() {
        String cipherB64 = prefs.getString(PREF_CIPHERTEXT, null);
        String ivB64 = prefs.getString(PREF_IV, null);
        if (cipherB64 == null) return "";

        try {
            byte[] encrypted = Base64.decode(cipherB64, Base64.NO_WRAP);
            if (ivB64 == null) {
                return new String(encrypted, StandardCharsets.UTF_8);
            }
            byte[] iv = Base64.decode(ivB64, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), new GCMParameterSpec(128, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public boolean hasApiKey() {
        return !getApiKey().isEmpty();
    }

    public void clearApiKey() {
        prefs.edit().remove(PREF_CIPHERTEXT).remove(PREF_IV).apply();
    }
}
