package com.reggate.lib;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
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

import androidx.core.content.FileProvider;

import com.google.zxing.NotFoundException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RegistrationActivity extends Activity {

    private static final int REQ_PICK_QR_IMAGE = 0x2001;
    private static final int REQ_PERM_SAVE_QR = 0x2002;
    private static final int REQ_SCAN_QR = 0x2003;

    public static final String EXTRA_APP_NAME = "extra_app_name";
    public static final String EXTRA_EXPIRED = "extra_expired";
    public static final String EXTRA_TRIAL_REMAINING_DAYS = "extra_trial_remaining_days";
    public static final String EXTRA_LICENSE_REMAINING_DAYS = "extra_license_remaining_days";
    public static final String EXTRA_TIME_TAMPERED = "extra_time_tampered";
    public static final String EXTRA_ANOMALY = "extra_anomaly";

    private RegistrationManager manager;

    private boolean isAnomaly;
    private String currentRequestCode;
    private TextView tvRequestCode;
    private TextView tvHint;
    private EditText etActivationCode;
    private TextView btnActivate;
    private TextView btnPaste;
    private View btnScanQr;
    private View btnPickQr;

    /** 保存二维码的权限待处理位图(API 28 及以下)。 */
    private Bitmap pendingSaveBitmap;
    private String pendingSaveBaseName;

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
    private Button btnSaveQr;

    private int currentQrCodeResId = 0;

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
        boolean timeTampered = getIntent().getBooleanExtra(EXTRA_TIME_TAMPERED, false);
        isAnomaly = getIntent().getBooleanExtra(EXTRA_ANOMALY, false);
        int trialRemaining = getIntent().getIntExtra(EXTRA_TRIAL_REMAINING_DAYS, 0);
        int licenseRemaining = getIntent().getIntExtra(EXTRA_LICENSE_REMAINING_DAYS, Integer.MIN_VALUE);

        TextView tvTitle = findViewById(RegGateResources.getId(this, "reggate_tv_title"));
        tvRequestCode = findViewById(RegGateResources.getId(this, "reggate_tv_request_code"));
        tvHint = findViewById(RegGateResources.getId(this, "reggate_tv_hint"));
        etActivationCode = findViewById(RegGateResources.getId(this, "reggate_et_activation_code"));
        btnActivate = findViewById(RegGateResources.getId(this, "reggate_btn_activate"));
        btnPaste = findViewById(RegGateResources.getId(this, "reggate_btn_paste"));
        View btnCopyCode = findViewById(RegGateResources.getId(this, "reggate_btn_copy_code"));
        View btnRequestQr = findViewById(RegGateResources.getId(this, "reggate_btn_request_qr"));

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
        btnSaveQr = findViewById(RegGateResources.getId(this, "reggate_btn_save_qr"));

        tvTitle.setText(RegGateResources.getString(this, "reggate_register_title", appName == null ? "" : appName));

        showRequestCode();
        setupContactInfo();

        if (isAnomaly) {
            tvHint.setText(RegGateResources.getString(this, "reggate_hint_registration_anomaly"));
            tvHint.setTextColor(0xFFE53935);
        } else if (timeTampered) {
            tvHint.setText(RegGateResources.getString(this, "reggate_hint_time_tampered"));
            tvHint.setTextColor(0xFFE53935);
        } else if (expired) {
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

        if (btnRequestQr != null) {
            btnRequestQr.setOnClickListener(v -> showRequestQrDialog());
        }

        btnPaste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (text != null) etActivationCode.setText(text);
            }
        });

        btnActivate.setOnClickListener(v -> doActivate());

        btnScanQr = findViewById(RegGateResources.getId(this, "reggate_btn_scan_qr"));
        btnPickQr = findViewById(RegGateResources.getId(this, "reggate_btn_pick_qr"));
        btnScanQr.setOnClickListener(v -> startQrScan());
        btnPickQr.setOnClickListener(v -> pickQrImage());

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
        currentRequestCode = manager.getCurrentRequestCode();
        tvRequestCode.setText(Base32.group(currentRequestCode, 4));
    }

    private void showRequestQrDialog() {
        if (TextUtils.isEmpty(currentRequestCode)) return;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        QrCodeDialog.show(this, currentRequestCode,
                Base32.group(currentRequestCode, 4),
                "安装码二维码_" + stamp);
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
        // 异常状态下不允许返回主界面（否则守卫会再次拦截，造成死循环）
        if (isAnomaly || !manager.canEnterMain()) {
            finishAffinity();
        } else {
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    // ==================== 二维码(相机扫描 / 相册识别) ====================

    private void startQrScan() {
        Intent intent = new Intent(this, PortraitCaptureActivity.class);
        startActivityForResult(intent, REQ_SCAN_QR);
    }

    private void pickQrImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQ_PICK_QR_IMAGE);
    }

    /** 扫码/识别成功: 回填激活码(按 5 字符分组显示)并立即执行激活。 */
    private void applyScannedCode(String raw) {
        if (TextUtils.isEmpty(raw)) return;
        String code = Base32.ungroup(raw);
        if (TextUtils.isEmpty(code)) return;
        etActivationCode.setText(Base32.group(code, 5));
        btnActivate.setEnabled(true);
        doActivate();
    }

    private void decodeQrImage(final Uri uri) {
        new Thread(() -> {
            try {
                final String text = QrImageDecoder.decode(getApplicationContext(), uri);
                runOnUiThread(() -> {
                    if (!isFinishing()) applyScannedCode(text);
                });
            } catch (NotFoundException nf) {
                runOnUiThread(() -> Toast.makeText(this,
                        RegGateResources.getString(this, "reggate_qr_not_found"),
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        RegGateResources.getString(this, "reggate_qr_load_failed"),
                        Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SCAN_QR) {
            // 自研竖屏扫码页返回; 用户取消时无数据, 静默忽略
            if (resultCode == RESULT_OK && data != null) {
                String contents = data.getStringExtra(PortraitCaptureActivity.EXTRA_SCAN_RESULT);
                if (!TextUtils.isEmpty(contents)) {
                    applyScannedCode(contents);
                }
            }
            return;
        }
        if (requestCode == REQ_PICK_QR_IMAGE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            decodeQrImage(data.getData());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void setupContactInfo() {
        ContactInfo configInfo = manager.getConfig().getContactInfo();

        String phone = configInfo != null ? configInfo.getPhone() : null;
        String email = configInfo != null ? configInfo.getEmail() : null;
        String website = configInfo != null ? configInfo.getWebsite() : null;
        String shopUrl = configInfo != null ? configInfo.getShopUrl() : null;
        String customText = configInfo != null ? configInfo.getCustomText() : null;
        int qrCodeResId = configInfo != null ? configInfo.getQrCodeResId() : 0;

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
            currentQrCodeResId = qrCodeResId;
            ivQrCode.setVisibility(View.VISIBLE);
            ivQrCode.setImageResource(qrCodeResId);
            btnSaveQr.setVisibility(View.VISIBLE);
            btnSaveQr.setOnClickListener(v -> saveQrCodeToGallery());
        } else {
            ivQrCode.setVisibility(View.GONE);
            btnSaveQr.setVisibility(View.GONE);
        }
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

    private void saveQrCodeToGallery() {
        if (currentQrCodeResId == 0) return;

        Drawable drawable = ivQrCode.getDrawable();
        if (!(drawable instanceof BitmapDrawable)) {
            Toast.makeText(this, RegGateResources.getString(this, "reggate_save_qr_failed"), Toast.LENGTH_SHORT).show();
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        saveQrBitmap(((BitmapDrawable) drawable).getBitmap(), "reggate_contact_qr_" + stamp);
    }

    // ==================== 二维码保存 / 发送(安装码弹窗与联系方式二维码共用) ====================

    /** 供 {@link QrCodeDialog} 调用: 保存 PNG 到相册(API 29+ 零权限)。 */
    public void saveQrBitmap(Bitmap bitmap, String fileBaseName) {
        if (bitmap == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveQrViaMediaStore(bitmap, fileBaseName + ".png");
        } else {
            if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    == PackageManager.PERMISSION_GRANTED) {
                saveQrLegacy(bitmap, fileBaseName + ".png");
            } else {
                pendingSaveBitmap = bitmap;
                pendingSaveBaseName = fileBaseName;
                requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"},
                        REQ_PERM_SAVE_QR);
            }
        }
    }

    /** 供 {@link QrCodeDialog} 调用: 经 FileProvider 分享 PNG(微信/QQ 等)。 */
    public void shareQrBitmap(Bitmap bitmap, String fileBaseName, String chooserTitle) {
        if (bitmap == null) return;
        try {
            File dir = new File(getCacheDir(), "qr");
            if (!dir.exists()) dir.mkdirs();
            // 清理历史临时文件, 避免缓存堆积
            File[] old = dir.listFiles();
            if (old != null) {
                for (File f : old) f.delete();
            }
            File file = new File(dir, fileBaseName + ".png");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    throw new Exception("compress failed");
                }
            }
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".reggate.fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, chooserTitle));
        } catch (Exception e) {
            Toast.makeText(this, RegGateResources.getString(this, "reggate_qr_share_failed"),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void saveQrViaMediaStore(Bitmap bitmap, String fileName) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);

            ContentResolver resolver = getContentResolver();
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Toast.makeText(this, RegGateResources.getString(this, "reggate_save_qr_failed"), Toast.LENGTH_SHORT).show();
                return;
            }
            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os != null && bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                    Toast.makeText(this, RegGateResources.getString(this, "reggate_save_qr_success"), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, RegGateResources.getString(this, "reggate_save_qr_failed"), Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, RegGateResources.getString(this, "reggate_save_qr_failed"), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveQrLegacy(Bitmap bitmap, String fileName) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)));
                    Toast.makeText(this, RegGateResources.getString(this, "reggate_save_qr_success"), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, RegGateResources.getString(this, "reggate_save_qr_failed"), Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, RegGateResources.getString(this, "reggate_save_qr_failed"), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_SAVE_QR) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && pendingSaveBitmap != null) {
                saveQrLegacy(pendingSaveBitmap, pendingSaveBaseName + ".png");
            } else {
                Toast.makeText(this, RegGateResources.getString(this, "reggate_save_qr_failed"), Toast.LENGTH_SHORT).show();
            }
            pendingSaveBitmap = null;
            pendingSaveBaseName = null;
        }
    }
}
