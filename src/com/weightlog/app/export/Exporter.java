package com.weightlog.app.export;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.weightlog.app.data.Config;
import com.weightlog.app.data.Measurement;
import com.weightlog.app.data.WeightDb;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 记录导出：CSV / JSON。
 *
 * Android 10+ 走 MediaStore 下载目录（无需存储权限，先写 IS_PENDING=1 再置 0）；
 * Android 9 及以下写公共下载目录（需要 WRITE_EXTERNAL_STORAGE，由 Activity 申请）。
 * 导出的文件不含 bind key / token。
 */
public final class Exporter {

    private static final String TAG = "WeightLog";
    private static final String DIR_NAME = "WeightLog";
    /** Excel 打开 UTF-8 CSV 需要 BOM，否则中文备注是乱码 */
    private static final String UTF8_BOM = "\uFEFF";

    private Exporter() {
    }

    public static JSONObject export(Context context, List<Measurement> list, Config config,
                                    String format) {
        JSONObject result = new JSONObject();
        try {
            boolean json = "json".equalsIgnoreCase(format);
            // 文件名精确到秒：API <= 28 时同一分钟内导出两次不会互相覆盖
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            String fileName = "weightlog-" + stamp + (json ? ".json" : ".csv");
            // BOM 只加给 CSV；JSON 必须是纯 UTF-8
            String text = json ? buildJson(list, config) : UTF8_BOM + buildCsv(list);
            byte[] content = text.getBytes("UTF-8");
            String mime = json ? "application/json" : "text/csv";

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME);
                // 先标记 pending，写完再清掉：失败时不会留下半成品
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                ContentResolver resolver = context.getContentResolver();
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    return fail(result, "无法创建下载文件");
                }
                OutputStream out = null;
                try {
                    out = resolver.openOutputStream(uri);
                    if (out == null) {
                        throw new IOException("openOutputStream returned null");
                    }
                    out.write(content);
                    out.flush();
                    out.close();
                    out = null;
                } catch (Exception e) {
                    if (out != null) {
                        try {
                            out.close();
                        } catch (Exception ignored) {
                            // ignore
                        }
                    }
                    // 写入失败：删掉刚插入的 pending 行，避免下载目录里留下空文件
                    try {
                        resolver.delete(uri, null, null);
                    } catch (Exception ignored) {
                        // ignore
                    }
                    Log.e(TAG, "export write failed", e);
                    return fail(result, "写入下载文件失败");
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                try {
                    resolver.update(uri, done, null, null);
                } catch (Exception e) {
                    Log.w(TAG, "clear pending failed");
                }
                result.put("ok", true);
                result.put("uri", uri.toString());
                result.put("path", "下载/" + DIR_NAME + "/" + fileName);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
                if (!dir.exists() && !dir.mkdirs()) {
                    return fail(result, "无法创建下载目录");
                }
                File file = new File(dir, fileName);
                FileOutputStream out = new FileOutputStream(file);
                try {
                    out.write(content);
                } finally {
                    out.close();
                }
                result.put("ok", true);
                result.put("path", "下载/" + DIR_NAME + "/" + fileName);
                result.put("uri", "");
            }
            result.put("count", list.size());
            result.put("format", json ? "json" : "csv");
            Log.i(TAG, "exported " + list.size() + " rows as " + (json ? "json" : "csv"));
        } catch (Exception e) {
            Log.e(TAG, "export failed", e);
            return fail(result, "导出失败：" + e.getClass().getSimpleName());
        }
        return result;
    }

    private static JSONObject fail(JSONObject result, String message) {
        try {
            result.put("ok", false);
            result.put("error", message);
        } catch (Exception e) {
            // ignore
        }
        return result;
    }

    private static String buildCsv(List<Measurement> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("measured_at,weight_kg,impedance_low_ohm,impedance_high_ohm,heart_rate,"
                + "body_fat_pct_est,bmi,bmr_kcal_est,source,note\n");
        for (int i = 0; i < list.size(); i++) {
            Measurement m = list.get(i);
            sb.append(csv(WeightDb.isoTime(m.measuredAtMs, m.tzOffsetMin)));
            sb.append(',').append(fmt(m.weightKg, 1));
            sb.append(',').append(m.impedanceLowOhm != null ? fmt(m.impedanceLowOhm.doubleValue(), 1) : "");
            sb.append(',').append(m.impedanceHighOhm != null ? fmt(m.impedanceHighOhm.doubleValue(), 1) : "");
            sb.append(',').append(m.heartRate != null ? String.valueOf(m.heartRate) : "");
            sb.append(',').append(m.bodyFatPct != null ? fmt(m.bodyFatPct.doubleValue(), 1) : "");
            sb.append(',').append(m.bmi != null ? fmt(m.bmi.doubleValue(), 1) : "");
            sb.append(',').append(m.bmrKcal != null ? String.valueOf(m.bmrKcal) : "");
            sb.append(',').append(csv(m.source != null ? m.source : ""));
            sb.append(',').append(csv(m.note != null ? m.note : ""));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String buildJson(List<Measurement> list, Config config) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "weightlog");
        root.put("exported_at", WeightDb.isoTime(System.currentTimeMillis(),
                WeightDb.currentTzOffsetMin()));
        root.put("count", list.size());
        if (config != null) {
            root.put("profile", config.profileJson());
        }
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            // 备份用完整序列化：带测量当时的档案快照与 created_at_ms，可还原历史估算
            arr.put(list.get(i).toBackupJson());
        }
        root.put("measurements", arr);
        return root.toString(2);
    }

    /**
     * CSV 单元格转义 + 公式注入防护。
     * 首字符是 = + - @ Tab CR 的单元格会被 Excel/Sheets 当公式执行，前面补一个单引号。
     */
    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (v.length() > 0) {
            char first = v.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@'
                    || first == '\t' || first == '\r') {
                v = "'" + v;
            }
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String fmt(double value, int digits) {
        return String.format(Locale.US, "%." + digits + "f", value);
    }
}
