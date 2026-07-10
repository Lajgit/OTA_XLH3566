package com.zeda.ota;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class BallFileDownloader {

    private static final String TAG = "OTA_TEST";
    private static volatile boolean ballDownloading = false;

    public static void download(
            Context context,
            String version,
            String url,
            String md5,
            String messageId
    ) {
        download(context, version, url, md5, messageId, 0, 0);
    }

    public static void download(
            Context context,
            String version,
            String url,
            String md5,
            String messageId,
            long recordId,
            long taskId
    ) {

        if (ballDownloading) {

            Log.e(TAG, "ball downloading");

            StatusReporter.report(
                    context,
                    messageId,
                    "downloading",
                    1,
                    PackageUtil.getVersion(context, "com.zeda"),
                    version,
                    recordId,
                    taskId,
                    "ball",
                    "TASK_ALREADY_RUNNING",
                    "弹珠板固件正在下载中，忽略重复下载请求"
            );

            return;
        }

        ballDownloading = true;

        new Thread(() -> {

            File dir = null;
            File finalFile = null;
            File tmpFile = null;
            Response response = null;
            InputStream input = null;
            RandomAccessFile raf = null;

            try {

                if (isEmpty(url)) {
                    reportFailAndFinish(
                            context,
                            messageId,
                            version,
                            recordId,
                            taskId,
                            "URL_EMPTY",
                            "弹珠板固件下载失败：下载地址为空"
                    );
                    return;
                }

                if (isEmpty(md5)) {
                    reportFailAndFinish(
                            context,
                            messageId,
                            version,
                            recordId,
                            taskId,
                            "MD5_EMPTY",
                            "弹珠板固件下载失败：服务器未下发MD5，无法校验文件完整性"
                    );
                    return;
                }

                dir = new File(Environment
                        .getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS
                        ), "ball_upgrade");

                if (!dir.exists()) {

                    boolean ok = dir.mkdirs();

                    Log.e(TAG,
                            "mkdirs=" + ok +
                                    " path=" + dir.getAbsolutePath());

                    if (!ok) {

                        reportFailAndFinish(
                                context,
                                messageId,
                                version,
                                recordId,
                                taskId,
                                "DIR_CREATE_FAIL",
                                "弹珠板固件下载失败：无法创建下载目录"
                        );

                        return;
                    }
                }

                finalFile = new File(
                        dir,
                        "ball_V" + version + ".bin"
                );

                tmpFile = new File(
                        dir,
                        "ball_V" + version + ".bin.tmp"
                );

                if (tmpFile.exists()) {
                    tmpFile.delete();
                }

                OkHttpClient client =
                        new OkHttpClient.Builder()
                                .connectTimeout(30, TimeUnit.SECONDS)
                                .readTimeout(180, TimeUnit.SECONDS)
                                .build();

                Request request =
                        new Request.Builder()
                                .url(url)
                                .build();

                response =
                        client.newCall(request)
                                .execute();

                if (!response.isSuccessful() || response.body() == null) {

                    reportFailAndFinish(
                            context,
                            messageId,
                            version,
                            recordId,
                            taskId,
                            getHttpErrorCode(response.code()),
                            getHttpErrorMessage(response.code())
                    );

                    return;
                }

                input = response.body().byteStream();
                raf = new RandomAccessFile(tmpFile, "rw");

                byte[] buffer = new byte[8192];
                int len;
                long total = 0;
                long size = response.body().contentLength();
                int lastProgress = 0;

                while ((len = input.read(buffer)) != -1) {

                    raf.write(buffer, 0, len);
                    total += len;

                    if (size > 0) {

                        int progress =
                                (int) (total * 100 / size);

                        if (progress == 100 || progress - lastProgress >= 5) {

                            lastProgress = progress;

                            Log.e(TAG, "ball progress=" + progress);

                            StatusReporter.report(
                                    context,
                                    messageId,
                                    "downloading",
                                    progress,
                                    PackageUtil.getVersion(context, "com.zeda"),
                                    version,
                                    recordId,
                                    taskId,
                                    "ball",
                                    null,
                                    null
                            );
                        }
                    }
                }

                closeQuietly(raf);
                raf = null;

                closeQuietly(input);
                input = null;

                closeQuietly(response);
                response = null;

                if (!Md5Util.check(tmpFile, md5)) {

                    if (tmpFile.exists()) {
                        tmpFile.delete();
                    }

                    reportFailAndFinish(
                            context,
                            messageId,
                            version,
                            recordId,
                            taskId,
                            "MD5_FAIL",
                            "弹珠板固件校验失败：MD5不一致，文件可能下载不完整或已损坏"
                    );

                    return;
                }

                // 新文件校验成功后，才删除旧 bin，避免失败时把旧文件删掉。
                File[] oldFiles = dir.listFiles();
                if (oldFiles != null) {
                    for (File old : oldFiles) {
                        if ((old.getName().endsWith(".bin")
                                || old.getName().endsWith(".hex"))
                                && !old.getName().equals(finalFile.getName())) {
                            old.delete();
                        }
                    }
                }

                if (finalFile.exists()) {
                    finalFile.delete();
                }

                if (!tmpFile.renameTo(finalFile)) {
                    reportFailAndFinish(
                            context,
                            messageId,
                            version,
                            recordId,
                            taskId,
                            "FILE_RENAME_FAIL",
                            "弹珠板固件下载失败：临时文件重命名失败"
                    );
                    return;
                }

                saveVersionInfo(
                        dir,
                        version,
                        finalFile.getName(),
                        md5
                );

                PendingStore.saveBall(
                        context,
                        finalFile.getAbsolutePath(),
                        version,
                        messageId
                );

                StatusReporter.report(
                        context,
                        messageId,
                        "success",
                        100,
                        PackageUtil.getVersion(context, "com.zeda"),
                        version,
                        recordId,
                        taskId,
                        "ball",
                        null,
                        null
                );

                PendingStore.clearTask(context, "ball");
                finishUpgrade(context);

                Log.e(
                        TAG,
                        "ball download success = " + finalFile.getAbsolutePath()
                );

            } catch (Exception e) {

                Log.e(TAG, "ball download err", e);

                if (tmpFile != null && tmpFile.exists()) {
                    tmpFile.delete();
                }

                reportFailAndFinish(
                        context,
                        messageId,
                        version,
                        recordId,
                        taskId,
                        "BALL_DOWNLOAD_FAIL",
                        "弹珠板固件下载异常：" + getExceptionMessage(e)
                );

            } finally {
                closeQuietly(raf);
                closeQuietly(input);
                closeQuietly(response);
                ballDownloading = false;
            }

        }).start();
    }

    private static void reportFailAndFinish(
            Context context,
            String messageId,
            String version,
            long recordId,
            long taskId,
            String errorCode,
            String errorMessage
    ) {

        StatusReporter.report(
                context,
                messageId,
                "failed",
                0,
                PackageUtil.getVersion(context, "com.zeda"),
                version,
                recordId,
                taskId,
                "ball",
                errorCode,
                errorMessage
        );

        PendingStore.clearTask(context, "ball");
        finishUpgrade(context);
    }

    private static String getHttpErrorCode(int code) {
        if (code == 404) {
            return "DOWNLOAD_URL_NOT_FOUND";
        }

        if (code == 403) {
            return "DOWNLOAD_FORBIDDEN";
        }

        if (code == 401) {
            return "DOWNLOAD_UNAUTHORIZED";
        }

        if (code >= 500) {
            return "DOWNLOAD_SERVER_ERROR";
        }

        return "HTTP_FAIL";
    }

    private static String getHttpErrorMessage(int code) {
        if (code == 404) {
            return "弹珠板固件下载失败：下载地址不可用，服务器返回404";
        }

        if (code == 403) {
            return "弹珠板固件下载失败：服务器拒绝访问，可能没有下载权限或链接已过期";
        }

        if (code == 401) {
            return "弹珠板固件下载失败：服务器鉴权失败，可能下载链接需要登录或签名已失效";
        }

        if (code >= 500) {
            return "弹珠板固件下载失败：服务器异常，HTTP状态码=" + code;
        }

        return "弹珠板固件下载失败：HTTP请求失败，状态码=" + code;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void closeQuietly(Object obj) {
        try {
            if (obj == null) return;

            if (obj instanceof InputStream) {
                ((InputStream) obj).close();
            } else if (obj instanceof RandomAccessFile) {
                ((RandomAccessFile) obj).close();
            } else if (obj instanceof Response) {
                ((Response) obj).close();
            }
        } catch (Exception ignored) {
        }
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

    /**
     * 保存版本信息
     */
    private static void saveVersionInfo(
            File dir,
            String version,
            String fileName,
            String md5
    ) {

        try {

            JSONObject json = new JSONObject();

            json.put("version", version);
            json.put("file", fileName);
            json.put("md5", md5);
            json.put("timestamp", System.currentTimeMillis());

            File versionFile =
                    new File(dir, "version.json");

            FileWriter writer =
                    new FileWriter(versionFile);

            writer.write(json.toString());

            writer.flush();
            writer.close();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "save version info fail",
                    e
            );
        }
    }
}
