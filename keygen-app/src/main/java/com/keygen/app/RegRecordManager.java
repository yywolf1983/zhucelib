package com.keygen.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 注册记录管理器（每条注册 = 独立记录，不合并）。
 *
 * 功能:
 *   - 每次生成激活码追加一条新记录（同设备多次注册不合并）
 *   - 按安装码查询该设备所有历史记录
 *   - 按唯一 id 删除单条记录、按 deviceId 删除该设备全部记录
 *   - 支持自定义存储目录(SAF DocumentTree)，位置记住
 *   - 导出到用户指定位置
 */
final class RegRecordManager {

    private static final String TAG = "RegRecord";
    private static final String RECORDS_DIR = "RegGate";
    private static final String RECORDS_FILE = "reg_records.json";

    private static final String PREFS_NAME = "reggate_records_prefs";
    private static final String PREF_STORAGE_URI = "storage_tree_uri";
    private static final String PREF_USE_CUSTOM = "use_custom_storage";

    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    // JSON keys
    private static final String K_ID = "id";
    private static final String K_DEVICE_ID = "deviceId";
    private static final String K_REQUEST_CODE = "requestCode";
    private static final String K_PACKAGE_NAME = "packageName";
    private static final String K_VALID_DAYS = "validDays";
    private static final String K_EXPIRY_DATE = "expiryDate";
    private static final String K_REG_AT = "regAt";
    private static final String K_ACTIVATION_CODE = "activationCode";

    private RegRecordManager() {}

    // ==================== Data Class ====================

    static final class Record {
        long id;             // 唯一 ID（时间戳）
        String deviceId;     // 设备 ID hex
        String requestCode;  // 安装码（未分组）
        String packageName;  // 目标 App 包名
        int validDays;       // 购买天数，0=永久
        String expiryDate;   // 到期日
        String regAt;        // 注册时间
        String activationCode;// 生成的激活码

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("记录ID: ").append(id).append("\n");
            sb.append("设备ID: ").append(deviceId).append("\n");
            if (packageName != null && !packageName.isEmpty()) {
                sb.append("包名: ").append(packageName).append("\n");
            }
            sb.append("注册时间: ").append(regAt).append("\n");
            sb.append("购买时长: ").append(validDays == 0 ? "永久" : validDays + " 天").append("\n");
            sb.append("到期: ").append(expiryDate).append("\n");
            sb.append("激活码: ").append(activationCode);
            return sb.toString();
        }
    }

    // ==================== Storage Path ====================

    static boolean isCustomStorage(Context ctx) {
        return getPrefs(ctx).getBoolean(PREF_USE_CUSTOM, false);
    }

    @Nullable
    static Uri getCustomStorageUri(Context ctx) {
        String s = getPrefs(ctx).getString(PREF_STORAGE_URI, null);
        return s != null ? Uri.parse(s) : null;
    }

    static void setCustomStorageUri(Context ctx, Uri treeUri) {
        try {
            ctx.getContentResolver().takePersistableUriPermission(treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {}
        getPrefs(ctx).edit()
                .putString(PREF_STORAGE_URI, treeUri.toString())
                .putBoolean(PREF_USE_CUSTOM, true)
                .apply();
        Log.i(TAG, "自定义存储路径已设置: " + treeUri);
    }

    static void clearCustomStorage(Context ctx) {
        Uri old = getCustomStorageUri(ctx);
        if (old != null) {
            try { ctx.getContentResolver().releasePersistableUriPermission(old,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
        }
        getPrefs(ctx).edit()
                .remove(PREF_STORAGE_URI)
                .putBoolean(PREF_USE_CUSTOM, false)
                .apply();
    }

    static String getStorageDescription(Context ctx) {
        if (!isCustomStorage(ctx)) {
            File dir = new File(ctx.getExternalFilesDir(null), RECORDS_DIR);
            return "默认: " + dir.getAbsolutePath();
        }
        Uri uri = getCustomStorageUri(ctx);
        return uri != null ? "自定义: " + uri.getLastPathSegment() : "未知";
    }

    // ==================== Device ID & Package Name ====================

    @Nullable
    static String extractDeviceIdHex(String ungroupedRequestCode) {
        byte[] data = Base32.decode(ungroupedRequestCode);
        if (data == null || data.length < KeygenUtils.DEVICE_ID_LEN + KeygenUtils.NONCE_LEN)
            return null;
        byte[] deviceId = new byte[KeygenUtils.DEVICE_ID_LEN];
        System.arraycopy(data, 0, deviceId, 0, KeygenUtils.DEVICE_ID_LEN);
        return bytesToHex(deviceId);
    }

    /**
     * 从安装码中提取包名。无包名返回空字符串，解析失败返回 null。
     */
    @Nullable
    static String extractPackageNameFromRequest(String ungroupedRequestCode) {
        return KeygenUtils.extractPackageName(ungroupedRequestCode);
    }

    // ==================== CRUD ====================

    /**
     * 追加一条新注册记录（每次都新建，不合并）。
     */
    static void saveRecord(Context ctx, String rawRequestCode, int validDays,
                           String activationCode, String packageName) {
        String deviceId = extractDeviceIdHex(rawRequestCode);
        if (deviceId == null) {
            Log.w(TAG, "无法解析安装码,跳过记录保存");
            return;
        }

        List<Record> records = readRecords(ctx);
        String now = SDF.format(new java.util.Date());
        String expiry = validDays == 0 ? "永久"
                : KeygenUtils.formatExpiry(validDays);

        Record r = new Record();
        r.id = System.currentTimeMillis();
        r.deviceId = deviceId;
        r.requestCode = rawRequestCode;
        r.packageName = (packageName != null) ? packageName : "";
        r.validDays = validDays;
        r.expiryDate = expiry;
        r.regAt = now;
        r.activationCode = activationCode;
        records.add(r);

        writeRecords(ctx, records);
        Log.i(TAG, "注册记录已追加: deviceId=" + deviceId + ", id=" + r.id
                + ", pkg=" + r.packageName);
    }

    // ---- 激活码查重 & 覆盖 ----

    /**
     * 按激活码查找已有记录。不存在返回 null。
     */
    @Nullable
    static Record findByActivationCode(Context ctx, String activationCode) {
        if (activationCode == null || activationCode.isEmpty()) return null;
        for (Record r : readRecords(ctx)) {
            if (activationCode.equals(r.activationCode)) return r;
        }
        return null;
    }

    /**
     * 按激活码覆盖：删除旧记录（如存在），追加新记录。
     * 保证同一激活码只有一条记录。
     */
    static void upsertByActivationCode(Context ctx, String rawRequestCode, int validDays,
                                       String activationCode, String packageName) {
        String deviceId = extractDeviceIdHex(rawRequestCode);
        if (deviceId == null) {
            Log.w(TAG, "无法解析安装码,跳过记录保存");
            return;
        }

        List<Record> records = readRecords(ctx);

        // 删除同激活码的旧记录
        if (activationCode != null && !activationCode.isEmpty()) {
            Iterator<Record> it = records.iterator();
            while (it.hasNext()) {
                if (activationCode.equals(it.next().activationCode)) {
                    it.remove();
                    break;
                }
            }
        }

        String now = SDF.format(new java.util.Date());
        String expiry = validDays == 0 ? "永久"
                : KeygenUtils.formatExpiry(validDays);

        Record r = new Record();
        r.id = System.currentTimeMillis();
        r.deviceId = deviceId;
        r.requestCode = rawRequestCode;
        r.packageName = (packageName != null) ? packageName : "";
        r.validDays = validDays;
        r.expiryDate = expiry;
        r.regAt = now;
        r.activationCode = activationCode;
        records.add(r);

        writeRecords(ctx, records);
        Log.i(TAG, "注册记录已覆盖: deviceId=" + deviceId + ", id=" + r.id
                + ", pkg=" + r.packageName);
    }

    /** 读取所有注册记录。 */
    static List<Record> readRecords(Context ctx) {
        List<Record> records = new ArrayList<>();
        String json = readRecordsJson(ctx);
        if (json == null) return records;

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Record r = new Record();
                r.id = obj.optLong(K_ID, System.currentTimeMillis());
                r.deviceId = obj.optString(K_DEVICE_ID, "");
                r.requestCode = obj.optString(K_REQUEST_CODE, "");
                r.packageName = obj.optString(K_PACKAGE_NAME, "");
                r.validDays = obj.optInt(K_VALID_DAYS, 0);
                r.expiryDate = obj.optString(K_EXPIRY_DATE, "");
                r.regAt = obj.optString(K_REG_AT, "");
                r.activationCode = obj.optString(K_ACTIVATION_CODE, "");
                records.add(r);
            }
        } catch (Exception e) {
            Log.w(TAG, "解析注册记录失败", e);
        }
        return records;
    }

    // ---- 按安装码查询 ----

    /**
     * 按安装码查询该设备所有注册历史（返回列表，可能为空）。
     */
    static List<Record> queryHistoryByRequestCode(Context ctx, String ungroupedRequestCode) {
        String deviceId = extractDeviceIdHex(ungroupedRequestCode);
        if (deviceId == null) return new ArrayList<>();
        return queryHistoryByDeviceId(ctx, deviceId);
    }

    /** 按 deviceId 查询所有历史记录。 */
    static List<Record> queryHistoryByDeviceId(Context ctx, String deviceId) {
        List<Record> result = new ArrayList<>();
        for (Record r : readRecords(ctx)) {
            if (deviceId.equals(r.deviceId)) result.add(r);
        }
        return result;
    }

    /**
     * 按安装码查询该设备最新一条记录。
     * @return 最新记录，未注册返回 null。
     */
    @Nullable
    static Record queryLatestByRequestCode(Context ctx, String ungroupedRequestCode) {
        List<Record> history = queryHistoryByRequestCode(ctx, ungroupedRequestCode);
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    // ---- 删除 ----

    /** 按唯一 id 删除单条记录。 */
    static boolean deleteById(Context ctx, long recordId) {
        List<Record> records = readRecords(ctx);
        boolean removed = false;
        Iterator<Record> it = records.iterator();
        while (it.hasNext()) {
            if (it.next().id == recordId) {
                it.remove();
                removed = true;
                break;
            }
        }
        if (removed) {
            writeRecords(ctx, records);
            Log.i(TAG, "已删除单条记录: id=" + recordId);
        }
        return removed;
    }

    /** 按 deviceId 删除该设备全部注册记录。 */
    static int deleteByDeviceId(Context ctx, String deviceId) {
        List<Record> records = readRecords(ctx);
        int removed = 0;
        Iterator<Record> it = records.iterator();
        while (it.hasNext()) {
            if (deviceId.equals(it.next().deviceId)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            writeRecords(ctx, records);
            Log.i(TAG, "已删除设备全部记录: deviceId=" + deviceId + ", count=" + removed);
        }
        return removed;
    }

    // ---- 统计 ----

    /** 格式化多条历史记录为文本（默认不显示激活码）。 */
    static String formatHistory(List<Record> history) {
        return formatHistory(history, false);
    }

    /** 格式化多条历史记录为文本。 */
    static String formatHistory(List<Record> history, boolean showActivationCodes) {
        if (history == null || history.isEmpty()) return "(暂无记录)";
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(history.size()).append(" 次注册\n");
        sb.append("设备ID: ").append(history.get(0).deviceId).append("\n");
        String pkg = history.get(0).packageName;
        if (pkg != null && !pkg.isEmpty()) {
            sb.append("包名: ").append(pkg).append("\n");
        }
        sb.append("——————————————\n");
        for (int i = 0; i < history.size(); i++) {
            Record r = history.get(i);
            if (i > 0) sb.append("——————————————\n");
            sb.append("第 ").append(i + 1).append(" 次注册\n");
            sb.append("时间: ").append(r.regAt).append("\n");
            sb.append("时长: ").append(r.validDays == 0 ? "永久" : r.validDays + " 天").append("\n");
            sb.append("到期: ").append(r.expiryDate).append("\n");
            if (showActivationCodes) {
                sb.append("激活码: ").append(r.activationCode).append("\n");
            }
        }
        return sb.toString();
    }

    /** 格式化所有记录为可读文本（兼容旧接口）。 */
    static String formatRecords(List<Record> records) {
        if (records == null || records.isEmpty()) return "(暂无注册记录)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) sb.append("\n——————————————\n\n");
            sb.append("【记录 ").append(i + 1).append("】\n");
            sb.append(records.get(i).toString());
        }
        return sb.toString();
    }

    /** 返回记录总数。 */
    static int getRecordCount(Context ctx) {
        return readRecords(ctx).size();
    }

    /** 返回不重复设备数。 */
    static int getUniqueDeviceCount(Context ctx) {
        List<String> ids = new ArrayList<>();
        for (Record r : readRecords(ctx)) {
            if (!ids.contains(r.deviceId)) ids.add(r.deviceId);
        }
        return ids.size();
    }

    // ==================== I/O ====================

    static void exportRecords(Context ctx, Uri targetUri) throws Exception {
        String json = readRecordsJson(ctx);
        if (json == null) throw new IllegalStateException("暂无注册记录可导出");

        try (OutputStream os = ctx.getContentResolver().openOutputStream(targetUri)) {
            if (os == null) throw new IllegalStateException("无法打开输出流");
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---------- internal I/O ----------

    @Nullable
    private static String readRecordsJson(Context ctx) {
        if (isCustomStorage(ctx)) {
            Uri treeUri = getCustomStorageUri(ctx);
            if (treeUri == null) return null;
            return readJsonFromSaf(ctx, treeUri);
        }
        return readJsonFromFile(ctx);
    }

    private static void writeRecords(Context ctx, List<Record> records) {
        JSONArray arr = new JSONArray();
        try {
            for (Record r : records) {
                JSONObject obj = new JSONObject();
                obj.put(K_ID, r.id);
                obj.put(K_DEVICE_ID, r.deviceId);
                obj.put(K_REQUEST_CODE, r.requestCode);
                obj.put(K_PACKAGE_NAME, r.packageName != null ? r.packageName : "");
                obj.put(K_VALID_DAYS, r.validDays);
                obj.put(K_EXPIRY_DATE, r.expiryDate);
                obj.put(K_REG_AT, r.regAt);
                obj.put(K_ACTIVATION_CODE, r.activationCode);
                arr.put(obj);
            }
        } catch (Exception e) {
            Log.w(TAG, "序列化记录失败", e);
            return;
        }

        String json;
        try {
            json = arr.toString(2);
        } catch (Exception e) {
            Log.w(TAG, "JSON toString 失败", e);
            return;
        }

        if (isCustomStorage(ctx)) {
            Uri treeUri = getCustomStorageUri(ctx);
            if (treeUri != null) writeJsonToSaf(ctx, treeUri, json);
        } else {
            writeJsonToFile(ctx, json);
        }
    }

    // -- Default file-based (externalFilesDir) --

    private static File getRecordsFile(Context ctx) {
        File dir = new File(ctx.getExternalFilesDir(null), RECORDS_DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, RECORDS_FILE);
    }

    @Nullable
    private static String readJsonFromFile(Context ctx) {
        File file = getRecordsFile(ctx);
        if (!file.exists()) return null;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "读取记录文件失败", e);
            return null;
        }
    }

    private static void writeJsonToFile(Context ctx, String json) {
        File file = getRecordsFile(ctx);
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(json);
        } catch (Exception e) {
            Log.w(TAG, "写入记录文件失败", e);
        }
    }

    // -- SAF-based (user-picked directory via ACTION_OPEN_DOCUMENT_TREE) --

    @Nullable
    private static String readJsonFromSaf(Context ctx, Uri treeUri) {
        try {
            DocumentFile root = DocumentFile.fromTreeUri(ctx, treeUri);
            DocumentFile dir = findOrCreateDir(root, RECORDS_DIR);
            if (dir == null) return null;
            DocumentFile file = dir.findFile(RECORDS_FILE);
            if (file == null || !file.exists()) return null;
            try (InputStream is = ctx.getContentResolver().openInputStream(file.getUri());
                 BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "SAF 读取记录失败", e);
            return null;
        }
    }

    private static void writeJsonToSaf(Context ctx, Uri treeUri, String json) {
        try {
            DocumentFile root = DocumentFile.fromTreeUri(ctx, treeUri);
            DocumentFile dir = findOrCreateDir(root, RECORDS_DIR);
            if (dir == null) {
                Log.w(TAG, "SAF 目录不可用");
                return;
            }
            DocumentFile file = dir.findFile(RECORDS_FILE);
            if (file != null && file.exists()) {
                file.delete();
            }
            file = dir.createFile("application/json", RECORDS_FILE);
            if (file == null) {
                Log.w(TAG, "SAF 创建文件失败");
                return;
            }
            try (OutputStream os = ctx.getContentResolver().openOutputStream(file.getUri())) {
                if (os != null) os.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "SAF 写入记录失败", e);
        }
    }

    @Nullable
    private static DocumentFile findOrCreateDir(DocumentFile parent, String name) {
        DocumentFile dir = parent.findFile(name);
        if (dir != null && dir.isDirectory()) return dir;
        dir = parent.createDirectory(name);
        return (dir != null && dir.isDirectory()) ? dir : null;
    }

    // ==================== Utils ====================

    private static SharedPreferences getPrefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }
}
