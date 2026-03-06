package com.loto.core;

import com.loto.callback.LotoServerCallback;
import com.loto.model.LotoPage;
import com.loto.model.Player;
import com.loto.network.IClientHandler;
import com.loto.persist.JsonPersistence;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds all mutable state shared across GameRoom sub-managers.
 * Not intended for direct use by callers — access via {@link GameRoom}.
 */
public class GameRoomState {

    // ─── Identity ─────────────────────────────────────────────────
    final String         roomId;
    final ServerConfig   config;

    // ─── Game state ───────────────────────────────────────────────
    volatile GameState   state = GameState.WAITING;

    // ─── Players ──────────────────────────────────────────────────
    final Map<String, Player>         playersByToken  = new ConcurrentHashMap<>();
    final Map<String, Player>         playersByConnId = new ConcurrentHashMap<>();
    final Map<String, IClientHandler> handlerByConnId = new ConcurrentHashMap<>();
    final Map<String, String>         ipByConnId      = new ConcurrentHashMap<>();

    // ─── Admin sessions ───────────────────────────────────────────
    final Set<String>    adminConnIds   = ConcurrentHashMap.newKeySet();

    // ─── Voting ───────────────────────────────────────────────────
    final Set<String>    votedPlayerIds = ConcurrentHashMap.newKeySet();

    // ─── Game data ────────────────────────────────────────────────
    final List<Integer>  drawnNumbers  = new ArrayList<>();
    final List<Integer>  numberPool    = new ArrayList<>();
    final AtomicInteger  pageIdCounter = new AtomicInteger(1);

    // ─── Jackpot & winners ────────────────────────────────────────
    long           jackpot    = 0;
    final List<String> winnerIds = new ArrayList<>();

    // ─── Ban lists ────────────────────────────────────────────────
    final Set<String>    bannedIds  = ConcurrentHashMap.newKeySet();
    final Set<String>    bannedIps  = ConcurrentHashMap.newKeySet();

    // ─── Bot manager reference (set by GameRoom facade) ───────────
    BotManager botManagerRef;

    // ─── Callbacks & persistence ──────────────────────────────────
    LotoServerCallback callback;
    JsonPersistence    persistence;

    // ─── Scheduler & tasks ────────────────────────────────────────
    final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    ScheduledFuture<?> drawTask;
    ScheduledFuture<?> saveDebounce;
    ScheduledFuture<?> autoResetTask;
    ScheduledFuture<?> autoStartTask;   // countdown to auto-start

    static final int SAVE_DEBOUNCE_MS = 500;

    // ─── Live-tunable settings ────────────────────────────────────
    volatile int  currentDrawIntervalMs;
    volatile long currentPricePerPage;
    /** Giá đặt trong lúc PLAYING — sẽ áp dụng từ ván tiếp theo. -1 = không có pending. */
    volatile long pendingPricePerPage = -1;
    volatile int  currentAutoResetDelayMs;
    volatile int  currentAutoStartMs;

    // ─── Constructor ──────────────────────────────────────────────

    GameRoomState(String roomId, ServerConfig config) {
        this.roomId                  = roomId;
        this.config                  = config;
        this.currentDrawIntervalMs   = config.drawIntervalMs;
        this.currentPricePerPage     = config.pricePerPage;
        this.currentAutoResetDelayMs = config.autoResetDelayMs;
        this.currentAutoStartMs      = config.autoStartMs;
        buildNumberPool();
    }

    // ─── Helpers shared by all managers ───────────────────────────

    void buildNumberPool() {
        numberPool.clear();
        for (int i = 1; i <= 90; i++) numberPool.add(i);
        Collections.shuffle(numberPool);
    }

    Player findPlayerById(String id) {
        return playersByToken.values().stream()
                .filter(p -> p.getId().equals(id))
                .findFirst().orElse(null);
    }

    String getConnIdForPlayer(Player target) {
        return playersByConnId.entrySet().stream()
                .filter(e -> e.getValue() == target)
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    int voteThreshold() {
        long realPlayers = playersByToken.values().stream()
                .filter(p -> !p.isBot())
                .count();
        int total = Math.max(1, (int) realPlayers);
        return Math.max(1, (int) Math.ceil(total * config.voteThresholdPct / 100.0));
    }

    long realPlayerCount() {
        return playersByToken.values().stream().filter(p -> !p.isBot()).count();
    }

    // ─── Snapshot restore ─────────────────────────────────────────

    void restoreFromSnapshot(JsonPersistence.GameSnapshot snap) {
        try { this.state = GameState.valueOf(snap.gameState); }
        catch (Exception e) { this.state = GameState.WAITING; }

        this.currentDrawIntervalMs   = snap.drawIntervalMs   >= 0 ? snap.drawIntervalMs   : config.drawIntervalMs;
        this.currentPricePerPage     = snap.pricePerPage     >= 0 ? snap.pricePerPage     : config.pricePerPage;
        this.currentAutoResetDelayMs = snap.autoResetDelayMs >= 0 ? snap.autoResetDelayMs : config.autoResetDelayMs;

        this.jackpot = snap.jackpot;

        this.drawnNumbers.clear();
        this.drawnNumbers.addAll(snap.drawnNumbers);
        this.numberPool.clear();
        for (int i = 1; i <= 90; i++) {
            if (!drawnNumbers.contains(i)) numberPool.add(i);
        }
        Collections.shuffle(numberPool);

        this.bannedIds.clear();
        this.bannedIds.addAll(snap.bannedIds);
        this.winnerIds.clear();
        this.winnerIds.addAll(snap.winnerIds);

        this.playersByToken.clear();
        this.playersByConnId.clear();
        this.handlerByConnId.clear();

        int maxPageId = 0;
        for (JsonPersistence.PlayerSnapshot ps : snap.players) {
            Player player = Player.restore(ps.id, ps.token, ps.name, false,
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
}
