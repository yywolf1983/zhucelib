package com.reggate.lib;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 激活界面:展示本机安装码 + 输入激活码。
 *
 * 流程:
 *   1. 显示安装码(每次随机,客户机生成)-> 复制发给注册机操作员
 *   2. 操作员在注册机输入安装码 + 购买天数 -> 生成激活码
 *   3. 用户粘贴激活码 -> "激活" 校验(Ed25519 验签 + 设备绑定 + 随机挑战绑定)
 */
public class RegistrationActivity extends AppCompatActivity {

    public static final String EXTRA_APP_NAME = "extra_app_name";
    public static final String EXTRA_EXPIRED = "extra_expired";
    public static final String EXTRA_TRIAL_REMAINING_DAYS = "extra_trial_remaining_days";
    public static final String EXTRA_LICENSE_REMAINING_DAYS = "extra_license_remaining_days";

    private RegistrationManager manager;

    private TextView tvRequestCode;
    private TextView tvHint;
    private EditText etActivationCode;
    private Button btnActivate;
    private Button btnPaste;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.reggate_activity_registration);

        manager = new RegistrationManager(this);

        String appName = getIntent().getStringExtra(EXTRA_APP_NAME);
        boolean expired = getIntent().getBooleanExtra(EXTRA_EXPIRED, false);
        int trialRemaining = getIntent().getIntExtra(EXTRA_TRIAL_REMAINING_DAYS, 0);
        int licenseRemaining = getIntent().getIntExtra(EXTRA_LICENSE_REMAINING_DAYS, Integer.MIN_VALUE);

        TextView tvTitle = findViewById(R.id.reggate_tv_title);
        tvRequestCode = findViewById(R.id.reggate_tv_request_code);
        tvHint = findViewById(R.id.reggate_tv_hint);
        etActivationCode = findViewById(R.id.reggate_et_activation_code);
        btnActivate = findViewById(R.id.reggate_btn_activate);
        btnPaste = findViewById(R.id.reggate_btn_paste);
        Button btnCopyCode = findViewById(R.id.reggate_btn_copy_code);
        Button btnRegenerate = findViewById(R.id.reggate_btn_regenerate);

        tvTitle.setText(getString(R.string.reggate_register_title, appName == null ? "" : appName));

        // 安装码
        showRequestCode();

        // 状态提示
        if (expired) {
            if (manager.getConfig().getTrialDays() > 0 && trialRemaining == 0
                    && (licenseRemaining == Integer.MIN_VALUE || licenseRemaining == 0)) {
                tvHint.setText(R.string.reggate_hint_trial_expired);
            } else if (licenseRemaining == 0) {
                tvHint.setText(R.string.reggate_hint_license_expired);
            } else {
                tvHint.setText(R.string.reggate_hint_expired);
            }
        } else if (trialRemaining > 0) {
            tvHint.setText(getString(R.string.reggate_hint_trial_remaining, trialRemaining));
        } else {
            tvHint.setText(R.string.reggate_hint_need_activate);
        }

        btnCopyCode.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("request_code",
                    Base32.ungroup(tvRequestCode.getText().toString())));
            Toast.makeText(this, R.string.reggate_request_copied, Toast.LENGTH_SHORT).show();
        });

        btnRegenerate.setOnClickListener(v -> {
            tvRequestCode.setText(Base32.group(manager.regenerateRequestCode(), 4));
            Toast.makeText(this, R.string.reggate_request_regenerated, Toast.LENGTH_SHORT).show();
        });

        btnPaste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (text != null) etActivationCode.setText(text);
            }
        });

        btnActivate.setOnClickListener(v -> doActivate());

        etActivationCode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                btnActivate.setEnabled(!TextUtils.isEmpty(Base32.ungroup(s.toString())));
            }
        });

        if (getSupportActionBar() != null) getSupportActionBar().hide();
    }

    private void showRequestCode() {
        String code = manager.getCurrentRequestCode();
        tvRequestCode.setText(Base32.group(code, 4));
    }

    private void doActivate() {
        String raw = etActivationCode.getText().toString();
        String code = Base32.ungroup(raw);
        if (TextUtils.isEmpty(code)) return;

        btnActivate.setEnabled(false);
        RegistrationManager.VerifyResult result = manager.verifyActivationCode(code);

        if (result.success) {
            Toast.makeText(this, R.string.reggate_activate_success, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } else {
            btnActivate.setEnabled(true);
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
            etActivationCode.requestFocus();
        }
    }

    @Override
    public void onBackPressed() {
        if (manager.canEnterMain()) {
            setResult(RESULT_CANCELED);
            finish();
        } else {
            // 未激活且不可进入:退出整个 App
            finishAffinity();
        }
    }
}
