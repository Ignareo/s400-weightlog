import com.weightlog.app.data.Measurement;
import com.weightlog.app.scale.BodyComposition;
import com.weightlog.app.scale.MiBeaconDecoder;
import com.weightlog.app.scale.RescuePolicy;
import com.weightlog.app.scale.WeighSession;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-JVM regression harness for the weightlog protocol / crypto / state machine / formulas.
 *
 * <p>Pure-JVM regression harness for the weightlog protocol / crypto / state machine / formulas.
 * Covers the S400 frame layout, NIST AES-CCM vector, payload parsing, the weigh session state
 * machine and the body-composition boundaries without Android, Gradle or a real scale. Run with
 * {@code bash test/run.sh}.
 * The harness compiles a TEST-ONLY minimal {@code org.json} shim (see {@code test/org/json/}) and
 * a TEST-ONLY {@code WeightDb} stub, because those production classes pull in Android types that
 * are not available on a desktop JDK.
 *
 * <p>Exit code is 0 only when every check passes.
 */
public final class ReviewTest {

    // 一条 S400 加密广播帧样本（已脱敏）：只用于验证帧结构 / 解密 / 解析的回归。
    // 帧内 MAC 未内联（走扫描地址），解密用的 bind key 也不在仓库里；单独看这串 hex 得不到任何明文，
    // 需要配合本机自己的 bind key 才能解出内容——所以它不构成可解密的数据。
    private static final String S400_FRAME =
            "4859d53b3587e460bbb05d55a07a5c5bb90200005623a4c9";
    private static final String SCAN_MAC = "E8:4A:54:12:34:56";

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) {
        System.out.println("weightlog JVM regression harness");
        System.out.println("--------------------------------");

        testCcmNistVector();
        testKnownS400Frame();
        testParseScalePayload();
        testWeighSessionMergeAndDedupe();
        testWeighSessionReset();
        testWeighSessionQuietFinalize();
        testWeighSessionForceFinalize();
        testWeighSessionHeartbeat();
        testWeighSessionRawCap();
        testBodyComposition();
        testMeasurementJson();
        testRescuePolicy();
        testNegatives();

        System.out.println("--------------------------------");
        if (!failures.isEmpty()) {
            System.out.println("failed checks:");
            for (int i = 0; i < failures.size(); i++) {
                System.out.println("  - " + failures.get(i));
            }
        }
        System.out.println("PASS " + passed + " / FAIL " + failed);
        System.out.println(failed == 0 ? "RESULT OK" : "RESULT FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ==================== 1. AES-CCM vs NIST SP 800-38C Example 1 ====================

    private static void testCcmNistVector() {
        byte[] key = hex("404142434445464748494a4b4c4d4e4f");
        byte[] nonce = hex("10111213141516");
        byte[] aad = hex("0001020304050607");
        byte[] ciphertext = hex("7162015b");
        byte[] mic = hex("4dac255d");

        byte[] plain = ccmDecrypt(key, nonce, aad, ciphertext, mic, 4);
        check("ccm: NIST SP 800-38C example 1 plaintext 20212223",
                plain != null && "20212223".equals(hex(plain)));

        byte[] tamperedMic = mic.clone();
        tamperedMic[3] = (byte) (tamperedMic[3] ^ 0x01);
        check("ccm: tampered MIC rejected",
                ccmDecrypt(key, nonce, aad, ciphertext, tamperedMic, 4) == null);

        byte[] wrongKey = key.clone();
        wrongKey[0] = (byte) (wrongKey[0] ^ 0x01);
        check("ccm: wrong key rejected",
                ccmDecrypt(wrongKey, nonce, aad, ciphertext, mic, 4) == null);
    }

    // ==================== 2. Known S400 frame ====================

    private static void testKnownS400Frame() {
        byte[] bytes = hex(S400_FRAME);
        MiBeaconDecoder.Frame frame = MiBeaconDecoder.parse(bytes, SCAN_MAC);

        check("frame: known S400 frame parses", frame != null);
        if (frame == null) {
            return;
        }
        eqInt("frame: version 5", frame.version, 5);
        eqInt("frame: productId 0x3BD5", frame.productId, 0x3BD5);
        eqInt("frame: counter 0x35", frame.counter, 0x35);
        check("frame: has object, is encrypted, no in-frame MAC",
                frame.hasObject && frame.encrypted && !frame.hasMac);
        eq("frame: rawHex preserved", frame.rawHex, S400_FRAME);
        eq("frame: MAC taken from scan address", hex(frame.mac), "e84a54123456");
        eqInt("frame: ciphertext length 12", ((byte[]) field(frame, "ciphertext")).length, 12);
        eq("frame: nonce tail 020000", hex((byte[]) field(frame, "nonceTail")), "020000");
        eq("frame: MIC 5623a4c9", hex((byte[]) field(frame, "mic")), "5623a4c9");
        check("frame: not decrypted before decrypt()",
                !frame.decrypted && frame.payload == null);
    }

    // ==================== 3. parseScalePayload vectors ====================

    private static void testParseScalePayload() {
        MiBeaconDecoder.ScalePacket weight = MiBeaconDecoder.parseScalePayload(
                weightPayload(MiBeaconDecoder.OBJECT_WEIGHT, 1, 654, 0, 5000));
        check("payload: weight packet parses", weight != null);
        if (weight != null) {
            eqDouble("payload: weight 65.4 kg", weight.massKg, 65.4);
            eqDouble("payload: low impedance 500.0 ohm", weight.impedanceLowOhm, 500.0);
            check("payload: weight packet is not stabilized and has no heart rate",
                    !weight.stabilized && weight.heartRate == null && weight.impedanceHighOhm == null);
            eqInt("payload: object type 0x6E16", weight.objectType, MiBeaconDecoder.OBJECT_WEIGHT);
            eqInt("payload: profile id 1", weight.profileId, 1);
        }

        MiBeaconDecoder.ScalePacket finalPacket = MiBeaconDecoder.parseScalePayload(
                weightPayload(MiBeaconDecoder.OBJECT_WEIGHT, 1, 0, 0, 5579));
        check("payload: final packet parses", finalPacket != null);
        if (finalPacket != null) {
            eqDouble("payload: final high impedance 557.9 ohm", finalPacket.impedanceHighOhm, 557.9);
            check("payload: final packet is stabilized with no mass/HR",
                    finalPacket.stabilized && finalPacket.massKg == null
                            && finalPacket.heartRate == null);
        }

        MiBeaconDecoder.ScalePacket withHr = MiBeaconDecoder.parseScalePayload(
                weightPayload(MiBeaconDecoder.OBJECT_WEIGHT_ALT, 2, 654, 22, 5000));
        check("payload: heart-rate packet parses", withHr != null);
        if (withHr != null) {
            eqInt("payload: heart rate 72 bpm", withHr.heartRate, 72);
            eqInt("payload: alternate object 0x4E16", withHr.objectType,
                    MiBeaconDecoder.OBJECT_WEIGHT_ALT);
            eqInt("payload: alternate profile id 2", withHr.profileId, 2);
        }

        MiBeaconDecoder.ScalePacket reset = MiBeaconDecoder.parseScalePayload(
                weightPayload(MiBeaconDecoder.OBJECT_WEIGHT, 1, 0, 0, 0));
        check("payload: reset packet flagged", reset != null && reset.reset && !reset.stabilized);
        if (reset != null) {
            check("payload: reset packet has no values",
                    reset.massKg == null && reset.impedanceLowOhm == null
                            && reset.impedanceHighOhm == null && reset.heartRate == null);
        }

        MiBeaconDecoder.ScalePacket weightOnly = MiBeaconDecoder.parseScalePayload(
                weightPayload(MiBeaconDecoder.OBJECT_WEIGHT, 1, 654, 0, 0));
        check("payload: weight-only packet is directly stabilized",
                weightOnly != null && weightOnly.stabilized
                        && weightOnly.impedanceLowOhm == null);

        check("payload: null payload returns null",
                MiBeaconDecoder.parseScalePayload(null) == null);
        check("payload: empty payload returns null",
                MiBeaconDecoder.parseScalePayload(new byte[0]) == null);
        boolean truncatedSafe;
        try {
            truncatedSafe = MiBeaconDecoder.parseScalePayload(
                    new byte[]{0x16, 0x6e, 5, 1}) == null;
        } catch (RuntimeException e) {
            truncatedSafe = false;
        }
        check("payload: truncated payload returns null without throwing", truncatedSafe);

        boolean unknownSafe;
        try {
            unknownSafe = MiBeaconDecoder.parseScalePayload(
                    new byte[]{0x34, 0x12, 1, 0}) == null;
        } catch (RuntimeException e) {
            unknownSafe = false;
        }
        check("payload: unknown object type returns null without throwing", unknownSafe);
    }

    // ==================== 4a. WeighSession merge + 5 s dedupe ====================

    private static void testWeighSessionMergeAndDedupe() {
        Collector collector = new Collector();
        WeighSession session = new WeighSession(collector);

        session.onPacket(packet(65.4, 500.0, null, null, false, false), "aa01", "pl01");
        eqInt("session: weight packet emits one live update", collector.lives.size(), 1);
        eqDouble("session: live weight 65.4", collector.lives.get(0).weightKg, 65.4);
        eqDouble("session: live low impedance 500.0", collector.lives.get(0).impedanceLowOhm, 500.0);
        check("session: open after weight packet", session.isOpen());

        session.onPacket(packet(null, null, 557.9, null, true, false), "aa02", "pl02");
        eqInt("session: weight + final merge into exactly one record", collector.finals.size(), 1);
        check("session: closed after final", !session.isOpen());

        Measurement measurement = collector.finals.get(0);
        eqDouble("session: merged weight 65.4", measurement.weightKg, 65.4);
        eqDouble("session: merged low impedance 500.0", measurement.impedanceLowOhm, 500.0);
        eqDouble("session: merged high impedance 557.9", measurement.impedanceHighOhm, 557.9);
        check("session: no heart rate in merged record", measurement.heartRate == null);
        check("session: merged record is stabilized", measurement.stabilized);
        eq("session: merged source ble", measurement.source, "ble");
        eqInt("session: merged calc_version is current formula",
                measurement.calcVersion, BodyComposition.CALC_VERSION);
        check("session: rawJson contains both adv and payload hex",
                measurement.rawJson.contains("aa01") && measurement.rawJson.contains("pl01")
                        && measurement.rawJson.contains("aa02") && measurement.rawJson.contains("pl02"));
        eqInt("session: rawJson has two packets", count(measurement.rawJson, "\"hex\""), 2);
        check("session: heartbeat right after final returns null",
                session.onHeartbeat() == null);

        session.onPacket(packet(65.4, 500.0, null, null, false, false), "aa03", null);
        session.onPacket(packet(null, null, 557.9, null, true, false), "aa04", null);
        eqInt("session: identical final within 5 s suppressed", collector.finals.size(), 1);

        setSessionLong(session, "lastFinalAtMs", System.currentTimeMillis() - 6000L);
        session.onPacket(packet(65.4, 500.0, null, null, false, false), "aa05", null);
        session.onPacket(packet(null, null, 557.9, null, true, false), "aa06", null);
        eqInt("session: next final allowed after 5 s window", collector.finals.size(), 2);
    }

    // ==================== 4b. reset packet ====================

    private static void testWeighSessionReset() {
        Collector collector = new Collector();
        WeighSession session = new WeighSession(collector);

        session.onPacket(packet(65.4, 500.0, null, null, false, false), "r1", "rp1");
        check("reset: session open before reset packet", session.isOpen());

        session.onPacket(packet(null, null, null, null, false, true), "r2", null);
        check("reset: reset packet closes the session", !session.isOpen());
        eqInt("reset: reset packet emits no record", collector.finals.size(), 0);

        session.onPacket(packet(null, null, 557.9, null, true, false), "r3", null);
        eqInt("reset: final packet after reset is ignored", collector.finals.size(), 0);
    }

    // ==================== 4c. quiet finalize ====================

    private static void testWeighSessionQuietFinalize() {
        Collector collector = new Collector();
        WeighSession session = new WeighSession(collector);

        check("quiet: fresh session returns null",
                session.pollQuietFinalize(System.currentTimeMillis() + 10000L, 0L) == null);

        session.onPacket(packet(65.4, null, null, null, false, false), "q1", null);
        check("quiet: weight without low impedance returns null",
                session.pollQuietFinalize(System.currentTimeMillis() + 10000L, 0L) == null);

        session.onPacket(packet(null, 500.0, null, null, false, false), "q2", null);
        long now = System.currentTimeMillis();
        check("quiet: before quietMs returns null",
                session.pollQuietFinalize(now + 500L, 3000L) == null);

        Measurement measurement = session.pollQuietFinalize(now + 3000L, 3000L);
        check("quiet: after quietMs returns a record", measurement != null);
        if (measurement != null) {
            eqDouble("quiet: record weight 65.4", measurement.weightKg, 65.4);
            eqDouble("quiet: record low impedance 500.0", measurement.impedanceLowOhm, 500.0);
            eq("quiet: record source ble", measurement.source, "ble");
        }
        check("quiet: session closed after quiet finalize", !session.isOpen());
        check("quiet: second poll returns null",
                session.pollQuietFinalize(now + 10000L, 1000L) == null);
    }

    // ==================== 4d. heartbeat finalize ====================

    private static void testWeighSessionForceFinalize() {
        Collector collector = new Collector();
        WeighSession session = new WeighSession(collector);

        check("force: fresh session returns null",
                session.forceFinalize(System.currentTimeMillis()) == null);

        // 只拿到体重（还没稳定/没有阻抗）时暂停也要落一条
        session.onPacket(packet(65.4, null, null, null, false, false), "ff1", null);
        Measurement measurement = session.forceFinalize(System.currentTimeMillis());
        check("force: weight-only session finalizes", measurement != null);
        if (measurement != null) {
            eqDouble("force: record weight 65.4", measurement.weightKg, 65.4);
            eq("force: record source ble", measurement.source, "ble");
            check("force: record stabilized", measurement.stabilized);
        }
        check("force: session closed after finalize", !session.isOpen());

        // 5 秒去重：刚 final 过 1 s 的会话再兜底收尾应返回 null
        Collector collector2 = new Collector();
        WeighSession session2 = new WeighSession(collector2);
        session2.onPacket(packet(66.1, null, null, null, false, false), "ff2", null);
        long now = System.currentTimeMillis();
        setSessionLong(session2, "lastFinalAtMs", now - 1000L);
        check("force: within 5s dedupe returns null", session2.forceFinalize(now) == null);
    }

    private static void testWeighSessionHeartbeat() {
        Collector collector = new Collector();
        WeighSession session = new WeighSession(collector);

        check("heartbeat: fresh session returns null", session.onHeartbeat() == null);

        session.onPacket(packet(65.4, 500.0, null, null, false, false), "h1", "hp1");
        Measurement measurement = session.onHeartbeat();
        check("heartbeat: finalizes with weight + low impedance", measurement != null);
        if (measurement != null) {
            eqDouble("heartbeat: weight 65.4", measurement.weightKg, 65.4);
            eqDouble("heartbeat: low impedance 500.0", measurement.impedanceLowOhm, 500.0);
            eq("heartbeat: source ble", measurement.source, "ble");
        }
        eqInt("heartbeat: does not go through listener.onFinal", collector.finals.size(), 0);
        check("heartbeat: dedupe right after final returns null",
                session.onHeartbeat() == null);

        session.onPacket(packet(65.4, null, null, null, false, false), "h2", null);
        check("heartbeat: no low impedance returns null", session.onHeartbeat() == null);
        check("heartbeat: failed heartbeat resets the session", !session.isOpen());
    }

    // ==================== 4e. raw packet history cap ====================

    private static void testWeighSessionRawCap() {
        Collector collector = new Collector();
        WeighSession session = new WeighSession(collector);

        for (int i = 0; i < 45; i++) {
            session.onPacket(packet(65.4, 500.0, null, null, false, false),
                    String.format("c%03d", Integer.valueOf(i)), null);
        }
        eqInt("rawcap: live updates deduped", collector.lives.size(), 1);

        session.onPacket(packet(null, null, 557.9, null, true, false), "c999", null);
        eqInt("rawcap: final still produced after 45 packets", collector.finals.size(), 1);
        if (collector.finals.isEmpty()) {
            return;
        }
        String rawJson = collector.finals.get(0).rawJson;
        eqInt("rawcap: rawJson capped at 40 packets", count(rawJson, "\"hex\""), 40);
        check("rawcap: rawJson keeps newest packets",
                rawJson.contains("c044") && rawJson.contains("c999"));
        check("rawcap: rawJson drops the oldest packets", !rawJson.contains("c005"));
    }

    // ==================== 5. BodyComposition ====================

    private static void testBodyComposition() {
        check("bodycomp: missing height returns null",
                BodyComposition.bodyFatPct(65.4, 557.9, 0, 30, "male") == null);
        check("bodycomp: missing age returns null",
                BodyComposition.bodyFatPct(65.4, 557.9, 175, 0, "male") == null);
        check("bodycomp: invalid weight returns null",
                BodyComposition.bodyFatPct(5.0, 557.9, 175, 30, "male") == null);
        check("bodycomp: invalid impedance returns null",
                BodyComposition.bodyFatPct(65.4, 0.0, 175, 30, "male") == null);

        Double maleFat = BodyComposition.bodyFatPct(65.4, 557.9, 175, 30, "male");
        eqDouble("bodycomp: male body fat 16.4 % for 65.4 kg / 557.9 ohm", maleFat, 16.4);
        Double femaleFat = BodyComposition.bodyFatPct(65.4, 557.9, 175, 30, "female");
        eqDouble("bodycomp: female body fat 30.1 %", femaleFat, 30.1);
        check("bodycomp: female branch differs from male",
                maleFat != null && femaleFat != null && !femaleFat.equals(maleFat));

        eqDouble("bodycomp: BMI 21.4 for 65.4 kg / 175 cm",
                BodyComposition.bmi(65.4, 175), 21.4);
        check("bodycomp: BMI null for zero weight", BodyComposition.bmi(0.0, 175) == null);
        check("bodycomp: BMI null for zero height", BodyComposition.bmi(65.4, 0) == null);

        eqInt("bodycomp: BMR 1603 kcal for 65.4 kg / 175 cm / 30 y male",
                BodyComposition.bmrKcal(65.4, 175, 30, "male"), 1603);
        eqInt("bodycomp: BMR 1437 kcal for same profile female",
                BodyComposition.bmrKcal(65.4, 175, 30, "female"), 1437);
        check("bodycomp: BMR null for missing age",
                BodyComposition.bmrKcal(65.4, 175, 0, "male") == null);

        eq("bodycomp: normalizeSex(null) -> male", BodyComposition.normalizeSex(null), "male");
        eq("bodycomp: normalizeSex(FEMALE) -> female",
                BodyComposition.normalizeSex("FEMALE"), "female");

        eqDouble("bodycomp: HALF_EVEN tie 2.25 -> 2.2", round1(2.25), 2.2);
        eqDouble("bodycomp: HALF_EVEN tie 2.75 -> 2.8", round1(2.75), 2.8);
    }

    // ==================== 6. Measurement JSON ====================

    private static void testMeasurementJson() {
        Measurement measurement = new Measurement();
        measurement.measuredAtMs = 1700000000000L;
        measurement.tzOffsetMin = 480;
        measurement.weightKg = 65.4;
        measurement.impedanceLowOhm = 500.0;
        measurement.source = Measurement.SOURCE_BLE;
        measurement.rawJson = "{\"packets\":[]}";

        String withRaw = measurement.toJson(true).toString();
        check("measurement json: contains weight and source",
                withRaw.contains("\"weight_kg\":65.4") && withRaw.contains("\"source\":\"ble\""));
        check("measurement json: null note serialized as null",
                withRaw.contains("\"note\":null"));
        check("measurement json: includeRaw=true adds raw_json",
                withRaw.contains("\"raw_json\""));
        check("measurement json: includeRaw=false omits raw_json",
                !measurement.toJson(false).toString().contains("\"raw_json\""));
    }

    // ==================== 7. negative / safety cases ====================

    private static void testNegatives() {
        check("negative: parse(null) returns null",
                MiBeaconDecoder.parse(null, SCAN_MAC) == null);
        check("negative: parse(short frame) returns null",
                MiBeaconDecoder.parse(new byte[]{0x48}, SCAN_MAC) == null);
        check("negative: decrypt(null) returns false",
                !MiBeaconDecoder.decrypt(null, new byte[16]));
        check("negative: unknown product id not accepted",
                !MiBeaconDecoder.isScaleProductId(0x1234)
                        && MiBeaconDecoder.isScaleProductId(0x3BD5)
                        && MiBeaconDecoder.isScaleProductId(0x30D9));

        byte[] other = hex(S400_FRAME);
        other[2] = 0x34;
        other[3] = 0x12;
        MiBeaconDecoder.Frame otherFrame = MiBeaconDecoder.parse(other, SCAN_MAC);
        check("negative: frame with unknown product id is not a scale",
                otherFrame != null && !MiBeaconDecoder.isScaleProductId(otherFrame.productId));

        byte[] inFrameMac = hex("1050d53b01563412544ae8");
        check("negative: in-frame MAC matches scan address",
                MiBeaconDecoder.parse(inFrameMac, "E8:4A:54:12:34:56") != null);
        check("negative: in-frame MAC mismatch rejected",
                MiBeaconDecoder.parse(inFrameMac, "E8:4A:54:12:34:57") == null);
    }

    // ==================== 8. rescue policy (timeout → re-discover) ====================

    private static void testRescuePolicy() {
        RescuePolicy p = new RescuePolicy(1000L, 5000L, 3);
        p.reset(0L);
        check("rescue: no trigger before timeout", !p.shouldRescue(999L));
        check("rescue: triggers at timeout", p.shouldRescue(1000L));
        p.beginRescue(1000L);
        check("rescue: active blocks re-trigger", p.isActive() && !p.shouldRescue(2000L));
        check("rescue: count incremented once", p.getRescueCount() == 1);
        p.endRescue(2000L);
        check("rescue: countdown restarts after rescue", !p.shouldRescue(2999L));
        check("rescue: cooldown blocks retry even after timeout", !p.shouldRescue(3000L));
        check("rescue: triggers again after cooldown", p.shouldRescue(6001L));

        // 冷却：紧接上一次重新发现，即使超时也不允许立刻再来一次
        RescuePolicy cool = new RescuePolicy(1000L, 5000L, 3);
        cool.reset(0L);
        cool.beginRescue(1000L);
        cool.endRescue(1000L);
        check("rescue: cooldown blocks immediate retry", !cool.shouldRescue(1500L));
        check("rescue: cooldown expires", cool.shouldRescue(6001L));

        // 解密连续失败是另一个触发条件
        RescuePolicy bad = new RescuePolicy(1000000L, 5000L, 3);
        bad.reset(0L);
        bad.noteDecodeFailure();
        bad.noteDecodeFailure();
        check("rescue: two decode failures are not enough", !bad.shouldRescue(10L));
        bad.noteDecodeFailure();
        check("rescue: three decode failures trigger", bad.shouldRescue(10L));
        bad.beginRescue(10L);
        bad.endRescue(10L);
        check("rescue: end clears decode failures", bad.getDecodeFailures() == 0
                && !bad.shouldRescue(20L));

        // 收到自家秤的广播会复位计时
        RescuePolicy good = new RescuePolicy(1000L, 5000L, 3);
        good.reset(0L);
        good.noteGood(900L);
        check("rescue: good packet resets timeout", !good.shouldRescue(1500L));
        check("rescue: triggers after fresh timeout", good.shouldRescue(1900L));

        // 心跳（noteAlive）只续命、不清失败计数：否则「MAC 填成邻居」时失败路径永远不触发
        RescuePolicy mixed = new RescuePolicy(1000000L, 1L, 3);
        mixed.reset(0L);
        mixed.noteAlive(100L);
        mixed.noteDecodeFailure();
        mixed.noteAlive(200L);
        mixed.noteDecodeFailure();
        check("rescue: two interleaved failures still below limit", !mixed.shouldRescue(200L));
        mixed.noteAlive(300L);
        mixed.noteDecodeFailure();
        check("rescue: heartbeats do not reset decode failures", mixed.shouldRescue(300L));

        // 只有认证成功（noteGood）才清零失败计数
        RescuePolicy auth = new RescuePolicy(1000000L, 1L, 3);
        auth.reset(0L);
        auth.noteDecodeFailure();
        auth.noteDecodeFailure();
        check("rescue: two failures are pending", auth.getDecodeFailures() == 2);
        auth.noteGood(10L);
        check("rescue: authenticated frame clears failures",
                auth.getDecodeFailures() == 0 && !auth.shouldRescue(20L));

        // 称重层打开后超时变短
        RescuePolicy live = new RescuePolicy(120000L, 5000L, 3);
        live.reset(0L);
        check("rescue: default (idle) timeout is long", !live.shouldRescue(60000L));
        live.setLiveMode(true);
        check("rescue: live mode shortens timeout",
                live.getDataTimeoutMs() == RescuePolicy.LIVE_DATA_TIMEOUT_MS
                        && live.shouldRescue(60000L));
        live.setLiveMode(false);
        check("rescue: live off restores idle timeout",
                live.getDataTimeoutMs() == RescuePolicy.DEFAULT_DATA_TIMEOUT_MS);
    }

    // ==================== helpers ====================

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            failures.add(name);
            System.out.println("FAIL  " + name);
        }
    }

    private static void eq(String name, Object actual, Object expected) {
        boolean ok = actual == null ? expected == null : actual.equals(expected);
        check(name + (ok ? "" : " (expected=" + expected + ", actual=" + actual + ")"), ok);
    }

    private static void eqInt(String name, int actual, int expected) {
        eq(name, Integer.valueOf(actual), Integer.valueOf(expected));
    }

    private static void eqDouble(String name, Double actual, double expected) {
        if (actual == null) {
            check(name + " (expected=" + expected + ", actual=null)", false);
        } else if (actual.doubleValue() == expected) {
            check(name, true);
        } else {
            check(name + " (expected=" + expected + ", actual=" + actual.doubleValue() + ")", false);
        }
    }

    private static MiBeaconDecoder.ScalePacket packet(Double massKg, Double lowOhm, Double highOhm,
                                                      Integer heartRate, boolean stabilized,
                                                      boolean reset) {
        MiBeaconDecoder.ScalePacket p = new MiBeaconDecoder.ScalePacket();
        p.objectType = MiBeaconDecoder.OBJECT_WEIGHT;
        p.profileId = 1;
        p.massKg = massKg;
        p.impedanceLowOhm = lowOhm;
        p.impedanceHighOhm = highOhm;
        p.heartRate = heartRate;
        p.stabilized = stabilized;
        p.reset = reset;
        return p;
    }

    /**
     * Builds a decrypted 0x6E16-style object stream:
     * [type LE 2B][len=5][profileId][v LE 4B] with
     * massRaw = v &amp; 0x7FF, hrRaw = (v &gt;&gt; 11) &amp; 0x7F, impRaw = v &gt;&gt;&gt; 18.
     */
    private static byte[] weightPayload(int objectType, int profileId, int massRaw, int hrRaw,
                                        int impRaw) {
        int v = (massRaw & 0x7FF) | ((hrRaw & 0x7F) << 11) | ((impRaw & 0x3FFF) << 18);
        return new byte[]{
                (byte) (objectType & 0xFF),
                (byte) ((objectType >> 8) & 0xFF),
                5,
                (byte) (profileId & 0xFF),
                (byte) (v & 0xFF),
                (byte) ((v >> 8) & 0xFF),
                (byte) ((v >> 16) & 0xFF),
                (byte) ((v >>> 24) & 0xFF)
        };
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(out);
    }

    private static byte[] hex(String hex) {
        if (hex == null) {
            return null;
        }
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("odd hex length: " + hex.length());
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ---- reflection bridges to production internals (kept in once place) ----

    private static byte[] ccmDecrypt(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertext,
                                     byte[] mic, int tagLength) {
        try {
            Method method = MiBeaconDecoder.class.getDeclaredMethod("ccmDecrypt",
                    byte[].class, byte[].class, byte[].class, byte[].class, byte[].class, int.class);
            method.setAccessible(true);
            return (byte[]) method.invoke(null, key, nonce, aad, ciphertext, mic,
                    Integer.valueOf(tagLength));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "MiBeaconDecoder.ccmDecrypt(...) not found; private API changed?", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("ccmDecrypt threw", e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("cannot invoke ccmDecrypt", e);
        }
    }

    private static double round1(double value) {
        try {
            Method method = BodyComposition.class.getDeclaredMethod("round1", double.class);
            method.setAccessible(true);
            return ((Double) method.invoke(null, Double.valueOf(value))).doubleValue();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "BodyComposition.round1(double) not found; private API changed?", e);
        } catch (Exception e) {
            throw new IllegalStateException("cannot invoke round1", e);
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read field " + name, e);
        }
    }

    private static void setSessionLong(WeighSession session, String name, long value) {
        try {
            Field field = WeighSession.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setLong(session, value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot write field " + name, e);
        }
    }

    private static final class Collector implements WeighSession.Listener {
        final List<WeighSession.LiveData> lives = new ArrayList<WeighSession.LiveData>();
        final List<Measurement> finals = new ArrayList<Measurement>();
        final List<String> errors = new ArrayList<String>();

        @Override
        public void onLive(WeighSession.LiveData live) {
            lives.add(live);
        }

        @Override
        public void onFinal(Measurement measurement) {
            finals.add(measurement);
        }

        @Override
        public void onSessionError(String message) {
            errors.add(message);
        }
    }

    private ReviewTest() {
    }
}
