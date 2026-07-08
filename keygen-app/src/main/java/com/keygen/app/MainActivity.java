package com.keygen.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.PrivateKey;

/**
 * 注册机主界面(RSA-2048)。
 *
 * <b>核心特性:</b>
 *   - 私钥从本地文件选择(.pem/.der),路径保存在 SharedPreferences
 *   - RSA-2048 + SHA256withRSA 签名
 *   - 输入安装码 + 购买天数 → 生成激活码
 *   - 导出公钥(由私钥推导)
 *
 * <b>试用配置:</b>写死在注册库中,不在注册码中传递。
 *
 * <b>使用流程:</b>
 *   1. 点击"选择私钥文件" → 从设备存储选择 .pem/.der 私钥文件(选择一次后记住)
 *   2. 自动导出公钥 → 复制公钥粘贴到注册库 RegGateConfig
 *   3. 输入客户机安装码 + 购买天数 → 生成激活码
 *   4. 复制激活码发回给客户机
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_OPEN_FILE = 0x1001;
    private static final String PREFS_NAME = "keygen_prefs";
    private static final String PREF_LAST_KEY_URI = "last_key_uri";

    private PrivateKey privateKey;
    private TextView tvPubKey;
    private TextView tvPrivStatus;
    private EditText etRequestCode;
    private EditText etValidDays;
    private TextView tvActivationCode;
    private TextView tvValidDaysResult;
    private TextView tvExpiryResult;
    private Button btnGenerate;
    private Button btnCopyActivation;
    private Button btnCopyPub;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvPubKey = findViewById(R.id.tv_pub_key);
        tvPrivStatus = findViewById(R.id.tv_priv_status);
        etRequestCode = findViewById(R.id.et_request_code);
        etValidDays = findViewById(R.id.et_valid_days);
        etValidDays.setText("365");
        tvActivationCode = findViewById(R.id.tv_activation_code);
        tvValidDaysResult = findViewById(R.id.tv_valid_days_result);
        tvExpiryResult = findViewById(R.id.tv_expiry_result);
        btnGenerate = findViewById(R.id.btn_generate);
        btnCopyActivation = findViewById(R.id.btn_copy_activation);

        Button btnSelectPriv = findViewById(R.id.btn_select_priv);
        btnCopyPub = findViewById(R.id.btn_copy_pub);
        Button btnPasteRequest = findViewById(R.id.btn_paste_request);

        btnSelectPriv.setOnClickListener(v -> openPrivateKeyFile());

        btnCopyPub.setOnClickListener(v -> {
            if (privateKey == null) {
                toast("请先选择私钥文件");
                return;
            }
            copyToClipboard("public_key", tvPubKey.getText().toString());
        });

        btnPasteRequest.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (text != null) etRequestCode.setText(text);
            }
        });

        btnGenerate.setOnClickListener(v -> doGenerate());

        btnCopyActivation.setOnClickListener(v -> {
            String code = Base32.ungroup(tvActivationCode.getText().toString());
            if (TextUtils.isEmpty(code)) return;
            copyToClipboard("activation_code", code);
            toast("激活码已复制");
        });

        String lastUri = getPreferences().getString(PREF_LAST_KEY_URI, null);
        if (lastUri != null) {
            loadPrivateKeyFromUri(Uri.parse(lastUri));
        }

        updateUiState();
    }

    private SharedPreferences getPreferences() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void saveLastKeyUri(Uri uri) {
        getPreferences().edit().putString(PREF_LAST_KEY_URI, uri.toString()).apply();
    }

    private void openPrivateKeyFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_OPEN_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
                saveLastKeyUri(uri);
                loadPrivateKeyFromUri(uri);
            }
        }
    }

    private void loadPrivateKeyFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String keyContent = sb.toString();

            privateKey = KeygenUtils.parsePrivateKey(keyContent);

            java.security.interfaces.RSAPrivateCrtKey rsaPriv = (java.security.interfaces.RSAPrivateCrtKey) privateKey;
            java.security.spec.RSAPublicKeySpec pubSpec = new java.security.spec.RSAPublicKeySpec(
                    rsaPriv.getModulus(), rsaPriv.getPublicExponent());
            java.security.PublicKey pub = java.security.KeyFactory.getInstance("RSA").generatePublic(pubSpec);
            String pubB64 = android.util.Base64.encodeToString(pub.getEncoded(), android.util.Base64.NO_WRAP);

            tvPubKey.setText(pubB64);
            tvPrivStatus.setText("私钥已加载: " + uri.getLastPathSegment());
            tvActivationCode.setText("");
            tvValidDaysResult.setText("");
            tvExpiryResult.setText("");
            btnCopyActivation.setEnabled(false);
            toast("私钥加载成功");

        } catch (IOException e) {
            toast("读取文件失败: " + e.getMessage());
        } catch (Exception e) {
            toast("私钥解析失败: " + e.getMessage());
        }
        updateUiState();
    }

    private void doGenerate() {
        if (privateKey == null) {
            toast("请先选择私钥文件");
            return;
        }

        String requestCode = Base32.ungroup(etRequestCode.getText().toString());
        if (TextUtils.isEmpty(requestCode)) {
            toast("请输入客户机安装码");
            etRequestCode.requestFocus();
            return;
        }

        int validDays;
        try {
            String d = etValidDays.getText().toString().trim();
            validDays = TextUtils.isEmpty(d) ? 0 : Integer.parseInt(d);
            if (validDays < 0 || validDays > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            toast("购买天数为 0~65535 的整数(0=永久)");
            return;
        }

        try {
            String code = KeygenUtils.generateActivationCode(requestCode, validDays, privateKey);

            tvActivationCode.setText(code);
            tvValidDaysResult.setText("购买时长: " + (validDays == 0 ? "永久" : validDays + " 天"));
            tvExpiryResult.setText("到期: " + KeygenUtils.formatExpiry(validDays));
            btnCopyActivation.setEnabled(true);
        } catch (IllegalArgumentException e) {
            toast("安装码格式错误");
        } catch (Exception e) {
            toast("生成失败: " + e.getMessage());
        }
    }

    private void updateUiState() {
        boolean hasKey = privateKey != null;
        btnGenerate.setEnabled(hasKey);
        btnCopyPub.setEnabled(hasKey);
        if (!hasKey) {
            tvPrivStatus.setText("未选择私钥文件");
        }
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        toast("已复制");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
