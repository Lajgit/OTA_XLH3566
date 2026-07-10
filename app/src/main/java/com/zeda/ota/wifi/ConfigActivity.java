package com.zeda.ota;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;

import android.widget.Toast;
import android.widget.TextView;
import com.zeda.ota.wifi.QrUtil;

public class ConfigActivity extends Activity {

    private static final String TAG = "OTA_TEST";

    private ConfigServer server;

    private ImageView ivQr;

    private TextView tvTitle;

    private TextView tvHint;

    private BatchConfigReceiver batchReceiver;

    private volatile boolean factoryMode = false;

    private final Handler mainHandler =
            new Handler(
                    Looper.getMainLooper()
            );

    private volatile boolean submitted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        hideSystemUI();

        setContentView(R.layout.activity_config);

        ivQr =
                findViewById(R.id.ivQr);

        tvTitle =
                findViewById(R.id.tvTitle);

        tvHint =
                findViewById(R.id.tvHint);

        startProvisionFlow();
    }

    private void startProvisionFlow() {

        submitted =
                false;

        setUiText(
                "正在搜索配网路由器",
                "正在尝试连接临时配网热点：\n"
                        + ProvisionConfig.FACTORY_WIFI_SSID
                        + "\n请稍后..."
        );

        new Thread(() -> {

            try {

                Log.e(
                        TAG,
                        "start provision flow"
                );

                try {
                    WifiManagerUtil.stopHotspot(
                            ConfigActivity.this
                    );
                } catch (Throwable ignored) {
                }

                /*
                 * 第二层：优先尝试连接临时配网路由器 OTA_FACTORY。
                 */
                Log.e(
                        TAG,
                        "try connect factory wifi"
                );

                WifiManagerUtil.connectFactoryWifi(
                        ConfigActivity.this
                );

                boolean factoryConnected =
                        waitFactoryWifiConnected(
                                ProvisionConfig.FACTORY_CONNECT_WAIT_SECONDS
                        );

                if (factoryConnected) {

                    Log.e(
                            TAG,
                            "factory wifi connected, enter batch config mode"
                    );

                    startFactoryMode();

                    return;
                }

                /*
                 * 第三层：OTA_FACTORY 不存在时，回退到单机热点。
                 */
                Log.e(
                        TAG,
                        "factory wifi not connected, enter single ap mode"
                );

                startSingleApMode();

            } catch (Throwable e) {

                Log.e(
                        TAG,
                        "start provision flow fail",
                        e
                );

                startSingleApMode();
            }

        }).start();
    }

    private void startFactoryMode() {

        factoryMode =
                true;

        setUiText(
                "批量配网模式",
                "已连接临时配网热点：\n"
                        + ProvisionConfig.FACTORY_WIFI_SSID
                        + "\n\n请使用手机连接热点："
                        + ProvisionConfig.FACTORY_WIFI_SSID
                        + "\n然后扫码配置正式 WiFi"
        );

        mainHandler.post(
                () -> Toast.makeText(
                        this,
                        "已连接 OTA_FACTORY，进入批量配网模式",
                        Toast.LENGTH_LONG
                ).show()
        );

        stopConfigServer();

        startConfigServer();

        startBatchReceiver();

        try {

            Thread.sleep(
                    1000
            );

        } catch (Throwable ignored) {
        }

        String url =
                WifiManagerUtil.getLanConfigUrl(
                        this
                );

        Log.e(
                TAG,
                "factory config url="
                        + url
        );

        if (url == null ||
                url.trim().isEmpty()) {

            Log.e(
                    TAG,
                    "factory config url empty, fallback single ap"
            );

            startSingleApMode();

            return;
        }

        showQr(
                url
        );
    }

    private void startSingleApMode() {

        factoryMode =
                false;

        stopBatchReceiver();

        stopConfigServer();

        startConfigServer();

        String hotspotSsid =
                WifiManagerUtil.getHotspotSsid(
                        ConfigActivity.this
                );

        setUiText(
                "单机配网模式",
                "未发现临时配网热点："
                        + ProvisionConfig.FACTORY_WIFI_SSID
                        + "\n\n正在开启设备热点：\n"
                        + hotspotSsid
                        + "\n请稍后..."
        );

        new Thread(() -> {

            try {

                boolean result =
                        WifiManagerUtil.startHotspot(
                                ConfigActivity.this
                        );

                Log.e(
                        TAG,
                        "startHotspot result="
                                + result
                );

                if (!result) {

                    setUiText(
                            "热点开启失败",
                            "设备热点开启失败，请检查系统热点权限"
                    );

                    Log.e(
                            TAG,
                            "hotspot start failed"
                    );

                    return;
                }

                Thread.sleep(
                        1500
                );

                String url =
                        WifiManagerUtil.getConfigUrl();

                Log.e(
                        TAG,
                        "single ap qr url="
                                + url
                );

                setUiText(
                        "单机配网模式",
                        "请使用手机连接热点：\n"
                                + hotspotSsid
                                + "\n密码："
                                + ProvisionConfig.SINGLE_AP_PASSWORD
                                + "\n\n连接后扫码配置 WiFi"
                );

                showQr(
                        url
                );

            } catch (Throwable e) {

                Log.e(
                        TAG,
                        "start single ap mode fail",
                        e
                );

                setUiText(
                        "单机配网异常",
                        "开启设备热点失败，请重启设备后重试"
                );
            }

        }).start();
    }

    private void startBatchReceiver() {

        stopBatchReceiver();

        batchReceiver =
                new BatchConfigReceiver(
                        this,
                        (configUuid, ssid, password) -> {

                            Log.e(
                                    TAG,
                                    "batch wifi config received"
                                            + ", uuid="
                                            + configUuid
                                            + ", ssid="
                                            + ssid
                            );

                            handleBatchWifiConfig(
                                    ssid,
                                    password
                            );
                        }
                );

        batchReceiver.start();
    }

    private void handleBatchWifiConfig(
            String ssid,
            String password
    ) {

        if (submitted) {

            Log.e(
                    TAG,
                    "already submitted, ignore batch config"
            );

            return;
        }

        submitted =
                true;

        mainHandler.post(
                () -> Toast.makeText(
                        this,
                        "收到批量配网配置，正在连接正式 WiFi",
                        Toast.LENGTH_LONG
                ).show()
        );

        new Thread(() -> {

            try {

                stopConfigServer();

                stopBatchReceiver();

                connectTargetWifiAndContinue(
                        ssid,
                        password,
                        true
                );

            } catch (Throwable e) {

                Log.e(
                        TAG,
                        "handle batch wifi config fail",
                        e
                );

                mainHandler.post(
                        () -> restartFactoryOrSingleMode(
                                "批量配网失败，请重新配置"
                        )
                );
            }

        }).start();
    }

    private void startConfigServer() {

        server =
                new ConfigServer(
                        (ssid, password) -> {

                            if (submitted) {

                                Log.e(
                                        TAG,
                                        "wifi already submitted, ignore"
                                );

                                return;
                            }

                            submitted = true;

                            Log.e(
                                    TAG,
                                    "receive wifi submit ssid="
                                            + ssid
                            );

                            handleWifiSubmit(
                                    ssid,
                                    password
                            );
                        }
                );

        try {

            server.start();

            Log.e(TAG, "config server started");

        } catch (Throwable e) {

            Log.e(TAG, "config server start fail", e);
        }
    }

    private void handleWifiSubmit(
            String ssid,
            String password
    ) {

        setUiText(
                "正在配置 WiFi",
                "已收到 WiFi 配置：\n"
                        + ssid
                        + "\n\n设备正在连接网络，请稍后..."
        );

        new Thread(() -> {

            try {

                Thread.sleep(
                        2000
                );

                if (factoryMode) {

                    setUiText(
                            "正在批量下发配置",
                            "正在把 WiFi 配置广播给其他设备：\n"
                                    + ssid
                                    + "\n\n请稍后..."
                    );

                    Log.e(
                            TAG,
                            "factory mode submit, broadcast wifi config"
                                    + ", ssid="
                                    + ssid
                    );

                    stopBatchReceiver();

                    BatchConfigSender.broadcastBlocking(
                            ConfigActivity.this,
                            ssid,
                            password,
                            ProvisionConfig.BATCH_BROADCAST_DURATION_MS
                    );

                    stopConfigServer();

                    connectTargetWifiAndContinue(
                            ssid,
                            password,
                            true
                    );

                    return;
                }

                Log.e(
                        TAG,
                        "single ap mode submit"
                                + ", ssid="
                                + ssid
                );

                stopConfigServer();

                WifiManagerUtil.stopHotspot(
                        ConfigActivity.this
                );

                Thread.sleep(
                        3000
                );

                connectTargetWifiAndContinue(
                        ssid,
                        password,
                        false
                );

            } catch (Throwable e) {

                Log.e(
                        TAG,
                        "handleWifiSubmit fail",
                        e
                );

                setUiText(
                        "配置异常",
                        "WiFi 配置失败，请重新尝试"
                );
            }

        }).start();
    }

    private void connectTargetWifiAndContinue(
            String ssid,
            String password,
            boolean fromFactoryMode
    ) {

        try {

            setUiText(
                    "正在连接正式 WiFi",
                    "正在连接：\n"
                            + ssid
                            + "\n\n请稍后..."
            );

            boolean connectInvoked =
                    WifiManagerUtil.connectWifi(
                            ConfigActivity.this,
                            ssid,
                            password
                    );

            Log.e(
                    TAG,
                    "connect target wifi invoked result="
                            + connectInvoked
                            + ", ssid="
                            + ssid
            );

            boolean connected =
                    waitTargetWifiConnected(
                            ProvisionConfig.TARGET_WIFI_CONNECT_WAIT_SECONDS
                    );

            if (connected) {

                Log.e(
                        TAG,
                        "target wifi connected, start MainService"
                );

                /*
                 * 关键：
                 * 保存正式 WiFi。
                 * 下次重启时，MainActivity 会优先主动连接这个 WiFi，
                 * 不会因为 OTA_FACTORY 还在就直接进入批量配网。
                 */
                WifiCredentialStore.save(
                        ConfigActivity.this,
                        ssid,
                        password
                );

                setUiText(
                        "WiFi 连接成功",
                        "已连接正式 WiFi：\n"
                                + ssid
                                + "\n\n正在启动设备服务..."
                );

                mainHandler.postDelayed(
                        this::startServiceAndFinish,
                        1000
                );

                return;
            }

            Log.e(
                    TAG,
                    "target wifi connect failed, ssid="
                            + ssid
            );

            setUiText(
                    "正式 WiFi 连接失败",
                    "WiFi 名称：\n"
                            + ssid
                            + "\n\n可能是密码错误或信号较弱\n正在重新进入配网模式..."
            );

            mainHandler.postDelayed(
                    () -> {

                        if (fromFactoryMode) {

                            restartFactoryOrSingleMode(
                                    "正式WiFi连接失败，重新进入批量配网"
                            );

                        } else {

                            restartConfigMode(
                                    "WiFi连接失败，请检查密码后重新配置"
                            );
                        }
                    },
                    2000
            );

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "connectTargetWifiAndContinue fail",
                    e
            );

            setUiText(
                    "WiFi 连接异常",
                    "连接 WiFi 时发生异常\n正在重新进入配网模式..."
            );

            mainHandler.postDelayed(
                    () -> {

                        if (fromFactoryMode) {

                            restartFactoryOrSingleMode(
                                    "WiFi连接异常，重新进入批量配网"
                            );

                        } else {

                            restartConfigMode(
                                    "WiFi连接异常，请重新配置"
                            );
                        }
                    },
                    2000
            );
        }
    }

    private boolean waitFactoryWifiConnected(
            int seconds
    ) {

        int retry =
                seconds;

        int stableCount =
                0;

        while (retry-- > 0) {

            try {

                Thread.sleep(
                        1000
                );

                String currentSsid =
                        WifiManagerUtil.getCurrentSsid(
                                this
                        );

                boolean factory =
                        ProvisionConfig.FACTORY_WIFI_SSID.equals(
                                currentSsid
                        );

                String localIp =
                        WifiManagerUtil.getLocalIp();

                boolean hasLocalIp =
                        localIp != null
                                && !localIp.trim().isEmpty()
                                && !localIp.startsWith("127.");

                boolean connected =
                        NetworkUtil.isConnected(
                                this
                        );

                if (factory && hasLocalIp) {

                    stableCount++;

                } else {

                    stableCount =
                            0;
                }

                Log.e(
                        TAG,
                        "wait factory wifi"
                                + ", connected="
                                + connected
                                + ", currentSsid="
                                + currentSsid
                                + ", factory="
                                + factory
                                + ", localIp="
                                + localIp
                                + ", hasLocalIp="
                                + hasLocalIp
                                + ", stableCount="
                                + stableCount
                                + ", retry="
                                + retry
                );

                /*
                 * OTA_FACTORY 是临时配网局域网，
                 * 不要求能访问互联网，只要求：
                 * 1. 当前 SSID 是 OTA_FACTORY
                 * 2. 已拿到局域网 IP
                 */
                if (stableCount >= 2) {
                    return true;
                }

            } catch (Throwable e) {

                Log.e(
                        TAG,
                        "wait factory wifi error",
                        e
                );
            }
        }

        return false;
    }

    private boolean waitTargetWifiConnected(
            int seconds
    ) {

        int retry =
                seconds;

        while (retry-- > 0) {

            try {

                Thread.sleep(
                        1000
                );

                boolean connected =
                        NetworkUtil.isConnected(
                                this
                        );

                boolean factory =
                        WifiManagerUtil.isConnectedToFactoryWifi(
                                this
                        );

                Log.e(
                        TAG,
                        "wait target wifi"
                                + ", connected="
                                + connected
                                + ", factory="
                                + factory
                                + ", retry="
                                + retry
                );

                /*
                 * 正式 WiFi 必须联网，并且不能是 OTA_FACTORY。
                 */
                if (connected && !factory) {
                    return true;
                }

            } catch (Throwable e) {

                Log.e(
                        TAG,
                        "wait target wifi error",
                        e
                );
            }
        }

        return false;
    }

    private void showQr(
            String url
    ) {

        try {

            Bitmap qr =
                    QrUtil.create(
                            url,
                            260
                    );

            runOnUiThread(() -> {

                if (qr != null &&
                        ivQr != null) {

                    ivQr.setImageBitmap(
                            qr
                    );
                }
            });

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "showQr fail",
                    e
            );
        }
    }

    private void stopConfigServer() {

        try {

            if (server != null) {

                server.stop();

                server =
                        null;

                Log.e(
                        TAG,
                        "config server stopped"
                );
            }

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "stop config server fail",
                    e
            );
        }
    }

    private void stopBatchReceiver() {

        try {

            if (batchReceiver != null) {

                batchReceiver.stop();

                batchReceiver =
                        null;
            }

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "stop batch receiver fail",
                    e
            );
        }
    }

    private void restartFactoryOrSingleMode(
            String reason
    ) {

        try {

            submitted =
                    false;

            Log.e(
                    TAG,
                    "restart factory or single mode: "
                            + reason
            );

            Toast.makeText(
                    this,
                    reason,
                    Toast.LENGTH_LONG
            ).show();

            stopConfigServer();

            stopBatchReceiver();

            startProvisionFlow();

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "restartFactoryOrSingleMode fail",
                    e
            );
        }
    }

    private void waitNetworkAndStartService() {

        int retry = 20;

        while (retry-- > 0) {

            try {

                Thread.sleep(2000);

                boolean connected =
                        NetworkUtil.isConnected(
                                this
                        );

                Log.e(
                        TAG,
                        "wait network connected="
                                + connected
                                + ", retry="
                                + retry
                );

                if (connected) {

                    mainHandler.post(
                            this::startServiceAndFinish
                    );

                    return;
                }

            } catch (Throwable e) {

                Log.e(TAG, "wait network error", e);
            }
        }

        Log.e(
                TAG,
                "network not connected after wifi submit, restart config mode"
        );

        mainHandler.post(
                () -> restartConfigMode(
                        "WiFi连接失败，请检查密码后重新配置"
                )
        );
    }

    private void restartConfigMode(
            String reason
    ) {

        try {

            submitted =
                    false;

            Log.e(
                    TAG,
                    "restart config mode: "
                            + reason
            );

            Toast.makeText(
                    this,
                    reason,
                    Toast.LENGTH_LONG
            ).show();

            try {

                if (server != null) {

                    server.stop();

                    server =
                            null;

                    Log.e(
                            TAG,
                            "old config server stopped before restart"
                    );
                }

            } catch (Throwable e) {

                Log.e(
                        TAG,
                        "stop old config server fail",
                        e
                );
            }

            startConfigServer();

            new Thread(() -> {

                try {

                    boolean result =
                            WifiManagerUtil.startHotspot(
                                    ConfigActivity.this
                            );

                    Log.e(
                            TAG,
                            "restart hotspot result="
                                    + result
                    );

                    if (!result) {
                        Log.e(
                                TAG,
                                "restart hotspot failed"
                        );
                        return;
                    }

                    Thread.sleep(
                            1500
                    );

                    String url =
                            WifiManagerUtil.getConfigUrl();

                    Log.e(
                            TAG,
                            "restart qr url="
                                    + url
                    );

                    Bitmap qr =
                            QrUtil.create(
                                    url,
                                    260
                            );

                    runOnUiThread(() -> {

                        if (qr != null &&
                                ivQr != null) {

                            ivQr.setImageBitmap(
                                    qr
                            );
                        }
                    });

                } catch (Throwable e) {

                    Log.e(
                            TAG,
                            "restart config mode fail",
                            e
                    );
                }

            }).start();

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "restartConfigMode error",
                    e
            );
        }
    }

    private void startServiceAndFinish() {

        try {

            Intent service =
                    new Intent(
                            this,
                            MainService.class
                    );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                startForegroundService(
                        service
                );

                Log.e(
                        TAG,
                        "startForegroundService after wifi config"
                );

            } else {

                startService(
                        service
                );

                Log.e(
                        TAG,
                        "startService after wifi config"
                );
            }

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "start service after wifi config fail",
                    e
            );
        }

        finish();
    }

    private void hideSystemUI() {

        try {

            getWindow()
                    .getDecorView()
                    .setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    );

        } catch (Throwable e) {

            Log.e(TAG, "hideSystemUI fail", e);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {

        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void setUiText(
            String title,
            String hint
    ) {

        mainHandler.post(() -> {

            try {

                if (tvTitle != null) {
                    tvTitle.setText(title);
                }

                if (tvHint != null) {
                    tvHint.setText(hint);
                }

            } catch (Throwable e) {
                Log.e(TAG, "setUiText fail", e);
            }
        });
    }

    @Override
    protected void onDestroy() {

        super.onDestroy();

        stopBatchReceiver();

        if (server != null) {

            try {
                server.stop();
            } catch (Throwable ignored) {
            }
            server =
                    null;
        }
    }
}