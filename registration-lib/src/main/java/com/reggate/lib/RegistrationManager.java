package com.reggate.lib;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.security.PublicKey;

/**
 * 注册状态管理器:安装码生成、激活码校验、状态判定。
 *
 * <b>绝对控制权机制:</b>
 *   1. Application 级拦截:宿主继承 {@link RegGateApplication} 或调用 {@link #installLifecycleGuard(android.app.Application)}
 *      在每次 Activity 启动时强制校验,未注册则终止进程
 *   2. 业务级断言:宿主在关键方法前调用 {@link #ensureRegistered()}
 *   3. GateActivity 入口:置于主界面之前
 *   4. 每次启动重新验签(不从缓存信任),防止篡改
 *
 * <b>隐写配置:</b>
 *   激活码中包含 flags 字节,存储试用天数、弹框时机、到期行为等配置。
 *   这些配置被 RSA 签名保护,即使看到源码也无法篡改。
 *   未激活时使用默认配置,激活后使用激活码中的配置。
 *
 * 状态机: LICENSED / TRIALING / EXPIRED / NEED_REGISTER
 */
public final class RegistrationManager {

    public enum State { LICENSED, TRIALING, EXPIRED, NEED_REGISTER }

    private static final String TAG = "RegGate";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final Context app;
    private final PrefsManager prefs;
    private final RegGateConfig config;
    private final PublicKey publicKey;
    private final byte[] deviceId;

    public RegistrationManager(Context ctx) {
        this.app = ctx.getApplicationContext();
        this.config = RegGateConfig.get();
        this.prefs = new PrefsManager(app);
        try {
            this.publicKey = CryptoUtils.parsePublicKey(config.getPublicKeyBase64());
        } catch (Exception e) {
            throw new IllegalStateException("公钥解析失败", e);
        }
        this.deviceId = DeviceUtils.getDeviceIdBytes(app);
    }

    public State getCurrentState() {
        if (isLicensed()) return State.LICENSED;
        int trialDays = getEffectiveTrialDays();
        if (trialDays > 0) {
            long first = prefs.getFirstLaunchMs();
            if (first == 0L) return State.TRIALING;
            long elapsed = System.currentTimeMillis() - first;
            long trialMs = trialDays * DAY_MS;
            return elapsed < trialMs ? State.TRIALING : State.EXPIRED;
        }
        return State.NEED_REGISTER;
    }

    public boolean isLicensed() {
        String code = prefs.getActivationCode();
        byte[] nonce = prefs.getLicenseNonce();
        if (code == null || nonce == null) return false;
        CryptoUtils.License lic = CryptoUtils.verifyActivationCode(code, publicKey, deviceId, nonce);
        return lic != null && !lic.isExpired();
    }

    public boolean canEnterMain() {
        State s = getCurrentState();
        if (s == State.LICENSED || s == State.TRIALING) return true;
        if (s == State.EXPIRED) return getEffectiveExpireBehavior() == RegGateConfig.ExpireBehavior.NAG_ONLY;
        return false;
    }

    public boolean isLicensedOrTrialing() {
        return getCurrentState() == State.LICENSED
                || getCurrentState() == State.TRIALING;
    }

    /**
     * 业务级断言:未注册则终止进程。宿主应在关键业务方法前调用。
     */
    public void ensureRegistered() {
        if (!isLicensedOrTrialing()) {
            Log.w(TAG, "未注册或试用期已过,强制终止");
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    /**
     * 安装生命周期守卫:在每次 Activity 启动时强制校验。
     * 未注册时自动弹出试用框/注册框,而不是直接杀进程。
     */
    public void installLifecycleGuard(android.app.Application appContext) {
        appContext.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}
            @Override
            public void onActivityStarted(android.app.Activity a) {
                enforceRegistration(a);
            }
            @Override
            public void onActivityResumed(android.app.Activity a) {}
            @Override
            public void onActivityPaused(android.app.Activity a) {}
            @Override
            public void onActivityStopped(android.app.Activity a) {}
            @Override
            public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
            @Override
            public void onActivityDestroyed(android.app.Activity a) {}
        });
    }

    private void enforceRegistration(android.app.Activity activity) {
        Class<?> cls = activity.getClass();
        if (cls == RegistrationGateActivity.class
                || cls == RegistrationActivity.class
                || cls == TrialDialogActivity.class
                || cls == ExpiredNagActivity.class) {
            return;
        }

        State state = getCurrentState();
        if (state == State.LICENSED || state == State.TRIALING) {
            ensureFirstLaunchRecorded();
            handleTrialDialogOnFirstLaunch(activity);
            return;
        }

        if (state == State.EXPIRED) {
            if (getEffectiveExpireBehavior() == RegGateConfig.ExpireBehavior.NAG_ONLY) {
                startExpiredNagActivity(activity);
            } else {
                startRegistrationActivity(activity, true);
            }
            return;
        }

        startRegistrationActivity(activity, false);
    }

    private void handleTrialDialogOnFirstLaunch(android.app.Activity activity) {
        if (isLicensed()) return;
        RegGateConfig.PromptTiming timing = getEffectivePromptTiming();
        boolean needDialog = timing == RegGateConfig.PromptTiming.FIRST_LAUNCH && !isTrialDialogShown()
                || timing == RegGateConfig.PromptTiming.EVERY_LAUNCH;
        if (!needDialog) return;

        if (timing == RegGateConfig.PromptTiming.FIRST_LAUNCH) {
            markTrialDialogShown();
        }

        Intent it = new Intent(activity, TrialDialogActivity.class);
        it.putExtra(TrialDialogActivity.EXTRA_APP_NAME, config.getAppName());
        it.putExtra(TrialDialogActivity.EXTRA_TRIAL_DAYS, getEffectiveTrialDays());
        it.putExtra(TrialDialogActivity.EXTRA_REMAINING_DAYS, getTrialRemainingDays());
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(it);
    }

    private void startRegistrationActivity(android.app.Activity activity, boolean expired) {
        Intent it = new Intent(activity, RegistrationActivity.class);
        it.putExtra(RegistrationActivity.EXTRA_APP_NAME, config.getAppName());
        it.putExtra(RegistrationActivity.EXTRA_EXPIRED, expired);
        it.putExtra(RegistrationActivity.EXTRA_TRIAL_REMAINING_DAYS, getTrialRemainingDays());
        it.putExtra(RegistrationActivity.EXTRA_LICENSE_REMAINING_DAYS, getLicenseRemainingDays());
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(it);
    }

    private void startExpiredNagActivity(android.app.Activity activity) {
        boolean licenseExpired = getLicenseExpiryMs() != null;
        Intent it = new Intent(activity, ExpiredNagActivity.class);
        it.putExtra(ExpiredNagActivity.EXTRA_APP_NAME, config.getAppName());
        it.putExtra(ExpiredNagActivity.EXTRA_TRIAL_EXPIRED, !licenseExpired);
        it.putExtra(ExpiredNagActivity.EXTRA_LICENSE_REMAINING, getLicenseRemainingDays());
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(it);
    }

    // ------------------ 隐写配置(从激活码读取) ------------------

    private CryptoUtils.License getActiveLicense() {
        String code = prefs.getActivationCode();
        byte[] nonce = prefs.getLicenseNonce();
        if (code == null || nonce == null) return null;
        return CryptoUtils.verifyActivationCode(code, publicKey, deviceId, nonce);
    }

    public int getEffectiveTrialDays() {
        return config.getTrialDays();
    }

    public RegGateConfig.PromptTiming getEffectivePromptTiming() {
        return config.getPromptTiming();
    }

    public RegGateConfig.ExpireBehavior getEffectiveExpireBehavior() {
        return config.getExpireBehavior();
    }

    // ------------------ 安装码 ------------------

    public String getCurrentRequestCode() {
        byte[] nonce = prefs.getPendingNonce();
        if (nonce == null || nonce.length != CryptoUtils.NONCE_LEN) {
            nonce = DeviceUtils.generateNonce();
            prefs.setPendingNonce(nonce);
        }
        return buildRequestCode(deviceId, nonce);
    }

    public String regenerateRequestCode() {
        byte[] nonce = DeviceUtils.generateNonce();
        prefs.setPendingNonce(nonce);
        return buildRequestCode(deviceId, nonce);
    }

    private static String buildRequestCode(byte[] deviceId, byte[] nonce) {
        byte[] data = new byte[deviceId.length + nonce.length];
        System.arraycopy(deviceId, 0, data, 0, deviceId.length);
        System.arraycopy(nonce, 0, data, deviceId.length, nonce.length);
        return Base32.encode(data);
    }

    public static byte[] extractNonceFromRequestCode(String requestCode) {
        byte[] data = Base32.decode(requestCode);
        if (data == null || data.length != CryptoUtils.DEVICE_ID_LEN + CryptoUtils.NONCE_LEN) return null;
        byte[] nonce = new byte[CryptoUtils.NONCE_LEN];
        System.arraycopy(data, CryptoUtils.DEVICE_ID_LEN, nonce, 0, nonce.length);
        return nonce;
    }

    public static byte[] extractDeviceIdFromRequestCode(String requestCode) {
        byte[] data = Base32.decode(requestCode);
        if (data == null || data.length != CryptoUtils.DEVICE_ID_LEN + CryptoUtils.NONCE_LEN) return null;
        byte[] id = new byte[CryptoUtils.DEVICE_ID_LEN];
        System.arraycopy(data, 0, id, 0, id.length);
        return id;
    }

    // ------------------ 激活码校验 ------------------

    public VerifyResult verifyActivationCode(String inputCode) {
        byte[] nonce = prefs.getPendingNonce();
        if (nonce == null || nonce.length != CryptoUtils.NONCE_LEN) {
            return VerifyResult.invalid("请先获取安装码");
        }
        CryptoUtils.License lic = CryptoUtils.verifyActivationCode(
                inputCode, publicKey, deviceId, nonce);
        if (lic == null) {
            return VerifyResult.invalid("激活码无效或与安装码不匹配");
        }
        if (lic.isExpired()) {
            return VerifyResult.invalid("激活码已过期");
        }
        prefs.saveLicense(inputCode.trim(), nonce);
        prefs.setPendingNonce(null);
        return VerifyResult.ok(lic);
    }

    public void revoke() {
        prefs.clearLicense();
    }

    // ------------------ 试用/到期信息 ------------------

    public long getFirstLaunchMs() { return prefs.getFirstLaunchMs(); }

    public void ensureFirstLaunchRecorded() {
        prefs.setFirstLaunchMsIfAbsent(System.currentTimeMillis());
    }

    public long getTrialRemainingMs() {
        int trialDays = getEffectiveTrialDays();
        if (trialDays <= 0) return 0L;
        long first = prefs.getFirstLaunchMs();
        if (first == 0L) return trialDays * DAY_MS;
        long now = System.currentTimeMillis();
        if (first > now) return trialDays * DAY_MS;
        long r = trialDays * DAY_MS - (now - first);
        return r < 0 ? 0L : r;
    }

    public int getTrialRemainingDays() {
        long ms = getTrialRemainingMs();
        if (ms <= 0) return 0;
        return (int) Math.ceil(ms / (double) DAY_MS);
    }

    public Long getLicenseExpiryMs() {
        String code = prefs.getActivationCode();
        byte[] nonce = prefs.getLicenseNonce();
        if (code == null || nonce == null) return null;
        CryptoUtils.License lic = CryptoUtils.verifyActivationCode(code, publicKey, deviceId, nonce);
        if (lic == null) return null;
        return lic.expiryMs;
    }

    public int getLicenseRemainingDays() {
        Long exp = getLicenseExpiryMs();
        if (exp == null) return Integer.MIN_VALUE;
        if (exp == 0L) return -1;
        long r = exp - System.currentTimeMillis();
        return r <= 0 ? 0 : (int) Math.ceil(r / (double) DAY_MS);
    }

    // ------------------ 试用框标记 ------------------

    public boolean isTrialDialogShown() { return prefs.isTrialDialogShown(); }
    public void markTrialDialogShown() { prefs.markTrialDialogShown(); }

    public RegGateConfig getConfig() { return config; }
    public byte[] getDeviceId() { return deviceId; }

    public static final class VerifyResult {
        public final boolean success;
        public final String message;
        public final CryptoUtils.License license;
        private VerifyResult(boolean ok, String msg, CryptoUtils.License lic) {
            this.success = ok; this.message = msg; this.license = lic;
        }
        static VerifyResult ok(CryptoUtils.License lic) { return new VerifyResult(true, "激活成功", lic); }
        static VerifyResult invalid(String msg) { return new VerifyResult(false, msg, null); }
    }
}
