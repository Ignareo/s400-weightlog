package com.weightlog.app.scale;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BLE 扫描：
 *   - 绑定模式：只放行 product id 命中、且 MAC 与配置一致的 S400，交给称重状态机；
 *   - 发现模式：附近所有小米体脂秤（0xFE95 + 已知 product id），用于「添加设备」时选秤；
 *   - 兜底模式（RESCUE）：绑定超时后重新广播发现时用。自家 MAC 的包照常走绑定回调，
 *     其它小米秤的包交给 {@link Listener#onRescueCandidate}，由宿主用 bind key 试解密，
 *     解密成功才认作「还是这台秤」，再自动纠正保存的地址。
 *
 * 不依赖硬件 ScanFilter：S400 的 FE95 是 service data（AD type 0x16），
 * 部分手机厂商栈用 serviceUuid 过滤会零回调，这里全量扫描 + 软件过滤。
 *
 * 权限：Android 12+ 用 BLUETOOTH_SCAN / BLUETOOTH_CONNECT；Android 11 及以下用定位权限。
 *
 * 线程模型：start/stop/destroy/扫描参数切换/候选列表都在 this 锁下；
 * 扫描参数默认 BALANCED，只有 JS 打开称重层时用 setLowLatency(true) 切到 LOW_LATENCY。
 */
public class BleScaleScanner {

    private static final String TAG = "WeightLog";

    public static final UUID MIBEACON_SERVICE_UUID =
            UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb");

    /** 扫描失败重试间隔：2s / 5s / 15s，最多 3 次 */
    private static final long[] RETRY_DELAYS_MS = {2000L, 5000L, 15000L};
    private static final int MAX_SCAN_RETRIES = RETRY_DELAYS_MS.length;

    public interface Listener {
        void onStateChanged();

        /** 绑定模式下收到自家秤的 0xFE95 service data */
        void onScaleData(byte[] serviceData, String address, String name, int rssi);

        /** 发现模式：附近秤列表（每次刷新都整表回调） */
        void onCandidates(List<Candidate> candidates);

        /**
         * 兜底模式：MAC 与配置不一致的小米体脂秤广播。
         * 宿主用 bind key 试解密，成功才说明「换了地址的还是这台秤」。
         */
        void onRescueCandidate(byte[] serviceData, String address, String name, int rssi);

        /** 错误码：bt_off / no_permission / location_off / no_config / scan_failed */
        void onError(String code, String message);
    }

    public static class Candidate {
        public String address;
        public String name;
        public int productId;
        public int rssi;
        public long lastSeenMs;

        public String toJson() {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("address", address);
                o.put("name", name != null ? name : "");
                o.put("product_id", productId);
                o.put("rssi", rssi);
                o.put("last_seen_ms", lastSeenMs);
            } catch (Exception e) {
                // ignore
            }
            return o.toString();
        }
    }

    public enum Mode {
        BIND, DISCOVER, RESCUE
    }

    private final Context context;
    private final ConfigProvider configProvider;
    private final Listener listener;
    private final BluetoothAdapter adapter;
    private final ParcelUuid serviceParcelUuid = new ParcelUuid(MIBEACON_SERVICE_UUID);

    private BluetoothLeScanner scanner;
    private volatile boolean scanning;
    private volatile boolean wantScan;
    private volatile Mode mode = Mode.BIND;
    /** 是否用 LOW_LATENCY（只在 JS 说称重层打开时为 true） */
    private volatile boolean lowLatency;
    private BroadcastReceiver stateReceiver;
    private boolean receiverRegistered;
    // 写入在扫描线程、读取在 bridge/UI 线程 → ConcurrentHashMap 保证并发安全
    private final ConcurrentHashMap<String, Candidate> candidates =
            new ConcurrentHashMap<String, Candidate>();
    private int droppedCount;

    private final Handler retryHandler;
    /** 已重试次数，只在 this 锁内读写 */
    private int retryCount;

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            Mode m;
            synchronized (BleScaleScanner.this) {
                if (!wantScan) {
                    return;
                }
                m = mode;
            }
            start(m);
        }
    };

    /** 只读取 MAC / bind key，避免扫描器依赖整个 Config 类。 */
    public interface ConfigProvider {
        String getMac();

        boolean hasMac();

        boolean hasKey();
    }

    public BleScaleScanner(Context context, ConfigProvider configProvider, Listener listener) {
        this.context = context.getApplicationContext();
        this.configProvider = configProvider;
        this.listener = listener;
        this.retryHandler = new Handler(Looper.getMainLooper());
        BluetoothManager manager = null;
        try {
            manager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        } catch (Exception e) {
            Log.w(TAG, "BluetoothManager unavailable");
        }
        this.adapter = manager != null ? manager.getAdapter() : null;
    }

    // ========== 能力 / 状态 ==========

    public boolean isSupported() {
        return adapter != null;
    }

    public boolean isBtOn() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isScanning() {
        return scanning;
    }

    public Mode getMode() {
        return mode;
    }

    /** 当前需要的运行时权限。 */
    public static String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    public boolean hasPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        String[] perms = requiredPermissions();
        for (String p : perms) {
            if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /** Android 11 及以下扫描 BLE 需要系统定位开关打开（12+ 不再要求）。 */
    public boolean isLocationServiceOn() {
        if (Build.VERSION.SDK_INT >= 31) {
            return true;
        }
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= 28) {
                return lm.isLocationEnabled();
            }
            int mode = Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
            return mode != Settings.Secure.LOCATION_MODE_OFF;
        } catch (Exception e) {
            return true;
        }
    }

    // ========== 启停 ==========

    public synchronized boolean start(Mode newMode) {
        wantScan = true;
        mode = newMode;
        if (scanning) {
            // 模式切换：先停再起，避免沿用旧回调逻辑
            stopScanInternal();
        }
        if (adapter == null) {
            return failStart("bt_off", "本机不支持蓝牙");
        }
        if (!isBtOn()) {
            return failStart("bt_off", "蓝牙未开启");
        }
        if (!hasPermission()) {
            return failStart("no_permission", "缺少蓝牙/定位权限，无法扫描体重秤");
        }
        if (!isLocationServiceOn()) {
            return failStart("location_off", "请打开系统定位开关（安卓 11 及以下扫描 BLE 需要）");
        }
        // 绑定 / 兜底两种模式都需要 MAC + bind key：兜底发现要靠 bind key 试解密认秤
        if (newMode == Mode.BIND || newMode == Mode.RESCUE) {
            if (configProvider == null || !configProvider.hasMac()) {
                return failStart("no_config", "还没有绑定体重秤 MAC");
            }
            if (!configProvider.hasKey()) {
                return failStart("no_config", "还没有填写秤的 bind key");
            }
        }
        try {
            scanner = adapter.getBluetoothLeScanner();
        } catch (Exception e) {
            scanner = null;
        }
        if (scanner == null) {
            return failStart("bt_off", "蓝牙扫描器不可用");
        }
        ScanSettings settings = buildScanSettings();
        try {
            registerStateReceiver();
            scanner.startScan(null, settings, scanCallback);
            scanning = true;
            retryCount = 0;
            retryHandler.removeCallbacks(retryRunnable);
            Log.i(TAG, "BLE scan started mode=" + newMode + " lowLatency=" + lowLatency);
            notifyState();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startScan failed: " + e.getClass().getSimpleName());
            scanning = false;
            unregisterStateReceiver();
            return failStart("scan_failed", "启动蓝牙扫描失败，请重开蓝牙后再试");
        }
    }

    /** start() 的失败出口：必须清掉 wantScan，避免「想要扫描但实际没扫」。 */
    private boolean failStart(String code, String message) {
        wantScan = false;
        notifyError(code, message);
        notifyState();
        return false;
    }

    public synchronized void stop() {
        wantScan = false;
        retryCount = 0;
        retryHandler.removeCallbacks(retryRunnable);
        stopScanInternal();
        notifyState();
    }

    public synchronized void destroy() {
        wantScan = false;
        retryCount = 0;
        retryHandler.removeCallbacks(retryRunnable);
        stopScanInternal();
        scanner = null;
    }

    private synchronized void stopScanInternal() {
        boolean wasScanning = scanning;
        scanning = false;
        if (scanner != null && wasScanning) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception e) {
                // 蓝牙已关时 stopScan 可能抛异常
            }
        }
        unregisterStateReceiver();
    }

    /**
     * 切换扫描功耗档位：false = SCAN_MODE_BALANCED（默认），true = SCAN_MODE_LOW_LATENCY。
     * JS 在称重层打开/关闭时调用；扫描中会立即用新 ScanSettings 重启，wantScan/mode 保持不变。
     */
    public synchronized void setLowLatency(boolean value) {
        if (lowLatency == value) {
            return;
        }
        lowLatency = value;
        if (scanning) {
            stopScanInternal();
            start(mode);
        }
    }

    private ScanSettings buildScanSettings() {
        ScanSettings.Builder builder = new ScanSettings.Builder()
                .setScanMode(lowLatency
                        ? ScanSettings.SCAN_MODE_LOW_LATENCY
                        : ScanSettings.SCAN_MODE_BALANCED);
        return builder.build();
    }

    // ========== 扫描回调 ==========

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null) {
                return;
            }
            try {
                handleScanResult(result);
            } catch (Exception e) {
                Log.w(TAG, "scan result parse failed");
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) {
                return;
            }
            for (int i = 0; i < results.size(); i++) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, results.get(i));
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "BLE scan failed: code=" + errorCode);
            boolean retrying;
            synchronized (BleScaleScanner.this) {
                stopScanInternal();
                retrying = scheduleRetryLocked();
            }
            if (!retrying) {
                notifyError("scan_failed", "蓝牙扫描失败（" + errorCode + "），请重开蓝牙后再试");
            }
            notifyState();
        }
    };

    /** @return true 表示已安排重试；false 表示终止（已把 wantScan 置 false） */
    private boolean scheduleRetryLocked() {
        retryHandler.removeCallbacks(retryRunnable);
        if (!wantScan) {
            // 用户已经 stop()，不要偷偷重试
            return false;
        }
        if (retryCount >= MAX_SCAN_RETRIES) {
            wantScan = false;
            return false;
        }
        long delay = RETRY_DELAYS_MS[retryCount];
        retryCount++;
        retryHandler.postDelayed(retryRunnable, delay);
        Log.w(TAG, "BLE scan retry " + retryCount + "/" + MAX_SCAN_RETRIES
                + " in " + delay + "ms");
        return true;
    }

    private void handleScanResult(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record == null) {
            return;
        }
        byte[] serviceData = record.getServiceData(serviceParcelUuid);
        if (serviceData == null || serviceData.length < 4) {
            return;
        }
        int productId = (serviceData[2] & 0xFF) | ((serviceData[3] & 0xFF) << 8);
        if (!MiBeaconDecoder.isScaleProductId(productId)) {
            return;
        }
        BluetoothDevice device = result.getDevice();
        String address = device != null ? device.getAddress() : "";
        String name = record.getDeviceName();
        if (name == null && device != null) {
            name = device.getName();
        }
        if (mode == Mode.DISCOVER) {
            Candidate c = candidates.get(address);
            if (c == null) {
                Candidate fresh = new Candidate();
                fresh.address = address;
                fresh.name = name;
                fresh.productId = productId;
                Candidate prev = candidates.putIfAbsent(address, fresh);
                c = prev != null ? prev : fresh;
            }
            if (name != null && name.length() > 0) {
                c.name = name;
            }
            c.rssi = result.getRssi();
            c.lastSeenMs = System.currentTimeMillis();
            if (listener != null) {
                listener.onCandidates(snapshotCandidates());
            }
            return;
        }
        boolean configured = matchesConfiguredDevice(address);
        if (mode == Mode.RESCUE) {
            if (configured) {
                if (listener != null) {
                    listener.onScaleData(serviceData, address, name, result.getRssi());
                }
            } else if (listener != null) {
                listener.onRescueCandidate(serviceData, address, name, result.getRssi());
            }
            return;
        }
        if (!configured) {
            logDropped(address, name);
            return;
        }
        if (listener != null) {
            listener.onScaleData(serviceData, address, name, result.getRssi());
        }
    }

    /** ConcurrentHashMap 的迭代是弱一致的，不会抛 ConcurrentModificationException。 */
    private List<Candidate> snapshotCandidates() {
        long now = System.currentTimeMillis();
        List<Candidate> list = new ArrayList<Candidate>();
        for (Candidate c : candidates.values()) {
            // 10 秒没再出现就不展示，避免旧设备留在列表里
            if (now - c.lastSeenMs <= 10000L) {
                list.add(c);
            }
        }
        return list;
    }

    /** 发现模式开始前清空旧列表。 */
    public synchronized void clearCandidates() {
        candidates.clear();
    }

    /** 当前候选秤（10 秒内出现过的），供 JS 拉取。 */
    public List<Candidate> snapshotPublic() {
        return snapshotCandidates();
    }

    private boolean matchesConfiguredDevice(String address) {
        if (configProvider == null) {
            return false;
        }
        String configuredMac = configProvider.getMac();
        return address != null && configuredMac != null
                && address.equalsIgnoreCase(configuredMac);
    }

    private void logDropped(String address, String name) {
        droppedCount++;
        if (droppedCount == 1 || droppedCount % 30 == 0) {
            Log.d(TAG, "ignore scale addr=" + address + " name=" + name
                    + " dropped=" + droppedCount);
        }
    }

    // ========== 蓝牙开关广播 ==========

    private synchronized void registerStateReceiver() {
        if (receiverRegistered) {
            return;
        }
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null
                        || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                    return;
                }
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                handleAdapterState(state);
            }
        };
        try {
            context.registerReceiver(stateReceiver,
                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            receiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "register bluetooth receiver failed");
            stateReceiver = null;
        }
    }

    private synchronized void unregisterStateReceiver() {
        if (!receiverRegistered) {
            return;
        }
        receiverRegistered = false;
        try {
            context.unregisterReceiver(stateReceiver);
        } catch (Exception e) {
            // 已注销
        }
        stateReceiver = null;
    }

    /** 蓝牙开关变化：只在锁内改扫描状态，回调放到锁外，避免跨线程死锁。 */
    private void handleAdapterState(int state) {
        if (state == BluetoothAdapter.STATE_OFF) {
            synchronized (this) {
                stopScanInternal();
            }
            notifyError("bt_off", "蓝牙已关闭");
            notifyState();
        } else if (state == BluetoothAdapter.STATE_ON) {
            boolean restart;
            Mode m;
            synchronized (this) {
                restart = wantScan;
                m = mode;
            }
            notifyState();
            if (restart) {
                start(m);
            }
        }
    }

    private void notifyState() {
        if (listener != null) {
            listener.onStateChanged();
        }
    }

    private void notifyError(String code, String message) {
        Log.w(TAG, "scan error " + code + ": " + message);
        if (listener != null) {
            listener.onError(code, message);
        }
    }
}
