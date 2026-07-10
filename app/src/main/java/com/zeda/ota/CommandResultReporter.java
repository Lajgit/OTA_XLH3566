package com.zeda.ota;

import android.content.Context;
import android.util.Log;

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

        try {

            String deviceNo =
                    DeviceUtil.getDeviceId(context);

            JSONObject json =
                    new JSONObject();

            json.put("deviceNo", deviceNo);
            json.put("messageId", messageId);
            json.put("commandType", "collect_log");
            json.put("status", status);
            json.put("resultCode", resultCode == null ? "0" : resultCode);
            json.put("resultMessage", resultMessage == null ? "" : resultMessage);
            json.put("timestamp", System.currentTimeMillis());

            String topic =
                    MqttConfig.getCommandResultTopic(deviceNo);

            Log.e(TAG, "command result topic=" + topic);
            Log.e(TAG, "command result payload=" + json.toString());

            MqttManager.get(context)
                    .publish(
                            topic,
                            json.toString()
                    );

        } catch (Throwable e) {

            Log.e(TAG, "command result report fail", e);
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