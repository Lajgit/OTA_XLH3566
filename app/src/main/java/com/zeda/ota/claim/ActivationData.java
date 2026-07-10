package com.zeda.ota;

public class ActivationData {

    public boolean accepted;

    public boolean claimed;

    public String deviceNo;

    public String mqttBrokerUrl;

    public String mqttClientId;

    public String mqttUsername;

    public String mqttPassword;

    public int heartbeatInterval;

    public int keepAliveSeconds;

    public int configVersion;
}