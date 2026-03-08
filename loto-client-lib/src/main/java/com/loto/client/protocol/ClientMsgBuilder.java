package com.loto.client.protocol;

import org.json.JSONObject;

/**
 * Builds newline-delimited JSON messages: client → server.
 */
public class ClientMsgBuilder {

    // ── Join ──────────────────────────────────────────────────────

    public static String join(String name) {
        return msg("JOIN", new JSONObject().put("name", name));
    }

    /** Multi-room mode: include roomId in JOIN. */
    public static String join(String name, String roomId) {
        JSONObject p = new JSONObject().put("name", name);
        if (roomId != null) p.put("roomId", roomId);
        return msg("JOIN", p);
    }

    /**
     * JOIN with token for reconnect — preferred over legacy RECONNECT message.
     * Server tries reconnect first; falls back to fresh join if token unrecognised.
     */
    public static String joinWithToken(String name, String token, String roomId) {
        JSONObject p = new JSONObject().put("name", name).put("token", token);
        if (roomId != null) p.put("roomId", roomId);
        return msg("JOIN", p);
    }

    /** @deprecated Use {@link #joinWithToken} — server prefers JOIN with token. */
    @Deprecated
    public static String reconnect(String token) {
        return msg("RECONNECT", new JSONObject().put("token", token));
    }

    // ── Admin authentication ──────────────────────────────────────

    /**
     * Authenticate as admin using the server's secret token.
     * Server responds with ADMIN_AUTH_OK on success.
     * @param adminToken the secret token printed in the server banner at startup
     */
    public static String adminAuth(String adminToken) {
        return msg("ADMIN_AUTH", new JSONObject().put("token", adminToken));
    }

    // ── Player actions ────────────────────────────────────────────

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

    // ── Host/Admin-only ───────────────────────────────────────────

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

    public static String kick(String playerId, String reason) {
        JSONObject p = new JSONObject().put("playerId", playerId);
        if (reason != null) p.put("reason", reason);
        return msg("KICK", p);
    }

    public static String ban(String playerId, String reason) {
        JSONObject p = new JSONObject().put("playerId", playerId);
        if (reason != null) p.put("reason", reason);
        return msg("BAN", p);
    }

    public static String unban(String name) {
        return msg("UNBAN", new JSONObject().put("name", name));
    }

    /** Pause the draw loop mid-game. Admin only. */
    public static String pauseGame() {
        return msg("PAUSE_GAME", new JSONObject());
    }

    /** Resume a paused game. Admin only. */
    public static String resumeGame() {
        return msg("RESUME_GAME", new JSONObject());
    }

    /** Start game ngay — bypass vote. Admin only. */
    public static String serverStart() {
        return msg("SERVER_START", new JSONObject());
    }

    /** Kết thúc game không có winner. Admin only. */
    public static String serverEnd(String reason) {
        JSONObject p = new JSONObject();
        if (reason != null) p.put("reason", reason);
        return msg("SERVER_END", p);
    }

    /** Reset phòng về WAITING — giữ balance. Admin only. */
    public static String resetRoom() {
        return msg("RESET_ROOM", new JSONObject());
    }

    /** Cấm theo địa chỉ IP. Admin only. */
    public static String banIp(String ip) {
        return msg("BAN_IP", new JSONObject().put("ip", ip));
    }

    /** Gỡ cấm IP. Admin only. */
    public static String unbanIp(String ip) {
        return msg("UNBAN_IP", new JSONObject().put("ip", ip));
    }

    /** Lấy danh sách tên + IP bị cấm. Admin only. */
    public static String getBanList() {
        return msg("GET_BAN_LIST", new JSONObject());
    }

    /**
     * Change draw speed live — admin only.
     * @param intervalMs milliseconds between numbers (min 200)
     */
    public static String setDrawInterval(int intervalMs) {
        return msg("SET_DRAW_INTERVAL", new JSONObject().put("intervalMs", intervalMs));
    }

    /**
     * Change price per page — admin only.
     * Only allowed before any page has been purchased (jackpot == 0).
     * @param price new price in đồng (>= 0)
     */
    public static String setPricePerPage(long price) {
        return msg("SET_PRICE_PER_PAGE", new JSONObject().put("price", price));
    }

    /**
     * Set auto-reset delay after game ends — admin only.
     * @param delayMs milliseconds (0 = disable auto-reset)
     */
    public static String setAutoReset(int delayMs) {
        return msg("SET_AUTO_RESET", new JSONObject().put("delayMs", delayMs));
    }

    /**
     * Set auto-start delay — admin only.
     * @param delayMs milliseconds (0 = disable auto-start)
     */
    public static String setAutoStart(int delayMs) {
        return msg("SET_AUTO_START", new JSONObject().put("delayMs", delayMs));
    }

    // ── Helper ────────────────────────────────────────────────────

    private static String msg(String type, JSONObject payload) {
        return new JSONObject()
                .put("type", type)
                .put("payload", payload)
                .toString();
    }
}