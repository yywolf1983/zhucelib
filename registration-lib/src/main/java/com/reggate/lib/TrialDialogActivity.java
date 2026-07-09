package com.reggate.lib;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

public class TrialDialogActivity extends Activity {

    public static final String EXTRA_APP_NAME = "extra_app_name";
    public static final String EXTRA_TRIAL_DAYS = "extra_trial_days";
    public static final String EXTRA_REMAINING_DAYS = "extra_remaining_days";

    private static final int REQ_REGISTER = 0x2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int layoutId = RegGateResources.getLayoutId(this, "reggate_activity_trial_dialog");
        if (layoutId == 0) {
            finish();
            return;
        }
        setContentView(layoutId);

        String appName = getIntent().getStringExtra(EXTRA_APP_NAME);
        int trialDays = getIntent().getIntExtra(EXTRA_TRIAL_DAYS, 0);
        int remainingDays = getIntent().getIntExtra(EXTRA_REMAINING_DAYS, trialDays);

        TextView tvTitle = findViewById(RegGateResources.getId(this, "reggate_tv_trial_title"));
        TextView tvMsg = findViewById(RegGateResources.getId(this, "reggate_tv_trial_msg"));
        Button btnRegister = findViewById(RegGateResources.getId(this, "reggate_btn_trial_register"));
        Button btnContinue = findViewById(RegGateResources.getId(this, "reggate_btn_trial_continue"));

        if (tvTitle == null || tvMsg == null) {
            finish();
            return;
        }

        tvTitle.setText(RegGateResources.getString(this, "reggate_trial_title", appName == null ? "" : appName));
        tvMsg.setText(RegGateResources.getString(this, "reggate_trial_msg", trialDays, remainingDays));

        if (btnRegister != null) {
            btnRegister.setOnClickListener(v -> {
                Intent it = new Intent(this, RegistrationActivity.class);
                it.putExtra(RegistrationActivity.EXTRA_APP_NAME, appName);
                it.putExtra(RegistrationActivity.EXTRA_EXPIRED, false);
                it.putExtra(RegistrationActivity.EXTRA_TRIAL_REMAINING_DAYS, remainingDays);
                it.putExtra(RegistrationActivity.EXTRA_LICENSE_REMAINING_DAYS, Integer.MIN_VALUE);
                startActivityForResult(it, REQ_REGISTER);
            });
        }

        if (btnContinue != null) {
            btnContinue.setOnClickListener(v -> {
                setResult(RESULT_CANCELED);
                finish();
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_REGISTER && resultCode == RESULT_OK) {
            setResult(RESULT_OK);
            finish();
        }
    }
}
