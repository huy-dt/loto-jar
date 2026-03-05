package com.loto.client.protocol;

import org.json.JSONObject;

/**
 * Builds newline-delimited JSON messages from client → server.
 */
public class ClientMsgBuilder {

    public static String join(String name) {
        return msg("JOIN", new JSONObject().put("name", name));
    }

    public static String reconnect(String token) {
        return msg("RECONNECT", new JSONObject().put("token", token));
    }

    public static String buyPage(int count) {
        return msg("BUY_PAGE", new JSONObject().put("count", count));
    }

    public static String voteStart() {
        return msg("VOTE_START", new JSONObject());
    }

    public static String claimWin(int pageId) {
        return msg("CLAIM_WIN", new JSONObject().put("pageId", pageId));
    }

    public static String getWallet() {
        return msg("GET_WALLET", new JSONObject());
    }

    // ── Host-only ─────────────────────────────────────────────────

    public static String confirmWin(String playerId, int pageId) {
        return msg("CONFIRM_WIN", new JSONObject()
                .put("playerId", playerId)
                .put("pageId", pageId));
    }

    public static String rejectWin(String playerId, int pageId) {
        return msg("REJECT_WIN", new JSONObject()
                .put("playerId", playerId)
                .put("pageId", pageId));
    }

    public static String topUp(String playerId, long amount, String note) {
        JSONObject p = new JSONObject()
                .put("playerId", playerId)
                .put("amount", amount);
        if (note != null) p.put("note", note);
        return msg("TOPUP", p);
    }

    public static String cancelGame(String reason) {
        return msg("CANCEL_GAME", new JSONObject().put("reason", reason));
    }

    // ── Helper ────────────────────────────────────────────────────

    private static String msg(String type, JSONObject payload) {
        return new JSONObject()
                .put("type", type)
                .put("payload", payload)
                .toString();
    }
}
