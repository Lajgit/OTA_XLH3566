package com.zeda.ota.gameconfig;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

/**
 * 接收 Unity 返回的游戏配置事件。
 *
 * <p>当前步骤只建立广播入口，完整状态机将在后续 GameConfigManager 中接入。</p>
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

        String action = intent.getAction();
        if (!GameConfigProtocol.ACTION_GAME_CONFIG_EVENT.equals(action)) {
            Log.e(TAG, "ignore unknown game config event action=" + action);
            return;
        }

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

            if (protocolVersion != GameConfigProtocol.PROTOCOL_VERSION) {
                Log.e(
                        TAG,
                        "ignore game config event: unsupported protocolVersion="
                                + protocolVersion
                );
                return;
            }

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

            /*
             * 后续步骤接入 GameConfigManager 后，将在这里转交：
             * GameConfigManager.get(context).handleGameEvent(payload);
             */
        } catch (Exception e) {
            Log.e(TAG, "parse game config event fail", e);
        }
    }
}
