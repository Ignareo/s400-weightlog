package com.weightlog.app.data;

import org.json.JSONObject;

/**
 * 一次称重记录（weight_measurement 表一行）。
 *
 * 体重、阻抗来自 S400 广播；体脂 / BMI / BMR 是按测量当时的档案估算的近似值，
 * 不是米家官方算法（UI 与导出都必须标注「估算」）。
 */
public class Measurement {

    public static final String SOURCE_BLE = "ble";
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_DEMO = "demo";

    public long id;
    public long measuredAtMs;
    public int tzOffsetMin;
    public int profileId = 1;
    public double weightKg;
    public Double impedanceLowOhm;
    public Double impedanceHighOhm;
    public Integer heartRate;
    public boolean stabilized = true;
    public Double bodyFatPct;
    public Double bmi;
    public Integer bmrKcal;
    public int calcVersion = 1;
    public Integer profileHeightCm;
    public Integer profileAge;
    public String profileSex;
    public String source = SOURCE_BLE;
    public String note;
    public String rawJson;
    public long createdAtMs;

    /** 供 JS / 导出使用；includeRaw=true 时附带原始广播。 */
    public JSONObject toJson(boolean includeRaw) {
        JSONObject o = new JSONObject();
        try {
            if (id > 0) {
                o.put("id", id);
            }
            o.put("measured_at_ms", measuredAtMs);
            o.put("measured_at", WeightDb.isoTime(measuredAtMs, tzOffsetMin));
            o.put("tz_offset_min", tzOffsetMin);
            o.put("profile_id", profileId);
            o.put("weight_kg", weightKg);
            o.put("impedance_low_ohm", jsonOrNull(impedanceLowOhm));
            o.put("impedance_high_ohm", jsonOrNull(impedanceHighOhm));
            o.put("heart_rate", jsonOrNull(heartRate));
            o.put("stabilized", stabilized);
            o.put("body_fat_pct", jsonOrNull(bodyFatPct));
            o.put("bmi", jsonOrNull(bmi));
            o.put("bmr_kcal", jsonOrNull(bmrKcal));
            o.put("calc_version", calcVersion);
            o.put("source", source != null ? source : SOURCE_BLE);
            o.put("note", note != null ? note : JSONObject.NULL);
            if (includeRaw && rawJson != null && rawJson.length() > 0) {
                o.put("raw_json", rawJson);
            }
        } catch (Exception e) {
            // JSONObject.put 理论上不会失败
        }
        return o;
    }

    /**
     * 导出/备份专用：在 {@link #toJson(boolean)}（含原始广播）基础上补上
     * 测量当时的档案快照与入库时间，保证备份能还原历史估算值的上下文。
     * 只有 JSON 备份用这个；state/list 仍用 toJson(false)，避免 UI 载荷变大。
     * 备份 schema 固定：没有原始广播时 raw_json 也显式为 null，方便导入方按 schema 解析。
     */
    public JSONObject toBackupJson() {
        JSONObject o = toJson(true);
        try {
            o.put("profile_height_cm", jsonOrNull(profileHeightCm));
            o.put("profile_age", jsonOrNull(profileAge));
            o.put("profile_sex", profileSex != null ? profileSex : JSONObject.NULL);
            o.put("created_at_ms", createdAtMs);
            o.put("raw_json", rawJson != null && rawJson.length() > 0 ? rawJson : JSONObject.NULL);
        } catch (Exception e) {
            // JSONObject.put 理论上不会失败
        }
        return o;
    }

    private static Object jsonOrNull(Object value) {
        return value != null ? value : JSONObject.NULL;
    }
}
