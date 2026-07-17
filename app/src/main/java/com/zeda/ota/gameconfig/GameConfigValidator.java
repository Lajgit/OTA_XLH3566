package com.zeda.ota.gameconfig;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 游戏配置命令、Schema 和哈希的严格校验器。
 */
public final class GameConfigValidator {

    private static final Pattern INTEGER_PATTERN =
            Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Pattern SHA256_PATTERN =
            Pattern.compile("[0-9a-f]{64}");

    private static final Set<String> CONFIG_FIELDS =
            setOf("lighting", "sound", "difficulty");
    private static final Set<String> LIGHTING_FIELDS = setOf(
            "playfieldBrightnessPercent",
            "stripBrightnessPercent",
            "panelBrightnessPercent"
    );
    private static final Set<String> SOUND_FIELDS = setOf(
            "musicVolumePercent",
            "effectVolumePercent"
    );
    private static final Set<String> DIFFICULTY_FIELDS = setOf("mode");
    private static final Set<String> SMART_DIFFICULTY_FIELDS = setOf(
            "mode",
            "smartModeSettings"
    );
    private static final Set<String> SMART_SETTINGS_FIELDS = setOf(
            "profitRatePercent",
            "profitCycleDays",
            "cardExchangeAmountCent"
    );
    private static final Set<String> DIFFICULTY_MODES = setOf(
            "SMART",
            "EASY",
            "MEDIUM",
            "HARD"
    );

    public GameConfigCommand validateCommand(
            JsonObject payload
    ) throws GameConfigValidationException {
        if (payload == null) {
            throw invalid("MQTT 配置指令不能为空");
        }

        String messageId = requireString(payload, "messageId", "messageId");
        if (messageId.length() > 64) {
            throw invalid("messageId 长度不能超过 64 个字符");
        }

        String deviceNo = requireString(payload, "deviceNo", "deviceNo");
        String commandType = requireString(payload, "commandType", "commandType");
        if (!GameConfigProtocol.COMMAND_TYPE_SYNC_GAME_CONFIG.equals(commandType)) {
            throw invalid("commandType 必须为 sync_game_config");
        }

        long timestamp = requireLong(payload, "timestamp", "timestamp", false);
        JsonObject data = requireObject(payload, "data", "data");

        String gameCode = requireString(data, "gameCode", "data.gameCode");
        if (!GameConfigProtocol.GAME_CODE.equals(gameCode)) {
            throw invalid("data.gameCode 必须为 " + GameConfigProtocol.GAME_CODE);
        }

        int schemaVersion = requireInt(
                data,
                "schemaVersion",
                "data.schemaVersion",
                1,
                Integer.MAX_VALUE
        );
        validateSupportedSchema(schemaVersion);

        long configVersion = requireLong(
                data,
                "configVersion",
                "data.configVersion",
                true
        );

        String configHash = requireString(data, "configHash", "data.configHash");
        validateHashFormat(configHash);

        JsonObject config = requireObject(data, "config", "data.config");
        String canonicalConfig = validateAndCanonicalizeConfig(schemaVersion, config);
        String calculatedHash = GameConfigCanonicalizer.sha256(canonicalConfig);

        if (!configHash.equals(calculatedHash)) {
            throw new GameConfigValidationException(
                    GameConfigProtocol.ERROR_CONFIG_HASH_MISMATCH,
                    "data.configHash 与设备重新计算结果不一致"
            );
        }

        return new GameConfigCommand(
                messageId,
                deviceNo,
                timestamp,
                gameCode,
                schemaVersion,
                configVersion,
                configHash,
                config,
                canonicalConfig
        );
    }

    public GameConfigSnapshot validateSnapshot(
            String gameCode,
            int schemaVersion,
            long configVersion,
            String configHash,
            JsonObject config
    ) throws GameConfigValidationException {
        if (!GameConfigProtocol.GAME_CODE.equals(gameCode)) {
            throw invalid("gameCode 必须为 " + GameConfigProtocol.GAME_CODE);
        }
        validateSupportedSchema(schemaVersion);
        if (configVersion <= 0) {
            throw invalid("configVersion 必须为正整数");
        }
        validateHashFormat(configHash);

        String canonicalConfig = validateAndCanonicalizeConfig(schemaVersion, config);
        String calculatedHash = GameConfigCanonicalizer.sha256(canonicalConfig);
        if (!configHash.equals(calculatedHash)) {
            throw new GameConfigValidationException(
                    GameConfigProtocol.ERROR_CONFIG_HASH_MISMATCH,
                    "configHash 与实际配置重新计算结果不一致"
            );
        }

        return new GameConfigSnapshot(
                gameCode,
                schemaVersion,
                configVersion,
                configHash,
                config,
                canonicalConfig
        );
    }

    public String validateAndCanonicalizeConfig(
            int schemaVersion,
            JsonObject config
    ) throws GameConfigValidationException {
        validateSupportedSchema(schemaVersion);
        if (config == null) {
            throw invalid("config 必须是 JSON 对象");
        }

        validateDepth(config, 1);

        final String canonicalConfig;
        try {
            canonicalConfig = GameConfigCanonicalizer.canonicalize(config);
        } catch (IllegalArgumentException e) {
            throw invalid("config 规范化失败：" + e.getMessage());
        }

        int configBytes = canonicalConfig.getBytes(StandardCharsets.UTF_8).length;
        if (configBytes > GameConfigProtocol.MAX_CONFIG_BYTES) {
            throw invalid(
                    "config 规范化后超过 "
                            + GameConfigProtocol.MAX_CONFIG_BYTES
                            + " 字节"
            );
        }

        validateSchemaV1(config);
        return canonicalConfig;
    }

    private void validateSchemaV1(
            JsonObject config
    ) throws GameConfigValidationException {
        requireExactFields(config, CONFIG_FIELDS, "config");

        JsonObject lighting = requireObject(config, "lighting", "config.lighting");
        requireExactFields(lighting, LIGHTING_FIELDS, "config.lighting");
        requireInt(
                lighting,
                "playfieldBrightnessPercent",
                "config.lighting.playfieldBrightnessPercent",
                0,
                100
        );
        requireInt(
                lighting,
                "stripBrightnessPercent",
                "config.lighting.stripBrightnessPercent",
                0,
                100
        );
        requireInt(
                lighting,
                "panelBrightnessPercent",
                "config.lighting.panelBrightnessPercent",
                0,
                100
        );

        JsonObject sound = requireObject(config, "sound", "config.sound");
        requireExactFields(sound, SOUND_FIELDS, "config.sound");
        requireInt(
                sound,
                "musicVolumePercent",
                "config.sound.musicVolumePercent",
                0,
                100
        );
        requireInt(
                sound,
                "effectVolumePercent",
                "config.sound.effectVolumePercent",
                0,
                100
        );

        JsonObject difficulty = requireObject(
                config,
                "difficulty",
                "config.difficulty"
        );
        String mode = requireString(difficulty, "mode", "config.difficulty.mode");
        if (!DIFFICULTY_MODES.contains(mode)) {
            throw invalid(
                    "config.difficulty.mode 必须为 SMART、EASY、MEDIUM 或 HARD"
            );
        }

        if ("SMART".equals(mode)) {
            requireExactFields(
                    difficulty,
                    SMART_DIFFICULTY_FIELDS,
                    "config.difficulty"
            );
            JsonObject smartSettings = requireObject(
                    difficulty,
                    "smartModeSettings",
                    "config.difficulty.smartModeSettings"
            );
            requireExactFields(
                    smartSettings,
                    SMART_SETTINGS_FIELDS,
                    "config.difficulty.smartModeSettings"
            );
            requireInt(
                    smartSettings,
                    "profitRatePercent",
                    "config.difficulty.smartModeSettings.profitRatePercent",
                    0,
                    100
            );
            requireInt(
                    smartSettings,
                    "profitCycleDays",
                    "config.difficulty.smartModeSettings.profitCycleDays",
                    1,
                    Integer.MAX_VALUE
            );
            requireInt(
                    smartSettings,
                    "cardExchangeAmountCent",
                    "config.difficulty.smartModeSettings.cardExchangeAmountCent",
                    1,
                    Integer.MAX_VALUE
            );
        } else {
            requireExactFields(
                    difficulty,
                    DIFFICULTY_FIELDS,
                    "config.difficulty"
            );
        }
    }

    private void validateSupportedSchema(
            int schemaVersion
    ) throws GameConfigValidationException {
        if (schemaVersion <= 0) {
            throw invalid("schemaVersion 必须为正整数");
        }
        if (schemaVersion > GameConfigProtocol.MAX_SUPPORTED_SCHEMA_VERSION) {
            throw new GameConfigValidationException(
                    GameConfigProtocol.ERROR_SCHEMA_UNSUPPORTED,
                    "schemaVersion="
                            + schemaVersion
                            + " 高于设备支持上限 "
                            + GameConfigProtocol.MAX_SUPPORTED_SCHEMA_VERSION
            );
        }
    }

    private void validateHashFormat(
            String configHash
    ) throws GameConfigValidationException {
        if (!SHA256_PATTERN.matcher(configHash).matches()) {
            throw invalid("configHash 必须是 64 位小写十六进制 SHA-256");
        }
    }

    private void validateDepth(
            JsonElement element,
            int depth
    ) throws GameConfigValidationException {
        if (depth > GameConfigProtocol.MAX_JSON_DEPTH) {
            throw invalid(
                    "config 嵌套层级不能超过 "
                            + GameConfigProtocol.MAX_JSON_DEPTH
            );
        }

        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
            return;
        }

        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry
                    : element.getAsJsonObject().entrySet()) {
                validateDepth(entry.getValue(), depth + 1);
            }
            return;
        }

        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                validateDepth(child, depth + 1);
            }
        }
    }

    private static String requireString(
            JsonObject object,
            String field,
            String path
    ) throws GameConfigValidationException {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw invalid(path + " 必须是非空字符串");
        }

        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isString()) {
            throw invalid(path + " 必须是字符串");
        }

        String text = primitive.getAsString();
        if (text.trim().isEmpty()) {
            throw invalid(path + " 不能为空");
        }
        return text;
    }

    private static JsonObject requireObject(
            JsonObject object,
            String field,
            String path
    ) throws GameConfigValidationException {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonObject()) {
            throw invalid(path + " 必须是 JSON 对象");
        }
        return value.getAsJsonObject();
    }

    private static int requireInt(
            JsonObject object,
            String field,
            String path,
            int min,
            int max
    ) throws GameConfigValidationException {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw invalid(path + " 必须是整数");
        }

        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw invalid(path + " 必须是整数");
        }

        String raw = primitive.getAsString();
        if (!INTEGER_PATTERN.matcher(raw).matches()) {
            throw invalid(path + " 必须是整数，不能使用小数或科学计数法");
        }

        final long parsed;
        try {
            parsed = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw invalid(path + " 超出整数范围");
        }

        if (parsed < min || parsed > max) {
            throw invalid(path + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return (int) parsed;
    }

    private static long requireLong(
            JsonObject object,
            String field,
            String path,
            boolean positive
    ) throws GameConfigValidationException {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw invalid(path + " 必须是整数");
        }

        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw invalid(path + " 必须是整数");
        }

        String raw = primitive.getAsString();
        if (!INTEGER_PATTERN.matcher(raw).matches()) {
            throw invalid(path + " 必须是整数，不能使用小数或科学计数法");
        }

        final long parsed;
        try {
            parsed = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw invalid(path + " 超出 long 范围");
        }

        if (positive && parsed <= 0) {
            throw invalid(path + " 必须为正整数");
        }
        if (!positive && parsed < 0) {
            throw invalid(path + " 不能为负数");
        }
        return parsed;
    }

    private static void requireExactFields(
            JsonObject object,
            Set<String> expected,
            String path
    ) throws GameConfigValidationException {
        for (String field : expected) {
            if (!object.has(field)) {
                throw invalid(path + " 缺少字段 " + field);
            }
        }

        for (String field : object.keySet()) {
            if (!expected.contains(field)) {
                throw invalid(path + " 包含未定义字段 " + field);
            }
        }
    }

    private static Set<String> setOf(
            String... values
    ) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static GameConfigValidationException invalid(
            String message
    ) {
        return new GameConfigValidationException(
                GameConfigProtocol.ERROR_CONFIG_INVALID,
                message
        );
    }
}
