package com.zeda.ota;

import java.io.File;

public class UpdateTask {
    public String type;      // main / ball / ota
    public String version;
    public String url;
    public String md5;
    public String messageId;
    public File apkFile;     // 下载后APK文件，main/ota才会用

    public UpdateTask(String type, String version, String url, String md5, String messageId) {
        this.type = type;
        this.version = version;
        this.url = url;
        this.md5 = md5;
        this.messageId = messageId;
    }
}