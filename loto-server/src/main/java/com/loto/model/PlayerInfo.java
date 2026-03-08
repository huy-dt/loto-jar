package com.loto.model;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/**
 * Lightweight snapshot of a Player — safe to broadcast to all clients.
 *
 * <p>Now includes the player's {@code pages} list so the client can render
 * each player's board inside {@code roomInfo.players[]} without an extra
 * round-trip.</p>
 */
public class PlayerInfo {

    public final String         playerId;
    public final String         name;
    public final boolean        isHost;
    public final boolean        isBot;
    public final boolean        isConnected;
    public final int            pageCount;
    public final long           balance;
    public final List<LotoPage> pages;

    public PlayerInfo(Player player) {
        this.playerId    = player.getId();
        this.name        = player.getName();
        this.isHost      = player.isHost();
        this.isBot       = player.isBot();
        this.isConnected = player.isConnected();
        this.pageCount   = player.getPages().size();
        this.balance     = player.getBalance();
        this.pages       = player.getPages();   // unmodifiable view from Player
    }

    /**
     * JSON sent inside roomInfo.players[].
     *
     *   { playerId, name, isHost, isBot, isConnected,
     *     pageCount, balance,
     *     pages: [ { id, grid: [[...], ...] } ] }
     */
    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("playerId",    playerId);
        obj.put("name",        name);
        obj.put("isHost",      isHost);
        obj.put("isBot",       isBot);
        obj.put("isConnected", isConnected);
        obj.put("pageCount",   pageCount);
        obj.put("balance",     balance);
        obj.put("pages",       pagesToJson(pages));
        return obj;
    }

    // --- Helpers --------------------------------------------------

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

    /** Full snapshot including transaction history - sent privately to the owner. */
    public static JSONObject toPrivateJson(Player player) {
        JSONObject obj = new JSONObject();
        obj.put("playerId",    player.getId());
        obj.put("name",        player.getName());
        obj.put("balance",     player.getBalance());
        obj.put("pageCount",   player.getPages().size());

        JSONArray txArr = new JSONArray();
        for (Transaction tx : player.getTransactions()) {
            JSONObject t = new JSONObject();
            t.put("timestamp",    tx.getTimestamp());
            t.put("type",         tx.getType().name());
            t.put("amount",       tx.getAmount());
            t.put("balanceAfter", tx.getBalanceAfter());
            t.put("note",         tx.getNote());
            txArr.put(t);
        }
        obj.put("transactions", txArr);
        return obj;
    }
}
