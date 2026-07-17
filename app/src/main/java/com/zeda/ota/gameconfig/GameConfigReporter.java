package com.zeda.ota.gameconfig;

import android.content.Context;
import android.util.Log;

import com.zeda.ota.DeviceUtil;
import com.zeda.ota.MqttConfig;
import com.zeda.ota.MqttManager;

/**
 * 游戏实际配置状态上报器。
 */
public final class GameConfigReporter {

    private static final String TAG = "OTA_TEST";

    private GameConfigReporter() {
    }

    public static boolean reportPayload(
            Context context,
            String payload
    ) {
        try {
            if (context == null) {
                Log.e(TAG, "game config report fail: context null");
                return false;
            }

            if (payload == null || payload.trim().isEmpty()) {
                Log.e(TAG, "game config report fail: payload empty");
                return false;
            }

            String deviceNo =
                    DeviceUtil.getDeviceId(context);

            String topic =
                    MqttConfig.getGameConfigReportTopic(deviceNo);

            Log.e(TAG, "game config report topic=" + topic);
            Log.e(TAG, "game config report payload=" + payload);

            return MqttManager.get(context)
                    .publish(
                            topic,
                            payload
                    );
        } catch (Throwable e) {
            Log.e(TAG, "game config report fail", e);
            return false;
        }
    }

    public static void flushOutbox(
            Context context
    ) {
        try {
            GameConfigStore store =
                    new GameConfigStore(context);

            String payload =
                    store.getGameConfigReportOutbox();

            if (payload == null || payload.trim().isEmpty()) {
                return;
            }

            if (reportPayload(context, payload)) {
                store.clearGameConfigReportOutbox();
            }
        } catch (Throwable e) {
            Log.e(TAG, "flush game config report outbox fail", e);
        }
    }
}
