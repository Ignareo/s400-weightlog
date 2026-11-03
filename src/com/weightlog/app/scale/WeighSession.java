package com.weightlog.app.scale;

import com.weightlog.app.data.Measurement;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * 一次称重的状态机（与信息屏 App 的 ScaleSession 同源）：
 *   - 体重包（mass>0，低频阻抗）→ 打开会话，推送实时数字；
 *   - 收尾包（高频阻抗 / 仅体重，stabilized=true）→ 与体重包合并，落库一次；
 *   - 心跳包（object=0）→ 有体重+低频阻抗就收尾，否则复位；
 *   - 两次完整称重至少隔 5 秒，重复包不重复落库。
 *
 * 只做内存合并，不碰数据库；落库与推送由宿主负责。
 */
public class WeighSession {

    public interface Listener {
        void onLive(LiveData live);

        void onFinal(Measurement measurement);

        void onSessionError(String message);
    }

    public static final class LiveData {
        public double weightKg;
        public Double impedanceLowOhm;
        public Double impedanceHighOhm;
        public Integer heartRate;
        public boolean stabilized;
        public long tsMs;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("weight_kg", weightKg);
                o.put("impedance_low_ohm", impedanceLowOhm != null ? impedanceLowOhm : JSONObject.NULL);
                o.put("impedance_high_ohm", impedanceHighOhm != null ? impedanceHighOhm : JSONObject.NULL);
                o.put("heart_rate", heartRate != null ? heartRate : JSONObject.NULL);
                o.put("stabilized", stabilized);
                o.put("ts_ms", tsMs);
            } catch (Exception e) {
                // ignore
            }
            return o;
        }
    }

    private static final long MIN_FINAL_INTERVAL_MS = 5000L;
    private static final int MAX_RAW_PACKETS = 40;

    private final Listener listener;

    private boolean open;
    private boolean finalized;
    private Double weightKg;
    private Double lowOhm;
    private Double highOhm;
    private Integer heartRate;
    private long lastFinalAtMs;
    private String lastLiveSignature;
    private String lastRawHex;
    private long lastPacketAtMs;
    private final List<JSONObject> rawPackets = new ArrayList<JSONObject>();

    public WeighSession(Listener listener) {
        this.listener = listener;
    }

    /** 心跳包：有体重+低频阻抗就用它们收尾（心率/高频阻抗允许为空）。 */
    public synchronized Measurement onHeartbeat() {
        long now = System.currentTimeMillis();
        Measurement measurement = null;
        if (open && weightKg != null && weightKg.doubleValue() > 0 && lowOhm != null
                && (lastFinalAtMs <= 0 || now - lastFinalAtMs >= MIN_FINAL_INTERVAL_MS)) {
            measurement = buildFinalMeasurement(now);
        }
        if (measurement != null) {
            lastFinalAtMs = now;
            finishSession();
            return measurement;
        }
        resetInternal();
        return null;
    }

    public synchronized void reset() {
        resetInternal();
    }

    public synchronized boolean isOpen() {
        return open;
    }

    public synchronized void onPacket(MiBeaconDecoder.ScalePacket packet, String advHex, String payloadHex) {
        if (packet == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (packet.reset) {
            resetInternal();
            return;
        }

        boolean hasWeight = packet.massKg != null && packet.massKg.doubleValue() > 0;
        if (hasWeight) {
            if (finalized) {
                if (now - lastFinalAtMs < MIN_FINAL_INTERVAL_MS) {
                    return;
                }
                resetInternal();
            }
            open = true;
        }
        if (!open) {
            return;
        }

        lastPacketAtMs = now;
        storeRaw(advHex, payloadHex);
        if (hasWeight) {
            weightKg = packet.massKg;
        }
        if (packet.impedanceLowOhm != null) {
            lowOhm = packet.impedanceLowOhm;
        }
        if (packet.impedanceHighOhm != null) {
            highOhm = packet.impedanceHighOhm;
        }
        if (packet.heartRate != null) {
            heartRate = packet.heartRate;
        }

        if (packet.stabilized) {
            if (weightKg == null || weightKg.doubleValue() <= 0) {
                return;
            }
            if (lastFinalAtMs > 0 && now - lastFinalAtMs < MIN_FINAL_INTERVAL_MS) {
                finishSession();
                return;
            }
            Measurement measurement = buildFinalMeasurement(now);
            if (measurement == null) {
                return;
            }
            lastFinalAtMs = now;
            finishSession();
            if (listener != null) {
                listener.onFinal(measurement);
            }
            return;
        }

        if (!hasWeight) {
            return;
        }
        String signature = buildLiveSignature();
        if (signature.equals(lastLiveSignature)) {
            return;
        }
        lastLiveSignature = signature;
        if (listener != null) {
            LiveData live = new LiveData();
            live.weightKg = weightKg.doubleValue();
            live.impedanceLowOhm = lowOhm;
            live.impedanceHighOhm = highOhm;
            live.heartRate = heartRate;
            live.stabilized = false;
            live.tsMs = now;
            listener.onLive(live);
        }
    }

    /** 静默收尾：距最后一个包 >= quietMs 且已有体重+低频阻抗时落一条最终记录。 */
    public synchronized Measurement pollQuietFinalize(long nowMs, long quietMs) {
        if (!open) {
            return null;
        }
        if (weightKg == null || weightKg.doubleValue() <= 0 || lowOhm == null) {
            return null;
        }
        if (lastPacketAtMs <= 0 || nowMs - lastPacketAtMs < quietMs) {
            return null;
        }
        if (lastFinalAtMs > 0 && nowMs - lastFinalAtMs < MIN_FINAL_INTERVAL_MS) {
            finishSession();
            return null;
        }
        Measurement measurement = buildFinalMeasurement(nowMs);
        if (measurement == null) {
            return null;
        }
        lastFinalAtMs = nowMs;
        finishSession();
        return measurement;
    }

    /**
     * 兜底收尾：Activity 暂停/销毁时调用。只要拿到过体重就落一条，
     * 不等静默窗口、也不要求阻抗（穿袜子称重没有阻抗）。
     * 返回 null 表示这次会话还没有可用体重。
     */
    public synchronized Measurement forceFinalize(long nowMs) {
        if (!open) {
            return null;
        }
        if (weightKg == null || weightKg.doubleValue() <= 0) {
            return null;
        }
        if (lastFinalAtMs > 0 && nowMs - lastFinalAtMs < MIN_FINAL_INTERVAL_MS) {
            finishSession();
            return null;
        }
        Measurement m = buildFinalMeasurement(nowMs);
        if (m == null) {
            return null;
        }
        lastFinalAtMs = nowMs;
        finishSession();
        return m;
    }

    // ========== 内部 ==========

    private Measurement buildFinalMeasurement(long now) {
        if (weightKg == null || weightKg.doubleValue() <= 0) {
            return null;
        }
        Measurement m = new Measurement();
        m.measuredAtMs = now;
        m.tzOffsetMin = TimeZone.getDefault().getOffset(now) / 60000;
        m.weightKg = weightKg.doubleValue();
        m.impedanceLowOhm = lowOhm;
        m.impedanceHighOhm = highOhm;
        m.heartRate = heartRate;
        m.stabilized = true;
        m.calcVersion = BodyComposition.CALC_VERSION;
        m.source = Measurement.SOURCE_BLE;
        m.rawJson = buildRawJson();
        return m;
    }

    private void resetInternal() {
        open = false;
        finalized = false;
        weightKg = null;
        lowOhm = null;
        highOhm = null;
        heartRate = null;
        lastLiveSignature = null;
        lastRawHex = null;
        lastPacketAtMs = 0;
        rawPackets.clear();
    }

    private void finishSession() {
        open = false;
        finalized = true;
        weightKg = null;
        lowOhm = null;
        highOhm = null;
        heartRate = null;
        lastLiveSignature = null;
        lastRawHex = null;
        lastPacketAtMs = 0;
        rawPackets.clear();
    }

    private String buildLiveSignature() {
        return weightKg + "|" + lowOhm + "|" + highOhm + "|" + heartRate;
    }

    private void storeRaw(String advHex, String payloadHex) {
        if (advHex == null || advHex.length() == 0 || advHex.equals(lastRawHex)) {
            return;
        }
        lastRawHex = advHex;
        try {
            JSONObject item = new JSONObject();
            item.put("hex", advHex);
            if (payloadHex != null && payloadHex.length() > 0) {
                item.put("payload", payloadHex);
            }
            rawPackets.add(item);
            while (rawPackets.size() > MAX_RAW_PACKETS) {
                rawPackets.remove(0);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private String buildRawJson() {
        JSONObject o = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < rawPackets.size(); i++) {
                arr.put(rawPackets.get(i));
            }
            o.put("packets", arr);
        } catch (Exception e) {
            // ignore
        }
        return o.toString();
    }
}
