package com.reggate.demo;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.reggate.lib.Base32;
import com.reggate.lib.RegGateConfig;
import com.reggate.lib.RegistrationManager;

/**
 * Demo 主界面：展示注册状态，支持输入激活码验证。
 * 使用流程：
 *   1. 首次启动会弹出试用/注册框（由注册库控制）
 *   2. 进入主界面后可查看安装码（用于在 keygen-app 中生成激活码）
 *   3. 输入激活码并点击"验证激活码"完成注册
 */
public class MainActivity extends AppCompatActivity {

    private RegistrationManager manager;
    private TextView tvInstallCode;
    private TextView tvStatus;
    private TextView tvLimits;
    private EditText etActivationCode;
    private Button btnVerify;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        manager = new RegistrationManager(this);

        tvInstallCode = findViewById(R.id.tv_install_code);
        tvStatus = findViewById(R.id.tv_status);
        tvLimits = findViewById(R.id.tv_limits);
        etActivationCode = findViewById(R.id.et_activation_code);
        btnVerify = findViewById(R.id.btn_verify);

        btnVerify.setOnClickListener(v -> doVerify());
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
        tvInstallCode.setText(Base32.group(manager.getCurrentRequestCode()));

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
            btnVerify.setEnabled(false);
            etActivationCode.setEnabled(false);
        } else if (state == RegistrationManager.State.TRIALING) {
            sb.append("试用中");
            sb.append("\n剩余天数: ").append(manager.getTrialRemainingDays());
            btnVerify.setEnabled(true);
            etActivationCode.setEnabled(true);
        } else if (state == RegistrationManager.State.EXPIRED) {
            sb.append("已过期");
            btnVerify.setEnabled(true);
            etActivationCode.setEnabled(true);
        } else {
            sb.append("未注册");
            btnVerify.setEnabled(true);
            etActivationCode.setEnabled(true);
        }
        tvStatus.setText(sb.toString());
    }

    private void doVerify() {
        String code = Base32.ungroup(etActivationCode.getText().toString());
        if (TextUtils.isEmpty(code)) {
            toast("请输入激活码");
            return;
        }

        RegistrationManager.VerifyResult result = manager.verifyActivationCode(code);
        if (result.success) {
            toast("激活成功!");
            updateStatus();
            updateLimits();
            etActivationCode.setText("");
        } else {
            toast("激活失败: " + result.message);
        }
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
        } else {
            sb.append("到期后");
        }

        sb.append("\n到期行为: ");
        RegGateConfig.ExpireBehavior behavior = manager.getEffectiveExpireBehavior();
        if (behavior == RegGateConfig.ExpireBehavior.BLOCK) {
            sb.append("限制功能(必须注册)");
        } else {
            sb.append("仅弹提示(可继续使用)");
        }
        tvLimits.setText(sb.toString());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
