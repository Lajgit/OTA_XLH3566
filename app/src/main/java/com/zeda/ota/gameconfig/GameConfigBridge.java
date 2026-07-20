package com.zeda.ota.gameconfig;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

/**
 * OTA 向 Unity 发送游戏配置请求的广播桥。
 *
 * <p>本类只负责构造广播 payload 并发送显式广播，
 * 不负责配置校验、pending 状态保存或 MQTT 上报。</p>
 */
public final class GameConfigBridge {

    private static final String TAG = "OTA_TEST";

    private GameConfigBridge() {
    }

    /**
     * 向 Unity 下发待应用的游戏配置。
     *
     * <p>pending 中保存的是 OTA 已可靠落盘的配置任务，
     * 因此这里发送失败时应由状态机决定是否重试或失败。</p>
     */
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

    /**
     * 查询 Unity 当前实际配置。
     *
     * <p>当前版本主要预留给后续恢复核验使用，
     * 主流程仍以 apply_game_config 和 game_config_ready 为主。</p>
     */
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

    /**
     * 发送已构造好的 payload 字符串。
     *
     * <p>这里必须指定目标包 com.zeda，并带上签名级权限，
     * 防止配置广播被其他应用接收或伪造。</p>
     */
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

    /**
     * 构造 OTA 与 Unity 广播共用的基础字段。
     */
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

    /**
     * 防止 JSONObject 写入 null 字符串字段时产生歧义。
     */
    private static String safe(
            String value
    ) {
        return value == null ? "" : value;
    }
}
