package com.zeda.ota;

import android.content.Context;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONObject;

public class VersionReporter {

    public static void report(Context context) {

        try {

            String version =
                    PackageUtil.getVersion(context, "com.zeda");

            publish(context, version, "online");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void reportSuccess(
            Context context,
            String version) {

        publish(context, version, "success");
    }

    public static void reportFail(
            Context context,
            String version) {

        publish(context, version, "fail");
    }

    private static void publish(
            Context context,
            String version,
            String status) {

        try {

            String id = DeviceUtil.getDeviceId(context);

            JSONObject json = new JSONObject();

            json.put("deviceNo", id);
            json.put("version", version);
            json.put("status", status);

            MqttManager.get(context)
                    .getClient()
                    .publish(
                            "device/" + id + "/version",
                            new MqttMessage(
                                    json.toString().getBytes()
                            )
                    );

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}