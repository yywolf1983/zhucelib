package com.reggate.lib;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

public class ExpiredNagActivity extends Activity {

    public static final String EXTRA_APP_NAME = "extra_app_name";
    public static final String EXTRA_TRIAL_EXPIRED = "extra_trial_expired";
    public static final String EXTRA_LICENSE_REMAINING = "extra_license_remaining";

    private static final int REQ_REGISTER = 0x3001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int layoutId = RegGateResources.getLayoutId(this, "reggate_activity_expired_nag");
        if (layoutId == 0) {
            finish();
            return;
        }
        setContentView(layoutId);

        String appName = getIntent().getStringExtra(EXTRA_APP_NAME);
        boolean trialExpired = getIntent().getBooleanExtra(EXTRA_TRIAL_EXPIRED, true);
        int licenseRemaining = getIntent().getIntExtra(EXTRA_LICENSE_REMAINING, Integer.MIN_VALUE);

        TextView tvTitle = findViewById(RegGateResources.getId(this, "reggate_tv_nag_title"));
        TextView tvMsg = findViewById(RegGateResources.getId(this, "reggate_tv_nag_msg"));

        if (tvTitle == null || tvMsg == null) {
            finish();
            return;
        }

        tvTitle.setText(RegGateResources.getString(this, "reggate_nag_title", appName == null ? "" : appName));

        String msg;
        if (trialExpired) {
            msg = RegGateResources.getString(this, "reggate_nag_trial_expired");
        } else if (licenseRemaining == 0) {
            msg = RegGateResources.getString(this, "reggate_nag_license_expired");
        } else {
            msg = RegGateResources.getString(this, "reggate_nag_default");
        }
        tvMsg.setText(msg);

        Button btnActivate = findViewById(RegGateResources.getId(this, "reggate_btn_nag_activate"));
        Button btnContinue = findViewById(RegGateResources.getId(this, "reggate_btn_nag_continue"));

        if (btnActivate != null) {
            btnActivate.setOnClickListener(v -> {
                RegistrationManager mgr = new RegistrationManager(this);
                Intent it = new Intent(this, RegistrationActivity.class);
                it.putExtra(RegistrationActivity.EXTRA_APP_NAME, appName);
                it.putExtra(RegistrationActivity.EXTRA_EXPIRED, true);
                it.putExtra(RegistrationActivity.EXTRA_TIME_TAMPERED, mgr.isTimeTampered());
                it.putExtra(RegistrationActivity.EXTRA_ANOMALY, mgr.isAnomaly());
                it.putExtra(RegistrationActivity.EXTRA_TRIAL_REMAINING_DAYS, 0);
                it.putExtra(RegistrationActivity.EXTRA_LICENSE_REMAINING_DAYS, licenseRemaining);
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
