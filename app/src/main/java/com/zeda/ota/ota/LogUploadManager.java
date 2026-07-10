package com.zeda.ota;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LogUploadManager {

    private static final String TAG = "OTA_TEST";

    private static final String BASE_URL =
            "https://api.dzxd.top";

    private static volatile boolean uploading = false;

    /**
     * 新文档：command/task 拉日志上传。
     */
    public static boolean uploadCommandBlocking(
            Context context,
            String messageId,
            JSONArray logTypes
    ) {

        return uploadInternalBlocking(
                context,
                "command",
                messageId,
                getFirstLogType(logTypes)
        );
    }

    /**
     * 兼容旧代码：不要再用于 command/task。
     * 如果后端没有支持 schedule 上传，建议 SchedulerManager 里先不要调用这个。
     */
    public static void upload(Context context) {

        new Thread(() -> {

            boolean ok =
                    uploadScheduleBlocking(
                            context
                    );

            Log.e(
                    TAG,
                    "schedule log upload result="
                            + ok
            );

        }).start();
    }

    /**
     * 半月自动上传。
     * 注意：这个不是 command/task。
     * 后端需要支持 uploadSource=schedule，否则可能会被拒绝。
     */
    public static boolean uploadScheduleBlocking(
            Context context
    ) {

        String messageId = "";
//                "schedule-"
//                        + DeviceUtil.getDeviceId(context)
//                        + "-"
//                        + System.currentTimeMillis();

        return uploadInternalBlocking(
                context,
                "scheduled",
                messageId,
                "system"
        );
    }

    private static boolean uploadInternalBlocking(
            Context context,
            String uploadSource,
            String messageId,
            String logType
    ) {

        if (uploading) {

            Log.e(TAG, "log uploading");

            return false;
        }

        uploading =
                true;

        File zipFile = null;

        try {

            File logDir =
                    new File(
                            Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS
                            ),
                            "zeda/log"
                    );

            Log.e(TAG, "log dir=" + logDir.getAbsolutePath());

            if (!logDir.exists()) {

                Log.e(TAG, "log dir not exist");

                return false;
            }

            File[] files =
                    logDir.listFiles();

            if (files == null ||
                    files.length == 0) {

                Log.e(TAG, "log empty");

                return false;
            }

            String deviceNo =
                    DeviceUtil.getDeviceId(context);

            zipFile =
                    new File(
                            context.getCacheDir(),
                            "device-log-"
                                    + deviceNo
                                    + "-"
                                    + System.currentTimeMillis()
                                    + ".zip"
                    );

            boolean zipOk =
                    zipLogs(
                            files,
                            zipFile
                    );

            if (!zipOk ||
                    !zipFile.exists() ||
                    zipFile.length() <= 0) {

                Log.e(TAG, "zip log fail");

                return false;
            }

            String originalFilename =
                    zipFile.getName();

            long fileSize =
                    zipFile.length();

            long timestamp =
                    System.currentTimeMillis();

            String nonce =
                    UUID.randomUUID()
                            .toString()
                            .replace(
                                    "-",
                                    ""
                            );

            MqttCredential cred =
                    MqttCredentialStore.load(
                            context
                    );

            if (cred == null ||
                    cred.password == null ||
                    cred.password.trim().isEmpty()) {

                Log.e(TAG, "mqtt password empty, can not sign log upload");

                return false;
            }

            String signRaw =
                    deviceNo
                            + "|"
                            + uploadSource
                            + "|"
                            + messageId
                            + "|"
                            + logType
                            + "|"
                            + originalFilename
                            + "|"
                            + fileSize
                            + "|"
                            + timestamp
                            + "|"
                            + nonce;

            String signature =
                    HmacUtil.hmacSha256Base64Url(
                            signRaw,
                            cred.password
                    );

            Log.e(TAG, "log upload source=" + uploadSource);
            Log.e(TAG, "log upload messageId=" + messageId);
            Log.e(TAG, "log upload logType=" + logType);
            Log.e(TAG, "log upload file=" + originalFilename);
            Log.e(TAG, "log upload fileSize=" + fileSize);
            Log.e(TAG, "log upload signRaw=" + signRaw);

            RequestBody fileBody =
                    RequestBody.create(
                            zipFile,
                            MediaType.parse("application/zip")
                    );

            MultipartBody requestBody =
                    new MultipartBody.Builder()
                            .setType(
                                    MultipartBody.FORM
                            )
                            .addFormDataPart(
                                    "file",
                                    originalFilename,
                                    fileBody
                            )
                            .addFormDataPart(
                                    "deviceNo",
                                    deviceNo
                            )
                            .addFormDataPart(
                                    "uploadSource",
                                    uploadSource
                            )
                            .addFormDataPart(
                                    "messageId",
                                    messageId
                            )
                            .addFormDataPart(
                                    "logType",
                                    logType
                            )
                            .addFormDataPart(
                                    "timestamp",
                                    String.valueOf(timestamp)
                            )
                            .addFormDataPart(
                                    "nonce",
                                    nonce
                            )
                            .addFormDataPart(
                                    "signature",
                                    signature
                            )
                            .build();

            String url =
                    BASE_URL
                            + "/api/file/public/device-log/upload";

            OkHttpClient client =
                    new OkHttpClient.Builder()
                            .connectTimeout(
                                    30,
                                    TimeUnit.SECONDS
                            )
                            .readTimeout(
                                    120,
                                    TimeUnit.SECONDS
                            )
                            .writeTimeout(
                                    120,
                                    TimeUnit.SECONDS
                            )
                            .build();

            Request request =
                    new Request.Builder()
                            .url(url)
                            .post(requestBody)
                            .build();

            Response response =
                    client.newCall(request)
                            .execute();

            String resp =
                    response.body() != null
                            ? response.body().string()
                            : "";

            Log.e(TAG, "log upload code=" + response.code());
            Log.e(TAG, "log upload resp=" + resp);

            return response.isSuccessful() &&
                    resp.contains("\"success\":true");

        } catch (Throwable e) {

            Log.e(TAG, "log upload fail", e);

            return false;

        } finally {

            uploading =
                    false;

            try {
                if (zipFile != null &&
                        zipFile.exists()) {
                    zipFile.delete();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean zipLogs(
            File[] files,
            File zipFile
    ) {

        ZipOutputStream zos = null;

        try {

            zos =
                    new ZipOutputStream(
                            new FileOutputStream(
                                    zipFile
                            )
                    );

            byte[] buffer =
                    new byte[8192];

            int count =
                    0;

            for (File f : files) {

                if (f == null ||
                        !f.exists() ||
                        !f.isFile()) {

                    continue;
                }

                if (f.getName().endsWith(".uploaded")) {
                    continue;
                }

                FileInputStream fis = null;

                try {

                    fis =
                            new FileInputStream(
                                    f
                            );

                    ZipEntry entry =
                            new ZipEntry(
                                    f.getName()
                            );

                    zos.putNextEntry(
                            entry
                    );

                    int len;

                    while ((len = fis.read(buffer)) != -1) {

                        zos.write(
                                buffer,
                                0,
                                len
                        );
                    }

                    zos.closeEntry();

                    count++;

                } finally {

                    try {
                        if (fis != null) {
                            fis.close();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            zos.finish();

            Log.e(TAG, "zip log count=" + count);

            return count > 0;

        } catch (Throwable e) {

            Log.e(TAG, "zipLogs fail", e);

            return false;

        } finally {

            try {
                if (zos != null) {
                    zos.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String getFirstLogType(
            JSONArray logTypes
    ) {

        try {

            if (logTypes != null &&
                    logTypes.length() > 0) {

                String type =
                        logTypes.optString(
                                0,
                                ""
                        );

                if (type != null &&
                        !type.trim().isEmpty()) {

                    return type.trim();
                }
            }

        } catch (Throwable ignored) {
        }

        return "system";
    }
}