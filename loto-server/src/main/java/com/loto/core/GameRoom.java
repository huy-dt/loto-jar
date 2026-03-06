package com.loto.core;

import com.loto.callback.LotoServerCallback;
import com.loto.model.BotPlayer;
import com.loto.model.LotoPage;
import com.loto.model.Player;
import com.loto.model.PlayerInfo;
import com.loto.model.Transaction;

import com.loto.protocol.OutboundMsg;

import com.loto.network.IClientHandler;

import java.util.*;
import com.loto.persist.JsonPersistence;
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
    private final Map<String, IClientHandler>  handlerByConnId = new ConcurrentHashMap<>();
    private final Map<String, String>          ipByConnId      = new ConcurrentHashMap<>();

    // ─── Bot manager ──────────────────────────────────────────────
    private       BotManager                   botManager;
    private final Set<String>                  votedPlayerIds  = ConcurrentHashMap.newKeySet();

    // ─── Game data ────────────────────────────────────────────────
    private final List<Integer>                drawnNumbers   = new ArrayList<>();
    private final List<Integer>                numberPool     = new ArrayList<>();
    private final AtomicInteger                pageIdCounter  = new AtomicInteger(1);

    // ─── Jackpot & winners ───────────────────────────────────────────
    private       long                         jackpot        = 0;
    /** Players confirmed as winners — jackpot split on reset(). */
    private final List<String>                 winnerIds      = new ArrayList<>();

    // ─── Ban list ─────────────────────────────────────────────────
    // Ban theo tên (fallback) và theo IP (chính)
    private final Set<String>                  bannedIds      = ConcurrentHashMap.newKeySet();
    private final Set<String>                  bannedIps      = ConcurrentHashMap.newKeySet();

    // ─── Callback & scheduler ─────────────────────────────────────
    private       LotoServerCallback           callback;
    private       JsonPersistence             persistence;
    private final ScheduledExecutorService     scheduler      = Executors.newSingleThreadScheduledExecutor();
    private       ScheduledFuture<?>           drawTask;
    private       ScheduledFuture<?>           saveDebounce;
    /** Current draw interval — can be changed live via setDrawInterval(). */
    private volatile int                       currentDrawIntervalMs;
    /** Current price per page — can be changed by host when no pages bought yet. */
    private volatile long                      currentPricePerPage;
    /** Current auto-reset delay ms — 0 = disabled. Can be changed anytime via setAutoResetDelay(). */
    private volatile int                       currentAutoResetDelayMs;
    /** Pending auto-reset task (scheduled after game ENDED/CANCELLED). */
    private       ScheduledFuture<?>           autoResetTask;
    private static final int                   SAVE_DEBOUNCE_MS = 500;

    // ─── Constructor ──────────────────────────────────────────────

    public GameRoom(String roomId, ServerConfig config) {
        this.roomId = roomId;
        this.config = config;
        this.currentDrawIntervalMs  = config.drawIntervalMs;
        this.currentPricePerPage    = config.pricePerPage;
        this.currentAutoResetDelayMs = config.autoResetDelayMs;
        buildNumberPool();
    }

    public void setCallback(LotoServerCallback callback) {
        this.callback = callback;
    }

    public BotManager getBotManager() {
        if (botManager == null) botManager = new BotManager(this);
        return botManager;
    }


    public void setPersistence(JsonPersistence persistence) {
        this.persistence = persistence;
    }


    // ─── Snapshot restore ─────────────────────────────────────────

    /**
     * Restores room state from a {@link JsonPersistence.GameSnapshot}.
     * Called by {@link com.loto.core.LotoServer#loadSavedState()} at startup.
     * The room must be freshly constructed (no players connected yet).
     */
    public synchronized void restoreFromSnapshot(JsonPersistence.GameSnapshot snap) {
        // Restore state
        try { this.state = GameState.valueOf(snap.gameState); }
        catch (Exception e) { this.state = GameState.WAITING; }
        this.currentDrawIntervalMs  = snap.drawIntervalMs >= 0
                ? snap.drawIntervalMs : config.drawIntervalMs;
        this.currentPricePerPage    = snap.pricePerPage >= 0
                ? snap.pricePerPage : config.pricePerPage;
        this.currentAutoResetDelayMs = snap.autoResetDelayMs >= 0
                ? snap.autoResetDelayMs : config.autoResetDelayMs;

        this.jackpot = snap.jackpot;

        // Restore drawn numbers & rebuild number pool
        this.drawnNumbers.clear();
        this.drawnNumbers.addAll(snap.drawnNumbers);
        this.numberPool.clear();
        for (int i = 1; i <= 90; i++) {
            if (!drawnNumbers.contains(i)) numberPool.add(i);
        }
        Collections.shuffle(numberPool);

        // Restore banned list
        this.bannedIds.clear();
        this.bannedIds.addAll(snap.bannedIds);
        this.winnerIds.clear();
        this.winnerIds.addAll(snap.winnerIds);

        // Restore players (offline — they'll reconnect via token)
        this.playersByToken.clear();
        this.playersByConnId.clear();
        this.handlerByConnId.clear();

        int maxPageId = 0;
        for (JsonPersistence.PlayerSnapshot ps : snap.players) {
            Player player = Player.restore(ps.id, ps.token, ps.name, ps.isHost,
                                           ps.balance, ps.pages, ps.transactions);
            player.setConnected(false);
            playersByToken.put(player.getToken(), player);
            for (LotoPage page : player.getPages()) {
                if (page.getId() > maxPageId) maxPageId = page.getId();
            }
        }
        pageIdCounter.set(maxPageId + 1);

        System.out.printf("[GameRoom] Restored: state=%s players=%d drawn=%d jackpot=%,d%n",
                state, playersByToken.size(), drawnNumbers.size(), jackpot);
    }

    // ─── Persistence helpers ──────────────────────────────────────

    /**
     * Builds a full snapshot of the current room state and saves it to disk.
     * Called automatically after every state-changing operation.
     * No-op if no persistence is configured.
     */
    /**
     * Schedules a save 500ms from now, cancelling any pending save.
     * This way rapid events (e.g. many numbers drawn quickly) only trigger one write.
     * For critical events (game end, cancel) call saveStateNow() instead.
     */
    public void saveState() {
        if (persistence == null) return;
        if (saveDebounce != null && !saveDebounce.isDone()) saveDebounce.cancel(false);
        saveDebounce = scheduler.schedule(this::saveStateNow, SAVE_DEBOUNCE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Bypasses debounce — writes immediately. Used for critical state changes. */
    public void saveStateNow() {
        if (persistence == null) return;
        JsonPersistence.GameSnapshot snap = new JsonPersistence.GameSnapshot();
        snap.roomId      = roomId;
        snap.gameState   = state.name();
        snap.jackpot     = jackpot;
        snap.drawnNumbers.addAll(drawnNumbers);
        snap.bannedIds.addAll(bannedIds);
        snap.winnerIds.addAll(winnerIds);
        snap.drawIntervalMs     = currentDrawIntervalMs;
        snap.pricePerPage       = currentPricePerPage;
        snap.autoResetDelayMs   = currentAutoResetDelayMs;

        for (Player p : playersByToken.values()) {
            JsonPersistence.PlayerSnapshot ps = new JsonPersistence.PlayerSnapshot();
            ps.id           = p.getId();
            ps.token        = p.getToken();
            ps.name         = p.getName();
            ps.isHost       = p.isHost();
            ps.balance      = p.getBalance();
            ps.pages.addAll(p.getPages());
            ps.transactions.addAll(p.getTransactions());
            snap.players.add(ps);
        }
        persistence.save(snap);
    }

    // ─── Player lifecycle ─────────────────────────────────────────

    public synchronized Player join(String connId, String name, IClientHandler handler) {
        boolean isHost = playersByToken.isEmpty();
        Player  player = new Player(name, isHost, config.initialBalance);

        // ── Check ban by temporary playerId seed — ban by name để đơn giản ──
        // (sau khi kick+ban, playerId mới sẽ khác nhưng tên giống → block)
        // Nếu muốn block chắc hơn thì dùng IP ở ClientHandler
        // Check ban by IP (primary) then by name (fallback)
        String ip = handler.getRemoteIp();
        if (bannedIps.contains(ip)) {
            handler.send(OutboundMsg.banned("IP này đã bị cấm vào phòng").toJson());
            handler.close();
            return null;
        }
        if (bannedIds.contains(name.toLowerCase().trim())) {
            handler.send(OutboundMsg.banned("Tên này đã bị cấm vào phòng").toJson());
            handler.close();
            return null;
        }
        ipByConnId.put(connId, ip);

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
        saveState();
        return player;
    }

    // ─── Bot join / remove ────────────────────────────────────────

    /**
     * Joins a pre-constructed BotPlayer into the room.
     * Skips ban checks and normal welcome flow — broadcasts PLAYER_JOINED + ROOM_UPDATE.
     */
    public synchronized Player joinBot(String connId, BotPlayer bot, IClientHandler handler) {
        playersByToken.put(bot.getToken(), bot);
        playersByConnId.put(connId, bot);
        handlerByConnId.put(connId, handler);
        ipByConnId.put(connId, "bot");

        broadcast(OutboundMsg.playerJoined(bot.getId(), bot.getName(), false).toJson(), null);
        broadcastRoomUpdate();

        if (callback != null) callback.onPlayerJoined(bot);
        saveState();
        return bot;
    }

    /**
     * Removes a bot from the room silently — broadcasts PLAYER_LEFT + ROOM_UPDATE.
     * Does NOT refund pages (bot money doesn't matter).
     */
    public synchronized void removeBot(String connId, BotPlayer bot) {
        playersByToken.remove(bot.getToken());
        playersByConnId.remove(connId);
        handlerByConnId.remove(connId);
        ipByConnId.remove(connId);

        broadcast(OutboundMsg.playerLeft(bot.getId()).toJson(), null);
        broadcastRoomUpdate();
        saveState();
    }

    /**
     * Bot buys pages — same logic as buyPages() but bypasses the balance check
     * (bots always have enough; host sets their balance on addBot).
     */
    public synchronized void buyPagesBot(String connId, int count) {
        Player player = playersByConnId.get(connId);
        if (!(player instanceof BotPlayer)) return;
        if (state != GameState.WAITING && state != GameState.VOTING) return;

        count = Math.min(count, config.maxPagesPerBuy);
        long totalCost = currentPricePerPage * count;

        // Bots still pay from their balance if they have it, otherwise buy for free
        List<LotoPage> newPages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            newPages.add(new LotoPage(pageIdCounter.getAndIncrement()));
        }
        player.addPages(newPages);

        if (currentPricePerPage > 0 && player.getBalance() >= totalCost) {
            player.deduct(totalCost,
                String.format("Bot mua %d tờ × %,d", count, currentPricePerPage));
            jackpot += totalCost;
        }

        broadcastRoomUpdate();
        System.out.printf("[BOT] %s mua %d tờ%n", player.getName(), count);
        if (callback != null) callback.onPagesBought(player, newPages);
        saveState();
    }

    /**
     * Bot claims a win — same as claimWin() but identified as a bot action.
     */
    public synchronized void claimWinBot(String connId, int pageId) {
        Player player = playersByConnId.get(connId);
        if (!(player instanceof BotPlayer)) return;
        claimWin(connId, pageId);
    }

    public synchronized Player reconnect(String connId, String token, IClientHandler handler) {
        Player player = playersByToken.get(token);
        if (player == null) return null;

        player.setConnected(true);
        playersByConnId.values().remove(player);
        playersByConnId.put(connId, player);
        handlerByConnId.put(connId, handler);
        ipByConnId.put(connId, handler.getRemoteIp());

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
        ipByConnId.remove(connId);
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
        long totalCost = currentPricePerPage * count;

        // Check balance
        if (currentPricePerPage > 0 && player.getBalance() < totalCost) {
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
        if (currentPricePerPage > 0) {
            player.deduct(totalCost,
                String.format("Mua %d tờ × %d", count, currentPricePerPage));
            jackpot += totalCost;
        }

        // Notify buyer
        sendTo(connId, OutboundMsg.pagesAssigned(player.getId(), newPages).toJson());
        sendBalanceUpdate(connId, player);

        // Update room (pageCount + balance changed)
        broadcastRoomUpdate();

        if (callback != null) callback.onPagesBought(player, newPages);
        saveState();
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

        broadcast(OutboundMsg.gameStarting(currentDrawIntervalMs).toJson(), null);
        broadcastRoomUpdate();
        if (callback != null) callback.onGameStarting();

        // Notify bots to buy pages
        if (botManager != null) botManager.onGameStarted();

        drawTask = scheduler.scheduleAtFixedRate(
                this::drawNextNumber,
                currentDrawIntervalMs,
                currentDrawIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    private synchronized void drawNextNumber() {
        if (state != GameState.PLAYING) { stopDrawing(); return; }
        if (numberPool.isEmpty()) {
            stopDrawing();
            // All 90 numbers drawn — end the game automatically
            state = GameState.ENDED;
            broadcast(OutboundMsg.gameEndedByServer("Hết 90 số — không có người thắng").toJson(), null);
            broadcastRoomUpdate();
            saveState();
            if (callback != null) callback.onServerGameEnded("Hết 90 số — không có người thắng");
            // Auto-reset if configured
            if (currentAutoResetDelayMs > 0) scheduleAutoReset(currentAutoResetDelayMs);
            return;
        }
        int number = numberPool.remove(numberPool.size() - 1);
        drawnNumbers.add(number);

        broadcast(OutboundMsg.numberDrawn(number, new ArrayList<>(drawnNumbers)).toJson(), null);
        if (callback != null) callback.onNumberDrawn(number, new ArrayList<>(drawnNumbers));

        // Let bots check their pages
        if (botManager != null) botManager.onNumberDrawn(new ArrayList<>(drawnNumbers));
    }

    // ─── Win claim ────────────────────────────────────────────────

    public synchronized void claimWin(String connId, int pageId) {
        Player player = playersByConnId.get(connId);
        // Allow claim while PLAYING (game running) or ENDED (first winner confirmed,
        // others still have time to claim before reset)
        if (player == null) return;
        if (state != GameState.PLAYING && state != GameState.ENDED) return;

        // Prevent the same player claiming the same page twice
        if (winnerIds.contains(player.getId())) {
            sendTo(connId, OutboundMsg.error("ALREADY_CLAIMED",
                    "Bạn đã được xác nhận thắng rồi").toJson());
            return;
        }

        LotoPage page = player.getPageById(pageId);
        if (page == null) {
            sendTo(connId, OutboundMsg.error("INVALID_PAGE", "Page not found").toJson());
            return;
        }

        broadcast(OutboundMsg.claimReceived(player.getId(), player.getName(), pageId).toJson(), null);
        if (callback != null) callback.onClaimReceived(player, pageId);

        // Auto-verify if enabled: server checks the page immediately
        if (config.autoVerifyWin) {
            boolean valid = page.hasWinningRow(new ArrayList<>(drawnNumbers));
            if (valid) {
                confirmWin(player.getId(), pageId);
            } else {
                rejectWin(player.getId(), pageId);
            }
        }
    }

    /**
     * Confirms a winner — stops drawing and marks them as a winner.
     * Jackpot is NOT paid yet; it will be split equally among all confirmed
     * winners when {@link #reset()} is called.
     * Multiple players can be confirmed before reset.
     */
    public synchronized void confirmWin(String playerId, int pageId) {
        Player player = findPlayerById(playerId);
        if (player == null) return;
        // Only valid while game is active (PLAYING or ENDED-but-not-reset)
        if (state != GameState.PLAYING && state != GameState.ENDED) return;

        // Stop drawing on first confirm
        if (state == GameState.PLAYING) {
            stopDrawing();
            state = GameState.ENDED;
        }

        // Register winner (avoid duplicates)
        if (!winnerIds.contains(playerId)) {
            winnerIds.add(playerId);
        }

        broadcast(OutboundMsg.winConfirmed(player.getId(), player.getName(), pageId).toJson(), null);

        // Only broadcast GAME_ENDED on the first winner
        if (winnerIds.size() == 1) {
            broadcast(OutboundMsg.gameEnded(player.getId(), player.getName()).toJson(), null);
        }

        broadcastRoomUpdate();

        if (callback != null) {
            // prize = 0 here; actual payout happens at reset()
            callback.onWinConfirmed(player, pageId, 0);
            if (winnerIds.size() == 1) callback.onGameEnded(player, 0);
        }
        saveStateNow();

        // Auto-reset if configured (only on first winner — when game transitions to ENDED)
        if (winnerIds.size() == 1 && currentAutoResetDelayMs > 0) {
            scheduleAutoReset(currentAutoResetDelayMs);
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
        IClientHandler handler = connId != null ? handlerByConnId.get(connId) : null;

        // Refund pages if game not started
        if (state == GameState.WAITING || state == GameState.VOTING) {
            long refund = (long) player.getPages().size() * currentPricePerPage;
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
            // Ban by IP (primary) + name (fallback)
            String connId = getConnIdForPlayer(player);
            if (connId != null) {
                String ip = ipByConnId.get(connId);
                if (ip != null && !ip.equals("unknown")) bannedIps.add(ip);
            }
            bannedIds.add(player.getName().toLowerCase().trim());
            kick(playerId, reason != null ? reason : "Bị cấm khỏi phòng");
            if (callback != null) callback.onPlayerBanned(player.getId(), reason);
        } else {
            bannedIds.add(playerId.toLowerCase().trim());
            if (callback != null) callback.onPlayerBanned(playerId, reason);
        }
    }

    /** Ban an IP address directly (without needing a playerId). */
    public synchronized void banIp(String ip) {
        if (ip != null && !ip.isBlank()) {
            bannedIps.add(ip.trim());
            if (callback != null) callback.onPlayerBanned("ip:" + ip, "IP ban");
        }
    }

    /** Unban an IP address explicitly. */
    public synchronized void unbanIp(String ip) {
        bannedIps.remove(ip);
    }

    public Set<String> getBannedIps() {
        return Collections.unmodifiableSet(bannedIps);
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


    // ─── Reset ────────────────────────────────────────────────────

    /**
     * Resets the room back to WAITING state so a new game can be started.
     * - Clears drawn numbers, rebuilds number pool
     * - Resets jackpot to 0
     * - Clears all player pages (balance kept)
     * - Clears votes
     * - Resets pageIdCounter
     * - Broadcasts ROOM_UPDATE so clients reflect the new state
     *
     * Does NOT remove players or clear ban list.
     */
    public synchronized void reset() {
        stopDrawing();
        cancelAutoReset();   // cancel any pending auto-reset timer

        // ── Pay out jackpot to all confirmed winners ──────────────
        long prizeEach   = 0;
        int  winnerCount = winnerIds.size();
        if (winnerCount > 0 && jackpot > 0) {
            prizeEach = jackpot / winnerCount;
            for (String wid : winnerIds) {
                Player w = findPlayerById(wid);
                if (w == null) continue;
                w.addPrize(prizeEach,
                    String.format("Jackpot chia %d người: %,d", winnerCount, prizeEach));
                String wConnId = getConnIdForPlayer(w);
                if (wConnId != null) sendBalanceUpdate(wConnId, w);
            }
            if (callback != null) callback.onJackpotPaid(new ArrayList<>(winnerIds), prizeEach);
        }

        // ── Reset game state ──────────────────────────────────────
        state = GameState.WAITING;
        drawnNumbers.clear();
        numberPool.clear();
        buildNumberPool();
        jackpot = 0;
        votedPlayerIds.clear();
        pageIdCounter.set(1);
        winnerIds.clear();
        // currentDrawIntervalMs, currentPricePerPage, currentAutoResetDelayMs
        // đều là cài đặt phòng — GIỮ NGUYÊN qua các ván

        // Clear each player's pages (balance stays)
        for (Player p : playersByToken.values()) {
            p.clearPages();
        }

        broadcast(OutboundMsg.roomReset(prizeEach, winnerCount).toJson(), null);
        broadcastRoomUpdate();
        saveStateNow();

        if (callback != null) callback.onRoomReset();
        if (botManager != null) botManager.onRoomReset();
    }

    // ─── Server-controlled game flow ──────────────────────────────

    /**
     * Start the game immediately from the server side — bypasses vote threshold.
     * Useful for programmatic control (timer, admin API, etc.).
     * No-op if the game is already PLAYING or ENDED.
     */
    public synchronized void serverStart() {
        if (state == GameState.ENDED) {
            System.err.println("[GameRoom] serverStart() ignored — game ENDED. Call reset() first.");
            return;
        }
        if (state == GameState.PLAYING) return;
        startGame();
    }

    /**
     * End the game gracefully from the server side.
     * Behaves like a win confirmation without a winner — no prize is paid.
     * Broadcasts GAME_ENDED with a null winner and transitions to ENDED.
     * Call this when all 90 numbers have been drawn or by a scheduler.
     */
    public synchronized void serverEnd(String reason) {
        if (state != GameState.PLAYING) return;

        stopDrawing();
        state = GameState.ENDED;

        String msg = reason != null ? reason : "Server kết thúc game";
        broadcast(OutboundMsg.gameEndedByServer(msg).toJson(), null);
        broadcastRoomUpdate();

        if (callback != null) callback.onServerGameEnded(msg);

        // Auto-reset if configured
        if (currentAutoResetDelayMs > 0) scheduleAutoReset(currentAutoResetDelayMs);
    }

    /**
     * Cancel the game from the server side — refunds all players.
     * Identical to {@link #cancelGame(String)} but callable without a host.
     */
    public synchronized void serverCancel(String reason) {
        cancelGame(reason != null ? reason : "Server hủy game");
    }


    // ─── Live draw speed control ──────────────────────────────────

    /**
     * Changes the draw interval immediately, even while the game is running.
     *
     * <p>If the game is currently PLAYING, the existing scheduled task is
     * cancelled and a new one starts with the new interval right away.
     * The next number will be drawn after {@code intervalMs} milliseconds
     * from the moment this method is called.
     *
     * @param intervalMs new interval in milliseconds (minimum 200ms)
     */
    public synchronized void setDrawInterval(int intervalMs) {
        if (intervalMs < 200) intervalMs = 200;   // hard floor
        int old = currentDrawIntervalMs;
        currentDrawIntervalMs = intervalMs;

        // If currently playing, reschedule immediately with new interval
        if (state == GameState.PLAYING) {
            stopDrawing();
            drawTask = scheduler.scheduleAtFixedRate(
                    this::drawNextNumber,
                    intervalMs,   // initial delay = new interval (clean cadence)
                    intervalMs,
                    TimeUnit.MILLISECONDS);
        }

        broadcast(OutboundMsg.drawIntervalChanged(intervalMs).toJson(), null);
        saveState();

        if (callback != null) callback.onDrawIntervalChanged(old, intervalMs);
    }

    public int getCurrentDrawIntervalMs() { return currentDrawIntervalMs; }

    // ─── Live price control ───────────────────────────────────────

    /**
     * Changes the price per page.
     * Allowed whenever NO player has bought any page yet (jackpot == 0),
     * regardless of game state. Once the first page is purchased the price is locked.
     * Broadcasts PRICE_PER_PAGE_CHANGED to all clients.
     *
     * @param newPrice new price in đồng (must be >= 0)
     */
    public synchronized void setPricePerPage(long newPrice) {
        if (newPrice < 0) newPrice = 0;
        // Lock price once any page has been purchased (jackpot > 0 means money was collected)
        if (jackpot > 0) {
            // Already have purchases — reject silently (caller should check first)
            return;
        }
        long old = currentPricePerPage;
        currentPricePerPage = newPrice;
        broadcast(OutboundMsg.pricePerPageChanged(newPrice).toJson(), null);
        saveState();
        if (callback != null) callback.onPricePerPageChanged(old, newPrice);
    }

    /** Returns true if the price per page can still be changed (no pages bought yet). */
    public boolean canChangePricePerPage() { return jackpot == 0; }

    public long getCurrentPricePerPage() { return currentPricePerPage; }

    // ─── Auto-reset scheduling ────────────────────────────────────

    /**
     * Sets the auto-reset delay at runtime — can be called at any time, even mid-game.
     * <ul>
     *   <li>{@code delayMs == 0}: disables auto-reset and cancels any pending timer.</li>
     *   <li>Game already ENDED: cancels old timer and starts a new one with the new delay.</li>
     *   <li>Game still running: saves new value; timer activates when the game ends.</li>
     * </ul>
     * Broadcasts AUTO_RESET_SCHEDULED (with delayMs=0 to signal cancellation).
     */
    public synchronized void setAutoResetDelay(int delayMs) {
        if (delayMs < 0) delayMs = 0;
        int old = currentAutoResetDelayMs;
        currentAutoResetDelayMs = delayMs;
        saveState();
        if (callback != null) callback.onAutoResetDelayChanged(old, delayMs);

        if (delayMs == 0) {
            cancelAutoReset();
            broadcast(OutboundMsg.autoResetScheduled(0).toJson(), null);
        } else if (state == GameState.ENDED) {
            // Game already over — apply immediately
            scheduleAutoReset(delayMs);
        } else {
            // Not ended yet — just announce the upcoming delay
            broadcast(OutboundMsg.autoResetScheduled(delayMs).toJson(), null);
        }
    }

    public int getCurrentAutoResetDelayMs() { return currentAutoResetDelayMs; }

    /**
     * Schedules an automatic room reset after {@code delayMs} milliseconds.
     * Cancels any previously scheduled auto-reset.
     * Broadcasts AUTO_RESET_SCHEDULED so clients can show a countdown.
     * If delayMs is 0 resets immediately.
     */
    public synchronized void scheduleAutoReset(int delayMs) {
        cancelAutoReset();
        broadcast(OutboundMsg.autoResetScheduled(delayMs).toJson(), null);
        if (callback != null) callback.onAutoResetScheduled(delayMs);
        if (delayMs <= 0) {
            reset();
            return;
        }
        autoResetTask = scheduler.schedule(() -> {
            synchronized (GameRoom.this) { reset(); }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Cancels a pending auto-reset without resetting the room. */
    public synchronized void cancelAutoReset() {
        if (autoResetTask != null && !autoResetTask.isDone()) {
            autoResetTask.cancel(false);
            autoResetTask = null;
        }
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
            long  refundAmt   = pageCount * currentPricePerPage;
            if (refundAmt > 0) {
                player.refund(refundAmt,
                    String.format("Hoàn tiền — game hủy (%d tờ × %d)", pageCount, currentPricePerPage));
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
        saveStateNow();

        // Auto-reset if configured
        if (currentAutoResetDelayMs > 0) scheduleAutoReset(currentAutoResetDelayMs);
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
        String json = OutboundMsg.roomUpdate(snapshot, state.name(),
                currentPricePerPage, currentAutoResetDelayMs).toJson();
        broadcast(json, null);
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

    public void broadcast(String json, String excludeConnId) {
        // Snapshot handlers under lock, then send outside lock so a slow/hung
        // client cannot block the entire room while holding the monitor.
        List<IClientHandler> targets;
        synchronized (this) {
            targets = new ArrayList<>();
            for (Map.Entry<String, IClientHandler> entry : handlerByConnId.entrySet()) {
                if (!entry.getKey().equals(excludeConnId)) targets.add(entry.getValue());
            }
        }
        for (IClientHandler h : targets) h.send(json);
    }

    public void sendTo(String connId, String json) {
        IClientHandler handler = handlerByConnId.get(connId);
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

    /** Used by MessageDispatcher to check host status. */
    public Player getPlayerByConnId(String connId) {
        return playersByConnId.get(connId);
    }

    public String      getRoomId()       { return roomId; }
    public GameState   getState()        { return state; }
    public ServerConfig getConfig()      { return config; }

    public void shutdown() {
        stopDrawing();
        if (botManager != null) botManager.shutdown();
        scheduler.shutdownNow();
    }
}
