package com.reggate.lib;

import android.util.Base64;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * RSA-2048 + SHA256withRSA 验签工具(公钥侧)。
 *
 * <b>协议(挑战-响应):</b>
 *
 * 安装码(客户机 → 注册机): Base32( deviceId[12] || nonce[8] )
 *
 * 激活码(注册机 → 客户机): Base32( XOR(validDays[2] || issuedDay[4] || sig[256], keystream[262]) )
 *   - keystream = SHA-256 CTR(deviceId || nonce [|| pkgLen || pkgBytes]) 生成 262 字节密钥流 (全量隐写+包绑定)
 *   - sig = SHA256withRSA 签名,覆盖 deviceId[12] || nonce[8] || validDays[2] || issuedDay[4] [|| pkgLen[2] || pkgBytes[...]]
 *   - 客户机用自身 deviceId+nonce 与激活码中的 validDays+issuedDay 重建签名消息再验签
 *
 * <b>安全模型:</b>
 *   - 公钥编译时写死在注册库源码中
 *   - 私钥由注册机从本地文件加载(.pem/.der),不存储在任何 App 内
 *   - 源码可公开,因为签名密钥始终在注册机操作员手中
 *   - 试用配置(天数、弹框时机、到期行为)写死在注册库中,不在注册码中传递
 */
final class CryptoUtils {

    static final int DEVICE_ID_LEN = 12;
    static final int NONCE_LEN = 8;
    private static final int VALID_DAYS_LEN = 2;
    private static final int ISSUED_DAY_LEN = 4;
    private static final int SIG_LEN = 256;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    // 安装码 XOR 加扰固定密钥（SHA-256("RegGate.Request.ScrambleKey.v1")）
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

    private CryptoUtils() {}

    /**
     * 从 deviceId、nonce 和可选 pkgBytes 派生指定长度的 XOR 密钥流（SHA-256 CTR 模式）。
     *
     * <p>若 pkgBytes 非空则密钥流绑定到具体 App 包，与注册机端完全一致。
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
            return null; // NEVER happen on Android
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
     * 注册库和注册机端密钥一致，确保安装码外观随机且可逆。
     */
    static byte[] deriveRequestKeystream(int length) {
        return deriveRequestKeystreamWithNonce(null, length);
    }

    /**
     * 从固定种子 + nonce 派生安装码 XOR 密钥流（SHA-256 CTR 模式）。
     * nonce 非空时密钥流绑定到当前 nonce，使每次生成的安装码全位随机变化。
     * nonce 为空时退化为纯固定密钥流（用于解码 nonce 本身）。
     */
    static byte[] deriveRequestKeystreamWithNonce(@Nullable byte[] nonce, int length) {
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
            return null; // NEVER happen
        }
    }

    static PublicKey parsePublicKey(String publicKeyBase64) throws Exception {
        String clean = publicKeyBase64
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] raw = Base64.decode(clean, Base64.DEFAULT);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(raw);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    static License verifyActivationCode(String activationCode,
                                        PublicKey pub,
                                        byte[] expectedDeviceId,
                                        byte[] expectedNonce,
                                        byte[] expectedPkgBytes) {
        if (activationCode == null || pub == null
                || expectedDeviceId == null || expectedNonce == null) return null;
        if (expectedDeviceId.length != DEVICE_ID_LEN
                || expectedNonce.length != NONCE_LEN) return null;

        byte[] data = Base32.decode(activationCode);
        if (data == null) return null;
        int expectedLen = VALID_DAYS_LEN + ISSUED_DAY_LEN + SIG_LEN;
        if (data.length != expectedLen) return null;

        // 全量解隐写：用派生密钥流(含包名绑定)对整个 262 字节做 XOR 解乱
        byte[] keystream = deriveKeystream(expectedDeviceId, expectedNonce, expectedPkgBytes, data.length);
        if (keystream == null) return null;
        xorScramble(data, keystream);

        // 从解乱后的明文载荷中解析 validDays、issuedDay、sig
        int validDays = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        long issuedDay = ((long)(data[2] & 0xFF) << 24)
                | ((long)(data[3] & 0xFF) << 16)
                | ((long)(data[4] & 0xFF) << 8)
                | (data[5] & 0xFF);
        byte[] sig = new byte[SIG_LEN];
        System.arraycopy(data, VALID_DAYS_LEN + ISSUED_DAY_LEN, sig, 0, SIG_LEN);

        byte[] msg = buildSignedMessage(expectedDeviceId, expectedNonce, expectedPkgBytes, validDays, issuedDay);

        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(pub);
            verifier.update(msg);
            if (!verifier.verify(sig)) return null;
        } catch (Exception e) {
            return null;
        }

        long issuedMs = issuedDay * DAY_MS;
        long expiryMs = (validDays == 0) ? 0L : (issuedDay + validDays) * DAY_MS;
        return new License(expectedDeviceId, expectedNonce, expectedPkgBytes, validDays, issuedDay,
                issuedMs, expiryMs);
    }

    /**
     * 构建签名消息。若 pkgBytes 非空则包含包名以实现包级绑定。
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

    static final class License {
        public final byte[] deviceId;
        public final byte[] nonce;
        public final byte[] pkgBytes;   // null/empty = 无包绑定(旧格式)
        public final int validDays;
        public final long issuedDay;
        public final long issuedMs;
        public final long expiryMs;

        License(byte[] deviceId, byte[] nonce, byte[] pkgBytes, int validDays, long issuedDay,
                long issuedMs, long expiryMs) {
            this.deviceId = deviceId;
            this.nonce = nonce;
            this.pkgBytes = (pkgBytes != null && pkgBytes.length > 0) ? pkgBytes : null;
            this.validDays = validDays;
            this.issuedDay = issuedDay;
            this.issuedMs = issuedMs;
            this.expiryMs = expiryMs;
        }

        boolean isExpired() {
            return expiryMs > 0 && System.currentTimeMillis() > expiryMs;
        }
    }
}
