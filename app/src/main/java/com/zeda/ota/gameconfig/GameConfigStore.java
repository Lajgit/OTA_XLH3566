package com.zeda.ota.gameconfig;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏配置状态仓库。
 *
 * <p>所有写入方法均使用 commit()，用于 ACK 前可靠落盘。</p>
 */
public final class GameConfigStore {

    private static final String TAG = "OTA_TEST";
    private static final String SP = "game_config_state";

    private static final String KEY_PENDING_RECORD = "pending_record";
    private static final String KEY_APPLIED_RECORD = "applied_record";
    private static final String KEY_RECENT_RESULTS = "recent_results";
    private static final String KEY_COMMAND_RESULT_OUTBOX = "command_result_outbox";
    private static final String KEY_GAME_CONFIG_REPORT_OUTBOX = "game_config_report_outbox";

    private final SharedPreferences sp;

    public GameConfigStore(
            Context context
    ) {
        this.sp = context.getApplicationContext()
                .getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public boolean savePending(
            GameConfigPending pending
    ) {
        if (pending == null) {
            return false;
        }

        try {
            return sp.edit()
                    .putString(KEY_PENDING_RECORD, pending.toJson().toString())
                    .commit();
        } catch (Exception e) {
            Log.e(TAG, "save game config pending fail", e);
            return false;
        }
    }

    public GameConfigPending getPending() {
        try {
            return GameConfigPending.fromJson(
                    sp.getString(KEY_PENDING_RECORD, "")
            );
        } catch (Exception e) {
            Log.e(TAG, "read game config pending fail", e);
            return null;
        }
    }

    public boolean clearPending() {
        return sp.edit()
                .remove(KEY_PENDING_RECORD)
                .commit();
    }

    public boolean saveApplied(
            GameConfigSnapshot snapshot,
            long appliedAt
    ) {
        if (snapshot == null) {
            return false;
        }

        try {
            JSONObject json = new JSONObject();
            json.put("gameCode", snapshot.getGameCode());
            json.put("schemaVersion", snapshot.getSchemaVersion());
            json.put("configVersion", snapshot.getConfigVersion());
            json.put("configHash", snapshot.getConfigHash());
            json.put("canonicalConfig", snapshot.getCanonicalConfig());
            json.put("appliedAt", appliedAt);

            return sp.edit()
                    .putString(KEY_APPLIED_RECORD, json.toString())
                    .commit();
        } catch (Exception e) {
            Log.e(TAG, "save game config applied fail", e);
            return false;
        }
    }

    public GameConfigSnapshot getApplied() {
        try {
            String value = sp.getString(KEY_APPLIED_RECORD, "");
            if (value == null || value.trim().isEmpty()) {
                return null;
            }

            JSONObject json = new JSONObject(value);
            String canonicalConfig = json.optString("canonicalConfig", "");
            if (canonicalConfig.trim().isEmpty()) {
                return null;
            }

            JsonObject config = JsonParser.parseString(canonicalConfig)
                    .getAsJsonObject();
            return new GameConfigSnapshot(
                    json.optString("gameCode", ""),
                    json.optInt("schemaVersion", 0),
                    json.optLong("configVersion", 0),
                    json.optString("configHash", ""),
                    config,
                    canonicalConfig
            );
        } catch (Exception e) {
            Log.e(TAG, "read game config applied fail", e);
            return null;
        }
    }

    public long getAppliedAt() {
        try {
            String value = sp.getString(KEY_APPLIED_RECORD, "");
            if (value == null || value.trim().isEmpty()) {
                return 0;
            }
            return new JSONObject(value).optLong("appliedAt", 0);
        } catch (Exception e) {
            Log.e(TAG, "read game config appliedAt fail", e);
            return 0;
        }
    }

    public boolean clearApplied() {
        return sp.edit()
                .remove(KEY_APPLIED_RECORD)
                .commit();
    }

    public boolean addRecentResult(
            GameConfigResultRecord record
    ) {
        if (record == null || record.getMessageId().isEmpty()) {
            return false;
        }

        try {
            JSONArray oldArray = readRecentArray();
            JSONArray newArray = new JSONArray();

            for (int index = 0; index < oldArray.length(); index++) {
                JSONObject item = oldArray.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String messageId = item.optString("messageId", "");
                if (!record.getMessageId().equals(messageId)) {
                    newArray.put(item);
                }
            }

            newArray.put(record.toJson());
            JSONArray limitedArray = trimRecentResults(newArray);

            return sp.edit()
                    .putString(KEY_RECENT_RESULTS, limitedArray.toString())
                    .commit();
        } catch (Exception e) {
            Log.e(TAG, "save game config recent result fail", e);
            return false;
        }
    }

    public GameConfigResultRecord findRecentResult(
            String messageId
    ) {
        if (messageId == null || messageId.trim().isEmpty()) {
            return null;
        }

        try {
            JSONArray array = readRecentArray();
            for (int index = array.length() - 1; index >= 0; index--) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                if (messageId.equals(item.optString("messageId", ""))) {
                    return GameConfigResultRecord.fromJson(item);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "find game config recent result fail", e);
        }

        return null;
    }

    public List<GameConfigResultRecord> getRecentResults() {
        List<GameConfigResultRecord> records = new ArrayList<>();

        try {
            JSONArray array = readRecentArray();
            for (int index = 0; index < array.length(); index++) {
                GameConfigResultRecord record =
                        GameConfigResultRecord.fromJson(array.optJSONObject(index));
                if (record != null) {
                    records.add(record);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "read game config recent results fail", e);
        }

        return records;
    }

    public boolean saveCommandResultOutbox(
            String payload
    ) {
        return saveString(KEY_COMMAND_RESULT_OUTBOX, payload);
    }

    public String getCommandResultOutbox() {
        return sp.getString(KEY_COMMAND_RESULT_OUTBOX, "");
    }

    public boolean clearCommandResultOutbox() {
        return sp.edit()
                .remove(KEY_COMMAND_RESULT_OUTBOX)
                .commit();
    }

    public boolean saveGameConfigReportOutbox(
            String payload
    ) {
        return saveString(KEY_GAME_CONFIG_REPORT_OUTBOX, payload);
    }

    public String getGameConfigReportOutbox() {
        return sp.getString(KEY_GAME_CONFIG_REPORT_OUTBOX, "");
    }

    public boolean clearGameConfigReportOutbox() {
        return sp.edit()
                .remove(KEY_GAME_CONFIG_REPORT_OUTBOX)
                .commit();
    }

    public boolean clearAll() {
        return sp.edit()
                .remove(KEY_PENDING_RECORD)
                .remove(KEY_APPLIED_RECORD)
                .remove(KEY_RECENT_RESULTS)
                .remove(KEY_COMMAND_RESULT_OUTBOX)
                .remove(KEY_GAME_CONFIG_REPORT_OUTBOX)
                .commit();
    }

    private boolean saveString(
            String key,
            String value
    ) {
        return sp.edit()
                .putString(key, value == null ? "" : value)
                .commit();
    }

    private JSONArray readRecentArray() throws Exception {
        String value = sp.getString(KEY_RECENT_RESULTS, "[]");
        if (value == null || value.trim().isEmpty()) {
            value = "[]";
        }
        return new JSONArray(value);
    }

    private JSONArray trimRecentResults(
            JSONArray source
    ) {
        JSONArray target = new JSONArray();
        int start = Math.max(
                0,
                source.length() - GameConfigProtocol.RECENT_MESSAGE_LIMIT
        );

        for (int index = start; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item != null) {
                target.put(item);
            }
        }
        return target;
    }
}
