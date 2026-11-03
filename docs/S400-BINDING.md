# S400 绑定与配对

> 对象：小米体脂秤 S400（`MJTZC01YM` / `yunmai.scales.ms104`），固件实测 `2.1.1_0006`。
> 本文只讲**这台秤怎么变成"本 App 能解开的秤"**：拿什么、填哪里、什么时候失效、失效了怎么重来。
> 广播帧结构、GATT 登录（协议意义上的"配对"）见 [S400-PROTOCOL.md](S400-PROTOCOL.md)；隐私与威胁模型见 [S400-PRIVACY.md](S400-PRIVACY.md)。
> **文中不写真实 MAC / key，统一用 `XX:XX:XX:XX:XX:XX`、`<32 hex>`、`<24 hex>` 占位。**

---

## 0. 一页速查

| 要做的事 | 怎么做 | 得到什么 |
|---|---|---|
| 拿秤的 MAC | App 设备页「扫描附近设备」，站上秤唤醒 | `XX:XX:XX:XX:XX:XX` |
| 拿 bind key | 米家 token extractor，手机扫码，看这台秤的 `BLE KEY` | 32 位 hex / 16 字节 |
| 填进 App | 设备页填 MAC + bind key →「保存配置」 | 保存后立即开始扫描 |
| 构建时就绑好 | 把 `S400_MAC` / `S400_BLE_KEY` 写进 env 文件，`bash build.sh` | APK 首启即已绑定 |
| 不想内置 | `bash build.sh --no-preset` | 首启需要手动绑定 / 扫描 |
| key 变了 | 米家 App 里**解绑再重新绑定**秤 → 重跑 extractor | 新的 `BLE KEY` |
| 只想知道现在对不对 | 站上秤，看 App 有没有弹出实时体重 | 有 = key 正确 |

最短路径：**扫 MAC → extractor 取 BLE KEY → 填进设备页 → 站上秤验证**。

---

## 1. 先分清两把"钥匙"

S400 有两把完全不同的密钥，界面和第三方工具里都叫得含糊，混了就一定填错。

| 说法 | 实际含义 | 长度 / 形式 | 本 App 要吗 |
|---|---|---|---|
| **米家绑定** | 把秤绑到一个米家账号，云端据此下发下面两把钥匙 | — | 只为**间接拿到钥匙**，App 本身不需要登录 |
| **BLE bind key** | 解密 MiBeacon 广播的 AES-CCM 密钥 | **32 位 hex / 16 字节** | ✅ **唯一要填的** |
| **GATT 登录 token** | 登录秤的实时通道用的凭据 | **24 位 hex / 12 字节** | ❌ 不需要（本 App 只监听广播） |

> **80% 的绑定失败是把 24 位 `TOKEN` 当成了 32 位 `BLE KEY`。**
> 判据很简单：bind key 是 **32 位**十六进制；24 位那个连广播都解不开。

补充三条容易踩的事实：

1. **bind key 不是蓝牙配对密钥**——不需要在系统设置里"配对设备"，也没有 PIN；BLE 层根本不配对，App 只是被动收广播。
2. **一把 key 只对一台秤**，不是账号一把；换秤就得换 key。
3. **只在秤没被重置、没换账号期间有效**；米家里"解绑重绑"就会轮换。

> **"配对"在这台秤上有两个含义，别混。**
> 本文的**绑定** = 把 MAC + bind key 填进 App，被动解密广播，**不建立连接**；
> 协议里还有一种 **GATT 登录**（某些工具叫"配对"）= 连上秤做 challenge-response 登录，要 12 字节 token，
> 且会占用秤唯一的连接位。本 App **不做 GATT**，握手细节见 [S400-PROTOCOL.md](S400-PROTOCOL.md) §2–3。

---

## 2. 前置条件

| 项 | 要求 | 不满足会怎样 |
|---|---|---|
| 米家账号 | 秤绑在**你自己的**账号下（不是别人共享给你的） | extractor 输出里没有这台秤 |
| 账号地区 | 港版 S400 通常在 `tw` / `sg` / `in`，**不在 `cn`** | 只查 `cn` 会完全看不到它 |
| 手机 | 能开浏览器、能扫码登录米家 | — |
| 网络 | 取 key 要联网；**填进 App 后 App 自己不联网** | — |

> 大陆米家 App 的家庭列表里看不到港版秤是正常的，不影响取 key，也不影响 BLE 读取。

---

## 3. 取 MAC 和 BLE KEY

### 3.1 用 token extractor

**本仓库不含取 key 的工具**（App 不联网，也不该集成米家登录）。用社区实现
[Xiaomi-cloud-tokens-extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor)：
下 release → 建虚拟环境 → 运行 → 本地 Web 服务 → 手机二维码登录。

```sh
# 以下命令在 extractor 自己的目录里执行，参数以它当时的 README 为准
./XiaomiCloudTokensExtractor.py                       # 不带 -s：遍历全部地区（港版秤靠这个才找得到）
./XiaomiCloudTokensExtractor.py -s tw                 # 已知在台湾区，可只查一个
./XiaomiCloudTokensExtractor.py -o /tmp/tokens.json   # 存成 JSON，方便复制
```

然后浏览器打开它打印的本地地址（常见 `http://127.0.0.1:31415`），**手机米家扫码**。

二维码登录不触发密码 / 验证码，是目前唯一推荐的日常路径；`miiocli cloud` 那条路 2025 年起会被验证码挡住，不要用。

> 2026 年部分 extractor 版本对 `result: null` / 新版米家接口会崩，社区有兼容补丁；核心是拿 `BLE KEY` 那一行，
> 工具本身怎么修不属于本仓库范围。输出含**全部设备 token**，取完就删。

### 3.2 认哪几行

```text
NAME:     Xiaomi Body Composition Scale S400
MAC:      XX:XX:XX:XX:XX:XX
BLE KEY:  <32 hex>          ← 复制这个
TOKEN:    <24 hex>          ← 不是这个（GATT 登录才用）
MODEL:    yunmai.scales.ms104
```

| 字段 | 用途 | 备注 |
|---|---|---|
| `MAC` | App 里筛广播、认设备 | 是**固定地址**，不是随机地址 |
| `BLE KEY` | 解密体重帧 | 32 位 hex，**唯一要填进 App 的密钥** |
| `TOKEN` | GATT 登录 | 本 App 不用；填进去只会报"解密失败" |

### 3.3 也可以让 App 自己扫 MAC

设备页「扫描附近设备」→ 站上秤唤醒 → 列表里出现 `Xiaomi Scale S400 XXXX`（后 4 位是 MAC 末 4 位）→ 点一下自动填 MAC。

**扫描只能拿到 MAC 和广播名，拿不到 bind key**——key 永远只能从账号侧取。所以顺序是：先 extractor 拿 key，再扫描或手填 MAC。

---

## 4. 填进 App

### 4.1 手动绑定（换秤 / 秤被重置时）

1. 设备页 →「秤的 MAC」填 `XX:XX:XX:XX:XX:XX`（17 位、冒号分隔，大小写都行）；
2. 「bind key」填 32 位 hex（输入框是密码型，已保存时留空表示不修改）；
3. 点「保存配置」→ 立刻开始扫描 → 站上秤验证。

> 「bind key 已保存，留空表示不修改」是**保护设计**：页面永远不回显 key，`state()` 只回 `bindKeySet: true/false`。
> 所以想换 key 必须重新输入完整的 32 位，不能靠清空。

### 4.2 构建期预绑定（自用 APK）

`build.sh` 默认从 env 文件读三个值，注入 `BuildConfig`，APK 首次启动自动写进配置：

```sh
# 默认读仓库外的 ../scale/.env（不存在就自动退化为不预绑定）
S400_MAC=XX:XX:XX:XX:XX:XX
S400_BLE_KEY=<32 hex>
S400_NAME=Xiaomi Scale S400 XXXX     # 可选，不写会按 MAC 末 4 位自动拼
```

```sh
bash build.sh                                        # 预绑定这台秤
bash build.sh --no-preset                            # 不预绑定，首启手动填
WEIGHTLOG_PRESET_ENV=/path/to/my.env bash build.sh   # 指定别的 env 文件
```

| 约束 | 说明 |
|---|---|
| 只进 `build/` 生成物和 APK | 源码里**没有**任何 key；`build/` 已 gitignore |
| 构建日志只打 MAC | 不打 key；`--install` 也不会把 key 打出来 |
| 校验不过就退化 | MAC 格式或 key 不是 32 hex → 打印 WARN、本次不预绑定，构建继续 |
| env 文件不进仓库 | 权限 600 + gitignore，当密码管理 |

> **预绑定包只适合自用 / 自己人安装。** 拿到 APK 就等于拿到 key（`classes.dex` 里是明文字符串），
> 要发给别人必须用 `--no-preset` 重新构建。

### 4.3 预绑定落盘规则

首启时 `Config.maybeApplyPreset()` 按下面四条判断，**不会覆盖你改过的设备**：

| 当前设备配置 | 行为 |
|---|---|
| 没有设备 / 有 MAC 但缺 key | 写入预绑定的 MAC + key + 名字 |
| 就是预绑定那台、只是缺 key | 补 key |
| 已经配了别的 MAC | 不动（尊重你改过的配置） |
| 用户点过「解除绑定」 | 不动（写了 `preset_off_v1` 抑制），直到点「恢复预绑定」 |

---

## 5. 什么时候需要重新绑定

| 触发 | 现象 | 处理 |
|---|---|---|
| 米家 App 里解绑 / 重新绑定秤 | 持续「广播解密失败」；地址兜底也失败 | 重跑 extractor 取新 `BLE KEY` → 设备页重填 |
| 秤恢复出厂 / 长按重置 | 同上，MAC 也可能变 | 先在米家里重新绑定，再重扫 MAC |
| 换了米家账号 | 旧 key 全部失效 | 用新账号重新取 |
| 固件大版本更新 | 偶发帧对象从 `0x6E16` 变 `0x4E16` | 先确认不是 key 问题，再提 issue |

判定顺序（别一上来就怪 key）：

```text
站上秤，App 有实时数字？
├─ 有            → key 正确，问题在别处（重复记录 / 时间 / 档案）
└─ 没有
   ├─ 设备页能看到广播名 / RSSI？  → BLE 链路正常，问题在 key
   │                              → 检查是否误填 24 位 TOKEN；重跑 extractor
   └─ 连广播名都没有              → 蓝牙 / 权限 / 距离；不是 key 的问题
```

### 重新绑定的完整顺序

1. 米家 App → 该秤 → **解绑**（只从 App 里删配置不会让旧 key 变安全）；
2. 重新绑定 / 配网，等云端下发新凭据；
3. 用 token extractor 重跑（不带 `-s`），拿新的 `BLE KEY`；
4. App 设备页重填 key（MAC 通常不变，变了就一起改）或重新构建预绑定包；
5. 站上秤验证；已经导出过的历史数据不受影响。

> 轮换之后，**之前被抓到的历史广播仍可被旧 key 解密**——这是已经发生的泄露，撤不回来。
> 所以 key 一旦怀疑外泄（截图、发群、仓库误传、APK 外发），第 1 步是必须做的，不是可选项。

---

## 6. 地址兜底：MAC 过期也能自己找回来

预绑定 / 恢复旧备份 / 手动填错 MAC 时，App 不会一直卡在"等待秤"：

```text
绑定扫描超时（空闲 120 s / 称重层 45 s）
  或 连续 3 次解密失败
        ↓
切 RESCUE：重新广播发现，对每个候选包用当前 bind key 试解密
        ↓
只有 MIC 校验通过才认作同一台秤 → 自动把新 MAC 写回配置
        ↓
页面提示「秤的地址变了，已自动纠正」
```

| 要点 | 说明 |
|---|---|
| 只认"解得开"的 | 解不开就不会被当成这台秤 |
| 有窗口和冷却 | 20 秒窗口、120 秒冷却，避免一直扫 |
| key 错了修不了 | 兜底只修正**地址**，不修正密钥 |

---

## 7. 安全边界

| 资产 | 敏感度 | 规则 |
|---|---|---|
| `BLE KEY`（16B） | 高：能解这台秤的**全部**广播，含历史回放 | 不进源码、不进 Git、不进日志、不进导出文件、不进截图 |
| `MAC` | 低-中：空口明文，可被旁观 | 可以展示，公开仓库里建议打码 |
| `TOKEN`（12B，GATT） | 高：能登录实时通道 | 本 App 不存、不用 |
| 预绑定 APK | **等同 key** | 只给自己人；外发必须 `--no-preset` 重建 |
| 导出 CSV / JSON | 个人健康数据 | 不含 key；分享前自己决定 |

具体到本 App：

- 权限清单里**没有 `INTERNET`**，数据不出本机；key 只存在 App 私有 `SharedPreferences`；
- key 只会以 `bindKeySet` / `tokenSet` 布尔值出现在页面和桥接口里，不会回显明文；
- 残留风险：key 是明文静态存储（未用 Keystore / `EncryptedSharedPreferences`），root 或能读私有目录的工具仍可拿到；
  「显示」按钮露出 key 时没有 `FLAG_SECURE`，可能被截图 / 录屏。个人自用可接受，不适合多用户场景。

---

## 8. 排障

| 现象 | 原因 | 处理 |
|---|---|---|
| extractor 输出里没有这台秤 | 只查了 `cn`；账号不对；秤没绑在这个账号 | 去掉 `-s` 遍历；确认是主账号 |
| `No homelist` / 空结果 | 地区选错 | 不带 `-s` 重跑 |
| 扫码后页面一直转 | 打开的不是本机 `127.0.0.1:31415` | 用电脑上的本地地址 |
| 填了 key 仍然「解密失败」 | 用了 24 位 `TOKEN`；或 key 已轮换 | 核对长度 32；重跑 extractor |
| 保存后一直「正在等待秤」 | App 不在前台；蓝牙没开；距离远；Android 11 及以下定位开关没开 | 前台 + 蓝牙 + 靠近；开定位开关 |
| 扫不到任何广播 | 秤在休眠（广播很弱）；系统蓝牙权限被拒 | 站上秤唤醒；设备页看权限提示 |
| 绑定成功但没数据 | MAC 填错 | 核对 MAC 与广播名末 4 位 |
| 换了秤一直没数据 | MAC 过期，兜底还在窗口内 | 等提示「地址已自动纠正」，或手动点「恢复预绑定」 |
| 解绑后回不去了 | `preset_off_v1` 抑制了自动预绑定 | 设备页点「恢复预绑定」 |
| 构建后仍显示未绑定 | env 路径不对 / key 不是 32 hex | 看构建日志的 `[WARN]`；用 `WEIGHTLOG_PRESET_ENV` 指定 |

---

## 9. 参考

- [S400-PROTOCOL.md](S400-PROTOCOL.md)：广播帧、AES-CCM、GATT 登录（配对）握手
- [S400-PRIVACY.md](S400-PRIVACY.md)：威胁模型、key 泄露处置、数据地图
- [Xiaomi-cloud-tokens-extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor)：取 `BLE KEY` / `TOKEN` 的社区工具
- [Bluetooth-Devices/xiaomi-ble](https://github.com/Bluetooth-Devices/xiaomi-ble)：MiBeacon 解析与 AES-CCM
- [MIoT S400 spec](https://home.miot-spec.com/spec?type=urn:miot-spec-v2:device:scale:0000A07D:yunmai-ms104:1)
- [openScale S400 issue](https://github.com/oliexdev/openScale/issues/1188)
