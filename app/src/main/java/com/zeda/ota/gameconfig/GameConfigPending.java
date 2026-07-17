package com.zeda.ota.gameconfig;

import org.json.JSONObject;

/**
 * 已可靠保存、等待 Unity 执行或等待结果回传的游戏配置任务。
 */
public final class GameConfigPending {

    public static final String STATE_SAVED = "saved";
    public static final String STATE_DISPATCHED = "dispatched";

    private final String messageId;
    private final String requestId;
    private final String gameCode;
    private final int schemaVersion;
    private final long configVersion;
    private final String configHash;
    private final String canonicalConfig;
    private final String state;
    private final long createdAt;
    private final long updatedAt;
    private final int dispatchCount;

    public GameConfigPending(
            String messageId,
            String requestId,
            String gameCode,
            int schemaVersion,
            long configVersion,
            String configHash,
            String canonicalConfig,
            String state,
            long createdAt,
            long updatedAt,
            int dispatchCount
    ) {
        this.messageId = safe(messageId);
        this.requestId = safe(requestId);
        this.gameCode = safe(gameCode);
        this.schemaVersion = schemaVersion;
        this.configVersion = configVersion;
        this.configHash = safe(configHash);
        this.canonicalConfig = safe(canonicalConfig);
        this.state = safe(state);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.dispatchCount = dispatchCount;
    }

    public static GameConfigPending fromCommand(
            GameConfigCommand command,
            String requestId,
            long now
    ) {
        return new GameConfigPending(
                command.getMessageId(),
                requestId,
                command.getGameCode(),
                command.getSchemaVersion(),
                command.getConfigVersion(),
                command.getConfigHash(),
                command.getCanonicalConfig(),
                STATE_SAVED,
                now,
                now,
                0
        );
    }

    public GameConfigPending markDispatched(
            String newRequestId,
            long now
    ) {
        return new GameConfigPending(
                messageId,
                newRequestId,
                gameCode,
                schemaVersion,
                configVersion,
                configHash,
                canonicalConfig,
                STATE_DISPATCHED,
                createdAt,
                now,
                dispatchCount + 1
        );
    }

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("messageId", messageId);
        json.put("requestId", requestId);
        json.put("gameCode", gameCode);
        json.put("schemaVersion", schemaVersion);
        json.put("configVersion", configVersion);
        json.put("configHash", configHash);
        json.put("canonicalConfig", canonicalConfig);
        json.put("state", state);
        json.put("createdAt", createdAt);
        json.put("updatedAt", updatedAt);
        json.put("dispatchCount", dispatchCount);
        return json;
    }

    public static GameConfigPending fromJson(
            String value
    ) throws Exception {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        JSONObject json = new JSONObject(value);
        return new GameConfigPending(
                json.optString("messageId", ""),
                json.optString("requestId", ""),
                json.optString("gameCode", ""),
                json.optInt("schemaVersion", 0),
                json.optLong("configVersion", 0),
                json.optString("configHash", ""),
                json.optString("canonicalConfig", ""),
                json.optString("state", ""),
                json.optLong("createdAt", 0),
                json.optLong("updatedAt", 0),
                json.optInt("dispatchCount", 0)
        );
    }

    public String getMessageId() {
        return messageId;
    }

    public String getRequestId() {
        return requestId;
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

    public String getCanonicalConfig() {
        return canonicalConfig;
    }

    public String getState() {
        return state;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public int getDispatchCount() {
        return dispatchCount;
    }

    private static String safe(
            String value
    ) {
        return value == null ? "" : value;
    }
}
