package com.loto.core;

import com.loto.model.Player;
import com.loto.model.PlayerInfo;
import com.loto.model.Transaction;
import com.loto.network.IClientHandler;
import com.loto.protocol.OutboundMsg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handles all outbound messaging: broadcast, sendTo, balance updates, room updates.
 * Operates on shared {@link GameRoomState}.
 */
public class GameRoomBroadcaster {

    private final GameRoomState s;

    GameRoomBroadcaster(GameRoomState s) { this.s = s; }

    /** Sends JSON to all clients, optionally excluding one connId. */
    public void broadcast(String json, String excludeConnId) {
        List<IClientHandler> targets;
        synchronized (s) {
            targets = new ArrayList<>();
            for (Map.Entry<String, IClientHandler> entry : s.handlerByConnId.entrySet()) {
                if (!entry.getKey().equals(excludeConnId)) targets.add(entry.getValue());
            }
        }
        for (IClientHandler h : targets) h.send(json);
    }

    /** Sends JSON to a single connection. */
    public void sendTo(String connId, String json) {
        IClientHandler handler = s.handlerByConnId.get(connId);
        if (handler != null) handler.send(json);
    }

    /** Broadcasts the full room state to everyone. */
    public void broadcastRoomUpdate() {
        List<PlayerInfo> snapshot = s.playersByToken.values().stream()
                .map(PlayerInfo::new)
                .collect(Collectors.toList());
        String json = OutboundMsg.roomUpdate(snapshot, s.state.name(),
                s.currentPricePerPage, s.currentAutoResetDelayMs).toJson();
        broadcast(json, null);
    }

    /** Sends the last transaction as a BALANCE_UPDATE to one client. */
    public void sendBalanceUpdate(String connId, Player player) {
        List<Transaction> txList = player.getTransactions();
        if (txList.isEmpty()) return;
        Transaction lastTx = txList.get(txList.size() - 1);
        sendTo(connId, OutboundMsg.balanceUpdate(
                player.getId(), player.getBalance(), lastTx).toJson());
    }

    /** Sends full wallet history privately (used on reconnect). */
    public void sendBalanceSnapshot(String connId, Player player) {
        sendTo(connId, OutboundMsg.walletHistory(player.getId(), player).toJson());
    }
}
