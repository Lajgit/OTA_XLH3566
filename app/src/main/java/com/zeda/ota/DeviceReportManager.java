package com.zeda.ota;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 新增上报：
 * 1. report/status：状态/IP/版本上报
 * 2. report/profile：硬件档案上报
 */
public class DeviceReportManager {

    private static final String TAG = "OTA_TEST";

    public static final int STATUS_IDLE = 0;
    public static final int STATUS_PLAYING = 1;
    public static final int STATUS_ERROR = 2;
    public static final int STATUS_MAINTAINING = 3;
    public static final int STATUS_UPGRADING = 4;

    private static final long STATUS_INTERVAL_MS =
            60L * 60L * 1000L;

    private static final long PROFILE_INTERVAL_MS =
            7L * 24L * 60L * 60L * 1000L;

    private static volatile DeviceReportManager instance;

    private final Context context;

    private ScheduledExecutorService executor;

    private ConnectivityManager.NetworkCallback networkCallback;

    private final Object networkStateLock =
            new Object();

    private NetworkState lastNetworkState;

    private volatile boolean started = false;

    private volatile int currentRunningStatus =
            STATUS_IDLE;

    private volatile boolean networkCheckPending = false;

    private DeviceReportManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static DeviceReportManager get(Context context) {

        if (instance == null) {
            synchronized (DeviceReportManager.class) {
                if (instance == null) {
                    instance = new DeviceReportManager(context);
                }
            }
        }

        return instance;
    }

    public synchronized void start() {

        if (started) {
            Log.e(TAG, "device report manager already started");
            return;
        }

        started = true;

        Log.e(TAG, "device report manager start");

        startSchedule();

        registerNetworkCallback();
    }

    public synchronized void stop() {

        started = false;

        try {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        } catch (Throwable e) {
            Log.e(TAG, "device report executor stop fail", e);
        }

        try {
            if (networkCallback != null) {

                ConnectivityManager cm =
                        (ConnectivityManager) context.getSystemService(
                                Context.CONNECTIVITY_SERVICE
                        );

                if (cm != null) {
                    cm.unregisterNetworkCallback(
                            networkCallback
                    );
                }

                networkCallback = null;
            }
        } catch (Throwable e) {
            Log.e(TAG, "unregister network callback fail", e);
        }
    }

    private void startSchedule() {

        executor =
                Executors.newSingleThreadScheduledExecutor();

        // 状态兜底：1小时1次
        executor.scheduleWithFixedDelay(
                () -> {
                    try {
                        reportStatus(currentRunningStatus);
                    } catch (Throwable e) {
                        Log.e(TAG, "scheduled status report fail", e);
                    }
                },
                1,
                1,
                TimeUnit.HOURS
        );

        // 硬件档案兜底：7天1次
        executor.scheduleWithFixedDelay(
                () -> {
                    try {
                        reportProfileIfNeeded(false);
                    } catch (Throwable e) {
                        Log.e(TAG, "scheduled profile report fail", e);
                    }
                },
                1,
                24,
                TimeUnit.HOURS
        );
    }

    private void registerNetworkCallback() {

        try {

            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(
                            Context.CONNECTIVITY_SERVICE
                    );

            if (cm == null) {
                Log.e(TAG, "ConnectivityManager null, skip network callback");
                return;
            }

            NetworkRequest request =
                    new NetworkRequest.Builder()
                            .addCapability(
                                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                            )
                            .build();

            networkCallback =
                    new ConnectivityManager.NetworkCallback() {

                        @Override
                        public void onAvailable(Network network) {
//                            Log.e(TAG, "network available");
                            reportStatusDelayIfNetworkChanged();
                        }

                        @Override
                        public void onLost(Network network) {
//                            Log.e(TAG, "network lost");
                            reportStatusDelayIfNetworkChanged();
                        }

                        @Override
                        public void onCapabilitiesChanged(
                                Network network,
                                NetworkCapabilities networkCapabilities
                        ) {
//                            Log.e(TAG, "network capabilities changed");
                            reportStatusDelayIfNetworkChanged();
                        }
                    };

            updateLastNetworkState(
                    getCurrentNetworkState()
            );

            cm.registerNetworkCallback(
                    request,
                    networkCallback
            );

            Log.e(TAG, "network callback registered");

        } catch (Throwable e) {

            Log.e(TAG, "register network callback fail", e);
        }
    }

    private void reportStatusDelayIfNetworkChanged() {

        synchronized (networkStateLock) {

            if (networkCheckPending) {
                return;
            }

            networkCheckPending = true;
        }

        new Thread(() -> {

            try {

                Thread.sleep(3000);

                NetworkState current =
                        getCurrentNetworkState();

                if (!markNetworkChanged(current)) {
                    return;
                }

                Log.e(
                        TAG,
                        "network ip/type changed, report status"
                                + ", privateIp="
                                + current.privateIp
                                + ", networkType="
                                + current.networkType
                );

                reportStatus(currentRunningStatus);

            } catch (Throwable e) {

                Log.e(TAG, "report status delay fail", e);

            } finally {

                synchronized (networkStateLock) {
                    networkCheckPending = false;
                }
            }

        }).start();
    }

    private boolean markNetworkChanged(
            NetworkState current
    ) {

        synchronized (networkStateLock) {

            if (lastNetworkState != null &&
                    lastNetworkState.equalsTo(current)) {

                return false;
            }

            lastNetworkState =
                    current;

            return true;
        }
    }

    private void updateLastNetworkState(
            NetworkState state
    ) {

        synchronized (networkStateLock) {
            lastNetworkState =
                    state;
        }
    }

    private NetworkState getCurrentNetworkState() {

        return new NetworkState(
                getPrivateIp(),
                getNetworkType()
        );
    }

    public int getRunningStatus() {
        return currentRunningStatus;
    }

    /**
     * 外部可以调用这个方法更新运行状态并立即上报。
     */
    public void setRunningStatusAndReport(int runningStatus) {

        currentRunningStatus =
                runningStatus;

        reportStatus(
                runningStatus
        );
    }

    /**
     * 状态上报：report/status
     */
    public void reportStatus(int runningStatus) {

        try {

            String deviceId =
                    DeviceUtil.getDeviceId(context);

            JSONObject json =
                    new JSONObject();

            json.put(
                    "runningStatus",
                    runningStatus
            );

            json.put(
                    "publicIp",
                    getPublicIp()
            );

            String privateIp =
                    getPrivateIp();

            String networkType =
                    getNetworkType();

            json.put(
                    "privateIp",
                    privateIp
            );

            json.put(
                    "networkType",
                    networkType
            );

            updateLastNetworkState(
                    new NetworkState(
                            privateIp,
                            networkType
                    )
            );

            /*
             * 当前设备端还没有局域网升级包下载服务，所以先上报空。
             * 如果后面增加 8088 下载网关，再改成：
             * http://privateIp:8088
             */
            json.put(
                    "privateHttpBaseUrl",
                    ""
            );

            json.put(
                    "apkVersion",
                    getPackageVersion(
                            "com.zeda"
                    )
            );

            json.put(
                    "apkVersionCode",
                    getPackageVersionCode(
                            "com.zeda"
                    )
            );

            json.put(
                    "firmwareVersion",
                    "1.0.0"
            );

            json.put(
                    "firmwareVersionCode",
                    Build.VERSION.SDK_INT
            );

            json.put(
                    "timestamp",
                    System.currentTimeMillis()
            );

            String topic =
                    MqttConfig.getStatusTopic(
                            deviceId
                    );

            boolean mqttConnected =
                    MqttManager.get(context)
                            .isConnected();

//            Log.e(TAG, "========== REPORT STATUS ==========");
            Log.e(TAG, "report status mqttConnected=" + mqttConnected);
            Log.e(TAG, "report status topic=" + topic);
            Log.e(TAG, "report status payload=" + json.toString());
//            Log.e(TAG, "===================================");

            if (!mqttConnected) {

                Log.e(
                        TAG,
                        "report status skipped: mqtt not connected"
                );

                return;
            }

            MqttManager.get(context)
                    .publish(
                            topic,
                            json.toString()
                    );

        } catch (Throwable e) {

            Log.e(TAG, "report status fail", e);
        }
    }

    /**
     * 硬件档案上报：
     * force=true 强制上报；
     * force=false 按 7 天兜底和硬件档案变化判断。
     */
    public void reportProfileIfNeeded(boolean force) {

        try {

            SharedPreferences sp =
                    context.getSharedPreferences(
                            "device_report",
                            Context.MODE_PRIVATE
                    );

            JSONObject baseProfile =
                    buildProfileJson(false);

            String profileHash =
                    baseProfile.toString();

            long now =
                    System.currentTimeMillis();

            long lastTime =
                    sp.getLong(
                            "last_profile_time",
                            0
                    );

            String lastHash =
                    sp.getString(
                            "last_profile_hash",
                            ""
                    );

            boolean expired =
                    now - lastTime >= PROFILE_INTERVAL_MS;

            boolean changed =
                    !profileHash.equals(lastHash);

            boolean neverReported =
                    lastTime <= 0;

            if (!force &&
                    !neverReported &&
                    !expired &&
                    !changed) {

//                Log.e(TAG, "========== REPORT PROFILE SKIP ==========");
                Log.e(TAG, "profile skipped reason=unchanged and not expired");
                Log.e(TAG, "force=" + force);
                Log.e(TAG, "neverReported=" + neverReported);
                Log.e(TAG, "expired=" + expired);
                Log.e(TAG, "changed=" + changed);
                Log.e(TAG, "lastTime=" + lastTime);
                Log.e(TAG, "profileHash=" + profileHash);
//                Log.e(TAG, "=========================================");

                return;
            }

            JSONObject json =
                    buildProfileJson(true);

            String deviceId =
                    DeviceUtil.getDeviceId(context);

            String topic =
                    MqttConfig.getProfileTopic(
                            deviceId
                    );

            boolean mqttConnected =
                    MqttManager.get(context)
                            .isConnected();

//            Log.e(TAG, "========== REPORT PROFILE ==========");
            Log.e(TAG, "report profile mqttConnected=" + mqttConnected);
            Log.e(TAG, "report profile force=" + force);
            Log.e(TAG, "report profile neverReported=" + neverReported);
            Log.e(TAG, "report profile expired=" + expired);
            Log.e(TAG, "report profile changed=" + changed);
            Log.e(TAG, "report profile topic=" + topic);
            Log.e(TAG, "report profile payload=" + json.toString());
//            Log.e(TAG, "====================================");

            if (!mqttConnected) {

                Log.e(
                        TAG,
                        "report profile skipped: mqtt not connected"
                );

                return;
            }

            MqttManager.get(context)
                    .publish(
                            topic,
                            json.toString()
                    );

            sp.edit()
                    .putLong(
                            "last_profile_time",
                            now
                    )
                    .putString(
                            "last_profile_hash",
                            profileHash
                    )
                    .apply();

        } catch (Throwable e) {

            Log.e(TAG, "report profile fail", e);
        }
    }

    private JSONObject buildProfileJson(boolean withTimestamp)
            throws Exception {

        JSONObject json =
                new JSONObject();

        json.put(
                "profileVersion",
                "1.0"
        );

        json.put(
                "cpuModel",
                getCpuModel()
        );

        json.put(
                "cpuCores",
                Runtime.getRuntime()
                        .availableProcessors()
        );

        json.put(
                "memoryMb",
                getMemoryMb()
        );

        json.put(
                "storageTotalMb",
                getStorageTotalMb()
        );

        json.put(
                "screenResolution",
                getScreenResolution()
        );

        json.put(
                "androidVersion",
                Build.VERSION.RELEASE == null
                        ? ""
                        : Build.VERSION.RELEASE
        );

        json.put(
                "boardModel",
                getBoardModel()
        );

        json.put(
                "macAddress",
                getMacAddress()
        );

        json.put(
                "serialNo",
                getSerialNo()
        );

        json.put(
                "vendor",
                getVendor()
        );

        if (withTimestamp) {

            json.put(
                    "timestamp",
                    System.currentTimeMillis()
            );
        }

        return json;
    }

    private String getPackageVersion(String packageName) {

        try {

            PackageManager pm =
                    context.getPackageManager();

            PackageInfo info =
                    pm.getPackageInfo(
                            packageName,
                            0
                    );

            return info.versionName == null
                    ? ""
                    : info.versionName;

        } catch (Throwable e) {

            if (!context.getPackageName().equals(packageName)) {
                return getPackageVersion(
                        context.getPackageName()
                );
            }

            return "";
        }
    }

    private long getPackageVersionCode(String packageName) {

        try {

            PackageManager pm =
                    context.getPackageManager();

            PackageInfo info =
                    pm.getPackageInfo(
                            packageName,
                            0
                    );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }

            return info.versionCode;

        } catch (Throwable e) {

            if (!context.getPackageName().equals(packageName)) {
                return getPackageVersionCode(
                        context.getPackageName()
                );
            }

            return 0;
        }
    }

    private String getPublicIp() {

        HttpURLConnection conn = null;
        InputStream inputStream = null;
        BufferedReader reader = null;

        try {

            URL url =
                    new URL(
                            "http://ipinfo.io/ip"
                    );

            conn =
                    (HttpURLConnection) url.openConnection();

            conn.setRequestMethod(
                    "GET"
            );

            conn.setConnectTimeout(
                    3000
            );

            conn.setReadTimeout(
                    3000
            );

            conn.setUseCaches(
                    false
            );

            conn.setRequestProperty(
                    "User-Agent",
                    "curl/7.79.1"
            );

            int code =
                    conn.getResponseCode();

            if (code != 200) {

                Log.e(
                        TAG,
                        "getPublicIp http code="
                                + code
                );

                return "";
            }

            inputStream =
                    conn.getInputStream();

            reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    inputStream
                            )
                    );

            String ip =
                    reader.readLine();

            if (ip == null) {
                return "";
            }

            ip =
                    ip.trim();

            Log.e(
                    TAG,
                    "publicIp="
                            + ip
            );

            return ip;

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "getPublicIp fail",
                    e
            );

            return "";

        } finally {

            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Throwable ignored) {
            }

            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Throwable ignored) {
            }

            try {
                if (conn != null) {
                    conn.disconnect();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private String getPrivateIp() {

        try {

            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {

                NetworkInterface ni =
                        interfaces.nextElement();

                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }

                Enumeration<InetAddress> addrs =
                        ni.getInetAddresses();

                while (addrs.hasMoreElements()) {

                    InetAddress addr =
                            addrs.nextElement();

                    if (addr instanceof Inet4Address &&
                            !addr.isLoopbackAddress()) {

                        String ip =
                                addr.getHostAddress();

                        if (ip != null &&
                                !ip.startsWith("127.")) {

                            return ip;
                        }
                    }
                }
            }

        } catch (Throwable e) {

            Log.e(TAG, "getPrivateIp fail", e);
        }

        return "";
    }

    private String getNetworkType() {

        try {

            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(
                            Context.CONNECTIVITY_SERVICE
                    );

            if (cm == null) {
                return "unknown";
            }

            Network network =
                    cm.getActiveNetwork();

            if (network == null) {
                return "unknown";
            }

            NetworkCapabilities caps =
                    cm.getNetworkCapabilities(
                            network
                    );

            if (caps == null) {
                return "unknown";
            }

            if (caps.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI
            )) {
                return "wifi";
            }

            if (caps.hasTransport(
                    NetworkCapabilities.TRANSPORT_ETHERNET
            )) {
                return "ethernet";
            }

            if (caps.hasTransport(
                    NetworkCapabilities.TRANSPORT_CELLULAR
            )) {
                return "4g";
            }

        } catch (Throwable e) {

            Log.e(TAG, "getNetworkType fail", e);
        }

        return "unknown";
    }

    private String getCpuModel() {

        /*
         * 先从系统属性里判断。
         * 你的 boardModel 已经是 rk3566_r/rk30sdk，
         * 所以这里可以稳定识别 RK3566。
         */
        String buildText =
                (
                        safe(Build.MODEL)
                                + " "
                                + safe(Build.BOARD)
                                + " "
                                + safe(Build.HARDWARE)
                                + " "
                                + safe(Build.DEVICE)
                                + " "
                                + safe(Build.PRODUCT)
                ).toLowerCase();

        if (buildText.contains("rk3566")) {
            return "RK3566";
        }

        if (buildText.contains("rk3568")) {
            return "RK3568";
        }

        if (buildText.contains("rk3588")) {
            return "RK3588";
        }

        if (buildText.contains("rk3562")) {
            return "RK3562";
        }

        BufferedReader reader = null;

        try {

            reader =
                    new BufferedReader(
                            new FileReader(
                                    "/proc/cpuinfo"
                            )
                    );

            String line;

            String hardwareValue = "";
            String modelNameValue = "";

            while ((line = reader.readLine()) != null) {

                String lower =
                        line.toLowerCase();

                int index =
                        line.indexOf(":");

                if (index < 0 ||
                        index + 1 >= line.length()) {
                    continue;
                }

                String value =
                        line.substring(
                                index + 1
                        ).trim();

                if (value.isEmpty()) {
                    continue;
                }

                /*
                 * 注意：
                 * 不要再用 startsWith("processor")。
                 * Android /proc/cpuinfo 里经常有：
                 * processor : 0
                 * processor : 1
                 * 这不是 CPU 型号。
                 */
                if (lower.startsWith("hardware")) {
                    hardwareValue = value;
                } else if (lower.startsWith("model name")) {
                    modelNameValue = value;
                }
            }

            if (!hardwareValue.isEmpty() &&
                    !isPureNumber(hardwareValue)) {

                String normalized =
                        normalizeCpuModel(
                                hardwareValue
                        );

                if (!normalized.isEmpty()) {
                    return normalized;
                }
            }

            if (!modelNameValue.isEmpty() &&
                    !isPureNumber(modelNameValue)) {

                String normalized =
                        normalizeCpuModel(
                                modelNameValue
                        );

                if (!normalized.isEmpty()) {
                    return normalized;
                }
            }

        } catch (Throwable e) {

            Log.e(TAG, "getCpuModel fail", e);

        } finally {

            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Throwable ignored) {
            }
        }

        if (!safe(Build.HARDWARE).isEmpty() &&
                !isPureNumber(Build.HARDWARE)) {
            return safe(Build.HARDWARE).toUpperCase(Locale.US);
        }

        if (!safe(Build.BOARD).isEmpty() &&
                !isPureNumber(Build.BOARD)) {
            return safe(Build.BOARD).toUpperCase(Locale.US);
        }

        return "UNKNOWN";
    }

    private String safe(String s) {

        return s == null
                ? ""
                : s.trim();
    }

    private boolean isPureNumber(String s) {

        if (s == null) {
            return true;
        }

        return s.trim()
                .matches(
                        "^\\d+$"
                );
    }

    private String normalizeCpuModel(String value) {

        if (value == null) {
            return "";
        }

        String text =
                value.trim();

        String lower =
                text.toLowerCase();

        if (lower.contains("rk3566")) {
            return "RK3566";
        }

        if (lower.contains("rk3568")) {
            return "RK3568";
        }

        if (lower.contains("rk3588")) {
            return "RK3588";
        }

        if (lower.contains("rk3562")) {
            return "RK3562";
        }

        if (isPureNumber(text)) {
            return "";
        }

        return text.toUpperCase(Locale.US);
    }

    private long getMemoryMb() {

        try {

            ActivityManager am =
                    (ActivityManager) context.getSystemService(
                            Context.ACTIVITY_SERVICE
                    );

            if (am == null) {
                return 0;
            }

            ActivityManager.MemoryInfo info =
                    new ActivityManager.MemoryInfo();

            am.getMemoryInfo(
                    info
            );

            return info.totalMem / 1024 / 1024;

        } catch (Throwable e) {

            Log.e(TAG, "getMemoryMb fail", e);
            return 0;
        }
    }

    private long getStorageTotalMb() {

        try {

            String[] blockDevices =
                    new String[]{
                            "/sys/block/mmcblk0/size",
                            "/sys/block/mmcblk1/size",
                            "/sys/block/sda/size",
                            "/sys/block/vda/size"
                    };

            long maxBytes =
                    0;

            for (String path : blockDevices) {

                long sectors =
                        readLongFromFile(
                                path
                        );

                if (sectors > 0) {

                    long bytes =
                            sectors * 512L;

                    if (bytes > maxBytes) {
                        maxBytes = bytes;
                    }

                    Log.e(
                            TAG,
                            "storage block path="
                                    + path
                                    + ", sectors="
                                    + sectors
                                    + ", mb="
                                    + bytes / 1024 / 1024
                    );
                }
            }

            if (maxBytes > 0) {

                return maxBytes / 1024 / 1024;
            }

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "getStorageTotalMb block fail",
                    e
            );
        }

        try {

            StatFs statFs =
                    new StatFs(
                            Environment.getDataDirectory()
                                    .getAbsolutePath()
                    );

            long bytes =
                    statFs.getBlockSizeLong()
                            * statFs.getBlockCountLong();

            return bytes / 1024 / 1024;

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "getStorageTotalMb fallback fail",
                    e
            );

            return 0;
        }
    }

    private long readLongFromFile(
            String path
    ) {

        BufferedReader reader = null;

        try {

            File file =
                    new File(
                            path
                    );

            if (!file.exists()) {
                return 0;
            }

            reader =
                    new BufferedReader(
                            new FileReader(
                                    file
                            )
                    );

            String line =
                    reader.readLine();

            if (line == null) {
                return 0;
            }

            return Long.parseLong(
                    line.trim()
            );

        } catch (Throwable e) {

            return 0;

        } finally {

            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private String getScreenResolution() {

        try {

            WindowManager wm =
                    (WindowManager) context.getSystemService(
                            Context.WINDOW_SERVICE
                    );

            if (wm == null) {
                return "";
            }

            Display display =
                    wm.getDefaultDisplay();

            DisplayMetrics metrics =
                    new DisplayMetrics();

            display.getRealMetrics(
                    metrics
            );

            return metrics.widthPixels
                    + "x"
                    + metrics.heightPixels;

        } catch (Throwable e) {

            Log.e(TAG, "getScreenResolution fail", e);
            return "";
        }
    }

    private String getBoardModel() {

        String model =
                Build.MODEL == null
                        ? ""
                        : Build.MODEL;

        String board =
                Build.BOARD == null
                        ? ""
                        : Build.BOARD;

        if (!model.isEmpty() &&
                !board.isEmpty()) {

            return model + "/" + board;
        }

        if (!model.isEmpty()) {
            return model;
        }

        return board;
    }

    private String getMacAddress() {

        try {

            String[] sysPaths =
                    new String[]{
                            "/sys/class/net/wlan0/address",
                            "/sys/class/net/eth0/address",
                            "/sys/class/net/p2p0/address",
                            "/sys/class/net/usb0/address"
                    };

            for (String path : sysPaths) {

                String mac =
                        readFirstLine(
                                path
                        );

                mac =
                        normalizeMac(
                                mac
                        );

                if (!mac.isEmpty()) {

                    Log.e(
                            TAG,
                            "mac from "
                                    + path
                                    + " = "
                                    + mac
                    );

                    return mac;
                }
            }

            String[] names =
                    new String[]{
                            "wlan0",
                            "eth0",
                            "p2p0",
                            "usb0"
                    };

            for (String name : names) {

                NetworkInterface ni =
                        NetworkInterface.getByName(
                                name
                        );

                String mac =
                        formatMac(
                                ni
                        );

                mac =
                        normalizeMac(
                                mac
                        );

                if (!mac.isEmpty()) {

                    Log.e(
                            TAG,
                            "mac from interface "
                                    + name
                                    + " = "
                                    + mac
                    );

                    return mac;
                }
            }

            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {

                NetworkInterface ni =
                        interfaces.nextElement();

                if (ni == null ||
                        ni.isLoopback()) {
                    continue;
                }

                String mac =
                        formatMac(
                                ni
                        );

                mac =
                        normalizeMac(
                                mac
                        );

                if (!mac.isEmpty()) {

                    Log.e(
                            TAG,
                            "mac from interface enum "
                                    + ni.getName()
                                    + " = "
                                    + mac
                    );

                    return mac;
                }
            }

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "getMacAddress fail",
                    e
            );
        }

        return "";
    }

    private String readFirstLine(
            String path
    ) {

        BufferedReader reader = null;

        try {

            File file =
                    new File(
                            path
                    );

            if (!file.exists()) {
                return "";
            }

            reader =
                    new BufferedReader(
                            new FileReader(
                                    file
                            )
                    );

            String line =
                    reader.readLine();

            return line == null
                    ? ""
                    : line.trim();

        } catch (Throwable e) {

            return "";

        } finally {

            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private String normalizeMac(
            String mac
    ) {

        if (mac == null) {
            return "";
        }

        mac =
                mac.trim()
                        .toUpperCase(
                                Locale.US
                        );

        if (mac.isEmpty()) {
            return "";
        }

        if ("00:00:00:00:00:00".equals(mac)) {
            return "";
        }

        if ("02:00:00:00:00:00".equals(mac)) {
            return "";
        }

        if (!mac.matches(
                "^[0-9A-F]{2}(:[0-9A-F]{2}){5}$"
        )) {
            return "";
        }

        return mac;
    }

    private String formatMac(
            NetworkInterface ni
    ) {

        try {

            if (ni == null) {
                return "";
            }

            byte[] bytes =
                    ni.getHardwareAddress();

            if (bytes == null ||
                    bytes.length == 0) {
                return "";
            }

            StringBuilder sb =
                    new StringBuilder();

            for (int i = 0; i < bytes.length; i++) {

                if (i > 0) {
                    sb.append(":");
                }

                sb.append(
                        String.format(
                                Locale.US,
                                "%02X",
                                bytes[i]
                        )
                );
            }

            return sb.toString();

        } catch (Throwable e) {

            return "";
        }
    }

    private String getSerialNo() {

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                String serial =
                        Build.getSerial();

                if (serial != null &&
                        !serial.trim().isEmpty() &&
                        !"unknown".equalsIgnoreCase(serial)) {

                    return serial;
                }
            }

        } catch (Throwable ignored) {
        }

        try {

            String serial =
                    Build.SERIAL;

            if (serial != null &&
                    !serial.trim().isEmpty() &&
                    !"unknown".equalsIgnoreCase(serial)) {

                return serial;
            }

        } catch (Throwable ignored) {
        }

        try {

            String androidId =
                    Settings.Secure.getString(
                            context.getContentResolver(),
                            Settings.Secure.ANDROID_ID
                    );

            return androidId == null
                    ? ""
                    : androidId.toUpperCase();

        } catch (Throwable e) {

            Log.e(TAG, "getSerialNo fail", e);
            return "";
        }
    }

    private String getVendor() {

        String manufacturer =
                Build.MANUFACTURER == null
                        ? ""
                        : Build.MANUFACTURER;

        if (!manufacturer.isEmpty()) {
            return manufacturer;
        }

        return "ZEDA";
    }

    private static class NetworkState {

        final String privateIp;

        final String networkType;

        NetworkState(
                String privateIp,
                String networkType
        ) {

            this.privateIp =
                    privateIp == null
                            ? ""
                            : privateIp;

            this.networkType =
                    networkType == null
                            ? ""
                            : networkType;
        }

        boolean equalsTo(
                NetworkState other
        ) {

            if (other == null) {
                return false;
            }

            return privateIp.equals(other.privateIp) &&
                    networkType.equals(other.networkType);
        }
    }
}
