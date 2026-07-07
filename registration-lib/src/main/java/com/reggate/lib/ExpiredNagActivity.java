package com.reggate.lib;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 到期提示框(NAG_ONLY 模式下每次启动弹出)。
 *
 * - "立即激活" -> 打开 {@link RegistrationActivity}
 * - "继续使用" -> 关闭,回到主界面(NAG_ONLY 允许继续)
 */
public class ExpiredNagActivity extends AppCompatActivity {

    public static final String EXTRA_APP_NAME = "extra_app_name";
    public static final String EXTRA_TRIAL_EXPIRED = "extra_trial_expired";
    public static final String EXTRA_LICENSE_REMAINING = "extra_license_remaining";

    private static final int REQ_REGISTER = 0x3001;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.reggate_activity_expired_nag);

        String appName = getIntent().getStringExtra(EXTRA_APP_NAME);
        boolean trialExpired = getIntent().getBooleanExtra(EXTRA_TRIAL_EXPIRED, true);
        int licenseRemaining = getIntent().getIntExtra(EXTRA_LICENSE_REMAINING, Integer.MIN_VALUE);

        TextView tvTitle = findViewById(R.id.reggate_tv_nag_title);
        TextView tvMsg = findViewById(R.id.reggate_tv_nag_msg);

        tvTitle.setText(getString(R.string.reggate_nag_title, appName == null ? "" : appName));

        String msg;
        if (trialExpired) {
            msg = getString(R.string.reggate_nag_trial_expired);
        } else if (licenseRemaining == 0) {
            msg = getString(R.string.reggate_nag_license_expired);
        } else {
            msg = getString(R.string.reggate_nag_default);
        }
        tvMsg.setText(msg);

        Button btnActivate = findViewById(R.id.reggate_btn_nag_activate);
        Button btnContinue = findViewById(R.id.reggate_btn_nag_continue);

        btnActivate.setOnClickListener(v -> {
            Intent it = new Intent(this, RegistrationActivity.class);
            it.putExtra(RegistrationActivity.EXTRA_APP_NAME, appName);
            it.putExtra(RegistrationActivity.EXTRA_EXPIRED, true);
            it.putExtra(RegistrationActivity.EXTRA_TRIAL_REMAINING_DAYS, 0);
            it.putExtra(RegistrationActivity.EXTRA_LICENSE_REMAINING_DAYS, licenseRemaining);
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
