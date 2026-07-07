package com.keygen.app;

import android.util.Base64;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * RSA-2048 + SHA256withRSA 签名工具(私钥侧)。
 *
 * <b>协议(与注册库对称 + 隐写配置):</b>
 *
 * 输入:安装码 = Base32( deviceId[12] || nonce[8] )
 *
 * 输出:激活码 = Base32( flags[1] || validDays[2 BE] || issuedDay[4 BE] || sig[256] )
 *   - flags[1]: 隐写配置(试用天数、弹框时机、到期行为),被签名保护
 *   - 签名消息 = deviceId[12] || nonce[8] || flags[1] || validDays[2 BE] || issuedDay[4 BE]
 *   - sig = SHA256withRSA 签名(私钥)
 *
 * <b>隐写配置(flags 字节):</b>
 *   - bit 0-1: trialDays 模式 (0=3天, 1=7天, 2=14天, 3=无试用)
 *   - bit 2: promptTiming (0=FIRST_LAUNCH, 1=ON_EXPIRY)
 *   - bit 3: expireBehavior (0=BLOCK, 1=NAG_ONLY)
 *   - bit 4-7: 保留(扩展用)
 *
 * <b>私钥来源:</b>从本地文件加载(.pem/.der),不存储在注册机内。
 */
final class KeygenUtils {

    static final int DEVICE_ID_LEN = 12;
    static final int NONCE_LEN = 8;
    private static final int FLAGS_LEN = 1;
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

    static byte buildFlags(int trialDaysMode, int promptTimingMode, boolean nagOnlyExpire) {
        byte flags = 0;
        flags |= (trialDaysMode & 0x03) << 0;
        flags |= (promptTimingMode & 0x03) << 2;
        flags |= (nagOnlyExpire ? 1 : 0) << 4;
        return flags;
    }

    static int trialDaysModeFromDays(int days) {
        if (days <= 0) return 3;
        if (days <= 3) return 0;
        if (days <= 7) return 1;
        return 2;
    }

    static String trialDaysLabel(int mode) {
        switch (mode) {
            case 0: return "3天";
            case 1: return "7天";
            case 2: return "14天";
            default: return "无试用";
        }
    }

    static String generateActivationCode(String requestCode, int validDays, int trialDaysMode,
                                         int promptTimingMode, boolean nagOnlyExpire,
                                         PrivateKey priv) throws Exception {
        byte[][] parsed = parseRequestCode(requestCode);
        if (parsed == null) throw new IllegalArgumentException("安装码格式错误");
        byte[] deviceId = parsed[0];
        byte[] nonce = parsed[1];

        long issuedDay = System.currentTimeMillis() / DAY_MS;
        byte flags = buildFlags(trialDaysMode, promptTimingMode, nagOnlyExpire);

        byte[] msg = buildSignedMessage(deviceId, nonce, flags, validDays, issuedDay);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(priv);
        signer.update(msg);
        byte[] sig = signer.sign();

        byte[] out = new byte[FLAGS_LEN + VALID_DAYS_LEN + ISSUED_DAY_LEN + SIG_LEN];
        int off = 0;
        out[off++] = flags;
        out[off++] = (byte) (validDays >> 8);
        out[off++] = (byte) validDays;
        out[off++] = (byte) (issuedDay >> 24);
        out[off++] = (byte) (issuedDay >> 16);
        out[off++] = (byte) (issuedDay >> 8);
        out[off++] = (byte) issuedDay;
        System.arraycopy(sig, 0, out, off, sig.length);

        return Base32.group(Base32.encode(out), 5);
    }

    static byte[] buildSignedMessage(byte[] deviceId, byte[] nonce, byte flags, int validDays, long issuedDay) {
        byte[] msg = new byte[DEVICE_ID_LEN + NONCE_LEN + FLAGS_LEN + VALID_DAYS_LEN + ISSUED_DAY_LEN];
        System.arraycopy(deviceId, 0, msg, 0, DEVICE_ID_LEN);
        System.arraycopy(nonce, 0, msg, DEVICE_ID_LEN, NONCE_LEN);
        int off = DEVICE_ID_LEN + NONCE_LEN;
        msg[off++] = flags;
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
