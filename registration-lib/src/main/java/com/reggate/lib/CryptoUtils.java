package com.reggate.lib;

import android.util.Base64;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * RSA-2048 + SHA256withRSA 验签工具(公钥侧)。
 *
 * <b>协议(挑战-响应):</b>
 *
 * 安装码(客户机 → 注册机): Base32( deviceId[12] || nonce[8] )
 *
 * 激活码(注册机 → 客户机): Base32( validDays[2 BE] || issuedDay[4 BE] || sig[256] )
 *   - sig = SHA256withRSA 签名,覆盖 deviceId[12] || nonce[8] || validDays[2] || issuedDay[4]
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

    private CryptoUtils() {}

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
                                        byte[] expectedNonce) {
        if (activationCode == null || pub == null
                || expectedDeviceId == null || expectedNonce == null) return null;
        if (expectedDeviceId.length != DEVICE_ID_LEN
                || expectedNonce.length != NONCE_LEN) return null;

        byte[] data = Base32.decode(activationCode);
        if (data == null) return null;
        int expectedLen = VALID_DAYS_LEN + ISSUED_DAY_LEN + SIG_LEN;
        if (data.length != expectedLen) return null;

        int validDays = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        long issuedDay = ((long)(data[2] & 0xFF) << 24)
                | ((long)(data[3] & 0xFF) << 16)
                | ((long)(data[4] & 0xFF) << 8)
                | (data[5] & 0xFF);
        byte[] sig = new byte[SIG_LEN];
        System.arraycopy(data, VALID_DAYS_LEN + ISSUED_DAY_LEN, sig, 0, SIG_LEN);

        byte[] msg = buildSignedMessage(expectedDeviceId, expectedNonce, validDays, issuedDay);

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
        return new License(expectedDeviceId, expectedNonce, validDays, issuedDay,
                issuedMs, expiryMs);
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

    static final class License {
        public final byte[] deviceId;
        public final byte[] nonce;
        public final int validDays;
        public final long issuedDay;
        public final long issuedMs;
        public final long expiryMs;

        License(byte[] deviceId, byte[] nonce, int validDays, long issuedDay,
                long issuedMs, long expiryMs) {
            this.deviceId = deviceId;
            this.nonce = nonce;
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
