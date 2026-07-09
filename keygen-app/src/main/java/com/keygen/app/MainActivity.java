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
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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
    private Button btnQueryDevice;
    private Button btnStorageSettings;
    private TextView tvRecordCount;
    private TextView tvStoragePath;
    private TextView tvQueryResult;
    private LinearLayout llDeviceOverview;
    private TextView tvDeviceOverviewSummary;

    private List<RegRecordManager.Record> lastQueryHistory;
    private String lastQueryDeviceId;

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
                    // 粘贴后自动查询展示完整记录
                    doQuery();
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

        // 安装码查询
        btnQueryDevice = findViewById(R.id.btn_query_device);
        tvQueryResult = findViewById(R.id.tv_query_result);
        btnQueryDevice.setOnClickListener(v -> doQuery());

        // 查询结果可点击查看详情
        tvQueryResult.setOnClickListener(v -> {
            if (lastQueryHistory != null && !lastQueryHistory.isEmpty()) {
                showHistoryDetailDialog(lastQueryHistory);
            }
        });

        // 安装码输入变化时自动清理查询结果
        etRequestCode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                tvQueryResult.setText("");
                tvQueryResult.setVisibility(View.GONE);
                tvQueryResult.setClickable(false);
                lastQueryHistory = null;
                lastQueryDeviceId = null;
            }
            @Override public void afterTextChanged(Editable s) {}
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
        // 如果之前查询过，刷新查询结果
        if (lastQueryDeviceId != null) {
            refreshQueryDisplay();
        }
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

    // ==================== 查询设备（立即显示历史） ====================

    private void doQuery() {
        String requestCode = Base32.ungroup(etRequestCode.getText().toString());
        if (TextUtils.isEmpty(requestCode)) {
            toast("请先输入安装码");
            return;
        }

        String deviceId = RegRecordManager.extractDeviceIdHex(requestCode);
        if (deviceId == null) {
            tvQueryResult.setText("安装码格式错误");
            tvQueryResult.setTextColor(0xFFE53935);
            tvQueryResult.setVisibility(View.VISIBLE);
            tvQueryResult.setClickable(false);
            lastQueryHistory = null;
            lastQueryDeviceId = null;
            return;
        }

        lastQueryDeviceId = deviceId;
        refreshQueryDisplay();
    }

    /** 刷新查询结果显示（从存储器重新读取）。 */
    private void refreshQueryDisplay() {
        if (lastQueryDeviceId == null) return;

        lastQueryHistory = RegRecordManager.queryHistoryByDeviceId(this, lastQueryDeviceId);

        if (lastQueryHistory == null || lastQueryHistory.isEmpty()) {
            tvQueryResult.setText("此设备首次注册 (" + lastQueryDeviceId + ")");
            tvQueryResult.setTextColor(0xFF388E3C);
            tvQueryResult.setVisibility(View.VISIBLE);
            tvQueryResult.setClickable(false);
        } else {
            RegRecordManager.Record latest = lastQueryHistory.get(lastQueryHistory.size() - 1);
            String pkgInfo = (latest.packageName != null && !latest.packageName.isEmpty())
                    ? " · " + latest.packageName : "";
            tvQueryResult.setText("已注册 " + lastQueryHistory.size() + " 次"
                    + pkgInfo
                    + " · 到期: " + latest.expiryDate
                    + " · 末次: " + latest.regAt
                    + "\n(点击查看完整历史)");
            tvQueryResult.setTextColor(0xFF1976D2);
            tvQueryResult.setVisibility(View.VISIBLE);
            tvQueryResult.setClickable(true);
        }
    }

    /** 弹窗展示该设备所有历史注册记录。 */
    private void showHistoryDetailDialog(List<RegRecordManager.Record> history) {
        String text = RegRecordManager.formatHistory(history);
        RegRecordManager.Record first = history.get(0);
        String title = "注册历史 · " + first.deviceId;
        if (first.packageName != null && !first.packageName.isEmpty()) {
            title += " · " + first.packageName;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton("关闭", null)
                .setNeutralButton("删除此设备全部记录", (d, w) -> {
                    new AlertDialog.Builder(this)
                            .setTitle("确认删除")
                            .setMessage("确定删除设备 " + history.get(0).deviceId + " 的全部 "
                                    + history.size() + " 条注册记录？")
                            .setPositiveButton("确认删除", (dd, ww) -> {
                                RegRecordManager.deleteByDeviceId(this, history.get(0).deviceId);
                                lastQueryDeviceId = null;
                                lastQueryHistory = null;
                                tvQueryResult.setText("已删除 · 此设备无记录");
                                tvQueryResult.setTextColor(0xFFE53935);
                                tvQueryResult.setClickable(false);
                                refreshRecordCount();
                                refreshDeviceOverview();
                                toast("已删除");
                            })
                            .setNegativeButton("取消", null)
                            .show();
                })
                .show();
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

            // 检查同激活码是否已有记录
            RegRecordManager.Record existing = RegRecordManager.findByActivationCode(this, code);
            if (existing != null) {
                // 重复激活码 → 弹窗提示，确认后覆盖
                String deviceInfo = (existing.packageName != null && !existing.packageName.isEmpty())
                        ? existing.deviceId + " · " + existing.packageName
                        : existing.deviceId;
                new AlertDialog.Builder(this)
                        .setTitle("重复激活码")
                        .setMessage("此激活码已有注册记录，是否覆盖重新记录？\n\n"
                                + "设备: " + deviceInfo + "\n"
                                + "原记录时间: " + existing.regAt + "\n"
                                + "原到期: " + existing.expiryDate)
                        .setPositiveButton("重新记录", (d, w) -> {
                            RegRecordManager.upsertByActivationCode(
                                    this, requestCode, validDays, code, pkg);
                            refreshRecordCount();
                            refreshDeviceOverview();
                            refreshQueryAfterGenerate(requestCode);
                            toast("已覆盖记录");
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                // 新激活码 → 追加新记录
                RegRecordManager.saveRecord(this, requestCode, validDays, code, pkg);
                refreshRecordCount();
                refreshDeviceOverview();
                refreshQueryAfterGenerate(requestCode);
                toast("生成成功，已追加记录");
            }
        } catch (IllegalArgumentException e) {
            toast("安装码格式错误");
        } catch (Exception e) {
            toast("生成失败: " + e.getMessage());
        }
    }

    /** 生成激活码后刷新查询结果（如果当前安装码对应同设备）。 */
    private void refreshQueryAfterGenerate(String requestCode) {
        String deviceId = RegRecordManager.extractDeviceIdHex(requestCode);
        if (deviceId != null && deviceId.equals(lastQueryDeviceId)) {
            refreshQueryDisplay();
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
        List<RegRecordManager.DeviceGroup> groups = RegRecordManager.getDeviceGroups(this);
        llDeviceOverview.removeAllViews();

        if (groups.isEmpty()) {
            tvDeviceOverviewSummary.setVisibility(View.GONE);
            return;
        }

        int totalPkgs = 0;
        int totalRecords = 0;
        for (RegRecordManager.DeviceGroup g : groups) {
            totalPkgs += g.getPackageCount();
            totalRecords += g.records.size();
        }
        tvDeviceOverviewSummary.setText(groups.size() + " 个设备 · " + totalPkgs + " 个包 · 共 " + totalRecords + " 条记录");
        tvDeviceOverviewSummary.setVisibility(View.VISIBLE);

        for (RegRecordManager.DeviceGroup group : groups) {
            View card = buildDeviceCard(group);
            llDeviceOverview.addView(card);
        }
    }

    private View buildDeviceCard(RegRecordManager.DeviceGroup group) {
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

        // 设备头部行: deviceId + 包数 + 展开箭头
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
        tvPkgCount.setText(group.getPackageCount() + " 个包");
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

        // 包详情容器（默认折叠）
        final LinearLayout pkgContainer = new LinearLayout(this);
        pkgContainer.setOrientation(LinearLayout.VERTICAL);
        pkgContainer.setVisibility(View.GONE);
        pkgContainer.setPadding(dp(8), dp(4), 0, 0);

        List<String> pkgNames = group.getPackageNames();
        for (String pkgName : pkgNames) {
            RegRecordManager.Record latest = group.getLatestByPackage(pkgName);
            pkgContainer.addView(buildPkgRow(pkgName, latest));
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

    private View buildPkgRow(String pkgName, @Nullable RegRecordManager.Record record) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView tvPkg = new TextView(this);
        tvPkg.setText(pkgName);
        tvPkg.setTextSize(11);
        tvPkg.setTextColor(0xFF555555);
        tvPkg.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams pkgParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tvPkg.setLayoutParams(pkgParams);

        TextView tvInfo = new TextView(this);
        if (record != null) {
            String dur = record.validDays == 0 ? "永久" : record.validDays + "天";
            tvInfo.setText(dur + " · 到期 " + record.expiryDate);
            tvInfo.setTextColor(0xFF388E3C);
        } else {
            tvInfo.setText("无记录");
            tvInfo.setTextColor(0xFF999999);
        }
        tvInfo.setTextSize(10);

        row.addView(tvPkg);
        row.addView(tvInfo);
        return row;
    }

    private void showDeviceDetail(RegRecordManager.DeviceGroup group) {
        List<RegRecordManager.Record> history = group.records;
        String text = RegRecordManager.formatHistory(history, false);
        final boolean[] showAct = {false};

        new AlertDialog.Builder(this)
                .setTitle("设备 " + group.deviceId)
                .setMessage(text)
                .setPositiveButton("显示激活码", null)
                .setNeutralButton("关闭", null)
                .setNegativeButton("删除全部", (d, w) -> confirmDeleteDeviceOverview(group))
                .show();
    }

    private void confirmDeleteDeviceOverview(RegRecordManager.DeviceGroup group) {
        new AlertDialog.Builder(this)
                .setTitle("删除设备全部记录")
                .setMessage("确定删除设备 " + group.deviceId + " 的全部 "
                        + group.records.size() + " 条注册记录？\n\n"
                        + group.getPackageCount() + " 个包的许可将全部失效。")
                .setPositiveButton("全部删除", (d, w) -> {
                    RegRecordManager.deleteByDeviceId(this, group.deviceId);
                    refreshRecordCount();
                    refreshDeviceOverview();
                    if (lastQueryDeviceId != null && lastQueryDeviceId.equals(group.deviceId)) {
                        lastQueryDeviceId = null;
                        lastQueryHistory = null;
                        tvQueryResult.setText("");
                        tvQueryResult.setVisibility(View.GONE);
                    }
                    toast("已删除 " + group.records.size() + " 条记录");
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
