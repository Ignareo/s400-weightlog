# S400 协议：广播解密与 GATT 登录（配对）

> 对象：小米体脂秤 S400（`MJTZC01YM` / `yunmai.scales.ms104`），实测固件 `2.1.1_0006`。
> 本文讲**协议层**：广播怎么加密、GATT 通道怎么登录（也就是协议意义上的「配对」）。
> 怎么拿到并填进密钥见 [S400-BINDING.md](S400-BINDING.md)；隐私与威胁模型见 [S400-PRIVACY.md](S400-PRIVACY.md)。
> **文中不写真实 MAC / key / 体重，统一用 `XX:XX:XX:XX:XX:XX`、`<32 hex>`、`<24 hex>`、`<kg>` 占位。**

---

## 0. 两条通路，两种"配对"

| 通路 | 数据 | 延迟 | 需要什么 | 本 App |
|---|---|---|---|---|
| BLE 广播（MiBeacon v5） | 每次称重末尾的 2 个结果包：体重、双阻抗、心率、profile | 测量完成后约 1 秒 | 16 字节 bind key | ✅ **唯一实现的通路** |
| GATT（Mi Home v2 登录） | 实时体重（约 0.3 秒一帧，带 stable 标志）；final 双阻抗 / profile / 时间 | 站上秤就开始 | bind key + 12 字节 token | ❌ 未实现，本文件只作协议参考 |

两条通路互斥：**GATT 连接期间秤通常不再广播**。所以"配对"在这台秤上其实有两个不同含义：

- **绑定**（App 侧、广播侧）：把 MAC + bind key 填进 App，让它能解密广播。BLE 层**不配对**、没有 PIN、不需要在系统设置里点"配对设备"。
- **配对/登录**（GATT 侧）：和秤建立连接后做一次 challenge-response 登录，成功才拿到实时通道。这一步会占用秤的唯一连接位。

本 App 只做前者；后者写在这里，是为了让第三方实现（电视端、Home Assistant、脚本）有据可查。

---

## 1. 广播（MiBeacon v5）

### 1.1 广告

- Service UUID：`0000fe95-0000-1000-8000-00805f9b34fb`；
- Service data 就是 MiBeacon 帧本身（第一字节是 frame control，**没有** `0x95 0xFE` 前缀）；
- product id：`0x3BD5` / `0x30D9`；
- 广播名形如 `Xiaomi Scale S400 XXXX`（末 4 位是 MAC 后 4 位）。

帧头：

```text
frctrl    = data[0] | data[1] << 8
version   = frctrl >> 12              // S400 = 5
encrypted = (frctrl >> 3) & 1
object    = (frctrl >> 6) & 1         // 0 = 心跳/空闲，1 = 结果包
macIncl   = (frctrl >> 4) & 1
pid       = data[2] | data[3] << 8
counter   = data[4]
mac       = macIncl ? reverse(data[5:11]) : 扫描结果里的地址
i         = 5 + (macIncl ? 6 : 0) + capability 字节
```

### 1.2 心跳包与结果包

- **心跳**：`object=0`、`enc=0`，没有数据；空闲时约每 0.3–0.6 秒一包，用于复位会话。
- **结果包**：`object=1`、`enc=1`，一次称重通常只有 2 个不同的包：

```text
#1  体重 + 低频阻抗 + 心率，stabilized=false
#2  体重 + 低频阻抗 + 高频阻抗 + 心率，stabilized=true
```

两包相差约 1 秒，所以要"实时"显示只能靠 GATT；广播版最多比称重结束早约 1 秒。

### 1.3 解密

```text
nonce = MAC(6 字节) + data[2:5] + data[-7:-4]
aad   = 0x11
tag   = 4 字节
ct    = data[i:-7] + data[-4:]
plain = AES-CCM-decrypt(bindKey, nonce, aad, ct, tagLen=4)
```

- `bindKey` 来自米家云端下发的 `BLE KEY`（32 位 hex / 16 字节）；
- 不要依赖 `AES/CCM/NoPadding`：Android 6 各厂商 provider 行为不一致，用 `AES/ECB/NoPadding` 手写 CCM 更稳；
- MIC 校验失败 = key 错或包损坏，**不要落库**；
- **有 MAC 帧**取帧内 MAC，否则用扫描地址；两者不一致时必须丢弃（防止伪造）。

### 1.4 `0x6E16` / `0x4E16` 对象

```text
profile_id = data[0]
v          = data[1:5] LE32
mass       = (v & 0x7FF) / 10              kg
hr         = ((v >> 11) & 0x7F) + 50       bpm（0 或 127 表示无）
impedance  = (v >> 18) / 10                Ω
```

- 有 mass 的包 = 低频阻抗（约 50 kHz，数值较大）；
- 收尾包（mass=0、hr=0）= 高频阻抗（约 250 kHz，数值较小）+ stabilized；
- 固件升级后偶发对象号在 `0x6E16` / `0x4E16` 之间变化，两个都要认；
- 心率只在广播 final 包里有，GATT final 没有（见 §4.5）。

---

## 2. GATT 服务与特征

连接（**不需要先在系统里配对**）：

```text
SERVICE 00008000-0065-6c62-2e74-6f696d2e696d   # 自定义（MiOT / Yunmai）
  CHAR 00008001-0065-6c62-2e74-6f696d2e696d   write-without-response
  CHAR 00008002-0065-6c62-2e74-6f696d2e696d   notify

SERVICE 0000fe95-0000-1000-8000-00805f9b34fb   # Xiaomi MiBeacon / auth
  CHAR 00000004  read                           # 固件版本（ASCII，如 2.1.1_0006）
  CHAR 00000005  read/notify
  CHAR 00000010  write-without-response/notify  # UPNP：命令/结果
  CHAR 00000017  write/notify                   # AVCTP
  CHAR 00000018  write-without-response/notify
  CHAR 00000019  write-without-response/notify  # AVDTP：登录/多帧
  CHAR 0000001a  write-without-response/notify  # VEND1A：登录后 app→device
  CHAR 0000001b  write-without-response/notify  # CMTP：登录后 device→app
  CHAR 0000001c  write-without-response/notify
```

- 登录前先给 `0x10 / 0x17 / 0x19 / 0x1a / 0x1b / 0x1c` 开 notify（CCCD `0x2902`）；
- 16 位 UUID 补全为 `0000XXXX-0000-1000-8000-00805f9b34fb`；
- 秤同一时间只接受**一个** GATT 连接；手机米家或别的 BLE App 连着时，这里会 `connect_failed`。

---

## 3. Mi Home v2 登录（GATT 配对握手）

需要两个秘密：

- `bindKey`（16 字节，广播解密用，**登录不直接用它**）；
- `token`（**12 字节 / 24 位 hex**，登录用；来自米家云端下发的 `TOKEN`，和 `BLE KEY` 不是一回事）。

token 在秤重新绑定 / 重置 / 换账号后轮换；轮换后 HMAC 校验失败，必须重新提取。

### 3.1 命令 / 帧常量

```text
UPNP  (0x10) 命令（4 字节 LE）：
  CMD_GET_INFO   = a2 00 00 00
  CMD_SET_KEY    = 15 00 00 00
  CMD_LOGIN      = 24 00 00 00
  CMD_AUTH       = 13 00 00 00
  CFM_LOGIN_OK   = 21 00 00 00
  CFM_LOGIN_ERR  = 23 00 00 00

AVDTP (0x19)：
  CMD_SEND_KEY   = 00 00 00 0b 01 00
  CMD_SEND_INFO  = 00 00 00 0a 02 00
  RCV_RDY        = 00 00 01 01
  RCV_OK         = 00 00 01 00
```

### 3.2 登录步骤

```text
 1. 写 UPNP  CMD_LOGIN
 2. 写 AVDTP CMD_SEND_KEY
 3. 等 AVDTP RCV_RDY
 4. writeParcel(AVDTP, app_rand = 16 字节随机)        # 每片 18 字节，前缀 [seq LE16]
 5. 等 AVDTP RCV_OK
 6. 收 dev_rand 多帧（16 字节）
 7. 收 remote_info 多帧
 8. HKDF-SHA256(token, salt = app_rand + dev_rand, info = "mible-login-info", 64 字节)
      dev_key = 0:16, app_key = 16:32, dev_iv = 32:36, app_iv = 36:40
 9. 校验 remote_info == HMAC-SHA256(dev_key, dev_rand + app_rand)
10. 写 AVDTP CMD_SEND_INFO
11. 等 RCV_RDY
12. writeParcel(AVDTP, our_info = HMAC-SHA256(app_key, app_rand + dev_rand))
13. 等 RCV_OK
14. 等 UPNP CFM_LOGIN_OK
```

多帧接收格式：

```text
首帧： 00 00 00 SS LL 00        # expected = data[4] | data[5] << 8
回 RCV_RDY
收 expected 帧：NN 00 <payload>  # 每帧前 2 字节是序号
回 RCV_OK
拼接 payload
```

### 3.3 加密命令通道（CMTP `0x1b`）

```text
头：  00 00 00 SS LL 00          # expected = data[4]；回 RCV_RDY
数据：NN 00 <payload>
完整后：
  iter  = plaintext[0:2]         # LE16，逐帧递增
  ct    = plaintext[2:]          # 含 4 字节 tag
  nonce = dev_iv(4) + 00 00 00 00 + iter(2) + 00 00
  plain = AES-CCM-decrypt(dev_key, nonce, AAD = None, tag = 4)
回 RCV_OK
```

### 3.4 解密后的 CSV 负载

明文里找 `0xA0`，其后是 ASCII CSV：

```text
live（2 字段）：   weight_x10, stable
                   例：653,0

final（32 字段）：
  0,0,profile,weight_x10,stable,2,unix_ts,0,...,imp_high_x10,imp_low_x10
  例：0,0,1,665,0,2,1792593503,0,0,...,4520,4070
```

- live 只有体重；
- final 最后两个字段分别 /10 得到阻抗；**较大的值是低频（约 50 kHz）**，较小的是高频（约 250 kHz）；
- final 没有可用心率字段（广播 final 才有）。

---

## 4. 两条通路对比

| 项 | 广播版 | GATT 称重模式 |
|---|---|---|
| 需要 token | 否 | 是（12 字节，会轮换） |
| 实时体重 | 否（只有末尾 2 包） | 是（约 0.3 秒/帧） |
| 心率 | 广播 final 有 | GATT final 没有（null） |
| 对秤电池 | 无额外影响 | 每次称重多约 1–1.5 分钟连接 |
| 对秤显示 | 不影响 | 未登录的裸连接可能让体脂显示 `----`；登录后按社区反馈正常 |
| 连接数 | 广播可多端同时收 | 秤同时只接受一个 GATT 连接 |
| 失败回退 | — | 回到广播扫描 |

---

## 5. 参考实现与工具

本仓库只实现广播；GATT 实时通道的第三方实现：

- [nokistin/xiaomi-s400-live](https://github.com/nokistin/xiaomi-s400-live)：Mi Home v2 登录 + CMTP 的 Python 实现
- [kbpk/xiaomi-s400-homeassistant](https://github.com/kbpk/xiaomi-s400-homeassistant)：本地配对 / auth v1 研究方向

协议字段来源：

- [Bluetooth-Devices/xiaomi-ble](https://github.com/Bluetooth-Devices/xiaomi-ble)：MiBeacon 解析与 AES-CCM
- [ESPHome xiaomi_body_scale](https://esphome.io/components/sensor/xiaomi_body_scale/)：`0x6E16` / `0x4E16` 字段与产品 ID
- [MIoT S400 spec](https://home.miot-spec.com/spec?type=urn:miot-spec-v2:device:scale:0000A07D:yunmai-ms104:1)
- [openScale S400 issue](https://github.com/oliexdev/openScale/issues/1188)
- [Gadgetbridge S400 讨论](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/4501)：广播 / 协议 / 干扰实测

---

## 6. 注意

- `bindKey`、`token` 都是密钥：不提交、不贴日志、不发群；本 App 只存 bind key，token 不存。
- 协议是逆向结果，固件更新可能失效；实测固件 `2.1.1_0006`。
- 秤同时只接受一个 BLE 连接：联调时退出手机米家 / 其他 BLE App。
- 未登录的裸 GATT 连接可能让秤体脂显示 `----`；必须登录后再称。
- 广播帧无时间戳、无防重放，服务端按"收到时间"入库即可被重放污染，详见 [S400-PRIVACY.md](S400-PRIVACY.md)。
