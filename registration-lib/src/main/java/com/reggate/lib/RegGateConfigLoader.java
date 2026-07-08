package com.reggate.lib;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;

public class RegGateConfigLoader {

    private static final String CONFIG_FILE_NAME = "reggate_config.dat";

    public static JSONObject loadConfig(Context context) {
        JSONObject config = null;

        try {
            config = loadConfigFromAssets(context, CONFIG_FILE_NAME);
            Log.d("RegGateConfigLoader", "配置文件加载成功");
        } catch (Exception e) {
            Log.w("RegGateConfigLoader", "从 assets 加载配置失败: " + e.getMessage());
        }

        if (config == null) {
            config = createDefaultConfig();
            Log.d("RegGateConfigLoader", "使用默认配置");
        }

        return config;
    }

    private static JSONObject loadConfigFromAssets(Context context, String fileName) throws IOException {
        try (InputStream is = context.getAssets().open(fileName)) {
            return parseInputStream(is);
        }
    }

    private static JSONObject parseInputStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        byte[] bytes = baos.toByteArray();

        String decrypted;
        try {
            decrypted = RegGateCrypto.decrypt(bytes);
        } catch (GeneralSecurityException e) {
            throw new IOException("配置文件解密失败: " + e.getMessage());
        }

        try {
            return new JSONObject(decrypted);
        } catch (JSONException e) {
            throw new IOException("配置文件格式错误: " + e.getMessage());
        }
    }

    private static JSONObject createDefaultConfig() {
        try {
            JSONObject config = new JSONObject();
            config.put("trial_days", 7);
            config.put("prompt_timing", "EVERY_LAUNCH");
            config.put("expire_behavior", "BLOCK");
            config.put("first_trial_delay_ms", 0);

            JSONObject contact = new JSONObject();
            contact.put("phone", "13800138000");
            contact.put("email", "support@example.com");
            contact.put("website", "https://example.com");
            contact.put("shop_url", "");
            contact.put("qr_code_res_name", "reggate_qr_code");
            contact.put("custom_text", "");
            config.put("contact", contact);

            return config;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public static int getInt(JSONObject config, String key, int defaultValue) {
        try {
            return config.has(key) ? config.getInt(key) : defaultValue;
        } catch (JSONException e) {
            return defaultValue;
        }
    }

    public static long getLong(JSONObject config, String key, long defaultValue) {
        try {
            return config.has(key) ? config.getLong(key) : defaultValue;
        } catch (JSONException e) {
            return defaultValue;
        }
    }

    public static String getString(JSONObject config, String key, String defaultValue) {
        try {
            return config.has(key) ? config.getString(key) : defaultValue;
        } catch (JSONException e) {
            return defaultValue;
        }
    }

    public static JSONObject getJSONObject(JSONObject config, String key) {
        try {
            return config.has(key) ? config.getJSONObject(key) : null;
        } catch (JSONException e) {
            return null;
        }
    }

    public static RegGateConfig.PromptTiming getPromptTiming(JSONObject config) {
        String value = getString(config, "prompt_timing", "EVERY_LAUNCH");
        try {
            return RegGateConfig.PromptTiming.valueOf(value);
        } catch (IllegalArgumentException e) {
            return RegGateConfig.PromptTiming.EVERY_LAUNCH;
        }
    }

    public static RegGateConfig.ExpireBehavior getExpireBehavior(JSONObject config) {
        String value = getString(config, "expire_behavior", "BLOCK");
        try {
            return RegGateConfig.ExpireBehavior.valueOf(value);
        } catch (IllegalArgumentException e) {
            return RegGateConfig.ExpireBehavior.BLOCK;
        }
    }

    public static ContactInfo getContactInfo(Context context, JSONObject config) {
        JSONObject contact = getJSONObject(config, "contact");
        if (contact == null) {
            return null;
        }

        String phone = getString(contact, "phone", "");
        String email = getString(contact, "email", "");
        String website = getString(contact, "website", "");
        String shopUrl = getString(contact, "shop_url", "");
        String customText = getString(contact, "custom_text", "");

        String qrCodeResName = getString(contact, "qr_code_res_name", "");
        int qrCodeResId = 0;
        if (!qrCodeResName.isEmpty()) {
            String resName = qrCodeResName;
            if (resName.contains(".")) {
                resName = resName.substring(0, resName.lastIndexOf('.'));
            }
            qrCodeResId = context.getResources().getIdentifier(resName, "drawable", context.getPackageName());
            if (qrCodeResId == 0) {
                qrCodeResId = context.getResources().getIdentifier(resName, "drawable", "com.reggate.lib");
            }
        }

        ContactInfo.Builder builder = ContactInfo.builder();
        if (!phone.isEmpty()) builder.phone(phone);
        if (!email.isEmpty()) builder.email(email);
        if (!website.isEmpty()) builder.website(website);
        if (!shopUrl.isEmpty()) builder.shopUrl(shopUrl);
        if (!customText.isEmpty()) builder.customText(customText);
        if (qrCodeResId != 0) builder.qrCodeResId(qrCodeResId);

        ContactInfo info = builder.build();
        return info.hasContact() ? info : null;
    }
}