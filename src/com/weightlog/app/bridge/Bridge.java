package com.weightlog.app.bridge;

import android.app.Activity;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * JS ↔ Native 桥（页面里是 window.WeightLog）。
 *
 * 约定：
 *   - 查询类方法同步返回 JSON 字符串；
 *   - 扫描/称重结果由原生主动调用 window.__wlEvent__(type, json) 推送；
 *   - bind key / token 永远不出现在返回值里（只回 bindKeySet / tokenSet）。
 */
public class Bridge {

    public interface Host {
        String stateJson();

        String listJson(int days);

        String addManual(String json);

        String updateNote(long id, String note);

        String deleteRecord(long id);

        String clearAll();

        String saveProfile(String json);

        String saveDevice(String json);

        /** 恢复构建时内置的预绑定秤（设备页「恢复预绑定」）。 */
        String applyPresetDevice();

        String clearDeviceData();

        void startScan(String mode);

        void stopScan();

        String candidatesJson();

        String exportData(String format);

        String seedDemo();

        /** 只删 source='demo' 的记录（不动真实记录）。 */
        String clearDemo();

        /** JS 称重层打开/关闭：true 时扫描用 LOW_LATENCY，false 时回 BALANCED。 */
        void setLiveMode(boolean live);

        void openSettings(String target);

        void setOnboarded(boolean value);
    }

    private static final String TAG = "WeightLog";

    private final Activity activity;
    private final Host host;

    public Bridge(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    // ========== 状态 / 记录 ==========

    @JavascriptInterface
    public String state() {
        try {
            return host.stateJson();
        } catch (Exception e) {
            Log.e(TAG, "state failed", e);
            return "{\"error\":\"state_failed\"}";
        }
    }

    @JavascriptInterface
    public String list(int days) {
        try {
            return host.listJson(days);
        } catch (Exception e) {
            Log.e(TAG, "list failed", e);
            return "[]";
        }
    }

    @JavascriptInterface
    public String addManual(String json) {
        try {
            return host.addManual(json);
        } catch (Exception e) {
            Log.e(TAG, "addManual failed", e);
            return "{\"ok\":false,\"error\":\"manual_failed\"}";
        }
    }

    @JavascriptInterface
    public String updateNote(long id, String note) {
        try {
            return host.updateNote(id, note);
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    @JavascriptInterface
    public String remove(long id) {
        try {
            return host.deleteRecord(id);
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    @JavascriptInterface
    public String clearAll() {
        try {
            return host.clearAll();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    // ========== 档案 / 设备 ==========

    @JavascriptInterface
    public String saveProfile(String json) {
        try {
            return host.saveProfile(json);
        } catch (Exception e) {
            Log.e(TAG, "saveProfile failed", e);
            return "{\"ok\":false}";
        }
    }

    @JavascriptInterface
    public String saveDevice(String json) {
        try {
            return host.saveDevice(json);
        } catch (Exception e) {
            Log.e(TAG, "saveDevice failed", e);
            return "{\"ok\":false}";
        }
    }

    @JavascriptInterface
    public String applyPresetDevice() {
        try {
            return host.applyPresetDevice();
        } catch (Exception e) {
            Log.e(TAG, "applyPresetDevice failed", e);
            return "{\"ok\":false,\"error\":\"preset_failed\"}";
        }
    }

    @JavascriptInterface
    public String clearDevice() {
        try {
            return host.clearDeviceData();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    @JavascriptInterface
    public void setOnboarded(boolean value) {
        try {
            host.setOnboarded(value);
        } catch (Exception e) {
            // ignore
        }
    }

    // ========== 扫描 ==========

    @JavascriptInterface
    public void startScan(String mode) {
        host.startScan(mode);
    }

    @JavascriptInterface
    public void stopScan() {
        host.stopScan();
    }

    @JavascriptInterface
    public String candidates() {
        try {
            return host.candidatesJson();
        } catch (Exception e) {
            return "[]";
        }
    }

    // ========== 导出 / 工具 ==========

    @JavascriptInterface
    public String exportData(String format) {
        try {
            return host.exportData(format);
        } catch (Exception e) {
            Log.e(TAG, "export failed", e);
            return "{\"ok\":false,\"error\":\"export_failed\"}";
        }
    }

    @JavascriptInterface
    public String seedDemo() {
        try {
            return host.seedDemo();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    @JavascriptInterface
    public String clearDemo() {
        try {
            return host.clearDemo();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    @JavascriptInterface
    public void setLiveMode(boolean live) {
        try {
            host.setLiveMode(live);
        } catch (Exception e) {
            // ignore
        }
    }

    @JavascriptInterface
    public void openSettings(String target) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                host.openSettings(target == null ? "" : target);
            }
        });
    }

    /** 原生 → JS 事件推送（UI 线程调用）。 */
    public static void push(Activity activity, final android.webkit.WebView webView,
                            final String type, final String payload) {
        if (activity == null || webView == null) {
            return;
        }
        final String typeJson = JSONObject.quote(type != null ? type : "");
        final String payloadJson = payload != null && payload.length() > 0 ? payload : "null";
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.evaluateJavascript(
                            "window.__wlEvent__&&window.__wlEvent__(" + typeJson + ","
                                    + payloadJson + ");", null);
                } catch (Exception e) {
                    Log.w(TAG, "push failed: " + type);
                }
            }
        });
    }
}
