package com.zeda.ota.gameconfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 按项目约定生成确定性的 JSON 字符串并计算 SHA-256。
 */
public final class GameConfigCanonicalizer {

    private static final Comparator<String> CODE_POINT_COMPARATOR =
            GameConfigCanonicalizer::compareByCodePoint;

    private GameConfigCanonicalizer() {
    }

    public static String canonicalize(JsonElement element) {
        StringBuilder builder = new StringBuilder();
        appendCanonical(element, builder);
        return builder.toString();
    }

    public static String calculateConfigHash(JsonElement config) {
        return sha256(canonicalize(config));
    }

    public static String sha256(String value) {
        if (value == null) {
            throw new IllegalArgumentException("待计算哈希的字符串不能为空");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);

            for (byte item : bytes) {
                int valueByte = item & 0xff;
                if (valueByte < 0x10) {
                    hex.append('0');
                }
                hex.append(Integer.toHexString(valueByte));
            }

            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", e);
        }
    }

    private static void appendCanonical(
            JsonElement element,
            StringBuilder builder
    ) {
        if (element == null || element.isJsonNull()) {
            builder.append("null");
            return;
        }

        if (element.isJsonObject()) {
            appendObject(element.getAsJsonObject(), builder);
            return;
        }

        if (element.isJsonArray()) {
            appendArray(element.getAsJsonArray(), builder);
            return;
        }

        if (element.isJsonPrimitive()) {
            appendPrimitive(element.getAsJsonPrimitive(), builder);
            return;
        }

        throw new IllegalArgumentException("不支持的 JSON 节点类型");
    }

    private static void appendObject(
            JsonObject object,
            StringBuilder builder
    ) {
        List<Map.Entry<String, JsonElement>> entries =
                new ArrayList<>(object.entrySet());
        entries.sort((left, right) ->
                CODE_POINT_COMPARATOR.compare(left.getKey(), right.getKey()));

        builder.append('{');

        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }

            Map.Entry<String, JsonElement> entry = entries.get(index);
            appendEscapedString(entry.getKey(), builder);
            builder.append(':');
            appendCanonical(entry.getValue(), builder);
        }

        builder.append('}');
    }

    private static void appendArray(
            JsonArray array,
            StringBuilder builder
    ) {
        builder.append('[');

        for (int index = 0; index < array.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            appendCanonical(array.get(index), builder);
        }

        builder.append(']');
    }

    private static void appendPrimitive(
            JsonPrimitive primitive,
            StringBuilder builder
    ) {
        if (primitive.isString()) {
            appendEscapedString(primitive.getAsString(), builder);
            return;
        }

        if (primitive.isBoolean()) {
            builder.append(primitive.getAsBoolean() ? "true" : "false");
            return;
        }

        if (primitive.isNumber()) {
            builder.append(normalizeNumber(primitive.getAsString()));
            return;
        }

        throw new IllegalArgumentException("不支持的 JSON 基本类型");
    }

    private static String normalizeNumber(String raw) {
        try {
            BigDecimal decimal = new BigDecimal(raw);
            if (decimal.signum() == 0) {
                return "0";
            }
            return decimal.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("JSON 数字格式无效：" + raw, e);
        }
    }

    private static void appendEscapedString(
            String value,
            StringBuilder builder
    ) {
        builder.append('"');

        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);

            switch (current) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (current < 0x20) {
                        appendUnicodeEscape(current, builder);
                    } else if (Character.isHighSurrogate(current)) {
                        if (index + 1 >= value.length()
                                || !Character.isLowSurrogate(value.charAt(index + 1))) {
                            throw new IllegalArgumentException("字符串包含无效的高代理字符");
                        }
                        builder.append(current);
                        builder.append(value.charAt(++index));
                    } else if (Character.isLowSurrogate(current)) {
                        throw new IllegalArgumentException("字符串包含无效的低代理字符");
                    } else {
                        builder.append(current);
                    }
                    break;
            }
        }

        builder.append('"');
    }

    private static void appendUnicodeEscape(
            char value,
            StringBuilder builder
    ) {
        final char[] hex = "0123456789abcdef".toCharArray();
        builder.append("\\u");
        builder.append(hex[(value >> 12) & 0x0f]);
        builder.append(hex[(value >> 8) & 0x0f]);
        builder.append(hex[(value >> 4) & 0x0f]);
        builder.append(hex[value & 0x0f]);
    }

    private static int compareByCodePoint(
            String left,
            String right
    ) {
        int leftIndex = 0;
        int rightIndex = 0;

        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftCodePoint = left.codePointAt(leftIndex);
            int rightCodePoint = right.codePointAt(rightIndex);

            if (leftCodePoint != rightCodePoint) {
                return Integer.compare(leftCodePoint, rightCodePoint);
            }

            leftIndex += Character.charCount(leftCodePoint);
            rightIndex += Character.charCount(rightCodePoint);
        }

        if (leftIndex == left.length() && rightIndex == right.length()) {
            return 0;
        }
        return leftIndex == left.length() ? -1 : 1;
    }
}
