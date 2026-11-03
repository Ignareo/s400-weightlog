package com.weightlog.app.scale;

import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * MiBeacon v4/v5 广播解析 + AES-CCM 解密 + 小米体脂秤 0x6E16 object 解析。
 *
 * 纯 Java：只依赖 java.* / javax.crypto，不 import android.*，方便在 Mac JVM 上离线验证。
 * Android 6 的 AES/CCM provider 不可靠，这里用 AES/ECB/NoPadding 按 RFC 3610 自己实现 CCM
 * （CBC-MAC + CTR），算法与 Mac 端 xiaomi-ble 的 AESCCM(tag_length=4) 完全一致。
 *
 * 帧格式（与 xiaomi-ble parser.py 对齐）：
 *   frctrl    = data[0] | data[1]<<8
 *   version   = frctrl >> 12
 *   object    = (frctrl >> 6) & 1     // 0 = 心跳/空闲包
 *   hasMac    = (frctrl >> 4) & 1     // 帧内自带 MAC（倒序）
 *   encrypted = (frctrl >> 3) & 1
 *   productId = data[2] | data[3]<<8
 *   counter   = data[4]
 *   i         = 5 (+6 带 MAC) (+1 capability，capability&0x20 再多 1)
 *
 * 解密参数：
 *   nonce = MAC6(带 MAC 帧取 data[5:11]，否则取扫描地址的倒序) + data[2:5] + data[-7:-4]
 *   aad   = 0x11；tag = 4B；ct = data[i:-7]；mic = data[-4:]
 */
public final class MiBeaconDecoder {

    /** S400 / MJTZC01YM 的 product id */
    public static final int PRODUCT_ID_S400 = 0x3BD5;
    /** 兼容的另一个 product id（Body Composition Scale） */
    public static final int PRODUCT_ID_ALT = 0x30D9;

    /** S400 体重/阻抗 object */
    public static final int OBJECT_WEIGHT = 0x6E16;
    /** 变体 object（字段布局相同，按同样方式解析） */
    public static final int OBJECT_WEIGHT_ALT = 0x4E16;

    private static final byte[] AAD = new byte[]{0x11};
    private static final int TAG_LENGTH = 4;

    private MiBeaconDecoder() {
    }

    // ========== 帧结构 ==========

    public static final class Frame {
        public int frameControl;
        public int version;
        public int productId;
        public int counter;
        public boolean encrypted;
        public boolean hasObject;
        public boolean hasMac;
        /** 正常顺序的 6 字节 MAC（E8 4A 54 49 C6 46） */
        public byte[] mac;
        /** 扫描结果里的设备地址（AA:BB:...），可能为 null */
        public String scanAddress;
        public byte[] raw;
        public String rawHex;
        /** 解密后（或明文）的 object 流；心跳包为 null */
        public byte[] payload;
        public String payloadHex;
        public boolean decrypted;

        int payloadOffset;
        byte[] macPrefix;
        byte[] nonceTail;
        byte[] ciphertext;
        byte[] mic;
    }

    /** 0x6E16 解析结果（结构与 Mac 端 obj6e16 的取值规则一致） */
    public static final class ScalePacket {
        public int objectType;
        public int profileId;
        /** 三个字段全为 0：人下秤，本次会话复位 */
        public boolean reset;
        /** true = 收尾包（高频阻抗/仅体重）；false = 称重中（低频阻抗） */
        public boolean stabilized;
        public Double massKg;
        public Integer heartRate;
        public Double impedanceLowOhm;
        public Double impedanceHighOhm;
    }

    // ========== 工具 ==========

    /** 统一 MAC 格式：去分隔符、转大写、每 2 位插冒号；非法输入返回清理后的 hex。 */
    public static String normalizeMac(String mac) {
        if (mac == null) {
            return "";
        }
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < mac.length(); i++) {
            char c = mac.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                hex.append(Character.toUpperCase(c));
            }
        }
        if (hex.length() != 12) {
            return hex.toString();
        }
        StringBuilder out = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                out.append(':');
            }
            out.append(hex.charAt(i)).append(hex.charAt(i + 1));
        }
        return out.toString();
    }

    /** MAC 字符串转 6 字节；格式不对返回 null。 */
    public static byte[] macToBytes(String mac) {
        if (mac == null) {
            return null;
        }
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < mac.length(); i++) {
            char c = mac.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                hex.append(c);
            }
        }
        if (hex.length() != 12) {
            return null;
        }
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null) {
            return null;
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                clean.append(c);
            }
        }
        if (clean.length() == 0 || clean.length() % 2 != 0) {
            return null;
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 小写 hex，与 Python bytes.hex() 一致。 */
    public static String toHex(byte[] data) {
        if (data == null) {
            return "";
        }
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(out);
    }

    public static boolean isScaleProductId(int productId) {
        return productId == PRODUCT_ID_S400 || productId == PRODUCT_ID_ALT;
    }

    // ========== 帧解析 ==========

    /**
     * 解析 0xFE95 service data（不含 0x95 0xFE 前缀，直接是 MiBeacon 帧）。
     *
     * @param data        service data 原始字节
     * @param scanAddress 扫描结果里的设备地址；帧内不带 MAC 时用它构造 nonce
     * @return 无法解析 / mesh / 旧版本 / MAC 不一致时返回 null
     */
    public static Frame parse(byte[] data, String scanAddress) {
        if (data == null || data.length < 5) {
            return null;
        }
        int frctrl = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8);
        if (((frctrl >> 7) & 1) != 0) {
            return null; // mesh 设备不支持
        }
        int version = frctrl >> 12;
        if (version < 2) {
            return null;
        }
        boolean hasObject = ((frctrl >> 6) & 1) != 0;
        boolean hasCapability = ((frctrl >> 5) & 1) != 0;
        boolean hasMac = ((frctrl >> 4) & 1) != 0;
        boolean encrypted = ((frctrl >> 3) & 1) != 0;

        byte[] scanMac = macToBytes(scanAddress);
        byte[] mac;
        byte[] macPrefix;
        int i = 5;
        if (hasMac) {
            if (data.length < 11) {
                return null;
            }
            macPrefix = Arrays.copyOfRange(data, 5, 11);
            mac = reverse(macPrefix);
            if (scanMac != null && !Arrays.equals(scanMac, mac)) {
                // 帧内 MAC 与扫描地址不一致：不是同一台设备，直接丢弃
                return null;
            }
            i += 6;
        } else {
            if (scanMac == null) {
                return null; // 没有 MAC 就无法构造 nonce
            }
            mac = scanMac;
            macPrefix = reverse(scanMac);
        }

        if (hasCapability) {
            if (data.length < i + 1) {
                return null;
            }
            int capability = data[i] & 0xFF;
            i += 1;
            if ((capability & 0x20) != 0) {
                if (data.length < i + 1) {
                    return null;
                }
                i += 1;
            }
        }

        Frame frame = new Frame();
        frame.frameControl = frctrl;
        frame.version = version;
        frame.productId = (data[2] & 0xFF) | ((data[3] & 0xFF) << 8);
        frame.counter = data[4] & 0xFF;
        frame.encrypted = encrypted;
        frame.hasObject = hasObject;
        frame.hasMac = hasMac;
        frame.mac = mac;
        frame.scanAddress = scanAddress;
        frame.raw = data;
        frame.rawHex = toHex(data);
        frame.payloadOffset = i;
        frame.macPrefix = macPrefix;

        if (!hasObject) {
            // 心跳/空闲包：没有 object，用于复位本次称重
            return frame;
        }
        if (!encrypted) {
            if (data.length < i + 3) {
                return null;
            }
            frame.payload = Arrays.copyOfRange(data, i, data.length);
            frame.payloadHex = toHex(frame.payload);
            frame.decrypted = true;
            return frame;
        }

        // 与 xiaomi-ble 一致：加密帧至少要给 object/ct/MIC 留出空间
        if (data.length < i + 9) {
            return null;
        }
        frame.nonceTail = Arrays.copyOfRange(data, data.length - 7, data.length - 4);
        frame.mic = Arrays.copyOfRange(data, data.length - 4, data.length);
        frame.ciphertext = Arrays.copyOfRange(data, i, data.length - 7);
        return frame;
    }

    /**
     * 用 bind key 解密加密帧；成功时填充 frame.payload，失败（MIC 不对）返回 false。
     */
    public static boolean decrypt(Frame frame, byte[] bindKey) {
        if (frame == null) {
            return false;
        }
        if (!frame.encrypted) {
            return frame.payload != null;
        }
        if (bindKey == null || bindKey.length != 16 || frame.ciphertext == null) {
            return false;
        }
        byte[] nonce = new byte[12];
        System.arraycopy(frame.macPrefix, 0, nonce, 0, 6);
        System.arraycopy(frame.raw, 2, nonce, 6, 3);
        System.arraycopy(frame.nonceTail, 0, nonce, 9, 3);

        byte[] plain = ccmDecrypt(bindKey, nonce, AAD, frame.ciphertext, frame.mic, TAG_LENGTH);
        if (plain == null) {
            return false;
        }
        frame.payload = plain;
        frame.payloadHex = toHex(plain);
        frame.decrypted = true;
        return true;
    }

    // ========== 0x6E16 object 解析 ==========

    /**
     * 解析解密后的 object 流（[type 2B LE][len 1B][data]），找第一个体重 object。
     * 字段取值规则与 Mac 端 obj6e16 一致：
     *   mass       = (v & 0x7FF) / 10
     *   heart_rate = ((v >> 11) & 0x7F) + 50（0/127 视为无）
     *   impedance  = (v >> 18) / 10
     */
    public static ScalePacket parseScalePayload(byte[] payload) {
        if (payload == null || payload.length < 3) {
            return null;
        }
        int pos = 0;
        while (pos + 3 <= payload.length) {
            int type = (payload[pos] & 0xFF) | ((payload[pos + 1] & 0xFF) << 8);
            int len = payload[pos + 2] & 0xFF;
            int start = pos + 3;
            if (start + len > payload.length) {
                break; // 负载截断/损坏
            }
            if ((type == OBJECT_WEIGHT || type == OBJECT_WEIGHT_ALT) && len >= 5) {
                return parseWeightObject(type, payload, start, len);
            }
            pos = start + len;
        }
        return null;
    }

    private static ScalePacket parseWeightObject(int type, byte[] payload, int start, int len) {
        ScalePacket p = new ScalePacket();
        p.objectType = type;
        p.profileId = payload[start] & 0xFF;
        int v = (payload[start + 1] & 0xFF)
                | ((payload[start + 2] & 0xFF) << 8)
                | ((payload[start + 3] & 0xFF) << 16)
                | ((payload[start + 4] & 0xFF) << 24);
        int massRaw = v & 0x7FF;
        int hrRaw = (v >> 11) & 0x7F;
        int impRaw = v >>> 18;

        if (massRaw == 0 && hrRaw == 0 && impRaw == 0) {
            // 人下秤 → 复位
            p.reset = true;
            p.stabilized = false;
            return p;
        }
        if (massRaw > 0) {
            p.massKg = Double.valueOf(massRaw / 10.0);
        }
        if (hrRaw > 0 && hrRaw < 127) {
            p.heartRate = Integer.valueOf(hrRaw + 50);
        }
        if (massRaw == 0 && hrRaw == 0) {
            // 收尾包：高频阻抗 250 kHz → impedance_high + stabilized
            if (impRaw > 0) {
                p.impedanceHighOhm = Double.valueOf(impRaw / 10.0);
            }
            p.stabilized = true;
        } else if (impRaw > 0) {
            // 称重中的包：低频阻抗 50 kHz → impedance_low
            p.impedanceLowOhm = Double.valueOf(impRaw / 10.0);
            p.stabilized = false;
        } else {
            // 只测到体重（穿袜子）→ 直接稳定
            p.stabilized = true;
        }
        return p;
    }

    // ========== 手写 AES-CCM（RFC 3610，仅解密） ==========

    /**
     * AES-CCM 解密并校验 4 字节 MIC。
     * @return 明文；MIC 校验失败/参数非法返回 null
     */
    private static byte[] ccmDecrypt(byte[] key, byte[] nonce, byte[] aad,
                                     byte[] ciphertext, byte[] tag, int tagLen) {
        if (key == null || key.length != 16 || nonce == null || tag == null) {
            return null;
        }
        if (nonce.length < 7 || nonce.length > 13 || tag.length != tagLen) {
            return null;
        }
        int l = 15 - nonce.length; // CCM 的 L 参数
        if (l < 2 || l > 8) {
            return null;
        }
        try {
            Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));

            // ---- CTR 解密：P_i = C_i xor E(A_i) ----
            byte[] a0 = new byte[16];
            a0[0] = (byte) (l - 1);
            System.arraycopy(nonce, 0, a0, 1, nonce.length);
            byte[] s0 = aes.doFinal(a0);

            byte[] plain = new byte[ciphertext.length];
            int blocks = (ciphertext.length + 15) / 16;
            for (int b = 1; b <= blocks; b++) {
                byte[] ai = new byte[16];
                ai[0] = (byte) (l - 1);
                System.arraycopy(nonce, 0, ai, 1, nonce.length);
                int ctr = b;
                for (int k = 0; k < l; k++) {
                    ai[15 - k] = (byte) (ctr & 0xFF);
                    ctr >>>= 8;
                }
                byte[] si = aes.doFinal(ai);
                int off = (b - 1) * 16;
                for (int j = 0; j < 16 && off + j < ciphertext.length; j++) {
                    plain[off + j] = (byte) (ciphertext[off + j] ^ si[j]);
                }
            }

            // ---- CBC-MAC：B0 || AAD || 明文（各自补零到 16B）----
            byte[] b0 = new byte[16];
            int flags = 0;
            if (aad != null && aad.length > 0) {
                flags |= 0x40;
            }
            flags |= ((tagLen - 2) / 2) << 3;
            flags |= (l - 1);
            b0[0] = (byte) flags;
            System.arraycopy(nonce, 0, b0, 1, nonce.length);
            long msgLen = plain.length;
            for (int k = 0; k < l; k++) {
                b0[15 - k] = (byte) (msgLen & 0xFF);
                msgLen >>>= 8;
            }

            byte[] x = new byte[16];
            x = aes.doFinal(xor(x, b0));
            if (aad != null && aad.length > 0) {
                if (aad.length >= 0xFF00) {
                    return null; // 本协议 aad 固定 1 字节，不支持长 AAD
                }
                byte[] encoded = new byte[2 + aad.length];
                encoded[0] = (byte) ((aad.length >> 8) & 0xFF);
                encoded[1] = (byte) (aad.length & 0xFF);
                System.arraycopy(aad, 0, encoded, 2, aad.length);
                x = cbcMac(x, encoded, aes);
            }
            x = cbcMac(x, plain, aes);

            byte[] expected = new byte[tagLen];
            for (int i = 0; i < tagLen; i++) {
                expected[i] = (byte) (x[i] ^ s0[i]);
            }
            if (!Arrays.equals(expected, tag)) {
                return null;
            }
            return plain;
        } catch (Exception e) {
            // 任何 provider/参数异常都按解密失败处理，不向上抛
            return null;
        }
    }

    /** 对 input 按 16B 补零分块做 CBC-MAC（X_i = E(X_{i-1} xor B_i)）。 */
    private static byte[] cbcMac(byte[] x, byte[] input, Cipher aes) throws Exception {
        int blocks = (input.length + 15) / 16;
        for (int b = 0; b < blocks; b++) {
            byte[] block = new byte[16];
            int off = b * 16;
            int n = Math.min(16, input.length - off);
            System.arraycopy(input, off, block, 0, n);
            x = aes.doFinal(xor(x, block));
        }
        return x;
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    private static byte[] reverse(byte[] src) {
        byte[] out = new byte[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = src[src.length - 1 - i];
        }
        return out;
    }
}
