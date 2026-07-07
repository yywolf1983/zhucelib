package com.reggate.lib;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public class RegistrationGateActivity extends Activity {

    private static final int REQ_REGISTER = 0x1001;
    private static final int REQ_NAG = 0x1002;

    private RegistrationManager manager;
    private boolean routed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            manager = new RegistrationManager(this);
        } catch (IllegalStateException e) {
            finish();
            return;
        }

        manager.ensureFirstLaunchRecorded();

        RegistrationManager.State state = manager.getCurrentState();
        switch (state) {
            case LICENSED:
                launchMain();
                return;
            case TRIALING:
                handleTrialing();
                return;
            case EXPIRED:
                handleExpired();
                return;
            case NEED_REGISTER:
            default:
                startRegistrationActivity(false);
                return;
        }
    }

    private void handleTrialing() {
        RegGateConfig.PromptTiming timing = manager.getConfig().getPromptTiming();
        boolean needDialog = timing == RegGateConfig.PromptTiming.FIRST_LAUNCH
                && !manager.isTrialDialogShown()
                || timing == RegGateConfig.PromptTiming.EVERY_LAUNCH;

        if (!needDialog) {
            launchMain();
            return;
        }

        if (timing == RegGateConfig.PromptTiming.FIRST_LAUNCH) {
            manager.markTrialDialogShown();
        }

        final long delayMs = manager.getConfig().getFirstTrialDialogDelayMs();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing()) return;
            Intent it = new Intent(this, TrialDialogActivity.class);
            it.putExtra(TrialDialogActivity.EXTRA_APP_NAME, manager.getConfig().getAppName());
            it.putExtra(TrialDialogActivity.EXTRA_TRIAL_DAYS, manager.getConfig().getTrialDays());
            it.putExtra(TrialDialogActivity.EXTRA_REMAINING_DAYS, manager.getTrialRemainingDays());
            startActivityForResult(it, REQ_REGISTER);
        }, delayMs);
    }

    private void handleExpired() {
        if (manager.getConfig().getExpireBehavior() == RegGateConfig.ExpireBehavior.NAG_ONLY) {
            boolean licenseExpired = manager.getLicenseExpiryMs() != null;
            Intent it = new Intent(this, ExpiredNagActivity.class);
            it.putExtra(ExpiredNagActivity.EXTRA_APP_NAME, manager.getConfig().getAppName());
            it.putExtra(ExpiredNagActivity.EXTRA_TRIAL_EXPIRED, !licenseExpired);
            it.putExtra(ExpiredNagActivity.EXTRA_LICENSE_REMAINING, manager.getLicenseRemainingDays());
            startActivityForResult(it, REQ_NAG);
        } else {
            startRegistrationActivity(true);
        }
    }

    private void startRegistrationActivity(boolean expired) {
        Intent it = new Intent(this, RegistrationActivity.class);
        it.putExtra(RegistrationActivity.EXTRA_APP_NAME, manager.getConfig().getAppName());
        it.putExtra(RegistrationActivity.EXTRA_EXPIRED, expired);
        it.putExtra(RegistrationActivity.EXTRA_TRIAL_REMAINING_DAYS, manager.getTrialRemainingDays());
        it.putExtra(RegistrationActivity.EXTRA_LICENSE_REMAINING_DAYS, manager.getLicenseRemainingDays());
        startActivityForResult(it, REQ_REGISTER);
    }

    private void launchMain() {
        if (routed) return;
        routed = true;
        Class<?> main = manager.getConfig().getMainActivityClass();
        Intent it = new Intent(this, main);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(it);
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        boolean canEnter = manager.canEnterMain();

        if (requestCode == REQ_NAG) {
            if (canEnter) launchMain();
            else finishAffinity();
            return;
        }

        if (requestCode == REQ_REGISTER) {
            if (canEnter) launchMain();
            else finishAffinity();
            return;
        }
    }
}
