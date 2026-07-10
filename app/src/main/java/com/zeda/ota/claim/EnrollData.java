package com.zeda.ota;

public class EnrollData {

    public String deviceNo;

    public String firmwareVersion;

    public String apkVersion;

    public String nonce;

    public long timestamp;

    public String signature;

    // 自动认领版本新增
    public String batchNo;

    public String batchSignature;

    // 返回字段
    public boolean accepted;

    public String claimToken;

    public String claimCode;

    public String claimQrContent;

    public int claimStatus;

    public String claimStatusDesc;

    public boolean claimed;

    public String message;
}