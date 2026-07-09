package com.keygen.app;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

/**
 * RSA-2048 + SHA256withRSA 签名工具(私钥侧)。
 *
 * <b>协议(与注册库对称):</b>
 *
 * 输入:安装码 = Base32( deviceId[12] || nonce[8] )
 *
 * 输出:激活码 = Base32( XOR(validDays[2] || issuedDay[4] || sig[256], keystream[262]) )
 *   - keystream = SHA-256 CTR(deviceId || nonce) 生成 262 字节密钥流 (全量隐写)
 *   - 签名消息 = deviceId[12] || nonce[8] || validDays[2 BE] || issuedDay[4 BE]
 *   - sig = SHA256withRSA 签名(私钥)
 *
 * <b>私钥来源:</b>从本地文件加载(.pem/.der),不存储在注册机内。
 * <b>试用配置:</b>写死在注册库中,不在注册码中传递。
 */
final class KeygenUtils {

    static final int DEVICE_ID_LEN = 12;
    static final int NONCE_LEN = 8;
    private static final int VALID_DAYS_LEN = 2;
    private static final int ISSUED_DAY_LEN = 4;
    private static final int SIG_LEN = 256;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private KeygenUtils() {}

    /**
     * 从 deviceId 和 nonce 派生指定长度的 XOR 密钥流（SHA-256 CTR 模式）。
     * 用于对整个激活码载荷做全量隐写置乱，使输出完全不可区分于随机数据。
     * 两端(注册机/客户端)使用相同派生逻辑，确保 XOR 可逆。
     */
    static byte[] deriveKeystream(byte[] deviceId, byte[] nonce, int length) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] keystream = new byte[length];
            for (int block = 0; block < length; block += 32) {
                md.reset();
                md.update(deviceId);
                md.update(nonce);
                // CTR 模式: 块序号写入大端 4 字节
                md.update((byte) (block >> 24));
                md.update((byte) (block >> 16));
                md.update((byte) (block >> 8));
                md.update((byte) block);
                byte[] hash = md.digest();
                int copyLen = Math.min(32, length - block);
                System.arraycopy(hash, 0, keystream, block, copyLen);
            }
            return keystream;
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    /** 对整个载荷做 XOR 置乱/解乱（对称操作）。 */
    static void xorScramble(byte[] payload, byte[] keystream) {
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= keystream[i];
        }
    }

    static PrivateKey parsePrivateKey(String privateKeyBase64) throws Exception {
        String clean = privateKeyBase64
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] raw = Base64.decode(clean, Base64.DEFAULT);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(raw);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

/**
     * 解析安装码: deviceId[12] || nonce[8] || pkgLen[2 BE] || packageName[pkgLen]。
     *
     * @return [deviceId, nonce, pkgBytes], 格式错误返回 null。
     *         pkgBytes 可能为空(旧格式或未嵌入包名)。
     */
    static byte[][] parseRequestCode(String requestCode) {
        byte[] data = Base32.decode(requestCode);
        if (data == null || data.length < DEVICE_ID_LEN + NONCE_LEN) return null;
        byte[] deviceId = new byte[DEVICE_ID_LEN];
        byte[] nonce = new byte[NONCE_LEN];
        System.arraycopy(data, 0, deviceId, 0, DEVICE_ID_LEN);
        System.arraycopy(data, DEVICE_ID_LEN, nonce, 0, NONCE_LEN);

        byte[] pkgBytes = new byte[0];
        int minLen = DEVICE_ID_LEN + NONCE_LEN;
        if (data.length >= minLen + 2) {
            int pkgLen = ((data[minLen] & 0xFF) << 8) | (data[minLen + 1] & 0xFF);
            if (pkgLen > 0 && data.length >= minLen + 2 + pkgLen) {
                pkgBytes = new byte[pkgLen];
                System.arraycopy(data, minLen + 2, pkgBytes, 0, pkgLen);
            }
        }
        return new byte[][]{deviceId, nonce, pkgBytes};
    }

    /**
     * 从安装码中提取包名。无包名返回空字符串，解析失败返回 null。
     */
    @androidx.annotation.Nullable
    static String extractPackageName(String requestCode) {
        byte[][] parsed = parseRequestCode(requestCode);
        if (parsed == null) return null;
        byte[] pkgBytes = parsed[2];
        if (pkgBytes.length == 0) return "";
        return new String(pkgBytes, StandardCharsets.UTF_8);
    }

    static String generateActivationCode(String requestCode, int validDays, PrivateKey priv) throws Exception {
        byte[][] parsed = parseRequestCode(requestCode);
        if (parsed == null) throw new IllegalArgumentException("安装码格式错误");
        byte[] deviceId = parsed[0];
        byte[] nonce = parsed[1];

        long issuedDay = System.currentTimeMillis() / DAY_MS;

        byte[] msg = buildSignedMessage(deviceId, nonce, validDays, issuedDay);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(priv);
        signer.update(msg);
        byte[] sig = signer.sign();

        byte[] out = new byte[VALID_DAYS_LEN + ISSUED_DAY_LEN + SIG_LEN];
        // 构建明文载荷: validDays[2] || issuedDay[4] || sig[256]
        out[0] = (byte) (validDays >> 8);
        out[1] = (byte) validDays;
        out[2] = (byte) (issuedDay >> 24);
        out[3] = (byte) (issuedDay >> 16);
        out[4] = (byte) (issuedDay >> 8);
        out[5] = (byte) issuedDay;
        System.arraycopy(sig, 0, out, VALID_DAYS_LEN + ISSUED_DAY_LEN, sig.length);

        // 全量隐写置乱：用派生密钥流对整个 262 字节做 XOR，消除一切固定结构
        byte[] keystream = deriveKeystream(deviceId, nonce, out.length);
        xorScramble(out, keystream);

        return Base32.group(Base32.encode(out), 5);
    }

    static byte[] buildSignedMessage(byte[] deviceId, byte[] nonce, int validDays, long issuedDay) {
        byte[] msg = new byte[DEVICE_ID_LEN + NONCE_LEN + VALID_DAYS_LEN + ISSUED_DAY_LEN];
        System.arraycopy(deviceId, 0, msg, 0, DEVICE_ID_LEN);
        System.arraycopy(nonce, 0, msg, DEVICE_ID_LEN, NONCE_LEN);
        int off = DEVICE_ID_LEN + NONCE_LEN;
        msg[off++] = (byte) (validDays >> 8);
        msg[off++] = (byte) validDays;
        msg[off++] = (byte) (issuedDay >> 24);
        msg[off++] = (byte) (issuedDay >> 16);
        msg[off++] = (byte) (issuedDay >> 8);
        msg[off] = (byte) issuedDay;
        return msg;
    }

    static long calcExpiryMs(int validDays) {
        if (validDays <= 0) return 0L;
        long issuedDay = System.currentTimeMillis() / DAY_MS;
        return (issuedDay + validDays) * DAY_MS;
    }

    static String formatExpiry(int validDays) {
        if (validDays <= 0) return "永久";
        long exp = calcExpiryMs(validDays);
        return java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(exp),
                java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
}
