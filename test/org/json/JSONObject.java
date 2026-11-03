package org.json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TEST-ONLY minimal {@code org.json.JSONObject} shim.
 *
 * <p>This file exists solely so the pure-Java JVM regression harness under
 * {@code test/} can compile and run on a desktop JDK without the
 * Android framework and without any {@code org.json} implementation jar. It implements
 * only the handful of members used by {@code WeighSession} and {@code Measurement}
 * (plus the serialization needed to make their JSON output inspectable by the tests).
 * It is NOT shipped in the APK and must never be copied into {@code src/}.
 *
 * <p>Serialization produces compact JSON (no whitespace) with valid string escaping.
 */
public class JSONObject {

    /** Sentinel used by the real org.json to represent an explicit JSON {@code null}. */
    public static final Object NULL = new Null();

    private final Map<String, Object> values = new LinkedHashMap<String, Object>();

    public JSONObject() {
    }

    public JSONObject put(String key, Object value) {
        values.put(key, value == null ? NULL : value);
        return this;
    }

    public JSONObject put(String key, double value) {
        values.put(key, Double.valueOf(value));
        return this;
    }

    public JSONObject put(String key, boolean value) {
        values.put(key, Boolean.valueOf(value));
        return this;
    }

    public JSONObject put(String key, int value) {
        values.put(key, Integer.valueOf(value));
        return this;
    }

    public JSONObject put(String key, long value) {
        values.put(key, Long.valueOf(value));
        return this;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public Object get(String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("no such key: " + key);
        }
        return value;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        write(sb, this);
        return sb.toString();
    }

    /** JSON string literal, including the surrounding quotes. */
    public static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '/':
                    sb.append("\\/");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** Package-private serializer shared with {@link JSONArray}. */
    static String valueToJson(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null || value == NULL || value instanceof Null) {
            sb.append("null");
        } else if (value instanceof JSONObject) {
            ((JSONObject) value).writeObject(sb);
        } else if (value instanceof JSONArray) {
            ((JSONArray) value).writeArray(sb);
        } else if (value instanceof String) {
            sb.append(quote((String) value));
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Double) {
            double d = ((Double) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append(Long.toString((long) d));
            } else {
                sb.append(Double.toString(d));
            }
        } else if (value instanceof Number) {
            sb.append(value.toString());
        } else {
            sb.append(quote(value.toString()));
        }
    }

    private void writeObject(StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(entry.getKey())).append(':');
            write(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static final class Null {
        @Override
        public boolean equals(Object other) {
            return other == null || other == this;
        }

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public String toString() {
            return "null";
        }
    }
}
