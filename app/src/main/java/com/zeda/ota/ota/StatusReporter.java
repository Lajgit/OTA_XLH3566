package com.zeda.ota;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

public class StatusReporter {

    private static final String TAG = "OTA_TEST";
    private static final String SP = "ota_status_report";
    private static final String KEY_QUEUE = "pending_queue";
    private static final int MAX_QUEUE_SIZE = 50;

    public static void report(
            Context context,
            String messageId,
            String status,
            int progress,
            String currentVersion,
            String targetVersion,
            long recordId,
            long taskId,
            String type,
            String errorCode,
            String errorMessage
    ) {

        try {

            String machineId = DeviceUtil.getDeviceId(context);

            JSONObject json = new JSONObject();

            json.put("messageId", safe(messageId));
            json.put("recordId", recordId);
            json.put("taskId", taskId);
            json.put("type", safe(type));

            json.put("machineId", machineId);
            json.put("status", safe(status));
            json.put("progress", progress);

            json.put("currentVersion", safe(currentVersion));
            json.put("targetVersion", safe(targetVersion));

            if (errorCode != null && !errorCode.isEmpty()) {
                json.put("errorCode", errorCode);
                json.put("resultCode", errorCode);
            }

            if (errorMessage != null && !errorMessage.isEmpty()) {
                json.put("errorMessage", errorMessage);
                json.put("resultMsg", errorMessage);
            }

            json.put("timestamp", System.currentTimeMillis());

            String topic =
                    "pxd/v1/device/" +
                            machineId +
                            "/report/upgrade-progress";

            sendOrCache(context, topic, json.toString());

        } catch (Exception e) {

            Log.e(TAG, "status build/send fail", e);
        }
    }

    public static void flushPending(Context context) {

        try {
            if (!MqttManager.get(context).isConnected()) {
                return;
            }

            SharedPreferences sp =
                    context.getSharedPreferences(SP, Context.MODE_PRIVATE);

            JSONArray queue =
                    new JSONArray(sp.getString(KEY_QUEUE, "[]"));

            if (queue.length() == 0) {
                return;
            }

            Log.e(TAG, "flush pending status count=" + queue.length());

            JSONArray failedOrLeft = new JSONArray();

            for (int i = 0; i < queue.length(); i++) {
                JSONObject item = queue.optJSONObject(i);

                if (item == null) {
                    continue;
                }

                String topic = item.optString("topic", "");
                String payload = item.optString("payload", "");

                if (topic.isEmpty() || payload.isEmpty()) {
                    continue;
                }

                boolean sent =
                        MqttManager.get(context)
                                .publish(
                                        topic,
                                        payload
                                );

                if (!sent) {

                    failedOrLeft.put(item);

                } else {

                    Log.e(
                            TAG,
                            "flush status success = "
                                    + payload
                    );
                }
            }

            sp.edit()
                    .putString(KEY_QUEUE, failedOrLeft.toString())
                    .apply();

        } catch (Exception e) {
            Log.e(TAG, "flush pending status fail", e);
        }
    }

    private static void sendOrCache(
            Context context,
            String topic,
            String payload
    ) {

        try {

            boolean sent =
                    MqttManager.get(context)
                            .publish(
                                    topic,
                                    payload
                            );

            if (sent) {

                Log.e(
                        TAG,
                        "status publish success = "
                                + payload
                );

                return;
            }

            Log.e(
                    TAG,
                    "status publish failed, cache = "
                            + payload
            );

            cache(
                    context,
                    topic,
                    payload
            );

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "status send exception, cache status",
                    e
            );

            cache(
                    context,
                    topic,
                    payload
            );
        }
    }

    private static void cache(
            Context context,
            String topic,
            String payload
    ) {

        try {
            SharedPreferences sp =
                    context.getSharedPreferences(SP, Context.MODE_PRIVATE);

            JSONArray oldQueue =
                    new JSONArray(sp.getString(KEY_QUEUE, "[]"));

            JSONArray newQueue = new JSONArray();

            int start = Math.max(0, oldQueue.length() - MAX_QUEUE_SIZE + 1);

            for (int i = start; i < oldQueue.length(); i++) {
                JSONObject item = oldQueue.optJSONObject(i);
                if (item != null) {
                    newQueue.put(item);
                }
            }

            JSONObject item = new JSONObject();
            item.put("topic", topic);
            item.put("payload", payload);
            item.put("timestamp", System.currentTimeMillis());

            newQueue.put(item);

            sp.edit()
                    .putString(KEY_QUEUE, newQueue.toString())
                    .apply();

        } catch (Exception e) {
            Log.e(TAG, "cache status fail", e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
