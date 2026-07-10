package com.zeda.ota;

import android.content.Context;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONObject;

public class MqttCallbackImpl implements MqttCallbackExtended {

    private static final String TAG = "OTA_TEST";

    private final Context context;

    public MqttCallbackImpl(Context context) {

        this.context =
                context.getApplicationContext();
    }

    @Override
    public void connectComplete(
            boolean reconnect,
            String serverURI
    ) {

        Log.e(
                TAG,
                "mqtt connectComplete reconnect="
                        + reconnect
                        + ", serverURI="
                        + serverURI
        );

        if (reconnect) {

            Log.e(TAG, "mqtt reconnect complete, ensure subscribe");

            MqttManager.get(context)
                    .ensureSubscribed();

            MqttManager.get(context)
                    .sendHeartbeat();

            StatusReporter.flushPending(context);

            MqttManager.get(context)
                    .startHeartbeatLoop();

            DeviceReportManager.get(context)
                    .reportStatus(
                            DeviceReportManager.get(context).getRunningStatus()
                    );

            DeviceReportManager.get(context)
                    .reportProfileIfNeeded(
                            false
                    );
        }
    }

    @Override
    public void connectionLost(Throwable cause) {

        Log.e(TAG, "mqtt lost", cause);

        /*
         * 已经在 MqttConnectOptions 里 setAutomaticReconnect(true)，
         * 这里不要再手动疯狂 connect，避免重复连接。
         */
    }

    @Override
    public void messageArrived(
            String topic,
            MqttMessage message
    ) {

        try {

            String payload =
                    message.toString();

            Log.e(TAG, "mqtt message arrived");
            Log.e(TAG, "topic = " + topic);
            Log.e(TAG, "payload = " + payload);

            JSONObject obj =
                    new JSONObject(payload);

            String type =
                    obj.optString("type", "");

            String msgId =
                    obj.optString("messageId", "");

            String deviceId =
                    obj.optString("deviceNo", "");

            String localId =
                    DeviceUtil.getDeviceId(context);

            Log.e(TAG, "local device id = " + localId);

            if (!deviceId.isEmpty() &&
                    !deviceId.equals(localId)) {

                Log.e(
                        TAG,
                        "ignore: not target device, local="
                                + localId
                                + ", target="
                                + deviceId
                );

                return;
            }

            if (topic.contains("/command/task")) {

                Log.e(TAG, "receive command task");

                CommandTaskManager.handle(
                        context,
                        topic,
                        payload
                );

                return;
            }

            if (topic.contains("/command/upgrade")) {

                handleUpgradeCommand(obj, payload);
                return;
            }

            if (topic.contains("/command/control")) {

                Log.e(TAG, "control command");

                return;
            }

            if (topic.contains("/command/config")) {

                Log.e(TAG, "config command");

                return;
            }

            Log.e(TAG, "unknown topic = " + topic);

        } catch (Throwable e) {

            Log.e(TAG, "mqtt callback error", e);
        }
    }

    private void handleUpgradeCommand(JSONObject obj, String payload) {

        String type = obj.optString("type", "");
        String msgId = obj.optString("messageId", "");
        String version = obj.optString("version", "");
        long recordId = obj.optLong("recordId", 0);
        long taskId = obj.optLong("taskId", 0);

        Log.e(TAG, "receive upgrade command, type=" + type);

        String currentVersion = "";
        String lastMsgKey = "";

        switch (type) {

            case "game":

                currentVersion =
                        PackageUtil.getVersion(
                                context,
                                "com.zeda"
                        );

                lastMsgKey = "game_msg";

                break;

            case "ball":

                currentVersion =
                        PendingStore.get(
                                context,
                                "ball_version"
                        );

                lastMsgKey = "ball_msg";

                break;

            case "ota":

                currentVersion =
                        PackageUtil.getVersion(
                                context,
                                "com.zeda.ota"
                        );

                lastMsgKey = "ota_msg";

                break;

            default:

                Log.e(TAG, "unknown upgrade type = " + type);

                StatusReporter.report(
                        context,
                        msgId,
                        "failed",
                        0,
                        "",
                        version,
                        recordId,
                        taskId,
                        type,
                        "UNSUPPORTED_TYPE",
                        "不支持的升级类型：" + type
                );

                DeviceReportManager.get(context)
                        .setRunningStatusAndReport(
                                DeviceReportManager.STATUS_IDLE
                        );

                return;
        }

        String lastMsg =
                PendingStore.get(
                        context,
                        lastMsgKey
                );

        Log.e(
                TAG,
                "type="
                        + type
                        + " version="
                        + version
                        + " current="
                        + currentVersion
                        + " lastMsg="
                        + lastMsg
                        + " msgId="
                        + msgId
        );

        if (!version.isEmpty() &&
                version.equals(currentVersion)) {

            Log.e(TAG, "already latest version");

            StatusReporter.report(
                    context,
                    msgId,
                    "skipped",
                    100,
                    currentVersion,
                    version,
                    recordId,
                    taskId,
                    type,
                    "ALREADY_LATEST",
                    "当前已是目标版本，无需升级"
            );

            DeviceReportManager.get(context)
                    .setRunningStatusAndReport(
                            DeviceReportManager.STATUS_IDLE
                    );

            return;
        }

        if (!msgId.isEmpty() &&
                msgId.equals(lastMsg)) {

            Log.e(TAG, "duplicate mqtt message");

            StatusReporter.report(
                    context,
                    msgId,
                    "downloading",
                    1,
                    currentVersion,
                    version,
                    recordId,
                    taskId,
                    type,
                    "DUPLICATE_MESSAGE",
                    "重复升级指令，设备已收到并正在处理或已进入后续流程"
            );

            return;
        }

        DeviceReportManager.get(context)
                .setRunningStatusAndReport(
                        DeviceReportManager.STATUS_UPGRADING
                );

        PendingStore.save(
                context,
                lastMsgKey,
                msgId
        );

        OtaReceiver.onReceive(
                context,
                payload
        );
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {

    }
}
