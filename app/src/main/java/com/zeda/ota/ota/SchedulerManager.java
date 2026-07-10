package com.zeda.ota;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public class SchedulerManager {

    private static final String TAG = "OTA_TEST";

    private static final long HALF_MONTH_MS =
            15L * 24L * 60L * 60L * 1000L;
//            60L * 1000L;

    private static final long MIN_VALID_TIME_MS =
            1704067200000L; // 2024-01-01

    public static void start(Context context) {

        SharedPreferences sp =
                context.getSharedPreferences(
                        "ota",
                        Context.MODE_PRIVATE
                );

        long last =
                sp.getLong(
                        "log_upload_time",
                        0
                );

        long now =
                System.currentTimeMillis();

        Log.e(
                TAG,
                "scheduler start, now="
                        + now
                        + ", lastLogUploadTime="
                        + last
        );

        /*
         * 设备时间不正确时，不做半月判断。
         * 否则会出现证书/时间/半月判断全部混乱。
         */
        if (now < MIN_VALID_TIME_MS) {

            Log.e(
                    TAG,
                    "scheduler skip: system time invalid"
            );

            return;
        }

        if (last <= 0) {

            Log.e(
                    TAG,
                    "scheduler init first log upload time"
            );

            sp.edit()
                    .putLong(
                            "log_upload_time",
                            now
                    )
                    .apply();

            return;
        }

        long diff =
                now - last;

        if (diff < HALF_MONTH_MS) {

            Log.e(
                    TAG,
                    "scheduler skip log upload, diffMs="
                            + diff
            );

            return;
        }

        new Thread(() -> {

            boolean ok =
                    LogUploadManager.uploadScheduleBlocking(
                            context
                    );

            Log.e(
                    TAG,
                    "half month log upload result="
                            + ok
            );

            /*
             * 关键：
             * 只有上传成功才更新时间。
             * 失败不更新时间，下次开机继续尝试。
             */
            if (ok) {

                sp.edit()
                        .putLong(
                                "log_upload_time",
                                System.currentTimeMillis()
                        )
                        .apply();
            }

        }).start();
    }
}