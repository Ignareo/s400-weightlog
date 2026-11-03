package org.json;

import java.util.ArrayList;
import java.util.List;

/**
 * TEST-ONLY minimal {@code org.json.JSONArray} shim — see {@link JSONObject} for why this
 * exists. Implements only {@code new JSONArray()}, {@code put(Object)}, {@code length()},
 * {@code get(int)} and {@code toString()}.
 */
public class JSONArray {

    private final List<Object> values = new ArrayList<Object>();

    public JSONArray() {
    }

    public JSONArray put(Object value) {
        values.add(value == null ? JSONObject.NULL : value);
        return this;
    }

    public int length() {
        return values.size();
    }

    public Object get(int index) {
        return values.get(index);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        writeArray(sb);
        return sb.toString();
    }

    void writeArray(StringBuilder sb) {
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(JSONObject.valueToJson(values.get(i)));
        }
        sb.append(']');
    }
}
