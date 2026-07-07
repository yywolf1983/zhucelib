package com.reggate.lib;

import android.app.Activity;
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

public class RegistrationActivity extends Activity {

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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int layoutId = RegGateResources.getLayoutId(this, "reggate_activity_registration");
        if (layoutId == 0) {
            finish();
            return;
        }
        setContentView(layoutId);

        manager = new RegistrationManager(this);

        String appName = getIntent().getStringExtra(EXTRA_APP_NAME);
        boolean expired = getIntent().getBooleanExtra(EXTRA_EXPIRED, false);
        int trialRemaining = getIntent().getIntExtra(EXTRA_TRIAL_REMAINING_DAYS, 0);
        int licenseRemaining = getIntent().getIntExtra(EXTRA_LICENSE_REMAINING_DAYS, Integer.MIN_VALUE);

        TextView tvTitle = findViewById(RegGateResources.getId(this, "reggate_tv_title"));
        tvRequestCode = findViewById(RegGateResources.getId(this, "reggate_tv_request_code"));
        tvHint = findViewById(RegGateResources.getId(this, "reggate_tv_hint"));
        etActivationCode = findViewById(RegGateResources.getId(this, "reggate_et_activation_code"));
        btnActivate = findViewById(RegGateResources.getId(this, "reggate_btn_activate"));
        btnPaste = findViewById(RegGateResources.getId(this, "reggate_btn_paste"));
        Button btnCopyCode = findViewById(RegGateResources.getId(this, "reggate_btn_copy_code"));
        Button btnRegenerate = findViewById(RegGateResources.getId(this, "reggate_btn_regenerate"));

        tvTitle.setText(RegGateResources.getString(this, "reggate_register_title", appName == null ? "" : appName));

        showRequestCode();

        if (expired) {
            if (manager.getConfig().getTrialDays() > 0 && trialRemaining == 0
                    && (licenseRemaining == Integer.MIN_VALUE || licenseRemaining == 0)) {
                tvHint.setText(RegGateResources.getString(this, "reggate_hint_trial_expired"));
            } else if (licenseRemaining == 0) {
                tvHint.setText(RegGateResources.getString(this, "reggate_hint_license_expired"));
            } else {
                tvHint.setText(RegGateResources.getString(this, "reggate_hint_expired"));
            }
        } else if (trialRemaining > 0) {
            tvHint.setText(RegGateResources.getString(this, "reggate_hint_trial_remaining", trialRemaining));
        } else {
            tvHint.setText(RegGateResources.getString(this, "reggate_hint_need_activate"));
        }

        btnCopyCode.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("request_code",
                    Base32.ungroup(tvRequestCode.getText().toString())));
            Toast.makeText(this, RegGateResources.getString(this, "reggate_request_copied"), Toast.LENGTH_SHORT).show();
        });

        btnRegenerate.setOnClickListener(v -> {
            tvRequestCode.setText(Base32.group(manager.regenerateRequestCode(), 4));
            Toast.makeText(this, RegGateResources.getString(this, "reggate_request_regenerated"), Toast.LENGTH_SHORT).show();
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

        if (getActionBar() != null) getActionBar().hide();
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
            Toast.makeText(this, RegGateResources.getString(this, "reggate_activate_success"), Toast.LENGTH_SHORT).show();
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
            finishAffinity();
        }
    }
}
