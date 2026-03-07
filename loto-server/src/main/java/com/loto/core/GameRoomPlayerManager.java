package com.loto.core;

import com.loto.model.BotPlayer;
import com.loto.model.LotoPage;
import com.loto.model.Player;
import com.loto.network.IClientHandler;
import com.loto.protocol.OutboundMsg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Handles all player lifecycle events: join, reconnect, disconnect, kick, ban, topup.
 * Also handles bot join/remove/buy/claimWin delegation.
 */
public class GameRoomPlayerManager {

    private final GameRoomState      s;
    private final GameRoomBroadcaster bc;
    private final GameRoomPersistence persist;

    // Back-reference to the facade for auto-start check
    private final GameRoom room;

    GameRoomPlayerManager(GameRoomState s, GameRoomBroadcaster bc,
                          GameRoomPersistence persist, GameRoom room) {
        this.s       = s;
        this.bc      = bc;
        this.persist = persist;
        this.room    = room;
    }

    // ─── Join ─────────────────────────────────────────────────────

    public synchronized Player join(String connId, String name, IClientHandler handler) {
        Player player = new Player(name, false, s.config.initialBalance);

        String ip = handler.getRemoteIp();
        if (s.bannedIps.contains(ip)) {
            handler.send(OutboundMsg.banned("IP này đã bị cấm vào phòng").toJson());
            handler.close();
            return null;
        }
        if (s.bannedIds.contains(name.toLowerCase().trim())) {
            handler.send(OutboundMsg.banned("Tên này đã bị cấm vào phòng").toJson());
            handler.close();
            return null;
        }
        s.ipByConnId.put(connId, ip);
        s.playersByToken.put(player.getToken(), player);
        s.playersByConnId.put(connId, player);
        s.handlerByConnId.put(connId, handler);

        // Full-state welcome: player gets room snapshot on join (no extra round-trips)
        bc.sendWelcome(connId, player, false);
        bc.broadcast(OutboundMsg.playerJoined(player.getId(), player.getName(), false).toJson(), connId);
        bc.broadcastRoomUpdate();

        if (s.callback != null) s.callback.onPlayerJoined(player);
        persist.saveState();

        // Trigger auto-start countdown if applicable
        room.checkAutoStart();
        return player;
    }

    // ─── Bot join / remove ────────────────────────────────────────

    public synchronized Player joinBot(String connId, BotPlayer bot, IClientHandler handler) {
        s.playersByToken.put(bot.getToken(), bot);
        s.playersByConnId.put(connId, bot);
        s.handlerByConnId.put(connId, handler);
        s.ipByConnId.put(connId, "bot");

        bc.broadcast(OutboundMsg.playerJoined(bot.getId(), bot.getName(), false).toJson(), null);
        bc.broadcastRoomUpdate();

        if (s.callback != null) s.callback.onPlayerJoined(bot);
        persist.saveState();
        return bot;
    }

    public synchronized void removeBot(String connId, BotPlayer bot) {
        s.playersByToken.remove(bot.getToken());
        s.playersByConnId.remove(connId);
        s.handlerByConnId.remove(connId);
        s.ipByConnId.remove(connId);

        bc.broadcast(OutboundMsg.playerLeft(bot.getId()).toJson(), null);
        bc.broadcastRoomUpdate();
        persist.saveState();
        if (s.callback != null) s.callback.onPlayerLeft(bot, true);
    }

    public synchronized void buyPagesBot(String connId, int count) {
        Player player = s.playersByConnId.get(connId);
        if (!(player instanceof BotPlayer)) return;
        if (s.state != GameState.WAITING && s.state != GameState.VOTING) return;

        count = Math.min(count, s.config.maxPagesPerBuy);
        long totalCost = s.currentPricePerPage * count;

        List<LotoPage> newPages = new ArrayList<>();
        for (int i = 0; i < count; i++) newPages.add(new LotoPage(s.pageIdCounter.getAndIncrement()));
        player.addPages(newPages);

        if (s.currentPricePerPage > 0 && player.getBalance() >= totalCost) {
            player.deduct(totalCost, String.format("Bot mua %d tờ × %,d", count, s.currentPricePerPage));
            s.jackpot += totalCost;
        }

        bc.broadcastRoomUpdate();
        System.out.printf("[BOT] %s mua %d tờ%n", player.getName(), count);
        if (s.callback != null) s.callback.onPagesBought(player, newPages);
        persist.saveState();
    }

    // ─── Reconnect ────────────────────────────────────────────────

    public synchronized Player reconnect(String connId, String token, IClientHandler handler) {
        Player player = s.playersByToken.get(token);
        if (player == null) return null;

        player.setConnected(true);
        s.playersByConnId.values().remove(player);
        s.playersByConnId.put(connId, player);
        s.handlerByConnId.put(connId, handler);
        s.ipByConnId.put(connId, handler.getRemoteIp());

        // Full-state welcome: includes pages, drawn numbers, game state, room snapshot
        bc.sendReconnectWelcome(connId, player);
        // Send full wallet history so balance/transactions are up to date
        bc.sendBalanceSnapshot(connId, player);

        bc.broadcast(OutboundMsg.playerJoined(player.getId(), player.getName(), false).toJson(), null);
        bc.broadcastRoomUpdate();

        if (s.callback != null) s.callback.onPlayerReconnected(player);
        return player;
    }

    // ─── Disconnect ───────────────────────────────────────────────

    public synchronized void onConnectionLost(String connId) {
        Player player = s.playersByConnId.remove(connId);
        s.handlerByConnId.remove(connId);
        s.ipByConnId.remove(connId);
        s.adminConnIds.remove(connId);
        if (player == null) return;

        player.setConnected(false);
        bc.broadcast(OutboundMsg.playerLeft(player.getId()).toJson(), null);
        bc.broadcastRoomUpdate();

        if (s.callback != null) s.callback.onPlayerLeft(player, false);

        // Ẩn countdown ngay nếu player disconnect (pages vẫn còn nhưng isConnected=false)
        room.checkAutoStart();

        s.scheduler.schedule(() -> {
            if (!player.isConnected()) {
                s.playersByToken.remove(player.getToken());
                if (s.callback != null) s.callback.onPlayerLeft(player, true);
                room.checkAutoStart();
            }
        }, s.config.reconnectTimeoutMs, TimeUnit.MILLISECONDS);
    }

    // ─── Page purchasing ──────────────────────────────────────────

    public synchronized void buyPages(String connId, int count) {
        Player player = s.playersByConnId.get(connId);
        if (player == null) return;

        if (s.state != GameState.WAITING && s.state != GameState.VOTING) {
            bc.sendTo(connId, OutboundMsg.error("GAME_STARTED", "Cannot buy pages after game started").toJson());
            return;
        }

        count = Math.min(count, s.config.maxPagesPerBuy);
        long totalCost = s.currentPricePerPage * count;

        if (s.currentPricePerPage > 0 && player.getBalance() < totalCost) {
            bc.sendTo(connId, OutboundMsg.error("INSUFFICIENT_BALANCE",
                    String.format("Need %d, have %d", totalCost, player.getBalance())).toJson());
            if (s.callback != null) s.callback.onInsufficientBalance(player, totalCost, player.getBalance());
            return;
        }

        List<LotoPage> newPages = new ArrayList<>();
        for (int i = 0; i < count; i++) newPages.add(new LotoPage(s.pageIdCounter.getAndIncrement()));
        player.addPages(newPages);

        if (s.currentPricePerPage > 0) {
            player.deduct(totalCost, String.format("Mua %d tờ × %d", count, s.currentPricePerPage));
            s.jackpot += totalCost;
        }

        bc.sendTo(connId, OutboundMsg.pagesAssigned(player.getId(), newPages).toJson());
        bc.sendBalanceUpdate(connId, player);
        bc.broadcastRoomUpdate();

        if (s.callback != null) s.callback.onPagesBought(player, newPages);
        persist.saveState();

        // Trigger auto-start countdown if this is the first real buyer
        room.checkAutoStart();
    }

    // ─── Kick & Ban ───────────────────────────────────────────────

    public synchronized void kick(String playerId, String reason) {
        Player player = s.findPlayerById(playerId);
        if (player == null) return;

        String connId = s.getConnIdForPlayer(player);
        IClientHandler handler = connId != null ? s.handlerByConnId.get(connId) : null;

        if (s.state == GameState.WAITING || s.state == GameState.VOTING) {
            long refund = (long) player.getPages().size() * s.currentPricePerPage;
            if (refund > 0) {
                player.refund(refund, "Hoàn tiền do bị kick");
                s.jackpot = Math.max(0, s.jackpot - refund);
            }
        }

        s.playersByToken.remove(player.getToken());
        if (connId != null) {
            s.playersByConnId.remove(connId);
            s.handlerByConnId.remove(connId);
        }

        if (handler != null) {
            handler.send(OutboundMsg.kicked(reason).toJson());
            handler.close();
        }

        bc.broadcast(OutboundMsg.playerLeft(player.getId()).toJson(), null);
        bc.broadcastRoomUpdate();

        if (s.callback != null) s.callback.onPlayerKicked(player, reason);
        room.checkAutoStart();
    }

    public synchronized void ban(String playerId, String reason) {
        Player player = s.findPlayerById(playerId);
        if (player != null) {
            String connId = s.getConnIdForPlayer(player);
            if (connId != null) {
                String ip = s.ipByConnId.get(connId);
                if (ip != null && !ip.equals("unknown") && !ip.equals("bot")) s.bannedIps.add(ip);
            }
            s.bannedIds.add(player.getName().toLowerCase().trim());
            kick(playerId, reason != null ? reason : "Bị cấm khỏi phòng");
            if (s.callback != null) s.callback.onPlayerBanned(player.getId(), reason);
        } else {
            s.bannedIds.add(playerId.toLowerCase().trim());
            if (s.callback != null) s.callback.onPlayerBanned(playerId, reason);
        }
    }

    public synchronized void banIp(String ip) {
        if (ip != null && !ip.isBlank()) {
            s.bannedIps.add(ip.trim());
            if (s.callback != null) s.callback.onPlayerBanned("ip:" + ip, "IP ban");
        }
    }

    public synchronized void unbanIp(String ip)   { s.bannedIps.remove(ip); }
    public java.util.Set<String> getBannedIps()    { return Collections.unmodifiableSet(s.bannedIps); }

    public synchronized void unban(String name) {
        boolean removed = s.bannedIds.remove(name.toLowerCase().trim());
        if (removed && s.callback != null) s.callback.onPlayerUnbanned(name);
    }

    public java.util.Set<String> getBannedIds()    { return Collections.unmodifiableSet(s.bannedIds); }

    // ─── Top-up ───────────────────────────────────────────────────

    public synchronized void topUp(String playerId, long amount, String note) {
        Player player = s.findPlayerById(playerId);
        if (player == null || amount <= 0) return;

        player.topUp(amount, note != null ? note : "Host nạp tiền");

        String connId = s.getConnIdForPlayer(player);
        if (connId != null) bc.sendBalanceUpdate(connId, player);

        bc.broadcastRoomUpdate();
        if (s.callback != null) s.callback.onTopUp(player, amount);
    }

    // ─── Wallet history ───────────────────────────────────────────

    public synchronized void sendWalletHistory(String connId) {
        Player player = s.playersByConnId.get(connId);
        if (player == null) return;
        bc.sendTo(connId, OutboundMsg.walletHistory(player.getId(), player).toJson());
    }
}
