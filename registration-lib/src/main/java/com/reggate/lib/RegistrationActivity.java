package com.reggate.lib;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    private Button btnRegenerate;

    private LinearLayout contactContainer;
    private TextView tvContactPhone;
    private TextView tvContactEmail;
    private TextView tvContactWebsite;
    private TextView tvContactShop;
    private TextView tvContactCustom;
    private TextView tvContactPhoneCopy;
    private TextView tvContactEmailCopy;
    private TextView tvContactWebsiteCopy;
    private TextView tvContactShopCopy;
    private ImageView ivQrCode;

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
        btnRegenerate = findViewById(RegGateResources.getId(this, "reggate_btn_regenerate"));

        contactContainer = findViewById(RegGateResources.getId(this, "reggate_contact_container"));
        tvContactPhone = findViewById(RegGateResources.getId(this, "reggate_tv_contact_phone"));
        tvContactEmail = findViewById(RegGateResources.getId(this, "reggate_tv_contact_email"));
        tvContactWebsite = findViewById(RegGateResources.getId(this, "reggate_tv_contact_website"));
        tvContactShop = findViewById(RegGateResources.getId(this, "reggate_tv_contact_shop"));
        tvContactCustom = findViewById(RegGateResources.getId(this, "reggate_tv_contact_custom"));
        tvContactPhoneCopy = findViewById(RegGateResources.getId(this, "reggate_tv_contact_phone_copy"));
        tvContactEmailCopy = findViewById(RegGateResources.getId(this, "reggate_tv_contact_email_copy"));
        tvContactWebsiteCopy = findViewById(RegGateResources.getId(this, "reggate_tv_contact_website_copy"));
        tvContactShopCopy = findViewById(RegGateResources.getId(this, "reggate_tv_contact_shop_copy"));
        ivQrCode = findViewById(RegGateResources.getId(this, "reggate_iv_qr_code"));

        tvTitle.setText(RegGateResources.getString(this, "reggate_register_title", appName == null ? "" : appName));

        showRequestCode();
        setupContactInfo();

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

    private void setupContactInfo() {
        ContactInfo configInfo = manager.getConfig().getContactInfo();

        String phone = getStringValue(configInfo != null ? configInfo.getPhone() : null, "reggate_default_contact_phone");
        String email = getStringValue(configInfo != null ? configInfo.getEmail() : null, "reggate_default_contact_email");
        String website = getStringValue(configInfo != null ? configInfo.getWebsite() : null, "reggate_default_contact_website");
        String shopUrl = configInfo != null ? configInfo.getShopUrl() : null;
        String customText = configInfo != null ? configInfo.getCustomText() : null;
        int qrCodeResId = configInfo != null ? configInfo.getQrCodeResId() : 0;
        if (qrCodeResId == 0) {
            qrCodeResId = RegGateResources.getDrawableId(this, "reggate_qr_code");
        }

        boolean hasContact = !TextUtils.isEmpty(phone) || !TextUtils.isEmpty(email) || 
                             !TextUtils.isEmpty(website) || !TextUtils.isEmpty(shopUrl) ||
                             !TextUtils.isEmpty(customText) || qrCodeResId != 0;

        if (hasContact) {
            contactContainer.setVisibility(View.VISIBLE);
        } else {
            contactContainer.setVisibility(View.GONE);
            return;
        }

        View phoneLayout = findViewById(RegGateResources.getId(this, "reggate_contact_phone_layout"));
        View emailLayout = findViewById(RegGateResources.getId(this, "reggate_contact_email_layout"));
        View websiteLayout = findViewById(RegGateResources.getId(this, "reggate_contact_website_layout"));
        View shopLayout = findViewById(RegGateResources.getId(this, "reggate_contact_shop_layout"));

        if (!TextUtils.isEmpty(phone)) {
            phoneLayout.setVisibility(View.VISIBLE);
            tvContactPhone.setText(RegGateResources.getString(this, "reggate_contact_phone_label", phone));
            tvContactPhone.setOnClickListener(v -> dialPhone(phone));
            tvContactPhoneCopy.setOnClickListener(v -> copyToClipboard(phone));
        } else {
            phoneLayout.setVisibility(View.GONE);
        }

        if (!TextUtils.isEmpty(email)) {
            emailLayout.setVisibility(View.VISIBLE);
            tvContactEmail.setText(RegGateResources.getString(this, "reggate_contact_email_label", email));
            tvContactEmail.setOnClickListener(v -> sendEmail(email));
            tvContactEmailCopy.setOnClickListener(v -> copyToClipboard(email));
        } else {
            emailLayout.setVisibility(View.GONE);
        }

        if (!TextUtils.isEmpty(website)) {
            websiteLayout.setVisibility(View.VISIBLE);
            tvContactWebsite.setText(RegGateResources.getString(this, "reggate_contact_website_label", website));
            tvContactWebsite.setOnClickListener(v -> openWebsite(website));
            tvContactWebsiteCopy.setOnClickListener(v -> copyToClipboard(website));
        } else {
            websiteLayout.setVisibility(View.GONE);
        }

        if (!TextUtils.isEmpty(shopUrl)) {
            shopLayout.setVisibility(View.VISIBLE);
            tvContactShop.setText(RegGateResources.getString(this, "reggate_contact_shop_label", shopUrl));
            tvContactShop.setOnClickListener(v -> openWebsite(shopUrl));
            tvContactShopCopy.setOnClickListener(v -> copyToClipboard(shopUrl));
        } else {
            shopLayout.setVisibility(View.GONE);
        }

        if (!TextUtils.isEmpty(customText)) {
            tvContactCustom.setText(customText);
            tvContactCustom.setVisibility(View.VISIBLE);
        } else {
            tvContactCustom.setVisibility(View.GONE);
        }

        if (qrCodeResId != 0) {
            ivQrCode.setVisibility(View.VISIBLE);
            ivQrCode.setImageResource(qrCodeResId);
        } else {
            ivQrCode.setVisibility(View.GONE);
        }
    }

    private String getStringValue(String configValue, String defaultResName) {
        if (!TextUtils.isEmpty(configValue)) {
            return configValue;
        }
        int resId = RegGateResources.getStringId(this, defaultResName);
        if (resId != 0) {
            return getString(resId);
        }
        return null;
    }

    private void dialPhone(String phone) {
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone));
        startActivity(intent);
    }

    private void sendEmail(String email) {
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + email));
        startActivity(intent);
    }

    private void openWebsite(String website) {
        if (!website.startsWith("http://") && !website.startsWith("https://")) {
            website = "https://" + website;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(website));
        startActivity(intent);
    }

    private void copyToClipboard(String text) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("contact", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, RegGateResources.getString(this, "reggate_copy_success"), Toast.LENGTH_SHORT).show();
    }
}
