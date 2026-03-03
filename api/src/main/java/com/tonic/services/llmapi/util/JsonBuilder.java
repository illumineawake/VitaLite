package com.tonic.services.llmapi.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility class for building JSON strings without external dependencies.
 * Follows the pattern used in ProfilerServer.
 */
public class JsonBuilder {
    private final StringBuilder sb;
    private boolean needsComma = false;
    private int depth = 0;

    public JsonBuilder() {
        this.sb = new StringBuilder();
    }

    public static String escapeString(String s) {
        if (s == null) return "null";
        StringBuilder result = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < ' ') {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
            }
        }
        return result.toString();
    }

    public JsonBuilder startObject() {
        if (needsComma) sb.append(",");
        sb.append("{");
        needsComma = false;
        depth++;
        return this;
    }

    public JsonBuilder endObject() {
        sb.append("}");
        needsComma = true;
        depth--;
        return this;
    }

    public JsonBuilder startArray() {
        if (needsComma) sb.append(",");
        sb.append("[");
        needsComma = false;
        depth++;
        return this;
    }

    public JsonBuilder endArray() {
        sb.append("]");
        needsComma = true;
        depth--;
        return this;
    }

    public JsonBuilder key(String key) {
        if (needsComma) sb.append(",");
        sb.append("\"").append(escapeString(key)).append("\":");
        needsComma = false;
        return this;
    }

    public JsonBuilder value(String value) {
        if (needsComma) sb.append(",");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(escapeString(value)).append("\"");
        }
        needsComma = true;
        return this;
    }

    public JsonBuilder value(int value) {
        if (needsComma) sb.append(",");
        sb.append(value);
        needsComma = true;
        return this;
    }

    public JsonBuilder value(long value) {
        if (needsComma) sb.append(",");
        sb.append(value);
        needsComma = true;
        return this;
    }

    public JsonBuilder value(double value) {
        if (needsComma) sb.append(",");
        sb.append(value);
        needsComma = true;
        return this;
    }

    public JsonBuilder value(boolean value) {
        if (needsComma) sb.append(",");
        sb.append(value);
        needsComma = true;
        return this;
    }

    public JsonBuilder nullValue() {
        if (needsComma) sb.append(",");
        sb.append("null");
        needsComma = true;
        return this;
    }

    public JsonBuilder field(String key, String value) {
        key(key);
        if (value == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(escapeString(value)).append("\"");
        }
        needsComma = true;
        return this;
    }

    public JsonBuilder field(String key, int value) {
        key(key);
        sb.append(value);
        needsComma = true;
        return this;
    }

    public JsonBuilder field(String key, long value) {
        key(key);
        sb.append(value);
        needsComma = true;
        return this;
    }

    public JsonBuilder field(String key, double value) {
        key(key);
        sb.append(value);
        needsComma = true;
        return this;
    }

    public JsonBuilder field(String key, boolean value) {
        key(key);
        sb.append(value);
        needsComma = true;
        return this;
    }

    public JsonBuilder fieldNull(String key) {
        key(key);
        sb.append("null");
        needsComma = true;
        return this;
    }

    public JsonBuilder fieldArray(String key, String[] values) {
        key(key);
        sb.append("[");
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                if (i > 0) sb.append(",");
                if (values[i] == null) {
                    sb.append("null");
                } else {
                    sb.append("\"").append(escapeString(values[i])).append("\"");
                }
            }
        }
        sb.append("]");
        needsComma = true;
        return this;
    }

    public JsonBuilder fieldArray(String key, int[] values) {
        key(key);
        sb.append("[");
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(values[i]);
            }
        }
        sb.append("]");
        needsComma = true;
        return this;
    }

    public JsonBuilder fieldArray(String key, int[][] values) {
        key(key);
        sb.append("[");
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("[");
                if (values[i] != null) {
                    for (int j = 0; j < values[i].length; j++) {
                        if (j > 0) sb.append(",");
                        sb.append(values[i][j]);
                    }
                }
                sb.append("]");
            }
        }
        sb.append("]");
        needsComma = true;
        return this;
    }

    public JsonBuilder rawJson(String json) {
        if (needsComma) sb.append(",");
        sb.append(json);
        needsComma = true;
        return this;
    }

    public JsonBuilder fieldRaw(String key, String rawJson) {
        key(key);
        sb.append(rawJson);
        needsComma = true;
        return this;
    }

    @Override
    public String toString() {
        return sb.toString();
    }

    /**
     * Create a simple position object JSON
     */
    public static String position(int x, int y, int plane) {
        return String.format("{\"x\":%d,\"y\":%d,\"plane\":%d}", x, y, plane);
    }

    /**
     * Create an error response JSON
     */
    public static String error(int code, String message) {
        return new JsonBuilder()
                .startObject()
                .field("error", true)
                .field("code", code)
                .field("message", message)
                .endObject()
                .toString();
    }

    /**
     * Create a success response with a message
     */
    public static String success(String message) {
        return new JsonBuilder()
                .startObject()
                .field("success", true)
                .field("message", message)
                .endObject()
                .toString();
    }

    /**
     * Filter out null strings from an array
     */
    public static String[] filterNullActions(String[] actions) {
        if (actions == null) return new String[0];
        return Arrays.stream(actions)
                .filter(a -> a != null && !a.isEmpty())
                .toArray(String[]::new);
    }
}
