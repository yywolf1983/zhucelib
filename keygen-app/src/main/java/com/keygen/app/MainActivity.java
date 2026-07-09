package com.keygen.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_OPEN_FILE = 0x1001;
    private static final int REQ_EXPORT_RECORDS = 0x1002;
    private static final int REQ_PICK_STORAGE_DIR = 0x1003;
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
    private Button btnViewRecords;
    private Button btnExportRecords;
    private Button btnStorageSettings;
    private TextView tvRecordCount;
    private TextView tvStoragePath;
    private LinearLayout llDeviceOverview;
    private TextView tvDeviceOverviewSummary;

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
                if (text != null) {
                    etRequestCode.setText(text);
                    refreshDeviceOverview();
                }
            }
        });

        btnGenerate.setOnClickListener(v -> doGenerate());

        btnCopyActivation.setOnClickListener(v -> {
            String code = Base32.ungroup(tvActivationCode.getText().toString());
            if (TextUtils.isEmpty(code)) return;
            copyToClipboard("activation_code", code);
            toast("激活码已复制");
        });

        // 存储路径设置
        tvStoragePath = findViewById(R.id.tv_storage_path);
        btnStorageSettings = findViewById(R.id.btn_storage_settings);
        btnStorageSettings.setOnClickListener(v -> showStorageSettingsDialog());
        refreshStoragePath();

        // 注册记录区域
        tvRecordCount = findViewById(R.id.tv_record_count);
        btnViewRecords = findViewById(R.id.btn_view_records);
        btnExportRecords = findViewById(R.id.btn_export_records);
        llDeviceOverview = findViewById(R.id.ll_device_overview);
        tvDeviceOverviewSummary = findViewById(R.id.tv_device_overview_summary);

        btnViewRecords.setOnClickListener(v -> {
            startActivity(new Intent(this, RecordsActivity.class));
        });
        btnExportRecords.setOnClickListener(v -> exportRecords());
        refreshRecordCount();

        String lastUri = getPreferences().getString(PREF_LAST_KEY_URI, null);
        if (lastUri != null) {
            loadPrivateKeyFromUri(Uri.parse(lastUri));
        }

        updateUiState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRecordCount();
        refreshStoragePath();
        refreshDeviceOverview();
    }

    // ==================== 存储路径 ====================

    private void refreshStoragePath() {
        tvStoragePath.setText(RegRecordManager.getStorageDescription(this));
    }

    private void showStorageSettingsDialog() {
        String[] items;
        if (RegRecordManager.isCustomStorage(this)) {
            items = new String[]{"更改存储目录", "恢复默认存储路径", "取消"};
        } else {
            items = new String[]{"选择自定义存储目录", "取消"};
        }

        new AlertDialog.Builder(this)
                .setTitle("注册记录存储位置")
                .setItems(items, (d, idx) -> {
                    if (items[idx].startsWith("选择") || items[idx].startsWith("更改")) {
                        pickStorageDirectory();
                    } else if (items[idx].startsWith("恢复")) {
                        RegRecordManager.clearCustomStorage(this);
                        refreshStoragePath();
                        refreshRecordCount();
                        toast("已恢复默认存储路径");
                    }
                })
                .show();
    }

    private void pickStorageDirectory() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_STORAGE_DIR);
    }

    // ==================== 生成 ====================

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
            final String code = KeygenUtils.generateActivationCode(requestCode, validDays, privateKey);

            tvActivationCode.setText(code);
            tvValidDaysResult.setText("购买时长: " + (validDays == 0 ? "永久" : validDays + " 天"));
            tvExpiryResult.setText("到期: " + KeygenUtils.formatExpiry(validDays));
            btnCopyActivation.setEnabled(true);

            // 提取包名
            final String pkg;
            String extracted = RegRecordManager.extractPackageNameFromRequest(requestCode);
            pkg = (extracted != null) ? extracted : "";

            // 按安装码查重：同一安装码改变时长=覆盖旧记录，算一次注册
            RegRecordManager.Record existing = RegRecordManager.findByRequestCode(this, requestCode);
            if (existing != null) {
                // 同一安装码 → 弹窗提示，确认后覆盖
                String deviceInfo = (existing.packageName != null && !existing.packageName.isEmpty())
                        ? existing.deviceId + " · " + existing.packageName
                        : existing.deviceId;
                String oldDur = existing.validDays == 0 ? "永久" : existing.validDays + "天";
                String newDur = validDays == 0 ? "永久" : validDays + "天";
                new AlertDialog.Builder(this)
                        .setTitle("安装码已有记录")
                        .setMessage("该安装码已有一条记录，更新时长将覆盖旧记录。\n\n"
                                + "设备: " + deviceInfo + "\n"
                                + "原时长: " + oldDur + " → 新时长: " + newDur + "\n"
                                + "原到期: " + existing.expiryDate + "\n"
                                + "原时间: " + existing.regAt)
                        .setPositiveButton("更新记录", (d, w) -> {
                            RegRecordManager.upsertByRequestCode(
                                    this, requestCode, validDays, code, pkg);
                            refreshRecordCount();
                            refreshDeviceOverview();
                            toast("记录已更新");
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                // 新安装码 → 追加记录
                RegRecordManager.saveRecord(this, requestCode, validDays, code, pkg);
                refreshRecordCount();
                refreshDeviceOverview();
                toast("生成成功，已追加记录");
            }
        } catch (IllegalArgumentException e) {
            toast("安装码格式错误");
        } catch (Exception e) {
            toast("生成失败: " + e.getMessage());
        }
    }

    // ==================== ActivityResult ====================

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
        } else if (requestCode == REQ_EXPORT_RECORDS && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    RegRecordManager.exportRecords(this, uri);
                    toast("注册记录已导出");
                } catch (Exception e) {
                    toast("导出失败: " + e.getMessage());
                }
            }
        } else if (requestCode == REQ_PICK_STORAGE_DIR && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                RegRecordManager.setCustomStorageUri(this, uri);
                refreshStoragePath();
                refreshRecordCount();
                toast("存储路径已更新");
            }
        }
    }

    // ==================== 私钥 ====================

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

    private void loadPrivateKeyFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            String keyContent = sb.toString();

            privateKey = KeygenUtils.parsePrivateKey(keyContent);

            java.security.interfaces.RSAPrivateCrtKey rsaPriv =
                    (java.security.interfaces.RSAPrivateCrtKey) privateKey;
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

    // ==================== 记录 ====================

    private void refreshRecordCount() {
        int count = RegRecordManager.getRecordCount(this);
        int deviceCount = RegRecordManager.getUniqueDeviceCount(this);
        tvRecordCount.setText("共 " + count + " 条记录 · " + deviceCount + " 个设备");
        btnViewRecords.setEnabled(count > 0);
        btnExportRecords.setEnabled(count > 0);
    }

    // ==================== 设备总览 ====================

    private void refreshDeviceOverview() {
        llDeviceOverview.removeAllViews();

        // 从当前安装码中提取设备 ID
        String requestCode = Base32.ungroup(etRequestCode.getText().toString());
        String currentDeviceId = null;
        if (!TextUtils.isEmpty(requestCode)) {
            currentDeviceId = RegRecordManager.extractDeviceIdHex(requestCode);
        }

        if (currentDeviceId == null) {
            tvDeviceOverviewSummary.setVisibility(View.GONE);
            return;
        }

        // 只获取当前设备的分组信息
        List<RegRecordManager.DevicePackageGroup> groups = RegRecordManager.getDevicePackageGroupsByDeviceId(this, currentDeviceId);
        if (groups.isEmpty()) {
            tvDeviceOverviewSummary.setVisibility(View.GONE);
            return;
        }

        RegRecordManager.DevicePackageGroup group = groups.get(0);
        int totalRecords = group.getTotalRecordCount();
        int totalPkgs = group.packageGroups.size();
        tvDeviceOverviewSummary.setText(totalPkgs + " 个包 · 共 " + totalRecords + " 条记录");
        tvDeviceOverviewSummary.setVisibility(View.VISIBLE);

        View card = buildDeviceCard(group);
        llDeviceOverview.addView(card);
    }

    /** 第一层: 设备卡片 */
    private View buildDeviceCard(RegRecordManager.DevicePackageGroup group) {
        // 卡片容器
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, dp(4), 0, dp(4));
        card.setLayoutParams(cardParams);
        card.setBackgroundColor(0xFFFAFAFA);
        card.setClickable(true);
        card.setFocusable(true);

        // 设备头部行: deviceId + 包数记录数 + 展开箭头
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvDeviceId = new TextView(this);
        tvDeviceId.setText(group.deviceId);
        tvDeviceId.setTextSize(13);
        tvDeviceId.setTypeface(null, Typeface.BOLD);
        tvDeviceId.setTextColor(0xFF1976D2);
        tvDeviceId.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams devParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tvDeviceId.setLayoutParams(devParams);

        TextView tvPkgCount = new TextView(this);
        tvPkgCount.setText(group.packageGroups.size() + " 个包 · " + group.getTotalRecordCount() + " 条");
        tvPkgCount.setTextSize(11);
        tvPkgCount.setTextColor(0xFF7B1FA2);
        tvPkgCount.setPadding(dp(8), 0, dp(4), 0);

        final TextView tvArrow = new TextView(this);
        tvArrow.setText("▶");
        tvArrow.setTextSize(12);
        tvArrow.setTextColor(0xFF999999);

        header.addView(tvDeviceId);
        header.addView(tvPkgCount);
        header.addView(tvArrow);
        card.addView(header);

        // 包详情容器（默认折叠）: 第二层按包名展示
        final LinearLayout pkgContainer = new LinearLayout(this);
        pkgContainer.setOrientation(LinearLayout.VERTICAL);
        pkgContainer.setVisibility(View.GONE);
        pkgContainer.setPadding(dp(8), dp(4), 0, 0);

        for (int pi = 0; pi < group.packageGroups.size(); pi++) {
            if (pi > 0) {
                View pkgDiv = new View(this);
                pkgDiv.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
                pkgDiv.setBackgroundColor(0xFFE8E8E8);
                pkgContainer.addView(pkgDiv);
            }
            RegRecordManager.PackageGroup pg = group.packageGroups.get(pi);
            pkgContainer.addView(buildPkgSection(pg));
        }

        // 按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(6), 0, 0);

        TextView btnDetail = new TextView(this);
        btnDetail.setText("详情");
        btnDetail.setTextSize(11);
        btnDetail.setTextColor(0xFF1976D2);
        btnDetail.setPadding(dp(12), dp(4), dp(12), dp(4));
        btnDetail.setClickable(true);
        btnDetail.setFocusable(true);
        btnDetail.setOnClickListener(v -> showDeviceDetail(group));

        TextView btnDelete = new TextView(this);
        btnDelete.setText("删除");
        btnDelete.setTextSize(11);
        btnDelete.setTextColor(0xFFE53935);
        btnDelete.setPadding(dp(12), dp(4), dp(12), dp(4));
        btnDelete.setClickable(true);
        btnDelete.setFocusable(true);
        btnDelete.setOnClickListener(v -> confirmDeleteDeviceOverview(group));

        btnRow.addView(btnDetail);
        btnRow.addView(btnDelete);
        pkgContainer.addView(btnRow);

        card.addView(pkgContainer);

        // 点击头部展开/折叠
        final boolean[] expanded = {false};
        header.setOnClickListener(v -> {
            expanded[0] = !expanded[0];
            pkgContainer.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
            tvArrow.setText(expanded[0] ? "▼" : "▶");
        });

        // 设备ID长按复制
        tvDeviceId.setOnLongClickListener(v -> {
            copyToClipboard("device_id", group.deviceId);
            toast("设备ID已复制");
            return true;
        });

        return card;
    }

    /** 第二层: 包名分组，展示该包最新记录摘要 */
    private View buildPkgSection(RegRecordManager.PackageGroup pkgGroup) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(4), dp(4), 0, dp(2));

        // 包名行
        LinearLayout pkgRow = new LinearLayout(this);
        pkgRow.setOrientation(LinearLayout.HORIZONTAL);
        pkgRow.setGravity(Gravity.CENTER_VERTICAL);
        pkgRow.setPadding(dp(4), dp(2), 0, dp(2));

        View pkgDot = new View(this);
        pkgDot.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));
        pkgDot.setBackgroundColor(0xFF1976D2);
        pkgRow.addView(pkgDot);

        TextView tvPkgName = new TextView(this);
        tvPkgName.setText("  " + pkgGroup.packageName);
        tvPkgName.setTextSize(11);
        tvPkgName.setTextColor(0xFF555555);
        tvPkgName.setTypeface(Typeface.MONOSPACE);

        TextView tvPkgCount = new TextView(this);
        tvPkgCount.setText(" (" + pkgGroup.records.size() + " 次)");
        tvPkgCount.setTextSize(10);
        tvPkgCount.setTextColor(0xFF888888);

        pkgRow.addView(tvPkgName);
        pkgRow.addView(tvPkgCount);

        // 最新记录摘要
        RegRecordManager.Record latest = pkgGroup.records.get(0);
        TextView tvSummary = new TextView(this);
        String dur = latest.validDays == 0 ? "永久" : latest.validDays + "天";
        tvSummary.setText(dur + " · 到期 " + latest.expiryDate + " · " + latest.regAt);
        tvSummary.setTextSize(10);
        tvSummary.setTextColor(0xFF388E3C);
        tvSummary.setPadding(dp(16), dp(1), 0, dp(2));

        section.addView(pkgRow);
        section.addView(tvSummary);

        return section;
    }

    private void showDeviceDetail(RegRecordManager.DevicePackageGroup group) {
        // 汇总所有记录为历史列表
        List<RegRecordManager.Record> allRecords = new ArrayList<>();
        for (RegRecordManager.PackageGroup pg : group.packageGroups) {
            allRecords.addAll(pg.records);
        }
        allRecords.sort((a, b) -> Long.compare(b.id, a.id));

        String text = RegRecordManager.formatHistory(allRecords, false);
        final boolean[] showAct = {false};

        android.app.AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("设备 " + group.deviceId)
                .setMessage(text)
                .setPositiveButton("显示激活码", null)
                .setNeutralButton("关闭", null)
                .setNegativeButton("删除全部", (d, w) -> confirmDeleteDeviceOverview(group))
                .create();

        dialog.setOnShowListener(d -> {
            android.widget.Button btnToggle = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            btnToggle.setOnClickListener(v -> {
                showAct[0] = !showAct[0];
                String newText = RegRecordManager.formatHistory(allRecords, showAct[0]);
                android.widget.TextView msgTv = (android.widget.TextView) dialog.findViewById(android.R.id.message);
                if (msgTv != null) msgTv.setText(newText);
                btnToggle.setText(showAct[0] ? "隐藏激活码" : "显示激活码");
            });
        });

        dialog.show();
    }

    private void confirmDeleteDeviceOverview(RegRecordManager.DevicePackageGroup group) {
        int total = group.getTotalRecordCount();
        new AlertDialog.Builder(this)
                .setTitle("删除设备全部记录")
                .setMessage("确定删除设备 " + group.deviceId + " 的全部 "
                        + total + " 条注册记录？")
                .setPositiveButton("全部删除", (d, w) -> {
                    RegRecordManager.deleteByDeviceId(this, group.deviceId);
                    refreshRecordCount();
                    refreshDeviceOverview();
                    toast("已删除 " + total + " 条记录");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp(int px) {
        return (int) (px * getResources().getDisplayMetrics().density);
    }

    private void exportRecords() {
        List<RegRecordManager.Record> records = RegRecordManager.readRecords(this);
        if (records.isEmpty()) {
            toast("暂无注册记录可导出");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "reg_records_" +
                new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                        .format(new java.util.Date()) + ".json");
        startActivityForResult(intent, REQ_EXPORT_RECORDS);
    }

    // ==================== Utils ====================

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
