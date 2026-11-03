# AGENTS.md — 体重记 App 开发指南（面向 AI / 开发者）

本文件是本仓库的**唯一开发约定来源**：构建、改代码、改桥、改协议、测试、发版都按这里做。
面向普通用户的安装 / 使用 / 排障说明见 [README.md](README.md)（只面向人类，不要在 README 里堆开发细节）。

> 命令默认在仓库根目录执行：`cd weightlog`。

---

## 0. 不变量（改代码前必读）

1. **没有网络**：不加 `INTERNET` 权限、不加网络请求 / CDN / 在线字体 / 远程资源。数据只在本机。
2. **密钥保密**：bind key / token 不打印、不导出、不进日志、不进错误信息 / 文件名 / `state()` / `list()`；对外只回 `bindKeySet` / `tokenSet`。
3. **版本单一来源**：版本号只由 `build.sh --version/--code` 注入（生成 `BuildConfig`，同时改 manifest）。Java / JS 里不要硬编码版本。
4. **`assets/www/` 是源文件**：直接改，不是生成目录；不要引入打包 / 预编译步骤。
5. **旧 WebView 兼容**：目标机型可能是 Android 6–8 + 任意版本 WebView。JS 只用 ES5（`var`/`function`，禁 `let`/`const`/箭头/`?.`/`??`/`replaceAll`/顶层 `await`）；CSS 禁 `inset`、flex `gap`、CSS 嵌套；`env()` 必须**先写纯数值再 `calc()` 覆盖**；图标只用 inline SVG / CSS，不用 Emoji。
6. **契约改动要同步**：JS 桥（§5）、事件 / 状态 JSON（§5）、导出字段（§9）、时间规则（§10）都是跨文件契约；改动同步改 `test/`、本文件与 README。
7. **公式版本**：体脂公式一改就必须 bump `BodyComposition.CALC_VERSION`（写入每条记录 `calc_version`）。
8. **数据库 schema**：改表 / 加字段前先在 `WeightDb.migrate()` 写迁移，再 bump `DB_VERSION`；不允许 `onUpgrade` 空实现。
9. **权限矩阵固定**（§6）：不加新权限、不加 Activity / Service / Receiver / Provider。导出权限内嵌在 `exportData`。
10. **`test/` 只能放纯 Java**：不得 import `android.*`；测试专用 shim 不得被 `src/` 引用、不得进 APK。
11. **预绑定 / 地址兜底**：预绑定 MAC + bind key 只由 `build.sh` 从仓库外的 env 文件（默认 `../scale/.env`）注入 `BuildConfig.PRESET_SCALE_*`（源码里不出现 key）；`--no-preset` 出干净包。自动纠正地址**必须先用 bind key 解密成功**才允许写回（否则会误绑邻居的 S400）；纠正事件走 `device` 事件、地址属公开信息，key 永不下发 JS。

---

## 1. 固定事实

| 项 | 值 |
|---|---|
| 工作目录 | 仓库根目录 |
| App 源码 | 仓库根目录下的 `src/` / `assets/` |
| 包名 / 应用名 | `com.weightlog.app` / 体重记 |
| 版本 | `1.2`（versionCode 3）；`build.sh` 生成 `BuildConfig.VERSION_NAME/VERSION_CODE` |
| minSdk / targetSdk / compileSdk | 23 / 31 / 35 |
| Android SDK | `~/Library/Android/sdk`（build-tools **37.0.0**，platform android-35→31 自动查找） |
| JDK | 17（javac 以 `-source 8 -target 8` 编译） |
| 签名 | 本地 debug 证书（默认 `debug.keystore`，alias `weightlog`；**不进仓库**，缺失时 `build.sh` 自动生成；用 `WEIGHTLOG_KEYSTORE` / `WEIGHTLOG_KEYSTORE_PASS` / `WEIGHTLOG_KEYSTORE_ALIAS` 指向已有证书） |
| 产物 | `apks/weightlog-<version>.apk`（可用 `WEIGHTLOG_APK_OUT` 覆盖；`apks/` 已 gitignore） |
| 预绑定 | `build.sh` 默认读 `../scale/.env`（可用 `WEIGHTLOG_PRESET_ENV` 指定）的 `S400_MAC` / `S400_BLE_KEY`（可选 `S400_NAME`）→ `BuildConfig.PRESET_SCALE_MAC/BIND_KEY/NAME`；首次启动自动写成 `device_v1`，不用扫描添加；`--no-preset` 关闭 |
| 地址兜底 | 绑定扫描超时（空闲 120 s / 称重层 45 s）或连续 3 次解密失败 → 切 `RESCUE` 重新广播发现 → bind key 解密成功才自动纠正 MAC（20 s 窗口、120 s 冷却） |
| 协议 | BLE MiBeacon v5（`0xFE95`），product id `0x3BD5` / `0x30D9`；object `0x6E16` / `0x4E16`，心跳 object=0 |
| 解密 | 16 字节 bind key，AES-CCM（tag 4，AAD `0x11`，nonce = MAC6 + `data[2:5]` + `data[-7:-4]`） |
| 测试用秤 | 文档/代码里的 MAC `02:00:00:00:00:01`、广播名 `Xiaomi Scale S400 0001` 都是**示例值**，不是真机 |
| 本机模拟器 | AVD `mgmt30`（API 30，arm64；无 BLE 广播源） |
| 协议细节 | `src/com/weightlog/app/scale/MiBeaconDecoder.java`；电视端是同一协议的另一个实现（不在本仓库） |

---

## 2. 构建与发布（生产包）

```sh
# 生产包（默认 1.2 / code 3；默认注入 scale/.env 的预绑定 S400）→ apks/weightlog-1.2.apk
bash build.sh

# 发新版本（版本号唯一入口）
bash build.sh --version 1.2 --code 3

# 不预绑定（要发给别人 / 没有 scale/.env 时）
bash build.sh --version 1.2 --code 3 --no-preset

# 构建 + adb install + 启动
bash build.sh --install
```

预绑定读取顺序：`WEIGHTLOG_PRESET_ENV`（可选，覆盖 `.env` 路径）→ `$MIJIA/scale/.env` 的
`S400_MAC` / `S400_BLE_KEY` / `S400_NAME`。MAC 或 key 不合法时只 WARN、构建出「无预绑定」包；
key 只在生成物 `build/gen/.../BuildConfig.java` 与 APK 里，构建日志只打印 MAC。

流水线：`aapt2 compile/link`（minSdk 23 / targetSdk 31 / assets）→ 生成 `build/gen/com/weightlog/app/BuildConfig.java` → `javac` → `d8` → 打 zip → `zipalign -p 4` → `apksigner`（v1/v2/v3；**v4 关闭**，不生成 `.idsig` 残渣）。
不依赖 Gradle；`build/` 是生成目录，可随时删除，不要提交。

构建后必须检查：

```sh
ls -lh apks/weightlog-1.2.apk
shasum -a 256 apks/weightlog-1.2.apk
BT="$HOME/Library/Android/sdk/build-tools/37.0.0"
"$BT/aapt2" dump badging apks/weightlog-1.2.apk | grep -E "^package:|application-label:|sdkVersion|targetSdkVersion"
"$BT/aapt2" dump permissions apks/weightlog-1.2.apk   # 必须没有 INTERNET
"$BT/apksigner" verify --verbose apks/weightlog-1.2.apk
# 预绑定检查：BuildConfig 里应有 MAC / bind key（key 不要贴到聊天/文档里）
sed -n '/PRESET_SCALE/p' build/gen/com/weightlog/app/BuildConfig.java
```

发版后：更新本文件 §16 版本记录（版本 / 大小 / SHA-256 / 验证说明）；README 的「APK 大小」也要同步（本仓库不分发 APK，`apks/` 已 gitignore）。
版本号不变时 `adb install -r` 直接覆盖安装即可。

---

## 3. 目录与文件职责

```text
mijia/weightlog/
├── AndroidManifest.xml        # 包名、权限矩阵、单 Activity（竖屏 / adjustResize / allowBackup=false）
├── build.sh                   # 无 Gradle 构建 + BuildConfig 生成 + 签名
├── debug.keystore             # 签名证书（本地生成，.gitignore 忽略，不入库）
├── README.md                  # GitHub 落地页 + 用户手册（安装/使用/排障）——不要塞开发细节
├── CHANGELOG.md               # 逐版本变更（版本记录与 §16 保持同步）
├── LICENSE                    # MIT
├── AGENTS.md                  # 本文件（AI/开发者唯一约定来源）
├── res/                       # 图标 + app_name（build.sh 会再注入一次 label）
├── assets/www/                # 手机 UI：index.html / style.css / app.js / bridge-demo.js
├── src/com/weightlog/app/
│   ├── MainActivity.java      # 生命周期、WebView、权限、扫描、事件推送、落库、导出
│   ├── bridge/Bridge.java     # @JavascriptInterface + 原生→JS 事件推送
│   ├── scale/MiBeaconDecoder.java  # FE95 帧解析 + 手写 AES-CCM（纯 Java）
│   ├── scale/BleScaleScanner.java  # BLE 扫描 / 发现 / 兜底重扫 / 功耗档 / 失败重试
│   ├── scale/RescuePolicy.java     # 超时 / 解密失败 → 何时重新广播发现（纯 Java）
│   ├── scale/WeighSession.java     # 一次称重的状态机（纯 Java）
│   ├── scale/BodyComposition.java  # 体脂 / BMI / BMR（纯 Java）
│   ├── data/Config.java            # SharedPreferences：device_v1 / profile_v1 / onboarded_v1 / preset_off_v1
│   ├── data/Measurement.java       # 记录模型 + JSON（toJson / toBackupJson）
│   ├── data/WeightDb.java          # SQLite：measurement 表、查询、事务、迁移、错误标记
│   └── export/Exporter.java        # CSV / JSON 导出（MediaStore / 旧系统两条路径）
├── docs/images/               # README 截图（人类）
├── docs/S400-BINDING.md       # 绑定：MAC + bind key、构建期预绑定、key 轮换、地址兜底
├── docs/S400-PROTOCOL.md      # 协议：广播帧 + AES-CCM + GATT 登录（配对）握手
├── docs/S400-PRIVACY.md       # 隐私：旁观者能力、本 App 已知暴露面、泄露处置
├── docs/PLAN-1.2.md           # 待修改任务书（1.2/1.3/2.0 批次 + 逐条验收，交给执行方用）
└── test/                      # 纯 JVM 回归（run.sh + ReviewTest + 测试专用 shim/stub）
```

构建生成物（不提交、不手动改）：`build/`；`apks/weightlog-*.apk` 是发布产物。

---

## 4. 架构与数据流

```text
BLE 广播 0xFE95
  → BleScaleScanner（全量扫描 + productId/MAC 软件过滤；BIND / DISCOVER / RESCUE 三种模式）
  → MiBeaconDecoder.parse/decrypt（bind key）
  → WeighSession（体重包 + 收尾包合并、5 秒去重、静默/心跳收尾）
  → MainActivity.applyProfile（按当前档案算体脂/BMI/BMR + 档案快照）
  → WeightDb.insert（io 线程）
  → __wlEvent__("final") → app.js（结果页 / 列表 / 趋势 / 首页）
```

- **地址兜底链**：`RescuePolicy`（纯 Java 判定）→ `MainActivity.enterRescue()` 切 `RESCUE` 模式 →
  `onRescueCandidate` 用 bind key 试解密 → 成功才 `config.saveDevice(新 MAC, 保留 key)` →
  `device` 事件 + 回 `BIND`。窗口 20 s（`RESCUE_WINDOW_MS`）、检查周期 5 s、冷却 120 s。
- `live` 帧路径：`WeighSession.onLive` → `MainActivity.liveJson()` 补原生体成分 → `__wlEvent__("live")`。
- **线程模型**：UI 线程（Activity / 扫描回调注册）、WebView JavaBridge 线程（`Bridge` 同步方法）、BLE 扫描回调线程、单线程 `io` ExecutorService（落库 / 导出 / `pushState` 的状态查询）。`Bridge.push` 内部切 UI 线程。
  - UI 操作 / 权限 / `startActivity` 必须 `runOnUiThread`。
  - DB 只在 JavaBridge / io / 导出线程同步读写；`pushState` 在 io 上构造状态以保持与落库的顺序。
  - 跨线程共享字段要么 `volatile`、要么在锁内（扫描器生命周期 / candidates 已处理）。
- `WeighSession` 的 5 秒去重窗口 `MIN_FINAL_INTERVAL_MS = 5000`；静默收尾 3 s（`QUIET_FINALIZE_MS`，每 400 ms 检查）；扫描错误去抖 4 s。
- `onPause` / `onDestroy`：先 `WeighSession.forceFinalize()` 把「已经拿到体重、还没等到最终包」的那次称重补存一条，再 `reset()`；
  称重层打开时 `setLiveMode(true)` 会加 `FLAG_KEEP_SCREEN_ON`（关闭时清），避免等秤时被系统熄屏打断。
- `onDestroy`：先停扫描/会话/定时器，再摘 `db`/`io` 字段，把 DB close 任务排进 io 队列尾部后 `shutdown()` 排空（**不是** `shutdownNow()`），保证最后一次落库不丢。

---

## 5. JS 桥契约（`window.WeightLog`）

原生注入（`Bridge.java`），查询类**同步**返回 JSON 字符串，写操作返回 `{ok,...}`。页面在 `assets/www/app.js`；浏览器预览时由 `bridge-demo.js` 提供同接口内存实现（真机上检测到 `window.WeightLog` 后直接退出，不接管）。

### 5.1 方法表（冻结）

| 方法 | 参数 | 返回 / 说明 |
|---|---|---|
| `state()` | — | 全量状态（5.3） |
| `list(days)` | `0`=全部 | 记录数组，按时间倒序，`toJson(false)` 字段（5.4） |
| `addManual(json)` | `{weight_kg, measured_at_ms?, note?}` | `{ok, measurement, state}`；时间未来 >60 s 会被夹到现在 |
| `updateNote(id, note)` | — | `{ok}` |
| `remove(id)` | — | `{ok, state}` |
| `clearAll()` | — | `{ok, state}`；只删记录 |
| `saveProfile(json)` | `{name,heightCm,age,sex,targetWeightKg}` | `{ok, profile, state}`，并重算历史 |
| `saveDevice(json)` | `{mac, bindKey?, name?}` | `{ok, device, keyValid, state}`；`bindKey` 空 = 不改 |
| `applyPresetDevice()` | — | `{ok, device, keyValid, state}`；恢复构建时注入的预绑定秤（没有预绑定时 `{ok:false,error}`） |
| `clearDevice()` | — | `{ok, state}`；保留记录；会置 `preset_off_v1` 抑制自动预绑定 |
| `setOnboarded(bool)` | — | 无返回 |
| `startScan(mode)` | `"bind"` / `"discover"` | 无返回；缺权限会自动申请 |
| `stopScan()` | — | 无返回 |
| `candidates()` | — | 发现模式候选秤数组 |
| `exportData(format)` | `"csv"` / `"json"` | `{ok,path,uri,count,format}` 或 `{ok:false,pending:true}`（等待存储权限） |
| `seedDemo()` | — | `{ok,count,state}`；只写 `source='demo'` 的记录，**不写假档案**（档案为空时体脂/BMI/BMR 留空） |
| `clearDemo()` | — | `{ok,count,state}`；只删 `source='demo'`，真实记录不动（「我的 → 数据」两次确认） |
| `openSettings(target)` | `"bluetooth"` / `"location"` / `""` | 跳系统设置；设备页「去设置」按钮调用 |
| `setLiveMode(live)` | `true` / `false` | 称重层打开/关闭；原生据此用 LOW_LATENCY / BALANCED 扫描 |

> 已删除且不要再加回：`requestPermissions`、`shareLastExport`、`closeApp`、`version`、`log`。版本读 `state().version`；导出权限由 `exportData` 内部处理；没有分享入口。

### 5.2 事件（原生 → JS）

`webView.evaluateJavascript("window.__wlEvent__(type, payload)")`。`__wlEvent__` 必须同时容忍 payload 是 JSON 字符串或对象。

| type | payload |
|---|---|
| `state` | 同 `state()`；扫描状态变化、页面加载完成、配置保存后 |
| `live` | `{weight_kg, impedance_low_ohm, impedance_high_ohm, heart_rate, stabilized, ts_ms, bmi, body_fat_pct, bmr_kcal, profile_ready}`；约 0.3–0.5 s/帧；体成分由原生算好，算不出时为 `null`（键始终存在） |
| `final` | `{measurement, state}` |
| `candidates` | `[{address,name,product_id,rssi,last_seen_ms}]`（10 秒内出现过的） |
| `device` | `{code:"address_corrected", oldMac, mac, name}`；兜底重扫时 bind key 解密成功、自动纠正了保存的地址后推送（页面 toast + 重新拉 state） |
| `error` | `{code,message}`；code ∈ `no_permission` / `bt_off` / `location_off` / `no_config` / `no_key` / `decode_failed` / `db_error` / `scan_failed` |
| `export` | `{ok,path,uri,count,format}`；**只在权限回调后的异步导出路径推送**，同步导出只返回结果（避免双 toast） |

### 5.3 `state()` JSON

```json
{
  "version": "1.2",
  "onboarded": true,
  "device": {"mac": "02:00:00:...", "name": "Xiaomi Scale S400 0001", "bindKeySet": true, "tokenSet": false,
             "preset": true, "presetMac": "02:00:00:00:00:01", "presetName": "Xiaomi Scale S400 0001"},
  "profile": {"name": "演示用户", "heightCm": 172, "age": 28, "sex": "male", "targetWeightKg": 65,
              "targetSetAtKg": 66.8, "targetSetAtMs": 1762000000000, "ready": true},
  "scan": {"supported": true, "btOn": true, "permission": true, "locationOn": true,
           "scanning": true, "mode": "bind", "rescue": false, "wantScan": true,
           "error": {"code": "...", "message": "..."}},
  "records": {"count": 24, "last": {...}, "previous": {...}},
  "dbError": false
}
```

`scan.error` 只保留最近 30 秒；`scan.rescue=true` 表示正在超时兜底重扫（页面显示「重新找秤」）；
`device.preset` 表示这个 APK 内置了预绑定秤（`presetMac` / `presetName` 只有 `preset=true` 时有值，不含 key）；
`dbError=true` 表示 DB 打开/读写失败（不要显示成「没有数据」）。

### 5.4 measurement 字段

`toJson(false)`（`state()` / `list()` / `final` 用）：

```text
id, measured_at_ms, measured_at (ISO8601), tz_offset_min, profile_id,
weight_kg, impedance_low_ohm, impedance_high_ohm, heart_rate,
stabilized, body_fat_pct, bmi, bmr_kcal, calc_version, source (ble|manual|demo), note
```

`toBackupJson()`（**仅 JSON 导出**用）在上面的基础上追加：

```text
profile_height_cm, profile_age, profile_sex, created_at_ms, raw_json
```

`raw_json` 是原始广播 hex；没有原始广播时为 `null`（键始终存在）。

---

## 6. 权限与蓝牙扫描

| 设备版本 | manifest 权限 | 运行时要求 |
|---|---|---|
| API 31+ | `BLUETOOTH_SCAN`（`neverForLocation`）+ `BLUETOOTH_CONNECT` | 附近的设备权限 |
| API 23–30 | `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`（`maxSdkVersion=30`） | 定位权限 + 系统定位开关 |
| API ≤28 | `WRITE_EXTERNAL_STORAGE`（`maxSdkVersion=28`） | 导出时申请 |

- `BLUETOOTH` / `BLUETOOTH_ADMIN` 只给 ≤30 用；API 31+ 不要出现定位权限。
- 扫描**不使用硬件 ScanFilter**：FE95 在 service data（AD type `0x16`），部分机型用 serviceUuid 过滤会零回调；一律全量扫描 + `handleScanResult` 软件过滤（product id + MAC）。
- 功耗：默认 `SCAN_MODE_BALANCED`；JS 打开称重层调 `setLiveMode(true)` 才切 `SCAN_MODE_LOW_LATENCY`，关闭切回。切换时用新 settings 重启扫描，保留 `wantScan`/mode。
- 失败重试：`onScanFailed` 按 2 s / 5 s / 15 s 退避重试（最多 3 次）；仍失败清 `wantScan` 并报 `scan_failed`。`start()` 的所有失败分支也必须清 `wantScan`。
- 发现模式（`startScan("discover")`）只收集候选秤，15 秒后由 JS 调 `stopScan()`。
- **兜底模式（`Mode.RESCUE`）**：绑定扫描空闲 120 s（称重层打开时 45 s）没有自家秤广播、或连续 3 次解密失败时，
  `RescuePolicy.shouldRescue()` 返回 true → `MainActivity.enterRescue()` 切 `RESCUE`；此时附近**所有**小米体脂秤
  （product id 命中）的包都回调 `onRescueCandidate`，用 bind key 试解密：解开才算「换了地址的还是这台秤」，
  落盘新 MAC（保留 key）、推 `device` 事件、喂这一帧给会话，然后回 `BIND`。20 s 窗口内没解开就回 `BIND`；
  冷却 120 s；邻居的 S400 解不开 key，不会被误绑。**只接受带 object 的加密帧**（心跳帧没数据、未加密帧无法用
  bind key 验身份，都不参与认秤）。RESCUE 期间自家 MAC 的包仍走 `onScaleData`，不影响称重。
  注意语义：心跳只算「这个地址还有设备在广播」——会续 idle 计时、但不清解密失败计数。所以「MAC 填成一台
  醒着的邻居」时，如果对方只发心跳（没人上秤），既不超时也攒不满 3 次解密失败，不会自动纠正；
  等邻居被踩出体重帧（加密 object 帧）才会累计失败触发 RESCUE。

---

## 7. 数据库与配置

### 7.1 SQLite（`weightlog.db`，`DB_VERSION = 1`）

表 `measurement`：`id`(PK autoincrement) / `measured_at_ms` / `tz_offset_min` / `profile_id` / `weight_kg` / `impedance_low_ohm` / `impedance_high_ohm` / `heart_rate` / `stabilized` / `body_fat_pct` / `bmi` / `bmr_kcal` / `calc_version` / `profile_height_cm` / `profile_age` / `profile_sex` / `source` / `note` / `raw_json` / `created_at_ms`；索引 `idx_measurement_time(measured_at_ms DESC)`。

- `profile_*` 是**测量当时的档案快照**；体脂/BMI/BMR 是估算值，`calc_version` 标记公式版本。
- `onUpgrade` 是**真迁移**：`for (v = old; v < new; v++) migrate(db, v)`；加字段/索引时补 `migrate()` 的 case，再 bump `DB_VERSION`。
- `transaction(Runnable)`：`recalcHistory()`（改档案后重算）与 `seedDemoRows()`（含先删旧 demo）都在一个事务里。
- 所有方法同步阻塞，调用方保证不在主线程；catch 里调 `markError()`，`hasRecentError()`（60 秒窗口）会让 `state().dbError=true`。
- `WeightDb.isoTime(epochMs, tzOffsetMin)` 与 `tzOffsetMinAt(epochMs)` 是纯工具函数（测试 stub 依赖前者）。

### 7.2 配置（SharedPreferences `weightlog_config`）

```json
device_v1  = {"mac":"02:00:00:00:00:01","bindKey":"<32 hex>","token":"","name":"..."}
profile_v1 = {"name":"...","heightCm":172,"age":28,"sex":"male","targetWeightKg":65,
              "targetSetAtKg":66.8,"targetSetAtMs":1762000000000}
preset_off_v1 = true   # 用户主动解绑后抑制自动预绑定；applyPresetDevice()/恢复预绑定会清掉
```

- `bindKey` / `token` 只在私有目录；`saveDevice` 传空 key = 保持原值。
- 名称做 `sanitizeName()`：去控制字符 / 换行、截断 40 字符；原始长度 >200 直接拒绝。
- `Config` 可变字段全部 `volatile`（UI / bridge 线程写，BLE 线程读）。
- **预绑定落盘规则**（`Config.maybeApplyPreset()`）：`BuildConfig.PRESET_SCALE_*` 合法、且当前设备段缺失 / 缺 key、
  且没被 `preset_off_v1` 抑制时自动 `saveDevice(预设 MAC, 预设 key, "", 预设名)`。已有合法设备配置时不覆盖
  （尊重用户改过的秤）；配的正是预设那台但缺 key 时补 key；配的是别的秤就不动。自动预绑定只在 `reload()`
  里跑一次（`applyingPreset` 防递归），日志只打 `preset device applied mac=...`。

---

## 8. 体成分公式（唯一实现：`BodyComposition.java`）

- 体脂 = 「米家 / 社区公式 v1」单公式（LBM + 性别/年龄/体重/身高修正，clamp 5–75 %，`BigDecimal` HALF_EVEN 保留 1 位）；`BMI = w/h²`；`BMR` = Mifflin-St Jeor。
- **不是** `scale/metrics.py` + `scale/指标计算.md` 的「指标计算 v2」（2003 中国 50 kHz + Deurenberg，输出区间）。两套口径不可混用；App 有意保留 v1。
- `CALC_VERSION = 1`；公式一改必须 bump，否则历史记录无法区分算法族、无法选择重算。
- 实时预览不单独实现：`live` 事件由原生带 `bmi/body_fat_pct/bmr_kcal/profile_ready`，`app.js` 不做本地估算。`bridge-demo.js` 的 `bodyFat/bmi/bmr` 只是浏览器离线镜像，必须与原生同公式、同舍入。
- `applyProfile(m)` 先清空旧值/快照再按当前档案赋值：档案不完整时不会把上一份档案的估算写回。

---

## 9. 导出契约（`Exporter.java`）

- 位置：`下载/WeightLog/`。
- **CSV**（UTF-8 + BOM `\uFEFF`）表头固定：

  ```text
  measured_at,weight_kg,impedance_low_ohm,impedance_high_ohm,heart_rate,body_fat_pct_est,bmi,bmr_kcal_est,source,note
  ```

  单元格以 `=` `+` `-` `@` `Tab` `CR` 开头时前缀 `'`（防 Excel/Sheets 公式注入），再按 `,` `"` 换行做标准引用。
- **JSON**：`{app, exported_at, count, profile, measurements[]}`；每条用 `toBackupJson()`（含档案快照 / `created_at_ms` / `raw_json`）。
- 文件名 `weightlog-yyyyMMdd-HHmmss.{csv,json}`（精确到秒，同一分钟多次导出不覆盖）。
- API 29+：MediaStore Downloads + `RELATIVE_PATH=Download/WeightLog`，先 `IS_PENDING=1` 写入，成功后置 0；写失败删除刚插入的 uri。
- API ≤28：写公共 Download 目录，需要 `WRITE_EXTERNAL_STORAGE`；返回 `path`，`uri=""`；没有分享入口。
- 导出文件**不得**包含 bind key / token。

---

## 10. 时间 / 时区

- 每条记录存 `measured_at_ms` + `tz_offset_min`（测量当时的偏移）；`measured_at` 由二者生成 ISO8601。
- 手动补录过去时间用 `WeightDb.tzOffsetMinAt(at)`，不是当前偏移。
- UI 显示历史记录一律用记录自己的 `tz_offset_min`（`app.js` 的 `dayLabel/timeLabel/...` 都接受可选 `offMin`）；只有 `greeting()` / 新建表单用设备本地时间。
- 不要在「今天/昨天」判断里用设备午夜；要在该偏移下比较。

---

## 11. UI / 兼容性 / 无障碍

- 结构：顶栏 + 4 Tab（首页/记录/趋势/我的）+ 5 个 sheet（`sheetWeigh/Add/Profile/Device/Detail`）+ `#mask` + `#onboarding` + `#toast`。
- `window.wlHandleBack()` 返回 `true` 表示 JS 消费了返回（关引导/弹层），否则原生 `finish()`。
- 设计令牌在 `:root`（`--orange #ff6900` / `--bg #f4f6fa` / `--radius 20`）；数字 `font-variant-numeric: tabular-nums`；体重 `toFixed(1)`。
- 图表：纯 Canvas（`drawTrendChart` / `drawSpark`），点位挂 `canvas._points`，触摸/鼠标用 `handleChartPointer` 显示 `#chartTip`；x 轴标签带记录的 `tz_offset_min`。
- 兼容性规则见 §0.5。额外：`env(safe-area-inset-bottom)` 的 5 个使用点（`.app` / `.tabbar` / `.sheet` / `.onboard-inner` / `.toast`）都必须保留纯数值回退声明在 `calc()` 之前。
- 无障碍：字号随系统 `fontScale` 缩放（WebView `textZoom`；原生 `setSupportZoom(false)`，双指缩放实际不可用）；sheet/引导是 `role="dialog" aria-modal="true" tabindex="-1"`，打开时焦点移入、关闭恢复；toast 是 `role="status" aria-live="polite"`。
- 弹层：`.sheet` 默认 `visibility: hidden`（防阴影漏到底栏）；`[hidden]` 全局 `display:none !important`；`closeSheet` 的 260 ms 遮罩定时器存 `App.maskTimer`，`openSheet` 先清掉再显示遮罩。
- P1 行为：用户点「先不称了」→ `App.weighDismissed = true`；`live` 自动拉起必须同时满足 `!sheet && !weighOpen && !weighDismissed`；新的 `final` 只 toast「已记录」，不强制弹层；`openWeigh` / `weight-again` / 新 `final` 重置该标记。**不要**因为关闭而停止扫描。

---

## 12. 安全边界（已知限制，不要当承诺）

个人自用设计，审查后选择**记录**而不是修改：

- bind key 明文在私有 `SharedPreferences`；无 Keystore / `EncryptedSharedPreferences`。`allowBackup=false` 挡 `adb backup`，挡不住 root / 能读私有目录的工具。
- **预绑定包把 bind key 连同 APK 一起分发**（`BuildConfig.PRESET_SCALE_BIND_KEY` 可被反编译取出）：只适合自用 / 自己人安装；对外分发用 `--no-preset`，让对方自己填 key。
- 签名证书是本机生成的 debug 证书（口令固定为 `android`，写在 `build.sh` 里），证书文件本身**不进仓库**；换机后签名会变，已装 App 需先卸载。
- 无 `FLAG_SECURE`：在「显示」里露 key 时可能被截图 / 录屏。
- BLE 帧无防重放：附近攻击者可重放抓到的加密帧注入旧体重。
- **BIND 路径不校验未加密帧**：已配置 MAC 下，未加密的 MiBeacon object 帧仍会按体重处理（1.0 起的老行为）；
  只有 RESCUE 认秤强制「加密 + 解密成功」。这是个人自用威胁模型下的已知限制，别把它当新漏洞。
- 仍保证：key/token 不打印、不导出、不进日志；`state()` 只回 `bindKeySet` / `tokenSet`。

---

## 13. 测试与验证

### 13.1 必跑（改完代码至少跑这些）

```sh
cd weightlog

# JS 语法
node --check assets/www/app.js
node --check assets/www/bridge-demo.js

# 协议 / 加密 / 状态机 / 公式回归（纯 JVM，~1 s，不需要 Android SDK）
bash test/run.sh          # 期望 PASS n / FAIL 0

# 构建 + APK 静态检查（见 §2）
bash build.sh
```

`test/` 组成：`ReviewTest.java`（无 JUnit，135 条断言，含 `RescuePolicy` 超时/冷却/解密失败/心跳续命/称重层超时用例）+ `run.sh` + 测试专用 `org/json` 最小 shim + `WeightDb` stub（只提供纯 `isoTime`）。`ReviewTest` 反射调用 `MiBeaconDecoder.ccmDecrypt`；私有 API 改名会故意让测试失败，改测试而不是放宽断言。

### 13.2 浏览器 UI 冒烟（无设备也能跑）

```sh
cd assets/www
python3 -m http.server 8797 --bind 127.0.0.1 &
# Playwright/无头 Chrome 打开 http://127.0.0.1:8797/index.html?reset=1
```

最低检查项：无 `pageerror` / `console.error`；24 条演示数据；4 个 Tab 无横向溢出；点「上秤称重」收到 live 后点「先不称了」，2–3 秒内不被重新打开、最终记录仍落库；设备页注入 `scan.error` 时出现「去设置」并能转发 `openSettings`；CSV 下载带 BOM 且 `'=1+1` 被转义；JSON 含 `profile_* / created_at_ms / raw_json`；`?onboard=1` / `?empty=1` 正常；`device.preset=true` 时引导第 2 步文案变「已预绑定」、设备页出现「恢复预绑定」并能调用 `applyPresetDevice`。

### 13.3 设备 / 模拟器冒烟

```sh
# AVD mgmt30（API 30 arm64）；BLE 无广播源，只能验证 UI/DB/权限/导出
~/Library/Android/sdk/emulator/emulator -avd mgmt30 -no-window -no-audio \
  -no-boot-anim -gpu swiftshader_indirect -no-snapshot -no-snapshot-save &

adb install -r apks/weightlog-1.2.apk
adb logcat -c; adb shell am start -n com.weightlog.app/.MainActivity
adb logcat -d -s WeightLog:*     # 期望 app start version=1.2 ... preset=<MAC> / preset device applied / 无 FATAL
```

- 模拟器首次启动若报 `snapshot operation ... pending`：删 `~/.android/avd/mgmt30.avd/multiinstance.lock` 再试（AVD 目录在沙箱外时需完整磁盘权限）。
- 可选的 WebView CDP 冒烟：`adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>`，拉 `http://127.0.0.1:9333/json/list` 取 `webSocketDebuggerUrl`，用任意 WebSocket 客户端（无 ws 库时手写握手）发 `Runtime.evaluate`。已验证点：`state().version` 来自 `BuildConfig`、演示数据 24 条、`addManual` 后 `state` 事件重绘、绑定后 `live` 自动开层、点「先不称了」后新 `live` 不再弹、`final` 仍入库、CSV/JSON 导出字段与 BOM、`setLiveMode` 不崩。
- 真秤只能真机验证：站上秤 → live + final 各一次，bind key 错时报 `decode_failed`。

### 13.4 验收清单（DoD）

- [ ] `node --check` 两个 JS 通过。
- [ ] `bash test/run.sh` PASS，`FAIL 0`。
- [ ] `build.sh` 成功；`aapt2 dump badging` 包名 / label / 版本 / minSdk 23 / targetSdk 31 正确；权限无 `INTERNET`；v1/v2/v3 签名通过。
- [ ] 浏览器冒烟无 `PAGEERROR`，四个 Tab 无横向溢出。
- [ ] 设备 / 模拟器：安装后能启动，logcat 无 `FATAL`；导出路径与字段正确。
- [ ] 更新 §16 版本记录；README 大小同步；清掉 `build/`、临时服务、模拟器。

---

## 14. 发布流程（生产包）

1. 改代码 → 跑 §13.1 与 §13.2；必要时设备冒烟（§13.3）。
2. 如需新版本：`bash build.sh --version x.y --code n`（同时更新 README/AGENTS 的版本相关描述）。
3. `apksigner verify` + `aapt2 dump permissions`（无 INTERNET）+ `shasum -a 256`。
4. 更新本文件 §16（大小 / SHA / 验证记录）；README「APK 大小」同步。
5. 清理：`rm -rf build`；确认 `apks/` 下没有 `.idsig` 之类构建残渣。

---

## 15. 踩坑（按症状查）

1. **隐藏 sheet 的阴影漏到底栏**：`.sheet` 用 transform 藏到视口外但仍绘制阴影；必须默认 `visibility: hidden`，`.open` 才显示并加阴影。
2. **`[hidden]` 失效**：组件自定义 `display:flex` 会盖掉 `hidden`；全局 `[hidden]{display:none!important}`。
3. **固定底栏盖住列表最后一行**：`.app` 要留 `padding-bottom: 76px + safe-area`；旧 WebView 不认 `env()`，所以 `padding-bottom: 76px` 必须先写。
4. **旧 WebView 不认 `inset` / flex `gap`**：用 `top/right/bottom/left` 和 margin 代替。
5. **Java 桥不在主线程**：`Bridge` 方法在 JavaBridge 线程；UI / 权限 / `startActivity` 必须 `runOnUiThread`。
6. **导出双 toast**：同步导出只返回结果，不推 `export`；只有「权限回调后的异步导出」推一次事件。
7. **旧系统导出没有分享 URI**：分享入口已删除；不要再引回 `file://` Uri（API 24+ 会 `FileUriExposedException`）。
8. **MediaStore 空文件**：先 `IS_PENDING=1` 插入，写完再发布；失败删 uri。
9. **权限两套规则**：API 31+ 用 `BLUETOOTH_SCAN/CONNECT`；≤30 用定位权限且要开定位开关；旧权限一律 `maxSdkVersion=30`（存储 ≤28）。
10. **硬件 ScanFilter 过滤 FE95 会零回调**：只能全量扫描 + 软件过滤。
11. **体脂有两套口径**：App 是 v1 单公式；`scale/metrics.py` 是 v2（区间）。改公式 bump `calc_version`，并同步 `bridge-demo.js` 镜像。
12. **演示数据时间**：`seedDemoRows()` 用 `Calendar` 归到「今天 08:12」再往前推；不要 `now - i*day + 7h`（会造未来时间）。
13. **`state` 与本地记录数组**：`state.records.count` 是真实库条数，`App.records` 是缓存；count 不一致才 `list(0)`；`final` 自己插入。
14. **竖屏输入法**：`adjustResize`，输入框 16px 防缩放；打开手动记录后延时聚焦体重框。
15. **扫描功耗 / 假扫描**：默认 BALANCED；称重层打开才 LOW_LATENCY；`start()` 失败必须清 `wantScan`，否则 UI 显示「正在扫描」但实际没扫。
16. **记录时间漂移**：用每条记录的 `tz_offset_min` 显示，不要用当前设备时区。
17. **`raw_json` 备份键**：JSON 备份里该键始终存在（无广播时为 null）；`state()/list()` 里没有 snapshot/raw 字段。
18. **签名残渣**：`apksigner` 的 v4 已关闭，不要打开（会生成 `.idsig`）；重新构建后检查 `apks/` 干净。
19. **`pushState` 在 io 线程**：不要在 UI 线程假设它同步完成；也不要直接调 `stateJson()` 做 UI 刷新（用 `pushState` / 事件）。
20. **onDestroy 关闭顺序**：close 任务要先入队再 `shutdown()`（直接 `shutdown()` 后 `execute` 会被拒），否则最后一次落库/DB close 丢失。
21. **预绑定不要写成「无条件覆盖」**：`Config.maybeApplyPreset()` 只在设备缺失 / 缺 key 且没被 `preset_off_v1` 抑制时落盘；
   无条件覆盖会把用户手动换的秤改回去，也会让「解除绑定」失效。`clearDevice()` 必须写 `preset_off_v1`。
22. **自动纠正地址必须解密校验**：附近常有别的 S400 在广播，只按 product id / 名字认秤会绑错。
   `onRescueCandidate` 里 `MiBeaconDecoder.decrypt(frame, bindKey)` 失败就丢弃；心跳包（`!frame.hasObject`）
   和未加密帧（无法用 bind key 验身份）都不参与认秤。
   另：`finishRescue()` / `enterRescue()` 会重启扫描，必须切 UI 线程（BLE 回调线程里调 `scanner.start()` 容易和 `stopScan` 打架）；
   暂停 / 手动改设备 / 解绑时要 `cancelRescue()`，别让兜底窗口在后台继续跑。
   桥线程里改设备（`saveDevice` / `applyPresetDevice` / `clearDeviceData`）只写配置，重启/停扫描统一走
   `restartBindScanOnUi()` / `stopScanOnUi()`，避免和 `onPause` 的 `scanner.stop()` 交错成「后台还在扫」。

---

## 16. 版本记录

- **1.0（2026-10-28）**：首个版本。S400 BLE 广播直读、实时称重、SQLite 记录、体脂/BMI/BMR 估算、7/30/90 天趋势、目标体重、手动补录、备注/删除、CSV/JSON 导出、附近设备发现、演示数据、首次引导；浏览器演示桥；Android 11 模拟器全流程验证。
- **1.0 加固（2026-10-29）**：版本单一来源 `BuildConfig`；`live` 体成分改原生计算；桥接口瘦身（删 5 个死方法，加 `setLiveMode`）并接通「去设置」；CSV BOM + 公式注入防护 + 文件名带秒；JSON 升级为完整备份；MediaStore `IS_PENDING`；DB 迁移循环 + 事务 + `dbError`；扫描默认 BALANCED + 退避重试；记录按 `tz_offset_min` 显示；无障碍；新增纯 JVM 回归 `test/`。
- **1.0 生产包（2026-10-30）**：整理目录（删除过程性审查报告与冗余测试 README，开发约定收敛到本文件）；`build.sh` 关闭 v4 签名；JSON 备份 `raw_json` 键固定（无值为 null）；重建生产包 `apks/weightlog-1.0.apk`（113 KB，v1/v2/v3），并完成模拟器安装冒烟（启动 / 演示数据 / 手动补录 / 绑定 / live 自动开层 / 关闭不弹回 / 导出 / 无 FATAL）。
- **1.1（2026-11-01）**：**固定预绑定 + 地址兜底**。① `build.sh` 从 env 文件（默认 `../scale/.env`）注入 `BuildConfig.PRESET_SCALE_MAC/BIND_KEY/NAME`，首启自动落成 `device_v1`（解绑后可用设备页「恢复预绑定」一键恢复；`--no-preset` 出干净包）；② 新增纯 Java `RescuePolicy`：绑定扫描超时（空闲 120 s / 称重层 45 s）或连续 3 次解密失败 → `RESCUE` 模式重新广播发现，用 bind key 试解密，成功才自动纠正保存的 MAC（20 s 窗口 / 120 s 冷却）并推 `device` 事件；③ 桥新增 `applyPresetDevice()`，`state().device` 加 `preset/presetMac/presetName`，`state().scan` 加 `rescue`；④ JVM 回归 107→128 条（含「心跳不清失败计数」交错顺序断言）。生产包 `apks/weightlog-1.1.apk`（117 KB）。
- **1.2（2026-11-02）**：**丢数据 / 给错结论 / 首用失败 / 演示污染** 8 项修复。① A1 熄屏/切后台不再丢进行中的称重：`WeighSession.forceFinalize()` + `onPause`/`onDestroy` 补存 + 称重层 `FLAG_KEEP_SCREEN_ON`；② A2 增重目标不再恒显示「已达成」：方向按「起点 → 目标」判定，进度起点落盘 `targetSetAtKg/targetSetAtMs`（`profile_v1` 与 `state().profile` 新键），删掉百分比文字；③ A3 前端消费 `state().dbError`：顶部错误条 + 记录页「读取失败」+ 坏库隐藏手动记录入口；④ A4 首次「扫描附近设备」授权回调用 `pendingScanMode` 继续 DISCOVER，不再需要点第二次；未绑定时报 `no_config`；⑤ A5 演示数据不再写假档案、桥新增 `clearDemo()`（只删 `source='demo'`），「我的」按条数显示清除入口（两次确认），记录页加「含演示数据」标记；⑥ A7 删掉 `--safe-bottom` 自定义属性，5 处 `env()` 全部内联（旧 WebView 回退为纯数值）；⑦ A8 `discoverMode` 自愈：回前台 + WebView 重建后清掉泄漏的发现模式并重新起 BIND；⑧ A6 版本 1.2 / code 3，README/AGENTS/截图同步。JVM 回归 128→135 条。生产包 `apks/weightlog-1.2.apk`（117 KB）。
