package com.zeda.ota;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BatchConfigSender {

    private static final String TAG = "OTA_TEST";

    public static void broadcastBlocking(
            Context context,
            String ssid,
            String password,
            long durationMs
    ) {

        DatagramSocket socket = null;

        try {

            String configUuid =
                    UUID.randomUUID()
                            .toString()
                            .replace(
                                    "-",
                                    ""
                            );

            long timestamp =
                    System.currentTimeMillis();

            String signRaw =
                    configUuid
                            + "|"
                            + ssid
                            + "|"
                            + password
                            + "|"
                            + timestamp;

            String signature =
                    HmacUtil.hmacSha256Base64Url(
                            signRaw,
                            ProvisionConfig.BATCH_CONFIG_SECRET
                    );

            JSONObject json =
                    new JSONObject();

            json.put(
                    "type",
                    "wifi_config"
            );

            json.put(
                    "configUuid",
                    configUuid
            );

            json.put(
                    "ssid",
                    ssid
            );

            json.put(
                    "password",
                    password
            );

            json.put(
                    "timestamp",
                    timestamp
            );

            json.put(
                    "senderDeviceNo",
                    DeviceUtil.getDeviceId(
                            context
                    )
            );

            json.put(
                    "signature",
                    signature
            );

            byte[] data =
                    json.toString()
                            .getBytes(
                                    StandardCharsets.UTF_8
                            );

            socket =
                    new DatagramSocket();

            socket.setBroadcast(
                    true
            );

            InetAddress broadcast =
                    InetAddress.getByName(
                            "255.255.255.255"
                    );

            DatagramPacket packet =
                    new DatagramPacket(
                            data,
                            data.length,
                            broadcast,
                            ProvisionConfig.BATCH_CONFIG_PORT
                    );

            long endTime =
                    System.currentTimeMillis()
                            + durationMs;

            Log.e(
                    TAG,
                    "batch config broadcast start"
                            + ", ssid="
                            + ssid
                            + ", configUuid="
                            + configUuid
            );

            while (System.currentTimeMillis() < endTime) {

                socket.send(
                        packet
                );

                Log.e(
                        TAG,
                        "batch config broadcast sent"
                                + ", port="
                                + ProvisionConfig.BATCH_CONFIG_PORT
                                + ", configUuid="
                                + configUuid
                );

                Thread.sleep(
                        500
                );
            }

            Log.e(
                    TAG,
                    "batch config broadcast finished"
            );

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "batch config broadcast fail",
                    e
            );

        } finally {

            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}