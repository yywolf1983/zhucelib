package com.reggate.lib;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 设备指纹工具。
 *
 * 生成 12 字节(96 位)设备指纹,用于 {@link CryptoUtils} 的安装码/激活码绑定。
 * 来源:SHA-256(AndroidID + 厂商 + 型号 + 品牌) 前 12 字节。
 */
public final class DeviceUtils {

    public static final int DEVICE_ID_LEN = 12;

    private DeviceUtils() {}

    /** 12 字节设备指纹。 */
    public static byte[] getDeviceIdBytes(Context ctx) {
        String raw = getRawFingerprint(ctx);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes("UTF-8"));
            byte[] out = new byte[DEVICE_ID_LEN];
            System.arraycopy(d, 0, out, 0, DEVICE_ID_LEN);
            return out;
        } catch (Exception e) {
            // 退化:用 hashCode 填充(极端情况)
            byte[] out = new byte[DEVICE_ID_LEN];
            int h = raw.hashCode();
            for (int i = 0; i < DEVICE_ID_LEN; i += 4) {
                int v = (h == 0) ? i : h * (i + 1);
                out[i] = (byte) (v);
                if (i + 1 < DEVICE_ID_LEN) out[i + 1] = (byte) (v >> 8);
                if (i + 2 < DEVICE_ID_LEN) out[i + 2] = (byte) (v >> 16);
                if (i + 3 < DEVICE_ID_LEN) out[i + 3] = (byte) (v >> 24);
            }
            return out;
        }
    }

    @SuppressLint("HardwareIds")
    private static String getRawFingerprint(Context ctx) {
        String androidId = Settings.Secure.getString(
                ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.length() == 0) androidId = "unknown";
        return androidId + "|" + Build.MANUFACTURER + "|" + Build.MODEL + "|" + Build.BRAND;
    }

    /** 8 字节随机挑战 nonce(每次生成安装码时调用)。 */
    public static byte[] generateNonce() {
        byte[] nonce = new byte[8];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    }
}
