package com.reggate.lib;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import java.nio.charset.StandardCharsets;

final class PrefsManager {

    private static final String PREF_NAME = "reggate_prefs";
    private static final String KEY_FIRST_LAUNCH_MS = "first_launch_ms";
    private static final String KEY_ACTIVATION_CODE = "activation_code";
    private static final String KEY_LICENSE_NONCE = "license_nonce";
    private static final String KEY_LICENSE_PACKAGE = "license_package";
    private static final String KEY_PENDING_NONCE = "pending_nonce";
    private static final String KEY_TRIAL_DIALOG_SHOWN = "trial_dialog_shown";
    private static final String KEY_LAST_WALL_TIME = "last_wall_time";
    private static final String KEY_LAST_REAL_TIME = "last_real_time";

    private final SharedPreferences sp;

    PrefsManager(Context ctx) {
        Context app = ctx.getApplicationContext();
        sp = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    long getFirstLaunchMs() { return sp.getLong(KEY_FIRST_LAUNCH_MS, 0L); }

    String getActivationCode() { return sp.getString(KEY_ACTIVATION_CODE, null); }

    byte[] getLicenseNonce() {
        String b64 = sp.getString(KEY_LICENSE_NONCE, null);
        if (TextUtils.isEmpty(b64)) return null;
        return Base64.decode(b64, Base64.NO_WRAP);
    }

    /** 获取存储的许可包名（用于包级验签绑定）。无存储返回 null。 */
    @androidx.annotation.Nullable
    byte[] getLicensePkgBytes() {
        String pkg = sp.getString(KEY_LICENSE_PACKAGE, null);
        if (TextUtils.isEmpty(pkg)) return null;
        return Base64.decode(pkg, Base64.NO_WRAP);
    }

    void saveLicense(String activationCode, byte[] nonce, String packageName) {
        sp.edit()
                .putString(KEY_ACTIVATION_CODE, activationCode)
                .putString(KEY_LICENSE_NONCE, Base64.encodeToString(nonce, Base64.NO_WRAP))
                .putString(KEY_LICENSE_PACKAGE, !TextUtils.isEmpty(packageName)
                        ? Base64.encodeToString(packageName.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP)
                        : null)
                .apply();
    }

    void clearLicense() {
        sp.edit()
                .remove(KEY_ACTIVATION_CODE)
                .remove(KEY_LICENSE_NONCE)
                .remove(KEY_LICENSE_PACKAGE)
                .apply();
    }

    boolean hasLicense() { return !TextUtils.isEmpty(getActivationCode()); }

    byte[] getPendingNonce() {
        String b64 = sp.getString(KEY_PENDING_NONCE, null);
        if (TextUtils.isEmpty(b64)) return null;
        return Base64.decode(b64, Base64.NO_WRAP);
    }

    void setPendingNonce(byte[] nonce) {
        if (nonce == null) {
            sp.edit().remove(KEY_PENDING_NONCE).apply();
        } else {
            sp.edit().putString(KEY_PENDING_NONCE, Base64.encodeToString(nonce, Base64.NO_WRAP)).apply();
        }
    }

    boolean isTrialDialogShown() { return sp.getBoolean(KEY_TRIAL_DIALOG_SHOWN, false); }

    void markTrialDialogShown() { sp.edit().putBoolean(KEY_TRIAL_DIALOG_SHOWN, true).apply(); }

    // ------------------ 单调时钟交叉验证(防系统时钟回拨) ------------------

    long getLastWallTime() { return sp.getLong(KEY_LAST_WALL_TIME, 0L); }

    long getLastRealTime() { return sp.getLong(KEY_LAST_REAL_TIME, 0L); }

    void updateClockCheckpoint(long wallTime, long realTime) {
        sp.edit()
                .putLong(KEY_LAST_WALL_TIME, wallTime)
                .putLong(KEY_LAST_REAL_TIME, realTime)
                .apply();
    }

    /**
     * 首次启动时同时记录 wall clock 和单调时钟偏移。
     */
    void recordFirstLaunchWithClock(long wallClockMs, long realTimeMs) {
        if (sp.getLong(KEY_FIRST_LAUNCH_MS, 0L) == 0L) {
            sp.edit()
                    .putLong(KEY_FIRST_LAUNCH_MS, wallClockMs)
                    .putLong(KEY_LAST_WALL_TIME, wallClockMs)
                    .putLong(KEY_LAST_REAL_TIME, realTimeMs)
                    .apply();
        }
    }
}
