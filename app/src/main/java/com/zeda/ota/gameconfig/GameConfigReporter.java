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
 *
 * <p>负责向后端 report/game-config 主题上报设备当前实际生效配置。
 * 所有发布仍通过 MqttManager，保持 QoS=1、retain=false 的统一策略。</p>
 */
public final class GameConfigReporter {

    private static final String TAG = "OTA_TEST";

    private GameConfigReporter() {
    }

    /**
     * 直接发布一条 report/game-config payload。
     *
     * <p>调用方通常是 GameConfigStore 的 outbox 刷新逻辑。
     * 如果 MQTT 未连接，本方法返回 false，由 outbox 保留 payload 等待下次补发。</p>
     */
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

    /**
     * MQTT 首次连接、连接复用或自动重连后调用。
     *
     * <p>执行顺序：
     * 1. 优先补发历史 outbox；
     * 2. 如果本地存在 applied_record，再主动补报当前实际配置；
     * 3. 触发 pending/applied 向 Unity 的恢复下发。</p>
     */
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

    /**
     * 补发之前因 MQTT 离线或发布失败而保留的 report/game-config。
     */
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

    /**
     * 根据本地 applied_record 重新构造一条当前实际配置上报。
     *
     * <p>用于 OTA 重启或 MQTT 重连后，主动让后端重新获得设备当前配置状态。</p>
     */
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
