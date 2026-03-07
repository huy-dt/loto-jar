package com.loto.core;

import com.loto.model.LotoPage;
import com.loto.model.Player;
import com.loto.model.PlayerInfo;
import com.loto.model.Transaction;
import com.loto.network.IClientHandler;
import com.loto.protocol.OutboundMsg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.loto.model.LotoPage;

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

    /**
     * Sends a full-state WELCOME to a freshly joined player.
     * Includes current room snapshot so the client doesn't need extra requests.
     * Also sends individual state messages for SDK/client backward compatibility.
     */
    public void sendWelcome(String connId, Player player, boolean isHost) {
        List<PlayerInfo> snapshot = buildPlayerSnapshot();
        boolean isPaused = s.state == com.loto.core.GameState.PAUSED;
        String gameStateName = isPaused ? "PLAYING" : s.state.name();

        int voteNeeded = (int) Math.ceil(
            s.playersByToken.values().stream().filter(p -> !p.isBot()).count() * 0.5
        );

        // 1. Full-state WELCOME (for clients that parse it fully)
        String welcomeJson = OutboundMsg.welcome(
            player.getId(), player.getToken(), isHost,
            player.getPages(),
            snapshot,
            gameStateName,
            isPaused,
            new ArrayList<>(s.drawnNumbers),
            s.votedPlayerIds.size(),
            Math.max(voteNeeded, 1),
            s.currentDrawIntervalMs,
            s.currentPricePerPage
        ).toJson();
        sendTo(connId, welcomeJson);

        // 2. Follow-up messages for SDK compatibility
        // ROOM_UPDATE — players list + game state
        String roomJson = OutboundMsg.roomUpdate(snapshot, gameStateName,
                s.currentPricePerPage, s.currentAutoResetDelayMs).toJson();
        sendTo(connId, roomJson);

        // VOTE_UPDATE — if waiting/voting
        if (s.state == com.loto.core.GameState.WAITING || s.state == com.loto.core.GameState.VOTING) {
            sendTo(connId, OutboundMsg.voteUpdate(s.votedPlayerIds.size(), Math.max(voteNeeded, 1)).toJson());
        }

        // GAME_STARTING — if mid-game, let client know interval
        if (s.state == com.loto.core.GameState.PLAYING || isPaused) {
            sendTo(connId, OutboundMsg.gameStarting(s.currentDrawIntervalMs).toJson());
            if (isPaused) sendTo(connId, OutboundMsg.gamePaused().toJson());
        }
    }

    /**
     * Sends a full-state WELCOME to a reconnecting player.
     * Same as sendWelcome but always includes the player's pages (even mid-game).
     */
    public void sendReconnectWelcome(String connId, Player player) {
        sendWelcome(connId, player, player.isHost());
    }

    // ─── Private helpers ──────────────────────────────────────────

    private List<PlayerInfo> buildPlayerSnapshot() {
        return s.playersByToken.values().stream()
                .map(PlayerInfo::new)
                .collect(Collectors.toList());
    }
}
