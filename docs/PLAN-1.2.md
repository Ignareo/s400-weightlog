# 体重记 · 待修改任务书（v1.1 → 1.2 / 1.3 / 2.0）

> **目录结构说明（发布后归一）**：本文写于 1.1 时代，当时的 `android/weightlog/` 就是本仓库根；
> 文中提到 `android/tvframe/`、`backup/`、`tools/`、`scale/` 的地方属于仓库外的其它工程，
> 在这个独立仓库里不存在，相关约束按「不碰仓库以外的东西」理解。

> **读者**：接手的 AI / 开发者。本文自包含：不需要看审查过程、不需要再猜设计意图。
> **编写依据**：2026-11-02 对 v1.1 的只读审查（通读全部源码 + 实际跑测试/构建 + 20 张 390×844@2x 截图 + 暗色/字号/命中区实测）。
> **行号基准**：v1.1（2026-11-01，`apks/weightlog-1.1.apk`，117 KB）。改代码后行号会漂移，**定位一律以「函数名 + 代码原文」为准**，行号只作起点。

---

## 0. 开工前必读（10 分钟）

### 0.1 按顺序读这 4 个文件

1. `AGENTS.md` — 唯一开发约定来源（不变量、桥契约、DB、导出、测试、踩坑）。**本文与它冲突时以它为准**，并回来告诉我冲突在哪。
2. `README.md` — 用户手册（安装/使用/排障）。
3. `AndroidManifest.xml` — 权限矩阵 + 单 Activity。
4. 本文第 2 节「硬约束」。

### 0.2 工作纪律

- 仓库**不是 git 仓库**（当时没有 `.git`）。"提交" = 保存文件 + 同步文档 + 构建 APK。
- 开工前先做快照，改坏能回退：
  ```sh
  cp -R . /tmp/weightlog-before-1.2 && ls /tmp/weightlog-before-1.2
  ```
- **一次只做一个任务（A1、A2…）**，做完立即跑该任务的验收命令，再进下一个。不要一口气改 10 个文件后一起调试。
- **不要动范围外的东西**：别的工程 / 别的 App 的目录一律不碰。只改本仓库的 `src/`、`assets/`、`test/`、`build.sh`、`AndroidManifest.xml`、README / AGENTS / docs，以及 `apks/weightlog-*.apk`。
- 改任何跨文件契约（桥方法、事件、`state()` 字段、导出字段、时间规则）必须**同步三处**：`test/`、`AGENTS.md`、`README.md`。不同步 = 没做完。
- 每个任务完成后输出：改了哪些文件、验收命令的实际输出、遗留问题。**别只写"已完成"。**

### 0.3 本文优先级

| 批次 | 内容 | 是否本轮必做 |
|---|---|---|
| **A（1.2）** | 8 个「丢数据 / 给错结论 / 首用失败 / 演示污染」修复 | **必须做**，估 0.5–1 天 |
| **B（1.3）** | 导入、暗色、HyperOS token、信息密度、文案、命中区、交互 | **建议全做**，估 2–3 天；B1 最高优先 |
| **C（2.0）** | 家人双人档、长期趋势、记录编辑/撤销、异常值、工程债 | **未经确认不要做**，只做被点名的那几个 |

---

## 1. 项目速览（别自己猜）

| 项 | 值 |
|---|---|
| 工作目录 | 仓库根目录 |
| App 目录 | 仓库根（包名 `com.weightlog.app`） |
| 版本 | 1.1 / versionCode 2；版本号唯一来源是 `build.sh --version/--code` 注入的 `BuildConfig` |
| 架构 | **WebView 混合**：UI = `assets/www/{index.html,style.css,app.js}`（HTML/CSS/JS，ES5）；原生 = `src/com/weightlog/app/`（Java 8，无 Gradle，`build.sh` + aapt2/javac/d8/apksigner） |
| 数据 | SQLite `weightlog.db`（1 张表 `measurement`，`DB_VERSION=1`）+ SharedPreferences `weightlog_config` |
| 输入设备 | 小米 S400 体重秤，只监听 BLE MiBeacon v5（`0xFE95`）广播，**不建 GATT**、**不联网** |
| 权限 | API 31+：`BLUETOOTH_SCAN`(neverForLocation)/`BLUETOOTH_CONNECT`；API 23–30：定位；API ≤28：写存储。**没有 INTERNET** |
| 构建 | `bash build.sh`（默认注入仓库外 env 文件里的预绑定 S400；env 不存在时自动退化为不预绑定） |
| 测试 | `bash test/run.sh` → 期望 `PASS 128 / FAIL 0`（纯 JVM，~1 s，不 import android.*） |
| 生产包 | `apks/weightlog-1.1.apk`（117 KB，v1/v2/v3 签名，7 权限无 INTERNET） |
| 浏览器预览 | `cd assets/www && python3 -m http.server 8797`，打开 `http://127.0.0.1:8797/index.html?reset=1`（`?onboard=1` / `?empty=1` 是调试入口） |

**UI 结构**（改之前先对上号）：顶栏 + 4 Tab（`#view-home / #view-records / #view-trend / #view-me`）+ 5 个 sheet（`#sheetWeigh / #sheetAdd / #sheetProfile / #sheetDevice / #sheetDetail`）+ `#mask` + `#onboarding` + `#toast`。弹层统一走 `openSheet(id)` / `closeSheet()`，返回键走 `window.wlHandleBack()`。

---

## 2. 硬约束（违反即不合格，逐条对照）

1. **没有网络**：不加 `INTERNET`、不加任何网络请求 / CDN / 在线字体 / 远程图片。数据只在本机。
2. **密钥保密**：bind key / token 不打印、不导出、不进日志、不进错误信息 / 文件名 / `state()` / `list()`；对外只回 `bindKeySet` / `tokenSet`。
3. **版本单一来源**：版本号只由 `build.sh --version/--code` 注入；Java / JS / HTML 里不许再出现可展示的硬编码版本（现有 `app.js` 的 `|| "1.0"` 是待修缺陷，见 B7）。
4. **`assets/www/` 是源文件**：直接改，不引入打包 / 预编译 / 依赖安装。
5. **旧 WebView 兼容**（目标机型可能是 Android 6–8 + 任意版本 WebView）：
   - JS 只用 **ES5**：禁 `let/const/class/箭头函数/模板串/?. / ?? /replaceAll/padStart/Object.assign/Array.from/includes/...`/顶层 await。
   - CSS 禁 `inset`、flex `gap`、CSS 嵌套；`env()` 必须**内联**在 `calc()` 里（前置一条纯数值声明），**不要**放进自定义属性（会把回退机制弄坏，见 A7）。
6. **契约同步**：桥方法表（AGENTS §5.1）、事件 JSON、`state()` 形状、导出字段（§9）、时间规则（§10）都是跨文件契约。改一处 → 同步 `test/`、`AGENTS.md`、`README.md`、`bridge-demo.js`。
7. **公式版本**：体脂公式一改必须 bump `BodyComposition.CALC_VERSION`（写进每条 `calc_version`）。
8. **DB 迁移**：先写 `WeightDb.migrate()` 的 case，再 bump `DB_VERSION`；`onUpgrade` 不许空实现；新增列一律带 `DEFAULT`（旧表 ALTER）。
9. **权限矩阵固定**：不加新权限、不加 Activity/Service/Receiver/Provider。文件选择走 Activity 已有的 `onActivityResult` 管道（`WRITE_EXTERNAL_STORAGE` 的导出就是这么做的），不算新组件。
10. **`test/` 只能放纯 Java**：不得 `import android.*`；测试 shim 不得被 `src/` 引用、不得进 APK。新增纯 Java 类若要被测试覆盖，记得加进 `test/run.sh` 的 `FILES`。
11. **预绑定 / 地址兜底**：预绑定 MAC+key 只由 `build.sh` 从仓库外的 env 文件注入；自动纠正地址**必须先用 bind key 解密成功**才允许写回；key 永不下发 JS。
12. **设计口径**（来自用户，优先级高于任何数值）：
    - 界面不是说明书，是「现在能做什么 / 刚才发生了什么」。
    - 首屏只回答当下，其余进弹窗；**同一事实只出现一次**；文字按动作词给，不按说明书给；默认态不写提示；留白也是信息；可点性靠 affordance（chevron / 主色），不靠文案；写操作要有可见进度；说明能删就删，删不掉才用 ⓘ/Toast（一个宿主最多一颗）。
    - 反模式：Material/M3 组件、ripple、阴影表达层级、渐变/毛玻璃、半像素字号、给所有控件加动效、自造 `warning/success/info` token。

> 设计语言目标：**Miuix / HyperOS（数值快照 v0.9.4 / `39c40f99`）**。本 App 没有 Compose/View 布局，所以数值以 **CSS 变量**落地（映射表见 B3），不要试图引入 Compose 或 Miuix 库。

---

## 3. 现状基线与自检命令

改之前先跑一遍，确认基线是绿的（应该全绿）：

```sh
cd weightlog

node --check assets/www/app.js
node --check assets/www/bridge-demo.js
bash test/run.sh            # PASS 128 / FAIL 0
bash build.sh               # 默认 1.1 / code 2，产物 apks/weightlog-1.1.apk
rm -rf build                # 构建目录不提交

BT="$HOME/Library/Android/sdk/build-tools/37.0.0"
"$BT/aapt2" dump badging apks/weightlog-1.1.apk | grep -E "^package:|sdkVersion|targetSdkVersion"
"$BT/aapt2" dump permissions apks/weightlog-1.1.apk   # 必须没有 INTERNET
"$BT/apksigner" verify --verbose apks/weightlog-1.1.apk
```

**注**：`build.sh` 默认会读仓库外的 env 文件注入预绑定 S400。如果 `.env` 不存在（或不想注入），用 `--no-preset`。构建日志里只应出现 MAC，**不应出现 key**。

---

## 4. 任务总览

| ID | 批次 | 标题 | 主要文件 | 依赖 | 预估 |
|---|---|---|---|---|---|
| A1 | 1.2 | 熄屏 / 切后台丢掉进行中的称重 | MainActivity, WeighSession | — | 0.5 h |
| A2 | 1.2 | 增重目标恒显示「已达成」+ 进度起点 | app.js, Config, MainActivity | — | 1 h |
| A3 | 1.2 | `dbError` 有产出无消费（假空态） | app.js, index.html, style.css | — | 1 h |
| A4 | 1.2 | 「扫描附近设备」授权回调走错模式 | MainActivity | — | 15 min |
| A5 | 1.2 | 演示数据污染 + 不能单独清 + 写假档案 | app.js, MainActivity, Bridge | — | 1 h |
| A6 | 1.2 | 版本 1.2 + 文档/截图/契约同步 | README, AGENTS, docs/images, apks | A1–A5 | 1.5 h |
| A7 | 1.2 | `--safe-bottom` 让 `env()` 回退失效 | style.css | — | 15 min |
| A8 | 1.2 | `discoverMode` 泄漏 → 回前台不再自动扫描 | MainActivity | A4 | 30 min |
| B1 | 1.3 | JSON 备份**导入** + `schema_version` + 上次导出提醒 | 新增 `Importer.java`, MainActivity, Bridge, app.js, Config | A5 | 1 天 |
| B2 | 1.3 | 暗色模式（CSS 变量 + 系统栏 + 关算法暗色） | style.css, index.html, MainActivity, app.js | B3 | 0.5 天 |
| B3 | 1.3 | HyperOS/Miuix token 与几何迁移 | style.css | B4 | 1 天 |
| B4 | 1.3 | 信息密度收缩（删块） | index.html, app.js, style.css | — | 0.5 天 |
| B5 | 1.3 | 文案清算（删说明书 / 动作词化） | index.html, app.js | B4 | 0.5 天 |
| B6 | 1.3 | 命中区 ≥44dp + 对比度 + 图表可读性 | style.css, app.js | B2/B3 | 0.5 天 |
| B7 | 1.3 | 交互与表单健壮性（10 处小修） | app.js, index.html, MainActivity | — | 0.5 天 |
| C1 | 2.0 | 家人 / 第二人归属（最小双人档） | DB 复用, Config, Bridge, app.js | B1 | 3–5 天 |
| C2 | 2.0 | 长期趋势（移动平均 / 月度聚合 / 年份） | app.js | — | 1 天 |
| C3 | 2.0 | 记录编辑 + 删除撤销 | Bridge, MainActivity, app.js | — | 1 天 |
| C4 | 2.0 | 异常值拦截（体重跳变 / 单位误输） | app.js（+可选 DB） | — | 0.5 天 |
| C5 | 2.0 | 工程债（pure.js / 前端测试 / 投影查询 / MainActivity 拆分 / WebView 纵深） | 多处 | — | 按项 |

**执行顺序建议**：A 全做 → 出 1.2 包并验证 → B1 → B4 → B5 → B3 → B2 → B6 → B7 → 出 1.3 包。C 组等确认。

---

## 5. 批次 A：1.2 必修

### A1 熄屏 / 切后台丢掉「正在称的那一次」

**为什么**：`onPause` 里直接 `session.reset()`，而 `WeighSession.resetInternal()` 会清空 `weightKg/lowOhm/...`。此时若「最终包」还没到（等秤、站上秤但数字还没稳定、稳定后 3 s 静默收尾期间），这次称重**不会落库**，用户拿起来只看到 App 回到首页、什么都没有。全仓没有 `FLAG_KEEP_SCREEN_ON`，所以屏幕超时（默认 30 s）就是触发器。

**位置**：`src/com/weightlog/app/MainActivity.java`
- `onPause()`（搜 `protected void onPause`）
- `setLiveMode()`（搜 `public void setLiveMode`）
- `onDestroy()`（搜 `protected void onDestroy`）
- `src/com/weightlog/app/scale/WeighSession.java`（只加一个方法，不改现有状态机）

**做法**：

1. `WeighSession` 新增（放在 `pollQuietFinalize` 旁边，不改它）：
   ```java
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
   ```
   注意：`buildFinalMeasurement` 保持原样（它已处理 `raw_json` / `stabilized` / 快照）。**不要**改成强制要求阻抗。

2. `MainActivity.setLiveMode(true)` 的 UI 线程分支加常亮；`false` 时清除：
   ```java
   if (live) {
       getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
   } else {
       getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
   }
   ```
   `android.view.WindowManager` 已 import 则直接用。

3. `onPause()` 里，`session.reset()` **之前**做 best-effort 落库：
   ```java
   if (session != null) {
       Measurement pending = session.forceFinalize(System.currentTimeMillis());
       session.reset();
       if (pending != null) {
           saveMeasurement(pending);   // 内部会 applyProfile + 入 io 队列
       }
   }
   ```
   `saveMeasurement` 里已经有 `RejectedExecutionException` 兜底，`onPause` 阶段 io 还在，安全。

4. `onDestroy()` 里同样在 `session` 还在时先 `forceFinalize` → `saveMeasurement`，**再**走原来的「摘 db/io → 入队 close → shutdown」顺序（顺序不能反，否则最后一次落库丢）。

**验收**：
- [ ] `bash test/run.sh` 仍 PASS（新方法没被测试覆盖也要不影响基线）。
- [ ] 新增 3 条 JVM 断言（加进 `test/ReviewTest.java`，`WeighSession` 已在 `FILES` 里）：
  1. 喂一条带体重的 stabilized 包 → `forceFinalize(now)` 返回非 null 且 `weightKg` 正确；
  2. 未收到任何包 → `forceFinalize` 返回 null；
  3. 刚 final 过 1 s → `forceFinalize` 返回 null（5 s 去重生效）。
- [ ] 浏览器无法验证（原生行为）；模拟器/真机：打开称重层 → 等系统熄屏（或按电源键）→ 回 App，logcat 应有 `saved id=... source=ble`（可用 `adb logcat -s WeightLog:*`），无 FATAL。
- [ ] 切到别的 App 再回来，同上。

**风险**：`onPause` 里调用 `saveMeasurement` 会 `Bridge.push` 一条 `final`，WebView 已 paused 时事件会在恢复后执行——无害，但不要在 `onDestroy` 阶段再 push（`webView` 可能已 destroy，`Bridge.push` 有 null 守卫）。

---

### A2 增重目标恒显示「已达成」+ 进度起点错

**为什么**：`renderGoal()` 用 `current <= t → progress=100`。用户目标比当前体重**高**（增肌、孕期、术后恢复）时会直接显示 100% + 「已达成目标」，这是当前唯一会**主动给出错误结论**的功能缺陷。另外进度分母是「史上第一条记录」，改一次目标百分比就会跳（80% → 20%），用户无法理解。

**位置**：`assets/www/app.js`
- `renderGoal(last)`（搜 `function renderGoal`）
- `renderInsight()` 的目标分支（搜 `if (!isFat && target())`）
- `Config.java`（加 2 个字段）+ `MainActivity.saveProfile`（落盘）+ `state().profile`（下发）

**做法**：

1. `renderGoal` 重写目标方向判定：
   ```js
   var t = target();
   var start = targetStartWeight();      // 设目标时的体重；没有就取最早记录
   var current = last.weight_kg;
   var dir = current > t ? "down" : "up";       // down=减重目标, up=增重目标
   var achieved = dir === "down" ? current <= t : current >= t;
   var total = Math.abs(start - t);
   var done = Math.abs(start - current);
   var progress = achieved ? 100 : (total > 0.05 ? Math.max(0, Math.min(100, done / total * 100)) : 0);
   var hint = achieved
     ? "已达成目标 <b>" + fmt1(t) + " kg</b>"
     : "距离目标还差 <b>" + fmt1(Math.abs(current - t)) + " kg</b>";
   ```
   进度条保留，**百分比文字删掉**（进度条已经表达，见 B5）。
2. 「设目标时的体重」落盘：`profile_v1` 加两个键（向后兼容，缺省 0）：
   - `targetWeightKg`（已有）
   - `targetSetAtKg`（设目标那一刻的当前体重，double）
   - `targetSetAtMs`（设定时间，long）
   JS 侧桥不变（`saveProfile` 继续传原 5 个字段）；由**原生**在 `saveProfile` 里判定「目标值变了」时写入 `targetSetAtKg = 当前最新体重（db.getLast()）、targetSetAtMs = now`。`state().profile` 增加 `targetSetAtKg` / `targetSetAtMs`（新增键，向后兼容）。
   没有历史记录时 `targetSetAtKg = 0`，JS 回退到最早记录体重。
3. `renderInsight` 的目标句同步改成按方向判断（`last.y <= t` 在增重目标下也是错的）：
   ```js
   var achieved = dir === "down" ? last.y <= t : last.y >= t;
   ```

**验收**：
- [ ] 浏览器 `?reset=1`：把 `profile.targetWeightKg` 改成比当前高（例如 70），刷新 → 首页目标卡显示「距离目标还差 3.9 kg」+ 进度条，**不出现**「已达成」；趋势洞察同步。
- [ ] 改成比当前低（例如 62.5）→ 显示「还差 3.6 kg」。
- [ ] 改成低于当前且已达成（例如 67）→ 显示「已达成目标」。
- [ ] 改目标后进度百分比不再回跳（改前后 `targetSetAtKg` 不同，进度从新起点算）。
- [ ] `bash test/run.sh` PASS；`state().profile` 新键在浏览器 devtools 可见（`WeightLog.state()`）。
- [ ] 契约同步：AGENTS §5.3 `state().profile` 增两个键、§7.2 `profile_v1` 增两个键；README 不涉及（用户可见文案不变）。

**风险**：`targetSetAtKg` 只在目标值变化时更新；如果用户先改身高再改目标，顺序无影响。

---

### A3 `dbError` 有产出无消费（DB 坏了显示成「还没有记录」）

**为什么**：原生会把 DB 失败写进 `state().dbError`（`hasRecentError()` 60 s 窗口），AGENTS §5.3 明确要求「不要显示成没有数据」，README §5 也承诺「数据库读写失败时 App 会明确提示」。但 `assets/www/app.js` **全文没有 `dbError`**，四个视图照常渲染空态 → 用户看到「还没有记录」，可能去生成演示数据（只写失败）或清空全部（更糟），或者卸载重装（数据只在私有目录，卸载即丢）。

**位置**：`assets/www/app.js`（`renderAll` / `renderHome` / `renderRecords` / `renderMe`）、`assets/www/index.html`（加错误条节点）、`assets/www/style.css`（样式）

**做法**：

1. `index.html` 在 `<main class="views">` 前加：
   ```html
   <div class="bar-danger" id="dbErrorBar" role="alert" hidden></div>
   ```
2. `style.css` 加（先用现有色板，B3 时再换 token）：
   ```css
   .bar-danger {
     margin: 8px 16px; padding: 12px 14px; border-radius: 12px;
     background: var(--red-soft); color: var(--red);
     font-size: 13px; line-height: 1.45;
   }
   ```
3. `app.js` 加一个渲染函数，并在 `renderAll()` 开头调用：
   ```js
   function renderDbError() {
     var el = $("dbErrorBar");
     if (!el) { return; }
     var bad = !!(App.state && App.state.dbError);
     el.hidden = !bad;
     if (bad) {
       el.textContent = "记录读写失败，可能有数据没保存。先导出备份，再重开 App。";
     }
   }
   ```
4. `renderHome`：
   - `dbError` 时 `#lastMeta` 文案改成「读取失败」而不是「还没有记录，绑定秤后站上去即可」；
   - `#recentList` 的「还没有记录，站上秤试试」在 dbError 时改成「读取失败」。
5. `renderRecords`：
   - `dbError` 时 `#recordCount` 显示「读取失败」；
   - `#recordsEmpty` 的 `<h3>还没有记录</h3>` / `<p>...` 换成「读取失败 / 数据没有丢，重开 App 试试」，并隐藏「手动记录」按钮（`#recordsEmpty .btn-ghost`），避免在坏库上继续写。
6. `renderMe`：`dbError` 时在「数据」分组上方复用同一条 `.bar-danger`（或直接让 `#dbErrorBar` 常驻页面顶部，已够）。

**验收**：
- [ ] 浏览器无法真造 DB 错误 → 用 mock 验证：在 devtools 执行
  ```js
  window.__wlEvent__("state", {dbError:true, records:{count:0}, profile:{}, device:{}, scan:{}})
  ```
  首页/记录页出现错误条，不出现「还没有记录」；`dbError:false` 后恢复。
- [ ] `node --check`、`test/run.sh` 通过。
- [ ] 契约同步：无需改 AGENTS（§5.3 已规定语义），但可在 §11 加一句「前端消费 dbError」。

---

### A4 首次「扫描附近设备」授权回调走错模式（干净包 / 换秤首用必失败一次）

**为什么**：`startScanInternal(mode)` 先做权限检查并 `return`，**之后**才置 `discoverMode = true`。第一次点「扫描附近设备」时没有权限 → 走 `requestScanPermission()` 返回；用户点「允许」后 `onRequestPermissionsResult` 看到 `discoverMode == false` → 走 BIND（有配置）或什么都不启动（无配置）。结果：候选列表永远空，15 s 后显示「没有扫到」，用户必须再点一次。

**位置**：`MainActivity.java`：`startScanInternal`、`onRequestPermissionsResult(REQ_SCAN)`、字段区（加 `pendingScanMode`）

**做法**：

1. 加字段：`private volatile String pendingScanMode;`
2. `startScanInternal` 开头先记录目标模式（在权限检查**之前**）：
   ```java
   pendingScanMode = "discover".equals(mode) ? "discover" : "bind";
   if (!hasScanPermission()) { requestScanPermission(); return; }
   ```
   成功启动后 `pendingScanMode = null;`（所有 return 分支都要清）。
3. 权限回调改成用 `pendingScanMode` 决定：
   ```java
   if (granted) {
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
   }
   ```
   注意「没配置」时现在会静默什么都不做，改成 `reportError("no_config", …)`（`error` 事件 → 设备页横幅 + 去设置按钮）。
4. 拒绝授权分支保持 `no_permission` 报错。

**验收**：
- [ ] 干净包（`bash build.sh --no-preset` 装到模拟器/真机）→ 装完删数据重进 → 设备页点「扫描附近设备」→ 系统弹权限 → 允许 → **不需要再点第二次**，候选列表开始扫（logcat 有 `start scan mode=DISCOVER`，15 s 后 JS 自动停）。
- [ ] 已绑定设备时同样路径不会把 BIND 扫停（切后台回来仍在扫）。
- [ ] 未绑定且拒绝权限 → 设备页出现「去设置」横幅，不是静默。
- [ ] 模拟器 BLE 无广播源，只看扫描启动日志即可。

**风险**：`pendingScanMode` 可能残留，务必在每个成功/失败分支清空。

---

### A5 演示数据污染真库、不能单独清、还会写假档案

**为什么**：`seedDemo` 直接写 `measurement` 表（`source=demo`），没有二次确认；README §1 明确写「演示记录可一键清空」，但**桥里没有这个方法**；`seedDemoRows` 在档案没填时会补一份假档案（"演示用户"之类），改档案时还会被一起重算。用户误点一次，首页「当前体重 / 较上次」、趋势、目标进度、导出全被 24 条假数据污染，唯一出口是「清空全部记录」——连真实记录一起删。

**位置**：`MainActivity.java`（`seedDemo` / `seedDemoRows`，搜 `public String seedDemo`）、`bridge/Bridge.java`（Host 接口 + 方法）、`app.js`（`seedDemo` / `clearAll` 附近）、`index.html`（「我的 → 数据」的 DEMO 行）

**做法**：

1. **桥新增 `clearDemo()`**（只删 `source='demo'`）：
   - `Bridge.Host` 加 `String clearDemo();`
   - `Bridge.clearDemo()` 带 `@JavascriptInterface`，try/catch 返回 `{"ok":false}`
   - `MainActivity.clearDemo()`：`db.deleteBySource("demo")`（已存在）→ 返回 `{"ok":true,"count":n,"state":stateJson()}`；失败走 `reportError("db_error", …)` 并返回 `{"ok":false}`
2. **`seedDemoRows` 不再写假档案**：删掉「档案没填就补默认 profile」的分支；档案不完整时演示记录的体脂/BMI/BMR 就留空（UI 已有 `--` 分支）。
3. **入口与确认**：
   - 把「生成演示数据」从「我的 → 数据」**删掉**（引导页已有「先看看演示数据」按钮，日常入口不需要；这也是 B4 的删减）。
   - 若产品上要保留，必须走 `App.clearArmed` 同款二次点击（按钮文字变「再点一次生成演示数据」）并在副行写「会写入记录，需要单独删除」。
   - 有 demo 记录时，「我的 → 数据」显示一行「清除演示记录（N 条）」+ 两次确认 → 调 `clearDemo()` → `reload()/renderAll()` + toast「已清除 N 条演示记录」。
4. **列表/首页标注**：`renderRecords` 的月分组头或记录页顶部，有 demo 时显示一个中性 tag「含演示数据」；不新开一整行说明（B4 的口径）。

**验收**：
- [ ] 浏览器：点「生成演示数据」→ 清除入口出现 → 两次确认后 24 条消失、真实记录仍在。
- [ ] 真机/模拟器：`seedDemo` 后再 `clearDemo`，`list(0)` 只剩原有记录；`state().records.count` 正确。
- [ ] 档案为空时 `seedDemo` 不写 `profile_v1`（`state().profile.name` 仍为空）。
- [ ] 契约同步：AGENTS §5.1 加 `clearDemo`、§16 版本记录；README §1「演示数据」改为「演示记录可在『我的』单独清除」。

---

### A6 版本 1.2 + 文档 / 截图 / 契约同步

**为什么**：1.2 改了桥契约（`clearDemo`、`state().profile` 新键）、用户行为（演示数据）、文案，文档不跟就会变成下一个人踩的坑。另外 `docs/images/` 7 张截图是 **10-28（1.0）** 的，引导页第 2 步文字已经变了（现在预绑定包显示「S400 已预绑定，不用再扫描添加…」），README 还在嵌旧图。

**做法**：

1. 构建 `--version 1.2 --code 3`，产物 `apks/weightlog-1.2.apk`；删旧包？**保留** 1.1 一个版本以便回滚（版本记录见 `AGENTS.md` §16）。
2. 按下面清单改文档（**逐条核对，不要只改版本号**）：

| 文档位置 | 现在写的 | 改成 |
|---|---|---|
| `README.md` §1 演示数据行 | 「演示记录可一键清空」 | 「演示记录可在『我的』单独清除（不影响真实记录）」 |
| `README.md` §3.1 | 「不填也能记体重」 | 与实现对齐：表单目前要求身高/年龄/性别齐全才能保存档案；**如果不改表单（推荐改，见 B7），就改这句** |
| `README.md` §3.3 / §7 | 「第一次没反应的话，等 8 秒看提示」 | 「称重层已打开时 8 秒后会提示；App 不在前台时不会扫描」 |
| `README.md` §5 | 「数据库读写失败会明确提示」 | 改成与 A3 一致（顶部错误条 + 「读取失败」） |
| `README.md` §5 安全边界 | 现有条目 | 补：**没有导入功能 → 1.3 做了就删这条并改写** |
| `README.md` §9 | 「APK 大小 约 117 KB」 | 新包实际大小（`ls -lh`） |
| `README.md` §11 无障碍 | 「viewport 允许双指缩放」 | 改成「字号随系统 fontScale 缩放（WebView textZoom）」——原生 `setSupportZoom(false)`，pinch zoom 实际不可用 |
| `AGENTS.md` §5.1 方法表 | 无 `clearDemo` | 加行：`clearDemo()` → `{ok,count,state}`；只删 demo |
| `AGENTS.md` §5.3 `state()` | 无 `targetSetAtKg/Ms` | 加两个键（A2） |
| `AGENTS.md` §5.3 | `dbError` 语义 | 加一句「前端必须消费：顶部错误条 + 空态换文案」 |
| `AGENTS.md` §7.2 `profile_v1` | 5 个键 | 加 `targetSetAtKg` / `targetSetAtMs` |
| `AGENTS.md` §16 | 版本记录到 1.1 | 加 1.2 条目（列出 A1–A8 的改动与新增测试数） |
| `AGENTS.md` §16 版本记录 | 1.1 行 | 加 1.2 行（版本/大小/SHA-256/验证说明） |
| `docs/images/*.png` | 10-28 旧图 | 重拍 7 张（390×844@2x，见 §10 截图回归），文件名不变 |

3. SHA-256 记进 `AGENTS.md` §16：`shasum -a 256 apks/weightlog-1.2.apk`。
4. 清理：`rm -rf build`；确认 `apks/` 下没有新增 `.idsig`。

**验收**：
- [ ] `grep -rn "1\.1" README.md AGENTS.md` 剩下的都是历史版本记录，不是当前版本描述。
- [ ] 新截图与当前实现一致（逐张对照：引导第 2 步文案、我的页 DEMO 行是否已删、空态文案）。
- [ ] `AGENTS.md` §16 记的 SHA 与实际文件一致。

---

### A7 `--safe-bottom` 让 `env()` 回退失效（旧 WebView 底栏压住最后一行）

**为什么**：`style.css` 把 `env()` 放进自定义属性：
```css
:root { --safe-bottom: env(safe-area-inset-bottom, 0px); }
.app  { padding-bottom: 76px; padding-bottom: calc(76px + var(--safe-bottom)); }
```
在**不认 `env()`、但认 `var()`** 的 WebView（Android 7 出厂 51/55、Android 8 出厂 58/61）上，`calc()` 在计算期变成 invalid at computed-value time → 属性回落到**初始值 0**，前面那条 `76px` 不会生效。症状：底栏盖住列表最后一行（AGENTS §15.3 想防的就是这个）、`.toast` 的 `bottom` 错位、sheet 底部内边距变 0。

**位置**：`assets/www/style.css`（`:root` 里的 `--safe-bottom` + 5 个使用点：`.app` / `.tabbar` / `.sheet` / `.onboard-inner` / `.toast`）

**做法**：删掉 `--safe-bottom`，5 处各自内联：
```css
.app { padding-bottom: 76px; padding-bottom: calc(76px + env(safe-area-inset-bottom, 0px)); }
.tabbar { padding: 7px 8px; padding: 7px 8px calc(7px + env(safe-area-inset-bottom, 0px)); }
.sheet { padding-bottom: 18px; padding-bottom: calc(18px + env(safe-area-inset-bottom, 0px)); }
.onboard-inner { padding-bottom: 28px; padding-bottom: calc(28px + env(safe-area-inset-bottom, 0px)); }
.toast { bottom: 84px; bottom: calc(84px + env(safe-area-inset-bottom, 0px)); }
```
（`.toast` / `.onboard-inner` 的数值以现有文件为准，只把变量换成内联 `env()`。）

**验收**：
- [ ] `grep -n "safe-bottom" style.css` → 只剩 5 处内联的 `env(`，没有 `--safe-bottom`。
- [ ] 浏览器 390×844 截图：底栏、最后一行的间距与改前一致（没有 inset 时纯数值生效）。
- [ ] 用 Chrome DevTools 的 `Element.style` 把 `env()` 替换成未知函数模拟老引擎：`.app` 的 `padding-bottom` 应为 76px（不是 0）。这是本任务的**核心证据**。

---

### A8 `discoverMode` 泄漏 → 回到前台不再自动扫描

**为什么**：`discoverMode` 只在 `stopScan()` / `saveDevice()` / `applyPresetDevice()` / `clearDeviceData()` 里清。若发现扫描被 WebView 页面重载 / 渲染进程崩溃重建 / 主页面加载失败重试打断（JS 的 15 s 定时器随页面消失，不会再调 `stopScan`），原生 `discoverMode` 会一直 true。`onResume` 的判断是 `if (!discoverMode && …) requestAutoScan();` → 之后**每次回前台都不再自动 BIND 扫描**，用户站上秤毫无反应，而设备页还可能显示 `mode=discover`。

**位置**：`MainActivity.java`：`onResume()`、`recreateWebView()`、`onRenderProcessGone` 分支

**做法**：

1. `onResume()` 开头做一次自愈：
   ```java
   if (discoverMode && (scanner == null || !scanner.isScanning())) {
       discoverMode = false;
   }
   ```
   （`BleScaleScanner` 若没有 `isScanning()`，加一个只读方法返回内部 `scanning` 字段。）
2. `recreateWebView()` 成功加载后（`onPageFinished` 已有 `pushState`）补一次 `stopScan` 等价清理：`discoverMode = false;` + `restartBindScanOnUi()`（若 `hasMac && hasKey`）。**在 UI 线程执行。**

**验收**：
- [ ] 浏览器无法验证；模拟器验证：点「扫描附近设备」后立刻用 `adb shell am force-stop` 不行（会退出），改用 CDP 触发页面重载 / 或在发现扫描期间旋转（竖屏锁定）——更稳的做法：发现扫描期间用 `adb shell input keyevent KEYCODE_BACK` 关掉 sheet（会走 `stopScan`，测不到泄漏）。**最省事的验证方式**：临时在 `recreateWebView()` 里加日志，强制触发一次重建，确认 `onResume` 后 logcat 出现 `start scan mode=BIND`。
- [ ] 代码走查：`discoverMode` 的每个 set/clear 点都在注释里列出来（便于下次改）。

---

## 6. 批次 B：1.3 体验

> 顺序建议：**B1 → B4 → B5 → B3 → B2 → B6 → B7**。B3/B2 都改 `style.css`，不要两个 AI 同时改这个文件。

### B1 JSON 备份导入 + `schema_version` + 「上次导出 N 天前」

**为什么**：`allowBackup=false` + 卸载删库 + 没有导入 = 换机 / 重装 / 误删的**唯一活路**是「用户自己记得导出并保管文件」。README 把 JSON 叫「完整备份」，但备份只有一半。这也是「手机 ↔ 电视互通」的前置（电视导出 JSON → 手机选文件导入，不需要加网络权限）。

**A. 导出格式升级（先做，向后兼容）**

`Exporter.buildJson` 根节点加：
```java
root.put("schema_version", 1);
root.put("app_version", BuildConfig.VERSION_NAME);   // 纯信息，导入不依赖
```
其余不变（`app/exported_at/count/profile/measurements[]`，每条 `Measurement.toBackupJson()`）。**旧文件没有 `schema_version`：导入器要能容忍（按 1 处理并提示「旧格式」）。**

**B. 新增纯 Java 导入器 `export/Importer.java`**（不 import android.* 的部分尽量多，便于 JVM 测试；文件读取用 `InputStream` 而不是 `File`）

签名建议：
```java
public final class Importer {
    public static final int SCHEMA_VERSION = 1;

    public static final class Result {
        public int imported, skipped, demoSkipped, invalid;
        public String error;          // null = 成功
    }

    /** 解析 + 校验，不落库；便于单测。 */
    public static List<Measurement> parse(String json, Result out) throws Exception;

    /** 落库：单事务，逐条 insert；返回实际插入数。由 MainActivity 在 io 线程调用。 */
    public static Result importInto(WeightDb db, List<Measurement> list);
}
```
校验规则（必须全部实现）：
1. 根必须是对象，`measurements` 必须是数组；否则 `error="不是体重记备份文件"`。
2. `schema_version` 缺失按 1；`> SCHEMA_VERSION` 直接拒绝（`error="备份来自更新版本"`）。
3. 每条必须有数值 `measured_at_ms`（>0）与 `weight_kg`（>0，建议夹在 5–400 之间）；缺 `tz_offset_min` 按 0。
4. `source` 白名单 `ble|manual|demo`，非法按 `manual`；`source == demo` **跳过**并计入 `demoSkipped`。
5. `calc_version` / `body_fat_pct` / `bmi` / `bmr_kcal` / `profile_*` 快照**原样保留**，**不要**按当前档案重算（否则历史估算被改写）。
6. `created_at_ms` 缺失按 `measured_at_ms`；`raw_json` 缺失/非字符串按 null。
7. `note` 截断到 500 字符（与 B7 的上限一致）。
8. 去重：同一次导出重复导入不应翻倍。判重键 = `measured_at_ms + weight_kg + source`（**不要用 id**，id 是自增）。落库前查已有记录（`db.list(0)` 或加一个 `exists(ms, weightKg, source)` 方法）→ 命中计入 `skipped`。

**C. 桥 + Activity 管道**

1. `Bridge.Host` 加 `String beginImport();` 和 `String importData(String uriString);`（或一个 `beginImport()` + 回调事件）。
   - 推荐形态：JS 调 `beginImport()` → 原生 `startActivityForResult(ACTION_OPEN_DOCUMENT, REQ_IMPORT)` → 返回 `{"ok":false,"pending":true}`（与导出权限路径同款）；用户选完文件后 `onActivityResult` 里读 `Uri` → io 线程 `Importer.parse` + `importInto` → `pushEvent("import", resultJson)`。
   - `REQ_IMPORT = 1003`。`ACTION_OPEN_DOCUMENT` + `type="application/json"`，`addCategory(CATEGORY_OPENABLE)`。**不需要新权限、不新增组件。**
2. `onActivityResult` 里只取 `data.getData()`；用 `getContentResolver().openInputStream(uri)` 读到字符串（限制 32 MB，防炸内存）；失败返回 `{"ok":false,"error":"读取失败"}`。
3. 导入后 `pushEvent("state", stateJson())` 让页面刷新；事件 `import` 的 payload：`{ok, imported, skipped, demoSkipped, invalid, error}`。
4. **原子性**：整个 `importInto` 放一个 `db.transaction(...)`；中途失败回滚并返回 `ok:false`（注意：`transaction` 目前是 `void` 且吞异常，见 C5，本任务先在 `importInto` 里自己做 `try/catch` + 计数，别依赖它）。

**D. UI**

1. 「我的 → 数据」加一行 `data-action="import-json"`：标题「从 JSON 恢复」，**副行写「选择导出的备份文件」**（这是动作词，不是说明书）。
2. 点击后按钮进入忙碌态（「导入中…」+ disabled，见 B7），收到 `import` 事件后：toast「已导入 N 条，跳过 M 条」或错误文案；`reload()`。
3. 「上次导出」提醒：`Config` 加 `getLastExportAtMs()/setLastExportAtMs(long)`（SharedPreferences 新键 `last_export_v1`），`doExport` 成功后写入；`state()` 加 `lastExportAtMs`（0 = 从未）。
   - 「我的」页「导出 JSON」副行改成动态：`从未导出` / `上次导出 3 天前` / `上次导出 2 小时前`（用 `dayLabel/timeLabel` 已有的偏移规则，不要新实现一套）。**只显示一句，不要解释为什么重要。**
4. 「导入」入口的可见性：数据为 0 时也要显示（换机首用就是 0 条）。

**验收**：
- [ ] 新增 JVM 用例（`test/run.sh` 的 `FILES` 加 `Importer.java`，注意它不能 import android.*）：
  1. 合法文件 3 条 → `parse` 出 3 条，字段齐全；
  2. 缺 `schema_version` → 按 1 解析成功；`schema_version=2` → 报错；
  3. 非法 `source` → 归 manual；`source=demo` → `demoSkipped=1`；
  4. `weight_kg=0` 或 `measured_at_ms=0` → `invalid=1` 跳过；
  5. 同一份文件导入两次（用内存 Map 模拟 db）→ 第二次 `skipped=3`；
  6. `profile_*` 快照原样保留，`calc_version` 不被改写。
- [ ] 真机/模拟器：导出 1.2 的 JSON → 清空全部 → 「从 JSON 恢复」→ 条数、时间、备注、体脂、`tz_offset_min` 一致；`raw_json` 保留（导出再比一次）。
- [ ] 旧 1.1 导出的文件（无 `schema_version`）能导入。
- [ ] 空文件 / 非 JSON / 别的 App 的 JSON → 明确报错，不崩。
- [ ] 契约同步：AGENTS §5.1（`beginImport`/事件 `import`）、§5.3（`lastExportAtMs`）、§9（`schema_version`）、§16；README §5/§10 改写（有导入了）；`bridge-demo.js` 补 `beginImport` 的内存实现（返回 `{ok:false,error:"浏览器预览不支持导入"}` 或直接用 `FileReader`，二选一，写明取舍）。

**风险**：10k 条 × `raw_json` 的 JSON 可能几十 MB → 解析放 io 线程，`openInputStream` 前先看文件大小（`OpenableColumns.SIZE`），>32 MB 直接拒绝并提示。不要在主线程解析。

---

### B2 暗色模式

**为什么**：实测 `?reset=1` 在 light/dark 两种 `color_scheme` 下截图 **sha256 完全相同** → 现在完全没有暗色。Manifest 主题固定 `Theme.Material.Light`，状态栏 / WebView 底色硬编码 `#F6F7FB`，图表颜色写死在 `app.js` 的 canvas 里。深夜使用是白底刺眼；而在 API 29+ 上，WebView 的算法暗色还可能把白卡片/橙色压成不可控的颜色（「没有暗色支持的暗色」）。

**前置**：先做 **B3**（token 变量化），否则暗色要改两遍。

**做法**：

1. `index.html` 头部，把单个 `theme-color` 换成：
   ```html
   <meta name="color-scheme" content="light dark">
   <meta name="theme-color" content="#F7F7F7">
   <meta name="theme-color" media="(prefers-color-scheme: dark)" content="#000000">
   ```
2. `style.css` 加暗色变量（**值取 Miuix dark**，与 B3 的变量名对齐）：
   ```css
   @media (prefers-color-scheme: dark) {
     :root {
       --bg: #000000;
       --card: #242424;
       --ink: #F2F2F2;
       --ink-2: rgba(255, 255, 255, 0.90);
       --sub: rgba(255, 255, 255, 0.60);
       --sub-2: rgba(255, 255, 255, 0.50);
       --line: #2A2A2A;
       --line-2: #393939;
       --accent-text: #FF8A3D;          /* App 自有：暗底上橙字要提亮 */
       --accent-soft: #2B1A0C;          /* App 自有：暖色容器 */
       --accent-line: #4A2A12;
       --green: #3DD68C; --green-soft: #12291F;
       --red: #FF6B5E;  --red-soft: #3A1512;
       --blue: #5D9BFF;
       --press: rgba(255, 255, 255, 0.10);
       --shadow: none; --shadow-sm: none;
     }
   }
   ```
   ⚠️ **不要**把 Android 的 ARGB 写法（`#E6FFFFFF`）搬进 CSS，CSS 里必须 `rgba()`。
3. `MainActivity`：新增 `isNight()` + 统一取色，替换 `setupWindow()` 里的硬编码：
   ```java
   private boolean isNight() {
       int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
       return mode == Configuration.UI_MODE_NIGHT_YES;
   }
   boolean night = isNight();
   int bg = night ? 0xFF000000 : 0xFFF7F7F7;
   window.setStatusBarColor(bg);
   window.setNavigationBarColor(night ? 0xFF000000 : 0xFFFFFFFF);
   int flags = 0;
   if (!night && Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
   if (!night && Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
   if (Build.VERSION.SDK_INT >= 23) window.getDecorView().setSystemUiVisibility(flags);
   ```
   `setupWebView` 的 `view.setBackgroundColor(#F6F7FB)` 同样换成 `bg`。
4. 关掉算法暗色（避免二次反色）：
   ```java
   if (Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT < 33) {
       s.setForceDark(WebSettings.FORCE_DARK_OFF);
   }
   if (Build.VERSION.SDK_INT >= 33) {
       s.setAlgorithmicDarkeningAllowed(false);
   }
   ```
5. 图表颜色变量化：在 `:root` 加 `--chart-line / --chart-fill / --chart-axis / --chart-grid / --chart-point`（亮暗两套），`app.js` 里把 `"#ff6900"` / `"rgba(255,105,0,...)"` / `"#aab0ba"` / `"#eef0f4"` 换成：
   ```js
   function cssVar(name, fallback) {
     try {
       var v = getComputedStyle(document.documentElement).getPropertyValue(name);
       return v && v.replace(/^\s+|\s+$/g, "") || fallback;
     } catch (e) { return fallback; }
   }
   ```
   每次 `drawTrendChart` / `drawSpark` 开头读一次（不要在循环里读）。
6. `Manifest` 的 `configChanges` **不要**加 `uiMode`，让 Activity 重建、WebView 重载最省事（代价：切暗色时丢掉当前弹层状态，可接受）。

**验收**：
- [ ] Playwright/Chrome `color_scheme=dark` 下 4 个 Tab + 2 个 sheet 截图：页面底 `#000000`、卡片 `#242424`、正文不刺眼、图表线可见；与 light 截图**不再相同**（sha256 不同）。
- [ ] 系统切暗色（真机/模拟器 `adb shell "cmd uimode night yes"`）→ 状态栏、导航栏、WebView 首帧底色都是深色，无白闪；切回 light 恢复。
- [ ] `adb shell "cmd uimode night no"` **必须还原**（改了设备状态要还原，这是既有约定）。
- [ ] 对比度：暗色下正文 ≥ 4.5:1（`#F2F2F2` on `#242424` ≈ 14:1）；`--sub` 60% 白在 `#242424` 上 ≈ 6.5:1、40–50% 白 ≈ 4.5:1 边界，**小字用 60%**。
- [ ] 旧 WebView（不认 `prefers-color-scheme`）降级为浅色，不破版。

---

### B3 HyperOS / Miuix token 与几何迁移

**为什么**：现在是「米家 1.0」——17 处阴影（非 none）、8 处渐变、18 种圆角、25 档字号（含 `10.5/11.5/12.5/13.5/14.5` 五个半像素值）、按压 3 种写法（scale / 近白 / 填充）、遮罩带 `backdrop-filter`。HyperOS 是**平面色阶**：没有阴影、没有渐变，层级 = `surface → surfaceContainer → surfaceContainerHighest` + 间距。

**前置**：先做 B4（删块），CSS 面积变小再换 token。

**目标 token（CSS 变量）**

| 变量 | light | dark | 用途 |
|---|---|---|---|
| `--surface` | `#F7F7F7` | `#000000` | 页面底、顶栏/底栏底 |
| `--card` | `#FFFFFF` | `#242424` | 卡片底 |
| `--card-high` | `#E8E8E8` | `#2D2D2D` | 输入框/胶囊底（surfaceContainerHighest）|
| `--ink` | `#000000` | `#F2F2F2` | 正文/标题 |
| `--sub` | `#6B7280`* | `rgba(255,255,255,.60)` | 摘要（≈ onSurfaceVariantSummary）|
| `--sub-2` | `#5F6672`* | `rgba(255,255,255,.50)` | 次要操作/轴标签（≈ onSurfaceVariantActions）|
| `--line` | `#E0E0E0` | `#393939` | 分割线（`dividerLine`，Web 用 1px 近似 0.75dp）|
| `--outline` | `#D9D9D9` | `#404040` | 真描边（尽量别用）|
| `--accent` | `#FF6900` | `#FF6900` | **App 自有品牌色**，只用于填充/数据线/选中 |
| `--accent-text` | `#C24E00`* | `#FF8A3D` | 白/黑底上的橙**文字**（过对比度）|
| `--accent-soft` | `#FFF1E6` | `#2B1A0C` | 暖色容器（App 自有）|
| `--press` | `rgba(0,0,0,.10)` | `rgba(255,255,255,.10)` | 按压遮罩（替代 ripple/scale）|
| `--error` | `#E94634` | `#F12522` | 唯一红色状态 role |
| `--radius` | `16px` | 同 | 卡片/按钮/输入框 |
| `--radius-sheet` | `28px` | 同 | sheet 顶部 |
| `--page-pad` | `12px` | 同 | 页边距 |
| `--row-pad` | `16px` | 同 | 卡片内行内边距 |

\* `--sub/--sub-2/--accent-text` 的 light 值是**为对比度改过的**（见 B6），不是 Miuix 原值；注释里写明「对比度修正」。

**字号阶梯（只留这些）**

| 用途 | size / weight |
|---|---|
| 页面标题 | 20px / 500 |
| sheet 标题 | 18px / 500 |
| 行标题、按钮文字 | 17px / 500（按钮 17px/400）|
| 卡片内正文 | 16px / 400 |
| 行副行、说明 | 14px / 400 |
| 页脚、最小说明 | 13px / 400 |
| 底栏标签 | 12px / 400（选中 700）|
| 分组标题 | 14px / 700（Bold 只在这里和底栏选中）|
| 数据展示 | 26 / 44 / 54 / 58 / 78px（大数字，允许例外，单位 17px）|

**做法（按顺序，一次只动一类）**

1. 改 `:root` 为上面两套变量（保留旧变量名做别名过渡：`--bg: var(--surface); --orange: var(--accent);`，改完后 `grep -n "var(--bg)"` 应该没有残留，再删别名）。
2. 删阴影：`grep -n "box-shadow" style.css` → 全部删（或 `box-shadow: none`）。底栏顶部分割线保留 `border-top: 1px solid var(--line)`。
3. 删渐变：`grep -n "gradient(" style.css` → 全部删。`hero` / `profile-card` / 主按钮 / 进度条改成纯色（`--card` / `--accent`）。`hero::after` 装饰圆环删掉。
4. 按压统一：删掉所有 `:active { transform: scale(...) }` 和 `:active { background: #fafbfd }` 之类，统一为：
   ```css
   .row:active, .action-row:active, .device-strip:active, .quick-add:active,
   .btn-primary:active, .btn-ghost:active, .scan-item:active, .tab-item:active {
     box-shadow: inset 0 0 0 1000px var(--press);
   }
   ```
   `box-shadow` 用 inset 遮罩是这里**唯一允许出现 box-shadow 的地方**（注释写明理由）。
5. 圆角收敛：卡片/按钮/输入框 `--radius`(16)、sheet 顶部 `--radius-sheet`(28)、头像/圆点 `50%`、其它（10/11/12/13/14/15/17/22/24/26）全部归到 16 或 12。验收 `grep -o "border-radius: [^;]*" | sort -u | wc -l` ≤ 6。
6. 字号收敛：按上表替换（`11.5px→14` 或 `13`，看语义；`10.5px→12`；`12.5→13`；`13.5→14`；`14.5→14`；`15→16`；`19→17`；`23→20`；26/44/54/58/78 保留）。
7. 字重收敛：`font-weight: 700` → 只保留分组标题（`.section-head h2`、`.month-head b`）和底栏选中；页面标题、sheet 标题、行标题、按钮改 `500`。
8. 遮罩：`background: rgba(0,0,0,.30)`（暗色 `.60`），删 `backdrop-filter`；触屏点击遮罩关闭见 B7。
9. sheet：`border-radius: 28px 28px 0 0`，删 `box-shadow`；`max-width: 560px` 保留。
10. 主按钮：`background: var(--accent)`、`color: #fff`、`min-height: 44px`、`border-radius: 16px`、无阴影/渐变/发光。
11. 输入框：`background: var(--card-high)`、无边框、`border-radius: 12px`、聚焦 `box-shadow: 0 0 0 2px var(--accent)`（这是允许的第二处 box-shadow，注释写「聚焦描边」）。
12. 触控目标见 B6。

**验收**：
- [ ] `grep -c "gradient(" style.css` = 0。
- [ ] `grep "box-shadow" style.css` 只剩 2 类：按压 inset 遮罩、输入框聚焦描边（外加 `none`）。
- [ ] `grep -o "border-radius: [^;]*" | sort -u | wc -l` ≤ 6。
- [ ] `grep -o "font-size: [0-9.]*px" | sort -u` 里不再有 `.5px` 半像素值。
- [ ] 4 个 Tab + 5 个 sheet 截图与 B3 前对比：层级靠色阶而不是阴影，卡片无描边，主按钮无发光。
- [ ] **不要**为了「更 HyperOS」重写 canvas 图表（只改颜色/字号，见 B2/B6）。
- [ ] **不要**把品牌橙全换成 `#3482FF`（那是产品决策，不是缺陷）；只保证橙**文字**用 `--accent-text`。

---

### B4 信息密度收缩（删块）

**为什么**：首屏 6 个区块，同一事实重复 3 遍。按「首屏只回答当下 / 同一事实只出现一次 / 留白也是信息」。

**要删的（逐条，删完自查）**：

| 删什么 | 位置 | 理由 |
|---|---|---|
| 首页 `#deviceStrip` 整块 | `index.html` `.card.device-strip` + `app.js` `renderDeviceStrip()` + 调用 | 设备状态已经在 hero 的 `#scanChip`；设备详情在「我的 → 设备」 |
| 首页「最近记录」整块 | `index.html` `.section-head`（最近记录/全部记录）+ `.card.list-card#recentList` + `app.js` `renderRecent()`/调用 | 与 hero 的「最新一条 + 较上次」重复；记录 Tab 第一屏就是它 |
| 首页 `footnote` | `index.html` 主页末尾 `<p class="footnote">体重来自小米 S400…` | 常驻免责声明，说明只在「关于」留一次 |
| 趋势页 `footnote` | 同上 | 同上 |
| 记录页 `page-sub`「全部称重数据」 | `index.html` `#recordSub` | 零信息；条数由 `#recordCount` 表达 |
| 记录页月分组「N 条」 | `app.js` `renderRecords()` 的 `month-head` 里 `<span>` | 低频信息，且与顶部总条数重复 |
| 趋势 `chartSub` 的「最近 N 天 ·」 | `app.js` `renderTrend()` | 区间由 seg 选中态表达，只留「N 次记录」 |
| 趋势洞察第一句（区间变化 + 周均） | `app.js` `renderInsight()` 的 `text` 前半段 | 上面的 `#trendStats` 已经有「区间变化 / 平均」；洞察只留最低/最高（带日期）+ 目标差 |
| 目标卡「已完成 N%」 | `app.js` `renderGoal()` 的 hint | 进度条已表达百分比 |
| 指标卡副标签（健康/正常/静息估算/本地估算） | `app.js` `renderMetrics()` | 三级信息，删 |
| 档案 sheet 的 `sheet-desc` + `form-tip` | `index.html` `#sheetProfile` | 说明书；「重算历史」改成按钮动作词（B5）|
| 「关于」卡 `about-note` 整段 | `index.html` `.about-note` | 说明书；bind key 说明已在设备页 |
| 引导页正文 + 3 条编号清单 | `index.html` `#onboarding` 的 `<p>` 与 `<ul class="onboard-list">` | 一次性页面也不能是说明书；留 logo + 一句动作词 + 两个按钮 |

**要保留的**（别顺手删）：
- 「上秤称重」主按钮 + 大数字 + 较上次（首屏只回答这两件事）。
- 3 个指标卡（体脂/BMI/BMR 是这个秤的核心价值），只删副标签。
- 设备 chip（状态）。
- 「我的 → 数据」里「清空全部记录」的副行「只删记录，不动档案和设备绑定」（不可逆操作前确实防误操作）。
- 「解除绑定（保留记录）」的括号。
- 称重层 pulse 动画与实时数字（全 App 唯一「写操作有可见进度」的正例）。

**验收**：
- [ ] 首屏只剩：hero（大数字 + 较上次 + 迷你折线 + 主按钮 + chip）→ 3 个指标 → 目标卡。滚动高度明显变短。
- [ ] 首页/记录/趋势页没有任何「免责声明 / 使用说明 / 解释性长句」（逐页读一遍）。
- [ ] `?empty=1` 空首页不再出现 4 句解释（配合 B5）。
- [ ] 删掉 `renderRecent` / `renderDeviceStrip` 后 `grep -n "recentList\|deviceStrip" app.js index.html style.css` 无残留（CSS 规则一起删）。
- [ ] `node --check`、`test/run.sh` 通过；浏览器 4 个 Tab 无 console error、无横向溢出。

---

### B5 文案清算（删说明书 / 动作词化）

**原则**：先删 → 改名/改值自解释 → 才配 ⓘ/Toast。默认态不写提示。

**逐条对照表**（左：现在；右：改成；「否」= 删了不会误操作 → 删）：

| 位置 | 现在 | 改成 | 删了会误操作？ |
|---|---|---|---|
| `index.html` 顶栏 `#brandSub` + `app.js` `greeting()` | 「夜深了，注意休息 / 早上好，今天也在好好记录 / 中午好，记得按时吃饭 / 下午好，保持节奏 / 晚上好，别忘了称一称」 | **删**（顶栏只留「体重记」） | 否 |
| `app.js` `renderHome` `#lastMeta` 空态 | 「还没有记录，绑定秤后站上去即可」 | **删**（显示 `--`，主按钮就在旁边） | 否 |
| `index.html` `#sparkEmpty` | 「记录 2 次后显示趋势」 | **删**（空画布） | 否 |
| `index.html` 主页/趋势 `footnote` | 两条估算声明 | **删**；「关于」只留一条（B4） | 否 |
| `index.html` 趋势 `page-sub` | 「看长期变化，而不是单次数字」 | **删** | 否 |
| `index.html` 记录空态 | 「站上体重秤，或在右上角手动补一条。」 | 「站上秤就会自动记录」 | 否（按钮就在下方） |
| `index.html` 数据行副行 ×3 | 「保存到『下载/WeightLog』，可导入表格」/「含原始广播，适合备份/排查」/「先看看完整界面长什么样」 | **删**（CSV 行改成「上次导出 …」动态，见 B1；DEMO 行按 A5 删除） | 否 |
| `index.html` 清空行副行 | 「只删记录，不动档案和设备绑定」 | **保留**，改「只删记录，档案和设备绑定不动」（更短） | **是——保留** |
| `index.html` 档案 `sheet-desc` | 「身高、年龄、性别用于估算…只存在本机。」 | **删** | 否 |
| `index.html` 档案 `form-tip` | 「保存后会按新档案重算历史记录的体脂率 / BMI / 基础代谢。」 | 删这句，**按钮改成「保存并重算历史」** | **是——重算是真实行为，必须进动作词** |
| `index.html` 设备 `form-tip` | 两行 bind key 说明（含「不是 24 位的连接 token」） | 删整段；把「32 位 hex，不是 TOKEN」放进 `#dKey` 的 placeholder | 可能——这是高频误解，保留半句在 placeholder |
| `index.html` 设备 `bind key` 字段标签 | 「bind key 16 字节 / 32 位 hex」 | 保留（字段名 + 规格，不是说明句） | 否 |
| `index.html` 关于 `about-note` | 整段 | **删** | 否 |
| `app.js` 引导第 2 步 | 「S400 已预绑定，不用再扫描添加（可在「我的 → 体重秤」里恢复）」 | 「S400 已预绑定」 | 否（括号是说明书） |
| `app.js` `renderMetrics` 副标签 | 「健康 / 正常 / 静息估算 / 本地估算」 | **删** | 否 |
| `app.js` `renderGoal` 未设目标 | 「设置一个目标，趋势页会显示达成进度。」 | **删**（卡片可点 + 「未设置」） | 否 |
| `app.js` `renderGoal` 无记录 | 「记录第一条体重后开始计算进度。」 | **删** | 否 |
| `app.js` `renderGoal` 达成 | 「已达成目标 <b>X kg</b>，继续保持」 | 「已达成目标 <b>X kg</b>」 | 否 |
| `app.js` `renderDeviceBanner` scan_failed | 「稍等几秒会自动重试，也可以去设置看看蓝牙状态。」 | **删**（横幅已有「去设置」按钮 + 标题） | 否 |
| `app.js` `renderDeviceBanner` rescue | 「超时后正在重新广播发现（地址不对会自动纠正）」 | 「重新找秤中」 | 否 |
| `app.js` `renderDeviceBanner` 未绑定 | 「扫描附近设备或手动填写 MAC 和 bind key」 | **删**（下面就是这两个控件） | 否 |
| `app.js` 结果页无指标 | 「完善档案后估算体脂」 | 改成可点跳档案（`data-action="open-profile"`），或删 | 否（删了用户不知道去哪补，建议可点） |
| `app.js` 称重层 8 s 提示 | 「确认秤在身上、蓝牙已打开；第一次用请检查 bind key」 | **保留**（异常态才出现，是动作指引） | **是——保留** |
| `app.js` 空记录行 | 「还没有记录，站上秤试试」 | 删（B4 已删整块） | 否 |

**验收**：
- [ ] 逐屏截图：默认态（有数据 / 空态）里没有第二句解释。
- [ ] `grep -n "记录 2 次后\|只存在本机\|按周/月看趋势\|注意休息\|保持节奏" app.js index.html` → 0 命中。
- [ ] 保留的两条功能性文字仍在（清空副行、重算动作词）。
- [ ] `node --check` 通过。

---

### B6 命中区 ≥44dp + 对比度 + 图表可读性

**为什么**：实测（390×844，1 CSS px ≈ 1 dp）关闭 × 32×32、密钥眼睛 43×32、顶栏「+ 记录」74×34、分段控件 34 高、主按钮 40 高——都 < 44。对比度实测 `--sub #8b909a` 3.2:1、`--sub-2 #aab0ba` 2.18:1（图表轴标签、月份、页脚）、绿 2.66:1、红 3.58:1、白底橙字 2.89:1，AA 要 4.5:1。

**做法**：

1. 命中区（视觉尺寸可不变，靠 padding / min-height 扩）：
   ```css
   .quick-add { min-height: 44px; height: auto; padding: 0 16px; }
   .sheet-close { width: 44px; height: 44px; margin: -6px -6px -6px 0; }
   .input-eye { height: 44px; right: 4px; }
   .seg-item { min-height: 44px; height: auto; padding: 10px 0; }
   .seg-sm .seg-item { min-height: 36px; height: auto; }
   .profile-edit, .banner-btn, .preset-btn { min-height: 44px; height: auto; }
   .btn-primary, .btn-ghost { height: auto; min-height: 44px; padding: 12px 22px; }
   .tab-item { min-height: 44px; }
   ```
   然后**重新量一遍**（Playwright `getBoundingClientRect`）并记录到验收里。
2. 颜色 token 换对比度达标值（与 B3 表一致）：
   - `--sub: #6B7280`（4.83:1）、`--sub-2: #5F6672`（5.78:1）
   - `--green: #007A50`（5.39:1）、`--red: #C22A20`（5.76:1）
   - `--accent-text: #C24E00`（4.79:1）——所有「橙色的文字」用它；填充仍用 `--accent`
   - placeholder `#767E8C`（4.09:1，占位可接受）
   - 暗色按 B2 的 `rgba(255,255,255,.6/.5)`（在 `#242424` 上 ≈ 7:1 / 5.5:1）
3. 图表：轴标签/提示字号 `10px → 11px`，颜色从变量读（B2 已做）；x 轴标签跨年时带年份（C2 做）；`.chart-tip` 不要用 `hidden` 造成布局跳动 → 改成常占位（`.chart-tip { min-height: 23px; visibility: hidden; }` + `.show { visibility: visible; }`），或外层固定 `min-height`。
4. canvas 无障碍：`<canvas id="chart" role="img" aria-label="...">`，在 `renderTrend` 里同步 `aria-label`（例如「最近 30 天体重，最新 65.4 公斤，区间变化 -1.4 公斤」）；`#spark` 同理。

**验收**：
- [ ] Playwright 量 9 个控件的 `width/height`，全部 ≥44（`.seg-sm` 允许 36，但外层容器 ≥44）。
- [ ] 对比度用工具（或手算）复核 6 个 token：正文 17px 4.5:1、小字 13px 4.5:1。
- [ ] 1.3× 字号模拟（逐个元素乘 1.3 的 `fontSize`）截图：不横向溢出；分段标签不挤成一行贴死；设备卡不出现「已预…」截断（B4 删了该卡后应自然消失）。
- [ ] 图表拖动时卡片高度不跳。

---

### B7 交互与表单健壮性（10 处小修）

| # | 问题 | 位置 | 改法 |
|---|---|---|---|
| 1 | 点遮罩不关 sheet（用户以为卡了） | `app.js` `init()` 附近 | `$("mask").addEventListener("click", closeSheet);`（`#mask` 目前没有任何 click 监听） |
| 2 | 5 个 sheet 都有 `.sheet-grab` 横条但没有拖拽实现（假控件） | `index.html` 5 处 | 删掉 `.sheet-grab`（和对应 CSS）；要真拖拽成本高，本期不做 |
| 3 | 导出无忙碌态，连点导出多份 | `app.js` `exportData()` | 调用桥前 `btn.disabled = true; btn.textContent = "导出中…"`，`finally` 恢复；导出完成后副行显示「上次导出 …」 |
| 4 | 「清空全部」的确认只靠 2.2 s toast，按钮文字不变，用户看不见待确认 | `app.js` `clearAll()` | armed 期间按钮文字改「再点一次确认清空」+ `.action-row.danger.armed` 高亮；4 s 后复原（与 `deleteRecord` 的 armed 文案保持一致） |
| 5 | 别的 sheet 打开时来了 `final`，结果写进没打开的称重层 → 用户看不到任何反馈 | `app.js` `__wlEvent__("final")` | 把 `if (!App.sheet)` 的判断提到 `showResult` 调用处：**有别的 sheet 打开时只 toast「已记录 X kg」**，不要动 `#weighResult`/`App.weighOpen` |
| 6 | `closeSheet()` 不清 `App.live`，芯片永久卡「称重中」 | `app.js` `closeSheet()` / `renderScanChip()` | `closeSheet` 里 `App.live = null;`；`renderScanChip` 要求 `App.sheet === "sheetWeigh"` 才算「称重中」 |
| 7 | 版本兜底硬编码 `"1.0"`（1.1 包在 state 失败时显示 1.0） | `app.js` `renderMe()`、`index.html` `#aboutVersion` 占位 | 兜底改 `"—"`，HTML 占位也改 `—` |
| 8 | `reload()` 在 `list()` 返回非 JSON 时把 `App.records` 清成 `[]`（列表瞬间全空） | `app.js` `reload()` | 只在返回是数组时覆盖；`state` 返回 `{error}` 时保留旧值 |
| 9 | 表单：未来时间被原生**静默**夹到 now；目标体重不校验 20–250；错误提示不聚焦 / 无 `aria` | `app.js` `saveManual` / `saveProfile`、`index.html` `#addError`/`#profileError` | `inputTime.max` 设为当前本地时间；超界提示「不能记录未来时间」；目标值校验 20–250；错误节点加 `role="alert"`，出错 `aria-invalid` + `input.focus()` |
| 10 | 手动记录默认填入上次体重，用户可能不看清就保存 | `app.js` `openAddSheet()` | value 留空，把上次体重放进 `placeholder` |

**验收**：
- [ ] 浏览器：点遮罩关闭 sheet；4 个 sheet 无 grab 横条；连点导出两次只出一份（或按钮在导出中不可点）；清空按钮第一次点后文字变化、4 s 恢复。
- [ ] 在「我的」sheet 打开时注入 `live`/`final`：只出现 toast，不污染称重层；关掉 sheet 后芯片不再显示「称重中」。
- [ ] `WeightLog.state()` 抛错时（devtools 覆盖 `window.WeightLog.state = function(){return "{}"}`）「关于」显示 `—`。
- [ ] 手动记录填明天 → 保存被拦并提示；填 1 kg 目标 → 被拦。
- [ ] `node --check`、`test/run.sh` 通过。

---

## 7. 批次 C：2.0（**未经确认不要动**）

> 这三个是「产品级改动」，每个都要改桥契约 + 文档 + 测试。等你确认要做哪几个再开工。

### C1 家人 / 第二人归属（最小双人档，估 3–5 天）

现状：`profile_id` 列已在表里（`DEFAULT 1`），但 `MainActivity` 硬编码 `1`；只有一份 `profile_v1`；记录没有「人」字段。后果：家人称重会成为首页「当前体重/较上次」（Δ 可能 −15 kg）、体脂按我的档案算、改档案会把家人记录一起重算、5 秒内第二人直接被去重丢弃。

**最小方案**（不做账号、不做云端）：
1. `Config` 新增 `profiles_v2`（JSON 数组，最多 2 条，每条 `{id, name, heightCm, age, sex, targetWeightKg, targetSetAtKg}`）+ `active_profile_v1`；`profile_v1` 保留为 `id=1` 的镜像（向后兼容，旧版本能读）。
2. `state().profile` 保持原样（= 当前 active）；新增 `state().profiles[]` 与 `activeProfileId`。
3. 桥新增 `setActiveProfile(id)`；`saveProfile` 支持可选 `profileId`（缺省 = active）。
4. 称重落库时 `m.profileId = activeProfileId`，体成分用该 profile 的档案算；**结果页不加「这是谁」的选择**（5 秒内决定人太难），改为：记录详情里加「归属」两个 chip（我 / 家人），点一下改 `profile_id` 并按该 profile 快照重算这一条（新增桥 `setRecordProfile(id, profileId)`）。
5. 记录页/趋势加「只看我 / 全部」；默认 active。
6. `recalcHistory` 改成按 `profile_id` 分组重算。
7. 导出 JSON 加 `profiles`；导入时映射（缺省全归 1）。

**验收**：两个人交替上秤 → 首页只显示 active 的；记录详情可改归属且体脂跟着变；导出含 `profiles` 且能被 B1 导入器还原。**不建议**做「多手机同步」（无网络）。

### C2 长期趋势（估 1 天）

- 7 日移动平均线叠在原始折线上（纯 JS，无新依赖）。
- 「全部」在 >120 点时按月平均（散点 + 折线），避免 365 个点的锯齿。
- x 轴刻度按时间等距取 4–5 个（现在只取首/中/尾记录），跨年时标签带年份（`fullDate` 现在没有年）。
- 洞察的「平均每周」现在用首末两点线性外推，被首条异常值一带就失真 → 用移动平均后的首末点，或改成「近 30 天均值」，并在文案上说清口径（用词不是说明句：`平均每周 -0.3 kg`）。
- 验收：伪造 400 条一年数据（浏览器 localStorage 或临时 `seedDemo` 变体）→ 图表可读、切 7/30/90/全部 不卡（<100 ms）。

### C3 记录编辑 + 删除撤销（估 1 天）

- 桥 `updateRecord(id, {weight_kg, measured_at_ms})`：改数值/时间后按当前档案重算体成分；校验 5–400 kg、时间不得未来。
- 删除改成 5 秒可撤销（前端持有被删行，点「撤销」重新插入，id 会变——可接受）。
- 验收：改一条时间到昨天 → 列表重排、月分组正确、趋势更新；删一条 → 5 s 内可撤销，超时后不可。

### C4 异常值拦截（估 0.5 天）

- BLE 体重 `<30` 或 `>200` kg：保存前弹确认（「这次 6.5 kg，确认记录？」）。
- 与 24 h 内上一条差 `>5 kg`：记录行/详情打「异常」标记，趋势可排除（先只前端标记，不改 schema）。
- 手动补录与上一条差 `>10 kg`：二次确认（顺带挡 kg/lb 误输）。
- 验收：注入 6.5 kg 的 live 帧 → 出现确认；取消后不入库。

### C5 工程债（按需）

1. 抽 `assets/www/pure.js`（`wallClock/dayStart/dayLabel/timeLabel/fullDate/monthLabel/fmt1/fmtDelta/esc/prevWeights/ascRecords`），`app.js` 改为引用；新增 `test/www/run-js.sh`（node 断言 + ES5 lint：正则扫 `let|const|=>|模板串|?.|??|replaceAll|padStart|Object.assign|Array.from`）；`index.html` 在 `app.js` 之前加 `<script src="pure.js">`。**不引入打包。**
2. `WeightDb` 投影查询：`list()` 加不选 `raw_json` 的重载给 UI/重算用，`raw_json` 只在导出选。
3. `recalcHistory` / `doExport` 挪到 io 队列（同步返回 `{pending:true}` + 事件）——**契约改动**，要同步 AGENTS/README/test。
4. `MainActivity` 拆分（`StateSerializer` / `ScanController` / `WeighCoordinator` / `RescueCoordinator` / `ExportController` / `DeviceController` / `DemoData` / `WebViewBootstrap` / `WindowStyler`），目标 450–600 行；只搬代码不改行为，每搬一个跑一次测试。
5. WebView 纵深：`setAllowFileAccess(false)`、加 `shouldOverrideUrlLoading` 只放行 `file:///android_asset/`、`index.html` 加 CSP meta（`default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:`）。**每项都要冒烟确认页面还能加载、图表还能画。**
6. 补 10 个 JVM 用例（明文帧接受路径、`ccmDecrypt` 参数守卫、`pollQuietFinalize` 去重分支、heartbeat 带高阻抗、`RescuePolicy.noteAlive`、`BodyComposition` 边界、`toBackupJson` schema、`isoTime` 偏移、`Exporter.csv` 注入/`Config.sanitizeName`——后两个需要先把纯函数抽出来）。

---

## 8. 契约变更汇总（写完对着这张表检查）

| 类型 | 名称 | 变更 | 向后兼容 | 同步位置 |
|---|---|---|---|---|
| 桥方法 | `clearDemo()` | 新增 | 是 | AGENTS §5.1、README §1、`bridge-demo.js` |
| 桥方法 | `beginImport()` / `importData(uri)` | 新增（B1） | 是 | AGENTS §5.1、README §5/§10 |
| 事件 | `import` | 新增（B1） | 是 | AGENTS §5.2 |
| `state()` | `profile.targetSetAtKg` / `targetSetAtMs` | 新增键（A2） | 是 | AGENTS §5.3 |
| `state()` | `lastExportAtMs` | 新增键（B1） | 是 | AGENTS §5.3 |
| `state()` | `dbError` | **语义落地**（A3，键已存在） | 是 | AGENTS §5.3/§11 |
| 导出 JSON | 根 `schema_version` / `app_version` | 新增键（B1） | 是 | AGENTS §9 |
| 配置 | `profile_v1` 加 `targetSetAtKg`/`targetSetAtMs` | 新增键（A2） | 是（缺省 0） | AGENTS §7.2 |
| 配置 | `last_export_v1` | 新增键（B1） | 是 | AGENTS §7.2 |
| DB | 无 schema 变更（A/B 批次） | — | — | — |
| 测试 | 新增 A1 三条 + B1 六条 JVM 断言 | — | — | `test/ReviewTest.java`、`test/run.sh` |

---

## 9. 测试与验收（每个任务都要跑）

### 9.1 必跑命令

```sh
cd weightlog

# 1) JS 语法（两个文件都要）
node --check assets/www/app.js
node --check assets/www/bridge-demo.js

# 2) JVM 回归（改过纯 Java 必跑；期望 PASS n / FAIL 0）
bash test/run.sh

# 3) 构建（新版本时带 --version/--code）
bash build.sh --version 1.2 --code 3
rm -rf build

# 4) APK 静态门禁
BT="$HOME/Library/Android/sdk/build-tools/37.0.0"
"$BT/aapt2" dump badging apks/weightlog-1.2.apk | grep -E "^package:|application-label:|sdkVersion|targetSdkVersion"
"$BT/aapt2" dump permissions apks/weightlog-1.2.apk        # 必须没有 INTERNET
"$BT/apksigner" verify --verbose apks/weightlog-1.2.apk    # v1/v2/v3 = true
shasum -a 256 apks/weightlog-1.2.apk
ls apks/ | grep -c idsig                                    # 必须是 0
```

### 9.2 浏览器冒烟（无设备也能跑）

```sh
cd assets/www
python3 -m http.server 8797 --bind 127.0.0.1 &
# 打开 http://127.0.0.1:8797/index.html?reset=1（390×844，DPR 2）
```

最低检查项（改哪块测哪块）：
- [ ] 无 `pageerror` / `console.error`。
- [ ] 4 个 Tab 无横向溢出（`document.documentElement.scrollWidth === clientWidth`）。
- [ ] `?empty=1` / `?onboard=1` 正常；空态没有第二句解释。
- [ ] 点「上秤称重」→ 收到 live → 点「先不称了」→ 2–3 s 内不被重新弹开，`final` 仍落库。
- [ ] 设备页注入 `scan.error` → 出现「去设置」并能转发 `openSettings`。
- [ ] 导出 CSV 带 BOM、`=1+1` 被转义为 `'=1+1`；导出 JSON 含 `schema_version` / `profile_*` / `created_at_ms` / `raw_json`。
- [ ] `device.preset=true` 时引导第 2 步文案、设备页「恢复预绑定」可用。
- [ ] 暗色（B2）：`color_scheme=dark` 截图与 light 不同；图表线可见。

### 9.3 模拟器 / 真机冒烟

```sh
# AVD mgmt30（API 30 arm64，无 BLE 广播源，只能验证 UI/DB/权限/导出/导入）
~/Library/Android/sdk/emulator/emulator -avd mgmt30 -no-window -no-audio \
  -no-boot-anim -gpu swiftshader_indirect -no-snapshot -no-snapshot-save &
adb install -r apks/weightlog-1.2.apk
adb logcat -c; adb shell am start -n com.weightlog.app/.MainActivity
adb logcat -d -s WeightLog:*      # 期望 app start version=1.2 ... 无 FATAL
```

- [ ] 干净包（`--no-preset`）安装 → 删数据重进 → 权限流程（A4）。
- [ ] 导入/导出（B1）在真机跑一遍；`下载/WeightLog/` 里有文件。
- [ ] 暗色切换后无白闪；`cmd uimode night no` 还原。
- [ ] 真秤只能真机验证（站上秤 → live + final 各一次；key 错报 `decode_failed`）——**如果做不到，明确写"未验证"**，不要假装通过。

### 9.4 截图回归（A6 / B2 / B3 必做）

用 Playwright + Chrome（本机已装 Chrome，`channel="chrome"`；**Python playwright 已装但自带的 chromium headless shell 没下载**，直接 `p.chromium.launch(channel="chrome")` 调系统 Chrome 即可，不要为此下载浏览器）在 390×844、DPR 2 下出图，页面一律用 `?reset=1` 拿 24 条演示数据：
- light：首页 / 记录 / 趋势 / 我的 / 称重 / 手动记录 / 档案 / 设备 / 详情 / 空首页 / 空记录 / 引导。
- dark：首页 / 记录 / 趋势 / 我的 / 称重（做完 B2 才要）。
- 存到 `docs/images/`（文件名沿用现有 7 个：home/records/trend/me/device/weigh/onboarding.png；新增页可放 `docs/images/`）。
- 对照清单：引导第 2 步文案、首页是否还有「最近记录」块、空态是否还有解释句、暗色是否真的深色。

---

## 10. 交付物清单

1. 代码改动（`assets/www/*`、`src/**`、`AndroidManifest.xml` 只在必要时）。
2. `apks/weightlog-1.2.apk`（或 1.3）+ `AGENTS.md` §16 的版本记录（大小 / SHA-256 / 验证说明）。
3. 文档：`README.md`（用户可见行为）、`AGENTS.md`（契约、§5/§7/§9/§16）、本文档（勾掉已完成项 + 记下偏差）。
4. 测试：`test/ReviewTest.java` 新增断言、`test/run.sh` 的 `FILES`（如新增纯 Java 类）。
5. 截图：`docs/images/` 更新，明暗两套。
6. 变更摘要（给用户看，5–10 行）：改了什么、验收命令实际输出、哪些没验证/没做及原因。

---

## 11. 踩坑提示（本次审查新增，按代价排序）

1. **`env()` 放进自定义属性 = 回退失效**：只有在 `calc()` 里内联 + 前置纯数值声明才是真回退。别再把 `env()` 塞进 `:root` 变量。
2. **CSS 的 `#E6FFFFFF` 是无效值**：Android ARGB 写法不能进 CSS，必须 `rgba()`。抄 Miuix 色值时重点检查。
3. **暗色 + 算法暗色 = 二次反色**：`prefers-color-scheme` 做完必须 `setForceDark(OFF)` / `setAlgorithmicDarkeningAllowed(false)`，否则 API 29+ 会把卡片压成怪色。
4. **`build.sh` 会覆盖 `apks/weightlog-<version>.apk`**：新版本用新 `--version`，别把 1.1 覆盖掉；构建后 `rm -rf build`（不提交）。
5. **预绑定 key 在构建日志里不能出现**：`build.sh` 只打 MAC；如果你加了日志，别打 key / 别打 `BuildConfig.PRESET_SCALE_BIND_KEY`。
6. **`configChanges` 别加 `uiMode`**：加了暗色切换不重建 WebView，页面不会变色。
7. **旧 WebView 不认 `backdrop-filter`**：删掉比留着好（新旧机观感不一致）。
8. **`transaction()` 返回 void 且吞异常**：新增写路径（导入/撤销）必须自己 `try/catch` 并检查结果，别依赖它报错（C5 会修）。
9. **JavaBridge 线程同步**：桥方法里不要做重活（导入解析、全表导出都放 io）。JS 会同步等返回。
10. **`Bridge.push` 用 `evaluateJavascript` 拼字符串**：payload 里不能有 U+2028/U+2029（JSON 允许，但 JS 字符串里会断）。用户备注粘贴可能触发——如果改了 `push`，用 `JSONObject.quote` 包一层。
11. **`?empty=1` / `?onboard=1` 只存在于 `bridge-demo.js`**：真机没有这些参数，验收时别依赖。
12. **`bridge-demo.js` 与原生行为有已知漂移**：`seedDemo` 会清掉 demo 之外的记录、CSV 小数位不同、`saveDevice` 不重启扫描。修 B1/A5 时顺手对齐或写注释，别拿浏览器行为当原生机行为。

---

## 12. 明确不要做的事

- ❌ 不要引入 Gradle / Compose / Miuix 库 / npm 依赖 / 打包步骤 / 网络字体。
- ❌ 不要自绘 squircle（CSS 做不到连续曲率；16dp 卡片差异 <1px，收益 0）。
- ❌ 不要重写 canvas 图表或引入图表库（无网络权限，APK 117 KB）。
- ❌ 不要加 `INTERNET`、不要做手机 ↔ 电视的网络同步（只做文件级导入导出）。
- ❌ 不要加 Widget / 系统通知 / 前台 Service / 新 Activity / Receiver / Provider。
- ❌ 不要为了「统一动效」砍掉称重层的 pulse 与实时数字（它是唯一正例）。
- ❌ 不要删 RESCUE 地址兜底、不要删 CSV 导出、不要删「清空」的二次确认与副行、不要删「解除绑定（保留记录）」的括号。
- ❌ 不要给记录页加月份折叠（已有 全部/7/30/90 + 按月分组）。
- ❌ 不要把品牌橙全换成 `#3482FF`（产品决策，先问）。
- ❌ 不要改仓库以外的工程目录（它们不属于本仓库）。

---

## 附录 A：关键锚点（按文件）

| 文件 | 关键函数 / 区域 | 本文相关 |
|---|---|---|
| `MainActivity.java` | `onCreate` 109–159；`setupWebView` 161–242；`onPause` 584–596；`onDestroy` 598–660；`onRequestPermissionsResult` 717–735；`requestAutoScan` 742–756；`startScanInternal` 777–798；`onResume` 570–582；`handleBroadcast` 853–904；`saveMeasurement` 939–979；`stateJson` 1037–1082；`recalcHistory` 1016–1035；`seedDemo`/`seedDemoRows` 1222–1290；`saveProfile` 1294–1334；`saveDevice` 1336–1375；`applyPresetDevice` 1377–1400；`exportData`/`doExport` 1463–1495；`setLiveMode` 1497–1510 | A1–A5、A8、B1、B7 |
| `WeighSession.java` | `onPacket` 101；`onHeartbeat` 77；`pollQuietFinalize` 181；`reset` 93 / `resetInternal` 224；`MIN_FINAL_INTERVAL_MS` 55 | A1 |
| `Config.java` | `reload` 68；`devicePublicJson` 189；`saveDevice` 207；`clearDevice` 243；`profileJson` 278；`saveProfile` 302；`sanitizeName` 342 | A2、A5、B1 |
| `WeightDb.java` | `onCreate` 42；`onUpgrade` 69；`transaction` 99；`insert` 132；`deleteBySource` 200；`list` 254（`SELECT *` 282） | A5、B1、C5 |
| `Exporter.java` | `export` 42；`buildCsv` 145；`buildJson` 166；`csv` 193 | B1 |
| `Bridge.java` | `Host` 接口 25–58；`state/list/addManual/…`；`push` 245 | A5、B1 |
| `assets/www/app.js` | `renderAll` 289；`renderHome` 352；`renderGoal` 432；`renderDeviceStrip` 474；`renderRecords` 561；`renderInsight` 678；`openWeigh` 1118；`renderLive` 1150；`showResult` 1176；`openSheet/closeSheet` 1050–1116；`seedDemo` 1616；`clearAll` 1627；`handleAction` 1755；`__wlEvent__` 1915 | A2、A3、A5、B1、B4、B5、B7 |
| `assets/www/style.css` | `:root` 5–30；`.hero` 147；`.btn-primary` 252；`.seg-item` 440；`.sheet` 664；`.tabbar` 616；`.mask` 648；`.sheet-close` 703 | A7、B2、B3、B6 |
| `assets/www/index.html` | 顶栏 14–36；首页 41–74；记录 77–100；趋势 103–136；我的 139–177；sheet 204–357；引导 360–375 | A3、B4、B5、B7 |

## 附录 B：可直接复制的验收片段

```bash
# 行数 / 结构快检
wc -l assets/www/{index.html,style.css,app.js}
grep -rn "dbError" assets/www/app.js
grep -c "gradient(" assets/www/style.css
grep -o "border-radius: [^;]*" assets/www/style.css | sort -u | wc -l

# APK 权限（必须无 INTERNET）
BT="$HOME/Library/Android/sdk/build-tools/37.0.0"
"$BT/aapt2" dump permissions apks/weightlog-1.2.apk | grep -c INTERNET   # 0

# 文档漂移自查
grep -n "一键清空\|双指缩放\|等 8 秒\|不填也能记" README.md
grep -n "1\.1" README.md AGENTS.md | tail -5
```

---

**最后**：做完 A 组后先别急着做 B——把 1.2 包装到你自己手机上用一天（称一次、熄屏一次、导入导出一次），再决定 B1 的交互细节。B 组里 B4/B5 是「删减」，比 B2/B3 更容易做过头，删完拿截图和改前对比，只删不添。

---

## 13. 执行记录（2026-11-02，批次 A 已全部完成）

**产物**：`apks/weightlog-1.2.apk`（1.2 / code 3，117 KB，SHA-256 `d35e6e7ec83179b76e326aaf53d3582e3f8915702a5e0716dcda3b7a04216fd7`，v1+v2+v3，无 INTERNET，无 `.idsig`）。`apks/weightlog-1.1.apk` 保留用于回滚。

| ID | 状态 | 实际改动 |
|---|---|---|
| A1 | ✅ | `WeighSession.forceFinalize()`；`onPause`/`onDestroy` 先补存再 reset；`setLiveMode(true)` 加 `FLAG_KEEP_SCREEN_ON`。JVM 新增 7 条断言（覆盖任务书的 3 个场景）。 |
| A2 | ✅ | `renderGoal`/`renderInsight` 按方向判定；`Config` 新增 `targetSetAtKg/targetSetAtMs`（`saveProfile` 重载、`profileJson` 下发）；`MainActivity.saveProfile` 在目标值变化时写入最新体重。删掉百分比文字。 |
| A3 | ✅ | `#dbErrorBar` + `.bar-danger`；`renderDbError()`；首页「读取失败」；记录页计数/空态换文案并隐藏手动记录按钮；`recordsEmpty*` 加 id。 |
| A4 | ✅ | `pendingScanMode` 在权限检查前记录、所有分支清空；授权回调据此继续；未绑定时报 `no_config`。 |
| A5 | ✅ | 桥新增 `clearDemo()`；`seedDemo` 不再写假档案；按任务书首选方案删除「我的 → 生成演示数据」入口；有 demo 时显示「清除演示记录（N 条）」（两次确认）；记录页「含演示数据」tag。 |
| A6 | ✅ | 1.2/code 3；README/AGENTS/apks-README/§16 同步；7 张截图按 390×844@2x 重拍。 |
| A7 | ✅ | 删除 `--safe-bottom`，5 处 `env()` 内联（`.app/.tabbar/.sheet/.onboard-inner/.toast`）。 |
| A8 | ✅ | `onResume` 自愈（`discoverMode && !scanner.isScanning()` → false）；`recreateWebView` 后清 `discoverMode` 并重新 BIND，列表出所有 set/clear 点。 |

**偏差 / 说明（重要）**

1. **A2 方向判定的伪代码与它自己的验收矛盾**：任务书写的 `dir = current > t ? "down" : "up"` 会让「减重目标掉到目标以下」时方向翻转、永远出不了「已达成」。实现改为 **`dir` 由「起点（targetSetAtKg / 最早记录）→ 目标」决定**，达成用当前值比较目标；这样 70（低于起点 67.4 视角的增重）、62.5（减重）、67（低于起点、已达成）三种验收都成立。浏览器实测：76.1→70「还差 3.9」、→62.5「还差 3.6」、67 未达成时「还差 0.9」、补一条 68 kg 后「已达成 67.0 kg」。
2. **A5 按首选方案删除了「我的 → 生成演示数据」入口**：任务书第二个验收项「浏览器点生成演示数据」因此改为直接调 `WeightLog.seedDemo()` 验证；引导页「先看看演示数据」入口保留。
3. **A6 的「README §11 无障碍」实际在 `AGENTS.md §11`**（README 只到 §10），已改在 AGENTS；README 里对应的是「不填也能记体重」等条目，已按实现改写。
4. **A1 新增 7 条 JVM 断言而不是 3 条**（3 个场景各自拆成可读断言），总数 128 → 135。
5. `bridge-demo.js` 同步了 `clearDemo`、`targetSetAtKg/Ms`、`seedDemo` 不写档案；演示版本号显示 `1.2-demo`。

**验收实测**

- `node --check app.js / bridge-demo.js` 通过；`bash test/run.sh` → **PASS 135 / FAIL 0**。
- `build.sh --version 1.2 --code 3` 成功；`aapt2 dump badging`：1.2 / code 3 / minSdk 23 / targetSdk 31；权限无 INTERNET；`apksigner verify` v1/v2/v3 = true；`apks/` 无新增 `.idsig`。
- 浏览器（Playwright + 系统 Chrome，390×844@2x）：25 项断言全过、0 console/pageerror、4 Tab 无横向溢出；覆盖 A2 三种目标方向、A3 dbError 注入/恢复、A5 清 demo 24 条且保留手动记录 + 空档案 seedDemo 不写档案、A7 `padding-bottom: 76px` 回退 + `--safe-bottom` 已消失 + 5 处内联 `env()`。
- 模拟器 mgmt30（API 30）：安装启动 `app start version=1.2`、无 FATAL；首启自动预绑定；拒绝定位权限 → 设备页「去设置」横幅；点「扫描附近设备」→ 允许权限后 **一次** 即 `BLE scan started mode=DISCOVER`；`clearDemo` 清掉 24 条演示记录（toast「已清除 24 条演示记录」，真实记录保留）。
- **未验证（需要真机 BLE）**：A1 熄屏/切后台补存（`forceFinalize` 的 JVM 断言已过，真机需站秤触发）；A8 页面重载/渲染进程崩溃后的发现模式自愈（仅代码走查 + 逻辑已单列 set/clear 点）。真秤称重、bind key 错误路径本轮未跑。

**未做**：批次 B（B1–B7）与批次 C，按任务书要求等确认。
