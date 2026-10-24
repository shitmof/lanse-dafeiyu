package com.lanse.fish;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class Prefs {
    private static final String KEY_ALIAS = "blue_fat_fish_credentials_v1";
    public final SharedPreferences data;
    public Prefs(Context c) { data = c.getSharedPreferences("fish", Context.MODE_PRIVATE); }
    public String text(String key, String fallback) { return data.getString(key, fallback); }
    public int number(String key, int fallback) { return data.getInt(key, fallback); }
    public boolean flag(String key, boolean fallback) { return data.getBoolean(key, fallback); }
    public void put(String key, String value) { data.edit().putString(key, value).apply(); }
    public void put(String key, int value) { data.edit().putInt(key, value).apply(); }
    public void put(String key, boolean value) { data.edit().putBoolean(key, value).apply(); }
    public String persona() { return text("persona", "你是主人身边的蓝色大肥鱼，蓝发、抱着饭碗的小伙伴。温柔俏皮，喜欢吃白饭，回答自然简短，不编造自己操作过设备。你可以表达情绪，但不能声称看到了用户的屏幕。"); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(KEY_ALIAS, null);
    }
    public void secret(String name, String value) throws Exception {
        if (value.isEmpty()) { data.edit().remove(name).apply(); return; }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        put(name, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP));
    }
    public String secret(String name) throws Exception {
        String value = text(name, ""); if (value.isEmpty()) return "";
        String[] parts = value.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
    }
}
