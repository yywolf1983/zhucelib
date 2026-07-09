package com.keygen.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
        new AlertDialog.Builder(this)
                .setTitle("注册详情")
                .setMessage(r.toString())
                .setPositiveButton("关闭", null)
                .setNegativeButton("删除此条", (d, w) -> confirmDeleteSingle(r))
                .setNeutralButton("删除此设备全部", (d, w) -> confirmDeleteDevice(r))
                .show();
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
            TextView tvDeviceId, tvRegTime, tvValidDays, tvExpiry;

            VH(View v) {
                super(v);
                tvDeviceId = v.findViewById(R.id.item_device_id);
                tvRegTime = v.findViewById(R.id.item_reg_time);
                tvValidDays = v.findViewById(R.id.item_valid_days);
                tvExpiry = v.findViewById(R.id.item_expiry);
            }

            void bind(RegRecordManager.Record r) {
                tvDeviceId.setText(r.deviceId);
                tvRegTime.setText(r.regAt);
                tvValidDays.setText(r.validDays == 0 ? "永久" : r.validDays + "天");
                tvExpiry.setText(r.expiryDate);

                // 点击 → 详情
                itemView.setOnClickListener(vi -> showDetail(r));
            }
        }
    }
}
