package com.keygen.app;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 注册记录查看页面 — 每条注册独立展示。
 *
 * 功能:
 *   - RecyclerView 列表，每条一条记录
 *   - 点击查看详情
 *   - 按记录 ID 删除
 *   - 长按删除该设备全部记录
 *   - 导出 JSON
 */
public class RecordsActivity extends AppCompatActivity {

    private static final int REQ_EXPORT = 0x2001;

    private RecyclerView recyclerView;
    private TextView tvSummary;
    private RecordAdapter adapter;
    private List<RegRecordManager.Record> records = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_records);

        recyclerView = findViewById(R.id.recycler_records);
        tvSummary = findViewById(R.id.tv_records_summary);
        Button btnExport = findViewById(R.id.btn_records_export);
        ImageButton btnBack = findViewById(R.id.btn_records_back);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecordAdapter();
        recyclerView.setAdapter(adapter);

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
        records = RegRecordManager.readRecords(this);

        // 统计不重复设备数
        Set<String> deviceSet = new HashSet<>();
        for (RegRecordManager.Record r : records) deviceSet.add(r.deviceId);

        adapter.setRecords(records);
        tvSummary.setText("共 " + records.size() + " 条记录 · " + deviceSet.size() + " 个设备 · "
                + RegRecordManager.getStorageDescription(this));
    }

    private void doExport() {
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

    private void confirmDeleteSingle(RegRecordManager.Record r) {
        new AlertDialog.Builder(this)
                .setTitle("删除记录")
                .setMessage("确定删除该条注册记录？\n\n"
                        + "设备ID: " + r.deviceId + "\n"
                        + "注册时间: " + r.regAt + "\n"
                        + "购买时长: " + (r.validDays == 0 ? "永久" : r.validDays + " 天"))
                .setPositiveButton("删除", (d, w) -> {
                    RegRecordManager.deleteById(this, r.id);
                    loadRecords();
                    toast("已删除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteDevice(RegRecordManager.Record r) {
        List<RegRecordManager.Record> history = RegRecordManager.queryHistoryByDeviceId(this, r.deviceId);
        new AlertDialog.Builder(this)
                .setTitle("删除设备全部记录")
                .setMessage("确定删除设备 " + r.deviceId + " 的全部 " + history.size() + " 条注册记录？")
                .setPositiveButton("全部删除", (d, w) -> {
                    RegRecordManager.deleteByDeviceId(this, r.deviceId);
                    loadRecords();
                    toast("已删除 " + history.size() + " 条记录");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDetail(RegRecordManager.Record r) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_record_detail);

        // 设置对话框尺寸为屏幕的 4/5
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams wlp = window.getAttributes();
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.8);
            wlp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.8);
            wlp.gravity = Gravity.CENTER;
            window.setAttributes(wlp);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        // 字段
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

        // 激活码折叠/展开
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

        // 设备ID点击 → 展示该设备所有注册记录
        tvDeviceId.setOnClickListener(v -> {
            dialog.dismiss();
            showDeviceHistory(r.deviceId);
        });

        // 按钮
        Button btnClose = dialog.findViewById(R.id.dialog_btn_close);
        Button btnDelete = dialog.findViewById(R.id.dialog_btn_delete);
        Button btnDeleteDevice = dialog.findViewById(R.id.dialog_btn_delete_device);

        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnDelete.setOnClickListener(v -> {
            dialog.dismiss();
            confirmDeleteSingle(r);
        });
        btnDeleteDevice.setOnClickListener(v -> {
            dialog.dismiss();
            confirmDeleteDevice(r);
        });

        dialog.show();
    }

    /** 弹窗展示某个设备的所有注册记录。 */
    private void showDeviceHistory(String deviceId) {
        List<RegRecordManager.Record> history = RegRecordManager.queryHistoryByDeviceId(this, deviceId);
        if (history.isEmpty()) {
            toast("该设备暂无注册记录");
            return;
        }

        final boolean[] showAct = {false};

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_device_history);

        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams wlp = window.getAttributes();
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.8);
            wlp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.8);
            wlp.gravity = Gravity.CENTER;
            window.setAttributes(wlp);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        TextView tvHistory = dialog.findViewById(R.id.dialog_history_text);
        Button btnToggle = dialog.findViewById(R.id.dialog_history_toggle_act);

        tvHistory.setText(RegRecordManager.formatHistory(history, false));

        btnToggle.setOnClickListener(v -> {
            showAct[0] = !showAct[0];
            tvHistory.setText(RegRecordManager.formatHistory(history, showAct[0]));
            btnToggle.setText(showAct[0] ? "隐藏激活码" : "显示激活码");
        });

        dialog.findViewById(R.id.dialog_history_btn_close).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ==================== Adapter ====================

    private class RecordAdapter extends RecyclerView.Adapter<RecordAdapter.VH> {

        private List<RegRecordManager.Record> items = new ArrayList<>();

        void setRecords(List<RegRecordManager.Record> list) {
            items = list != null ? list : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_record, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int pos) {
            RegRecordManager.Record r = items.get(pos);
            holder.bind(r);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvDeviceId, tvPackageName, tvRegTime, tvValidDays, tvExpiry, tvActCode;
            View actToggle;
            TextView actToggleLabel;

            VH(View v) {
                super(v);
                tvDeviceId = v.findViewById(R.id.item_device_id);
                tvPackageName = v.findViewById(R.id.item_package_name);
                tvRegTime = v.findViewById(R.id.item_reg_time);
                tvValidDays = v.findViewById(R.id.item_valid_days);
                tvExpiry = v.findViewById(R.id.item_expiry);
                tvActCode = v.findViewById(R.id.item_activation_code);
                actToggle = v.findViewById(R.id.item_act_toggle);
                actToggleLabel = v.findViewById(R.id.item_act_toggle_label);
            }

            void bind(RegRecordManager.Record r) {
                tvDeviceId.setText(r.deviceId);

                // 包名（有则显示）
                if (r.packageName != null && !r.packageName.isEmpty()) {
                    tvPackageName.setText(r.packageName);
                    tvPackageName.setVisibility(View.VISIBLE);
                } else {
                    tvPackageName.setVisibility(View.GONE);
                }

                tvRegTime.setText(r.regAt);
                tvValidDays.setText(r.validDays == 0 ? "永久" : r.validDays + "天");
                tvExpiry.setText(r.expiryDate);

                // 激活码默认折叠
                tvActCode.setText(r.activationCode);
                tvActCode.setVisibility(View.GONE);
                actToggleLabel.setText("激活码 ▶");
                actToggle.setOnClickListener(vi -> {
                    if (tvActCode.getVisibility() == View.GONE) {
                        tvActCode.setVisibility(View.VISIBLE);
                        actToggleLabel.setText("激活码 ▼");
                    } else {
                        tvActCode.setVisibility(View.GONE);
                        actToggleLabel.setText("激活码 ▶");
                    }
                });

                // 点击 → 详情
                itemView.setOnClickListener(vi -> showDetail(r));
            }
        }
    }
}
