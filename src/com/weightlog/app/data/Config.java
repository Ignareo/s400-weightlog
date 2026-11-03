package com.weightlog.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.weightlog.app.BuildConfig;
import com.weightlog.app.scale.BodyComposition;
import com.weightlog.app.scale.MiBeaconDecoder;

import org.json.JSONObject;

/**
 * App 配置（SharedPreferences，文件 weightlog_config）：
 *   device_v1  = {"mac","bindKey","token","name"}
 *   profile_v1 = {"name","heightCm","age","sex","targetWeightKg","targetSetAtKg","targetSetAtMs"}
 *
 * 预绑定：build.sh 从 scale/.env 读 S400_MAC / S400_BLE_KEY 写进 BuildConfig；
 * 首次启动（或设备段缺失 / 缺 key）时自动落成 device_v1，不用再扫描添加。
 * 用户主动「解除绑定」后写 preset_off_v1 抑制自动预绑定，直到点「恢复预绑定」。
 *
 * bindKey 是广播解密用的 16 字节密钥（32 hex），只在 App 私有目录里；
 * 不打印、不导出、不进日志。
 */
public class Config {

    private static final String TAG = "WeightLog";
    private static final String PREFS = "weightlog_config";
    private static final String K_DEVICE = "device_v1";
    private static final String K_PROFILE = "profile_v1";
    private static final String K_ONBOARDED = "onboarded_v1";
    /** 用户主动解绑后抑制自动预绑定（点「恢复预绑定」会清掉） */
    private static final String K_PRESET_OFF = "preset_off_v1";

    /** 构建时注入的预绑定秤（空 = 这个 APK 没有预绑定） */
    public static final String PRESET_MAC = BuildConfig.PRESET_SCALE_MAC != null
            ? MiBeaconDecoder.normalizeMac(BuildConfig.PRESET_SCALE_MAC) : "";
    public static final String PRESET_BIND_KEY = BuildConfig.PRESET_SCALE_BIND_KEY != null
            ? normalizeHex(BuildConfig.PRESET_SCALE_BIND_KEY) : "";
    public static final String PRESET_NAME = sanitizeName(BuildConfig.PRESET_SCALE_NAME);

    /** 名称类字段的最大长度（字符） */
    public static final int MAX_NAME_LEN = 40;
    /** 超过这个原始长度的名称直接拒绝写入（防异常输入） */
    public static final int MAX_RAW_NAME_LEN = 200;

    private final Context context;
    // 这些字段会被 UI 线程 / JavaBridge 线程写、被 BLE 扫描线程读，全部 volatile 保证可见性
    private volatile String mac = "";
    private volatile String bindKey = "";
    private volatile String token = "";
    private volatile String deviceName = "Xiaomi Scale S400";
    private volatile String profileName = "";
    private volatile int heightCm;
    private volatile int age;
    private volatile String sex = "male";
    private volatile double targetWeightKg;
    /** 设目标那一次的体重（0 = 没有记录，JS 回退到最早一条记录） */
    private volatile double targetSetAtKg;
    /** 设目标的时间戳（0 = 从未设置） */
    private volatile long targetSetAtMs;
    private volatile boolean onboarded;
    /** 只在 reload() 内用，防止 saveDevice → reload 递归套娃 */
    private boolean applyingPreset;

    public Config(Context context) {
        this.context = context.getApplicationContext();
        reload();
    }

    /** 重新从 SharedPreferences 读取（保存后调用）。 */
    public void reload() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            onboarded = prefs.getBoolean(K_ONBOARDED, false);
            String deviceJson = prefs.getString(K_DEVICE, "");
            if (deviceJson != null && deviceJson.length() > 0) {
                JSONObject o = new JSONObject(deviceJson);
                mac = MiBeaconDecoder.normalizeMac(o.optString("mac", ""));
                bindKey = normalizeHex(o.optString("bindKey", ""));
                token = normalizeHex(o.optString("token", ""));
                String name = o.optString("name", "");
                if (name.length() > 0 && !"null".equals(name)) {
                    String clean = sanitizeName(name);
                    if (clean.length() > 0) {
                        deviceName = clean;
                    }
                }
            }
            String profileJson = prefs.getString(K_PROFILE, "");
            if (profileJson != null && profileJson.length() > 0) {
                JSONObject o = new JSONObject(profileJson);
                String name = o.optString("name", "");
                profileName = "null".equals(name) ? "" : sanitizeName(name);
                heightCm = o.optInt("heightCm", 0);
                age = o.optInt("age", 0);
                sex = BodyComposition.normalizeSex(o.optString("sex", "male"));
                targetWeightKg = o.optDouble("targetWeightKg", 0);
                targetSetAtKg = o.optDouble("targetSetAtKg", 0);
                targetSetAtMs = o.optLong("targetSetAtMs", 0);
            }
        } catch (Exception e) {
            // 不打印 json（可能含 bind key）
            Log.w(TAG, "config parse failed");
        }
        maybeApplyPreset();
    }

    // ========== 预绑定 ==========

    /** 这个 APK 是否内置了可用的预绑定秤（MAC 合法 + bind key 合法）。 */
    public static boolean hasPreset() {
        return MiBeaconDecoder.macToBytes(PRESET_MAC) != null
                && isValidBindKey(PRESET_BIND_KEY);
    }

    /** 用户是否主动解绑过（抑制自动预绑定）。 */
    public boolean isPresetSuppressed() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(K_PRESET_OFF, false);
    }

    /**
     * 首次启动 / 设备段缺失 / 缺 key 时自动落预绑定。
     * 已有合法设备配置时不覆盖（尊重用户改过的设备）；主动解绑过也不覆盖。
     */
    private void maybeApplyPreset() {
        if (applyingPreset || !hasPreset()) {
            return;
        }
        boolean missing = !hasMac() || !hasKey();
        if (!missing || isPresetSuppressed()) {
            return;
        }
        // 配的正是预绑定那台但缺 key 时补 key；配的是别的秤就不动
        if (hasMac() && !PRESET_MAC.equalsIgnoreCase(mac)) {
            return;
        }
        applyingPreset = true;
        try {
            if (saveDevice(PRESET_MAC, PRESET_BIND_KEY, "", PRESET_NAME)) {
                Log.i(TAG, "preset device applied mac=" + PRESET_MAC);
            }
        } finally {
            applyingPreset = false;
        }
    }

    /** 显式恢复预绑定（设备页「恢复预绑定」）：清掉抑制标记并重新落盘。 */
    public boolean applyPreset() {
        if (!hasPreset()) {
            return false;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(K_PRESET_OFF, false).apply();
        applyingPreset = true;
        try {
            return saveDevice(PRESET_MAC, PRESET_BIND_KEY, "", PRESET_NAME);
        } finally {
            applyingPreset = false;
        }
    }

    // ========== 设备 ==========

    public String getMac() {
        return mac;
    }

    public String getBindKey() {
        return bindKey;
    }

    public String getToken() {
        return token;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public boolean hasMac() {
        return MiBeaconDecoder.macToBytes(mac) != null;
    }

    public boolean hasKey() {
        return isValidBindKey(bindKey);
    }

    public byte[] getBindKeyBytes() {
        return bindKeyBytes(bindKey);
    }

    /** 设备配置 JSON：**不返回 bindKey / token 明文**，只回是否已设置。 */
    public JSONObject devicePublicJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("mac", mac);
            o.put("name", deviceName);
            o.put("bindKeySet", hasKey());
            o.put("tokenSet", isValidToken(token));
            // 预绑定信息（不含 key）：页面据此显示「已预绑定」和「恢复预绑定」
            o.put("preset", hasPreset());
            o.put("presetMac", hasPreset() ? PRESET_MAC : "");
            o.put("presetName", hasPreset() ? PRESET_NAME : "");
        } catch (Exception e) {
            // ignore
        }
        return o;
    }

    /** 保存设备配置；bindKey / token 传空字符串表示保持原值。 */
    public boolean saveDevice(String macIn, String bindKeyIn, String tokenIn, String nameIn) {
        try {
            String newMac = MiBeaconDecoder.normalizeMac(macIn != null ? macIn : "");
            String newKey = normalizeHex(bindKeyIn != null ? bindKeyIn : "");
            String newToken = normalizeHex(tokenIn != null ? tokenIn : "");
            if (!hasMacValue(newMac)) {
                return false;
            }
            if (nameIn != null && nameIn.length() > MAX_RAW_NAME_LEN) {
                // 过长的名称是异常输入，拒绝写入
                Log.w(TAG, "save device failed: name too long");
                return false;
            }
            String cleanName = sanitizeName(nameIn);
            if (newKey.length() == 0) {
                newKey = bindKey;
            }
            if (newToken.length() == 0) {
                newToken = token;
            }
            JSONObject o = new JSONObject();
            o.put("mac", newMac);
            o.put("bindKey", newKey);
            o.put("token", newToken);
            o.put("name", cleanName.length() > 0 ? cleanName : deviceName);
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putString(K_DEVICE, o.toString()).apply();
            reload();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "save device failed");
            return false;
        }
    }

    /** 解绑设备：只清设备段，历史记录保留；同时抑制自动预绑定，直到用户点「恢复预绑定」。 */
    public void clearDevice() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().remove(K_DEVICE).putBoolean(K_PRESET_OFF, true).apply();
        mac = "";
        bindKey = "";
        token = "";
        deviceName = "Xiaomi Scale S400";
    }

    // ========== 档案 ==========

    public String getProfileName() {
        return profileName;
    }

    public int getHeightCm() {
        return heightCm;
    }

    public int getAge() {
        return age;
    }

    public String getSex() {
        return sex;
    }

    public double getTargetWeightKg() {
        return targetWeightKg;
    }

    /** 设目标那一刻的体重（0 = 没有历史记录，前端回退到最早一条）。 */
    public double getTargetSetAtKg() {
        return targetSetAtKg;
    }

    /** 设目标的时间戳（0 = 从未设置）。 */
    public long getTargetSetAtMs() {
        return targetSetAtMs;
    }

    public boolean isProfileReady() {
        return heightCm > 0 && age > 0;
    }

    public JSONObject profileJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", profileName != null ? profileName : "");
            o.put("heightCm", heightCm);
            o.put("age", age);
            o.put("sex", sex);
            o.put("targetWeightKg", targetWeightKg);
            // 目标进度起点：设目标时的体重 / 时间（旧数据缺省 0，前端会回退到最早记录）
            o.put("targetSetAtKg", targetSetAtKg);
            o.put("targetSetAtMs", targetSetAtMs);
            o.put("ready", isProfileReady());
        } catch (Exception e) {
            // ignore
        }
        return o;
    }

    /**
     * 保存档案（目标进度起点沿用旧值）。
     *
     * 名称会去掉控制字符/换行并截断到 {@link #MAX_NAME_LEN} 字符；
     * 原始名称超过 {@link #MAX_RAW_NAME_LEN} 字符视为异常输入，直接拒绝（返回 false）。
     *
     * @return false 仅表示拒绝写入或写入失败（SharedPreferences 异常）；
     *         正常写入返回 true（不区分「是否有变化」）。
     */
    public boolean saveProfile(String name, int height, int ageIn, String sexIn, double target) {
        return saveProfile(name, height, ageIn, sexIn, target, targetSetAtKg, targetSetAtMs);
    }

    /**
     * 保存档案，并显式写入「设目标时的体重 / 时间」。
     * 目标值变化时由调用方（MainActivity）查最新记录后传入；目标没变就沿用旧值。
     */
    public boolean saveProfile(String name, int height, int ageIn, String sexIn, double target,
                               double targetSetAtKgIn, long targetSetAtMsIn) {
        try {
            if (name != null && name.length() > MAX_RAW_NAME_LEN) {
                Log.w(TAG, "save profile failed: name too long");
                return false;
            }
            JSONObject o = new JSONObject();
            o.put("name", sanitizeName(name));
            o.put("heightCm", Math.max(0, Math.min(250, height)));
            o.put("age", Math.max(0, Math.min(120, ageIn)));
            o.put("sex", BodyComposition.normalizeSex(sexIn));
            o.put("targetWeightKg", Math.max(0, Math.min(300, target)));
            o.put("targetSetAtKg", Math.max(0, Math.min(400, targetSetAtKgIn)));
            o.put("targetSetAtMs", Math.max(0L, targetSetAtMsIn));
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putString(K_PROFILE, o.toString()).apply();
            reload();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "save profile failed");
            return false;
        }
    }

    // ========== 首次引导 ==========

    public boolean isOnboarded() {
        return onboarded;
    }

    public void setOnboarded(boolean value) {
        onboarded = value;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(K_ONBOARDED, value).apply();
    }

    // ========== 工具 ==========

    /**
     * 清洗名称：去掉控制字符（含换行/Tab）与首尾空白，并截断到 {@link #MAX_NAME_LEN} 字符。
     * 只用于展示类名称，不用于 mac / bind key 等十六进制字段。
     */
    public static String sanitizeName(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(value.length(), MAX_NAME_LEN));
        for (int i = 0; i < value.length() && sb.length() < MAX_NAME_LEN; i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    public static String normalizeHex(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    public static boolean isValidBindKey(String value) {
        return isHex(value, 32);
    }

    public static boolean isValidToken(String value) {
        return isHex(value, 24);
    }

    public static byte[] bindKeyBytes(String value) {
        return hexBytes(normalizeHex(value), 16);
    }

    private static boolean hasMacValue(String normalized) {
        return MiBeaconDecoder.macToBytes(normalized) != null;
    }

    private static boolean isHex(String value, int len) {
        if (value == null || value.length() != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static byte[] hexBytes(String normalized, int byteLen) {
        if (!isHex(normalized, byteLen * 2)) {
            return null;
        }
        byte[] out = new byte[byteLen];
        for (int i = 0; i < byteLen; i++) {
            out[i] = (byte) Integer.parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
