package com.zeda.ota;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;

public class WifiManagerUtil {

    private static final String TAG = "OTA_TEST";

    private static final int TETHERING_WIFI = 0;

    /*
     * SoftApConfiguration.SECURITY_TYPE_WPA2_PSK = 1
     * 这里保持你在 RK3566 Android 13 上验证通过的反射写法。
     */
    private static final int SOFT_AP_SECURITY_TYPE_WPA2_PSK = 1;

    /*
     * 兼容旧代码常量。实际单机配网热点名仍使用 getHotspotSsid(context)，
     * 也就是 ProvisionConfig.SINGLE_AP_PREFIX + 设备号后 6 位。
     */
    public static final String HOTSPOT_SSID = "OTA_CONFIG";
    public static final String HOTSPOT_PASSWORD = "12345678";

    private static volatile boolean hotspotStarting = false;
    private static volatile Context lastAppContext = null;

    public static String getConfigUrl() {
        return "http://" + getHotspotIpAddress() + ":8080";
    }

    public static synchronized boolean startHotspot(Context context) {
        if (context == null) {
            Log.e(TAG, "startHotspot fail: context null");
            return false;
        }

        Context appContext =
                context.getApplicationContext();

        lastAppContext =
                appContext;

        String hotspotSsid =
                getHotspotSsid(
                        appContext
                );

        return startHotspot(
                appContext,
                hotspotSsid,
                ProvisionConfig.SINGLE_AP_PASSWORD
        );
    }

    public static synchronized boolean startHotspot(
            Context context,
            String ssid,
            String password
    ) {
        if (hotspotStarting) {
            Log.e(TAG, "startHotspot ignored: already starting");
            return false;
        }

        if (context == null) {
            Log.e(TAG, "startHotspot fail: context null");
            return false;
        }

        if (ssid == null ||
                ssid.trim().isEmpty()) {

            Log.e(TAG, "startHotspot fail: ssid empty");
            return false;
        }

        if (password == null ||
                password.trim().length() < 8) {

            Log.e(TAG, "startHotspot fail: password length < 8");
            return false;
        }

        hotspotStarting =
                true;

        try {
            Context appContext =
                    context.getApplicationContext();

            lastAppContext =
                    appContext;

            Log.e(
                    TAG,
                    "startHotspot begin, ssid="
                            + ssid
            );

            try {
                stopHotspot(
                        appContext
                );
                Thread.sleep(
                        2000
                );
            } catch (Throwable e) {
                Log.e(TAG, "stop old hotspot ignored", e);
            }

            WifiManager wifiManager =
                    (WifiManager) appContext.getSystemService(
                            Context.WIFI_SERVICE
                    );

            if (wifiManager == null) {
                Log.e(TAG, "startHotspot fail: WifiManager null");
                return false;
            }

            try {
                if (wifiManager.isWifiEnabled()) {

                    Log.e(TAG, "disable wifi before hotspot");

                    boolean disableResult =
                            wifiManager.setWifiEnabled(false);

                    Log.e(
                            TAG,
                            "setWifiEnabled false result="
                                    + disableResult
                    );

                    Thread.sleep(
                            3000
                    );
                }

            } catch (Throwable e) {
                Log.e(TAG, "disable wifi before hotspot error", e);
            }

            Object softApConfig =
                    buildSoftApConfigByReflection(
                            ssid,
                            password
                    );

            if (softApConfig == null) {
                Log.e(TAG, "buildSoftApConfig fail");
                return false;
            }

            boolean setConfigOk =
                    setSoftApConfigByReflection(
                            wifiManager,
                            softApConfig
                    );

            Log.e(
                    TAG,
                    "setSoftApConfigByReflection result="
                            + setConfigOk
            );

            Thread.sleep(
                    1000
            );

            boolean startByWifiManager =
                    startTetheredHotspotByWifiManager(
                            wifiManager,
                            softApConfig
                    );

            if (startByWifiManager) {

                Thread.sleep(
                        3000
                );

                Log.e(TAG, "startHotspot success by WifiManager");
                Log.e(TAG, "config url=" + getConfigUrl());

                return true;
            }

            boolean startByConnectivity =
                    startTetheringByConnectivityManager(
                            appContext
                    );

            if (startByConnectivity) {

                Thread.sleep(
                        3000
                );

                Log.e(TAG, "startHotspot success by ConnectivityManager");
                Log.e(TAG, "config url=" + getConfigUrl());

                return true;
            }

            Log.e(TAG, "startHotspot fail: all methods failed");
            return false;

        } catch (Throwable e) {

            Log.e(TAG, "startHotspot fail", e);
            return false;

        } finally {

            hotspotStarting =
                    false;
        }
    }

    private static Object buildSoftApConfigByReflection(
            String ssid,
            String password
    ) {
        try {
            Class<?> builderClass =
                    Class.forName(
                            "android.net.wifi.SoftApConfiguration$Builder"
                    );

            Constructor<?> constructor =
                    builderClass.getDeclaredConstructor();

            constructor.setAccessible(
                    true
            );

            Object builder =
                    constructor.newInstance();

            Method setSsid =
                    builderClass.getDeclaredMethod(
                            "setSsid",
                            String.class
                    );

            setSsid.setAccessible(
                    true
            );

            setSsid.invoke(
                    builder,
                    ssid
            );

            Method setPassphrase =
                    builderClass.getDeclaredMethod(
                            "setPassphrase",
                            String.class,
                            int.class
                    );

            setPassphrase.setAccessible(
                    true
            );

            setPassphrase.invoke(
                    builder,
                    password,
                    SOFT_AP_SECURITY_TYPE_WPA2_PSK
            );

            Method build =
                    builderClass.getDeclaredMethod(
                            "build"
                    );

            build.setAccessible(
                    true
            );

            Object config =
                    build.invoke(
                            builder
                    );

            Log.e(
                    TAG,
                    "buildSoftApConfig success ssid="
                            + ssid
            );

            return config;

        } catch (Throwable e) {

            Log.e(TAG, "buildSoftApConfigByReflection fail", e);
            return null;
        }
    }

    private static boolean setSoftApConfigByReflection(
            WifiManager wifiManager,
            Object softApConfig
    ) {
        try {
            Class<?> softApConfigClass =
                    Class.forName(
                            "android.net.wifi.SoftApConfiguration"
                    );

            Method method =
                    WifiManager.class.getDeclaredMethod(
                            "setSoftApConfiguration",
                            softApConfigClass
                    );

            method.setAccessible(
                    true
            );

            Object result =
                    method.invoke(
                            wifiManager,
                            softApConfig
                    );

            Log.e(
                    TAG,
                    "setSoftApConfiguration invoke result="
                            + result
            );

            if (result instanceof Boolean) {
                return (Boolean) result;
            }

            return true;

        } catch (Throwable e) {

            Log.e(TAG, "setSoftApConfigByReflection fail", e);
            return false;
        }
    }

    private static boolean startTetheredHotspotByWifiManager(
            WifiManager wifiManager,
            Object softApConfig
    ) {
        try {
            Class<?> softApConfigClass =
                    Class.forName(
                            "android.net.wifi.SoftApConfiguration"
                    );

            Method method =
                    WifiManager.class.getDeclaredMethod(
                            "startTetheredHotspot",
                            softApConfigClass
                    );

            method.setAccessible(
                    true
            );

            Object result =
                    method.invoke(
                            wifiManager,
                            softApConfig
                    );

            Log.e(
                    TAG,
                    "startTetheredHotspot invoke result="
                            + result
            );

            if (result instanceof Boolean) {
                return (Boolean) result;
            }

            return true;

        } catch (Throwable e) {

            Log.e(TAG, "startTetheredHotspotByWifiManager unavailable", e);
            return false;
        }
    }

    private static boolean startTetheringByConnectivityManager(
            Context context
    ) {
        try {
            /*
             * 这个方法只保留为日志兜底。
             * 你之前在 RK3566 Android 13 验证通过的路径是：
             * WifiManager.setSoftApConfiguration + WifiManager.startTetheredHotspot。
             */
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(
                            Context.CONNECTIVITY_SERVICE
                    );

            if (cm == null) {
                Log.e(TAG, "ConnectivityManager null");
                return false;
            }

            Log.e(
                    TAG,
                    "skip ConnectivityManager.startTethering, use WifiManager.startTetheredHotspot first"
            );

            return false;

        } catch (Throwable e) {

            Log.e(TAG, "startTetheringByConnectivityManager fail", e);
            return false;
        }
    }

    public static synchronized boolean stopHotspot(Context context) {
        try {
            Context appContext =
                    context == null
                            ? lastAppContext
                            : context.getApplicationContext();

            if (appContext == null) {
                Log.e(TAG, "stopHotspot fail: context null");
                return false;
            }

            lastAppContext =
                    appContext;

            ConnectivityManager cm =
                    (ConnectivityManager) appContext.getSystemService(
                            Context.CONNECTIVITY_SERVICE
                    );

            if (cm == null) {
                Log.e(TAG, "stopHotspot fail: ConnectivityManager null");
                return false;
            }

            Method stopTethering =
                    ConnectivityManager.class.getDeclaredMethod(
                            "stopTethering",
                            int.class
                    );

            stopTethering.setAccessible(
                    true
            );

            stopTethering.invoke(
                    cm,
                    TETHERING_WIFI
            );

            Log.e(TAG, "stopHotspot invoked");

            return true;

        } catch (Throwable e) {

            Log.e(TAG, "stopHotspot fail", e);
            return false;
        }
    }

    /**
     * 兼容旧代码调用。
     */
    public static boolean stopHotspot() {
        return stopHotspot(
                lastAppContext
        );
    }

    @SuppressWarnings("deprecation")
    public static boolean connectWifi(
            Context context,
            String ssid,
            String password
    ) {
        try {
            if (context == null) {
                Log.e(TAG, "connectWifi fail: context null");
                return false;
            }

            if (ssid == null ||
                    ssid.trim().isEmpty()) {

                Log.e(TAG, "connectWifi fail: ssid empty");
                return false;
            }

            Context appContext =
                    context.getApplicationContext();

            lastAppContext =
                    appContext;

            WifiManager wifiManager =
                    (WifiManager) appContext.getSystemService(
                            Context.WIFI_SERVICE
                    );

            if (wifiManager == null) {
                Log.e(TAG, "connectWifi fail: WifiManager null");
                return false;
            }

            Log.e(
                    TAG,
                    "connectWifi ssid="
                            + ssid
            );

            try {
                if (!wifiManager.isWifiEnabled()) {

                    boolean enableResult =
                            wifiManager.setWifiEnabled(
                                    true
                            );

                    Log.e(
                            TAG,
                            "setWifiEnabled true result="
                                    + enableResult
                    );

                    Thread.sleep(
                            3000
                    );
                }

            } catch (Throwable e) {
                Log.e(TAG, "enable wifi fail", e);
            }

            WifiConfiguration config =
                    buildWifiConfig(
                            ssid,
                            password
                    );

            removeOldSameSsidNetwork(
                    wifiManager,
                    ssid
            );

            boolean hiddenResult =
                    connectByHiddenApi(
                            wifiManager,
                            config
                    );

            if (hiddenResult) {
                Log.e(TAG, "connectWifi command sent by hidden api");
                return true;
            }

            int netId =
                    wifiManager.addNetwork(
                            config
                    );

            Log.e(
                    TAG,
                    "addNetwork netId="
                            + netId
            );

            if (netId < 0) {
                Log.e(
                        TAG,
                        "connectWifi fail: addNetwork returned "
                                + netId
                );

                return false;
            }

            boolean disconnect =
                    wifiManager.disconnect();

            Log.e(
                    TAG,
                    "disconnect result="
                            + disconnect
            );

            boolean enable =
                    wifiManager.enableNetwork(
                            netId,
                            true
                    );

            Log.e(
                    TAG,
                    "enableNetwork result="
                            + enable
            );

            boolean reconnect =
                    wifiManager.reconnect();

            Log.e(
                    TAG,
                    "reconnect result="
                            + reconnect
            );

            return enable && reconnect;

        } catch (Throwable e) {

            Log.e(TAG, "connectWifi fail", e);
            return false;
        }
    }

    /**
     * 兼容旧代码调用。
     */
    public static boolean connectWifi(
            String ssid,
            String password
    ) {
        return connectWifi(
                lastAppContext,
                ssid,
                password
        );
    }

    private static WifiConfiguration buildWifiConfig(
            String ssid,
            String password
    ) {
        WifiConfiguration config =
                new WifiConfiguration();

        config.SSID =
                quoteSsid(
                        ssid
                );

        config.status =
                WifiConfiguration.Status.ENABLED;

        if (password == null ||
                password.trim().isEmpty()) {

            config.allowedKeyManagement.set(
                    WifiConfiguration.KeyMgmt.NONE
            );

        } else {

            config.preSharedKey =
                    quotePasswordIfNeeded(
                            password
                    );

            config.allowedKeyManagement.set(
                    WifiConfiguration.KeyMgmt.WPA_PSK
            );

            config.allowedProtocols.set(
                    WifiConfiguration.Protocol.RSN
            );

            config.allowedProtocols.set(
                    WifiConfiguration.Protocol.WPA
            );

            config.allowedPairwiseCiphers.set(
                    WifiConfiguration.PairwiseCipher.CCMP
            );

            config.allowedPairwiseCiphers.set(
                    WifiConfiguration.PairwiseCipher.TKIP
            );

            config.allowedGroupCiphers.set(
                    WifiConfiguration.GroupCipher.CCMP
            );

            config.allowedGroupCiphers.set(
                    WifiConfiguration.GroupCipher.TKIP
            );
        }

        return config;
    }

    private static boolean connectByHiddenApi(
            WifiManager wifiManager,
            WifiConfiguration config
    ) {
        try {
            Class<?> listenerClass =
                    Class.forName(
                            "android.net.wifi.WifiManager$ActionListener"
                    );

            Method connect =
                    WifiManager.class.getDeclaredMethod(
                            "connect",
                            WifiConfiguration.class,
                            listenerClass
                    );

            connect.setAccessible(
                    true
            );

            connect.invoke(
                    wifiManager,
                    config,
                    null
            );

            return true;

        } catch (Throwable e) {

            Log.e(TAG, "connectByHiddenApi unavailable", e);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static void removeOldSameSsidNetwork(
            WifiManager wifiManager,
            String ssid
    ) {
        try {
            List<WifiConfiguration> list =
                    wifiManager.getConfiguredNetworks();

            if (list == null) {
                return;
            }

            String target =
                    quoteSsid(
                            ssid
                    );

            for (WifiConfiguration item : list) {

                if (item == null) {
                    continue;
                }

                if (target.equals(
                        item.SSID
                )) {

                    Log.e(
                            TAG,
                            "remove old network ssid="
                                    + item.SSID
                                    + ", netId="
                                    + item.networkId
                    );

                    wifiManager.removeNetwork(
                            item.networkId
                    );
                }
            }

            try {
                wifiManager.saveConfiguration();
            } catch (Throwable e) {
                Log.e(TAG, "saveConfiguration ignored", e);
            }

        } catch (Throwable e) {

            Log.e(TAG, "removeOldSameSsidNetwork fail", e);
        }
    }

    public static String getHotspotSsid(
            Context context
    ) {
        try {
            String deviceId =
                    DeviceUtil.getDeviceId(
                            context
                    );

            if (deviceId == null) {
                deviceId =
                        "";
            }

            deviceId =
                    deviceId.trim()
                            .toUpperCase();

            String suffix;

            if (deviceId.length() >= 6) {

                suffix =
                        deviceId.substring(
                                deviceId.length() - 6
                        );

            } else if (deviceId.length() > 0) {

                suffix =
                        deviceId;

            } else {

                suffix =
                        "DEVICE";
            }

            return ProvisionConfig.SINGLE_AP_PREFIX
                    + suffix;

        } catch (Throwable e) {

            Log.e(TAG, "getHotspotSsid fail", e);

            return ProvisionConfig.SINGLE_AP_PREFIX
                    + "DEVICE";
        }
    }

    public static boolean connectFactoryWifi(
            Context context
    ) {
        return connectWifi(
                context,
                ProvisionConfig.FACTORY_WIFI_SSID,
                ProvisionConfig.FACTORY_WIFI_PASSWORD
        );
    }

    public static String getLanConfigUrl(
            Context context
    ) {
        String ip =
                getLocalIp();

        if (ip == null ||
                ip.trim().isEmpty()) {

            return "";
        }

        return "http://"
                + ip
                + ":8080";
    }

    public static String getLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {

                NetworkInterface ni =
                        interfaces.nextElement();

                if (ni == null ||
                        !ni.isUp() ||
                        ni.isLoopback()) {

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

            Log.e(TAG, "getLocalIp fail", e);
        }

        return "";
    }

    public static String getCurrentSsid(
            Context context
    ) {
        try {
            WifiManager wifiManager =
                    (WifiManager) context.getApplicationContext()
                            .getSystemService(
                                    Context.WIFI_SERVICE
                            );

            if (wifiManager == null) {
                return "";
            }

            WifiInfo info =
                    wifiManager.getConnectionInfo();

            if (info == null) {
                return "";
            }

            String ssid =
                    info.getSSID();

            if (ssid == null) {
                return "";
            }

            ssid =
                    ssid.trim();

            if (ssid.startsWith("\"") &&
                    ssid.endsWith("\"") &&
                    ssid.length() > 1) {

                ssid =
                        ssid.substring(
                                1,
                                ssid.length() - 1
                        );
            }

            if ("<unknown ssid>".equalsIgnoreCase(
                    ssid
            )) {

                return "";
            }

            return ssid;

        } catch (Throwable e) {

            Log.e(TAG, "getCurrentSsid fail", e);
            return "";
        }
    }

    public static boolean isConnectedToFactoryWifi(
            Context context
    ) {
        try {
            String currentSsid =
                    getCurrentSsid(
                            context
                    );

            return ProvisionConfig.FACTORY_WIFI_SSID.equals(
                    currentSsid
            );

        } catch (Throwable e) {

            Log.e(TAG, "isConnectedToFactoryWifi fail", e);
            return false;
        }
    }

    private static String getHotspotIpAddress() {
        String fallbackPrivateIp =
                "";

        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {

                NetworkInterface ni =
                        interfaces.nextElement();

                if (ni == null ||
                        !ni.isUp() ||
                        ni.isLoopback()) {

                    continue;
                }

                String name =
                        ni.getName();

                Enumeration<InetAddress> addrs =
                        ni.getInetAddresses();

                while (addrs.hasMoreElements()) {

                    InetAddress addr =
                            addrs.nextElement();

                    if (!(addr instanceof Inet4Address) ||
                            addr.isLoopbackAddress()) {

                        continue;
                    }

                    String ip =
                            addr.getHostAddress();

                    if (ip == null ||
                            ip.trim().isEmpty() ||
                            ip.startsWith("127.")) {

                        continue;
                    }

                    Log.e(
                            TAG,
                            "interface="
                                    + name
                                    + " ip="
                                    + ip
                    );

                    if (ip.startsWith("192.168.43.")
                            || ip.startsWith("192.168.49.")) {

                        return ip;
                    }

                    if (ip.startsWith("192.168.")
                            || ip.startsWith("172.")
                            || ip.startsWith("10.")) {

                        if (fallbackPrivateIp.isEmpty()) {
                            fallbackPrivateIp =
                                    ip;
                        }

                        if (name != null) {

                            String lowerName =
                                    name.toLowerCase();

                            if (lowerName.contains("wlan")
                                    || lowerName.contains("ap")
                                    || lowerName.contains("softap")
                                    || lowerName.contains("bridge")) {

                                return ip;
                            }
                        }
                    }
                }
            }

        } catch (Throwable e) {

            Log.e(TAG, "getHotspotIpAddress fail", e);
        }

        if (!fallbackPrivateIp.isEmpty()) {
            return fallbackPrivateIp;
        }

        return "192.168.43.1";
    }

    private static String quoteSsid(
            String ssid
    ) {
        String value =
                ssid.trim();

        if (value.startsWith("\"") &&
                value.endsWith("\"")) {

            return value;
        }

        return "\""
                + value
                + "\"";
    }

    private static String quotePasswordIfNeeded(
            String password
    ) {
        String value =
                password.trim();

        if (value.matches(
                "[0-9A-Fa-f]{64}"
        )) {

            return value;
        }

        if (value.startsWith("\"") &&
                value.endsWith("\"")) {

            return value;
        }

        return "\""
                + value
                + "\"";
    }
}
