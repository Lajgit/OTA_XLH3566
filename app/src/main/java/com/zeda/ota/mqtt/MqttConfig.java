package com.zeda.ota;

public class MqttConfig {

    /**
     * 默认 Broker，只作为兜底。
     * 正常情况下使用激活接口下发的 mqttBrokerUrl。
     */
//    public static final String DEFAULT_BROKER_URL =
//            "tcp://81.71.50.235:1883";

    /**
     * 连接超时
     */
    public static final int TIMEOUT = 10;

    /**
     * 默认 keepAlive。
     * 如果激活接口下发 keepAliveSeconds，优先使用接口下发值。
     */
    public static final int KEEPALIVE = 20;

    /**
     * 升级命令 topic
     */
    public static String getUpgradeTopic(String deviceId) {

        return "pxd/v1/device/"
                + deviceId
                + "/command/upgrade";
    }

    /**
     * 控制命令 topic
     */
    public static String getControlTopic(String deviceId) {

        return "pxd/v1/device/"
                + deviceId
                + "/command/control";
    }

    /**
     * 配置命令 topic
     */
    public static String getConfigTopic(String deviceId) {

        return "pxd/v1/device/"
                + deviceId
                + "/command/config";
    }

    /**
     * 心跳上报 topic
     */
    public static String getHeartbeatTopic(String deviceId) {

        return "pxd/v1/device/"
                + deviceId
                + "/report/heartbeat";
    }

    /**
     * 升级进度 topic
     */
    public static String getUpgradeProgressTopic(String deviceId) {

        return "pxd/v1/device/"
                + deviceId
                + "/report/upgrade-progress";
    }

    /**
     * 状态上报 topic
     */
    public static String getStatusTopic(String deviceId) {

        return "pxd/v1/device/" +
                deviceId +
                "/report/status";
    }

    /**
     * 硬件档案上报 topic
     */
    public static String getProfileTopic(String deviceId) {

        return "pxd/v1/device/" +
                deviceId +
                "/report/profile";
    }

    /**
     * 设备任务命令 topic
     */
    public static String getTaskTopic(String deviceId) {

        return "pxd/v1/device/"
                + deviceId
                + "/command/task";
    }

    /**
     * 设备任务回执 topic
     */
    public static String getCommandResultTopic(String deviceId) {

        return "pxd/v1/device/"
                + deviceId
                + "/report/command-result";
    }

    /**
     * 游戏实际配置上报 topic
     */
    public static String getGameConfigReportTopic(String deviceId) {

        return "pxd/v1/device/"
                + deviceId
                + "/report/game-config";
    }
}
