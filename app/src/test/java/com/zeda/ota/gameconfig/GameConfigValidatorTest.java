package com.zeda.ota.gameconfig;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GameConfigValidatorTest {

    private final GameConfigValidator validator = new GameConfigValidator();

    @Test
    public void validEasyCommandShouldPass() throws Exception {
        JsonObject payload = validPayload(easyConfig());

        GameConfigCommand command = validator.validateCommand(payload);

        assertEquals("message-001", command.getMessageId());
        assertEquals("DEVICE001", command.getDeviceNo());
        assertEquals(12L, command.getConfigVersion());
        assertEquals(
                "EASY",
                command.getConfig()
                        .getAsJsonObject("difficulty")
                        .get("mode")
                        .getAsString()
        );
    }

    @Test
    public void validSmartConfigShouldPass() throws Exception {
        GameConfigCommand command = validator.validateCommand(
                validPayload(smartConfig())
        );

        assertEquals(
                "SMART",
                command.getConfig()
                        .getAsJsonObject("difficulty")
                        .get("mode")
                        .getAsString()
        );
    }

    @Test
    public void unknownFieldShouldFail() {
        JsonObject config = easyConfig();
        config.addProperty("unknownField", 1);
        assertInvalid(config, GameConfigProtocol.ERROR_CONFIG_INVALID);
    }

    @Test
    public void missingRequiredObjectShouldFail() {
        JsonObject config = easyConfig();
        config.remove("sound");
        assertInvalid(config, GameConfigProtocol.ERROR_CONFIG_INVALID);
    }

    @Test
    public void percentAboveRangeShouldFail() {
        JsonObject config = easyConfig();
        config.getAsJsonObject("lighting")
                .addProperty("playfieldBrightnessPercent", 101);
        assertInvalid(config, GameConfigProtocol.ERROR_CONFIG_INVALID);
    }

    @Test
    public void decimalPercentShouldFail() {
        JsonObject config = easyConfig();
        config.getAsJsonObject("sound")
                .addProperty("musicVolumePercent", 1.5);
        assertInvalid(config, GameConfigProtocol.ERROR_CONFIG_INVALID);
    }

    @Test
    public void smartWithoutSettingsShouldFail() {
        JsonObject config = easyConfig();
        config.getAsJsonObject("difficulty")
                .addProperty("mode", "SMART");
        assertInvalid(config, GameConfigProtocol.ERROR_CONFIG_INVALID);
    }

    @Test
    public void easyWithSmartSettingsShouldFail() {
        JsonObject config = easyConfig();
        JsonObject settings = new JsonObject();
        settings.addProperty("profitRatePercent", 30);
        settings.addProperty("profitCycleDays", 7);
        settings.addProperty("cardExchangeAmountCent", 100);
        config.getAsJsonObject("difficulty")
                .add("smartModeSettings", settings);
        assertInvalid(config, GameConfigProtocol.ERROR_CONFIG_INVALID);
    }

    @Test
    public void nonPositiveSmartCycleShouldFail() {
        JsonObject config = smartConfig();
        config.getAsJsonObject("difficulty")
                .getAsJsonObject("smartModeSettings")
                .addProperty("profitCycleDays", 0);
        assertInvalid(config, GameConfigProtocol.ERROR_CONFIG_INVALID);
    }

    @Test
    public void unsupportedSchemaShouldReturnDedicatedCode() {
        JsonObject payload = validPayload(easyConfig());
        payload.getAsJsonObject("data")
                .addProperty("schemaVersion", 2);
        assertCommandInvalid(
                payload,
                GameConfigProtocol.ERROR_SCHEMA_UNSUPPORTED
        );
    }

    @Test
    public void hashMismatchShouldReturnDedicatedCode() {
        JsonObject payload = validPayload(easyConfig());
        payload.getAsJsonObject("data").addProperty(
                "configHash",
                "0000000000000000000000000000000000000000000000000000000000000000"
        );
        assertCommandInvalid(
                payload,
                GameConfigProtocol.ERROR_CONFIG_HASH_MISMATCH
        );
    }

    @Test
    public void uppercaseHashShouldFailFormatValidation() {
        JsonObject payload = validPayload(easyConfig());
        String hash = payload.getAsJsonObject("data")
                .get("configHash")
                .getAsString()
                .toUpperCase();
        payload.getAsJsonObject("data").addProperty("configHash", hash);
        assertCommandInvalid(payload, GameConfigProtocol.ERROR_CONFIG_INVALID);
    }

    @Test
    public void zeroConfigVersionShouldFail() {
        JsonObject payload = validPayload(easyConfig());
        payload.getAsJsonObject("data")
                .addProperty("configVersion", 0);
        assertCommandInvalid(payload, GameConfigProtocol.ERROR_CONFIG_INVALID);
    }

    @Test
    public void longMessageIdShouldFail() {
        JsonObject payload = validPayload(easyConfig());
        payload.addProperty("messageId", repeat('a', 65));
        assertCommandInvalid(payload, GameConfigProtocol.ERROR_CONFIG_INVALID);
    }

    @Test
    public void excessiveDepthShouldFailBeforeSchemaValidation() {
        JsonObject config = easyConfig();
        JsonObject current = new JsonObject();
        config.add("extra", current);

        for (int index = 0; index < 16; index++) {
            JsonObject next = new JsonObject();
            current.add("next", next);
            current = next;
        }

        assertInvalid(config, GameConfigProtocol.ERROR_CONFIG_INVALID);
    }

    @Test
    public void excessiveCanonicalSizeShouldFail() {
        JsonObject config = easyConfig();
        config.getAsJsonObject("difficulty")
                .addProperty("mode", repeat('A', 65536));
        assertInvalid(config, GameConfigProtocol.ERROR_CONFIG_INVALID);
    }

    private void assertInvalid(
            JsonObject config,
            String expectedCode
    ) {
        assertCommandInvalid(validPayload(config), expectedCode);
    }

    private void assertCommandInvalid(
            JsonObject payload,
            String expectedCode
    ) {
        try {
            validator.validateCommand(payload);
            fail("预期配置校验失败");
        } catch (GameConfigValidationException e) {
            assertEquals(expectedCode, e.getErrorCode());
            assertTrue(e.getMessage() != null && !e.getMessage().isEmpty());
        }
    }

    private JsonObject validPayload(
            JsonObject config
    ) {
        JsonObject payload = new JsonObject();
        payload.addProperty("messageId", "message-001");
        payload.addProperty("deviceNo", "DEVICE001");
        payload.addProperty(
                "commandType",
                GameConfigProtocol.COMMAND_TYPE_SYNC_GAME_CONFIG
        );
        payload.addProperty("timestamp", 1784200000000L);

        JsonObject data = new JsonObject();
        data.addProperty("gameCode", GameConfigProtocol.GAME_CODE);
        data.addProperty("schemaVersion", 1);
        data.addProperty("configVersion", 12);
        data.addProperty(
                "configHash",
                GameConfigCanonicalizer.calculateConfigHash(config)
        );
        data.add("config", config);
        payload.add("data", data);
        return payload;
    }

    private static JsonObject easyConfig() {
        return JsonParser.parseString(
                "{"
                        + "\"lighting\":{"
                        + "\"playfieldBrightnessPercent\":90,"
                        + "\"stripBrightnessPercent\":80,"
                        + "\"panelBrightnessPercent\":70},"
                        + "\"sound\":{"
                        + "\"musicVolumePercent\":60,"
                        + "\"effectVolumePercent\":40},"
                        + "\"difficulty\":{\"mode\":\"EASY\"}"
                        + "}"
        ).getAsJsonObject();
    }

    private static JsonObject smartConfig() {
        return JsonParser.parseString(
                "{"
                        + "\"lighting\":{"
                        + "\"playfieldBrightnessPercent\":100,"
                        + "\"stripBrightnessPercent\":85,"
                        + "\"panelBrightnessPercent\":75},"
                        + "\"sound\":{"
                        + "\"musicVolumePercent\":50,"
                        + "\"effectVolumePercent\":65},"
                        + "\"difficulty\":{"
                        + "\"mode\":\"SMART\","
                        + "\"smartModeSettings\":{"
                        + "\"profitRatePercent\":35,"
                        + "\"profitCycleDays\":7,"
                        + "\"cardExchangeAmountCent\":500}}"
                        + "}"
        ).getAsJsonObject();
    }

    private static String repeat(
            char value,
            int count
    ) {
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
