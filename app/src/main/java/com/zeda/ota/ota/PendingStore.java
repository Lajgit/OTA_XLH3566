package com.zeda.ota;

import android.content.Context;
import android.content.SharedPreferences;

public class PendingStore {

    private static final String SP = "ota_pending";

    public static void saveApk(
            Context context,
            String path,
            String version,
            String messageId
    ) {

        SharedPreferences sp =
                context.getSharedPreferences(SP, Context.MODE_PRIVATE);

        sp.edit()
                .putString("game_path", safe(path))
                .putString("game_version", safe(version))
                .putString("game_msg", safe(messageId))
                .apply();
    }

    public static void saveOta(
            Context context,
            String path,
            String version,
            String messageId
    ) {

        SharedPreferences sp =
                context.getSharedPreferences(SP, Context.MODE_PRIVATE);

        sp.edit()
                .putString("ota_path", safe(path))
                .putString("ota_version", safe(version))
                .putString("ota_msg", safe(messageId))
                .apply();
    }

    public static void saveBall(
            Context context,
            String path,
            String version,
            String messageId
    ) {

        SharedPreferences sp =
                context.getSharedPreferences(SP, Context.MODE_PRIVATE);

        sp.edit()
                .putString("ball_path", safe(path))
                .putString("ball_version", safe(version))
                .putString("ball_msg", safe(messageId))
                .apply();
    }

    public static void save(
            Context context,
            String key,
            String value
    ) {

        context.getSharedPreferences(
                        SP,
                        Context.MODE_PRIVATE
                ).edit()
                .putString(key, safe(value))
                .apply();
    }

    public static String get(
            Context context,
            String key
    ) {

        return context.getSharedPreferences(
                SP,
                Context.MODE_PRIVATE
        ).getString(key, "");
    }

    public static void clearApk(Context context) {

        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .edit()
                .remove("game_path")
                .remove("game_version")
                .remove("game_msg")
                .apply();
    }

    public static void clearOta(Context context) {

        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .edit()
                .remove("ota_path")
                .remove("ota_version")
                .remove("ota_msg")
                .apply();
    }

    public static void clearBall(Context context) {

        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .edit()
                .remove("ball_path")
                .remove("ball_version")
                .remove("ball_msg")
                .apply();
    }

    public static void saveTask(
            Context context,
            String payload
    ) {

        // 兼容旧代码：默认保存为 game 任务，同时保留旧 key。
        saveTask(context, "game", payload);
    }

    public static void saveTask(
            Context context,
            String type,
            String payload
    ) {

        String taskKey = getTaskKey(type);

        SharedPreferences.Editor editor =
                context.getSharedPreferences(SP, Context.MODE_PRIVATE)
                        .edit()
                        .putString(taskKey, safe(payload));

        if ("game_task".equals(taskKey)) {
            editor.putString("pending_task", safe(payload));
        }

        editor.apply();
    }

    public static String getTask(
            Context context
    ) {

        return getTask(context, "game");
    }

    public static String getTask(
            Context context,
            String type
    ) {

        return context.getSharedPreferences(
                SP,
                Context.MODE_PRIVATE
        ).getString(getTaskKey(type), "");
    }

    public static void clearTask(
            Context context
    ) {

        clearTask(context, "game");
    }

    public static void clearTask(
            Context context,
            String type
    ) {

        String taskKey = getTaskKey(type);

        SharedPreferences.Editor editor =
                context.getSharedPreferences(SP, Context.MODE_PRIVATE)
                        .edit()
                        .remove(taskKey);

        if ("game_task".equals(taskKey)) {
            editor.remove("pending_task");
        }

        editor.apply();
    }

    private static String getTaskKey(String type) {

        if ("ball".equals(type)) {
            return "ball_task";
        }

        if ("ota".equals(type)) {
            return "ota_task";
        }

        return "game_task";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
