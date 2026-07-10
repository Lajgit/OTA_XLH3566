package com.zeda.ota;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import java.io.DataOutputStream;
import java.util.List;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class PackageUtil {

    public static void launchMainApp(Context context) {

        try {

            Process process =
                    Runtime.getRuntime().exec("su");

            DataOutputStream os =
                    new DataOutputStream(
                            process.getOutputStream()
                    );

            os.writeBytes(
                    "monkey -p com.zeda -c android.intent.category.LAUNCHER 1\n"
            );

            os.writeBytes("exit\n");
            os.flush();

            int result =
                    process.waitFor();

        } catch (Exception e) {

            Log.e("OTA_TEST", "launch fail", e);
        }
    }

    public static boolean isRunning(
            Context context,
            String packageName
    ) {

        if (context == null ||
                packageName == null ||
                packageName.trim().isEmpty()) {

            Log.e("OTA_TEST", "isRunning fail: packageName empty");
            return false;
        }

        packageName =
                packageName.trim();

        /*
         * 第一层：ActivityManager 判断。
         * 有些系统可以看到其他 App 进程，有些 Android 13 系统可能看不到。
         */
        try {

            ActivityManager am =
                    (ActivityManager)
                            context.getSystemService(
                                    Context.ACTIVITY_SERVICE
                            );

            if (am != null) {

                List<ActivityManager.RunningAppProcessInfo> list =
                        am.getRunningAppProcesses();

                if (list != null) {

                    for (ActivityManager.RunningAppProcessInfo info : list) {

                        if (info == null) {
                            continue;
                        }

                        String processName =
                                info.processName;

                        if (processName != null &&
                                (
                                        processName.equals(packageName) ||
                                                processName.startsWith(packageName + ":")
                                )) {

                            Log.e(
                                    "OTA_TEST",
                                    "isRunning true by ActivityManager, processName="
                                            + processName
                            );

                            return true;
                        }

                        if (info.pkgList != null) {

                            for (String pkg : info.pkgList) {

                                if (packageName.equals(pkg)) {

                                    Log.e(
                                            "OTA_TEST",
                                            "isRunning true by ActivityManager pkgList, processName="
                                                    + processName
                                    );

                                    return true;
                                }
                            }
                        }
                    }
                }
            }

        } catch (Throwable e) {

            Log.e("OTA_TEST", "isRunning ActivityManager error", e);
        }

        /*
         * 第二层：root pidof 判断。
         * 你刚才日志已经验证：
         * 普通 sh 查不到 com.zeda，root 可以查到 PID。
         */
        try {

            Process process =
                    Runtime.getRuntime()
                            .exec(
                                    new String[]{
                                            "su",
                                            "0",
                                            "sh",
                                            "-c",
                                            "pidof " + packageName
                                    }
                            );

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream()
                            )
                    );

            StringBuilder output =
                    new StringBuilder();

            String line;

            while ((line = reader.readLine()) != null) {

                output.append(line)
                        .append('\n');
            }

            BufferedReader errorReader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getErrorStream()
                            )
                    );

            StringBuilder error =
                    new StringBuilder();

            while ((line = errorReader.readLine()) != null) {

                error.append(line)
                        .append('\n');
            }

            int code =
                    process.waitFor();

            String pidOutput =
                    output.toString().trim();

            Log.e(
                    "OTA_TEST",
                    "isRunning root pidof"
                            + ", package="
                            + packageName
                            + ", code="
                            + code
                            + ", output="
                            + pidOutput
                            + ", error="
                            + error.toString().trim()
            );

            if (code == 0 &&
                    !pidOutput.isEmpty()) {

                return true;
            }

        } catch (Throwable e) {

            Log.e("OTA_TEST", "isRunning root pidof error", e);
        }

        Log.e(
                "OTA_TEST",
                "isRunning false, package="
                        + packageName
        );

        return false;
    }

    public static void debugIsRunning(
            Context context,
            String packageName
    ) {

        Log.e("OTA_TEST", "========== debugIsRunning start ==========");

        /*
         * 1. ActivityManager 测试
         */
        try {

            ActivityManager am =
                    (ActivityManager)
                            context.getSystemService(
                                    Context.ACTIVITY_SERVICE
                            );

            if (am == null) {

                Log.e("OTA_TEST", "debug ActivityManager am=null");

            } else {

                List<ActivityManager.RunningAppProcessInfo> list =
                        am.getRunningAppProcesses();

                if (list == null) {

                    Log.e("OTA_TEST", "debug ActivityManager list=null");

                } else {

                    for (ActivityManager.RunningAppProcessInfo info : list) {

                        if (info == null) {
                            continue;
                        }

                        if (packageName.equals(info.processName)) {

                            Log.e(
                                    "OTA_TEST",
                                    "debug ActivityManager found processName="
                                            + info.processName
                            );
                        }
                    }
                }
            }

        } catch (Throwable e) {

            Log.e("OTA_TEST", "debug ActivityManager error", e);
        }

        /*
         * 2. OTA App 普通权限 sh 测试
         */
        try {

            Process process =
                    Runtime.getRuntime()
                            .exec(
                                    new String[]{
                                            "sh",
                                            "-c",
                                            "id; pidof " + packageName + "; echo code=$?"
                                    }
                            );

            java.io.BufferedReader reader =
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    process.getInputStream()
                            )
                    );

            StringBuilder sb =
                    new StringBuilder();

            String line;

            while ((line = reader.readLine()) != null) {

                sb.append(line).append('\n');
            }

            int code =
                    process.waitFor();

            Log.e(
                    "OTA_TEST",
                    "debug normal sh output:\n"
                            + sb
                            + "processExitCode="
                            + code
            );

        } catch (Throwable e) {

            Log.e("OTA_TEST", "debug normal sh error", e);
        }

        /*
         * 3. OTA App 通过 su root 测试
         */
        try {

            Process process =
                    Runtime.getRuntime()
                            .exec(
                                    new String[]{
                                            "su",
                                            "0",
                                            "sh",
                                            "-c",
                                            "id; pidof " + packageName + "; echo code=$?"
                                    }
                            );

            java.io.BufferedReader reader =
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    process.getInputStream()
                            )
                    );

            StringBuilder sb =
                    new StringBuilder();

            String line;

            while ((line = reader.readLine()) != null) {

                sb.append(line).append('\n');
            }

            int code =
                    process.waitFor();

            Log.e(
                    "OTA_TEST",
                    "debug root sh output:\n"
                            + sb
                            + "processExitCode="
                            + code
            );

        } catch (Throwable e) {

            Log.e("OTA_TEST", "debug root sh error", e);
        }

        Log.e("OTA_TEST", "========== debugIsRunning end ==========");
    }

    public static String getVersion(
            Context context,
            String pkg
    ) {

        try {

            return context.getPackageManager()
                    .getPackageInfo(pkg, 0)
                    .versionName;

        } catch (Exception e) {

            return "0.0.0";
        }
    }
}