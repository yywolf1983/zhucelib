package com.reggate.demo;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.reggate.lib.RegGateConfig;
import com.reggate.lib.RegistrationManager;

/**
 * Demo 主界面：仅展示注册状态，不包含任何注册机交互。
 * 所有注册逻辑由注册库自动完成（试用框、激活界面等）。
 */
public class MainActivity extends AppCompatActivity {

    private RegistrationManager manager;
    private TextView tvStatus;
    private TextView tvLimits;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        manager = new RegistrationManager(this);

        tvStatus = findViewById(R.id.tv_status);
        tvLimits = findViewById(R.id.tv_limits);

        updateStatus();
        updateLimits();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        updateLimits();
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("注册状态: ");
        RegistrationManager.State state = manager.getCurrentState();
        if (state == RegistrationManager.State.LICENSED) {
            sb.append("已注册");
            Long expiry = manager.getLicenseExpiryMs();
            if (expiry != null && expiry > 0) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd",
                        java.util.Locale.getDefault());
                sb.append("\n到期时间: ").append(sdf.format(new java.util.Date(expiry)));
            } else {
                sb.append("\n到期时间: 永久");
            }
        } else if (state == RegistrationManager.State.TRIALING) {
            sb.append("试用中");
            sb.append("\n剩余天数: ").append(manager.getTrialRemainingDays());
        } else if (state == RegistrationManager.State.EXPIRED) {
            sb.append("已过期");
        } else {
            sb.append("未注册");
        }
        tvStatus.setText(sb.toString());
    }

    private void updateLimits() {
        StringBuilder sb = new StringBuilder();
        sb.append("试用天数: ");
        int trialDays = manager.getEffectiveTrialDays();
        if (trialDays == 0) {
            sb.append("无试用");
        } else {
            sb.append(trialDays).append(" 天");
        }

        sb.append("\n弹框时机: ");
        RegGateConfig.PromptTiming timing = manager.getEffectivePromptTiming();
        if (timing == RegGateConfig.PromptTiming.FIRST_LAUNCH) {
            sb.append("首次启动");
        } else if (timing == RegGateConfig.PromptTiming.ON_EXPIRY) {
            sb.append("到期后");
        } else {
            sb.append("每次启动");
        }

        sb.append("\n到期行为: ");
        RegGateConfig.ExpireBehavior behavior = manager.getEffectiveExpireBehavior();
        if (behavior == RegGateConfig.ExpireBehavior.BLOCK) {
            sb.append("限制功能(必须注册)");
        } else {
            sb.append("仅弹提示(可继续使用)");
        }

        long first = manager.getFirstLaunchMs();
        if (first > 0) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.getDefault());
            sb.append("\n首次启动: ").append(sdf.format(new java.util.Date(first)));
        }

        boolean tampered = manager.isTimeTampered();
        sb.append("\n时钟回拨: ").append(tampered ? "检测到异常" : "正常");
        tvLimits.setText(sb.toString());
    }
}
