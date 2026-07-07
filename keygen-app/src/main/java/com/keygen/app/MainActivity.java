package com.keygen.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
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
 * 注册机主界面(RSA-2048 + 隐写配置)。
 *
 * <b>核心特性:</b>
 *   - 私钥从本地文件选择(.pem/.der),不存储在注册机内
 *   - RSA-2048 + SHA256withRSA 签名
 *   - 设置隐写在激活码中(试用天数、弹框时机、到期行为),被签名保护
 *   - 输入安装码 + 购买天数 + 配置选项 → 生成激活码
 *   - 导出公钥(由私钥推导)
 *
 * <b>隐写配置(flags 字节):</b>
 *   - bit 0-1: trialDays 模式 (0=3天, 1=7天, 2=14天, 3=无试用)
 *   - bit 2: promptTiming (0=FIRST_LAUNCH, 1=ON_EXPIRY)
 *   - bit 3: expireBehavior (0=BLOCK, 1=NAG_ONLY)
 *
 * <b>使用流程:</b>
 *   1. 点击"选择私钥文件" → 从设备存储选择 .pem/.der 私钥文件
 *   2. 自动导出公钥 → 复制公钥粘贴到注册库 RegGateConfig.publicKey(...)
 *   3. 设置隐写配置(试用天数、弹框时机、到期行为)
 *   4. 输入客户机安装码 + 购买天数 → 生成激活码
 *   5. 复制激活码发回给客户机
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_OPEN_FILE = 0x1001;

    private PrivateKey privateKey;
    private TextView tvPubKey;
    private TextView tvPrivStatus;
    private EditText etRequestCode;
    private EditText etValidDays;
    private RadioGroup rgTrialDays;
    private RadioGroup rgPrompt;
    private RadioGroup rgExpire;
    private TextView tvActivationCode;
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
        rgTrialDays = findViewById(R.id.rg_trial_days);
        rgPrompt = findViewById(R.id.rg_prompt);
        rgExpire = findViewById(R.id.rg_expire);
        tvActivationCode = findViewById(R.id.tv_activation_code);
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
            int idx = code.indexOf('\n');
            if (idx > 0) code = code.substring(0, idx);
            copyToClipboard("activation_code", code);
            toast("激活码已复制");
        });

        updateUiState();
    }

    private void openPrivateKeyFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_OPEN_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
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

        int trialDaysMode = getTrialDaysMode();
        int promptTimingMode = getPromptTimingMode();
        boolean nagOnlyExpire = rgExpire.getCheckedRadioButtonId() == R.id.rb_expire_nag;

        try {
            String code = KeygenUtils.generateActivationCode(
                    requestCode, validDays, trialDaysMode, promptTimingMode, nagOnlyExpire, privateKey);

            String display = code + "\n\n"
                    + "购买时长: " + (validDays == 0 ? "永久" : validDays + " 天") + "\n"
                    + "到期: " + KeygenUtils.formatExpiry(validDays) + "\n"
                    + "试用天数: " + KeygenUtils.trialDaysLabel(trialDaysMode) + "\n"
                    + "弹框时机: " + getPromptTimingLabel(promptTimingMode) + "\n"
                    + "到期行为: " + (nagOnlyExpire ? "仅弹提示" : "限制功能");

            tvActivationCode.setText(display);
            btnCopyActivation.setEnabled(true);
        } catch (IllegalArgumentException e) {
            toast("安装码格式错误");
        } catch (Exception e) {
            toast("生成失败: " + e.getMessage());
        }
    }

    private int getTrialDaysMode() {
        int id = rgTrialDays.getCheckedRadioButtonId();
        if (id == R.id.rb_trial_3) return 0;
        if (id == R.id.rb_trial_7) return 1;
        if (id == R.id.rb_trial_14) return 2;
        return 3;
    }

    private int getPromptTimingMode() {
        int id = rgPrompt.getCheckedRadioButtonId();
        if (id == R.id.rb_prompt_first) return 0;
        if (id == R.id.rb_prompt_expiry) return 1;
        if (id == R.id.rb_prompt_every) return 2;
        return 0;
    }

    private String getPromptTimingLabel(int mode) {
        switch (mode) {
            case 0: return "首次启动";
            case 1: return "到期后";
            case 2: return "每次启动";
            default: return "首次启动";
        }
    }

    private void updateUiState() {
        boolean hasKey = privateKey != null;
        btnGenerate.setEnabled(hasKey);
        btnCopyPub.setEnabled(hasKey);
        tvPrivStatus.setText(hasKey ? "私钥已加载" : "未选择私钥文件");
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
