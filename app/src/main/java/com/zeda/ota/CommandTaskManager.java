package com.zeda.ota;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

public class CommandTaskManager {

    private static final String TAG = "OTA_TEST";

    public static void handle(
            Context context,
            String topic,
            String payload
    ) {

        try {

            JSONObject obj =
                    new JSONObject(payload);

            String localDeviceNo =
                    DeviceUtil.getDeviceId(context);

            String deviceNo =
                    obj.optString("deviceNo", "");

            String messageId =
                    obj.optString("messageId", "");

            String commandType =
                    obj.optString("commandType", "");

            String taskType =
                    obj.optString("taskType", "");

            Log.e(TAG, "command task messageId=" + messageId);
            Log.e(TAG, "command task commandType=" + commandType);
            Log.e(TAG, "command task taskType=" + taskType);

            if (messageId.isEmpty()) {

                Log.e(TAG, "command task ignore: messageId empty");
                return;
            }

            if (!deviceNo.isEmpty() &&
                    !deviceNo.equals(localDeviceNo)) {

                Log.e(
                        TAG,
                        "command task ignore: device not match"
                                + ", local="
                                + localDeviceNo
                                + ", target="
                                + deviceNo
                );

                return;
            }

            if (!"collect_log".equals(commandType) ||
                    !"collect_log".equals(taskType)) {

                Log.e(
                        TAG,
                        "command task ignore: unsupported commandType="
                                + commandType
                                + ", taskType="
                                + taskType
                );

                return;
            }

            String doneKey =
                    "collect_log_done_"
                            + messageId;

            String done =
                    PendingStore.get(
                            context,
                            doneKey
                    );

            if ("success".equals(done)) {

                Log.e(
                        TAG,
                        "collect_log duplicate success ignored, messageId="
                                + messageId
                );

                CommandResultReporter.success(
                        context,
                        messageId
                );

                return;
            }

            CommandResultReporter.ack(
                    context,
                    messageId
            );

            JSONObject data =
                    obj.optJSONObject("data");

            JSONArray logTypes =
                    null;

            if (data != null) {
                logTypes =
                        data.optJSONArray("logTypes");
            }

            JSONArray finalLogTypes =
                    logTypes;

            new Thread(() -> {

                try {

                    boolean ok =
                            LogUploadManager.uploadCommandBlocking(
                                    context,
                                    messageId,
                                    finalLogTypes
                            );

                    if (ok) {

                        PendingStore.save(
                                context,
                                doneKey,
                                "success"
                        );

                        CommandResultReporter.success(
                                context,
                                messageId
                        );

                    } else {

                        CommandResultReporter.failed(
                                context,
                                messageId,
                                "LOG_UPLOAD_FAILED",
                                "日志文件上传失败"
                        );
                    }

                } catch (Throwable e) {

                    Log.e(TAG, "collect_log execute fail", e);

                    CommandResultReporter.failed(
                            context,
                            messageId,
                            "LOG_UPLOAD_EXCEPTION",
                            e.toString()
                    );
                }

            }).start();

        } catch (Throwable e) {

            Log.e(TAG, "handle command task fail", e);
        }
    }
}