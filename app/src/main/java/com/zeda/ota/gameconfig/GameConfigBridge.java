package com.zeda.ota.gameconfig;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

/**
 * OTA 向 Unity 发送游戏配置请求的广播桥。
 */
public final class GameConfigBridge {

    private static final String TAG = "OTA_TEST";

    private GameConfigBridge() {
    }

    public static boolean sendApply(
            Context context,
            GameConfigPending pending
    ) {
        if (pending == null) {
            Log.e(TAG, "send game config apply fail: pending null");
            return false;
        }

        try {
            JSONObject data = new JSONObject();
            data.put("gameCode", pending.getGameCode());
            data.put("schemaVersion", pending.getSchemaVersion());
            data.put("configVersion", pending.getConfigVersion());
            data.put("configHash", pending.getConfigHash());
            data.put("config", new JSONObject(pending.getCanonicalConfig()));

            JSONObject payload = buildBasePayload(
                    GameConfigProtocol.OP_APPLY_GAME_CONFIG,
                    pending.getRequestId(),
                    pending.getMessageId()
            );
            payload.put("data", data);

            return sendPayload(context, payload.toString());
        } catch (Exception e) {
            Log.e(TAG, "send game config apply fail", e);
            return false;
        }
    }

    public static boolean sendQuery(
            Context context,
            String requestId
    ) {
        try {
            JSONObject payload = buildBasePayload(
                    GameConfigProtocol.OP_QUERY_GAME_CONFIG,
                    requestId,
                    ""
            );
            payload.put("data", new JSONObject());
            return sendPayload(context, payload.toString());
        } catch (Exception e) {
            Log.e(TAG, "send game config query fail", e);
            return false;
        }
    }

    public static boolean sendPayload(
            Context context,
            String payload
    ) {
        if (context == null) {
            Log.e(TAG, "send game config payload fail: context null");
            return false;
        }

        if (payload == null || payload.trim().isEmpty()) {
            Log.e(TAG, "send game config payload fail: payload empty");
            return false;
        }

        try {
            Intent intent = new Intent(GameConfigProtocol.ACTION_GAME_CONFIG_REQUEST);
            intent.setPackage(GameConfigProtocol.GAME_PACKAGE);
            intent.putExtra(GameConfigProtocol.EXTRA_PAYLOAD, payload);

            context.getApplicationContext().sendBroadcast(
                    intent,
                    GameConfigProtocol.PERMISSION_GAME_CONFIG
            );

            Log.e(TAG, "game config request sent = " + payload);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "send game config payload exception", e);
            return false;
        }
    }

    private static JSONObject buildBasePayload(
            String operation,
            String requestId,
            String messageId
    ) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("protocolVersion", GameConfigProtocol.PROTOCOL_VERSION);
        payload.put("operation", safe(operation));
        payload.put("requestId", safe(requestId));
        payload.put("messageId", safe(messageId));
        payload.put("timestamp", System.currentTimeMillis());
        return payload;
    }

    private static String safe(
            String value
    ) {
        return value == null ? "" : value;
    }
}
