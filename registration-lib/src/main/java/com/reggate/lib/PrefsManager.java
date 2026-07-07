package com.reggate.lib;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

final class PrefsManager {

    private static final String PREF_NAME = "reggate_prefs";
    private static final String KEY_FIRST_LAUNCH_MS = "first_launch_ms";
    private static final String KEY_ACTIVATION_CODE = "activation_code";
    private static final String KEY_LICENSE_NONCE = "license_nonce";
    private static final String KEY_PENDING_NONCE = "pending_nonce";
    private static final String KEY_TRIAL_DIALOG_SHOWN = "trial_dialog_shown";

    private final SharedPreferences sp;

    PrefsManager(Context ctx) {
        Context app = ctx.getApplicationContext();
        sp = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    long getFirstLaunchMs() { return sp.getLong(KEY_FIRST_LAUNCH_MS, 0L); }

    void setFirstLaunchMsIfAbsent(long now) {
        if (sp.getLong(KEY_FIRST_LAUNCH_MS, 0L) == 0L) {
            sp.edit().putLong(KEY_FIRST_LAUNCH_MS, now).apply();
        }
    }

    String getActivationCode() { return sp.getString(KEY_ACTIVATION_CODE, null); }

    byte[] getLicenseNonce() {
        String b64 = sp.getString(KEY_LICENSE_NONCE, null);
        if (TextUtils.isEmpty(b64)) return null;
        return Base64.decode(b64, Base64.NO_WRAP);
    }

    void saveLicense(String activationCode, byte[] nonce) {
        sp.edit()
                .putString(KEY_ACTIVATION_CODE, activationCode)
                .putString(KEY_LICENSE_NONCE, Base64.encodeToString(nonce, Base64.NO_WRAP))
                .apply();
    }

    void clearLicense() {
        sp.edit()
                .remove(KEY_ACTIVATION_CODE)
                .remove(KEY_LICENSE_NONCE)
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
}
