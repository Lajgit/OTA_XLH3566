package com.zeda.ota;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;

public class OtaReceiver {

    private static final String TAG = "OTA_TEST";

    public static void onReceive(Context context, String payload) {

        if (payload == null || payload.trim().isEmpty()) {
            return;
        }

        try {

            JSONObject json =
                    new JSONObject(payload);

            String type = json.optString("type", "game");
            String messageId = json.optString("messageId", "");
            String version = json.optString("version", "");
            String url = json.optString("url", "");
            String md5 = json.optString("md5", "");
            long recordId = json.optLong("recordId", 0);
            long taskId = json.optLong("taskId", 0);

            Log.e(TAG, "message type = " + type);

            if (!"game".equals(type)
                    && !"ball".equals(type)
                    && !"ota".equals(type)) {

                Log.e(TAG, "unknown type = " + type);

                StatusReporter.report(
                        context,
                        messageId,
                        "failed",
                        0,
                        "",
                        version,
                        recordId,
                        taskId,
                        type,
                        "UNSUPPORTED_TYPE",
                        "不支持的升级类型：" + type
                );

                finishUpgrade(context);
                return;
            }

            String currentMain = PackageUtil.getVersion(context, "com.zeda");
            String currentOta = PackageUtil.getVersion(context, "com.zeda.ota");
            String currentBall = PendingStore.get(context, "ball_version");

            String currentVersion = getCurrentVersion(type, currentMain, currentOta, currentBall);

            String validateError = validateUpgradeParam(version, url, md5);
            if (validateError != null) {

                StatusReporter.report(
                        context,
                        messageId,
                        "failed",
                        0,
                        currentVersion,
                        version,
                        recordId,
                        taskId,
                        type,
                        "PARAM_INVALID",
                        validateError
                );

                PendingStore.clearTask(context, type);
                finishUpgrade(context);
                return;
            }

            if (version.equals(currentVersion)) {

                Log.e(TAG, type + " version equals current, skip update");

                StatusReporter.report(
                        context,
                        messageId,
                        "skipped",
                        100,
                        currentVersion,
                        version,
                        recordId,
                        taskId,
                        type,
                        "ALREADY_LATEST",
                        "当前已是目标版本，无需升级"
                );

                PendingStore.clearTask(context, type);
                finishUpgrade(context);
                return;
            }

            PendingStore.saveTask(context, type, payload);

            switch (type) {
                case "game":

                    Log.e(TAG, "main version different, downloading");

                    ApkDownloader.download(
                            context,
                            version,
                            url,
                            md5,
                            messageId,
                            currentMain,
                            recordId,
                            taskId,
                            new ApkDownloader.Callback() {
                                @Override
                                public void onDownloadSuccess(File apk) {

                                    PendingStore.saveApk(
                                            context,
                                            apk.getAbsolutePath(),
                                            version,
                                            messageId
                                    );

                                    InstallManager.install(
                                            context,
                                            apk,
                                            version,
                                            messageId,
                                            recordId,
                                            taskId,
                                            "game",
                                            new InstallManager.Callback() {
                                                @Override
                                                public void onInstallSuccess() {
                                                    Log.e(TAG, "game install success callback");
                                                }

                                                @Override
                                                public void onInstallFail() {
                                                    Log.e(TAG, "game install fail callback");
                                                }
                                            }
                                    );
                                }

                                @Override
                                public void onDownloadFail(String errorCode, String errorMessage) {
                                    int failedProgress;

                                    /*
                                     * MD5_FAIL 说明文件已经下载完成，
                                     * 只是在完整性校验阶段失败。
                                     *
                                     * 此时失败进度应该保持 100，
                                     * 避免服务器因为进度从 100 回退到 0 而拒绝更新。
                                     */
                                    if ("MD5_FAIL".equals(errorCode)) {

                                        failedProgress = 100;

                                    } else {

                                        failedProgress = 0;
                                    }

                                    Log.e(
                                            TAG,
                                            "game download fail callback"
                                                    + ", errorCode="
                                                    + errorCode
                                                    + ", failedProgress="
                                                    + failedProgress
                                    );

                                    StatusReporter.report(
                                            context,
                                            messageId,
                                            "failed",
                                            failedProgress,
                                            currentMain,
                                            version,
                                            recordId,
                                            taskId,
                                            "game",
                                            errorCode,
                                            errorMessage
                                    );

                                    PendingStore.clearTask(
                                            context,
                                            "game"
                                    );

                                    finishUpgrade(context);

                                    PendingStore.clearTask(context, "game");
                                    finishUpgrade(context);
                                }
                            });
                    break;

                case "ball":

                    Log.e(TAG, "ball firmware version different, downloading");

                    BallFileDownloader.download(
                            context,
                            version,
                            url,
                            md5,
                            messageId,
                            recordId,
                            taskId
                    );
                    break;

                case "ota":

                    Log.e(TAG, "OTA app version different, updating");

                    OtaSelfUpdater.update(
                            context,
                            version,
                            url,
                            md5,
                            messageId,
                            recordId,
                            taskId
                    );
                    break;
            }

        } catch (Exception e) {

            Log.e(TAG, "ota error", e);
            finishUpgrade(context);
        }
    }

    private static String getCurrentVersion(
            String type,
            String currentMain,
            String currentOta,
            String currentBall
    ) {

        if ("ota".equals(type)) {
            return currentOta == null ? "" : currentOta;
        }

        if ("ball".equals(type)) {
            return currentBall == null ? "" : currentBall;
        }

        return currentMain == null ? "" : currentMain;
    }

    private static String validateUpgradeParam(
            String version,
            String url,
            String md5
    ) {

        if (version == null || version.trim().isEmpty()) {
            return "升级失败：服务器未下发目标版本号";
        }

        if (url == null || url.trim().isEmpty()) {
            return "升级失败：服务器未下发下载地址";
        }

        if (md5 == null || md5.trim().isEmpty()) {
            return "升级失败：服务器未下发MD5，无法校验文件完整性";
        }

        return null;
    }

    private static void finishUpgrade(Context context) {
        try {
            DeviceReportManager.get(context)
                    .setRunningStatusAndReport(
                            DeviceReportManager.STATUS_IDLE
                    );
        } catch (Throwable e) {
            Log.e(TAG, "finish upgrade fail", e);
        }
    }
}
