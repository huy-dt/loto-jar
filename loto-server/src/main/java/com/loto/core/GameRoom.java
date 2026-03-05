package com.loto.core;

import com.loto.callback.LotoServerCallback;
import com.loto.model.LotoPage;
import com.loto.model.Player;
import com.loto.model.PlayerInfo;
import com.loto.model.Transaction;
import com.loto.network.ClientHandler;
import com.loto.protocol.OutboundMsg;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Manages one loto game room.
 * Handles game flow, player money (balance / jackpot / refund), and room broadcasts.
 */
public class GameRoom {

    // ─── Config & state ───────────────────────────────────────────
    private final String                       roomId;
    private final ServerConfig                 config;
    private       GameState                    state = GameState.WAITING;

    // ─── Players ──────────────────────────────────────────────────
    private final Map<String, Player>          playersByToken  = new ConcurrentHashMap<>();
    private final Map<String, Player>          playersByConnId = new ConcurrentHashMap<>();
    private final Map<String, ClientHandler>   handlerByConnId = new ConcurrentHashMap<>();
    private final Set<String>                  votedPlayerIds  = ConcurrentHashMap.newKeySet();

    // ─── Game data ────────────────────────────────────────────────
    private final List<Integer>                drawnNumbers   = new ArrayList<>();
    private final List<Integer>                numberPool     = new ArrayList<>();
    private final AtomicInteger                pageIdCounter  = new AtomicInteger(1);

    // ─── Jackpot ──────────────────────────────────────────────────
    private       long                         jackpot        = 0;

    // ─── Ban list ─────────────────────────────────────────────────
    // Lưu playerId bị ban — check khi JOIN
    private final Set<String>                  bannedIds      = ConcurrentHashMap.newKeySet();

    // ─── Callback & scheduler ─────────────────────────────────────
    private       LotoServerCallback           callback;
    private final ScheduledExecutorService     scheduler      = Executors.newSingleThreadScheduledExecutor();
    private       ScheduledFuture<?>           drawTask;

    // ─── Constructor ──────────────────────────────────────────────

    public GameRoom(String roomId, ServerConfig config) {
        this.roomId = roomId;
        this.config = config;
        buildNumberPool();
    }

    public void setCallback(LotoServerCallback callback) {
        this.callback = callback;
    }

    // ─── Player lifecycle ─────────────────────────────────────────

    public synchronized Player join(String connId, String name, ClientHandler handler) {
        boolean isHost = playersByToken.isEmpty();
        Player  player = new Player(name, isHost, config.initialBalance);

        // ── Check ban by temporary playerId seed — ban by name để đơn giản ──
        // (sau khi kick+ban, playerId mới sẽ khác nhưng tên giống → block)
        // Nếu muốn block chắc hơn thì dùng IP ở ClientHandler
        if (bannedIds.contains(name.toLowerCase().trim())) {
            handler.send(OutboundMsg.banned("Tên này đã bị cấm vào phòng").toJson());
            handler.close();
            return null;
        }

        playersByToken.put(player.getToken(), player);
        playersByConnId.put(connId, player);
        handlerByConnId.put(connId, handler);

        // 1. WELCOME — private cho người mới
        handler.send(OutboundMsg.welcome(
                player.getId(), player.getToken(), isHost, player.getPages()).toJson());

        // 2. PLAYER_JOINED — others
        broadcast(OutboundMsg.playerJoined(
                player.getId(), player.getName(), isHost).toJson(), connId);

        // 3. ROOM_UPDATE — everyone
        broadcastRoomUpdate();

        if (callback != null) callback.onPlayerJoined(player);
        return player;
    }

    public synchronized Player reconnect(String connId, String token, ClientHandler handler) {
        Player player = playersByToken.get(token);
        if (player == null) return null;

        player.setConnected(true);
        playersByConnId.values().remove(player);
        playersByConnId.put(connId, player);
        handlerByConnId.put(connId, handler);

        // Re-send full state
        handler.send(OutboundMsg.welcome(
                player.getId(), player.getToken(), player.isHost(), player.getPages()).toJson());

        // Re-send balance + transaction history
        sendBalanceSnapshot(connId, player);

        if (!drawnNumbers.isEmpty()) {
            for (int num : drawnNumbers) {
                handler.send(OutboundMsg.numberDrawn(num, new ArrayList<>(drawnNumbers)).toJson());
            }
        }

        broadcast(OutboundMsg.playerJoined(
                player.getId(), player.getName(), player.isHost()).toJson(), null);
        broadcastRoomUpdate();

        if (callback != null) callback.onPlayerReconnected(player);
        return player;
    }

    public synchronized void onConnectionLost(String connId) {
        Player player = playersByConnId.remove(connId);
        handlerByConnId.remove(connId);
        if (player == null) return;

        player.setConnected(false);
        broadcast(OutboundMsg.playerLeft(player.getId()).toJson(), null);
        broadcastRoomUpdate();

        if (callback != null) callback.onPlayerLeft(player, false);

        scheduler.schedule(() -> {
            if (!player.isConnected()) {
                playersByToken.remove(player.getToken());
                if (callback != null) callback.onPlayerLeft(player, true);
            }
        }, config.reconnectTimeoutMs, TimeUnit.MILLISECONDS);
    }

    // ─── Page purchasing ──────────────────────────────────────────

    public synchronized void buyPages(String connId, int count) {
        Player player = playersByConnId.get(connId);
        if (player == null) return;

        if (state != GameState.WAITING && state != GameState.VOTING) {
            sendTo(connId, OutboundMsg.error("GAME_STARTED", "Cannot buy pages after game started").toJson());
            return;
        }

        count = Math.min(count, config.maxPagesPerBuy);
        long totalCost = config.pricePerPage * count;

        // Check balance
        if (config.pricePerPage > 0 && player.getBalance() < totalCost) {
            sendTo(connId, OutboundMsg.error(
                    "INSUFFICIENT_BALANCE",
                    String.format("Need %d, have %d", totalCost, player.getBalance())).toJson());
            if (callback != null)
                callback.onInsufficientBalance(player, totalCost, player.getBalance());
            return;
        }

        // Generate pages
        List<LotoPage> newPages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            newPages.add(new LotoPage(pageIdCounter.getAndIncrement()));
        }
        player.addPages(newPages);

        // Deduct balance & update jackpot
        if (config.pricePerPage > 0) {
            player.deduct(totalCost,
                String.format("Mua %d tờ × %d", count, config.pricePerPage));
            jackpot += totalCost;
        }

        // Notify buyer
        sendTo(connId, OutboundMsg.pagesAssigned(player.getId(), newPages).toJson());
        sendBalanceUpdate(connId, player);

        // Update room (pageCount + balance changed)
        broadcastRoomUpdate();

        if (callback != null) callback.onPagesBought(player, newPages);
    }

    // ─── Voting ───────────────────────────────────────────────────

    public synchronized void voteStart(String connId) {
        Player player = playersByConnId.get(connId);
        if (player == null || state == GameState.PLAYING || state == GameState.ENDED) return;

        state = GameState.VOTING;
        votedPlayerIds.add(player.getId());

        int needed = voteThreshold();
        broadcast(OutboundMsg.voteUpdate(votedPlayerIds.size(), needed).toJson(), null);
        if (callback != null) callback.onVoteUpdate(votedPlayerIds.size(), needed);

        if (votedPlayerIds.size() >= needed) startGame();
    }

    // ─── Game flow ────────────────────────────────────────────────

    private synchronized void startGame() {
        if (state == GameState.PLAYING) return;
        state = GameState.PLAYING;

        broadcast(OutboundMsg.gameStarting(config.drawIntervalMs).toJson(), null);
        broadcastRoomUpdate();
        if (callback != null) callback.onGameStarting();

        drawTask = scheduler.scheduleAtFixedRate(
                this::drawNextNumber,
                config.drawIntervalMs,
                config.drawIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    private synchronized void drawNextNumber() {
        if (numberPool.isEmpty() || state != GameState.PLAYING) { stopDrawing(); return; }
        int number = numberPool.remove(numberPool.size() - 1);
        drawnNumbers.add(number);

        broadcast(OutboundMsg.numberDrawn(number, new ArrayList<>(drawnNumbers)).toJson(), null);
        if (callback != null) callback.onNumberDrawn(number, new ArrayList<>(drawnNumbers));
    }

    // ─── Win claim ────────────────────────────────────────────────

    public synchronized void claimWin(String connId, int pageId) {
        Player player = playersByConnId.get(connId);
        if (player == null || state != GameState.PLAYING) return;

        LotoPage page = player.getPageById(pageId);
        if (page == null) {
            sendTo(connId, OutboundMsg.error("INVALID_PAGE", "Page not found").toJson());
            return;
        }

        broadcast(OutboundMsg.claimReceived(player.getId(), player.getName(), pageId).toJson(), null);
        if (callback != null) callback.onClaimReceived(player, pageId);
    }

    /**
     * Host confirms win → pays out jackpot to winner, ends game.
     */
    public synchronized void confirmWin(String playerId, int pageId) {
        Player player = findPlayerById(playerId);
        if (player == null) return;

        stopDrawing();
        state = GameState.ENDED;

        // Pay jackpot
        long prize = jackpot;
        if (prize > 0) {
            player.addPrize(prize, "Thắng jackpot " + prize);
            jackpot = 0;
        }

        broadcast(OutboundMsg.winConfirmed(player.getId(), player.getName(), pageId).toJson(), null);
        broadcast(OutboundMsg.gameEnded(player.getId(), player.getName()).toJson(), null);

        // Send updated balance privately to winner
        String winnerConnId = getConnIdForPlayer(player);
        if (winnerConnId != null) sendBalanceUpdate(winnerConnId, player);

        broadcastRoomUpdate();

        if (callback != null) {
            callback.onWinConfirmed(player, pageId, prize);
            callback.onGameEnded(player, prize);
        }
    }

    public synchronized void rejectWin(String playerId, int pageId) {
        Player player = findPlayerById(playerId);
        if (player == null) return;

        broadcast(OutboundMsg.winRejected(player.getId(), pageId).toJson(), null);
        if (callback != null) callback.onWinRejected(player, pageId);
    }

    // ─── Kick / Ban ───────────────────────────────────────────────

    // ─── Kick / Ban ───────────────────────────────────────────────

    /**
     * Kick a player out of the room immediately.
     * - Sends KICKED to the target before closing their socket
     * - Refunds page cost if game hasn't started yet
     * - Broadcasts PLAYER_LEFT + ROOM_UPDATE to everyone else
     */
    public synchronized void kick(String playerId, String reason) {
        Player player = findPlayerById(playerId);
        if (player == null) return;

        String        connId  = getConnIdForPlayer(player);
        ClientHandler handler = connId != null ? handlerByConnId.get(connId) : null;

        // Refund pages if game not started
        if (state == GameState.WAITING || state == GameState.VOTING) {
            long refund = (long) player.getPages().size() * config.pricePerPage;
            if (refund > 0) {
                player.refund(refund, "Hoàn tiền do bị kick");
                jackpot = Math.max(0, jackpot - refund);
            }
        }

        // Remove from all maps BEFORE closing so onConnectionLost won't double-fire
        playersByToken.remove(player.getToken());
        if (connId != null) {
            playersByConnId.remove(connId);
            handlerByConnId.remove(connId);
        }

        // Send KICKED then close — order matters
        if (handler != null) {
            handler.send(OutboundMsg.kicked(reason).toJson());
            handler.close();
        }

        // Notify room
        broadcast(OutboundMsg.playerLeft(player.getId()).toJson(), null);
        broadcastRoomUpdate();

        if (callback != null) callback.onPlayerKicked(player, reason);
    }

    /**
     * Ban a player — adds their name to the blacklist so they cannot rejoin.
     * Also kicks them if currently in the room.
     */
    public synchronized void ban(String playerId, String reason) {
        Player player = findPlayerById(playerId);
        if (player != null) {
            bannedIds.add(player.getName().toLowerCase().trim());
            kick(playerId, reason != null ? reason : "Bị cấm khỏi phòng");
            // onPlayerBanned fired after kick so callback gets both events
            if (callback != null) callback.onPlayerBanned(player.getId(), reason);
        } else {
            bannedIds.add(playerId.toLowerCase().trim());
            if (callback != null) callback.onPlayerBanned(playerId, reason);
        }
    }

    /** Remove a name/id from the ban list. */
    public synchronized void unban(String name) {
        boolean removed = bannedIds.remove(name.toLowerCase().trim());
        if (removed && callback != null) callback.onPlayerUnbanned(name);
    }

    public Set<String> getBannedIds() {
        return Collections.unmodifiableSet(bannedIds);
    }

    /**
     * Host manually tops up a player's balance.
     * Can be called from the host app directly (e.g. after receiving cash).
     */
    public synchronized void topUp(String playerId, long amount, String note) {
        Player player = findPlayerById(playerId);
        if (player == null) return;
        if (amount <= 0) return;

        player.topUp(amount, note != null ? note : "Host nạp tiền");

        String connId = getConnIdForPlayer(player);
        if (connId != null) sendBalanceUpdate(connId, player);

        broadcastRoomUpdate();

        if (callback != null) callback.onTopUp(player, amount);
    }

    // ─── Cancel game — refund all ─────────────────────────────────

    /**
     * Host cancels the game. All players are refunded their page costs.
     * Jackpot is cleared.
     */
    public synchronized void cancelGame(String reason) {
        if (state == GameState.ENDED) return;

        stopDrawing();
        state = GameState.ENDED;

        long totalRefunded = 0;

        // Refund each player proportionally (pages bought × pricePerPage)
        for (Player player : playersByToken.values()) {
            int   pageCount   = player.getPages().size();
            long  refundAmt   = pageCount * config.pricePerPage;
            if (refundAmt > 0) {
                player.refund(refundAmt,
                    String.format("Hoàn tiền — game hủy (%d tờ × %d)", pageCount, config.pricePerPage));
                totalRefunded += refundAmt;

                // Notify player of new balance
                String connId = getConnIdForPlayer(player);
                if (connId != null) sendBalanceUpdate(connId, player);
            }
        }
        jackpot = 0;

        broadcast(OutboundMsg.gameCancelled(reason, totalRefunded).toJson(), null);
        broadcastRoomUpdate();

        if (callback != null) callback.onGameCancelled(reason, totalRefunded);
    }

    // ─── Wallet history (on demand) ───────────────────────────────

    /**
     * Sends full transaction history privately to a player.
     * Called from MessageDispatcher when client sends GET_WALLET.
     */
    public synchronized void sendWalletHistory(String connId) {
        Player player = playersByConnId.get(connId);
        if (player == null) return;
        sendTo(connId, OutboundMsg.walletHistory(player.getId(), player).toJson());
    }

    // ─── Room snapshot ────────────────────────────────────────────

    private void broadcastRoomUpdate() {
        List<PlayerInfo> snapshot = playersByToken.values().stream()
                .map(PlayerInfo::new)
                .collect(Collectors.toList());
        broadcast(OutboundMsg.roomUpdate(snapshot, state.name()).toJson(), null);
    }

    public synchronized List<PlayerInfo> getRoomSnapshot() {
        return playersByToken.values().stream()
                .map(PlayerInfo::new)
                .collect(Collectors.toList());
    }

    /** Current jackpot = sum of all page purchases so far. */
    public long getJackpot() { return jackpot; }

    // ─── Balance helpers ──────────────────────────────────────────

    /** Sends a BALANCE_UPDATE privately to one client. */
    private void sendBalanceUpdate(String connId, Player player) {
        List<Transaction> txList = player.getTransactions();
        if (txList.isEmpty()) return;
        Transaction lastTx = txList.get(txList.size() - 1);
        sendTo(connId, OutboundMsg.balanceUpdate(
                player.getId(), player.getBalance(), lastTx).toJson());
    }

    /** Sends WALLET_HISTORY (full) privately on reconnect. */
    private void sendBalanceSnapshot(String connId, Player player) {
        sendTo(connId, OutboundMsg.walletHistory(player.getId(), player).toJson());
    }

    // ─── Broadcast helpers ────────────────────────────────────────

    public synchronized void broadcast(String json, String excludeConnId) {
        for (Map.Entry<String, ClientHandler> entry : handlerByConnId.entrySet()) {
            if (!entry.getKey().equals(excludeConnId)) entry.getValue().send(json);
        }
    }

    public void sendTo(String connId, String json) {
        ClientHandler handler = handlerByConnId.get(connId);
        if (handler != null) handler.send(json);
    }

    // ─── Private helpers ──────────────────────────────────────────

    private void stopDrawing() {
        if (drawTask != null && !drawTask.isDone()) drawTask.cancel(false);
    }

    private void buildNumberPool() {
        for (int i = 1; i <= 90; i++) numberPool.add(i);
        Collections.shuffle(numberPool);
    }

    private int voteThreshold() {
        int total = Math.max(1, playersByToken.size());
        return Math.max(1, (int) Math.ceil(total * config.voteThresholdPct / 100.0));
    }

    private Player findPlayerById(String id) {
        return playersByToken.values().stream()
                             .filter(p -> p.getId().equals(id))
                             .findFirst().orElse(null);
    }

    private String getConnIdForPlayer(Player target) {
        return playersByConnId.entrySet().stream()
                .filter(e -> e.getValue() == target)
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    public String      getRoomId()       { return roomId; }
    public GameState   getState()        { return state; }
    public ServerConfig getConfig()      { return config; }

    public void shutdown() {
        stopDrawing();
        scheduler.shutdownNow();
    }
}
