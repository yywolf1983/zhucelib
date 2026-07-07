package com.reggate.lib;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 首次启动试用提示框(promptTiming=FIRST_LAUNCH 时,首次启动延迟弹出一次)。
 */
public class TrialDialogActivity extends AppCompatActivity {

    public static final String EXTRA_APP_NAME = "extra_app_name";
    public static final String EXTRA_TRIAL_DAYS = "extra_trial_days";
    public static final String EXTRA_REMAINING_DAYS = "extra_remaining_days";

    private static final int REQ_REGISTER = 0x2001;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.reggate_activity_trial_dialog);

        String appName = getIntent().getStringExtra(EXTRA_APP_NAME);
        int trialDays = getIntent().getIntExtra(EXTRA_TRIAL_DAYS, 0);
        int remainingDays = getIntent().getIntExtra(EXTRA_REMAINING_DAYS, trialDays);

        TextView tvTitle = findViewById(R.id.reggate_tv_trial_title);
        TextView tvMsg = findViewById(R.id.reggate_tv_trial_msg);
        Button btnRegister = findViewById(R.id.reggate_btn_trial_register);
        Button btnContinue = findViewById(R.id.reggate_btn_trial_continue);

        tvTitle.setText(getString(R.string.reggate_trial_title, appName == null ? "" : appName));
        tvMsg.setText(getString(R.string.reggate_trial_msg, trialDays, remainingDays));

        btnRegister.setOnClickListener(v -> {
            Intent it = new Intent(this, RegistrationActivity.class);
            it.putExtra(RegistrationActivity.EXTRA_APP_NAME, appName);
            it.putExtra(RegistrationActivity.EXTRA_EXPIRED, false);
            it.putExtra(RegistrationActivity.EXTRA_TRIAL_REMAINING_DAYS, remainingDays);
            it.putExtra(RegistrationActivity.EXTRA_LICENSE_REMAINING_DAYS, Integer.MIN_VALUE);
            startActivityForResult(it, REQ_REGISTER);
        });

        btnContinue.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_REGISTER && resultCode == RESULT_OK) {
            setResult(RESULT_OK);
            finish();
        }
    }
}
