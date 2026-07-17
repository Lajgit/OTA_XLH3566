package com.zeda.ota.gameconfig;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zeda.ota.DeviceUtil;
import com.zeda.ota.PackageUtil;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 游戏配置同步状态机。
 *
 * <p>当前类只负责校验、pending 落盘、Unity 派发、Unity 结果处理和 outbox 写入。
 * MQTT 订阅入口、MQTT 实际发布和启动恢复将在后续步骤接入。</p>
 */
public final class GameConfigManager {

    private static final String TAG = "OTA_TEST";

    public static final long APPLY_TIMEOUT_MS = 60_000L;
    public static final long QUERY_TIMEOUT_MS = 30_000L;
    public static final int MAX_DISPATCH_COUNT = 2;

    private static volatile GameConfigManager instance;

    private final Context appContext;
    private final GameConfigStore store;
    private final GameConfigValidator validator;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private GameConfigManager(
            Context context
    ) {
        this.appContext = context.getApplicationContext();
        this.store = new GameConfigStore(appContext);
        this.validator = new GameConfigValidator();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static GameConfigManager get(
            Context context
    ) {
        if (instance == null) {
            synchronized (GameConfigManager.class) {
                if (instance == null) {
                    instance = new GameConfigManager(context);
                }
            }
        }
        return instance;
    }

    public void handleMqttCommand(
            String payload
    ) {
        executor.execute(() -> handleMqttCommandInternal(payload));
    }

    public void handleGameEvent(
            String payload
    ) {
        executor.execute(() -> handleGameEventInternal(payload));
    }

    public void retryPending() {
        executor.execute(() -> {
            GameConfigPending pending = store.getPending();
            if (pending != null) {
                dispatchPending(pending);
                return;
            }

            GameConfigSnapshot applied = store.getApplied();
            if (applied != null) {
                dispatchApplied(applied);
            }
        });
    }

    public void queryGameConfig(
            String requestId
    ) {
        String safeRequestId = isEmpty(requestId)
                ? newRequestId()
                : requestId;

        boolean sent = GameConfigBridge.sendQuery(appContext, safeRequestId);
        if (sent) {
            scheduleQueryTimeout(safeRequestId);
        }
    }

    private void handleMqttCommandInternal(
            String payload
    ) {
        if (isEmpty(payload)) {
            Log.e(TAG, "ignore game config command: payload empty");
            return;
        }

        GameConfigCommand command;
        try {
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            command = validator.validateCommand(json);
        } catch (GameConfigValidationException e) {
            Log.e(TAG, "game config command invalid: " + e.getMessage());
            cacheInvalidCommandResult(payload, e.getErrorCode(), e.getMessage());
            return;
        } catch (Exception e) {
            Log.e(TAG, "parse game config command fail", e);
            cacheInvalidCommandResult(
                    payload,
                    GameConfigProtocol.ERROR_CONFIG_INVALID,
                    "配置指令解析失败：" + safeMessage(e)
            );
            return;
        }

        GameConfigResultRecord oldResult =
                store.findRecentResult(command.getMessageId());
        if (oldResult != null) {
            cacheCommandResult(oldResult);
            Log.e(TAG, "game config duplicate message replay=" + command.getMessageId());
            return;
        }

        GameConfigPending currentPending = store.getPending();
        if (currentPending != null
                && command.getMessageId().equals(currentPending.getMessageId())) {
            cacheAck(command);
            dispatchPending(currentPending);
            Log.e(TAG, "game config duplicate pending message=" + command.getMessageId());
            return;
        }

        GameConfigSnapshot applied = store.getApplied();
        if (applied != null) {
            if (command.getConfigVersion() < applied.getConfigVersion()) {
                finishCommand(
                        command.getMessageId(),
                        command.getConfigVersion(),
                        command.getConfigHash(),
                        GameConfigProtocol.STATUS_FAILED,
                        GameConfigProtocol.ERROR_VERSION_STALE,
                        "配置版本低于设备已生效版本"
                );
                return;
            }

            if (command.getConfigVersion() == applied.getConfigVersion()) {
                if (!command.getConfigHash().equals(applied.getConfigHash())) {
                    finishCommand(
                            command.getMessageId(),
                            command.getConfigVersion(),
                            command.getConfigHash(),
                            GameConfigProtocol.STATUS_FAILED,
                            GameConfigProtocol.ERROR_VERSION_CONFLICT,
                            "相同配置版本对应不同配置哈希"
                    );
                    return;
                }

                finishSuccessFromCommand(command, "配置版本已生效，幂等返回成功");
                return;
            }
        }

        long now = System.currentTimeMillis();
        GameConfigPending pending = GameConfigPending.fromCommand(
                command,
                newRequestId(),
                now
        );

        boolean saved = store.savePending(pending);
        if (!saved) {
            finishCommand(
                    command.getMessageId(),
                    command.getConfigVersion(),
                    command.getConfigHash(),
                    GameConfigProtocol.STATUS_FAILED,
                    GameConfigProtocol.ERROR_APPLY_FAILED,
                    "配置任务可靠保存失败"
            );
            return;
        }

        cacheAck(command);
        dispatchPending(pending);
    }

    private void handleGameEventInternal(
            String payload
    ) {
        if (isEmpty(payload)) {
            Log.e(TAG, "ignore game config event: payload empty");
            return;
        }

        JSONObject json;
        try {
            json = new JSONObject(payload);
        } catch (Exception e) {
            Log.e(TAG, "parse game config event fail", e);
            return;
        }

        int protocolVersion = json.optInt("protocolVersion", 0);
        if (protocolVersion != GameConfigProtocol.PROTOCOL_VERSION) {
            Log.e(TAG, "ignore game config event: protocolVersion=" + protocolVersion);
            return;
        }

        String operation = json.optString("operation", "");
        if (GameConfigProtocol.OP_GAME_CONFIG_READY.equals(operation)) {
            handleGameReady();
            return;
        }

        if (GameConfigProtocol.OP_APPLY_GAME_CONFIG.equals(operation)) {
            handleApplyResult(json);
            return;
        }

        if (GameConfigProtocol.OP_QUERY_GAME_CONFIG.equals(operation)) {
            Log.e(TAG, "game config query result received, state machine reserved");
            return;
        }

        Log.e(TAG, "ignore unsupported game config event operation=" + operation);
    }

    private void handleGameReady() {
        GameConfigPending pending = store.getPending();
        if (pending != null) {
            dispatchPending(pending);
            return;
        }

        GameConfigSnapshot applied = store.getApplied();
        if (applied != null) {
            dispatchApplied(applied);
        }
    }

    private void handleApplyResult(
            JSONObject json
    ) {
        GameConfigPending pending = store.getPending();
        if (pending == null) {
            Log.e(TAG, "ignore game config apply result: pending empty");
            return;
        }

        String requestId = json.optString("requestId", "");
        if (!pending.getRequestId().equals(requestId)) {
            Log.e(
                    TAG,
                    "ignore stale game config result, requestId="
                            + requestId
                            + ", pending="
                            + pending.getRequestId()
            );
            return;
        }

        String status = json.optString("status", "");
        String resultCode = json.optString("resultCode", "");
        String resultMessage = json.optString("resultMessage", "");

        if (GameConfigProtocol.STATUS_SUCCESS.equals(status)) {
            handleApplySuccess(pending, json, resultMessage);
            return;
        }

        if (GameConfigProtocol.STATUS_FAILED.equals(status)
                || "error".equals(status)) {
            finishPending(
                    pending,
                    GameConfigProtocol.STATUS_FAILED,
                    isEmpty(resultCode)
                            ? GameConfigProtocol.ERROR_APPLY_FAILED
                            : resultCode,
                    isEmpty(resultMessage)
                            ? "Unity 返回游戏配置执行失败"
                            : resultMessage
            );
            return;
        }

        Log.e(TAG, "ignore game config result with unknown status=" + status);
    }

    private void handleApplySuccess(
            GameConfigPending pending,
            JSONObject json,
            String resultMessage
    ) {
        try {
            JSONObject data = json.optJSONObject("data");
            if (data == null) {
                throw new GameConfigValidationException(
                        GameConfigProtocol.ERROR_CONFIG_INVALID,
                        "Unity 成功结果缺少 data"
                );
            }

            JSONObject configObject = data.optJSONObject("config");
            if (configObject == null) {
                throw new GameConfigValidationException(
                        GameConfigProtocol.ERROR_CONFIG_INVALID,
                        "Unity 成功结果缺少 data.config"
                );
            }

            GameConfigSnapshot snapshot = validator.validateSnapshot(
                    data.optString("gameCode", ""),
                    data.optInt("schemaVersion", 0),
                    data.optLong("configVersion", 0),
                    data.optString("configHash", ""),
                    JsonParser.parseString(configObject.toString()).getAsJsonObject()
            );

            if (snapshot.getConfigVersion() != pending.getConfigVersion()) {
                throw new GameConfigValidationException(
                        GameConfigProtocol.ERROR_CONFIG_INVALID,
                        "Unity 返回 configVersion 与 pending 不一致"
                );
            }

            if (!snapshot.getConfigHash().equals(pending.getConfigHash())) {
                throw new GameConfigValidationException(
                        GameConfigProtocol.ERROR_CONFIG_HASH_MISMATCH,
                        "Unity 返回 configHash 与 pending 不一致"
                );
            }

            boolean appliedSaved = store.saveApplied(snapshot, System.currentTimeMillis());
            if (!appliedSaved) {
                throw new GameConfigValidationException(
                        GameConfigProtocol.ERROR_APPLY_FAILED,
                        "保存已生效配置失败"
                );
            }

            store.clearPending();
            finishCommand(
                    pending.getMessageId(),
                    pending.getConfigVersion(),
                    pending.getConfigHash(),
                    GameConfigProtocol.STATUS_SUCCESS,
                    GameConfigProtocol.RESULT_OK,
                    isEmpty(resultMessage)
                            ? "游戏配置已生效"
                            : resultMessage
            );
            cacheGameConfigReport(snapshot);
        } catch (GameConfigValidationException e) {
            finishPending(
                    pending,
                    GameConfigProtocol.STATUS_FAILED,
                    e.getErrorCode(),
                    e.getMessage()
            );
        } catch (Exception e) {
            finishPending(
                    pending,
                    GameConfigProtocol.STATUS_FAILED,
                    GameConfigProtocol.ERROR_APPLY_FAILED,
                    "处理 Unity 配置结果异常：" + safeMessage(e)
            );
        }
    }

    private void dispatchPending(
            GameConfigPending pending
    ) {
        if (pending.getDispatchCount() >= MAX_DISPATCH_COUNT) {
            finishPending(
                    pending,
                    GameConfigProtocol.STATUS_FAILED,
                    GameConfigProtocol.ERROR_APPLY_FAILED,
                    "游戏配置下发到 Unity 超过最大次数"
            );
            return;
        }

        long now = System.currentTimeMillis();
        GameConfigPending dispatched = pending.markDispatched(newRequestId(), now);
        boolean saved = store.savePending(dispatched);
        if (!saved) {
            finishPending(
                    pending,
                    GameConfigProtocol.STATUS_FAILED,
                    GameConfigProtocol.ERROR_APPLY_FAILED,
                    "保存 Unity 派发状态失败"
            );
            return;
        }

        boolean sent = GameConfigBridge.sendApply(appContext, dispatched);
        if (!sent) {
            finishPending(
                    dispatched,
                    GameConfigProtocol.STATUS_FAILED,
                    GameConfigProtocol.ERROR_APPLY_FAILED,
                    "发送 Unity 游戏配置广播失败"
            );
            return;
        }

        scheduleApplyTimeout(dispatched.getRequestId());
    }

    private void dispatchApplied(
            GameConfigSnapshot applied
    ) {
        long now = System.currentTimeMillis();
        GameConfigPending pending = new GameConfigPending(
                "",
                newRequestId(),
                applied.getGameCode(),
                applied.getSchemaVersion(),
                applied.getConfigVersion(),
                applied.getConfigHash(),
                applied.getCanonicalConfig(),
                GameConfigPending.STATE_SAVED,
                now,
                now,
                0
        );
        GameConfigBridge.sendApply(appContext, pending.markDispatched(newRequestId(), now));
    }

    private void scheduleApplyTimeout(
            String requestId
    ) {
        mainHandler.postDelayed(
                () -> executor.execute(() -> handleApplyTimeout(requestId)),
                APPLY_TIMEOUT_MS
        );
    }

    private void scheduleQueryTimeout(
            String requestId
    ) {
        mainHandler.postDelayed(
                () -> Log.e(TAG, "game config query timeout requestId=" + requestId),
                QUERY_TIMEOUT_MS
        );
    }

    private void handleApplyTimeout(
            String requestId
    ) {
        GameConfigPending pending = store.getPending();
        if (pending == null) {
            return;
        }
        if (!pending.getRequestId().equals(requestId)) {
            return;
        }

        if (pending.getDispatchCount() < MAX_DISPATCH_COUNT) {
            dispatchPending(pending);
            return;
        }

        finishPending(
                pending,
                GameConfigProtocol.STATUS_FAILED,
                GameConfigProtocol.ERROR_APPLY_FAILED,
                "等待游戏配置执行结果超时"
        );
    }

    private void finishSuccessFromCommand(
            GameConfigCommand command,
            String message
    ) {
        finishCommand(
                command.getMessageId(),
                command.getConfigVersion(),
                command.getConfigHash(),
                GameConfigProtocol.STATUS_SUCCESS,
                GameConfigProtocol.RESULT_OK,
                message
        );
    }

    private void finishPending(
            GameConfigPending pending,
            String status,
            String resultCode,
            String resultMessage
    ) {
        store.clearPending();
        finishCommand(
                pending.getMessageId(),
                pending.getConfigVersion(),
                pending.getConfigHash(),
                status,
                resultCode,
                resultMessage
        );
    }

    private void finishCommand(
            String messageId,
            long configVersion,
            String configHash,
            String status,
            String resultCode,
            String resultMessage
    ) {
        GameConfigResultRecord record = new GameConfigResultRecord(
                messageId,
                configVersion,
                configHash,
                status,
                resultCode,
                resultMessage,
                System.currentTimeMillis()
        );
        store.addRecentResult(record);
        cacheCommandResult(record);
    }

    private void cacheAck(
            GameConfigCommand command
    ) {
        JSONObject json = buildCommandResult(
                command.getMessageId(),
                command.getConfigVersion(),
                GameConfigProtocol.STATUS_ACK,
                GameConfigProtocol.RESULT_OK,
                "游戏配置任务已可靠保存"
        );
        store.saveCommandResultOutbox(json.toString());
    }

    private void cacheCommandResult(
            GameConfigResultRecord record
    ) {
        JSONObject json = buildCommandResult(
                record.getMessageId(),
                record.getConfigVersion(),
                record.getStatus(),
                record.getResultCode(),
                record.getResultMessage()
        );
        store.saveCommandResultOutbox(json.toString());
    }

    private void cacheInvalidCommandResult(
            String payload,
            String resultCode,
            String resultMessage
    ) {
        String messageId = "";
        long configVersion = 0;

        try {
            JSONObject json = new JSONObject(isEmpty(payload) ? "{}" : payload);
            messageId = json.optString("messageId", "");
            JSONObject data = json.optJSONObject("data");
            if (data != null) {
                configVersion = data.optLong("configVersion", 0);
            }
        } catch (Exception ignored) {
        }

        JSONObject result = buildCommandResult(
                messageId,
                configVersion,
                GameConfigProtocol.STATUS_FAILED,
                resultCode,
                resultMessage
        );
        store.saveCommandResultOutbox(result.toString());
    }

    private JSONObject buildCommandResult(
            String messageId,
            long configVersion,
            String status,
            String resultCode,
            String resultMessage
    ) {
        JSONObject json = new JSONObject();
        try {
            json.put("deviceNo", DeviceUtil.getDeviceId(appContext));
            json.put("messageId", safe(messageId));
            json.put("commandType", GameConfigProtocol.COMMAND_TYPE_SYNC_GAME_CONFIG);
            json.put("configVersion", configVersion);
            json.put("status", safe(status));
            json.put("resultCode", safe(resultCode));
            json.put("resultMessage", safe(resultMessage));
            json.put("timestamp", System.currentTimeMillis());
        } catch (Exception e) {
            Log.e(TAG, "build game config command result fail", e);
        }
        return json;
    }

    private void cacheGameConfigReport(
            GameConfigSnapshot snapshot
    ) {
        JSONObject json = new JSONObject();
        try {
            json.put("deviceNo", DeviceUtil.getDeviceId(appContext));
            json.put("gameCode", snapshot.getGameCode());
            json.put("gameVersion", PackageUtil.getVersion(
                    appContext,
                    GameConfigProtocol.GAME_PACKAGE
            ));
            json.put("maxSupportedSchemaVersion", GameConfigProtocol.MAX_SUPPORTED_SCHEMA_VERSION);
            json.put("schemaVersion", snapshot.getSchemaVersion());
            json.put("configVersion", snapshot.getConfigVersion());
            json.put("configHash", snapshot.getConfigHash());
            json.put("config", new JSONObject(snapshot.getCanonicalConfig()));
            json.put("timestamp", System.currentTimeMillis());
            store.saveGameConfigReportOutbox(json.toString());
        } catch (Exception e) {
            Log.e(TAG, "cache game config report fail", e);
        }
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean isEmpty(
            String value
    ) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(
            String value
    ) {
        return value == null ? "" : value;
    }

    private static String safeMessage(
            Throwable throwable
    ) {
        if (throwable == null) {
            return "未知异常";
        }
        String message = throwable.getMessage();
        if (isEmpty(message)) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }
}
