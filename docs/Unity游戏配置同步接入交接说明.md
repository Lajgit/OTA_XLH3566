# Unity 游戏配置同步接入交接说明

## 1. 文档目的

本文用于 Unity 游戏应用 `com.zeda` 接入 OTA 应用 `com.zeda.ota` 的游戏配置同步能力。

本协议只用于游戏配置同步，不用于以下流程：

- 游戏 APK 升级；
- 球盘 BIN 固件升级；
- OTA 应用自升级；
- 升级确认、升级审批或升级通知。

以上升级流程仍由 OTA 应用原有逻辑负责，Unity 不需要也不应该通过本文协议参与升级流程。

## 2. 角色边界

### 2.1 OTA 应用

包名：

```text
com.zeda.ota
```

职责：

1. 订阅后端 MQTT `command/config` 固定主题。
2. 校验游戏配置指令、Schema、版本和 SHA-256 哈希。
3. 可靠保存 pending 状态。
4. 向后端上报 `command-result` 的 `ack`。
5. 通过显式广播把配置下发给 Unity。
6. 接收 Unity 返回的执行结果。
7. 保存已生效配置 `applied_record`。
8. 上报 `command-result` 的 `success` 或 `failed`。
9. 上报 `report/game-config` 当前实际游戏配置。
10. OTA 重启、MQTT 重连、Unity ready 后恢复 pending 或 applied 配置。

### 2.2 Unity 游戏应用

包名：

```text
com.zeda
```

职责：

1. 声明并使用签名权限。
2. 接收 OTA 的 `apply_game_config` 请求。
3. 校验请求基础结构。
4. 应用灯光、声音、难度等游戏配置。
5. 以原子方式保存本地 JSON 配置。
6. 重新读取实际配置。
7. 返回 `success` 或 `failed` 给 OTA。
8. 启动后主动发送 `game_config_ready` 给 OTA。
9. 对重复 `messageId` 做幂等处理。

Unity 不连接 MQTT。

## 3. Android 签名权限

OTA 侧已经定义权限：

```xml
<permission
    android:name="com.zeda.permission.GAME_CONFIG"
    android:protectionLevel="signature" />
```

Unity 侧需要在 `AndroidManifest.xml` 声明：

```xml
<uses-permission android:name="com.zeda.permission.GAME_CONFIG" />
```

OTA 和 Unity 必须使用相同签名，否则广播将无法互通。

## 4. 广播 Action

### 4.1 OTA 发送给 Unity

Action：

```text
com.zeda.ota.action.GAME_CONFIG_REQUEST
```

目标包：

```text
com.zeda
```

Extra：

```text
payload
```

发送权限：

```text
com.zeda.permission.GAME_CONFIG
```

### 4.2 Unity 发送给 OTA

Action：

```text
com.zeda.action.GAME_CONFIG_EVENT
```

目标包：

```text
com.zeda.ota
```

Extra：

```text
payload
```

发送权限：

```text
com.zeda.permission.GAME_CONFIG
```

## 5. Unity 侧 Manifest 示例

Unity 需要增加一个 Android 原生 BroadcastReceiver，用于接收 OTA 请求。

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="com.zeda.permission.GAME_CONFIG" />

    <application>

        <receiver
            android:name="com.zeda.gameconfig.GameConfigRequestReceiver"
            android:enabled="true"
            android:exported="true"
            android:permission="com.zeda.permission.GAME_CONFIG">

            <intent-filter>
                <action android:name="com.zeda.ota.action.GAME_CONFIG_REQUEST" />
            </intent-filter>

        </receiver>

    </application>
</manifest>
```

Receiver 类名可以由 Unity 项目自行决定，但必须保证 Action、权限和 exported 配置一致。

## 6. payload 通用结构

所有广播都通过 Extra `payload` 携带 JSON 字符串。

通用字段：

```json
{
  "protocolVersion": 1,
  "operation": "apply_game_config",
  "requestId": "a1cbe541622e4ac99453435c5b726144",
  "messageId": "5e20c7f6e6354a29acb5d44d01736b8f",
  "timestamp": 1784200000000,
  "data": {}
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| protocolVersion | int | 是 | 固定为 `1` |
| operation | string | 是 | 操作类型 |
| requestId | string | 是 | 单次 IPC 调用 ID |
| messageId | string | 视场景 | 后端 MQTT 指令 ID |
| timestamp | long | 是 | 毫秒时间戳 |
| status | string | 返回时必填 | `success` / `failed` |
| resultCode | string | 返回时必填 | 结果码 |
| resultMessage | string | 返回时必填 | 结果描述 |
| data | object | 视场景 | 业务数据 |

### 6.1 requestId 与 messageId 区别

`messageId` 是后端 MQTT 指令的幂等 ID。

`requestId` 是 OTA 和 Unity 之间某一次广播调用的 ID。

同一个 `messageId` 在 OTA 重启或恢复时，可能会生成新的 `requestId` 重新下发给 Unity。Unity 返回结果时必须原样带回本次收到的 `requestId`。

## 7. operation 列表

| operation | 方向 | 说明 |
| --- | --- | --- |
| apply_game_config | OTA → Unity / Unity → OTA | OTA 下发配置，Unity 返回执行结果 |
| query_game_config | OTA → Unity / Unity → OTA | OTA 查询 Unity 当前配置，预留 |
| game_config_ready | Unity → OTA | Unity 配置模块启动就绪 |

当前必须实现：

- `apply_game_config`
- `game_config_ready`

`query_game_config` 为恢复和扩展预留，建议 Unity 同步实现。

## 8. OTA 下发 apply_game_config

示例：

```json
{
  "protocolVersion": 1,
  "operation": "apply_game_config",
  "requestId": "a1cbe541622e4ac99453435c5b726144",
  "messageId": "5e20c7f6e6354a29acb5d44d01736b8f",
  "timestamp": 1784200000000,
  "data": {
    "gameCode": "pinball-x",
    "schemaVersion": 1,
    "configVersion": 12,
    "configHash": "329efc9e24be1f153f99528c9d70e1461c3c702e7ba72c144daed56de71d36fd",
    "config": {
      "lighting": {
        "playfieldBrightnessPercent": 90,
        "stripBrightnessPercent": 80,
        "panelBrightnessPercent": 70
      },
      "sound": {
        "musicVolumePercent": 60,
        "effectVolumePercent": 40
      },
      "difficulty": {
        "mode": "EASY"
      }
    }
  }
}
```

Unity 收到后必须：

1. 校验 `protocolVersion == 1`。
2. 校验 `operation == apply_game_config`。
3. 保存本次 `requestId`。
4. 根据 `messageId` 做幂等判断。
5. 校验 `gameCode == pinball-x`。
6. 校验 `schemaVersion <= 1`。
7. 校验 `configVersion > 0`。
8. 校验 `configHash` 为 64 位小写十六进制。
9. 按本文规则重新计算 `config` 的 SHA-256。
10. 校验计算结果等于 `configHash`。
11. 应用配置。
12. 保存配置 JSON。
13. 重新读取实际配置。
14. 返回执行结果。

## 9. Unity 返回 apply_game_config 成功

```json
{
  "protocolVersion": 1,
  "operation": "apply_game_config",
  "requestId": "a1cbe541622e4ac99453435c5b726144",
  "messageId": "5e20c7f6e6354a29acb5d44d01736b8f",
  "status": "success",
  "resultCode": "0",
  "resultMessage": "游戏配置已生效",
  "timestamp": 1784200005000,
  "data": {
    "gameCode": "pinball-x",
    "schemaVersion": 1,
    "configVersion": 12,
    "configHash": "329efc9e24be1f153f99528c9d70e1461c3c702e7ba72c144daed56de71d36fd",
    "config": {
      "lighting": {
        "playfieldBrightnessPercent": 90,
        "stripBrightnessPercent": 80,
        "panelBrightnessPercent": 70
      },
      "sound": {
        "musicVolumePercent": 60,
        "effectVolumePercent": 40
      },
      "difficulty": {
        "mode": "EASY"
      }
    }
  }
}
```

成功返回要求：

1. `requestId` 必须等于 OTA 本次下发的 requestId。
2. `messageId` 必须等于 OTA 本次下发的 messageId。
3. `status` 固定为 `success`。
4. `resultCode` 固定为 `0`。
5. `data.config` 必须是 Unity 实际保存和生效后的完整配置。
6. OTA 会重新计算 `data.config` 的哈希，不信任 Unity 直接返回的 `configHash`。

## 10. Unity 返回 apply_game_config 失败

```json
{
  "protocolVersion": 1,
  "operation": "apply_game_config",
  "requestId": "a1cbe541622e4ac99453435c5b726144",
  "messageId": "5e20c7f6e6354a29acb5d44d01736b8f",
  "status": "failed",
  "resultCode": "APPLY_FAILED",
  "resultMessage": "游戏难度模块尚未初始化",
  "timestamp": 1784200005000,
  "data": {}
}
```

失败返回要求：

1. `requestId` 必须等于 OTA 本次下发的 requestId。
2. `status` 使用 `failed`。
3. `resultCode` 使用本文约定错误码或 Unity 自定义错误码。
4. `resultMessage` 应该明确说明失败原因。

## 11. Unity 启动 ready 事件

Unity 游戏启动并完成配置模块初始化后，必须主动向 OTA 发送：

```json
{
  "protocolVersion": 1,
  "operation": "game_config_ready",
  "requestId": "",
  "messageId": "",
  "status": "success",
  "resultCode": "0",
  "resultMessage": "游戏配置模块已就绪",
  "timestamp": 1784200005000,
  "data": {}
}
```

OTA 收到后会：

```text
如果存在 pending_record
→ 重新下发 pending 配置

如果没有 pending_record，但存在 applied_record
→ 重新下发最后一次成功配置
```

此事件用于处理 Unity 数据被清除后的配置恢复。当前游戏 APK 升级流程仍会清理 `com.zeda` 应用数据，因此 Unity 必须实现 ready 事件。

## 12. 配置 Schema v1

顶层 `config` 只允许以下字段：

```text
config
├── lighting
├── sound
└── difficulty
```

禁止额外字段。

### 12.1 lighting

```json
{
  "lighting": {
    "playfieldBrightnessPercent": 90,
    "stripBrightnessPercent": 80,
    "panelBrightnessPercent": 70
  }
}
```

字段：

| 字段 | 类型 | 范围 |
| --- | --- | --- |
| playfieldBrightnessPercent | int | 0～100 |
| stripBrightnessPercent | int | 0～100 |
| panelBrightnessPercent | int | 0～100 |

要求：

- 三个字段都必填；
- 必须是整数；
- 不允许小数；
- 不允许字符串数字；
- 不允许额外字段。

### 12.2 sound

```json
{
  "sound": {
    "musicVolumePercent": 60,
    "effectVolumePercent": 40
  }
}
```

字段：

| 字段 | 类型 | 范围 |
| --- | --- | --- |
| musicVolumePercent | int | 0～100 |
| effectVolumePercent | int | 0～100 |

要求同 lighting。

### 12.3 difficulty

支持模式：

```text
SMART
EASY
MEDIUM
HARD
```

#### EASY / MEDIUM / HARD

```json
{
  "difficulty": {
    "mode": "EASY"
  }
}
```

非 SMART 模式禁止出现 `smartModeSettings`。

#### SMART

```json
{
  "difficulty": {
    "mode": "SMART",
    "smartModeSettings": {
      "profitRatePercent": 35,
      "profitCycleDays": 7,
      "cardExchangeAmountCent": 500
    }
  }
}
```

字段：

| 字段 | 类型 | 范围 |
| --- | --- | --- |
| profitRatePercent | int | 0～100 |
| profitCycleDays | int | > 0 |
| cardExchangeAmountCent | int | > 0 |

SMART 模式必须包含 `smartModeSettings`。

## 13. 配置大小和深度限制

Unity 应遵守与 OTA 一致的限制：

```text
config 规范化 UTF-8 长度 <= 65536 字节
JSON 最大嵌套深度 <= 16
```

## 14. 本地 JSON 保存路径

Unity 建议使用：

```csharp
Path.Combine(
    Application.persistentDataPath,
    "game_config",
    "game_config.json"
)
```

Android 通常对应：

```text
/storage/emulated/0/Android/data/com.zeda/files/game_config/game_config.json
```

最终以 Unity 运行时 `Application.persistentDataPath` 为准。

## 15. Unity 原子保存要求

建议文件：

```text
game_config.json
game_config.json.tmp
game_config.json.bak
```

保存顺序：

```text
1. 校验并应用配置到内存
2. 生成完整实际配置对象
3. 写入 game_config.json.tmp
4. Flush 并关闭文件
5. 如果存在旧 game_config.json，先复制或重命名为 game_config.json.bak
6. 将 tmp 替换为正式 game_config.json
7. 重新读取正式 JSON
8. 返回重新读取后的实际配置
```

如果应用过程中任意模块失败，不得更新正式 JSON，也不得更新本地 `configVersion`。

## 16. Unity 幂等要求

Unity 至少需要保存最近一次成功应用的：

```text
messageId
configVersion
configHash
config
```

处理规则：

```text
同 messageId 重复收到
→ 如果上次已成功且配置一致，直接返回 success
→ 如果上次已失败，可返回相同 failed 或重新执行，由 Unity 自行决定

同 configVersion + 同 configHash
→ 视为同一配置，可幂等 success

同 configVersion + 不同 configHash
→ 返回 failed / VERSION_CONFLICT
```

OTA 侧也会做幂等判断，Unity 侧仍建议实现，避免应用内重复执行灯光、音量、难度切换。

## 17. JSON 规范化和 SHA-256

哈希只计算 `data.config` 对象本身，不包含：

- protocolVersion
- operation
- requestId
- messageId
- timestamp
- configVersion
- configHash

### 17.1 对象

对象 key 按 Unicode code point 升序排序，递归处理子对象。

### 17.2 数组

数组顺序保持不变。

### 17.3 数字

数字使用任意精度十进制处理：

```text
1          -> 1
1.0        -> 1
1e0        -> 1
-0         -> 0
1.2300     -> 1.23
0.00000100 -> 0.000001
```

要求：

- 不使用科学计数法；
- 去除小数尾随零；
- 去除无意义小数点；
- 正零和负零统一为 `0`；
- 不允许 NaN 和 Infinity。

### 17.4 字符串

- 使用双引号；
- `"` 转义为 `\"`；
- `\` 转义为 `\\`；
- 换行、回车、制表符按 JSON 标准转义；
- 其他控制字符使用小写 `\u00xx`；
- `/` 不转义；
- 中文、emoji 等 Unicode 字符直接使用 UTF-8；
- 不做 HTML 转义。

### 17.5 输出

最终 JSON：

- 无空格；
- 无换行；
- 无缩进；
- UTF-8 编码；
- SHA-256；
- 64 位小写十六进制。

完整哈希规范和 8 组测试向量见：

```text
docs/游戏配置哈希规范与测试向量.md
app/src/test/resources/game_config_hash_vectors.json
```

## 18. 标准测试向量摘要

### V01

```json
{}
```

SHA-256：

```text
44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a
```

### V02

规范化：

```json
{"a":1,"b":2}
```

SHA-256：

```text
43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777
```

### V03

规范化：

```json
{"a":1,"b":1,"c":0,"d":1.23,"e":0.000001}
```

SHA-256：

```text
b5f8b73ca6050d4f95dfc7c0cab0034401261955b4a11945ccbd133f7dccd0c3
```

### V04

规范化：

```json
{"a":{"x":null,"y":false},"z":[3,{"a":1,"b":2},1]}
```

SHA-256：

```text
0e4b68196889e9c5b45eedc202f906599cec476cba7a9405d09943da5ab2d801
```

### V05

规范化：

```json
{"emoji":"弹珠🎮","text":"引号\"和反斜杠\\以及换行\n","提示":"配置成功"}
```

SHA-256：

```text
39333ebd8b5cddb6ec9727b063bd99c3c3d128d26ccf328ae254ebbee2eb6840
```

### V06 EASY 完整配置

规范化：

```json
{"difficulty":{"mode":"EASY"},"lighting":{"panelBrightnessPercent":70,"playfieldBrightnessPercent":90,"stripBrightnessPercent":80},"sound":{"effectVolumePercent":40,"musicVolumePercent":60}}
```

SHA-256：

```text
329efc9e24be1f153f99528c9d70e1461c3c702e7ba72c144daed56de71d36fd
```

### V07 SMART 完整配置

规范化：

```json
{"difficulty":{"mode":"SMART","smartModeSettings":{"cardExchangeAmountCent":500,"profitCycleDays":7,"profitRatePercent":35}},"lighting":{"panelBrightnessPercent":75,"playfieldBrightnessPercent":100,"stripBrightnessPercent":85},"sound":{"effectVolumePercent":65,"musicVolumePercent":50}}
```

SHA-256：

```text
3ce79f4afcc1f824616335f957c3d3a41ebd3ff3549e298fce02a311852c0994
```

### V08

规范化：

```json
{"values":[1,1,0,1.23,{"a":1,"b":2}]}
```

SHA-256：

```text
efac387d4f52d1c4559fe3b1912ed5939fdc5d6d285ae91566125d1582b5706b
```

## 19. 错误码建议

| 错误码 | 说明 |
| --- | --- |
| CONFIG_INVALID | 配置结构、类型、范围或字段非法 |
| CONFIG_HASH_MISMATCH | configHash 与实际 config 计算结果不一致 |
| SCHEMA_UNSUPPORTED | schemaVersion 不支持 |
| VERSION_STALE | configVersion 低于当前已生效版本 |
| VERSION_CONFLICT | 同 configVersion 对应不同 configHash |
| APPLY_FAILED | Unity 应用配置失败 |

Unity 可以扩展更细错误码，但建议优先使用以上错误码，方便后端统一展示。

## 20. OTA 上报给后端的 command-result

OTA 会在 `command/config` 处理过程中上报：

```text
pxd/v1/device/{deviceNo}/report/command-result
```

### ACK

```json
{
  "deviceNo": "CE3992DCD6595FBC",
  "messageId": "5e20c7f6e6354a29acb5d44d01736b8f",
  "commandType": "sync_game_config",
  "configVersion": 12,
  "status": "ack",
  "resultCode": "0",
  "resultMessage": "游戏配置任务已可靠保存",
  "timestamp": 1784200000000
}
```

### Success

```json
{
  "deviceNo": "CE3992DCD6595FBC",
  "messageId": "5e20c7f6e6354a29acb5d44d01736b8f",
  "commandType": "sync_game_config",
  "configVersion": 12,
  "status": "success",
  "resultCode": "0",
  "resultMessage": "游戏配置已生效",
  "timestamp": 1784200005000
}
```

### Failed

```json
{
  "deviceNo": "CE3992DCD6595FBC",
  "messageId": "5e20c7f6e6354a29acb5d44d01736b8f",
  "commandType": "sync_game_config",
  "configVersion": 12,
  "status": "failed",
  "resultCode": "APPLY_FAILED",
  "resultMessage": "游戏难度模块尚未初始化",
  "timestamp": 1784200005000
}
```

## 21. OTA 上报给后端的 report/game-config

Topic：

```text
pxd/v1/device/{deviceNo}/report/game-config
```

Payload：

```json
{
  "deviceNo": "CE3992DCD6595FBC",
  "gameCode": "pinball-x",
  "gameVersion": "1.0.5",
  "maxSupportedSchemaVersion": 1,
  "schemaVersion": 1,
  "configVersion": 12,
  "configHash": "329efc9e24be1f153f99528c9d70e1461c3c702e7ba72c144daed56de71d36fd",
  "config": {
    "difficulty": {
      "mode": "EASY"
    },
    "lighting": {
      "panelBrightnessPercent": 70,
      "playfieldBrightnessPercent": 90,
      "stripBrightnessPercent": 80
    },
    "sound": {
      "effectVolumePercent": 40,
      "musicVolumePercent": 60
    }
  },
  "timestamp": 1784200005000
}
```

## 22. MQTT 参数

本阶段固定：

```text
订阅 QoS = 1
发布 QoS = 1
Retain = false
```

暂不使用激活接口返回的动态 `mqttTopics` 或动态 QoS。

## 23. 球盘 BIN 文件说明

球盘升级不通过本文广播协议通知 Unity。

如果 Unity 后续需要读取球盘固件文件信息，只读取 OTA 已保存的公共目录文件。

目录：

```text
/storage/emulated/0/Download/ball_upgrade/
```

固件文件命名：

```text
ball_V{version}.bin
```

示例：

```text
ball_V1.0.2.bin
```

版本描述文件：

```text
/storage/emulated/0/Download/ball_upgrade/version.json
```

示例：

```json
{
  "version": "1.0.2",
  "file": "ball_V1.0.2.bin",
  "md5": "固件MD5",
  "timestamp": 1784200000000
}
```

注意：

- 球盘 BIN 下载、MD5 校验和成功上报仍由 OTA 负责；
- Unity 不需要确认球盘升级；
- Unity 不需要通过广播监听球盘升级；
- 本文的 `apply_game_config` 不携带球盘文件路径。

## 24. Unity 建议代码结构

建议 Unity 项目中新增：

```text
Assets/Plugins/Android/GameConfigRequestReceiver.java
Assets/Scripts/GameConfig/GameConfigBridge.cs
Assets/Scripts/GameConfig/GameConfigService.cs
Assets/Scripts/GameConfig/GameConfigModels.cs
Assets/Scripts/GameConfig/GameConfigStorage.cs
Assets/Scripts/GameConfig/GameConfigCanonicalizer.cs
```

建议职责：

| 文件 | 职责 |
| --- | --- |
| GameConfigRequestReceiver.java | Android 广播入口，接收 OTA 请求 |
| GameConfigBridge.cs | C# 与 Android 原生层桥接 |
| GameConfigService.cs | 配置应用、幂等、结果返回 |
| GameConfigModels.cs | 配置数据模型 |
| GameConfigStorage.cs | 本地 JSON 原子保存和读取 |
| GameConfigCanonicalizer.cs | JSON 规范化和 SHA-256 |

## 25. Unity 返回事件发送示例 Java

```java
Intent intent = new Intent("com.zeda.action.GAME_CONFIG_EVENT");
intent.setPackage("com.zeda.ota");
intent.putExtra("payload", payloadJsonString);
context.sendBroadcast(intent, "com.zeda.permission.GAME_CONFIG");
```

必须使用目标包 `com.zeda.ota`，避免广播被其他应用接收。

## 26. 联调检查清单

### 26.1 正常配置下发

预期顺序：

```text
1. 后端下发 command/config
2. OTA 上报 command-result ack
3. OTA 广播 apply_game_config 给 Unity
4. Unity 应用并保存配置
5. Unity 广播 success 给 OTA
6. OTA 上报 command-result success
7. OTA 上报 report/game-config
```

### 26.2 哈希错误

预期：

```text
OTA 不广播给 Unity
OTA 上报 command-result failed / CONFIG_HASH_MISMATCH
```

### 26.3 Unity 应用失败

预期：

```text
Unity 返回 failed
OTA 清除 pending
OTA 上报 command-result failed
OTA 不更新 applied_record
```

### 26.4 OTA 重启恢复

预期：

```text
存在 pending_record
→ OTA 重新广播 pending 配置

存在 applied_record
→ OTA 重新上报 report/game-config
→ OTA 重新广播 applied 配置给 Unity
```

### 26.5 Unity 数据被清理

预期：

```text
Unity 启动后发送 game_config_ready
OTA 重新下发 applied 配置
Unity 重新保存 game_config.json
```

### 26.6 MQTT 离线后恢复

预期：

```text
OTA 保存 command_result_outbox / game_config_report_outbox
MQTT 恢复后自动补发
补发成功后清除 outbox
```

## 27. 禁止事项

Unity 不应：

- 直接修改 OTA 的 SharedPreferences；
- 连接 MQTT；
- 参与 APK 安装流程；
- 参与球盘 BIN 下载流程；
- 依赖外部存储中的游戏配置 JSON；
- 返回与实际保存内容不一致的 `config`；
- 忽略 `requestId`；
- 使用未规范化的 JSON 计算哈希。

OTA 不应：

- 直接写 Unity 的 `game_config.json`；
- 跳过 Unity 应用结果直接认为配置成功；
- 在 pending 未可靠保存前上报 ACK；
- 信任 Unity 返回的 `configHash` 而不重新计算。

## 28. 当前版本结论

Unity 只需要实现：

```text
1. 声明签名权限
2. 注册并接收 com.zeda.ota.action.GAME_CONFIG_REQUEST
3. 处理 apply_game_config
4. 启动后发送 game_config_ready
5. 保存 Application.persistentDataPath/game_config/game_config.json
6. 返回 com.zeda.action.GAME_CONFIG_EVENT
7. 通过 8 组哈希测试向量
```

完成以上内容后，即可与 OTA 的游戏配置同步能力联调。
