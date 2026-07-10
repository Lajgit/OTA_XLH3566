package com.zeda.ota;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.os.Build;
import android.os.Environment;

public class MainActivity extends Activity {

    private static final String TAG = "OTA_TEST";
    private boolean serviceStarted  = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

            if (!Environment.isExternalStorageManager()) {

                Intent intent =
                        new Intent(this, ConfigActivity.class);

                startActivity(intent);

                finish();

                return;
            }
        }


        showWaitingUi();
        checkAndStart();
    }

    private void showWaitingUi() {

        android.widget.TextView textView =
                new android.widget.TextView(this);

        textView.setText(
                "正在启动设备服务...\n\n正在连接已保存的 WiFi，请稍后..."
        );

        textView.setTextSize(28);
        textView.setTextColor(android.graphics.Color.rgb(40, 40, 40));
        textView.setGravity(android.view.Gravity.CENTER);
        textView.setBackgroundColor(android.graphics.Color.WHITE);

        setContentView(textView);
    }

    private void checkAndStart() {

        Log.e(TAG, "MainActivity start");

        /*
         * 如果没有保存过正式 WiFi，不要在 MainActivity 空等 300 秒。
         * 直接进入 ConfigActivity，由 ConfigActivity 显示：
         * 正在搜索 OTA_FACTORY / 开启 OTA_CONFIG_xxx。
         */
        if (!WifiCredentialStore.hasSavedWifi(this)) {

            Log.e(TAG, "no saved wifi, open config page directly");

            Intent intent =
                    new Intent(
                            this,
                            ConfigActivity.class
                    );

            startActivity(
                    intent
            );

            finish();

            return;
        }

        /*
         * 只有保存过正式 WiFi，才等待系统/程序连接上次的 WiFi。
         */
        new Thread(() -> {

            boolean normalNetworkConnected =
                    waitNormalNetworkConnected(
                            300
                    );

            runOnUiThread(() -> {

                Log.e(
                        TAG,
                        "normalNetworkConnected="
                                + normalNetworkConnected
                );

                if (!normalNetworkConnected) {

                    Log.e(
                            TAG,
                            "normal network not connected, open config page"
                    );

                    Intent intent =
                            new Intent(
                                    this,
                                    ConfigActivity.class
                            );

                    startActivity(
                            intent
                    );

                    finish();

                    return;
                }

                startOtaService();
            });

        }).start();
    }

    private boolean waitNormalNetworkConnected(
            int seconds
    ) {

        boolean hasSavedWifi =
                WifiCredentialStore.hasSavedWifi(
                        this
                );

        String savedSsid =
                WifiCredentialStore.getSsid(
                        this
                );

        String savedPassword =
                WifiCredentialStore.getPassword(
                        this
                );

        Log.e(
                TAG,
                "wait normal network start"
                        + ", hasSavedWifi="
                        + hasSavedWifi
                        + ", savedSsid="
                        + savedSsid
        );

        long lastConnectSavedTime =
                0;

        if (hasSavedWifi) {

            Log.e(
                    TAG,
                    "try connect saved wifi first, ssid="
                            + savedSsid
            );

            WifiManagerUtil.connectWifi(
                    this,
                    savedSsid,
                    savedPassword
            );

            lastConnectSavedTime =
                    System.currentTimeMillis();
        }

        int retry =
                seconds;

        int stableCount =
                0;

        while (retry-- > 0) {

            try {

                boolean connected =
                        NetworkUtil.isConnected(
                                this
                        );

                String currentSsid =
                        WifiManagerUtil.getCurrentSsid(
                                this
                        );

                boolean factory =
                        ProvisionConfig.FACTORY_WIFI_SSID.equals(
                                currentSsid
                        );

                boolean normalNetwork =
                        connected && !factory;

                if (normalNetwork) {

                    stableCount++;

                } else {

                    stableCount =
                            0;
                }

                Log.e(
                        TAG,
                        "wait normal network"
                                + ", connected="
                                + connected
                                + ", currentSsid="
                                + currentSsid
                                + ", factory="
                                + factory
                                + ", hasSavedWifi="
                                + hasSavedWifi
                                + ", savedSsid="
                                + savedSsid
                                + ", stableCount="
                                + stableCount
                                + ", retry="
                                + retry
                );

                if (stableCount >= 5) {

                    return true;
                }

                if (hasSavedWifi) {

                    long now =
                            System.currentTimeMillis();

                    if (now - lastConnectSavedTime > 10_000) {

                        if (!savedSsid.equals(currentSsid) ||
                                !connected) {

                            Log.e(
                                    TAG,
                                    "retry connect saved wifi"
                                            + ", ssid="
                                            + savedSsid
                                            + ", currentSsid="
                                            + currentSsid
                                            + ", connected="
                                            + connected
                            );

                            WifiManagerUtil.connectWifi(
                                    this,
                                    savedSsid,
                                    savedPassword
                            );

                            lastConnectSavedTime =
                                    now;
                        }
                    }
                }

                if (!hasSavedWifi &&
                        connected &&
                        factory) {

                    Log.e(
                            TAG,
                            "no saved wifi and connected to OTA_FACTORY"
                    );

                    return false;
                }

                Thread.sleep(
                        1000
                );

            } catch (Throwable e) {

                Log.e(
                        TAG,
                        "wait normal network error",
                        e
                );
            }
        }

        return false;
    }

    private void startOtaService() {
        if (serviceStarted) {
            Log.e(TAG, "service already started");
            finish();
            return;
        }
        serviceStarted = true;

        try {

            Intent service = new Intent(this, MainService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                startForegroundService(service);
                Log.e(TAG, "startForegroundService");

            } else {

                startService(service);
                Log.e(TAG, "startService");
            }

            Log.e(TAG, "service start command sent");

            // 服务需要充分的时间初始化，不要立刻 finish
            // 延迟更长时间后再 finish，给服务充分的初始化时间
            new android.os.Handler().postDelayed(() -> {
                Log.e(TAG, "MainActivity finishing");
                finish();
            }, 2000);

        } catch (Exception e) {
            serviceStarted = false;
            Log.e(TAG, "start service failed", e);
//            e.printStackTrace();
        }
    }
}