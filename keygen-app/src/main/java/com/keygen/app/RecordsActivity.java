package com.keygen.app;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 注册记录查看页面 — 按 设备ID(第一层) → 包名(第二层) 分组展示。
 */
public class RecordsActivity extends AppCompatActivity {

    private static final int REQ_EXPORT = 0x2001;

    private LinearLayout llContent;
    private TextView tvSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_records);

        llContent = findViewById(R.id.ll_records_content);
        tvSummary = findViewById(R.id.tv_records_summary);
        Button btnExport = findViewById(R.id.btn_records_export);
        ImageButton btnBack = findViewById(R.id.btn_records_back);

        btnBack.setOnClickListener(v -> finish());
        btnExport.setOnClickListener(v -> doExport());

        loadRecords();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadRecords();
    }

    private void loadRecords() {
        List<RegRecordManager.DevicePackageGroup> groups = RegRecordManager.getDevicePackageGroups(this);
        llContent.removeAllViews();

        if (groups.isEmpty()) {
            tvSummary.setText("暂无注册记录");
            return;
        }

        int totalRecords = 0;
        int totalPkgs = 0;
        for (RegRecordManager.DevicePackageGroup g : groups) {
            totalRecords += g.getTotalRecordCount();
            totalPkgs += g.packageGroups.size();
        }
        tvSummary.setText(groups.size() + " 个设备 · " + totalPkgs + " 个包 · 共 " + totalRecords + " 条记录");

        for (RegRecordManager.DevicePackageGroup group : groups) {
            llContent.addView(buildDeviceCard(group));
        }
    }

    /** 第一层: 设备卡片 */
    private View buildDeviceCard(RegRecordManager.DevicePackageGroup group) {
        // 卡片外层
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, dp(6), 0, dp(6));
        card.setLayoutParams(cp);
        card.setBackgroundColor(0xFFF5F5F5);

        // === 设备头部 ===
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvDev = new TextView(this);
        tvDev.setText(group.deviceId);
        tvDev.setTextSize(14);
        tvDev.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvDev.setTextColor(0xFF1976D2);
        LinearLayout.LayoutParams dp_ = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tvDev.setLayoutParams(dp_);

        TextView tvCount = new TextView(this);
        tvCount.setText(group.packageGroups.size() + " 个包 · " + group.getTotalRecordCount() + " 条记录");
        tvCount.setTextSize(11);
        tvCount.setTextColor(0xFF7B1FA2);
        tvCount.setPadding(dp(8), 0, dp(4), 0);

        final TextView tvArrow = new TextView(this);
        tvArrow.setText("▶");
        tvArrow.setTextSize(12);
        tvArrow.setTextColor(0xFF999);

        header.addView(tvDev);
        header.addView(tvCount);
        header.addView(tvArrow);
        card.addView(header);

        // === 设备备注（如果有） ===
        String remark = RegRecordManager.getDeviceRemark(this, group.deviceId);
        if (!remark.isEmpty()) {
            TextView tvRemark = new TextView(this);
            tvRemark.setText("📝 " + remark);
            tvRemark.setTextSize(11);
            tvRemark.setTextColor(0xFF666666);
            tvRemark.setPadding(dp(12), dp(2), dp(12), dp(2));
            tvRemark.setMaxLines(2);
            tvRemark.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(tvRemark);
        }

        // === 展开内容: 包名列表（第二层） ===
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        body.setPadding(dp(8), dp(6), 0, dp(4));

        for (int pi = 0; pi < group.packageGroups.size(); pi++) {
            if (pi > 0) {
                // 包间分隔
                View pkgDiv = new View(this);
                pkgDiv.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
                pkgDiv.setBackgroundColor(0xFFDDDDDD);
                body.addView(pkgDiv);
            }
            body.addView(buildPackageSection(group.packageGroups.get(pi)));
        }

        // 设备级操作按钮
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(8), 0, 0);

        // 备注按钮
        final String currentRemark = RegRecordManager.getDeviceRemark(this, group.deviceId);
        TextView btnRemark = new TextView(this);
        btnRemark.setText(currentRemark.isEmpty() ? "添加备注" : "编辑备注");
        btnRemark.setTextSize(11);
        btnRemark.setTextColor(0xFF1976D2);
        btnRemark.setPadding(dp(4), dp(4), dp(12), dp(4));
        btnRemark.setOnClickListener(v -> showRemarkDialog(group.deviceId, currentRemark));
        btnRow.addView(btnRemark);

        TextView btnDel = new TextView(this);
        btnDel.setText("删除该设备全部记录");
        btnDel.setTextSize(11);
        btnDel.setTextColor(0xFFE53935);
        btnDel.setPadding(dp(12), dp(4), dp(4), dp(4));
        btnDel.setOnClickListener(v -> confirmDeleteDevicePackageGroup(group));
        btnRow.addView(btnDel);
        body.addView(btnRow);

        card.addView(body);

        // 展开/折叠
        final boolean[] expanded = {false};
        header.setOnClickListener(v -> {
            expanded[0] = !expanded[0];
            body.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
            tvArrow.setText(expanded[0] ? "▼" : "▶");
        });

        return card;
    }

    /** 第二层: 包名分组 + 其下记录列表 */
    private View buildPackageSection(RegRecordManager.PackageGroup pkgGroup) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(4), dp(4), 0, dp(2));

        // 包名头部
        LinearLayout pkgHeader = new LinearLayout(this);
        pkgHeader.setOrientation(LinearLayout.HORIZONTAL);
        pkgHeader.setGravity(Gravity.CENTER_VERTICAL);
        pkgHeader.setPadding(dp(4), dp(2), 0, dp(2));

        View pkgDot = new View(this);
        pkgDot.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));
        pkgDot.setBackgroundColor(0xFF1976D2);
        pkgHeader.addView(pkgDot);

        TextView tvPkgName = new TextView(this);
        tvPkgName.setText("  " + pkgGroup.packageName);
        tvPkgName.setTextSize(12);
        tvPkgName.setTextColor(0xFF1976D2);
        tvPkgName.setTypeface(null, Typeface.BOLD);

        TextView tvPkgCount = new TextView(this);
        tvPkgCount.setText(" (" + pkgGroup.records.size() + " 次)");
        tvPkgCount.setTextSize(10);
        tvPkgCount.setTextColor(0xFF888888);

        pkgHeader.addView(tvPkgName);
        pkgHeader.addView(tvPkgCount);
        section.addView(pkgHeader);

        // 该包下的记录列表
        for (int i = 0; i < pkgGroup.records.size(); i++) {
            if (i > 0) {
                View div = new View(this);
                div.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
                div.setBackgroundColor(0xFFECECEC);
                section.addView(div);
            }
            section.addView(buildRecordRow(pkgGroup.records.get(i)));
        }

        return section;
    }

    private View buildRecordRow(RegRecordManager.Record r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(24), dp(4), dp(8), dp(4));
        row.setClickable(true);
        row.setFocusable(true);

        // 第一行: 时间
        TextView tvTime = new TextView(this);
        tvTime.setText(r.regAt);
        tvTime.setTextSize(11);
        tvTime.setTextColor(0xFF444444);
        tvTime.setTypeface(null, Typeface.BOLD);

        // 第二行: 时长 + 到期
        LinearLayout botRow = new LinearLayout(this);
        botRow.setOrientation(LinearLayout.HORIZONTAL);
        botRow.setGravity(Gravity.CENTER_VERTICAL);
        botRow.setPadding(0, dp(2), 0, 0);

        String dur = r.validDays == 0 ? "永久" : r.validDays + "天";
        TextView tvDur = new TextView(this);
        tvDur.setText("时长 " + dur);
        tvDur.setTextSize(10);
        tvDur.setTextColor(0xFF888888);

        TextView tvExp = new TextView(this);
        tvExp.setText("  ·  到期 " + r.expiryDate);
        tvExp.setTextSize(10);
        boolean expired = !"永久".equals(r.expiryDate);
        if (expired) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                Date expDate = sdf.parse(r.expiryDate);
                expired = expDate != null && expDate.getTime() < System.currentTimeMillis();
            } catch (Exception ignored) {}
        }
        tvExp.setTextColor(expired ? 0xFFE53935 : 0xFF388E3C);

        botRow.addView(tvDur);
        botRow.addView(tvExp);

        row.addView(tvTime);
        row.addView(botRow);

        row.setOnClickListener(v -> showRecordDetail(r));

        return row;
    }

    // ==================== 备注编辑 ====================

    private void showRemarkDialog(String deviceId, String currentRemark) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMaxLines(3);
        et.setHint("输入设备备注（如客户名称、联系方式等）");
        if (!currentRemark.isEmpty()) et.setText(currentRemark);
        et.setSelection(et.getText().length());
        et.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(8), dp(8), dp(8), dp(8));
        container.addView(et);

        new AlertDialog.Builder(this)
                .setTitle(currentRemark.isEmpty() ? "添加备注" : "编辑备注")
                .setView(container)
                .setPositiveButton("保存", (d, w) -> {
                    String text = et.getText().toString().trim();
                    RegRecordManager.setDeviceRemark(this, deviceId, text);
                    loadRecords();
                    toast(text.isEmpty() ? "已清除备注" : "已保存备注");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 详情 / 删除 ====================

    private void showRecordDetail(RegRecordManager.Record r) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_record_detail);

        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams wlp = window.getAttributes();
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.85);
            wlp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.75);
            wlp.gravity = Gravity.CENTER;
            window.setAttributes(wlp);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        TextView tvRecordId = dialog.findViewById(R.id.dialog_detail_record_id);
        TextView tvDeviceId = dialog.findViewById(R.id.dialog_detail_device_id);
        View pkgRow = dialog.findViewById(R.id.dialog_detail_pkg_row);
        TextView tvPackage = dialog.findViewById(R.id.dialog_detail_package);
        TextView tvRegTime = dialog.findViewById(R.id.dialog_detail_reg_time);
        TextView tvValidDays = dialog.findViewById(R.id.dialog_detail_valid_days);
        TextView tvExpiry = dialog.findViewById(R.id.dialog_detail_expiry);
        TextView tvActCode = dialog.findViewById(R.id.dialog_detail_activation_code);

        tvRecordId.setText(String.valueOf(r.id));
        tvDeviceId.setText(r.deviceId);
        if (r.packageName != null && !r.packageName.isEmpty()) {
            tvPackage.setText(r.packageName);
            pkgRow.setVisibility(View.VISIBLE);
        } else {
            pkgRow.setVisibility(View.GONE);
        }
        tvRegTime.setText(r.regAt);
        tvValidDays.setText(r.validDays == 0 ? "永久" : r.validDays + " 天");
        tvExpiry.setText(r.expiryDate);
        tvActCode.setText(r.activationCode);

        View actToggle = dialog.findViewById(R.id.dialog_detail_act_toggle);
        TextView actToggleLabel = (TextView) ((ViewGroup) actToggle).getChildAt(0);
        actToggle.setOnClickListener(v -> {
            if (tvActCode.getVisibility() == View.GONE) {
                tvActCode.setVisibility(View.VISIBLE);
                actToggleLabel.setText("激活码 ▼");
            } else {
                tvActCode.setVisibility(View.GONE);
                actToggleLabel.setText("激活码 ▶");
            }
        });

        Button btnClose = dialog.findViewById(R.id.dialog_btn_close);
        Button btnDelete = dialog.findViewById(R.id.dialog_btn_delete);

        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnDelete.setOnClickListener(v -> {
            dialog.dismiss();
            confirmDeleteSingle(r);
        });

        // 隐藏"删除设备"按钮 — Records 页已在设备卡片层提供
        Button btnDeleteDevice = dialog.findViewById(R.id.dialog_btn_delete_device);
        if (btnDeleteDevice != null) btnDeleteDevice.setVisibility(View.GONE);

        dialog.show();
    }

    private void confirmDeleteSingle(RegRecordManager.Record r) {
        new AlertDialog.Builder(this)
                .setTitle("删除记录")
                .setMessage("确定删除该条注册记录？\n\n设备: " + r.deviceId
                        + "\n包名: " + (r.packageName != null && !r.packageName.isEmpty() ? r.packageName : "无")
                        + "\n时间: " + r.regAt)
                .setPositiveButton("删除", (d, w) -> {
                    RegRecordManager.deleteById(this, r.id);
                    loadRecords();
                    toast("已删除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteDevicePackageGroup(RegRecordManager.DevicePackageGroup group) {
        int total = group.getTotalRecordCount();
        new AlertDialog.Builder(this)
                .setTitle("删除设备全部记录")
                .setMessage("确定删除设备 " + group.deviceId + " 的全部 "
                        + total + " 条注册记录？")
                .setPositiveButton("全部删除", (d, w) -> {
                    RegRecordManager.deleteByDeviceId(this, group.deviceId);
                    RegRecordManager.deleteDeviceRemark(this, group.deviceId);
                    loadRecords();
                    toast("已删除 " + total + " 条记录");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 导出 ====================

    private void doExport() {
        List<RegRecordManager.Record> records = RegRecordManager.readRecords(this);
        if (records.isEmpty()) {
            toast("暂无记录可导出");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "reg_records_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".json");
        startActivityForResult(intent, REQ_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    RegRecordManager.exportRecords(this, uri);
                    toast("已导出到 " + uri.getLastPathSegment());
                } catch (Exception e) {
                    toast("导出失败: " + e.getMessage());
                }
            }
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int px) {
        return (int) (px * getResources().getDisplayMetrics().density);
    }
}
