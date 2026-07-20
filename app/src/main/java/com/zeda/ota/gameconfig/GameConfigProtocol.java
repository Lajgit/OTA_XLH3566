package com.zeda.ota.gameconfig;

/**
 * 游戏配置同步协议常量。
 *
 * <p>本类只定义协议值，不包含 MQTT、持久化或广播执行逻辑。
 * 任何后端、OTA、Unity 三方都需要保持这些常量一致。</p>
 */
public final class GameConfigProtocol {

    private GameConfigProtocol() {
    }

    /** 当前 OTA 与 Unity 广播协议版本。 */
    public static final int PROTOCOL_VERSION = 1;

    /** OTA 当前最多支持的游戏配置 Schema 版本。 */
    public static final int MAX_SUPPORTED_SCHEMA_VERSION = 1;

    /** Unity 游戏应用包名。 */
    public static final String GAME_PACKAGE = "com.zeda";

    /** OTA 应用包名。 */
    public static final String OTA_PACKAGE = "com.zeda.ota";

    /** 当前项目固定游戏编码，后端下发必须一致。 */
    public static final String GAME_CODE = "pinball-x";

    /** 后端 command/config 中用于区分游戏配置同步的 commandType。 */
    public static final String COMMAND_TYPE_SYNC_GAME_CONFIG = "sync_game_config";

    /** OTA 与 Unity 互发广播时使用的签名级权限。 */
    public static final String PERMISSION_GAME_CONFIG =
            "com.zeda.permission.GAME_CONFIG";

    /** OTA 发给 Unity 的广播 Action。 */
    public static final String ACTION_GAME_CONFIG_REQUEST =
            "com.zeda.ota.action.GAME_CONFIG_REQUEST";

    /** Unity 返回给 OTA 的广播 Action。 */
    public static final String ACTION_GAME_CONFIG_EVENT =
            "com.zeda.action.GAME_CONFIG_EVENT";

    /** 广播中承载 JSON 字符串的 extra 名称。 */
    public static final String EXTRA_PAYLOAD = "payload";

    /** OTA 下发配置、Unity 返回执行结果时使用的 operation。 */
    public static final String OP_APPLY_GAME_CONFIG = "apply_game_config";

    /** 查询 Unity 当前配置的预留 operation。 */
    public static final String OP_QUERY_GAME_CONFIG = "query_game_config";

    /** Unity 启动完成后主动通知 OTA 恢复配置的 operation。 */
    public static final String OP_GAME_CONFIG_READY = "game_config_ready";

    /** command-result：任务已被 OTA 可靠保存。 */
    public static final String STATUS_ACK = "ack";

    /** command-result：任务执行成功。 */
    public static final String STATUS_SUCCESS = "success";

    /** command-result：任务执行失败。 */
    public static final String STATUS_FAILED = "failed";

    /** 通用成功结果码。 */
    public static final String RESULT_OK = "0";

    /** 配置结构、字段、类型、范围非法。 */
    public static final String ERROR_CONFIG_INVALID = "CONFIG_INVALID";

    /** 下发 configHash 与实际 data.config 计算值不一致。 */
    public static final String ERROR_CONFIG_HASH_MISMATCH = "CONFIG_HASH_MISMATCH";

    /** schemaVersion 超过 OTA 当前支持范围。 */
    public static final String ERROR_SCHEMA_UNSUPPORTED = "SCHEMA_UNSUPPORTED";

    /** 下发版本低于设备已生效版本。 */
    public static final String ERROR_VERSION_STALE = "VERSION_STALE";

    /** 同一 configVersion 对应不同 configHash。 */
    public static final String ERROR_VERSION_CONFLICT = "VERSION_CONFLICT";

    /** 配置下发、Unity 应用或持久化过程中失败。 */
    public static final String ERROR_APPLY_FAILED = "APPLY_FAILED";

    /** 规范化后的 config 最大 UTF-8 字节数：64KB。 */
    public static final int MAX_CONFIG_BYTES = 64 * 1024;

    /** JSON 最大嵌套深度，防止异常深层对象造成栈或解析风险。 */
    public static final int MAX_JSON_DEPTH = 16;

    /** 最近 messageId 幂等结果缓存数量。 */
    public static final int RECENT_MESSAGE_LIMIT = 100;
}
