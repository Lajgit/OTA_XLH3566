package com.zeda.ota;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;

public class InstallManager {

    private static volatile boolean installing = false;

    private static final String TAG = "OTA_TEST";
    private static final int MAX_INSTALL_RETRY = 3;

    public interface Callback {
        void onInstallSuccess();
        void onInstallFail();
    }

    private static final String PKG_NAME = "com.zeda";

    public static void install(
            Context context,
            File apk,
            String version,
            String messageId,
            Callback callback
    ) {

        TaskMeta meta = readTaskMeta(context, "game");

        install(
                context,
                apk,
                version,
                messageId,
                meta.recordId,
                meta.taskId,
                meta.type,
                callback
        );
    }

    public static void install(
            Context context,
            File apk,
            String version,
            String messageId,
            long recordId,
            long taskId,
            String type,
            Callback callback
    ) {

        String safeType = isEmpty(type) ? "game" : type;
        String current = PackageUtil.getVersion(context, PKG_NAME);

        if (installing) {
            Log.e(TAG, "installing, ignore duplicate install request");

            StatusReporter.report(
                    context,
                    messageId,
                    "installing",
                    50,
                    current,
                    version,
                    recordId,
                    taskId,
                    safeType,
                    "INSTALL_ALREADY_RUNNING",
                    "安装任务正在执行中，忽略重复安装请求"
            );

            return;
        }

        installing = true;

        new Thread(() -> {

            try {

                if (apk == null || !apk.exists()) {
                    StatusReporter.report(
                            context,
                            messageId,
                            "failed",
                            0,
                            current,
                            version,
                            recordId,
                            taskId,
                            safeType,
                            "APK_FILE_MISSING",
                            "安装失败：本地APK文件不存在"
                    );

                    PendingStore.clearTask(context, safeType);
                    PendingStore.clearApk(context);
                    finishUpgrade(context);

                    if (callback != null) {
                        callback.onInstallFail();
                    }

                    return;
                }

                StatusReporter.report(
                        context,
                        messageId,
                        "installing",
                        50,
                        current,
                        version,
                        recordId,
                        taskId,
                        safeType,
                        null,
                        null
                );

                // 停掉主游戏，避免安装时文件占用或者进程还在运行
                execRootCmd("am force-stop " + PKG_NAME + "\nsleep 2");
                Thread.sleep(2000);

                InstallResult lastResult = null;

                for (int attempt = 1; attempt <= MAX_INSTALL_RETRY; attempt++) {

                    Log.e(TAG, "pm install attempt=" + attempt + "/" + MAX_INSTALL_RETRY);

                    lastResult = pmInstall(apk);

                    Log.e(TAG, "install attempt=" + attempt
                            + ", code=" + lastResult.exitCode
                            + ", success=" + lastResult.success
                            + ", output=" + lastResult.output);

                    if (lastResult.success) {
                        afterInstallSuccess(context, apk, messageId, current, version, recordId, taskId, safeType);

                        finishUpgrade(context);

                        if (callback != null) {
                            callback.onInstallSuccess();
                        }

                        return;
                    }

                    if (attempt < MAX_INSTALL_RETRY) {
                        // 安装失败只重试安装，不重新下载
                        StatusReporter.report(
                                context,
                                messageId,
                                "installing",
                                50 + attempt * 10,
                                current,
                                version,
                                recordId,
                                taskId,
                                safeType,
                                null,
                                null
                        );

                        Thread.sleep(3000);
                    }
                }

                String rawInstallMessage = lastResult == null ? "" : lastResult.output;
                String errorCode = parseInstallErrorCode(rawInstallMessage);
                String errorMessage = parseInstallErrorMessage(rawInstallMessage);

                StatusReporter.report(
                        context,
                        messageId,
                        "failed",
                        100,
                        current,
                        version,
                        recordId,
                        taskId,
                        safeType,
                        errorCode,
                        errorMessage
                );

                PendingStore.clearTask(context, safeType);
                PendingStore.clearApk(context);
                finishUpgrade(context);

                if (apk != null && apk.exists()) {
                    apk.delete();
                }

                if (callback != null) {
                    callback.onInstallFail();
                }

            } catch (Exception e) {

                Log.e(TAG, "install err", e);

                StatusReporter.report(
                        context,
                        messageId,
                        "failed",
                        100,
                        PackageUtil.getVersion(context, PKG_NAME),
                        version,
                        recordId,
                        taskId,
                        safeType,
                        "INSTALL_EXCEPTION",
                        "安装异常：" + getExceptionMessage(e)
                );

                PendingStore.clearTask(context, safeType);
                PendingStore.clearApk(context);
                finishUpgrade(context);

                if (apk != null && apk.exists()) {
                    apk.delete();
                }

                if (callback != null) {
                    callback.onInstallFail();
                }

            } finally {
                installing = false;
            }

        }).start();
    }

    private static void afterInstallSuccess(
            Context context,
            File apk,
            String messageId,
            String oldVersion,
            String targetVersion,
            long recordId,
            long taskId,
            String type
    ) throws Exception {

        clearAppData();

        Thread.sleep(5000);

        PackageUtil.launchMainApp(context);

        StatusReporter.report(
                context,
                messageId,
                "success",
                100,
                oldVersion,
                targetVersion,
                recordId,
                taskId,
                type,
                null,
                null
        );

        PendingStore.clearTask(context, type);
        PendingStore.clearApk(context);

        Log.e(TAG, "pending cleared");

        if (apk.exists()) {
            apk.delete();
        }
    }

    private static InstallResult pmInstall(File apk) {

        InstallResult result = new InstallResult();

        Process process = null;
        DataOutputStream os = null;
        BufferedReader reader = null;
        BufferedReader errorReader = null;

        try {
            process = Runtime.getRuntime().exec("su");

            os = new DataOutputStream(process.getOutputStream());

            // --user 0 对 Android 多用户环境更稳；路径加引号避免特殊字符问题
            os.writeBytes("pm install -r --user 0 \"" + apk.getAbsolutePath() + "\"\n");
            os.writeBytes("exit\n");
            os.flush();

            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            StringBuilder sb = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                Log.e(TAG, "stdout=" + line);
                sb.append(line).append('\n');

                if (line.contains("Success")) {
                    result.success = true;
                }
            }

            while ((line = errorReader.readLine()) != null) {
                Log.e(TAG, "stderr=" + line);
                sb.append(line).append('\n');

                if (line.contains("Success")) {
                    result.success = true;
                }
            }

            result.exitCode = process.waitFor();
            result.output = sb.toString().trim();

            if (result.exitCode != 0) {
                result.success = false;
            }

            return result;

        } catch (Exception e) {
            Log.e(TAG, "pmInstall err", e);
            result.success = false;
            result.exitCode = -1;
            result.output = e.toString();
            return result;

        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Exception ignored) {
            }

            try {
                if (errorReader != null) errorReader.close();
            } catch (Exception ignored) {
            }

            try {
                if (os != null) os.close();
            } catch (Exception ignored) {
            }

            try {
                if (process != null) process.destroy();
            } catch (Exception ignored) {
            }
        }
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

        if (msg.contains("INSTALL_PARSE_FAILED")) {
            return "INSTALL_PARSE_FAILED";
        }

        if (msg.contains("INSTALL_FAILED_NO_MATCHING_ABIS")) {
            return "INSTALL_FAILED_NO_MATCHING_ABIS";
        }

        if (msg.contains("INSTALL_FAILED_INVALID_APK")) {
            return "INSTALL_FAILED_INVALID_APK";
        }

        if (msg.contains("INSTALL_FAILED_INVALID_URI")) {
            return "INSTALL_FAILED_INVALID_URI";
        }

        return "INSTALL_FAIL";
    }


    private static String parseInstallErrorMessage(String msg) {
        if (msg == null || msg.trim().isEmpty()) {
            return "安装失败：未获取到安装返回信息";
        }

        if (msg.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                || msg.contains("signatures do not match")) {
            return "安装失败：APK签名与已安装版本不一致，请使用同一签名证书重新打包后再升级";
        }

        if (msg.contains("INSTALL_FAILED_VERSION_DOWNGRADE")) {
            return "安装失败：目标版本低于当前已安装版本，系统禁止降级安装";
        }

        if (msg.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")) {
            return "安装失败：设备存储空间不足，请清理空间后重试";
        }

        if (msg.contains("INSTALL_PARSE_FAILED")
                || msg.contains("Failed parse")) {
            return "安装失败：APK解析失败，文件可能损坏、格式不正确或与当前系统不兼容";
        }

        if (msg.contains("INSTALL_FAILED_NO_MATCHING_ABIS")) {
            return "安装失败：APK架构不匹配，请确认安装包包含当前设备支持的ABI";
        }

        if (msg.contains("INSTALL_FAILED_INVALID_APK")) {
            return "安装失败：APK文件无效或已损坏";
        }

        if (msg.contains("INSTALL_FAILED_INVALID_URI")) {
            return "安装失败：APK路径无效，请检查下载文件是否存在";
        }

        if (msg.contains("Requires newer sdk version")
                || msg.contains("requires newer sdk version")) {
            return "安装失败：APK要求的Android版本高于当前设备系统版本";
        }

        if (msg.contains("Permission denied")
                || msg.contains("permission denied")) {
            return "安装失败：没有安装权限或root权限不足";
        }

        if (msg.contains("not enough free space")) {
            return "安装失败：设备剩余空间不足";
        }

        return "安装失败：系统安装命令返回失败，请查看设备日志确认具体原因：" + msg;
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

    private static class InstallResult {
        boolean success;
        int exitCode;
        String output = "";
    }

    private static class TaskMeta {
        long recordId;
        long taskId;
        String type = "game";
    }

    private static TaskMeta readTaskMeta(Context context, String type) {

        TaskMeta meta = new TaskMeta();
        meta.type = isEmpty(type) ? "game" : type;

        try {
            String taskJson = PendingStore.getTask(context, meta.type);

            if (!isEmpty(taskJson)) {
                JSONObject obj = new JSONObject(taskJson);
                meta.recordId = obj.optLong("recordId", 0);
                meta.taskId = obj.optLong("taskId", 0);
                meta.type = obj.optString("type", meta.type);
            }
        } catch (Exception e) {
            Log.e(TAG, "read task meta fail", e);
        }

        return meta;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
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

    // ============== 彻底清理应用数据 ==============
    private static void clearAppData() {
        try {
            execRootCmd("pm clear " + PKG_NAME + "\nrm -rf /data/data/" + PKG_NAME);
            Log.e(TAG, "数据清理完成");
        } catch (Exception e) {
            Log.e(TAG, "清理数据失败", e);
        }
    }

    // ============== 执行 SU 命令工具方法 ==============
    private static boolean execRootCmd(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int result = process.waitFor();
            process.destroy();
            return result == 0;
        } catch (Exception e) {
            Log.e(TAG, "execRootCmd 失败", e);
            return false;
        }
    }
}
