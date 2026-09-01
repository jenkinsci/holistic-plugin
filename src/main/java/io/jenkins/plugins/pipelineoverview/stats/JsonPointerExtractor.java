package io.jenkins.plugins.pipelineoverview.stats;

import net.sf.json.JSON;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import java.io.IOException;

public final class JsonPointerExtractor {

    public enum Mode { VALUE, COUNT }

    private JsonPointerExtractor() {}

    public static StatValue extract(String body, String pointer, Mode mode, long fetchedAt)
            throws IOException {
        JSON document = parse(body);
        Object node = resolve(document, pointer);
        return apply(node, mode, fetchedAt);
    }

    private static JSON parse(String body) throws IOException {
        try {
            Object parsed = JSONSerializer.toJSON(body);
            if (!(parsed instanceof JSONObject) && !(parsed instanceof JSONArray)) {
                throw new IOException("Response body must be a JSON object or array");
            }
            return (JSON) parsed;
        } catch (JSONException e) {
            throw new IOException("Response body must be a JSON object or array", e);
        }
    }

    private static Object resolve(JSON document, String pointer) throws IOException {
        String p = pointer != null ? pointer.trim() : "";
        if (p.isEmpty()) return document;
        if (!p.startsWith("/")) {
            throw new IOException("JSON pointer must start with '/' or be empty, got: " + p);
        }
        Object current = document;
        for (String rawToken : p.substring(1).split("/", -1)) {
            String token = rawToken.replace("~1", "/").replace("~0", "~");
            current = step(current, token, p);
        }
        return current;
    }

    private static Object step(Object current, String token, String pointer) throws IOException {
        if (current instanceof JSONObject obj) {
            if (!obj.containsKey(token)) {
                throw new IOException("JSON pointer " + pointer + " did not resolve: no key '" + token + "'");
            }
            return obj.get(token);
        }
        if (current instanceof JSONArray arr) {
            int index;
            try {
                index = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw new IOException("JSON pointer " + pointer + " used non-numeric index '" + token + "' on an array");
            }
            if (index < 0 || index >= arr.size()) {
                throw new IOException("JSON pointer " + pointer + " index " + index + " is out of bounds");
            }
            return arr.get(index);
        }
        throw new IOException("JSON pointer " + pointer + " descended past a scalar at '" + token + "'");
    }

    private static StatValue apply(Object node, Mode mode, long fetchedAt) throws IOException {
        if (node == null || node instanceof JSONNull) {
            throw new IOException("JSON pointer resolved to null");
        }
        if (mode == Mode.COUNT) {
            if (node instanceof JSONArray arr) return StatValue.numeric(arr.size(), fetchedAt);
            if (node instanceof JSONObject obj) return StatValue.numeric(obj.size(), fetchedAt);
            throw new IOException("count mode needs an array or object, got a scalar");
        }
        if (node instanceof JSONArray || node instanceof JSONObject) {
            throw new IOException("value mode needs a scalar, got an array or object. Use count mode.");
        }
        if (node instanceof Number n) return StatValue.numeric(n.doubleValue(), fetchedAt);
        if (node instanceof Boolean b) return StatValue.text(String.valueOf(b), fetchedAt);
        String s = String.valueOf(node);
        try {
            return StatValue.numeric(Double.parseDouble(s.trim()), fetchedAt);
        } catch (NumberFormatException e) {
            return StatValue.text(s, fetchedAt);
        }
    }
}
