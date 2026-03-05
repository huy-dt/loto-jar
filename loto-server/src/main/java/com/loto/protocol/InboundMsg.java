package com.loto.protocol;

import org.json.JSONObject;

/**
 * Represents a parsed message received from a client.
 * Raw JSON shape: { "type": "JOIN", "payload": { ... } }
 */
public class InboundMsg {

    private final MsgType type;
    private final JSONObject payload;

    private InboundMsg(MsgType type, JSONObject payload) {
        this.type    = type;
        this.payload = payload;
    }

    // ─── Factory ──────────────────────────────────────────────────

    /**
     * Parses raw JSON string into an InboundMsg.
     * Returns null if the message is malformed or type is unknown.
     */
    public static InboundMsg parse(String raw) {
        try {
            JSONObject json    = new JSONObject(raw);
            String     typeStr = json.getString("type");
            MsgType    type    = MsgType.valueOf(typeStr);
            JSONObject payload = json.optJSONObject("payload");
            return new InboundMsg(type, payload != null ? payload : new JSONObject());
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Accessors ────────────────────────────────────────────────

    public MsgType getType() {
        return type;
    }

    public JSONObject getPayload() {
        return payload;
    }

    public String getString(String key) {
        return payload.optString(key, null);
    }

    public int getInt(String key, int defaultVal) {
        return payload.optInt(key, defaultVal);
    }

    public long getLong(String key, long defaultVal) {
        return payload.optLong(key, defaultVal);
    }

    public boolean getBoolean(String key, boolean defaultVal) {
        return payload.optBoolean(key, defaultVal);
    }
}
