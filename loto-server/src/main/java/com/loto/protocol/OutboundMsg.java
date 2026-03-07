package com.loto.protocol;

import org.json.JSONObject;
import org.json.JSONArray;

import com.loto.model.LotoPage;

import com.loto.model.PlayerInfo;
import com.loto.model.Transaction;
import com.loto.model.Player;
import java.util.List;

/**
 * Builder for all server → client messages.
 * Usage: OutboundMsg.welcome(playerId, token, pages).toJson()
 */
public class OutboundMsg {

    private final JSONObject json;

    private OutboundMsg(MsgType type, JSONObject payload) {
        json = new JSONObject();
        json.put("type", type.name());
        json.put("payload", payload);
    }

    public String toJson() {
        return json.toString();
    }

    // ─── Factory methods ─────────────────────────────────────────

    public static OutboundMsg roomUpdate(List<PlayerInfo> players, String gameState,
                                         long pricePerPage, int autoResetDelayMs) {
        JSONObject p    = new JSONObject();
        JSONArray  arr  = new JSONArray();
        for (PlayerInfo info : players) arr.put(info.toJson());
        p.put("players",        arr);
        p.put("gameState",      gameState);
        p.put("pricePerPage",   pricePerPage);
        p.put("autoResetDelaySec", autoResetDelayMs / 1000);
        return new OutboundMsg(MsgType.ROOM_UPDATE, p);
    }

    /** @deprecated Use {@link #roomUpdate(List, String, long, int)} instead. */
    @Deprecated
    public static OutboundMsg roomUpdate(List<PlayerInfo> players, String gameState) {
        return roomUpdate(players, gameState, 0, 0);
    }

    public static OutboundMsg welcome(String playerId, String token, boolean isHost,
                                      List<LotoPage> pages) {
        JSONObject p = new JSONObject();
        p.put("playerId", playerId);
        p.put("token",    token);
        p.put("isHost",   isHost);
        p.put("pages",    pagesToJson(pages));
        return new OutboundMsg(MsgType.WELCOME, p);
    }

    /**
     * Full-state welcome — sent on JOIN and RECONNECT so the client can
     * immediately render the correct room state without extra round-trips.
     *
     * @param pages          the player's own pages (empty list for fresh join)
     * @param players        all current PlayerInfo snapshots
     * @param gameState      current GameState name ("WAITING", "PLAYING", etc.)
     * @param isPaused       whether the game is currently paused
     * @param drawnNumbers   numbers drawn so far (empty for WAITING)
     * @param voteCount      current vote count
     * @param voteNeeded     votes needed to start
     * @param drawIntervalMs current draw interval in ms
     * @param pricePerPage   current price per page
     */
    public static OutboundMsg welcome(String playerId, String token, boolean isHost,
                                      List<LotoPage> pages,
                                      List<PlayerInfo> players,
                                      String gameState,
                                      boolean isPaused,
                                      List<Integer> drawnNumbers,
                                      int voteCount,
                                      int voteNeeded,
                                      int drawIntervalMs,
                                      long pricePerPage) {
        JSONObject p = new JSONObject();
        p.put("playerId",      playerId);
        p.put("token",         token);
        p.put("isHost",        isHost);
        p.put("pages",         pagesToJson(pages));

        // Room snapshot
        JSONArray playersArr = new JSONArray();
        for (PlayerInfo info : players) playersArr.put(info.toJson());
        p.put("players",       playersArr);
        p.put("gameState",     gameState);
        p.put("isPaused",      isPaused);
        p.put("drawnNumbers",  new JSONArray(drawnNumbers));
        p.put("voteCount",     voteCount);
        p.put("voteNeeded",    voteNeeded);
        p.put("drawIntervalMs", drawIntervalMs);
        p.put("pricePerPage",  pricePerPage);
        return new OutboundMsg(MsgType.WELCOME, p);
    }

    public static OutboundMsg playerJoined(String playerId, String name, boolean isHost) {
        JSONObject p = new JSONObject();
        p.put("playerId", playerId);
        p.put("name",     name);
        p.put("isHost",   isHost);
        return new OutboundMsg(MsgType.PLAYER_JOINED, p);
    }

    public static OutboundMsg playerLeft(String playerId) {
        JSONObject p = new JSONObject();
        p.put("playerId", playerId);
        return new OutboundMsg(MsgType.PLAYER_LEFT, p);
    }

    public static OutboundMsg pagesAssigned(String playerId, List<LotoPage> pages) {
        JSONObject p = new JSONObject();
        p.put("playerId", playerId);
        p.put("pages",    pagesToJson(pages));
        return new OutboundMsg(MsgType.PAGES_ASSIGNED, p);
    }

    public static OutboundMsg voteUpdate(int voteCount, int needed) {
        JSONObject p = new JSONObject();
        p.put("voteCount", voteCount);
        p.put("needed",    needed);
        return new OutboundMsg(MsgType.VOTE_UPDATE, p);
    }

    public static OutboundMsg gameStarting(int drawIntervalMs) {
        JSONObject p = new JSONObject();
        p.put("drawIntervalMs", drawIntervalMs);
        return new OutboundMsg(MsgType.GAME_STARTING, p);
    }

    /**
     * Emits only the newly drawn number.
     * Clients append it to their local drawnNumbers list.
     * (drawnNumbers[] is delivered in full via WELCOME on connect/reconnect.)
     */
    public static OutboundMsg numberDrawn(int number) {
        JSONObject p = new JSONObject();
        p.put("number", number);
        return new OutboundMsg(MsgType.NUMBER_DRAWN, p);
    }

    /** @deprecated Use {@link #numberDrawn(int)} — clients maintain their own list. */
    @Deprecated
    public static OutboundMsg numberDrawn(int number, List<Integer> drawnSoFar) {
        JSONObject p = new JSONObject();
        p.put("number",    number);
        p.put("drawnList", new JSONArray(drawnSoFar));
        return new OutboundMsg(MsgType.NUMBER_DRAWN, p);
    }

    public static OutboundMsg claimReceived(String playerId, String playerName, int pageId) {
        JSONObject p = new JSONObject();
        p.put("playerId",   playerId);
        p.put("playerName", playerName);
        p.put("pageId",     pageId);
        return new OutboundMsg(MsgType.CLAIM_RECEIVED, p);
    }

    public static OutboundMsg winConfirmed(String playerId, String playerName, int pageId) {
        JSONObject p = new JSONObject();
        p.put("playerId",   playerId);
        p.put("playerName", playerName);
        p.put("pageId",     pageId);
        return new OutboundMsg(MsgType.WIN_CONFIRMED, p);
    }

    public static OutboundMsg winRejected(String playerId, int pageId) {
        JSONObject p = new JSONObject();
        p.put("playerId", playerId);
        p.put("pageId",   pageId);
        return new OutboundMsg(MsgType.WIN_REJECTED, p);
    }

    public static OutboundMsg gameEnded(String winnerPlayerId, String winnerName) {
        JSONObject p = new JSONObject();
        p.put("winnerPlayerId", winnerPlayerId);
        p.put("winnerName",     winnerName);
        return new OutboundMsg(MsgType.GAME_ENDED, p);
    }

    public static OutboundMsg balanceUpdate(String playerId, long balance, Transaction tx) {
        JSONObject p  = new JSONObject();
        JSONObject t  = new JSONObject();
        t.put("timestamp",    tx.getTimestamp());
        t.put("type",         tx.getType().name());
        t.put("amount",       tx.getAmount());
        t.put("balanceAfter", tx.getBalanceAfter());
        t.put("note",         tx.getNote());
        p.put("playerId",     playerId);
        p.put("balance",      balance);
        p.put("transaction",  t);
        return new OutboundMsg(MsgType.BALANCE_UPDATE, p);
    }

    public static OutboundMsg walletHistory(String playerId, Player player) {
        return new OutboundMsg(MsgType.WALLET_HISTORY,
                               PlayerInfo.toPrivateJson(player));
    }

    public static OutboundMsg gameCancelled(String reason, long jackpot) {
        JSONObject p = new JSONObject();
        p.put("reason",  reason);
        p.put("jackpot", jackpot);
        return new OutboundMsg(MsgType.GAME_CANCELLED, p);
    }

    public static OutboundMsg kicked(String reason) {
        JSONObject p = new JSONObject();
        p.put("reason", reason != null ? reason : "Bị kick khỏi phòng");
        return new OutboundMsg(MsgType.KICKED, p);
    }

    public static OutboundMsg banned(String reason) {
        JSONObject p = new JSONObject();
        p.put("reason", reason != null ? reason : "Bị cấm vào phòng");
        return new OutboundMsg(MsgType.BANNED, p);
    }


    public static OutboundMsg drawIntervalChanged(int intervalMs) {
        JSONObject p = new JSONObject();
        p.put("intervalMs", intervalMs);
        return new OutboundMsg(MsgType.DRAW_INTERVAL_CHANGED, p);
    }

    public static OutboundMsg pricePerPageChanged(long newPrice) {
        JSONObject p = new JSONObject();
        p.put("pricePerPage", newPrice);
        return new OutboundMsg(MsgType.PRICE_PER_PAGE_CHANGED, p);
    }

    public static OutboundMsg autoResetScheduled(int delayMs) {
        JSONObject p = new JSONObject();
        p.put("delayMs",  delayMs);
        p.put("delaySec", delayMs / 1000);
        return new OutboundMsg(MsgType.AUTO_RESET_SCHEDULED, p);
    }

    public static OutboundMsg roomReset(long prizeEach, int winnerCount) {
        JSONObject p = new JSONObject();
        p.put("prizeEach",   prizeEach);
        p.put("winnerCount", winnerCount);
        return new OutboundMsg(MsgType.ROOM_RESET, p);
    }

    public static OutboundMsg gameEndedByServer(String reason) {
        JSONObject p = new JSONObject();
        p.put("reason", reason);
        return new OutboundMsg(MsgType.GAME_ENDED, p);
    }

    public static OutboundMsg error(String code, String message) {
        JSONObject p = new JSONObject();
        p.put("code",    code);
        p.put("message", message);
        return new OutboundMsg(MsgType.ERROR, p);
    }

    public static OutboundMsg adminAuthOk() {
        JSONObject p = new JSONObject();
        p.put("message", "Admin authenticated successfully");
        return new OutboundMsg(MsgType.ADMIN_AUTH_OK, p);
    }

    public static OutboundMsg gamePaused() {
        return new OutboundMsg(MsgType.GAME_PAUSED, new JSONObject());
    }
    public static OutboundMsg gameResumed(int drawIntervalMs) {
        JSONObject p = new JSONObject();
        p.put("drawIntervalMs", drawIntervalMs);
        return new OutboundMsg(MsgType.GAME_RESUMED, p);
    }

    /**
     * Notifies clients of an auto-start countdown.
     * delayMs == 0 means the auto-start was cancelled.
     */
    public static OutboundMsg autoStartScheduled(int delayMs) {
        JSONObject p = new JSONObject();
        p.put("delayMs", delayMs);
        return new OutboundMsg(MsgType.AUTO_START_SCHEDULED, p);
    }

    // ─── Helpers ──────────────────────────────────────────────────

    public static OutboundMsg banList(java.util.Set<String> bannedNames,
                                      java.util.Set<String> bannedIps) {
        JSONObject p     = new JSONObject();
        JSONArray  names = new JSONArray();
        JSONArray  ips   = new JSONArray();
        bannedNames.forEach(names::put);
        bannedIps.forEach(ips::put);
        p.put("bannedNames", names);
        p.put("bannedIps",   ips);
        return new OutboundMsg(MsgType.BAN_LIST, p);
    }

    private static JSONArray pagesToJson(List<LotoPage> pages) {
        JSONArray arr = new JSONArray();
        for (LotoPage page : pages) {
            JSONObject obj  = new JSONObject();
            JSONArray  rows = new JSONArray();
            for (List<Integer> row : page.getPage()) {
                rows.put(new JSONArray(row));
            }
            obj.put("id",   page.getId());
            obj.put("grid", rows);
            arr.put(obj);
        }
        return arr;
    }
}