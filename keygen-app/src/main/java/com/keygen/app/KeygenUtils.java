package com.keygen.app;

import android.util.Base64;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * RSA-2048 + SHA256withRSA 签名工具(私钥侧)。
 *
 * <b>协议(与注册库对称):</b>
 *
 * 输入:安装码 = Base32( deviceId[12] || nonce[8] )
 *
 * 输出:激活码 = Base32( validDays[2 BE] || issuedDay[4 BE] || sig[256] )
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

    static byte[][] parseRequestCode(String requestCode) {
        byte[] data = Base32.decode(requestCode);
        if (data == null || data.length != DEVICE_ID_LEN + NONCE_LEN) return null;
        byte[] deviceId = new byte[DEVICE_ID_LEN];
        byte[] nonce = new byte[NONCE_LEN];
        System.arraycopy(data, 0, deviceId, 0, DEVICE_ID_LEN);
        System.arraycopy(data, DEVICE_ID_LEN, nonce, 0, NONCE_LEN);
        return new byte[][]{deviceId, nonce};
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
        int off = 0;
        out[off++] = (byte) (validDays >> 8);
        out[off++] = (byte) validDays;
        out[off++] = (byte) (issuedDay >> 24);
        out[off++] = (byte) (issuedDay >> 16);
        out[off++] = (byte) (issuedDay >> 8);
        out[off++] = (byte) issuedDay;
        System.arraycopy(sig, 0, out, off, sig.length);

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
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(new java.util.Date(exp));
    }
}
