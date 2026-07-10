package com.zeda.ota;

public class ProvisionConfig {

    /**
     * 临时配网路由器。
     * 批量部署时，现场准备一个这个 SSID 的路由器。
     */
    public static final String FACTORY_WIFI_SSID =
            "OTA_FACTORY";

    public static final String FACTORY_WIFI_PASSWORD =
            "12345678";

    /**
     * 单机配网热点前缀。
     * 实际热点名建议为 OTA_CONFIG_设备后6位。
     */
    public static final String SINGLE_AP_PREFIX =
            "OTA_CONFIG_";

    public static final String SINGLE_AP_PASSWORD =
            "12345678";

    /**
     * 批量配网 UDP 端口。
     */
    public static final int BATCH_CONFIG_PORT =
            19000;

    /**
     * 批量配网签名密钥。
     * 正式量产时建议由厂商单独配置。
     */
    public static final String BATCH_CONFIG_SECRET =
            "PXD_WIFI_BATCH_CONFIG_SECRET_202606";

    /**
     * 尝试连接 OTA_FACTORY 的等待时间。
     */
    public static final int FACTORY_CONNECT_WAIT_SECONDS =
            20;

    /**
     * 尝试连接正式 WiFi 的等待时间。
     */
    public static final int TARGET_WIFI_CONNECT_WAIT_SECONDS =
            60;

    /**
     * 主设备广播正式 WiFi 配置的持续时间300秒（300x1000L）。
     */
    public static final long BATCH_BROADCAST_DURATION_MS =
            300000L;
}