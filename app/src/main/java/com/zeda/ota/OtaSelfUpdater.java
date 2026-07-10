package com.zeda.ota;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import android.content.Intent;

public class OtaSelfUpdater {

    public static void update(
            Context context,
            String version,
            String url,
            String md5,
            String messageId,
            long recordId,
            long taskId
    ) {

        Log.e("OTA_TEST", "self update start");

        ApkDownloader.downloadself(
                context,
                version,
                url,
                md5,
                messageId,
                recordId,
                taskId,
                new ApkDownloader.Callback() {

                    @Override
                    public void onDownloadSuccess(File apk) {

                        Log.e("OTA_TEST", "self apk ready = " + apk.getAbsolutePath());

                        StatusReporter.report(
                                context,
                                messageId,
                                "installing",
                                50,
                                PackageUtil.getVersion(context, "com.zeda.ota"),
                                version,
                                recordId,
                                taskId,
                                "ota",
                                null,
                                null
                        );

                        StatusReporter.report(
                                context,
                                messageId,
                                "success",
                                100,
                                PackageUtil.getVersion(context, "com.zeda.ota"),
                                version,
                                recordId,
                                taskId,
                                "ota",
                                null,
                                null
                        );

                        installSelf(
                                context,
                                apk,
                                version,
                                messageId,
                                recordId,
                                taskId
                        );
                    }

                    @Override
                    public void onDownloadFail(String errorCode, String errorMessage) {

                        Log.e("OTA_TEST", "self apk fail: " + errorCode + ", " + errorMessage);

                        StatusReporter.report(
                                context,
                                messageId,
                                "failed",
                                0,
                                PackageUtil.getVersion(context, "com.zeda.ota"),
                                version,
                                recordId,
                                taskId,
                                "ota",
                                errorCode,
                                errorMessage
                        );
                    }
                }
        );
    }

    private static void installSelf(
            Context context,
            File apk,
            String targetVersion,
            String messageId,
            long recordId,
            long taskId
    ) {

        new Thread(() -> {

            Process process = null;

            try {

                process = Runtime.getRuntime().exec("su");

                java.io.DataOutputStream os =
                        new java.io.DataOutputStream(process.getOutputStream());
                os.writeBytes("pm install -r --user 0 \"" + apk.getAbsolutePath() + "\"\n");
                os.writeBytes("exit\n");
                os.flush();

                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(process.getInputStream()));

                StringBuilder installOutput = new StringBuilder();

                String line;
                while ((line = reader.readLine()) != null) {
                    Log.e("OTA_TEST", "su: " + line);
                    installOutput.append(line).append('\n');
                }

                BufferedReader errReader =
                        new BufferedReader(new InputStreamReader(process.getErrorStream()));

                while ((line = errReader.readLine()) != null) {
                    Log.e("OTA_TEST", "su err: " + line);
                    installOutput.append(line).append('\n');
                }

                int code = process.waitFor();
                Log.e("OTA_TEST", "self install exit code = " + code);

                if (code == 0) {

                    StatusReporter.report(
                            context,
                            messageId,
                            "success",
                            100,
                            PackageUtil.getVersion(context, "com.zeda.ota"),
                            targetVersion,
                            recordId,
                            taskId,
                            "ota",
                            null,
                            null
                    );

                    Log.e("OTA_TEST", "self update done");

                } else {

                    StatusReporter.report(
                            context,
                            messageId,
                            "failed",
                            0,
                            PackageUtil.getVersion(context, "com.zeda.ota"),
                            targetVersion,
                            recordId,
                            taskId,
                            "ota",
                            parseInstallErrorCode(installOutput.toString()),
                            parseInstallErrorMessage(installOutput.toString(), code)
                    );

                    Log.e("OTA_TEST", "self update failed code=" + code);
                }

                // 重新拉起自己
                try {
                    Thread.sleep(2000);
                } catch (Exception ignored) {
                }

                try {
                    Intent intent = context.getPackageManager()
                            .getLaunchIntentForPackage("com.zeda.ota");
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    }
                } catch (Exception e) {
                    Log.e("OTA_TEST", "restart ota app fail", e);
                }

            } catch (Exception e) {

                Log.e("OTA_TEST", "self update fail", e);

                StatusReporter.report(
                        context,
                        messageId,
                        "failed",
                        0,
                        PackageUtil.getVersion(context, "com.zeda.ota"),
                        targetVersion,
                        recordId,
                        taskId,
                        "ota",
                        "INSTALL_EXCEPTION",
                        "OTA应用安装异常：" + getExceptionMessage(e)
                );

            } finally {

                try {
                    if (process != null) {
                        process.destroy();
                    }
                } catch (Exception ignored) {
                }
            }

        }).start();
    }

    private static String parseInstallErrorCode(String msg) {
        if (msg == null) {
            return "INSTALL_FAIL";
        }

        if (msg.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                || msg.contains("signatures do not match")) {
            return "INSTALL_FAILED_UPDATE_INCOMPATIBLE";
        }

        if (msg.contains("INSTALL_FAILED_VERSION_DOWNGRADE")) {
            return "INSTALL_FAILED_VERSION_DOWNGRADE";
        }

        if (msg.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")) {
            return "INSTALL_FAILED_INSUFFICIENT_STORAGE";
        }

        if (msg.contains("INSTALL_PARSE_FAILED")
                || msg.contains("Failed parse")) {
            return "INSTALL_PARSE_FAILED";
        }

        if (msg.contains("INSTALL_FAILED_NO_MATCHING_ABIS")) {
            return "INSTALL_FAILED_NO_MATCHING_ABIS";
        }

        return "INSTALL_FAIL";
    }

    private static String parseInstallErrorMessage(String msg, int code) {
        if (msg == null) {
            msg = "";
        }

        if (msg.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                || msg.contains("signatures do not match")) {
            return "OTA应用安装失败：APK签名与已安装版本不一致，请使用同一签名证书重新打包";
        }

        if (msg.contains("INSTALL_FAILED_VERSION_DOWNGRADE")) {
            return "OTA应用安装失败：目标版本低于当前已安装版本，系统禁止降级安装";
        }

        if (msg.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")
                || msg.contains("not enough free space")) {
            return "OTA应用安装失败：设备存储空间不足，请清理空间后重试";
        }

        if (msg.contains("INSTALL_PARSE_FAILED")
                || msg.contains("Failed parse")) {
            return "OTA应用安装失败：APK解析失败，文件可能损坏、格式不正确或与当前系统不兼容";
        }

        if (msg.contains("INSTALL_FAILED_NO_MATCHING_ABIS")) {
            return "OTA应用安装失败：APK架构不匹配，请确认安装包包含当前设备支持的ABI";
        }

        if (msg.contains("Permission denied")
                || msg.contains("permission denied")) {
            return "OTA应用安装失败：没有安装权限或root权限不足";
        }

        return "OTA应用安装失败：系统安装命令返回码=" + code;
    }

    private static String getExceptionMessage(Exception e) {
        if (e == null) {
            return "未知异常";
        }

        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }

        return msg;
    }
}
