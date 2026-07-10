package com.zeda.ota;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public class MainAppGuard {

    private static final String TAG = "OTA_TEST";

    private static final String MAIN_PACKAGE = "com.zeda";

    /*
     * 防止主程序连续闪退时，每分钟心跳都疯狂拉起。
     * 这里设置为 60 秒，和你的心跳周期一致。
     */
    private static final long MIN_RESTART_INTERVAL_MS =
            60L * 1000L;

    private static volatile boolean checking = false;

    public static void checkAndRestartIfNeeded(Context context) {

        if (context == null) {
            return;
        }

        Context appContext =
                context.getApplicationContext();

        if (checking) {
            return;
        }

        checking = true;

        new Thread(() -> {

            try {

                /*
                 * 设备还没认领，不拉起主程序。
                 * 避免还在激活/配网流程时打开游戏。
                 */
                if (!ClaimStore.isClaimed(appContext)) {

                    Log.e(TAG, "main guard skip: device not claimed");

                    return;
                }

                /*
                 * 连接 OTA_FACTORY 时说明还在配网/配置模式，不拉起主程序。
                 */
                if (WifiManagerUtil.isConnectedToFactoryWifi(appContext)) {

                    Log.e(TAG, "main guard skip: factory wifi/config mode");

                    return;
                }

                /*
                 * 正在升级时不要拉起主程序，避免干扰安装。
                 */
                int runningStatus =
                        DeviceReportManager.get(appContext)
                                .getRunningStatus();

                if (runningStatus == DeviceReportManager.STATUS_UPGRADING) {

                    Log.e(TAG, "main guard skip: upgrading");

                    return;
                }

                /*
                 * 如果有待安装的主程序 APK，不拉起旧主程序。
                 */
                String pendingGamePath =
                        PendingStore.get(appContext, "game_path");

                if (pendingGamePath != null &&
                        !pendingGamePath.trim().isEmpty()) {

                    Log.e(TAG, "main guard skip: pending game update exists");

                    return;
                }

//                PackageUtil.debugIsRunning(
//                        appContext,
//                        MAIN_PACKAGE
//                );

                boolean running =
                        PackageUtil.isRunning(
                                appContext,
                                MAIN_PACKAGE
                        );

                Log.e(TAG, "main guard check, running=" + running);

                if (running) {
                    return;
                }

                long now =
                        System.currentTimeMillis();

                SharedPreferences sp =
                        appContext.getSharedPreferences(
                                "main_app_guard",
                                Context.MODE_PRIVATE
                        );

                long lastRestartTime =
                        sp.getLong(
                                "last_restart_time",
                                0
                        );

                if (now - lastRestartTime < MIN_RESTART_INTERVAL_MS) {

                    Log.e(TAG, "main guard skip: restart too frequent");

                    return;
                }

                sp.edit()
                        .putLong(
                                "last_restart_time",
                                now
                        )
                        .apply();

                Log.e(TAG, "main app not running, restart now");

                PackageUtil.launchMainApp(appContext);

            } catch (Throwable e) {

                Log.e(TAG, "main guard check fail", e);

            } finally {

                checking = false;
            }

        }).start();
    }
}