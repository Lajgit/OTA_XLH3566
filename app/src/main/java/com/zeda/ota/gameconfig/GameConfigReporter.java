package com.zeda.ota.gameconfig;

import android.content.Context;
import android.util.Log;

import com.zeda.ota.DeviceUtil;
import com.zeda.ota.MqttConfig;
import com.zeda.ota.MqttManager;
import com.zeda.ota.PackageUtil;

import org.json.JSONObject;

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
            if (context == null) {
                return;
            }

            Context appContext =
                    context.getApplicationContext();

            GameConfigStore store =
                    new GameConfigStore(appContext);

            flushSavedOutbox(appContext, store);

            reportAppliedSnapshot(appContext, store);

            /*
             * MainService 启动时会调用 MqttManager.connect()，
             * MqttManager 连接成功后会调用本方法。
             * 因此这里同时触发一次游戏配置 pending/applied 恢复，
             * 避免额外修改 MainService 并造成重复恢复入口。
             */
            GameConfigManager.get(appContext)
                    .retryPending();
        } catch (Throwable e) {
            Log.e(TAG, "flush game config report outbox fail", e);
        }
    }

    private static void flushSavedOutbox(
            Context context,
            GameConfigStore store
    ) {
        String payload =
                store.getGameConfigReportOutbox();

        if (payload == null || payload.trim().isEmpty()) {
            return;
        }

        if (reportPayload(context, payload)) {
            store.clearGameConfigReportOutbox();
        }
    }

    private static void reportAppliedSnapshot(
            Context context,
            GameConfigStore store
    ) {
        try {
            GameConfigSnapshot snapshot =
                    store.getApplied();

            if (snapshot == null) {
                return;
            }

            JSONObject json =
                    new JSONObject();

            json.put("deviceNo", DeviceUtil.getDeviceId(context));
            json.put("gameCode", snapshot.getGameCode());
            json.put("gameVersion", PackageUtil.getVersion(
                    context,
                    GameConfigProtocol.GAME_PACKAGE
            ));
            json.put("maxSupportedSchemaVersion", GameConfigProtocol.MAX_SUPPORTED_SCHEMA_VERSION);
            json.put("schemaVersion", snapshot.getSchemaVersion());
            json.put("configVersion", snapshot.getConfigVersion());
            json.put("configHash", snapshot.getConfigHash());
            json.put("config", new JSONObject(snapshot.getCanonicalConfig()));
            json.put("timestamp", System.currentTimeMillis());

            store.saveGameConfigReportOutbox(json.toString());
        } catch (Throwable e) {
            Log.e(TAG, "report applied game config snapshot fail", e);
        }
    }
}
