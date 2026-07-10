package com.zeda.ota;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class BatchConfigReceiver {

    private static final String TAG = "OTA_TEST";

    private final Context context;

    private final Listener listener;

    private volatile boolean running = false;

    private DatagramSocket socket;

    private String lastConfigUuid = "";

    public interface Listener {

        void onWifiConfigReceived(
                String configUuid,
                String ssid,
                String password
        );
    }

    public BatchConfigReceiver(
            Context context,
            Listener listener
    ) {

        this.context =
                context.getApplicationContext();

        this.listener =
                listener;
    }

    public void start() {

        if (running) {
            Log.e(TAG, "batch receiver already running");
            return;
        }

        running =
                true;

        new Thread(() -> {

            try {

                socket =
                        new DatagramSocket(
                                null
                        );

                socket.setReuseAddress(
                        true
                );

                socket.bind(
                        new InetSocketAddress(
                                ProvisionConfig.BATCH_CONFIG_PORT
                        )
                );

                Log.e(
                        TAG,
                        "batch config receiver started, port="
                                + ProvisionConfig.BATCH_CONFIG_PORT
                );

                byte[] buffer =
                        new byte[4096];

                while (running) {

                    DatagramPacket packet =
                            new DatagramPacket(
                                    buffer,
                                    buffer.length
                            );

                    socket.receive(
                            packet
                    );

                    String payload =
                            new String(
                                    packet.getData(),
                                    packet.getOffset(),
                                    packet.getLength(),
                                    StandardCharsets.UTF_8
                            );

                    handlePayload(
                            payload
                    );
                }

            } catch (Throwable e) {

                if (running) {
                    Log.e(
                            TAG,
                            "batch receiver error",
                            e
                    );
                }

            } finally {

                stop();
            }

        }).start();
    }

    private void handlePayload(
            String payload
    ) {

        try {

            JSONObject json =
                    new JSONObject(
                            payload
                    );

            String type =
                    json.optString(
                            "type",
                            ""
                    );

            if (!"wifi_config".equals(type)) {
                return;
            }

            String configUuid =
                    json.optString(
                            "configUuid",
                            ""
                    );

            if (configUuid.isEmpty()) {
                return;
            }

            if (configUuid.equals(lastConfigUuid)) {
                Log.e(
                        TAG,
                        "duplicate batch config ignored, uuid="
                                + configUuid
                );
                return;
            }

            String ssid =
                    json.optString(
                            "ssid",
                            ""
                    );

            String password =
                    json.optString(
                            "password",
                            ""
                    );

            long timestamp =
                    json.optLong(
                            "timestamp",
                            0
                    );

            String signature =
                    json.optString(
                            "signature",
                            ""
                    );

            if (ssid.isEmpty()) {
                Log.e(TAG, "batch config ssid empty");
                return;
            }

            if (timestamp <= 0) {

                Log.e(
                        TAG,
                        "batch config timestamp empty"
                                + ", timestamp="
                                + timestamp
                );

                return;
            }

            /*
             * 不再用本机时间判断是否过期。
             * 原因：
             * 批量配网时设备可能还没联网校时，
             * 不同设备 System.currentTimeMillis() 可能相差几天甚至几个月。
             * 当前只依赖 configUuid 去重 + HMAC 签名校验。
             */
            Log.e(
                    TAG,
                    "batch config timestamp accepted"
                            + ", timestamp="
                            + timestamp
                            + ", localNow="
                            + System.currentTimeMillis()
            );

            String signRaw =
                    configUuid
                            + "|"
                            + ssid
                            + "|"
                            + password
                            + "|"
                            + timestamp;

            String expected =
                    HmacUtil.hmacSha256Base64Url(
                            signRaw,
                            ProvisionConfig.BATCH_CONFIG_SECRET
                    );

            if (!expected.equals(signature)) {

                Log.e(
                        TAG,
                        "batch config signature invalid"
                );

                return;
            }

            lastConfigUuid =
                    configUuid;

            Log.e(
                    TAG,
                    "batch config received"
                            + ", uuid="
                            + configUuid
                            + ", ssid="
                            + ssid
            );

            if (listener != null) {

                listener.onWifiConfigReceived(
                        configUuid,
                        ssid,
                        password
                );
            }

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "handle batch config fail",
                    e
            );
        }
    }

    public void stop() {

        running =
                false;

        try {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (Throwable ignored) {
        }

        Log.e(
                TAG,
                "batch config receiver stopped"
        );
    }
}