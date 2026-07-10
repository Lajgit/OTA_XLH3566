package com.zeda.ota;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ApkDownloader {

    private static volatile boolean mainDownloading = false;
    private static volatile boolean otaDownloading = false;

    private static final String TAG = "OTA_TEST";
    private static final int MAX_RETRY = 3;
    private static final int BUFFER_SIZE = 8192;

    public interface Callback {
        void onDownloadSuccess(File apk);

        /**
         * 下载失败时返回具体失败原因。
         * errorCode 给服务器做判断，errorMessage 给后台展示中文原因。
         */
        void onDownloadFail(String errorCode, String errorMessage);
    }

    public static void download(
            Context context,
            String version,
            String url,
            String md5,
            String messageId,
            String currentMain,
            long recordId,
            long taskId,
            Callback callback
    ) {

        if (mainDownloading) {
            Log.e(TAG, "game downloading");
            StatusReporter.report(
                    context,
                    messageId,
                    "downloading",
                    1,
                    currentMain,
                    version,
                    recordId,
                    taskId,
                    "game",
                    "TASK_ALREADY_RUNNING",
                    "主游戏APK正在下载中，忽略重复下载请求"
            );
            return;
        }

        mainDownloading = true;

        new Thread(() -> {
            File baseDir = context.getExternalFilesDir(null);
            if (baseDir == null) {

                /*
                 * 先释放标志，再执行回调。
                 */
                mainDownloading = false;

                if (callback != null) {
                    callback.onDownloadFail(
                            "EXTERNAL_DIR_NULL",
                            "主游戏APK下载失败：应用外部存储目录不可用"
                    );
                }

                return;
            }

            File file = new File(baseDir, "main.apk");

            DownloadResult result;

            try {

                result = downloadWithResume(
                        context,
                        file,
                        version,
                        url,
                        md5,
                        messageId,
                        currentMain,
                        recordId,
                        taskId,
                        "game"
                );

            } catch (Throwable e) {

                Log.e(TAG, "game download thread fatal error", e);

                result = DownloadResult.fail(
                        "DOWNLOAD_EXCEPTION",
                        "主游戏APK下载异常：" + getThrowableMessage(e)
                );

            } finally {

                /*
                 * 关键修复：
                 * 必须在回调服务器失败状态之前清除下载标志。
                 *
                 * 否则服务器收到失败后立即重发任务时，
                 * 新任务会误判为 TASK_ALREADY_RUNNING。
                 */
                mainDownloading = false;

                Log.e(TAG, "game downloading flag cleared");
            }

            /*
             * 此时 mainDownloading 已经是 false。
             * 服务器立即重发任务，也可以正常重新下载。
             */
            if (callback != null) {

                if (result.success) {

                    callback.onDownloadSuccess(file);

                } else {

                    callback.onDownloadFail(
                            result.errorCode,
                            result.errorMessage
                    );
                }
            }
        }).start();
    }

    public static void downloadself(
            Context context,
            String version,
            String url,
            String md5,
            String messageId,
            long recordId,
            long taskId,
            Callback callback
    ) {

        if (otaDownloading) {
            Log.e(TAG, "ota downloading");
            StatusReporter.report(
                    context,
                    messageId,
                    "downloading",
                    1,
                    PackageUtil.getVersion(context, "com.zeda.ota"),
                    version,
                    recordId,
                    taskId,
                    "ota",
                    "TASK_ALREADY_RUNNING",
                    "OTA应用正在下载中，忽略重复下载请求"
            );
            return;
        }

        otaDownloading = true;

        new Thread(() -> {
            File baseDir = context.getExternalFilesDir(null);
            if (baseDir == null) {
                if (callback != null) {
                    callback.onDownloadFail(
                            "EXTERNAL_DIR_NULL",
                            "OTA应用下载失败：应用外部存储目录不可用"
                    );
                }
                otaDownloading = false;
                return;
            }

            File file = new File(baseDir, "ota.apk");

            try {
                String currentOta = PackageUtil.getVersion(context, "com.zeda.ota");

                DownloadResult result = downloadWithResume(
                        context,
                        file,
                        version,
                        url,
                        md5,
                        messageId,
                        currentOta,
                        recordId,
                        taskId,
                        "ota"
                );

                if (callback != null) {
                    if (result.success) {
                        callback.onDownloadSuccess(file);
                    } else {
                        callback.onDownloadFail(result.errorCode, result.errorMessage);
                    }
                }

            } finally {
                otaDownloading = false;
            }
        }).start();
    }

    /**
     * 支持断点续传，并区分失败原因：
     * 1. HTTP_FAIL / DOWNLOAD_URL_NOT_FOUND：下载地址或服务器问题。
     * 2. DOWNLOAD_NETWORK_FAIL：网络中断、超时、DNS失败。
     * 3. MD5_FAIL：文件下载完成，但MD5校验不一致。
     * 4. RANGE_FAIL：断点续传范围异常。
     */
    private static DownloadResult downloadWithResume(
            Context context,
            File file,
            String version,
            String url,
            String md5,
            String messageId,
            String currentVersion,
            long recordId,
            long taskId,
            String type
    ) {

        String typeName = getTypeName(type);

        if (isEmpty(url)) {
            return DownloadResult.fail(
                    "URL_EMPTY",
                    typeName + "下载失败：下载地址为空"
            );
        }

        if (isEmpty(md5)) {
            return DownloadResult.fail(
                    "MD5_EMPTY",
                    typeName + "下载失败：服务器未下发MD5，无法校验文件完整性"
            );
        }

        // 防止服务端重复下发同一个任务时，又把已经下载好的包重新下载一遍
        if (file.exists() && file.length() > 0 && Md5Util.check(file, md5)) {
            Log.e(TAG, type + " apk already downloaded, md5 ok, skip download");
            StatusReporter.report(
                    context,
                    messageId,
                    "downloading",
                    100,
                    currentVersion,
                    version,
                    recordId,
                    taskId,
                    type,
                    null,
                    null
            );
            return DownloadResult.success();
        }

        OkHttpClient client =
                new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(180, TimeUnit.SECONDS)
                        .build();

        String lastErrorCode = "DOWNLOAD_FAIL";
        String lastErrorMessage = typeName + "下载失败：网络异常或服务器中断";

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {

            Response response = null;
            RandomAccessFile raf = null;
            InputStream input = null;

            try {
                long downloaded = file.exists() ? file.length() : 0;

                Request.Builder requestBuilder = new Request.Builder().url(url);

                if (downloaded > 0) {
                    requestBuilder.addHeader("Range", "bytes=" + downloaded + "-");
                    Log.e(TAG, type + " resume download, attempt=" + attempt + ", from=" + downloaded);
                } else {
                    Log.e(TAG, type + " start download, attempt=" + attempt);
                }

                response = client.newCall(requestBuilder.build()).execute();

                if (response.code() == 416) {
                    // 本地文件长度超过服务端文件长度，直接重下
                    lastErrorCode = "RANGE_FAIL";
                    lastErrorMessage = typeName + "下载失败：断点续传范围无效，已删除本地半包后重试";

                    Log.e(TAG, type + " range not satisfiable, delete local partial file");
                    if (file.exists()) {
                        file.delete();
                    }
                    sleepQuietly(3000);
                    continue;
                }

                if (!response.isSuccessful() || response.body() == null) {
                    int code = response.code();

                    lastErrorCode = getHttpErrorCode(code);
                    lastErrorMessage = getHttpErrorMessage(typeName, code);

                    Log.e(TAG, type + " download http fail, code=" + code);
                    sleepQuietly(3000);
                    continue;
                }

                boolean partialResponse = response.code() == 206;

                // 已有部分文件，但服务端返回 200，说明服务端不支持 Range，只能从 0 开始
                if (downloaded > 0 && !partialResponse) {
                    Log.e(TAG, type + " server does not support Range, restart from 0");
                    downloaded = 0;
                    if (file.exists()) {
                        file.delete();
                    }
                }

                ResponseBody body = response.body();
                long bodyLength = body.contentLength();
                long totalSize = bodyLength > 0 ? downloaded + bodyLength : -1;

                raf = new RandomAccessFile(file, "rw");
                raf.seek(downloaded);

                input = body.byteStream();

                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                long total = downloaded;

                int lastProgress = 0;
                if (totalSize > 0 && downloaded > 0) {
                    lastProgress = (int) (downloaded * 100 / totalSize);
                }

                while ((len = input.read(buffer)) != -1) {
                    raf.write(buffer, 0, len);
                    total += len;

                    if (totalSize > 0) {
                        int rawProgress =
                                (int) (total * 100 / totalSize);

                        /*
                         * 下载阶段最多只上报 99%。
                         * 100% 必须留给文件完整性校验通过以后。
                         */
                        int progress =
                                Math.min(rawProgress, 99);

                        if (progress > lastProgress &&
                                (progress == 99 ||
                                        progress - lastProgress >= 10)) {
                            lastProgress = progress;

                            Log.e(TAG, type + " download progress=" + progress);

                            StatusReporter.report(
                                    context,
                                    messageId,
                                    "downloading",
                                    progress,
                                    currentVersion,
                                    version,
                                    recordId,
                                    taskId,
                                    type,
                                    null,
                                    null
                            );
                        }
                    }
                }

                closeQuietly(input);
                input = null;

                closeQuietly(raf);
                raf = null;

                closeQuietly(response);
                response = null;

                if (!Md5Util.check(file, md5)) {

                    String errorCode =
                            "MD5_FAIL";

                    String errorMessage =
                            typeName
                                    + "下载失败：MD5校验不一致，"
                                    + "文件可能下载不完整、被替换或服务器MD5配置错误";

                    Log.e(
                            TAG,
                            type
                                    + " md5 fail after download"
                                    + ", delete bad apk"
                                    + ", no local retry"
                    );

                    if (file.exists()) {

                        boolean deleted =
                                file.delete();

                        Log.e(
                                TAG,
                                type
                                        + " bad apk delete result="
                                        + deleted
                        );
                    }

                    /*
                     * MD5失败不在设备端重试。
                     * 立即返回失败，让 OtaReceiver 上报 MD5_FAIL。
                     * 后续重试由服务器重新下发升级任务完成。
                     */
                    return DownloadResult.fail(
                            errorCode,
                            errorMessage
                    );
                }

                /*
                 * 只有 MD5 校验通过，下载才真正算 100% 完成。
                 */
                StatusReporter.report(
                        context,
                        messageId,
                        "downloading",
                        100,
                        currentVersion,
                        version,
                        recordId,
                        taskId,
                        type,
                        null,
                        null
                );

                Log.e(TAG, type + " download success, md5 ok");

                return DownloadResult.success();

            } catch (Exception e) {
                // 这里不要删除 file，否则 MQTT lost / 网络抖动后就会从 0 重新下载
                lastErrorCode = getExceptionErrorCode(e);
                lastErrorMessage = getExceptionErrorMessage(typeName, e);

                Log.e(TAG, type + " download err, keep partial file for resume", e);
                sleepQuietly(3000);

            } finally {
                closeQuietly(input);
                closeQuietly(raf);
                closeQuietly(response);
            }
        }

        Log.e(TAG, type + " download failed after " + MAX_RETRY + " attempts"
                + ", errorCode=" + lastErrorCode
                + ", errorMessage=" + lastErrorMessage);

        return DownloadResult.fail(lastErrorCode, lastErrorMessage);
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

    private static String getHttpErrorMessage(String typeName, int code) {
        if (code == 404) {
            return typeName + "下载失败：下载地址不可用，服务器返回404";
        }

        if (code == 403) {
            return typeName + "下载失败：服务器拒绝访问，可能没有下载权限或链接已过期";
        }

        if (code == 401) {
            return typeName + "下载失败：服务器鉴权失败，可能下载链接需要登录或签名已失效";
        }

        if (code >= 500) {
            return typeName + "下载失败：服务器异常，HTTP状态码=" + code;
        }

        return typeName + "下载失败：HTTP请求失败，状态码=" + code;
    }

    private static String getExceptionErrorCode(Exception e) {
        if (e instanceof SocketTimeoutException) {
            return "DOWNLOAD_TIMEOUT";
        }

        if (e instanceof UnknownHostException) {
            return "DOWNLOAD_DNS_FAIL";
        }

        if (e instanceof IllegalArgumentException) {
            return "DOWNLOAD_URL_INVALID";
        }

        return "DOWNLOAD_NETWORK_FAIL";
    }

    private static String getExceptionErrorMessage(String typeName, Exception e) {
        if (e instanceof SocketTimeoutException) {
            return typeName + "下载失败：网络超时，请检查网络或服务器响应速度";
        }

        if (e instanceof UnknownHostException) {
            return typeName + "下载失败：域名解析失败，请检查下载域名或DNS";
        }

        if (e instanceof IllegalArgumentException) {
            return typeName + "下载失败：下载地址格式不正确";
        }

        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            msg = e.getClass().getSimpleName();
        }

        return typeName + "下载失败：网络异常或连接中断，原因：" + msg;
    }

    private static String getTypeName(String type) {
        if ("game".equals(type)) {
            return "主游戏APK";
        }

        if ("ota".equals(type)) {
            return "OTA应用";
        }

        return "文件";
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
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

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception ignored) {
        }
    }

    private static class DownloadResult {
        boolean success;
        String errorCode;
        String errorMessage;

        static DownloadResult success() {
            DownloadResult r = new DownloadResult();
            r.success = true;
            return r;
        }

        static DownloadResult fail(String errorCode, String errorMessage) {
            DownloadResult r = new DownloadResult();
            r.success = false;
            r.errorCode = errorCode;
            r.errorMessage = errorMessage;
            return r;
        }
    }

    private static String getThrowableMessage(Throwable e) {

        if (e == null) {
            return "未知异常";
        }

        String message = e.getMessage();

        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }

        return message;
    }
}
