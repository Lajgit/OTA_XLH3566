package com.zeda.ota;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.provider.Settings;

public class NetworkHelper {

    public static void openWifiAndQuickConnect(Context context) {

        try {

            WifiManager wifi =
                    (WifiManager) context.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);

            if (wifi != null && !wifi.isWifiEnabled()) {
                wifi.setWifiEnabled(true);
                Thread.sleep(2000);
            }

            // 优先打开全志 Quick connect（需要 AOSP 修改后才稳定）
            try {
                Intent quick = new Intent();
                quick.setComponent(new ComponentName(
                        "com.android.settings",
                        "com.android.settings.zeda.QuickConnectEntryActivity"
                ));
                quick.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(quick);
                return;
            } catch (Exception ignored) {
            }

            // 回退：打开普通 Wi-Fi 设置
            Intent wifiIntent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            wifiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(wifiIntent);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}