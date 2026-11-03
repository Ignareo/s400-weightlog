package com.weightlog.app.scale;

/**
 * 地址兜底策略（纯 Java，无 android.*，可在 test/ 的 JVM 回归里直接测）。
 *
 * <p>背景：S400 是 BLE 广播秤，不存在 IP；App 里与「IP」等价的是广播源地址（MAC）。
 * 绑定模式只接受 MAC 与配置完全一致的广播，因此一旦配置里的地址过期（换秤、恢复备份、
 * 手动填错），App 会永远等不到数据。这个策略负责判断「该不该重新广播发现」：
 *
 * <ul>
 *   <li>绑定模式下连续 {@code dataTimeoutMs} 没收到自家秤的任何广播 → 超时；</li>
 *   <li>或者连续 {@code decodeFailLimit} 次广播解密失败 → 地址/密钥可疑；</li>
 *   <li>两次重新发现之间至少间隔 {@code cooldownMs}，避免离线秤把扫描刷爆；</li>
 *   <li>称重层打开时把超时调短（用户正站在秤上，等不起）。</li>
 * </ul>
 *
 * 计时只在「绑定模式扫描中」有效：{@link #reset(long)} 进入绑定扫描时调用，
 * 离开绑定扫描（暂停 / 发现模式 / 重新发现中）时用 {@link #endRescue(long)} 之外的
 * 状态由宿主判断，不调用 {@link #noteGood(long)} 即可。
 */
public final class RescuePolicy {

    /** 后台/首页待称重时的超时（2 分钟没收到任何自家秤广播） */
    public static final long DEFAULT_DATA_TIMEOUT_MS = 120000L;
    /** 称重层打开时的超时（45 秒，用户已经站在秤上） */
    public static final long LIVE_DATA_TIMEOUT_MS = 45000L;
    /** 两次重新发现之间的冷却 */
    public static final long DEFAULT_COOLDOWN_MS = 120000L;
    /** 连续解密失败几次算地址/密钥可疑 */
    public static final int DEFAULT_DECODE_FAIL_LIMIT = 3;

    private final long cooldownMs;
    private final int decodeFailLimit;
    private long dataTimeoutMs;

    private long lastGoodAtMs;
    private int decodeFailures;
    private long lastRescueAtMs;
    private int rescueCount;
    private boolean active;

    public RescuePolicy() {
        this(DEFAULT_DATA_TIMEOUT_MS, DEFAULT_COOLDOWN_MS, DEFAULT_DECODE_FAIL_LIMIT);
    }

    public RescuePolicy(long dataTimeoutMs, long cooldownMs, int decodeFailLimit) {
        this.dataTimeoutMs = dataTimeoutMs > 0 ? dataTimeoutMs : DEFAULT_DATA_TIMEOUT_MS;
        this.cooldownMs = cooldownMs > 0 ? cooldownMs : DEFAULT_COOLDOWN_MS;
        this.decodeFailLimit = decodeFailLimit > 0 ? decodeFailLimit : DEFAULT_DECODE_FAIL_LIMIT;
    }

    /** 进入绑定扫描：从现在开始计时，清掉上一轮的失败计数。 */
    public synchronized void reset(long now) {
        lastGoodAtMs = now;
        decodeFailures = 0;
        active = false;
    }

    /**
     * 收到自家秤（配置的 MAC）的一帧广播，但还没通过认证（例如心跳包）：
     * 只把「还在广播」的计时续上，**不清**解密失败计数。
     * 否则「MAC 填成了邻居那台」时每帧都先 noteGood 再解密失败，失败计数永远到不了阈值，
     * 解密失败这条触发路径会彻底失效。
     */
    public synchronized void noteAlive(long now) {
        lastGoodAtMs = now;
    }

    /** 认证成功的帧（解密 / 解析通过）：续命计时并清掉失败计数。 */
    public synchronized void noteGood(long now) {
        lastGoodAtMs = now;
        decodeFailures = 0;
    }

    /** 自家秤的广播解密失败一次（bind key 错 / 秤被重新绑定 / 地址其实不是这台）。 */
    public synchronized void noteDecodeFailure() {
        decodeFailures++;
    }

    /** 称重层开/关时切换超时阈值；变小后下一次 shouldRescue 立即生效。 */
    public synchronized void setLiveMode(boolean live) {
        dataTimeoutMs = live ? LIVE_DATA_TIMEOUT_MS : DEFAULT_DATA_TIMEOUT_MS;
    }

    public synchronized long getDataTimeoutMs() {
        return dataTimeoutMs;
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized int getRescueCount() {
        return rescueCount;
    }

    public synchronized long getLastRescueAtMs() {
        return lastRescueAtMs;
    }

    public synchronized int getDecodeFailures() {
        return decodeFailures;
    }

    /**
     * 是否应该进入「重新广播发现」。
     *
     * @param now 当前时间（毫秒）
     */
    public synchronized boolean shouldRescue(long now) {
        if (active) {
            return false;
        }
        boolean timedOut = now - lastGoodAtMs >= dataTimeoutMs;
        boolean suspicious = decodeFailures >= decodeFailLimit;
        if (!timedOut && !suspicious) {
            return false;
        }
        if (lastRescueAtMs > 0 && now - lastRescueAtMs < cooldownMs) {
            return false;
        }
        return true;
    }

    /** 开始重新广播发现：记录时间、进入 active，冷却从这里算。 */
    public synchronized void beginRescue(long now) {
        active = true;
        lastRescueAtMs = now;
        rescueCount++;
        // 重新发现期间不再累积失败次数，避免一次发现窗口里刷爆
        decodeFailures = 0;
    }

    /** 结束重新广播发现：无论是否纠正成功，都从这一刻重新计时。 */
    public synchronized void endRescue(long now) {
        active = false;
        lastGoodAtMs = now;
        decodeFailures = 0;
    }
}
