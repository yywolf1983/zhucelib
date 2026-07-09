package com.keygen.app;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
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
 * 注册记录查看页面 — 按设备分组，每个设备下展示所有包的注册记录。
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
        List<RegRecordManager.DeviceGroup> groups = RegRecordManager.getDeviceGroups(this);
        llContent.removeAllViews();

        if (groups.isEmpty()) {
            tvSummary.setText("暂无注册记录");
            return;
        }

        int totalPkgs = 0;
        int totalRecords = 0;
        for (RegRecordManager.DeviceGroup g : groups) {
            totalPkgs += g.getPackageCount();
            totalRecords += g.records.size();
        }
        tvSummary.setText(groups.size() + " 个设备 · " + totalPkgs + " 个包 · 共 " + totalRecords + " 条记录");

        for (RegRecordManager.DeviceGroup group : groups) {
            llContent.addView(buildDeviceCard(group));
        }
    }

    private View buildDeviceCard(RegRecordManager.DeviceGroup group) {
        // 卡片外层
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, dp(6), 0, dp(6));
        card.setLayoutParams(cp);
        card.setBackgroundColor(0xFFF5F5F5);

        // === 头部 ===
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

        TextView tvPkg = new TextView(this);
        tvPkg.setText(group.getPackageCount() + " 包 · " + group.records.size() + " 条");
        tvPkg.setTextSize(11);
        tvPkg.setTextColor(0xFF7B1FA2);
        tvPkg.setPadding(dp(8), 0, dp(4), 0);

        final TextView tvArrow = new TextView(this);
        tvArrow.setText("▶");
        tvArrow.setTextSize(12);
        tvArrow.setTextColor(0xFF999);

        header.addView(tvDev);
        header.addView(tvPkg);
        header.addView(tvArrow);
        card.addView(header);

        // === 展开内容 ===
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        body.setPadding(dp(8), dp(6), 0, dp(4));

        // 按包名展示记录
        List<String> pkgNames = group.getPackageNames();
        for (String pkgName : pkgNames) {
            View pkgSection = buildPackageSection(group, pkgName);
            body.addView(pkgSection);
        }

        // 操作按钮
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(8), 0, 0);

        TextView btnDel = new TextView(this);
        btnDel.setText("删除该设备全部记录");
        btnDel.setTextSize(11);
        btnDel.setTextColor(0xFFE53935);
        btnDel.setPadding(dp(12), dp(4), dp(4), dp(4));
        btnDel.setOnClickListener(v -> confirmDeleteDevice(group));
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

    private View buildPackageSection(RegRecordManager.DeviceGroup group, String pkgName) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(4), 0, dp(4));

        // 包名头部
        TextView tvPkgLabel = new TextView(this);
        tvPkgLabel.setText("📦 " + pkgName);
        tvPkgLabel.setTextSize(12);
        tvPkgLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvPkgLabel.setTextColor(0xFF333333);
        tvPkgLabel.setPadding(0, dp(2), 0, dp(2));
        section.addView(tvPkgLabel);

        // 该包的所有记录
        for (RegRecordManager.Record r : group.records) {
            String rPkg = (r.packageName != null && !r.packageName.isEmpty())
                    ? r.packageName : "(未指定)";
            if (!rPkg.equals(pkgName)) continue;

            View recordRow = buildRecordRow(r);
            section.addView(recordRow);
        }

        return section;
    }

    private View buildRecordRow(RegRecordManager.Record r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(3), 0, dp(3));
        row.setClickable(true);
        row.setFocusable(true);

        // 时间 + 时长
        TextView tvLeft = new TextView(this);
        String dur = r.validDays == 0 ? "永久" : r.validDays + "天";
        tvLeft.setText(r.regAt + " · " + dur);
        tvLeft.setTextSize(11);
        tvLeft.setTextColor(0xFF666666);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tvLeft.setLayoutParams(lp);

        // 到期
        TextView tvExp = new TextView(this);
        tvExp.setText("到期 " + r.expiryDate);
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

        row.addView(tvLeft);
        row.addView(tvExp);

        row.setOnClickListener(v -> showRecordDetail(r));

        return row;
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

    private void confirmDeleteDevice(RegRecordManager.DeviceGroup group) {
        new AlertDialog.Builder(this)
                .setTitle("删除设备全部记录")
                .setMessage("确定删除设备 " + group.deviceId + " 的全部 "
                        + group.records.size() + " 条记录？\n\n"
                        + group.getPackageCount() + " 个包的许可将全部失效。")
                .setPositiveButton("全部删除", (d, w) -> {
                    RegRecordManager.deleteByDeviceId(this, group.deviceId);
                    loadRecords();
                    toast("已删除 " + group.records.size() + " 条记录");
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
