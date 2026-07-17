package com.zeda.ota.gameconfig;

import com.google.gson.JsonObject;

/**
 * 已通过协议、Schema 与哈希校验的游戏配置命令。
 */
public final class GameConfigCommand {

    private final String messageId;
    private final String deviceNo;
    private final long timestamp;
    private final String gameCode;
    private final int schemaVersion;
    private final long configVersion;
    private final String configHash;
    private final JsonObject config;
    private final String canonicalConfig;

    public GameConfigCommand(
            String messageId,
            String deviceNo,
            long timestamp,
            String gameCode,
            int schemaVersion,
            long configVersion,
            String configHash,
            JsonObject config,
            String canonicalConfig
    ) {
        this.messageId = messageId;
        this.deviceNo = deviceNo;
        this.timestamp = timestamp;
        this.gameCode = gameCode;
        this.schemaVersion = schemaVersion;
        this.configVersion = configVersion;
        this.configHash = configHash;
        this.config = config.deepCopy();
        this.canonicalConfig = canonicalConfig;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getDeviceNo() {
        return deviceNo;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getGameCode() {
        return gameCode;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public long getConfigVersion() {
        return configVersion;
    }

    public String getConfigHash() {
        return configHash;
    }

    public JsonObject getConfig() {
        return config.deepCopy();
    }

    public String getCanonicalConfig() {
        return canonicalConfig;
    }
}
