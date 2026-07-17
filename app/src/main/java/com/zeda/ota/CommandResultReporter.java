package com.zeda.ota;

import android.content.Context;
import android.util.Log;

import com.zeda.ota.gameconfig.GameConfigStore;

import org.json.JSONObject;

public class CommandResultReporter {

    private static final String TAG = "OTA_TEST";

    public static void report(
            Context context,
            String messageId,
            String status,
            String resultCode,
            String resultMessage
    ) {
        reportCommandResult(
                context,
                "collect_log",
                messageId,
                0,
                status,
                resultCode,
                resultMessage
        );
    }

    public static boolean reportCommandResult(
            Context context,
            String commandType,
            String messageId,
            long configVersion,
            String status,
            String resultCode,
            String resultMessage
    ) {

        try {

            String deviceNo =
                    DeviceUtil.getDeviceId(context);

            JSONObject json =
                    new JSONObject();

            json.put("deviceNo", deviceNo);
            json.put("messageId", messageId == null ? "" : messageId);
            json.put("commandType", commandType == null ? "" : commandType);
            if (configVersion > 0) {
                json.put("configVersion", configVersion);
            }
            json.put("status", status == null ? "" : status);
            json.put("resultCode", resultCode == null ? "0" : resultCode);
            json.put("resultMessage", resultMessage == null ? "" : resultMessage);
            json.put("timestamp", System.currentTimeMillis());

            return reportPayload(
                    context,
                    json.toString()
            );

        } catch (Throwable e) {

            Log.e(TAG, "command result build fail", e);
            return false;
        }
    }

    public static boolean reportPayload(
            Context context,
            String payload
    ) {
        try {
            if (context == null) {
                Log.e(TAG, "command result report fail: context null");
                return false;
            }

            if (payload == null || payload.trim().isEmpty()) {
                Log.e(TAG, "command result report fail: payload empty");
                return false;
            }

            String deviceNo =
                    DeviceUtil.getDeviceId(context);

            String topic =
                    MqttConfig.getCommandResultTopic(deviceNo);

            Log.e(TAG, "command result topic=" + topic);
            Log.e(TAG, "command result payload=" + payload);

            return MqttManager.get(context)
                    .publish(
                            topic,
                            payload
                    );

        } catch (Throwable e) {

            Log.e(TAG, "command result report fail", e);
            return false;
        }
    }

    public static void flushGameConfigOutbox(
            Context context
    ) {
        try {
            GameConfigStore store =
                    new GameConfigStore(context);

            String payload =
                    store.getCommandResultOutbox();

            if (payload == null || payload.trim().isEmpty()) {
                return;
            }

            if (reportPayload(context, payload)) {
                store.clearCommandResultOutbox();
            }
        } catch (Throwable e) {
            Log.e(TAG, "flush game config command result outbox fail", e);
        }
    }

    public static void ack(
            Context context,
            String messageId
    ) {

        report(
                context,
                messageId,
                "ack",
                "0",
                "设备已收到拉日志任务"
        );
    }

    public static void success(
            Context context,
            String messageId
    ) {

        report(
                context,
                messageId,
                "success",
                "0",
                "日志上传成功"
        );
    }

    public static void failed(
            Context context,
            String messageId,
            String resultCode,
            String resultMessage
    ) {

        report(
                context,
                messageId,
                "failed",
                resultCode,
                resultMessage
        );
    }
}
