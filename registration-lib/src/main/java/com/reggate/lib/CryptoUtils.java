package com.reggate.lib;

import android.util.Base64;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * RSA-2048 + SHA256withRSA 验签工具(公钥侧)。
 *
 * <b>协议(挑战-响应 + 隐写配置):</b>
 *
 * 安装码(客户机 → 注册机): Base32( deviceId[12] || nonce[8] )
 *
 * 激活码(注册机 → 客户机): Base32( flags[1] || validDays[2 BE] || issuedDay[4 BE] || sig[256] )
 *   - flags[1]: 隐写配置(试用天数、弹框时机、到期行为),被签名保护
 *   - sig = SHA256withRSA 签名,覆盖 deviceId[12] || nonce[8] || flags[1] || validDays[2] || issuedDay[4]
 *   - 客户机用自身 deviceId+nonce 与激活码中的 flags+validDays+issuedDay 重建签名消息再验签
 *
 * <b>隐写配置(flags 字节):</b>
 *   - bit 0-1: trialDays 模式 (0=3天, 1=7天, 2=14天, 3=无试用)
 *   - bit 2: promptTiming (0=FIRST_LAUNCH, 1=ON_EXPIRY)
 *   - bit 3: expireBehavior (0=BLOCK, 1=NAG_ONLY)
 *   - bit 4-7: 保留(扩展用)
 *
 * <b>安全模型:</b>
 *   - 公钥编译时写死在注册库源码中
 *   - 私钥由注册机从本地文件加载(.pem/.der),不存储在任何 App 内
 *   - 源码可公开,因为签名密钥始终在注册机操作员手中
 *   - 配置隐写在激活码中,即使看到源码也无法伪造或篡改
 */
final class CryptoUtils {

    static final int DEVICE_ID_LEN = 12;
    static final int NONCE_LEN = 8;
    private static final int FLAGS_LEN = 1;
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
        int expectedLen = FLAGS_LEN + VALID_DAYS_LEN + ISSUED_DAY_LEN + SIG_LEN;
        if (data.length != expectedLen) return null;

        byte flags = data[0];
        int validDays = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        long issuedDay = ((long)(data[3] & 0xFF) << 24)
                | ((long)(data[4] & 0xFF) << 16)
                | ((long)(data[5] & 0xFF) << 8)
                | (data[6] & 0xFF);
        byte[] sig = new byte[SIG_LEN];
        System.arraycopy(data, FLAGS_LEN + VALID_DAYS_LEN + ISSUED_DAY_LEN, sig, 0, SIG_LEN);

        byte[] msg = buildSignedMessage(expectedDeviceId, expectedNonce, flags, validDays, issuedDay);

        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(pub);
            verifier.update(msg);
            if (!verifier.verify(sig)) return null;
        } catch (Exception e) {
            return null;
        }

        License lic = new License();
        lic.deviceId = expectedDeviceId;
        lic.nonce = expectedNonce;
        lic.flags = flags;
        lic.validDays = validDays;
        lic.issuedDay = issuedDay;
        lic.issuedMs = issuedDay * DAY_MS;
        lic.expiryMs = (validDays == 0) ? 0L : (issuedDay + validDays) * DAY_MS;
        return lic;
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

    static final class License {
        public byte[] deviceId;
        public byte[] nonce;
        public byte flags;
        public int validDays;
        public long issuedDay;
        public long issuedMs;
        public long expiryMs;

        boolean isExpired() {
            return expiryMs > 0 && System.currentTimeMillis() > expiryMs;
        }

        int getTrialDays() {
            int mode = (flags >> 0) & 0x03;
            switch (mode) {
                case 0: return 3;
                case 1: return 7;
                case 2: return 14;
                default: return 0;
            }
        }

        RegGateConfig.PromptTiming getPromptTiming() {
            int mode = (flags >> 2) & 0x03;
            switch (mode) {
                case 0: return RegGateConfig.PromptTiming.FIRST_LAUNCH;
                case 1: return RegGateConfig.PromptTiming.ON_EXPIRY;
                case 2: return RegGateConfig.PromptTiming.EVERY_LAUNCH;
                default: return RegGateConfig.PromptTiming.FIRST_LAUNCH;
            }
        }

        RegGateConfig.ExpireBehavior getExpireBehavior() {
            int bit = (flags >> 4) & 0x01;
            return bit == 0 ? RegGateConfig.ExpireBehavior.BLOCK : RegGateConfig.ExpireBehavior.NAG_ONLY;
        }
    }
}
