package com.reggate.lib;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.nio.charset.StandardCharsets;
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
 * <b>配置管理:</b>
 *   试用配置(天数、弹框时机、到期行为)写死在注册库中,不在注册码中传递。
 *   宿主可通过 {@link RegGateConfig} 覆盖默认配置。
 *
 * 状态机: LICENSED / TRIALING / EXPIRED / NEED_REGISTER
 */
public final class RegistrationManager {

    public enum State { LICENSED, TRIALING, EXPIRED, NEED_REGISTER }

    private static final String TAG = "RegGate";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** 时钟回拨检测容忍值(毫秒)，允许少量正常偏差 */
    static final long CLOCK_TOLERANCE_MS = 60_000L;

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
        // 始终先检查许可: 持有有效许可的用户不应因时钟回拨检测而被阻挡
        if (checkLicensedInternal()) return State.LICENSED;

        if (isTimeTampered()) {
            Log.w(TAG, "检测到系统时钟回拨, 无有效许可, 强制判定为已过期");
            return State.EXPIRED;
        }
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
        if (isTimeTampered()) return false;
        return checkLicensedInternal();
    }

    /** 从 Prefs 读取许可包名字节，无存储返回空数组（向后兼容旧许可）。 */
    private byte[] getLicensePkgBytes() {
        byte[] stored = prefs.getLicensePkgBytes();
        return stored != null ? stored : new byte[0];
    }

    /**
     * 包内许可校验(不含时钟回拨检测)，避免 getCurrentState() 调用链中重复检测。
     */
    private boolean checkLicensedInternal() {
        String code = prefs.getActivationCode();
        byte[] nonce = prefs.getLicenseNonce();
        if (code == null || nonce == null) return false;
        CryptoUtils.License lic = CryptoUtils.verifyActivationCode(
                code, publicKey, deviceId, nonce, getLicensePkgBytes());
        return lic != null && !lic.isExpired();
    }

    public boolean canEnterMain() {
        State s = getCurrentState();
        if (s == State.LICENSED || s == State.TRIALING) return true;
        if (s == State.EXPIRED) return getEffectiveExpireBehavior() == RegGateConfig.ExpireBehavior.NAG_ONLY;
        return false;
    }

    public boolean isLicensedOrTrialing() {
        State s = getCurrentState();
        return s == State.LICENSED || s == State.TRIALING;
    }

    /**
     * 业务级断言:未注册且不是可进入状态则终止进程。宿主应在关键业务方法前调用。
     * 注意:NAG_ONLY 模式下到期后仍可进入,此方法不会杀进程。
     */
    public void ensureRegistered() {
        if (!canEnterMain()) {
            Log.w(TAG, "未注册且不可进入,强制终止");
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    /**
     * 安装生命周期守卫:在每次 Activity 启动时强制校验。
     * 未注册时自动弹出试用框/注册框,而不是直接杀进程。
     */
    public void installLifecycleGuard(android.app.Application appContext) {
        appContext.registerActivityLifecycleCallbacks(new RegGateActivityCallbacks(this));
    }

    void enforceRegistration(android.app.Activity activity) {
        Class<?> cls = activity.getClass();
        if (cls == RegistrationGateActivity.class
                || cls == RegistrationActivity.class
                || cls == TrialDialogActivity.class
                || cls == ExpiredNagActivity.class) {
            return;
        }

        // 异常状态下强制跳转注册页面，忽略状态机
        if (isAnomaly()) {
            startRegistrationActivity(activity, true);
            return;
        }

        State state = getCurrentState();
        if (state == State.LICENSED || state == State.TRIALING) {
            ensureFirstLaunchRecorded();
            handleTrialDialogOnFirstLaunch(activity);
            return;
        }

        if (state == State.EXPIRED) {
            // 异常状态下强制跳转注册页面，忽略 NAG_ONLY 配置
            if (isAnomaly()) {
                startRegistrationActivity(activity, true);
            } else if (getEffectiveExpireBehavior() == RegGateConfig.ExpireBehavior.NAG_ONLY) {
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
        boolean tampered = isTimeTampered();
        boolean anomaly = isAnomaly();
        Intent it = new Intent(activity, RegistrationActivity.class);
        it.putExtra(RegistrationActivity.EXTRA_APP_NAME, config.getAppName());
        it.putExtra(RegistrationActivity.EXTRA_EXPIRED, expired);
        it.putExtra(RegistrationActivity.EXTRA_TIME_TAMPERED, tampered);
        it.putExtra(RegistrationActivity.EXTRA_ANOMALY, anomaly);
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
        return CryptoUtils.verifyActivationCode(
                code, publicKey, deviceId, nonce, getLicensePkgBytes());
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
        return buildRequestCode(deviceId, nonce, app.getPackageName());
    }

    public String regenerateRequestCode() {
        byte[] nonce = DeviceUtils.generateNonce();
        prefs.setPendingNonce(nonce);
        return buildRequestCode(deviceId, nonce, app.getPackageName());
    }

    /**
     * 编码安装码: deviceId[12] || nonce[8] || pkgLen[2 BE] || packageName_utf8[pkgLen]
     * 不含包名时 pkgLen=0(向后兼容旧格式 20 字节)。
     */
    private static String buildRequestCode(byte[] deviceId, byte[] nonce, String packageName) {
        byte[] pkgBytes = (packageName != null && !packageName.isEmpty())
                ? packageName.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int pkgLen = Math.min(pkgBytes.length, 65535);
        int off = 0;
        byte[] data = new byte[deviceId.length + nonce.length + 2 + pkgLen];
        System.arraycopy(deviceId, 0, data, off, deviceId.length);
        off += deviceId.length;
        System.arraycopy(nonce, 0, data, off, nonce.length);
        off += nonce.length;
        data[off++] = (byte) (pkgLen >> 8);
        data[off++] = (byte) pkgLen;
        if (pkgLen > 0) System.arraycopy(pkgBytes, 0, data, off, pkgLen);
        return Base32.encode(data);
    }

    public static byte[] extractNonceFromRequestCode(String requestCode) {
        byte[] data = Base32.decode(requestCode);
        if (data == null || data.length < CryptoUtils.DEVICE_ID_LEN + CryptoUtils.NONCE_LEN) return null;
        byte[] nonce = new byte[CryptoUtils.NONCE_LEN];
        System.arraycopy(data, CryptoUtils.DEVICE_ID_LEN, nonce, 0, nonce.length);
        return nonce;
    }

    public static byte[] extractDeviceIdFromRequestCode(String requestCode) {
        byte[] data = Base32.decode(requestCode);
        if (data == null || data.length < CryptoUtils.DEVICE_ID_LEN + CryptoUtils.NONCE_LEN) return null;
        byte[] id = new byte[CryptoUtils.DEVICE_ID_LEN];
        System.arraycopy(data, 0, id, 0, id.length);
        return id;
    }

    /**
     * 从安装码中提取包名。无包名返回空字符串，解析失败返回 null。
     */
    @androidx.annotation.Nullable
    public static String extractPackageNameFromRequestCode(String requestCode) {
        byte[] data = Base32.decode(requestCode);
        if (data == null || data.length < CryptoUtils.DEVICE_ID_LEN + CryptoUtils.NONCE_LEN + 2) {
            // 旧格式安装码，无包名
            return (data != null && data.length >= CryptoUtils.DEVICE_ID_LEN + CryptoUtils.NONCE_LEN) ? "" : null;
        }
        int off = CryptoUtils.DEVICE_ID_LEN + CryptoUtils.NONCE_LEN;
        int pkgLen = ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
        if (pkgLen == 0) return "";
        if (pkgLen < 0 || off + 2 + pkgLen > data.length) return null;
        return new String(data, off + 2, pkgLen, StandardCharsets.UTF_8);
    }

    // ------------------ 激活码校验 ------------------

    public VerifyResult verifyActivationCode(String inputCode) {
        byte[] nonce = prefs.getPendingNonce();
        if (nonce == null || nonce.length != CryptoUtils.NONCE_LEN) {
            return VerifyResult.invalid("请先获取安装码");
        }
        // 包名参与验签: 安装码中的包名 ≈ 当前 App 的包名
        byte[] pkgBytes = app.getPackageName().getBytes(StandardCharsets.UTF_8);
        CryptoUtils.License lic = CryptoUtils.verifyActivationCode(
                inputCode, publicKey, deviceId, nonce, pkgBytes);
        if (lic == null) {
            return VerifyResult.invalid("激活码无效或与安装码不匹配");
        }
        if (lic.isExpired()) {
            return VerifyResult.invalid("激活码已过期");
        }
        prefs.saveLicense(inputCode.trim(), nonce, app.getPackageName());
        prefs.setPendingNonce(null);
        // 清除异常状态：重置时钟检测基准点，防止之前的时钟回拨标记阻断正常使用
        updateClockCheckpoint();
        return VerifyResult.ok(lic);
    }

    public void revoke() {
        prefs.clearLicense();
    }

    // ------------------ 异常检测 ------------------

    /**
     * 检测许可数据是否损坏：已存储激活码但验签失败（可能被篡改或数据损坏）。
     */
    public boolean hasCorruptLicense() {
        String code = prefs.getActivationCode();
        byte[] nonce = prefs.getLicenseNonce();
        if (code == null || nonce == null) return false;
        CryptoUtils.License lic = CryptoUtils.verifyActivationCode(
                code, publicKey, deviceId, nonce, getLicensePkgBytes());
        return lic == null;
    }

    /**
     * 综合异常检测：时钟回拨、许可数据损坏等都视为注册异常。
     * 异常状态下应强制跳转到注册页面，不应该放行到主界面。
     */
    public boolean isAnomaly() {
        return isTimeTampered() || hasCorruptLicense();
    }

    // ------------------ 试用/到期信息 ------------------

    public long getFirstLaunchMs() { return prefs.getFirstLaunchMs(); }

    /**
     * 单调时钟交叉验证，检测系统时钟是否被回拨。
     * 原理：{@link SystemClock#elapsedRealtime()} 从开机算起只增不减，
     * 与 {@link System#currentTimeMillis()} 交叉比对，若 wall clock
     * 在 realtime 持续增长时反而倒退，则判定为时钟被回拨。
     */
    public boolean isTimeTampered() {
        long lastWall = prefs.getLastWallTime();
        if (lastWall == 0L) {
            updateClockCheckpoint();
            return false;
        }

        long now = System.currentTimeMillis();
        long nowReal = SystemClock.elapsedRealtime();
        long lastReal = prefs.getLastRealTime();
        long realDelta = nowReal - lastReal;

        if (realDelta >= 0) {
            // 未重启：realtime 正常递增，wall clock 不应倒退
            if (now < lastWall - CLOCK_TOLERANCE_MS) {
                Log.w(TAG, "时钟回拨检测: wall=" + now + " < lastWall=" + lastWall
                        + ", realDelta=" + realDelta + "ms");
                return true;
            }
        } else {
            // 设备重启后 realtime 归零。仅检查 wall clock 是否倒退。
            if (now < lastWall - CLOCK_TOLERANCE_MS) {
                Log.w(TAG, "时钟回拨检测(重启后): wall=" + now + " < lastWall=" + lastWall);
                return true;
            }
        }

        updateClockCheckpoint();
        return false;
    }

    private void updateClockCheckpoint() {
        prefs.updateClockCheckpoint(System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    public void ensureFirstLaunchRecorded() {
        long now = System.currentTimeMillis();
        long real = SystemClock.elapsedRealtime();
        prefs.recordFirstLaunchWithClock(now, real);
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
        CryptoUtils.License lic = CryptoUtils.verifyActivationCode(
                code, publicKey, deviceId, nonce, getLicensePkgBytes());
        if (lic == null) return null;
        return lic.expiryMs;
    }

    public int getLicenseRemainingDays() {
        Long exp = getLicenseExpiryMs();
        if (exp == null) return 0;
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
