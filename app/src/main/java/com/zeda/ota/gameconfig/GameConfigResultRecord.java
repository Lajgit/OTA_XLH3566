package com.zeda.ota.gameconfig;

import org.json.JSONObject;

/**
 * 已完成的游戏配置指令结果，用于 messageId 幂等重放。
 */
public final class GameConfigResultRecord {

    private final String messageId;
    private final long configVersion;
    private final String configHash;
    private final String status;
    private final String resultCode;
    private final String resultMessage;
    private final long timestamp;

    public GameConfigResultRecord(
            String messageId,
            long configVersion,
            String configHash,
            String status,
            String resultCode,
            String resultMessage,
            long timestamp
    ) {
        this.messageId = safe(messageId);
        this.configVersion = configVersion;
        this.configHash = safe(configHash);
        this.status = safe(status);
        this.resultCode = safe(resultCode);
        this.resultMessage = safe(resultMessage);
        this.timestamp = timestamp;
    }

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("messageId", messageId);
        json.put("configVersion", configVersion);
        json.put("configHash", configHash);
        json.put("status", status);
        json.put("resultCode", resultCode);
        json.put("resultMessage", resultMessage);
        json.put("timestamp", timestamp);
        return json;
    }

    public static GameConfigResultRecord fromJson(
            JSONObject json
    ) {
        if (json == null) {
            return null;
        }

        return new GameConfigResultRecord(
                json.optString("messageId", ""),
                json.optLong("configVersion", 0),
                json.optString("configHash", ""),
                json.optString("status", ""),
                json.optString("resultCode", ""),
                json.optString("resultMessage", ""),
                json.optLong("timestamp", 0)
        );
    }

    public String getMessageId() {
        return messageId;
    }

    public long getConfigVersion() {
        return configVersion;
    }

    public String getConfigHash() {
        return configHash;
    }

    public String getStatus() {
        return status;
    }

    public String getResultCode() {
        return resultCode;
    }

    public String getResultMessage() {
        return resultMessage;
    }

    public long getTimestamp() {
        return timestamp;
    }

    private static String safe(
            String value
    ) {
        return value == null ? "" : value;
    }
}
