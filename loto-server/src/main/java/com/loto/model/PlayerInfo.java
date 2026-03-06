package com.loto.model;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Lightweight snapshot of a Player — safe to broadcast to all clients.
 * Includes balance and recent transactions.
 */
public class PlayerInfo {

    public final String  playerId;
    public final String  name;
    public final boolean isHost;
    public final boolean isBot;
    public final boolean isConnected;
    public final int     pageCount;
    public final long    balance;

    public PlayerInfo(Player player) {
        this.playerId    = player.getId();
        this.name        = player.getName();
        this.isHost      = player.isHost();
        this.isBot       = player.isBot();
        this.isConnected = player.isConnected();
        this.pageCount   = player.getPages().size();
        this.balance     = player.getBalance();
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("playerId",    playerId);
        obj.put("name",        name);
        obj.put("isHost",      isHost);
        obj.put("isBot",       isBot);
        obj.put("isConnected", isConnected);
        obj.put("pageCount",   pageCount);
        obj.put("balance",     balance);
        return obj;
    }

    /** Full snapshot including transaction history — sent privately to the owner. */
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
