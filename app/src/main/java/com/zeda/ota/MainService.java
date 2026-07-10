package com.zeda.ota;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import java.io.File;
import androidx.core.app.NotificationCompat;

import android.os.Handler;
import android.provider.Settings;

public class MainService extends Service {
    private static final String TAG = "OTA_TEST";
    private static final String ACTION_CONTINUE_AFTER_CLAIM =
            "com.zeda.ota.ACTION_CONTINUE_AFTER_CLAIM";

    private boolean initialized = false;
    // 记录最后一次 init 启动时间，用于检测 init 线程是否卡住
    private volatile long lastInitTime = 0;

    private ClaimManager claimManager;

    private volatile boolean claiming = false;

    public static void continueAfterClaim(android.content.Context context) {

        Intent intent =
                new Intent(
                        context,
                        MainService.class
                );

        intent.setAction(
                ACTION_CONTINUE_AFTER_CLAIM
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            context.startForegroundService(
                    intent
            );

        } else {

            context.startService(
                    intent
            );
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.e(TAG, "onStartCommand called, flags=" + flags + ", startId=" + startId);

        if (intent != null &&
                ACTION_CONTINUE_AFTER_CLAIM.equals(intent.getAction())) {

            Log.e(TAG, "continue after claim");

            synchronized (this) {
                initialized = false;
            }

            try {
                startInitThread();
            } catch (Throwable e) {
                Log.e(TAG, "continue init fail", e);
            }

            return START_STICKY;
        }

        synchronized (this) {

            if (initialized) {
                long now = System.currentTimeMillis();
                // 如果 init 已经很久没有更新，认为可能卡住，允许重新初始化
                if (lastInitTime > 0 && now - lastInitTime > 30_000) {
                    Log.e(TAG, "initialized flag present but init stale, forcing re-init (lastInit=" + lastInitTime + ")");
                    initialized = false; // 允许后续重新初始化
                } else {
                    Log.e(TAG, "service already initialized");
                    return START_STICKY;
                }
            }
            // 不立刻把 initialized 置为 true，等待 startForeground 和线程成功启动后再设置
        }

        try {

            // Android 8+ 前台服务必须立即进入前台
            Log.e(TAG, "creating notification channel");
            createNotificationChannel();

            Log.e(TAG, "building notification");
            NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(this, "OTA_CHANNEL")
                            .setContentTitle("OTA Service")
                            .setContentText("Running OTA updates")
                            .setSmallIcon(R.drawable.ic_launcher_foreground);

            Log.e(TAG, "calling startForeground");
            startForeground(1, builder.build());

            Log.e(TAG, "startForeground success, starting init thread");

            startInitThread();

        } catch (Exception e) {

            initialized = false;

            Log.e(TAG, "start service err", e);
            e.printStackTrace();
        }

        return START_STICKY;
    }

    private void startInitThread() {

        lastInitTime =
                System.currentTimeMillis();

        Thread t =
                new Thread(() -> {

                    try {

                        init();

                    } catch (Throwable e) {

                        initialized = false;

                        Log.e(
                                TAG,
                                "init thread error",
                                e
                        );
                    }
                });

        t.start();

        synchronized (this) {
            initialized = true;
        }
    }

    private void init() {
        // 每次 init 开始都更新活动时间戳，供 onStartCommand 检测是否卡住
        lastInitTime = System.currentTimeMillis();
        try {
            Log.e(TAG, "service init thread started");

            // 网络检查
            Log.e(TAG, "checking network...");

            if (!waitNormalNetworkStable(30)) {

                Log.e(TAG, "network not stable, stopping service");

                initialized =
                        false;

                stopSelf();

                return;
            }

            Log.e(TAG, "network connected");

            if (WifiManagerUtil.isConnectedToFactoryWifi(this)) {

                Log.e(
                        TAG,
                        "connected to OTA_FACTORY, should stay in config mode, stop service"
                );

                initialized =
                        false;

                stopSelf();

                return;
            }

            if (!ClaimStore.isClaimed(this)) {

                Log.e(
                        TAG,
                        "device not claimed, start auto claim in background"
                );

                startAutoClaim();

                return;
            }

            Log.e(
                    TAG,
                    "device already claimed"
            );

            ///////////////////////////////////////////////////////////////////////////////
            // MQTT 连接（自动重连 + 回调）
            Log.e(TAG, "connecting to mqtt...");
            try {
                MqttManager.get(getApplicationContext()).connect();
                Log.e(TAG, "mqtt connect initiated");
            } catch (Throwable mqttError) {
                Log.e(TAG, "mqtt connect failed", mqttError);
                // 继续执行，不中断服务
            }

            // SchedulerManager 启动（日志上传/定时任务）
            Log.e(TAG, "starting scheduler manager...");

            try {
                SchedulerManager.start(this);
                Log.e(TAG, "scheduler manager started");
            } catch (Throwable schedulerError) {
                Log.e(TAG, "scheduler manager start failed", schedulerError);
                // 继续执行，不中断服务
            }

            Log.e(TAG, "waiting for server push (3 seconds)...");
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Log.e(TAG, "init sleep interrupted", ie);
            }

            // 检查 pending 更新
            Log.e(TAG, "checking pending updates...");
            boolean pendingHandled = checkAndResumePendingGameUpdate();

            if (pendingHandled) {
                Log.e(TAG, "pending update has been handled, keep mqtt running");
                return;
            }

            // 正常启动主APP
            Log.e(TAG, "launching main app");
            try {
                PackageUtil.launchMainApp(this);
                Log.e(TAG, "main app launch completed");
            } catch (Throwable launchError) {
                Log.e(TAG, "failed to launch main app", launchError);
            }

        } catch (Throwable e) {
            initialized = false;
            Log.e(TAG, "service init fatal error", e);
            e.printStackTrace();
        }
    }


    private boolean checkAndResumePendingGameUpdate() {

        String pending = null;

        try {
            pending = PendingStore.get(this, "game_path");
        } catch (Throwable storeError) {
            Log.e(TAG, "pending store get error", storeError);
        }

        if (pending == null || pending.isEmpty()) {
            Log.e(TAG, "no pending updates found");
            return false;
        }

        Log.e(TAG, "found pending update at: " + pending);

        File apk = new File(pending);
        String pendingVersion = "";
        String currentVersion = "";
        String messageId = PendingStore.get(this, "game_msg");
        long recordId = 0;
        long taskId = 0;

        try {
            pendingVersion = PendingStore.get(this, "game_version");
            currentVersion = PackageUtil.getVersion(this, "com.zeda");

            String taskJson = PendingStore.getTask(this, "game");
            if (taskJson != null && !taskJson.isEmpty()) {
                org.json.JSONObject obj = new org.json.JSONObject(taskJson);
                recordId = obj.optLong("recordId", 0);
                taskId = obj.optLong("taskId", 0);
                if (messageId == null || messageId.isEmpty()) {
                    messageId = obj.optString("messageId", "");
                }
            }
        } catch (Throwable versionError) {
            Log.e(TAG, "version check error", versionError);
        }

        Log.e(TAG, "pendingVersion=" + pendingVersion + " currentVersion=" + currentVersion);

        if (!apk.exists()) {

            Log.e(TAG, "pending apk missing, clearing");

            StatusReporter.report(
                    this,
                    messageId,
                    "failed",
                    0,
                    currentVersion,
                    pendingVersion,
                    recordId,
                    taskId,
                    "game",
                    "APK_FILE_MISSING",
                    "重启恢复失败：本地待安装APK不存在"
            );

            PendingStore.clearTask(this, "game");
            PendingStore.clearApk(this);
            DeviceReportManager.get(this)
                    .setRunningStatusAndReport(DeviceReportManager.STATUS_IDLE);
            return false;
        }

        if (pendingVersion != null && pendingVersion.equals(currentVersion)) {

            Log.e(TAG, "pending already installed, clearing");

            StatusReporter.report(
                    this,
                    messageId,
                    "success",
                    100,
                    currentVersion,
                    pendingVersion,
                    recordId,
                    taskId,
                    "game",
                    null,
                    null
            );

            PendingStore.clearTask(this, "game");
            PendingStore.clearApk(this);

            if (apk.exists()) {
                apk.delete();
            }

            DeviceReportManager.get(this)
                    .setRunningStatusAndReport(DeviceReportManager.STATUS_IDLE);
            return false;
        }

        Log.e(TAG, "resuming pending game install");

        DeviceReportManager.get(this)
                .setRunningStatusAndReport(DeviceReportManager.STATUS_UPGRADING);

        InstallManager.install(
                this,
                apk,
                pendingVersion,
                messageId,
                recordId,
                taskId,
                "game",
                null
        );

        return true;
    }

    private boolean waitNormalNetworkStable(
            int seconds
    ) {

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

                boolean factory =
                        false;

                if (connected) {
                    factory =
                            WifiManagerUtil.isConnectedToFactoryWifi(
                                    this
                            );
                }

                if (connected && !factory) {

                    stableCount++;

                } else {

                    stableCount =
                            0;
                }

                Log.e(
                        TAG,
                        "wait service network"
                                + ", connected="
                                + connected
                                + ", factory="
                                + factory
                                + ", stableCount="
                                + stableCount
                                + ", retry="
                                + retry
                );

                if (stableCount >= 3) {

                    return true;
                }

                Thread.sleep(
                        1000
                );

            } catch (Throwable e) {

                Log.e(
                        TAG,
                        "wait service network error",
                        e
                );
            }
        }

        return false;
    }

    private void startAutoClaim() {

        if (claiming) {

            Log.e(
                    TAG,
                    "auto claim already running"
            );

            return;
        }

        claiming = true;

        if (claimManager != null) {

            try {
                claimManager.stop();
            } catch (Throwable ignored) {
            }
        }

        claimManager =
                new ClaimManager();

        Log.e(
                TAG,
                "auto claim start"
        );

        claimManager.start(
                getApplicationContext(),
                new ClaimManager.Callback() {

                    @Override
                    public void onWaitingClaim(
                            String qrContent,
                            String claimCode
                    ) {

                        /*
                         * 自动激活版本不显示二维码/认领码界面。
                         * 如果服务器还没激活成功，就继续后台轮询。
                         */
                        Log.e(
                                TAG,
                                "auto claim waiting, claimCode="
                                        + claimCode
                        );
                    }

                    @Override
                    public void onActivated(
                            ActivationData data
                    ) {

                        Log.e(
                                TAG,
                                "auto claim activated, continue service"
                        );

                        claiming = false;

                        if (claimManager != null) {
                            try {
                                claimManager.stop();
                            } catch (Throwable ignored) {
                            }
                        }

                        /*
                         * ClaimManager 内部已经负责：
                         * 1. ClaimStore.saveClaimed()
                         * 2. MqttCredentialStore.save()
                         *
                         * 这里重新让 MainService 走完整流程：
                         * device already claimed
                         * -> connect mqtt
                         * -> scheduler
                         * -> pending check
                         * -> launch main app
                         */
                        MainService.continueAfterClaim(
                                getApplicationContext()
                        );
                    }

                    @Override
                    public void onError(
                            Exception e
                    ) {

                        claiming = false;

                        Log.e(
                                TAG,
                                "auto claim error",
                                e
                        );

                        /*
                         * 出错后允许下次 MainService 重新初始化时再试。
                         */
                        initialized = false;

                        new Handler(getMainLooper()).postDelayed(
                                () -> {
                                    try {
                                        startAutoClaim();
                                    } catch (Throwable retryError) {
                                        Log.e(TAG, "auto claim retry fail", retryError);
                                    }
                                },
                                10_000
                        );
                    }
                }
        );
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "OTA_CHANNEL",
                    "OTA Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("OTA service running running");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {

        super.onDestroy();

        initialized = false;
        claiming = false;

        if (claimManager != null) {
            try {
                claimManager.stop();
            } catch (Throwable ignored) {
            }
        }

        Log.e(
                TAG,
                "service destroyed (START_STICKY will restart it automatically)"
        );
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}