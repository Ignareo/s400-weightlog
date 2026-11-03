package com.weightlog.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.weightlog.app.bridge.Bridge;
import com.weightlog.app.data.Config;
import com.weightlog.app.data.Measurement;
import com.weightlog.app.data.WeightDb;
import com.weightlog.app.export.Exporter;
import com.weightlog.app.scale.BleScaleScanner;
import com.weightlog.app.scale.BodyComposition;
import com.weightlog.app.scale.MiBeaconDecoder;
import com.weightlog.app.scale.RescuePolicy;
import com.weightlog.app.scale.WeighSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 体重记：手机端 S400 体重秤直读 + 本地记录 + 趋势 + 导出。
 *
 * 数据流：
 *   BLE 广播（BleScaleScanner）→ MiBeacon 解密（MiBeaconDecoder）
 *   → 会话合并（WeighSession）→ 体成分估算（BodyComposition）
 *   → SQLite（WeightDb）→ JS（window.__wlEvent__）→ 页面 UI
 *
 * 页面是内置 assets/www（file:///android_asset/www/index.html），
 * 手机 UI 全部在网页里；原生只负责蓝牙、数据库、权限、导出。
 */
public class MainActivity extends Activity implements Bridge.Host, BleScaleScanner.ConfigProvider {

    private static final String TAG = "WeightLog";
    /** 版本号唯一来源：build.sh 生成 BuildConfig（manifest 同源） */
    private static final String START_URL = "file:///android_asset/www/index.html";
    /** 调试用：开启后可以用 chrome://inspect / CDP 连 WebView；发布版保持 false */
    private static final boolean DEBUG_WEBVIEW = false;

    private static final int REQ_SCAN = 2001;
    private static final int REQ_EXPORT = 2002;

    /** 数据停了多久后收尾落盘（心率/高频阻抗不齐也保存） */
    private static final long QUIET_FINALIZE_MS = 3000L;
    private static final long QUIET_CHECK_MS = 400L;
    /** 扫描报错去抖，避免刷屏 */
    private static final long ERROR_THROTTLE_MS = 4000L;
    /** 超时兜底：重新广播发现窗口（这期间扫附近所有小米秤，用 bind key 试解密认秤） */
    private static final long RESCUE_WINDOW_MS = 20000L;
    /** 超时兜底的检查周期 */
    private static final long RESCUE_CHECK_MS = 5000L;

    private WebView webView;
    private Bridge bridge;
    private WeightDb db;
    private Config config;
    private BleScaleScanner scanner;
    private WeighSession session;
    private ExecutorService io;
    private Handler ui;

    /** 超时后重新广播发现的判定（纯 Java，可单测） */
    private final RescuePolicy rescuePolicy = new RescuePolicy();
    /** 当前扫描模式（null = 没在扫），用于在进入 BIND 时复位兜底计时 */
    private volatile BleScaleScanner.Mode observedMode;
    /** 是否正在「重新广播发现」窗口里 */
    private volatile boolean rescueActive;
    private boolean rescueCheckScheduled;

    private volatile boolean discoverMode;
    /** 等授权时记住用户本来要启动的扫描模式（授权回调据此继续，避免首用走错模式） */
    private volatile String pendingScanMode;
    /** 是否在前台；Bridge 线程排队中的扫描重启要据此放弃，避免退后台后又起扫 */
    private volatile boolean resumed;
    private boolean scanPermissionRequested;
    private volatile boolean quietCheckScheduled;
    private volatile long lastErrorAt;
    private volatile String lastErrorCode = "";
    private volatile String lastErrorMessage = "";
    private volatile long lastCandidatesPushAt;
    private volatile String pendingExportFormat;
    /** 主页面加载失败后只自动重试一次，避免死循环 */
    private boolean startPageReloadTried;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new Config(this);
        db = new WeightDb(this);
        io = Executors.newSingleThreadExecutor();
        ui = new Handler(Looper.getMainLooper());

        setupWindow();

        session = createSession();
        scanner = createScanner();

        bridge = new Bridge(this, this);
        try {
            // 显式关闭：避免某些系统/调试镜像默认把 WebView 调试端口暴露出去
            WebView.setWebContentsDebuggingEnabled(DEBUG_WEBVIEW);
        } catch (Exception e) {
            // 老系统没有这个开关
        }
        webView = new WebView(this);
        setupWebView(webView);
        setContentView(webView);
        webView.loadUrl("file:///android_asset/www/index.html");

        Log.i(TAG, "app start version=" + BuildConfig.VERSION_NAME
                + " hasMac=" + config.hasMac() + " hasKey=" + config.hasKey()
                + " preset=" + (Config.hasPreset() ? Config.PRESET_MAC : "none"));
    }

    private void setupWindow() {
        Window window = getWindow();
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.parseColor("#F6F7FB"));
            if (Build.VERSION.SDK_INT >= 26) {
                // Android 8+ 才支持深色导航栏图标；白色导航栏 + 白色图标在旧系统上看不见
                window.setNavigationBarColor(Color.parseColor("#FFFFFF"));
            }
            if (Build.VERSION.SDK_INT >= 23) {
                View decor = window.getDecorView();
                int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                decor.setSystemUiVisibility(flags);
            }
        } catch (Exception e) {
            Log.w(TAG, "window setup failed");
        }
    }

    private void setupWebView(WebView view) {
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        try {
            s.setAllowFileAccessFromFileURLs(false);
            s.setAllowUniversalAccessFromFileURLs(false);
        } catch (Exception e) {
            // 老系统没有这两个开关
        }
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        // 跟随系统字体大小（老 WebView 不认系统缩放，手动换算）
        int zoom = Math.round(100f * getResources().getConfiguration().fontScale);
        s.setTextZoom(zoom > 0 ? zoom : 100);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        view.setBackgroundColor(Color.parseColor("#F6F7FB"));
        view.addJavascriptInterface(bridge, "WeightLog");

        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                startPageReloadTried = false;
                pushState("state");
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // 渲染进程崩溃（低内存常见）：销毁死掉的 WebView，重建一个新的，别让整个 App 被杀
                Log.e(TAG, "webview render process gone");
                if (view != null) {
                    try {
                        view.removeJavascriptInterface("WeightLog");
                    } catch (Exception e) {
                        // ignore
                    }
                    try {
                        view.destroy();
                    } catch (Exception e) {
                        // ignore
                    }
                }
                if (webView == view) {
                    webView = null;
                }
                recreateWebView();
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                boolean mainFrame = request != null && request.isForMainFrame();
                int code = error != null ? error.getErrorCode() : -1;
                Log.w(TAG, "webview load error mainFrame=" + mainFrame
                        + " code=" + code + " url="
                        + (request != null && request.getUrl() != null ? request.getUrl() : "?"));
                if (mainFrame && view != null && !startPageReloadTried) {
                    // 主页面加载失败：只重试一次
                    startPageReloadTried = true;
                    try {
                        view.loadUrl(START_URL);
                    } catch (Exception e) {
                        Log.w(TAG, "webview reload failed");
                    }
                }
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                if (msg != null) {
                    Log.i(TAG, "console " + msg.message());
                }
                return true;
            }
        });
    }

    private WeighSession createSession() {
        return new WeighSession(new WeighSession.Listener() {
            @Override
            public void onLive(WeighSession.LiveData live) {
                if (live == null) {
                    return;
                }
                Bridge.push(MainActivity.this, webView, "live", liveJson(live));
                scheduleQuietCheck();
            }

            @Override
            public void onFinal(Measurement measurement) {
                cancelQuietCheck();
                saveMeasurement(measurement);
            }

            @Override
            public void onSessionError(String message) {
                Log.w(TAG, "session: " + message);
            }
        });
    }

    /** 渲染进程崩溃 / WebView 不可用后重建一个干净的 WebView。 */
    private void recreateWebView() {
        try {
            WebView fresh = new WebView(this);
            setupWebView(fresh);
            webView = fresh;
            setContentView(fresh);
            startPageReloadTried = false;
            fresh.loadUrl(START_URL);
            Log.i(TAG, "webview recreated");
            // 页面重载后 JS 的发现扫描定时器已经消失：清掉 discoverMode 并按配置重新起 BIND 扫描，
            // 否则用户回前台后设备页显示 discover、却再也不会自动扫描。
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    discoverMode = false;
                    if (resumed && config.hasMac() && config.hasKey() && hasScanPermission()
                            && scanner != null) {
                        scanner.start(BleScaleScanner.Mode.BIND);
                    }
                    pushState("state");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "webview recreate failed", e);
            finish();
        }
    }

    /**
     * live 事件载荷：WeighSession 的实时数据 + 原生体成分估算。
     * JS 不再自己算体脂，所以 bmi / body_fat_pct / bmr_kcal / profile_ready 每次都必须出现
     * （算不出来时为 JSON null）。
     */
    private String liveJson(WeighSession.LiveData live) {
        JSONObject o = live.toJson();
        try {
            int heightCm = config.getHeightCm();
            int age = config.getAge();
            String sex = config.getSex();
            boolean ready = config.isProfileReady();
            Double bmi = heightCm > 0
                    ? BodyComposition.bmi(live.weightKg, heightCm) : null;
            o.put("bmi", bmi != null ? bmi : JSONObject.NULL);
            Double impedance = live.impedanceLowOhm != null
                    ? live.impedanceLowOhm : live.impedanceHighOhm;
            Double bodyFat = null;
            if (impedance != null && ready) {
                bodyFat = BodyComposition.bodyFatPct(live.weightKg,
                        impedance.doubleValue(), heightCm, age, sex);
            }
            o.put("body_fat_pct", bodyFat != null ? bodyFat : JSONObject.NULL);
            Integer bmr = ready
                    ? BodyComposition.bmrKcal(live.weightKg, heightCm, age, sex) : null;
            o.put("bmr_kcal", bmr != null ? bmr : JSONObject.NULL);
            o.put("profile_ready", ready);
        } catch (Exception e) {
            // 兜底：字段必须存在
            try {
                if (!o.has("bmi")) {
                    o.put("bmi", JSONObject.NULL);
                }
                if (!o.has("body_fat_pct")) {
                    o.put("body_fat_pct", JSONObject.NULL);
                }
                if (!o.has("bmr_kcal")) {
                    o.put("bmr_kcal", JSONObject.NULL);
                }
                o.put("profile_ready", false);
            } catch (Exception ignored) {
                // ignore
            }
        }
        return o.toString();
    }

    // ========== 超时兜底：重新广播发现 + 自动纠正地址 ==========

    private final Runnable rescueCheckRunnable = new Runnable() {
        @Override
        public void run() {
            rescueCheckScheduled = false;
            BleScaleScanner s = scanner;
            if (s == null || !s.isScanning() || s.getMode() != BleScaleScanner.Mode.BIND) {
                return;
            }
            if (rescueActive) {
                scheduleRescueCheck();
                return;
            }
            if (rescuePolicy.shouldRescue(System.currentTimeMillis())) {
                enterRescue("timeout_or_decode_failed");
            } else {
                scheduleRescueCheck();
            }
        }
    };

    private final Runnable rescueTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            finishRescue(false);
        }
    };

    /** 只在 BIND 模式扫描时周期检查；其它模式（发现 / 兜底）不计时。 */
    private void scheduleRescueCheck() {
        if (rescueCheckScheduled || ui == null) {
            return;
        }
        rescueCheckScheduled = true;
        ui.postDelayed(rescueCheckRunnable, RESCUE_CHECK_MS);
    }

    private void cancelRescueCheck() {
        rescueCheckScheduled = false;
        if (ui != null) {
            ui.removeCallbacks(rescueCheckRunnable);
        }
    }

    /** 扫描模式变化：进入 BIND 重新计时；离开 BIND 停止计时。线程：任意（切到 UI 线程执行）。 */
    private void syncRescueMode() {
        BleScaleScanner s = scanner;
        final BleScaleScanner.Mode now = (s != null && s.isScanning()) ? s.getMode() : null;
        if (now == observedMode) {
            return;
        }
        observedMode = now;
        if (now == BleScaleScanner.Mode.BIND) {
            rescuePolicy.reset(System.currentTimeMillis());
            scheduleRescueCheck();
        } else {
            cancelRescueCheck();
        }
    }

    /**
     * 进入「重新广播发现」：不再只认配置的 MAC，附近所有小米体脂秤都拿 bind key 试解密。
     * 窗口结束还没有解密成功，就退回绑定扫描（不会乱绑）。
     */
    private void enterRescue(String reason) {
        BleScaleScanner s = scanner;
        if (s == null || rescueActive) {
            return;
        }
        if (!config.hasMac() || !config.hasKey() || !hasScanPermission()) {
            return;
        }
        rescueActive = true;
        rescuePolicy.beginRescue(System.currentTimeMillis());
        Log.i(TAG, "rescue start reason=" + reason + " mac=" + config.getMac());
        boolean started = s.start(BleScaleScanner.Mode.RESCUE);
        if (!started) {
            rescueActive = false;
            rescuePolicy.endRescue(System.currentTimeMillis());
            pushState("state");
            return;
        }
        if (ui != null) {
            ui.postDelayed(rescueTimeoutRunnable, RESCUE_WINDOW_MS);
        }
        pushState("state");
    }

    /** 结束兜底窗口（可从 BLE 回调线程调用）。 */
    private void finishRescue(final boolean corrected) {
        if (!rescueActive) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                finishRescueOnUi(corrected);
            }
        });
    }

    private void finishRescueOnUi(boolean corrected) {
        if (!rescueActive) {
            return;
        }
        rescueActive = false;
        if (ui != null) {
            ui.removeCallbacks(rescueTimeoutRunnable);
        }
        rescuePolicy.endRescue(System.currentTimeMillis());
        BleScaleScanner s = scanner;
        if (s != null && !discoverMode && config.hasMac() && config.hasKey()
                && hasScanPermission()) {
            s.start(BleScaleScanner.Mode.BIND);
        }
        Log.i(TAG, "rescue end corrected=" + corrected);
        pushState("state");
    }

    /** 立即取消兜底窗口（暂停 / 停止扫描 / 手动改设备时）。 */
    private void cancelRescue() {
        if (!rescueActive) {
            return;
        }
        rescueActive = false;
        if (ui != null) {
            ui.removeCallbacks(rescueTimeoutRunnable);
        }
        rescuePolicy.endRescue(System.currentTimeMillis());
    }

    /**
     * 兜底发现到的「别人的」小米体脂秤广播：用当前 bind key 试解密。
     * 解密成功才说明这就是那台秤换了地址 → 落盘纠正并马上回绑定模式；
     * 解密失败（邻居的秤 / bind key 不匹配）直接丢弃，绝不乱绑。
     * 未加密的帧无法用 bind key 验身份、心跳帧没有数据，都不参与认秤。
     */
    private void handleRescueCandidate(final byte[] serviceData, final String address,
                                       final String name) {
        if (serviceData == null || address == null || address.length() == 0) {
            return;
        }
        if (address.equalsIgnoreCase(config.getMac())) {
            return;
        }
        byte[] key = config.getBindKeyBytes();
        if (key == null) {
            return;
        }
        MiBeaconDecoder.Frame frame;
        try {
            frame = MiBeaconDecoder.parse(serviceData, address);
        } catch (Exception e) {
            return;
        }
        if (frame == null || !frame.hasObject || !frame.encrypted) {
            return;
        }
        if (!MiBeaconDecoder.decrypt(frame, key)) {
            return;
        }
        // 写配置 + 重启扫描都回 UI 线程，避免和手动保存 / 暂停抢扫描状态
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!rescueActive || !config.hasKey()
                        || address.equalsIgnoreCase(config.getMac())) {
                    return;
                }
                String oldMac = config.getMac();
                if (!config.saveDevice(address, "", "", Config.sanitizeName(name))) {
                    return;
                }
                Log.i(TAG, "scale address corrected " + oldMac + " -> " + address);
                // 这一帧很可能就是刚才那次称重：按正常路径喂给会话，别白站一次
                handleBroadcast(serviceData, address);
                JSONObject payload = new JSONObject();
                try {
                    payload.put("code", "address_corrected");
                    payload.put("oldMac", oldMac);
                    payload.put("mac", address);
                    payload.put("name", config.getDeviceName());
                } catch (Exception ignored) {
                    // ignore
                }
                Bridge.push(MainActivity.this, webView, "device", payload.toString());
                finishRescueOnUi(true);
            }
        });
    }

    private BleScaleScanner createScanner() {
        return new BleScaleScanner(this, this, new BleScaleScanner.Listener() {
            @Override
            public void onStateChanged() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        syncRescueMode();
                    }
                });
                pushState("state");
            }

            @Override
            public void onScaleData(byte[] serviceData, String address, String name, int rssi) {
                // 注意：这里**不能**因为「配置 MAC 还在广播」就提前结束 RESCUE。
                // 配置 MAC 可能填错（例如从扫描列表点到了旁边另一台秤）：那种情况下这个 MAC 照样
                // 一直有广播、但 bind key 解不开，提前结束会让 20 s 发现窗口立刻作废、冷却 120 s，
                // 自动纠正退化成碰运气。窗口就让它在 20 s 后自然结束；期间这帧照常按绑定路径处理，
                // 不耽误用户称重（handleBroadcast 里解密失败只做节流报错）。
                handleBroadcast(serviceData, address);
            }

            @Override
            public void onCandidates(List<BleScaleScanner.Candidate> candidates) {
                long now = System.currentTimeMillis();
                if (now - lastCandidatesPushAt < 500L) {
                    return;
                }
                lastCandidatesPushAt = now;
                Bridge.push(MainActivity.this, webView, "candidates", candidatesJson());
            }

            @Override
            public void onRescueCandidate(byte[] serviceData, String address, String name, int rssi) {
                handleRescueCandidate(serviceData, address, name);
            }

            @Override
            public void onError(String code, String message) {
                reportError(code, message);
            }
        });
    }

    // ========== 生命周期 ==========

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        config.reload();
        // discoverMode 自愈：页面重载 / 渲染进程重建会让 JS 的 15 s 定时器消失，之后没人调
        // stopScan()，discoverMode 会一直 true → 回前台不再自动 BIND 扫描。这里按扫描器实际状态纠正。
        // discoverMode 的 set/clear 点：startScanInternal / stopScan / saveDevice / applyPresetDevice /
        // clearDeviceData / onResume（此处）/ recreateWebView。
        if (discoverMode && (scanner == null || !scanner.isScanning())) {
            discoverMode = false;
        }
        if (webView != null) {
            pushState("state");
        }
        if (!discoverMode && config.hasMac() && config.hasKey()) {
            requestAutoScan();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        cancelRescueCheck();
        cancelRescue();
        if (scanner != null) {
            scanner.stop();
        }
        if (session != null) {
            // 熄屏 / 切后台时「最终包」可能还没到：先把已经拿到的体重落一条，再复位会话。
            Measurement pending = session.forceFinalize(System.currentTimeMillis());
            session.reset();
            if (pending != null) {
                Log.i(TAG, "pause finalize weight=" + pending.weightKg);
                saveMeasurement(pending);
            }
        }
        cancelQuietCheck();
    }

    @Override
    protected void onDestroy() {
        // 先停掉所有还会产生新任务的东西
        cancelQuietCheck();
        cancelRescueCheck();
        cancelRescue();
        if (scanner != null) {
            scanner.destroy();
            scanner = null;
        }
        if (session != null) {
            // 顺序不能反：最后一次落库必须发生在摘掉 db/io、入队 close 之前
            Measurement pending = session.forceFinalize(System.currentTimeMillis());
            session.reset();
            session = null;
            if (pending != null) {
                Log.i(TAG, "destroy finalize weight=" + pending.weightKg);
                saveMeasurement(pending);
            }
        }
        // 先摘掉字段，已提交的保存/导出任务继续用捕获的引用跑完
        final WeightDb dbRef = db;
        final ExecutorService ioRef = io;
        db = null;
        io = null;
        if (ioRef != null) {
            // 先把 close 任务排到队尾（单线程 FIFO，前面的落库/导出会先完成），再 shutdown 让它排空
            try {
                ioRef.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (dbRef != null) {
                            try {
                                dbRef.close();
                            } catch (Exception ignored) {
                                // ignore
                            }
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
                // 执行器已关闭：只能就地关（极端情况）
                if (dbRef != null) {
                    try {
                        dbRef.close();
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            }
            ioRef.shutdown();
        } else if (dbRef != null) {
            try {
                dbRef.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (webView != null) {
            try {
                webView.removeJavascriptInterface("WeightLog");
                webView.destroy();
            } catch (Exception e) {
                // ignore
            }
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        try {
            webView.evaluateJavascript(
                    "(function(){try{return !!(window.wlHandleBack&&window.wlHandleBack());}"
                            + "catch(e){return false;}})()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (!"true".equals(value)) {
                                finish();
                            }
                        }
                    });
        } catch (Exception e) {
            finish();
        }
    }

    // ========== 权限 ==========

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        String[] perms = BleScaleScanner.requiredPermissions();
        for (int i = 0; i < perms.length; i++) {
            if (checkSelfPermission(perms[i]) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestScanPermission() {
        if (Build.VERSION.SDK_INT < 23 || hasScanPermission()) {
            return;
        }
        scanPermissionRequested = true;
        requestPermissions(BleScaleScanner.requiredPermissions(), REQ_SCAN);
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= 29) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_SCAN) {
            boolean granted = grantResults.length > 0;
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                }
            }
            if (granted) {
                // 用授权前记下的模式继续；没有记录时（自动扫描）回退到 bind
                String mode = pendingScanMode != null ? pendingScanMode
                        : (discoverMode ? "discover" : "bind");
                pendingScanMode = null;
                if ("discover".equals(mode)) {
                    startScanInternal("discover");
                } else if (config.hasMac() && config.hasKey()) {
                    startScanInternal("bind");
                } else {
                    reportError("no_config", "先绑定体重秤的 MAC 和 bind key");
                }
            } else {
                pendingScanMode = null;
                reportError("no_permission", "没有权限无法扫描体重秤，可在系统设置里开启");
            }
            pushState("state");
        } else if (requestCode == REQ_EXPORT) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && pendingExportFormat != null) {
                final String format = pendingExportFormat;
                pendingExportFormat = null;
                final ExecutorService executor = io;
                if (executor != null) {
                    try {
                        executor.execute(new Runnable() {
                            @Override
                            public void run() {
                                // 异步授权路径：结果只能靠事件推给 JS
                                String result = doExport(format);
                                pushEvent("export", result);
                            }
                        });
                    } catch (RejectedExecutionException e) {
                        Log.w(TAG, "export rejected");
                    }
                }
            } else {
                pushEvent("export", "{\"ok\":false,\"error\":\"需要存储权限才能导出\"}");
            }
        }
    }

    // ========== 扫描 ==========

    private void requestAutoScan() {
        if (!hasScanPermission()) {
            if (!scanPermissionRequested) {
                requestScanPermission();
            } else {
                reportErrorThrottled("no_permission", "未授予蓝牙/定位权限，无法扫描体重秤");
            }
            return;
        }
        scanner.start(BleScaleScanner.Mode.BIND);
    }

    private void startScanInternal(String mode) {
        if (scanner == null) {
            pendingScanMode = null;
            return;
        }
        // 先记住要启动的模式，再做权限检查：首次点「扫描附近设备」会先弹权限，
        // 授权回调里必须知道这次是 DISCOVER 而不是沿用 discoverMode（那时还是 false）。
        pendingScanMode = "discover".equals(mode) ? "discover" : "bind";
        if (!hasScanPermission()) {
            requestScanPermission();
            return;
        }
        if ("discover".equals(mode)) {
            discoverMode = true;
            scanner.clearCandidates();
            scanner.start(BleScaleScanner.Mode.DISCOVER);
        } else {
            discoverMode = false;
            if (!config.hasMac() || !config.hasKey()) {
                pendingScanMode = null;
                reportError("no_config", "先绑定体重秤的 MAC 和 bind key");
                return;
            }
            scanner.start(BleScaleScanner.Mode.BIND);
        }
        pendingScanMode = null;
        pushState("state");
    }

    @Override
    public void startScan(String mode) {
        final String m = mode != null && mode.length() > 0 ? mode : "bind";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                config.reload();
                startScanInternal(m);
            }
        });
    }

    @Override
    public void stopScan() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                discoverMode = false;
                cancelRescueCheck();
                cancelRescue();
                if (scanner != null) {
                    scanner.stop();
                }
                if (session != null) {
                    session.reset();
                }
                pushState("state");
            }
        });
    }

    @Override
    public String candidatesJson() {
        if (scanner == null) {
            return "[]";
        }
        return candidatesToJson(scanner.snapshotPublic());
    }

    private String candidatesToJson(List<BleScaleScanner.Candidate> list) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            try {
                arr.put(new JSONObject(list.get(i).toJson()));
            } catch (Exception e) {
                // ignore
            }
        }
        return arr.toString();
    }

    // ========== 广播解析 ==========

    private void handleBroadcast(byte[] serviceData, String address) {
        WeighSession current = session;
        if (current == null) {
            return;
        }
        byte[] key = config.getBindKeyBytes();
        if (key == null) {
            reportErrorThrottled("no_key", "还没有填写 bind key");
            return;
        }
        MiBeaconDecoder.Frame frame;
        try {
            frame = MiBeaconDecoder.parse(serviceData, address);
        } catch (Exception e) {
            Log.w(TAG, "frame parse failed");
            return;
        }
        if (frame == null) {
            return;
        }
        if (!frame.hasObject) {
            // 心跳只证明这个 MAC 有设备在广播，不证明 MAC↔key 配对正确：只续命，不清失败计数
            rescuePolicy.noteAlive(System.currentTimeMillis());
            Measurement heartbeatFinal = current.onHeartbeat();
            if (heartbeatFinal != null) {
                Log.i(TAG, "heartbeat finalize weight=" + heartbeatFinal.weightKg);
                cancelQuietCheck();
                saveMeasurement(heartbeatFinal);
            }
            return;
        }
        if (frame.encrypted) {
            if (!MiBeaconDecoder.decrypt(frame, key)) {
                // 连续解密失败也是「地址/密钥可能过期」的信号，兜底策略会据此重扫
                rescuePolicy.noteDecodeFailure();
                reportErrorThrottled("decode_failed", "广播解密失败：bind key 不对，或秤已重新绑定");
                return;
            }
        }
        MiBeaconDecoder.ScalePacket packet = MiBeaconDecoder.parseScalePayload(frame.payload);
        if (packet == null) {
            return;
        }
        // 这一帧确实能被当前 key 解开并解析出体重对象：才算「地址/密钥配对成功」
        rescuePolicy.noteGood(System.currentTimeMillis());
        current.onPacket(packet, frame.rawHex, frame.payloadHex);
    }

    // ========== 静默收尾 ==========

    private final Runnable quietCheck = new Runnable() {
        @Override
        public void run() {
            quietCheckScheduled = false;
            WeighSession current = session;
            if (current == null || !current.isOpen()) {
                return;
            }
            Measurement m = current.pollQuietFinalize(System.currentTimeMillis(),
                    QUIET_FINALIZE_MS);
            if (m != null) {
                Log.i(TAG, "quiet finalize weight=" + m.weightKg);
                saveMeasurement(m);
                return;
            }
            scheduleQuietCheck();
        }
    };

    private void scheduleQuietCheck() {
        if (quietCheckScheduled || ui == null) {
            return;
        }
        quietCheckScheduled = true;
        ui.postDelayed(quietCheck, QUIET_CHECK_MS);
    }

    private void cancelQuietCheck() {
        quietCheckScheduled = false;
        if (ui != null) {
            ui.removeCallbacks(quietCheck);
        }
    }

    // ========== 落库 ==========

    private void saveMeasurement(final Measurement m) {
        cancelQuietCheck();
        if (m == null) {
            return;
        }
        applyProfile(m);
        // 提交时捕获 DB 引用：Activity 随后被销毁时，队列里的任务也能安全完成最后一次落库
        final WeightDb dbRef = db;
        if (dbRef == null) {
            return;
        }
        final ExecutorService executor = io;
        if (executor == null) {
            return;
        }
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    long id = dbRef.insert(m);
                    if (id <= 0) {
                        reportError("db_error", "保存称重记录失败");
                        return;
                    }
                    m.id = id;
                    Log.i(TAG, "saved id=" + id + " weight=" + m.weightKg
                            + " fat=" + m.bodyFatPct + " source=" + m.source);
                    JSONObject payload = new JSONObject();
                    try {
                        payload.put("measurement", m.toJson(false));
                        payload.put("state", new JSONObject(stateJson()));
                    } catch (Exception e) {
                        // ignore
                    }
                    Bridge.push(MainActivity.this, webView, "final", payload.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "save rejected (activity finishing)");
        }
    }

    /**
     * 补档案快照 + 体成分（体脂 / BMI / BMR 都是估算）。
     * 先清空旧值：档案不完整时不能把上一次档案算出来的值又写回去。
     */
    private void applyProfile(Measurement m) {
        m.profileId = 1;
        m.bmi = null;
        m.bodyFatPct = null;
        m.bmrKcal = null;
        m.profileHeightCm = null;
        m.profileAge = null;
        m.profileSex = null;
        int heightCm = config.getHeightCm();
        int age = config.getAge();
        String sex = config.getSex();
        boolean ready = config.isProfileReady();
        m.profileHeightCm = heightCm > 0 ? Integer.valueOf(heightCm) : null;
        m.profileAge = age > 0 ? Integer.valueOf(age) : null;
        m.profileSex = sex;
        m.calcVersion = BodyComposition.CALC_VERSION;
        if (heightCm > 0) {
            m.bmi = BodyComposition.bmi(m.weightKg, heightCm);
        }
        Double impedance = m.impedanceLowOhm != null ? m.impedanceLowOhm : m.impedanceHighOhm;
        // 体脂需要阻抗 + 完整档案；BMR 只要求档案完整
        if (impedance != null && ready) {
            m.bodyFatPct = BodyComposition.bodyFatPct(m.weightKg, impedance.doubleValue(),
                    heightCm, age, sex);
        }
        if (ready) {
            m.bmrKcal = BodyComposition.bmrKcal(m.weightKg, heightCm, age, sex);
        }
    }

    /** 档案变化后按现有阻抗重算历史记录（体脂/BMI/BMR + 档案快照）。 */
    private void recalcHistory() {
        final WeightDb dbRef = db;
        if (dbRef == null) {
            return;
        }
        final List<Measurement> all = dbRef.list(0);
        dbRef.transaction(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < all.size(); i++) {
                    Measurement m = all.get(i);
                    applyProfile(m);
                    dbRef.updateComposition(m.id, m.bodyFatPct, m.bmi, m.bmrKcal,
                            m.profileHeightCm, m.profileAge, m.profileSex, m.calcVersion);
                }
            }
        });
    }

    // ========== Bridge.Host：状态 / 记录 ==========

    @Override
    public String stateJson() {
        JSONObject o = new JSONObject();
        boolean dbError = false;
        try {
            o.put("version", BuildConfig.VERSION_NAME);
            o.put("onboarded", config.isOnboarded());
            o.put("device", config.devicePublicJson());
            o.put("profile", config.profileJson());
            o.put("scan", scanJson());
            JSONObject records = new JSONObject();
            // 捕获引用：Activity 销毁后 db 字段已置空，这里也不能 NPE
            final WeightDb dbRef = db;
            if (dbRef == null) {
                dbError = true;
                records.put("count", 0);
                records.put("last", JSONObject.NULL);
                records.put("previous", JSONObject.NULL);
            } else {
                try {
                    records.put("count", dbRef.count());
                    Measurement last = dbRef.getLast();
                    records.put("last", last != null ? last.toJson(false) : JSONObject.NULL);
                    Measurement previous = dbRef.getPrevious();
                    records.put("previous", previous != null ? previous.toJson(false) : JSONObject.NULL);
                    dbError = dbRef.hasRecentError();
                } catch (Exception e) {
                    // 查询失败也要暴露成错误，而不是「没有数据」
                    dbError = true;
                    records.put("count", 0);
                    records.put("last", JSONObject.NULL);
                    records.put("previous", JSONObject.NULL);
                }
            }
            o.put("records", records);
        } catch (Exception e) {
            Log.w(TAG, "state build failed");
            dbError = true;
        }
        try {
            o.put("dbError", dbError);
        } catch (Exception e) {
            // ignore
        }
        return o.toString();
    }

    private JSONObject scanJson() {
        JSONObject s = new JSONObject();
        try {
            s.put("supported", scanner != null && scanner.isSupported());
            s.put("btOn", scanner != null && scanner.isBtOn());
            s.put("permission", hasScanPermission());
            s.put("locationOn", scanner != null && scanner.isLocationServiceOn());
            s.put("scanning", scanner != null && scanner.isScanning());
            s.put("mode", discoverMode ? "discover" : "bind");
            // 正在「超时后重新广播发现」：页面据此显示「正在重新找秤」
            s.put("rescue", rescueActive);
            s.put("wantScan", config.hasMac() && config.hasKey());
            if (lastErrorCode.length() > 0
                    && System.currentTimeMillis() - lastErrorAt < 30000L) {
                JSONObject err = new JSONObject();
                err.put("code", lastErrorCode);
                err.put("message", lastErrorMessage);
                s.put("error", err);
            } else {
                s.put("error", JSONObject.NULL);
            }
        } catch (Exception e) {
            // ignore
        }
        return s;
    }

    @Override
    public String listJson(int days) {
        final WeightDb dbRef = db;
        if (dbRef == null) {
            return "[]";
        }
        List<Measurement> list = dbRef.list(days);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            arr.put(list.get(i).toJson(false));
        }
        return arr.toString();
    }

    @Override
    public String addManual(String json) {
        JSONObject result = new JSONObject();
        final WeightDb dbRef = db;
        try {
            if (dbRef == null) {
                result.put("ok", false);
                result.put("error", "数据库不可用");
                return result.toString();
            }
            JSONObject in = new JSONObject(json != null ? json : "{}");
            double weight = in.optDouble("weight_kg", 0);
            if (!(weight >= 20 && weight <= 250)) {
                result.put("ok", false);
                result.put("error", "体重需要在 20–250 kg 之间");
                return result.toString();
            }
            Measurement m = new Measurement();
            long at = in.optLong("measured_at_ms", 0);
            if (at <= 0 || at > System.currentTimeMillis() + 60000L) {
                at = System.currentTimeMillis();
            }
            m.measuredAtMs = at;
            // 补录历史记录时存「那个时刻」的时区偏移，而不是当前偏移
            m.tzOffsetMin = WeightDb.tzOffsetMinAt(at);
            m.weightKg = Math.round(weight * 10.0) / 10.0;
            m.source = Measurement.SOURCE_MANUAL;
            m.stabilized = true;
            m.note = in.optString("note", "");
            if (m.note.length() == 0) {
                m.note = null;
            }
            applyProfile(m);
            long id = dbRef.insert(m);
            if (id <= 0) {
                result.put("ok", false);
                result.put("error", "保存失败");
                return result.toString();
            }
            m.id = id;
            result.put("ok", true);
            result.put("measurement", m.toJson(false));
            result.put("state", new JSONObject(stateJson()));
        } catch (Exception e) {
            try {
                result.put("ok", false);
                result.put("error", "参数错误");
            } catch (Exception ignored) {
                // ignore
            }
        }
        return result.toString();
    }

    @Override
    public String updateNote(long id, String note) {
        final WeightDb dbRef = db;
        boolean ok = dbRef != null && dbRef.updateNote(id, note);
        JSONObject o = new JSONObject();
        try {
            o.put("ok", ok);
        } catch (Exception e) {
            // ignore
        }
        return o.toString();
    }

    @Override
    public String deleteRecord(long id) {
        final WeightDb dbRef = db;
        boolean ok = dbRef != null && dbRef.delete(id);
        JSONObject o = new JSONObject();
        try {
            o.put("ok", ok);
            o.put("state", new JSONObject(stateJson()));
        } catch (Exception e) {
            // ignore
        }
        return o.toString();
    }

    @Override
    public String clearAll() {
        final WeightDb dbRef = db;
        if (dbRef != null) {
            dbRef.clearAll();
        }
        JSONObject o = new JSONObject();
        try {
            o.put("ok", dbRef != null);
            o.put("state", new JSONObject(stateJson()));
        } catch (Exception e) {
            // ignore
        }
        return o.toString();
    }

    @Override
    public String seedDemo() {
        JSONObject result = new JSONObject();
        try {
            // 不写假档案：档案没填时演示记录的体脂/BMI/BMR 留空（UI 已有 -- 分支）
            final WeightDb dbRef = db;
            if (dbRef == null) {
                result.put("ok", false);
                result.put("error", "数据库不可用");
                return result.toString();
            }
            final int[] inserted = new int[]{0};
            // 删旧演示数据 + 重新插入放在同一个事务里，中途失败不会留下半套数据
            dbRef.transaction(new Runnable() {
                @Override
                public void run() {
                    dbRef.deleteBySource(Measurement.SOURCE_DEMO);
                    inserted[0] = seedDemoRows(dbRef);
                }
            });
            result.put("ok", true);
            result.put("count", inserted[0]);
            result.put("state", new JSONObject(stateJson()));
        } catch (Exception e) {
            try {
                result.put("ok", false);
                result.put("error", "生成演示数据失败");
            } catch (Exception ignored) {
                // ignore
            }
        }
        return result.toString();
    }

    @Override
    public String clearDemo() {
        JSONObject result = new JSONObject();
        final WeightDb dbRef = db;
        try {
            if (dbRef == null) {
                reportError("db_error", "清除演示记录失败");
                result.put("ok", false);
                result.put("error", "数据库不可用");
                return result.toString();
            }
            int n = dbRef.deleteBySource(Measurement.SOURCE_DEMO);
            if (dbRef.hasRecentError()) {
                reportError("db_error", "清除演示记录失败");
                result.put("ok", false);
                result.put("error", "清除演示记录失败");
                return result.toString();
            }
            Log.i(TAG, "clear demo count=" + n);
            result.put("ok", true);
            result.put("count", n);
            result.put("state", new JSONObject(stateJson()));
        } catch (Exception e) {
            try {
                result.put("ok", false);
                result.put("error", "清除演示记录失败");
            } catch (Exception ignored) {
                // ignore
            }
        }
        return result.toString();
    }

    private int seedDemoRows(WeightDb dbRef) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 8);
        cal.set(Calendar.MINUTE, 12);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long todayMorning = cal.getTimeInMillis();
        double base = 66.8;
        int inserted = 0;
        for (int i = 44; i >= 0; i--) {
            if (i % 2 == 1 && i > 2) {
                continue; // 隔天记录，看起来更真实
            }
            double t = (44 - i) / 44.0;
            double weight = base - 1.6 * t + Math.sin(i * 1.7) * 0.35 + (i % 5 == 0 ? 0.2 : 0);
            weight = Math.round(weight * 10.0) / 10.0;
            Measurement m = new Measurement();
            long at = todayMorning - (long) i * 86400000L;
            m.measuredAtMs = at;
            m.tzOffsetMin = WeightDb.tzOffsetMinAt(at);
            m.weightKg = weight;
            m.impedanceLowOhm = Double.valueOf(Math.round((545 + Math.sin(i * 0.9) * 12) * 10) / 10.0);
            m.impedanceHighOhm = Double.valueOf(Math.round((412 + Math.cos(i * 0.7) * 10) * 10) / 10.0);
            m.source = Measurement.SOURCE_DEMO;
            m.stabilized = true;
            m.note = i == 0 ? "演示数据" : null;
            applyProfile(m);
            if (dbRef.insert(m) > 0) {
                inserted++;
            }
        }
        return inserted;
    }

    // ========== Bridge.Host：档案 / 设备 ==========

    @Override
    public String saveProfile(String json) {
        JSONObject result = new JSONObject();
        try {
            JSONObject in = new JSONObject(json != null ? json : "{}");
            String name = in.optString("name", "");
            if ("null".equals(name)) {
                name = "";
            }
            int height = in.optInt("heightCm", 0);
            int age = in.optInt("age", 0);
            String sex = in.optString("sex", "male");
            double target = in.optDouble("targetWeightKg", 0);
            if (height < 0 || height > 250 || age < 0 || age > 120 || target < 0 || target > 300) {
                result.put("ok", false);
                result.put("error", "档案数值超出范围");
                return result.toString();
            }
            if (name != null && name.length() > Config.MAX_RAW_NAME_LEN) {
                result.put("ok", false);
                result.put("error", "名字太长（最多 40 个字）");
                return result.toString();
            }
            // 目标值变了才重置进度起点：起点用「设目标那一刻的最新体重」，
            // 没有记录时写 0，由前端回退到最早一条记录。
            double oldTarget = config.getTargetWeightKg();
            double setAtKg = config.getTargetSetAtKg();
            long setAtMs = config.getTargetSetAtMs();
            if (Math.abs(target - oldTarget) > 0.05) {
                setAtMs = System.currentTimeMillis();
                setAtKg = 0;
                try {
                    final WeightDb dbRef = db;
                    Measurement last = dbRef != null ? dbRef.getLast() : null;
                    if (last != null) {
                        setAtKg = last.weightKg;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "target start weight lookup failed");
                }
            }
            if (!config.saveProfile(name, height, age, sex, target, setAtKg, setAtMs)) {
                result.put("ok", false);
                result.put("error", "档案保存失败");
                return result.toString();
            }
            recalcHistory();
            result.put("ok", true);
            result.put("profile", config.profileJson());
            result.put("state", new JSONObject(stateJson()));
        } catch (Exception e) {
            try {
                result.put("ok", false);
            } catch (Exception ignored) {
                // ignore
            }
        }
        return result.toString();
    }

    @Override
    public String saveDevice(String json) {
        JSONObject result = new JSONObject();
        try {
            JSONObject in = new JSONObject(json != null ? json : "{}");
            String mac = in.optString("mac", "");
            String key = in.optString("bindKey", "");
            String keyHex = Config.normalizeHex(key);
            if (keyHex.length() > 0 && !Config.isValidBindKey(keyHex)) {
                result.put("ok", false);
                result.put("error", "bind key 应该是 32 位十六进制（16 字节）");
                return result.toString();
            }
            String name = in.optString("name", "");
            if (name != null && name.length() > Config.MAX_RAW_NAME_LEN) {
                result.put("ok", false);
                result.put("error", "设备名称太长（最多 40 个字）");
                return result.toString();
            }
            boolean ok = config.saveDevice(mac, key, "", name);
            if (!ok) {
                result.put("ok", false);
                result.put("error", "MAC 格式不对（例如 E8:4A:54:12:34:56）");
                return result.toString();
            }
            discoverMode = false;
            restartBindScanOnUi();
            result.put("ok", true);
            result.put("device", config.devicePublicJson());
            result.put("keyValid", config.hasKey());
            result.put("state", new JSONObject(stateJson()));
        } catch (Exception e) {
            try {
                result.put("ok", false);
            } catch (Exception ignored) {
                // ignore
            }
        }
        return result.toString();
    }

    @Override
    public String applyPresetDevice() {
        JSONObject result = new JSONObject();
        try {
            if (!Config.hasPreset()) {
                result.put("ok", false);
                result.put("error", "这个安装包没有内置预绑定设备");
                return result.toString();
            }
            boolean ok = config.applyPreset();
            result.put("ok", ok);
            if (!ok) {
                result.put("error", "恢复预绑定失败");
            }
            discoverMode = false;
            restartBindScanOnUi();
            result.put("device", config.devicePublicJson());
            result.put("keyValid", config.hasKey());
            result.put("state", new JSONObject(stateJson()));
        } catch (Exception e) {
            try {
                result.put("ok", false);
            } catch (Exception ignored) {
                // ignore
            }
        }
        return result.toString();
    }

    @Override
    public String clearDeviceData() {
        config.clearDevice();
        discoverMode = false;
        stopScanOnUi();
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("state", new JSONObject(stateJson()));
        } catch (Exception e) {
            // ignore
        }
        return o.toString();
    }

    /**
     * Bridge 线程改完设备配置后，在 UI 线程重启绑定扫描。
     * 不这么做的话，用户点完「保存配置」马上退到后台时，onPause 的 stop 可能和这里排队中的
     * start 交错，导致后台继续扫描。
     */
    private void restartBindScanOnUi() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!resumed) {
                    // 已经退到后台：onPause 已经 stop 过，别再起扫
                    return;
                }
                BleScaleScanner s = scanner;
                if (s == null) {
                    return;
                }
                cancelRescue();
                s.stop();
                if (!discoverMode && config.hasMac() && config.hasKey() && hasScanPermission()) {
                    s.start(BleScaleScanner.Mode.BIND);
                }
                pushState("state");
            }
        });
    }

    /** 解绑后在 UI 线程停扫描并取消兜底定时器。 */
    private void stopScanOnUi() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                cancelRescueCheck();
                cancelRescue();
                if (scanner != null) {
                    scanner.stop();
                }
                pushState("state");
            }
        });
    }

    // ========== Bridge.Host：导出 / 其他 ==========

    @Override
    public String exportData(String format) {
        if (!hasStoragePermission()) {
            pendingExportFormat = format;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            REQ_EXPORT);
                }
            });
            return "{\"ok\":false,\"pending\":true,\"error\":\"等待存储权限\"}";
        }
        return doExport(format);
    }

    private String doExport(String format) {
        // 提交时捕获 DB 引用，避免导出期间 Activity 销毁导致 NPE
        final WeightDb dbRef = db;
        if (dbRef == null) {
            return "{\"ok\":false,\"error\":\"数据库不可用\"}";
        }
        try {
            List<Measurement> list = dbRef.list(0);
            JSONObject r = Exporter.export(this, list, config, format);
            return r.toString();
        } catch (Exception e) {
            Log.e(TAG, "export failed", e);
            return "{\"ok\":false,\"error\":\"导出失败\"}";
        }
    }

    @Override
    public void setLiveMode(final boolean live) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // 正在称重时别让系统熄屏：熄屏会走 onPause，等秤的人还没站稳就断了
                if (live) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
                // 称重层打开时缩短兜底超时：用户已经站在秤上，等不起 2 分钟
                rescuePolicy.setLiveMode(live);
                BleScaleScanner s = scanner;
                if (s != null) {
                    s.setLowLatency(live);
                }
            }
        });
    }

    @Override
    public void openSettings(String target) {
        try {
            Intent intent;
            if ("bluetooth".equals(target)) {
                intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            } else if ("location".equals(target)) {
                intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            } else {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "open settings failed");
        }
    }

    @Override
    public void setOnboarded(boolean value) {
        config.setOnboarded(value);
    }

    // ========== 状态推送 / 错误 ==========

    private void pushState(final String type) {
        final WebView wv = webView;
        final ExecutorService ex = io;
        if (wv == null || ex == null) {
            return;
        }
        try {
            // 在单线程 io 上构造状态（COUNT/查询），保持与落库的先后顺序；Bridge.push 内部再切回 UI 线程
            ex.execute(new Runnable() {
                @Override
                public void run() {
                    String state;
                    try {
                        state = stateJson();
                    } catch (Exception e) {
                        return;
                    }
                    Bridge.push(MainActivity.this, wv, type, state);
                }
            });
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "pushState rejected (activity finishing)");
        }
    }

    private void pushEvent(String type, String payload) {
        Bridge.push(this, webView, type, payload);
    }

    private void reportError(String code, String message) {
        lastErrorCode = code != null ? code : "";
        lastErrorMessage = message != null ? message : "";
        lastErrorAt = System.currentTimeMillis();
        Log.w(TAG, "error " + code + ": " + message);
        JSONObject o = new JSONObject();
        try {
            o.put("code", lastErrorCode);
            o.put("message", lastErrorMessage);
        } catch (Exception e) {
            // ignore
        }
        pushEvent("error", o.toString());
        pushState("state");
    }

    private void reportErrorThrottled(String code, String message) {
        long now = System.currentTimeMillis();
        if (code != null && code.equals(lastErrorCode) && now - lastErrorAt < ERROR_THROTTLE_MS) {
            return;
        }
        reportError(code, message);
    }

    // ========== BleScaleScanner.ConfigProvider ==========

    @Override
    public String getMac() {
        return config.getMac();
    }

    @Override
    public boolean hasMac() {
        return config.hasMac();
    }

    @Override
    public boolean hasKey() {
        return config.hasKey();
    }
}
