package com.zeda.ota.gameconfig;

import com.google.gson.JsonObject;

/**
 * 已通过校验的当前实际游戏配置快照。
 */
public final class GameConfigSnapshot {

    private final String gameCode;
    private final int schemaVersion;
    private final long configVersion;
    private final String configHash;
    private final JsonObject config;
    private final String canonicalConfig;

    public GameConfigSnapshot(
            String gameCode,
            int schemaVersion,
            long configVersion,
            String configHash,
            JsonObject config,
            String canonicalConfig
    ) {
        this.gameCode = gameCode;
        this.schemaVersion = schemaVersion;
        this.configVersion = configVersion;
        this.configHash = configHash;
        this.config = config.deepCopy();
        this.canonicalConfig = canonicalConfig;
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
