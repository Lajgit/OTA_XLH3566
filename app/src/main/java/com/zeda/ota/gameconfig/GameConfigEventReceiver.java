package com.zeda.ota.gameconfig;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

/**
 * 接收 Unity 返回的游戏配置事件。
 *
 * <p>Manifest 中已使用签名权限限制调用方。
 * 这里再做 action、payload、protocolVersion 等基础校验，
 * 通过后把完整 payload 交给 GameConfigManager 状态机处理。</p>
 */
public class GameConfigEventReceiver extends BroadcastReceiver {

    private static final String TAG = "OTA_TEST";

    @Override
    public void onReceive(
            Context context,
            Intent intent
    ) {
        if (intent == null) {
            return;
        }

        // 只处理 Unity 发回来的游戏配置事件广播。
        String action = intent.getAction();
        if (!GameConfigProtocol.ACTION_GAME_CONFIG_EVENT.equals(action)) {
            Log.e(TAG, "ignore unknown game config event action=" + action);
            return;
        }

        // Unity 与 OTA 约定所有业务字段都放在 payload JSON 字符串中。
        String payload = intent.getStringExtra(GameConfigProtocol.EXTRA_PAYLOAD);
        if (payload == null || payload.trim().isEmpty()) {
            Log.e(TAG, "ignore game config event: payload empty");
            return;
        }

        try {
            JSONObject json = new JSONObject(payload);
            int protocolVersion = json.optInt("protocolVersion", 0);
            String operation = json.optString("operation", "");
            String requestId = json.optString("requestId", "");
            String messageId = json.optString("messageId", "");
            String status = json.optString("status", "");
            String resultCode = json.optString("resultCode", "");

            // 协议版本不一致时直接忽略，避免旧版或未知实现误触发状态机。
            if (protocolVersion != GameConfigProtocol.PROTOCOL_VERSION) {
                Log.e(
                        TAG,
                        "ignore game config event: unsupported protocolVersion="
                                + protocolVersion
                );
                return;
            }

            // operation 是状态机分支依据，缺失时无法判断业务含义。
            if (operation.trim().isEmpty()) {
                Log.e(TAG, "ignore game config event: operation empty");
                return;
            }

            Log.e(
                    TAG,
                    "game config event received"
                            + ", operation="
                            + operation
                            + ", requestId="
                            + requestId
                            + ", messageId="
                            + messageId
                            + ", status="
                            + status
                            + ", resultCode="
                            + resultCode
            );

            // 所有业务状态变化统一交给 GameConfigManager，Receiver 不直接改状态。
            GameConfigManager.get(context).handleGameEvent(payload);
        } catch (Exception e) {
            Log.e(TAG, "parse game config event fail", e);
        }
    }
}
