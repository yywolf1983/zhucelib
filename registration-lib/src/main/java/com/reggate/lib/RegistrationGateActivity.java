package com.reggate.lib;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 注册门 - 宿主 App 的真正入口 Activity(置于主界面之前)。
 *
 * 路由逻辑:
 *   LICENSED       -> 直接进主界面
 *   TRIALING       -> 若 promptTiming=FIRST_LAUNCH 且未弹过,延迟弹试用框,否则直接进主界面
 *   EXPIRED        -> NAG_ONLY: 每次启动弹到期提示框,关闭后进主界面
 *                     BLOCK:    进入激活界面,不激活无法使用
 *   NEED_REGISTER  -> 进入激活界面
 *
 * 该 Activity 使用透明主题,路由期间用户无感。
 */
public class RegistrationGateActivity extends AppCompatActivity {

    private static final int REQ_REGISTER = 0x1001;
    private static final int REQ_NAG = 0x1002;

    private RegistrationManager manager;
    private boolean routed = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
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

    /** 试用中:根据 promptTiming 决定是否弹首次试用框。 */
    private void handleTrialing() {
        boolean needDialog = manager.getConfig().getPromptTiming()
                == RegGateConfig.PromptTiming.FIRST_LAUNCH
                && !manager.isTrialDialogShown();

        if (!needDialog) {
            launchMain();
            return;
        }

        final long delayMs = manager.getConfig().getFirstTrialDialogDelayMs();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing()) return;
            manager.markTrialDialogShown();
            Intent it = new Intent(this, TrialDialogActivity.class);
            it.putExtra(TrialDialogActivity.EXTRA_APP_NAME, manager.getConfig().getAppName());
            it.putExtra(TrialDialogActivity.EXTRA_TRIAL_DAYS, manager.getConfig().getTrialDays());
            it.putExtra(TrialDialogActivity.EXTRA_REMAINING_DAYS, manager.getTrialRemainingDays());
            startActivityForResult(it, REQ_REGISTER);
        }, delayMs);
    }

    /** 到期后:NAG_ONLY 每次弹提示框;BLOCK 强制激活。 */
    private void handleExpired() {
        if (manager.getConfig().getExpireBehavior() == RegGateConfig.ExpireBehavior.NAG_ONLY) {
            // 区分"授权到期"(有存储的激活码但过期)与"试用到期"(无激活码且试用结束)
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
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 基于实际状态决定去留
        boolean canEnter = manager.canEnterMain();

        if (requestCode == REQ_NAG) {
            // 到期提示框关闭后:NAG_ONLY 仍可进入主界面
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
