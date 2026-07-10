package com.zeda.ota;

import android.content.Context;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class MqttManager {

    private static final String TAG = "OTA_TEST";
    private static final long DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 60;

    private static volatile MqttManager instance;

    private MqttClient client;

    private final Context context;

    private volatile boolean reconnecting = false;
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> heartbeatTask;

    public static synchronized MqttManager get(Context c) {

        if (instance == null) {
            instance = new MqttManager(c);
        }

        return instance;
    }

    private MqttManager(Context c) {
        context = c.getApplicationContext();
    }

    public synchronized void connect() {

        try {

            String localDeviceId =
                    DeviceUtil.getDeviceId(context);

            Log.e(TAG, "mqtt connect called, local device id=" + localDeviceId);

            MqttCredential cred =
                    MqttCredentialStore.load(context);

            if (!isValidCredential(cred)) {

                Log.e(TAG, "mqtt credential invalid, need activation first");

                /*
                 * 可选：
                 * 如果你有 ClaimStore.setClaimed(context, false)，
                 * 建议这里清掉激活状态，让设备重新走激活流程。
                 */
                // ClaimStore.setClaimed(context, false);

                return;
            }

            Log.e(
                    TAG,
                    "mqtt credential clientId="
                            + cred.clientId
                            + ", username="
                            + cred.username
            );

            if (client != null && client.isConnected()) {

                Log.e(TAG, "mqtt already connected, ensure subscribe");

                ensureSubscribed();

                sendHeartbeat();

                StatusReporter.flushPending(context);

                startHeartbeatLoop();

                DeviceReportManager.get(context)
                        .start();

                DeviceReportManager.get(context)
                        .reportStatus(
                                DeviceReportManager.get(context).getRunningStatus()
                        );

                DeviceReportManager.get(context)
                        .reportProfileIfNeeded(
                                false
                        );

                return;
            }

            closeOldClient();

            client =
                    new MqttClient(
                            cred.brokerUrl,
                            cred.clientId,
                            new MemoryPersistence()
                    );

            MqttConnectOptions options =
                    new MqttConnectOptions();

            /*
             * 这里建议先用 true。
             * 每次连接都重新订阅，避免 broker 上旧 session / 旧订阅状态影响调试。
             * 后续如果需要离线消息，再改回 false。
             */
            options.setCleanSession(true);

            options.setAutomaticReconnect(true);

            options.setConnectionTimeout(MqttConfig.TIMEOUT);

            int keepAliveSeconds =
                    getKeepAliveSeconds(cred);

            options.setKeepAliveInterval(
                    keepAliveSeconds
            );

            options.setUserName(cred.username);

            options.setPassword(cred.password.toCharArray());

            client.setCallback(new MqttCallbackImpl(context));

            Log.e(
                    TAG,
                    "mqtt connecting host="
                            + cred.brokerUrl
                            + ", clientId="
                            + cred.clientId
                            + ", keepAliveSeconds="
                            + keepAliveSeconds
            );

            client.connect(options);

            Log.e(TAG, "mqtt connected");

            ensureSubscribed();

            sendHeartbeat();

            StatusReporter.flushPending(context);

            startHeartbeatLoop();

            DeviceReportManager.get(context)
                    .start();

            DeviceReportManager.get(context)
                    .reportStatus(
                            DeviceReportManager.STATUS_IDLE
                    );

            DeviceReportManager.get(context)
                    .reportProfileIfNeeded(
                            true
                    );

        } catch (Throwable e) {

            Log.e(TAG, "mqtt connect fail", e);

            reconnectDelay();
        }
    }

    public synchronized void ensureSubscribed() {

        try {

            if (client == null) {

                Log.e(TAG, "subscribe fail: client null");

                return;
            }

            if (!client.isConnected()) {

                Log.e(TAG, "subscribe fail: mqtt not connected");

                return;
            }

            String deviceId =
                    DeviceUtil.getDeviceId(context);

            String upgradeTopic =
                    MqttConfig.getUpgradeTopic(deviceId);

            String controlTopic =
                    MqttConfig.getControlTopic(deviceId);

            String configTopic =
                    MqttConfig.getConfigTopic(deviceId);

            String taskTopic =
                    MqttConfig.getTaskTopic(deviceId);

            Log.e(TAG, "subscribe local device id=" + deviceId);

            client.subscribe(
                    new String[]{
                            upgradeTopic,
                            controlTopic,
                            configTopic,
                            taskTopic
                    },
                    new int[]{
                            1,
                            1,
                            1,
                            1
                    }
            );

            Log.e(TAG, "subscribe = " + upgradeTopic);
            Log.e(TAG, "subscribe = " + controlTopic);
            Log.e(TAG, "subscribe = " + configTopic);
            Log.e(TAG, "subscribe = " + taskTopic);
            Log.e(TAG, "subscribe success");

        } catch (Throwable e) {

            Log.e(TAG, "subscribe fail", e);
        }
    }

    public void sendHeartbeat() {

        sendHeartbeat(
                false
        );
    }

    public void sendHeartbeatWithMainGuard() {

        sendHeartbeat(
                true
        );
    }

    private void sendHeartbeat(
            boolean checkMainApp
    ) {

        try {

            String deviceId =
                    DeviceUtil.getDeviceId(context);

            JSONObject json =
                    new JSONObject();

            json.put("machineId", deviceId);
            json.put("status", "online");
            json.put("timestamp", System.currentTimeMillis());

            publish(
                    MqttConfig.getHeartbeatTopic(deviceId),
                    json.toString()
            );

            Log.e(TAG, "heartbeat send");

            if (checkMainApp) {

                /*
                 * 定时心跳才检测主程序。
                 * MQTT 刚连接成功时，不要在初始化完成前提前拉起主程序。
                 */
                MainAppGuard.checkAndRestartIfNeeded(
                        context
                );

            } else {

                Log.e(
                        TAG,
                        "main guard skip: heartbeat without guard"
                );
            }

        } catch (Throwable e) {

            Log.e(TAG, "heartbeat fail", e);
        }
    }

    public synchronized void startHeartbeatLoop() {

        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {

            Log.e(TAG, "heartbeat loop already started");

            return;
        }

        long heartbeatIntervalSeconds =
                getHeartbeatIntervalSeconds();

        heartbeatTask =
                heartbeatExecutor.scheduleWithFixedDelay(
                        this::sendHeartbeatWithMainGuard,
                        heartbeatIntervalSeconds,
                        heartbeatIntervalSeconds,
                        TimeUnit.SECONDS
                );

        Log.e(TAG, "heartbeat loop started, interval seconds=" + heartbeatIntervalSeconds);
    }

    public synchronized boolean publish(
            String topic,
            String payload
    ) {

        boolean connected =
                client != null &&
                        client.isConnected();

        Log.e(TAG, "publish connected = " + connected);
        Log.e(TAG, "publish topic = " + topic);
        Log.e(TAG, "publish payload = " + payload);

        if (!connected) {

            Log.e(
                    TAG,
                    "mqtt disconnected, publish failed"
                            + ", topic="
                            + topic
            );

            return false;
        }

        try {

            MqttMessage message =
                    new MqttMessage(
                            payload.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8
                            )
                    );

            message.setQos(1);
            message.setRetained(false);

            client.publish(
                    topic,
                    message
            );

            Log.e(TAG, "mqtt publish success");

            return true;

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "mqtt publish fail, topic="
                            + topic,
                    e
            );

            Log.e(
                    TAG,
                    "mqtt publish fail, payload="
                            + payload
            );

            return false;
        }
    }

    private void reconnectDelay() {

        if (reconnecting) {
            Log.e(TAG, "mqtt reconnect already scheduled");
            return;
        }

        reconnecting = true;

        new Thread(() -> {

            try {

                Thread.sleep(5000);

                reconnecting = false;

                connect();

            } catch (Throwable e) {

                reconnecting = false;

                Log.e(TAG, "mqtt reconnect delay fail", e);
            }

        }).start();
    }

    private void closeOldClient() {

        try {

            if (client != null) {

                try {
                    if (client.isConnected()) {
                        client.disconnect();
                    }
                } catch (Throwable ignored) {
                }

                try {
                    client.close();
                } catch (Throwable ignored) {
                }

                client = null;
            }

        } catch (Throwable e) {

            Log.e(TAG, "close old mqtt client fail", e);
        }
    }

    public synchronized boolean isConnected() {

        return client != null && client.isConnected();
    }

    public MqttClient getClient() {

        return client;
    }

    private long getHeartbeatIntervalSeconds() {

        MqttCredential cred =
                MqttCredentialStore.load(context);

        if (cred != null && cred.heartbeatInterval > 0) {
            return cred.heartbeatInterval;
        }

        return DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
    }

    private int getKeepAliveSeconds(
            MqttCredential cred
    ) {

        if (cred != null && cred.keepAliveSeconds > 0) {
            return cred.keepAliveSeconds;
        }

        return MqttConfig.KEEPALIVE;
    }

    private boolean isValidCredential(
            MqttCredential cred
    ) {

        if (cred == null) {
            return false;
        }

        if (isEmpty(cred.brokerUrl)) {
            Log.e(TAG, "mqtt brokerUrl empty");
            return false;
        }

        if (isEmpty(cred.clientId)) {
            Log.e(TAG, "mqtt clientId empty");
            return false;
        }

        if (isEmpty(cred.username)) {
            Log.e(TAG, "mqtt username empty");
            return false;
        }

        if (isEmpty(cred.password)) {
            Log.e(TAG, "mqtt password empty");
            return false;
        }

        return true;
    }

    private boolean isEmpty(String s) {

        return s == null || s.trim().isEmpty();
    }
}
