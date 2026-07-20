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
 *
 * <p>后端、OTA、Unity 必须使用同一套规范化规则，否则同一份 config
 * 可能算出不同 configHash，导致 CONFIG_HASH_MISMATCH。</p>
 */
public final class GameConfigCanonicalizer {

    /**
     * JSON 对象 key 排序规则：按 Unicode code point 升序排序，
     * 不使用 Java 默认 UTF-16 字符串顺序。
     */
    private static final Comparator<String> CODE_POINT_COMPARATOR =
            GameConfigCanonicalizer::compareByCodePoint;

    private GameConfigCanonicalizer() {
    }

    /**
     * 将任意 JsonElement 转为无空格、无换行、字段顺序稳定的规范化 JSON。
     */
    public static String canonicalize(JsonElement element) {
        StringBuilder builder = new StringBuilder();
        appendCanonical(element, builder);
        return builder.toString();
    }

    /**
     * 对 data.config 对象执行规范化后计算 SHA-256。
     */
    public static String calculateConfigHash(JsonElement config) {
        return sha256(canonicalize(config));
    }

    /**
     * 计算 UTF-8 字节序列的 SHA-256，返回 64 位小写十六进制字符串。
     */
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

    /**
     * 按 JSON 节点类型递归写入规范化结果。
     */
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

    /**
     * 规范化对象：key 排序，value 递归规范化。
     */
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

    /**
     * 规范化数组：数组元素顺序必须保持原样，不能排序。
     */
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

    /**
     * 规范化字符串、布尔值和数字。
     */
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

    /**
     * 数字规范化：去掉无意义小数位，不使用科学计数法，-0 统一为 0。
     */
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

    /**
     * 字符串转义规则：不做 HTML 转义，中文和 emoji 保持 UTF-8 原样。
     */
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

    /**
     * 控制字符使用小写 U+00XX 形式转义。
     */
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

    /**
     * 按 Unicode code point 比较两个字符串。
     *
     * <p>这样可以正确处理 emoji 等代理对字符，避免 UTF-16 默认排序差异。</p>
     */
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
