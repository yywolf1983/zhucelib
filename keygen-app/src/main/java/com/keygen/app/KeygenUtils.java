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
 *   - keystream = SHA-256 CTR(deviceId || nonce [|| pkgLen || pkgBytes]) 生成 262 字节密钥流 (全量隐写+包绑定)
 *   - 签名消息 = deviceId[12] || nonce[8] || validDays[2 BE] || issuedDay[4 BE] [|| pkgLen[2] || pkgBytes[...]]
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

    // 安装码 XOR 加扰固定密钥（与注册库完全一致）
    private static final byte[] REQUEST_SCRAMBLE_KEY;
    static {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            REQUEST_SCRAMBLE_KEY = md.digest(
                    "RegGate.Request.ScrambleKey.v1".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private KeygenUtils() {}

    /**
     * 从 deviceId、nonce 和可选的 pkgBytes 派生指定长度的 XOR 密钥流（SHA-256 CTR 模式）。
     *
     * <p>若 pkgBytes 非空，则密钥流绑定到具体 App 包，防止同设备不同包间的激活码互换。
     * 若 pkgBytes 为空，向后兼容旧格式（无包绑定）。
     */
    static byte[] deriveKeystream(byte[] deviceId, byte[] nonce, byte[] pkgBytes, int length) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] keystream = new byte[length];
            final boolean hasPkg = pkgBytes != null && pkgBytes.length > 0;
            for (int block = 0; block < length; block += 32) {
                md.reset();
                md.update(deviceId);
                md.update(nonce);
                if (hasPkg) {
                    md.update((byte) (pkgBytes.length >> 8));
                    md.update((byte) pkgBytes.length);
                    md.update(pkgBytes);
                }
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

    /** 对指定范围的载荷做 XOR 置乱/解乱。 */
    static void xorScrambleRange(byte[] payload, byte[] keystream, int offset, int length) {
        for (int i = 0; i < length; i++) {
            payload[offset + i] ^= keystream[i];
        }
    }

    /**
     * 从固定种子密钥派生安装码 XOR 密钥流（SHA-256 CTR 模式）。
     * 与注册库端密钥完全一致。
     */
    static byte[] deriveRequestKeystream(int length) {
        return deriveRequestKeystreamWithNonce(null, length);
    }

    /**
     * 从固定种子 + nonce 派生安装码 XOR 密钥流（SHA-256 CTR 模式）。
     * nonce 非空时密钥流绑定到当前 nonce；为空时退化为纯固定密钥流（用于解码 nonce 本身）。
     * 与注册库端完全一致。
     */
    static byte[] deriveRequestKeystreamWithNonce(@androidx.annotation.Nullable byte[] nonce, int length) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] keystream = new byte[length];
            boolean hasNonce = nonce != null && nonce.length > 0;
            for (int block = 0; block < length; block += 32) {
                md.reset();
                md.update(REQUEST_SCRAMBLE_KEY);
                if (hasNonce) {
                    md.update(nonce);
                }
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
     * 解析安装码。
     * V2 格式: 0x01 || XOR(nonce[8], fixed_ks[0:7]) || XOR(deviceId[12] || pkg, nonce_ks)
     *   - 先恢复 nonce（固定密钥流），再用 nonce 派生密钥流恢复其余。
     * V1 格式: 直通（向后兼容旧版注册库）
     *
     * @return [deviceId, nonce, pkgBytes], 格式错误返回 null。
     *         pkgBytes 可能为空(旧格式或未嵌入包名)。
     */
    static byte[][] parseRequestCode(String requestCode) {
        byte[] raw = Base32.decode(requestCode);
        if (raw == null || raw.length < 1) return null;

        byte[] data;
        if (raw[0] == (byte) 0x01) {
            // V2: 两步解扰
            data = new byte[raw.length - 1];
            System.arraycopy(raw, 1, data, 0, data.length);

            if (data.length < NONCE_LEN + DEVICE_ID_LEN) return null;

            // 第一步: 用固定密钥流恢复 nonce
            byte[] fixedKs = deriveRequestKeystream(data.length);
            byte[] nonce = new byte[NONCE_LEN];
            for (int i = 0; i < NONCE_LEN; i++) {
                nonce[i] = (byte) (data[i] ^ fixedKs[i]);
            }

            // 第二步: 用 nonce 派生密钥流恢复 deviceId + pkg
            int restLen = data.length - NONCE_LEN;
            byte[] nonceKs = deriveRequestKeystreamWithNonce(nonce, restLen);
            byte[] rest = new byte[restLen];
            for (int i = 0; i < restLen; i++) {
                rest[i] = (byte) (data[NONCE_LEN + i] ^ nonceKs[i]);
            }

            if (rest.length < DEVICE_ID_LEN) return null;
            byte[] deviceId = new byte[DEVICE_ID_LEN];
            System.arraycopy(rest, 0, deviceId, 0, DEVICE_ID_LEN);

            byte[] pkgBytes = new byte[0];
            int pkgOff = DEVICE_ID_LEN;
            if (rest.length >= pkgOff + 2) {
                int pkgLen = ((rest[pkgOff] & 0xFF) << 8) | (rest[pkgOff + 1] & 0xFF);
                if (pkgLen > 0 && rest.length >= pkgOff + 2 + pkgLen) {
                    pkgBytes = new byte[pkgLen];
                    System.arraycopy(rest, pkgOff + 2, pkgBytes, 0, pkgLen);
                }
            }
            return new byte[][]{deviceId, nonce, pkgBytes};
        } else {
            // V1: 旧格式直通
            data = raw;

            if (data.length < DEVICE_ID_LEN + NONCE_LEN) return null;
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
        byte[] pkgBytes = parsed[2]; // 可能为空(旧格式向后兼容)

        long issuedDay = System.currentTimeMillis() / DAY_MS;

        byte[] msg = buildSignedMessage(deviceId, nonce, pkgBytes, validDays, issuedDay);

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

        // 全量隐写置乱：用派生密钥流(含包名绑定)对整个 262 字节做 XOR，消除一切固定结构
        byte[] keystream = deriveKeystream(deviceId, nonce, pkgBytes, out.length);
        xorScramble(out, keystream);

        return Base32.group(Base32.encode(out), 5);
    }

    /**
     * 构建签名消息。若 pkgBytes 非空，消息中包含包名以实现包级绑定。
     * 消息结构: deviceId[12] || nonce[8] || validDays[2] || issuedDay[4] [|| pkgLen[2] || pkgBytes[pkgLen]]
     */
    static byte[] buildSignedMessage(byte[] deviceId, byte[] nonce, byte[] pkgBytes,
                                     int validDays, long issuedDay) {
        boolean hasPkg = pkgBytes != null && pkgBytes.length > 0;
        int pkgPart = hasPkg ? 2 + pkgBytes.length : 0;
        byte[] msg = new byte[DEVICE_ID_LEN + NONCE_LEN + VALID_DAYS_LEN + ISSUED_DAY_LEN + pkgPart];
        System.arraycopy(deviceId, 0, msg, 0, DEVICE_ID_LEN);
        System.arraycopy(nonce, 0, msg, DEVICE_ID_LEN, NONCE_LEN);
        int off = DEVICE_ID_LEN + NONCE_LEN;
        msg[off++] = (byte) (validDays >> 8);
        msg[off++] = (byte) validDays;
        msg[off++] = (byte) (issuedDay >> 24);
        msg[off++] = (byte) (issuedDay >> 16);
        msg[off++] = (byte) (issuedDay >> 8);
        msg[off++] = (byte) issuedDay;
        if (hasPkg) {
            msg[off++] = (byte) (pkgBytes.length >> 8);
            msg[off] = (byte) pkgBytes.length;
            System.arraycopy(pkgBytes, 0, msg, off + 1, pkgBytes.length);
        }
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
