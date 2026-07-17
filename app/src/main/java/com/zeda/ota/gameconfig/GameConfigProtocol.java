package com.zeda.ota.gameconfig;

/**
 * 游戏配置同步协议常量。
 *
 * <p>本类只定义协议值，不包含 MQTT、持久化或广播执行逻辑。</p>
 */
public final class GameConfigProtocol {

    private GameConfigProtocol() {
    }

    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_SUPPORTED_SCHEMA_VERSION = 1;

    public static final String GAME_PACKAGE = "com.zeda";
    public static final String OTA_PACKAGE = "com.zeda.ota";
    public static final String GAME_CODE = "pinball-x";
    public static final String COMMAND_TYPE_SYNC_GAME_CONFIG = "sync_game_config";

    public static final String PERMISSION_GAME_CONFIG =
            "com.zeda.permission.GAME_CONFIG";

    public static final String ACTION_GAME_CONFIG_REQUEST =
            "com.zeda.ota.action.GAME_CONFIG_REQUEST";
    public static final String ACTION_GAME_CONFIG_EVENT =
            "com.zeda.action.GAME_CONFIG_EVENT";

    public static final String EXTRA_PAYLOAD = "payload";

    public static final String OP_APPLY_GAME_CONFIG = "apply_game_config";
    public static final String OP_QUERY_GAME_CONFIG = "query_game_config";
    public static final String OP_GAME_CONFIG_READY = "game_config_ready";

    public static final String STATUS_ACK = "ack";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";

    public static final String RESULT_OK = "0";
    public static final String ERROR_CONFIG_INVALID = "CONFIG_INVALID";
    public static final String ERROR_CONFIG_HASH_MISMATCH = "CONFIG_HASH_MISMATCH";
    public static final String ERROR_SCHEMA_UNSUPPORTED = "SCHEMA_UNSUPPORTED";
    public static final String ERROR_VERSION_STALE = "VERSION_STALE";
    public static final String ERROR_VERSION_CONFLICT = "VERSION_CONFLICT";
    public static final String ERROR_APPLY_FAILED = "APPLY_FAILED";

    public static final int MAX_CONFIG_BYTES = 64 * 1024;
    public static final int MAX_JSON_DEPTH = 16;
    public static final int RECENT_MESSAGE_LIMIT = 100;
}
