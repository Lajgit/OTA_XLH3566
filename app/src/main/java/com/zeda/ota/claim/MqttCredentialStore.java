package com.zeda.ota;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public class MqttCredentialStore {

    private static final String TAG = "OTA_TEST";

    private static final String PREF =
            "mqtt_credential";

    private static final String KEY_BROKER_URL =
            "brokerUrl";

    private static final String KEY_CLIENT_ID =
            "clientId";

    private static final String KEY_USERNAME =
            "username";

    private static final String KEY_PASSWORD =
            "password";

    private static final String KEY_HEARTBEAT_INTERVAL =
            "heartbeatInterval";

    private static final String KEY_KEEP_ALIVE_SECONDS =
            "keepAliveSeconds";

    public static boolean save(
            Context context,
            ActivationData data
    ) {

        if (data == null) {
            Log.e(TAG, "MqttCredentialStore save fail: data null");
            return false;
        }

        String brokerUrl =
                data.mqttBrokerUrl;

        if (isEmpty(brokerUrl)
                || isEmpty(data.mqttClientId)
                || isEmpty(data.mqttUsername)
                || isEmpty(data.mqttPassword)) {

            Log.e(
                    TAG,
                    "MqttCredentialStore save fail: mqtt credential empty"
                            + ", brokerUrl=" + brokerUrl
                            + ", clientId=" + data.mqttClientId
                            + ", username=" + data.mqttUsername
            );

            return false;
        }

        boolean ok =
                context.getApplicationContext()
                        .getSharedPreferences(
                                PREF,
                                Context.MODE_PRIVATE
                        )
                        .edit()
                        .putString(
                                KEY_BROKER_URL,
                                brokerUrl
                        )
                        .putString(
                                KEY_CLIENT_ID,
                                data.mqttClientId
                        )
                        .putString(
                                KEY_USERNAME,
                                data.mqttUsername
                        )
                        .putString(
                                KEY_PASSWORD,
                                data.mqttPassword
                        )
                        .putInt(
                                KEY_HEARTBEAT_INTERVAL,
                                data.heartbeatInterval
                        )
                        .putInt(
                                KEY_KEEP_ALIVE_SECONDS,
                                data.keepAliveSeconds
                        )
                        .commit();

        Log.e(
                TAG,
                "MqttCredentialStore save result="
                        + ok
                        + ", brokerUrl="
                        + brokerUrl
                        + ", clientId="
                        + data.mqttClientId
                        + ", username="
                        + data.mqttUsername
                        + ", heartbeatInterval="
                        + data.heartbeatInterval
                        + ", keepAliveSeconds="
                        + data.keepAliveSeconds
        );

        return ok;
    }

    public static MqttCredential load(
            Context context
    ) {

        SharedPreferences sp =
                context.getApplicationContext()
                        .getSharedPreferences(
                                PREF,
                                Context.MODE_PRIVATE
                        );

        String brokerUrl =
                sp.getString(
                        KEY_BROKER_URL,
                        null
                );

        String clientId =
                sp.getString(
                        KEY_CLIENT_ID,
                        null
                );

        String username =
                sp.getString(
                        KEY_USERNAME,
                        null
                );

        String password =
                sp.getString(
                        KEY_PASSWORD,
                        null
                );

        if (isEmpty(brokerUrl)
                || isEmpty(clientId)
                || isEmpty(username)
                || isEmpty(password)) {

            Log.e(
                    TAG,
                    "MqttCredentialStore load fail: mqtt credential invalid"
                            + ", brokerUrl=" + brokerUrl
                            + ", clientId=" + clientId
                            + ", username=" + username
            );

            return null;
        }

        MqttCredential c =
                new MqttCredential();

        c.brokerUrl = brokerUrl;
        c.clientId = clientId;
        c.username = username;
        c.password = password;

        c.heartbeatInterval =
                sp.getInt(
                        KEY_HEARTBEAT_INTERVAL,
                        0
                );

        c.keepAliveSeconds =
                sp.getInt(
                        KEY_KEEP_ALIVE_SECONDS,
                        0
                );

        Log.e(
                TAG,
                "MqttCredentialStore load success"
                        + ", brokerUrl="
                        + c.brokerUrl
                        + ", clientId="
                        + c.clientId
                        + ", username="
                        + c.username
                        + ", heartbeatInterval="
                        + c.heartbeatInterval
                        + ", keepAliveSeconds="
                        + c.keepAliveSeconds
        );

        return c;
    }

    public static void clear(
            Context context
    ) {

        context.getApplicationContext()
                .getSharedPreferences(
                        PREF,
                        Context.MODE_PRIVATE
                )
                .edit()
                .clear()
                .apply();

        Log.e(TAG, "MqttCredentialStore cleared");
    }

    private static boolean isEmpty(String s) {

        return s == null || s.trim().isEmpty();
    }
}