package com.loto.core;

import com.loto.model.BotPlayer;
import com.loto.model.LotoPage;
import com.loto.model.Player;
import com.loto.network.IClientHandler;

import java.util.*;
import java.util.concurrent.*;

/**
 * Manages all bots in a GameRoom.
 *
 * Lifecycle:
 *  - addBot()    : tạo BotPlayer, join vào room như player thường
 *  - removeBot() : xóa bot khỏi room
 *  - onGameStarted() : mỗi bot mua ngẫu nhiên 1..maxPages tờ
 *  - onNumberDrawn() : mỗi bot kiểm tra tất cả tờ → nếu kình thì claimWin
 *  - onRoomReset()   : bot tự mua lại ở ván tiếp theo (được gọi từ onGameStarted)
 *
 * Bot dùng fake IClientHandler nội bộ (không có socket thật).
 */
public class BotManager {

    // ─── Fake handler để inject bot vào GameRoom ──────────────────

    /**
     * A no-op IClientHandler for bots — bots don't need a real socket.
     * Messages "sent" to a bot are silently discarded.
     */
    public static class BotHandler implements IClientHandler {
        private final String botConnId;
        BotHandler(String botConnId) { this.botConnId = botConnId; }

        @Override public void send(String json)      { /* bot ignores messages */ }
        @Override public void close()                { /* no socket to close  */ }
        @Override public boolean isConnected()       { return true; }
        @Override public String getConnectionId()    { return botConnId; }
        @Override public String getRemoteIp()        { return "bot"; }
    }

    // ─── State ────────────────────────────────────────────────────

    private final GameRoom                     room;
    private final Random                       rng         = new Random();
    /** connId → BotPlayer */
    private final Map<String, BotPlayer>       bots        = new LinkedHashMap<>();
    /** name (lowercase) → connId */
    private final Map<String, String>          nameToConn  = new HashMap<>();
    /** Executor for async bot actions (buying, claiming) */
    private final ScheduledExecutorService     executor    = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bot-manager");
        t.setDaemon(true);
        return t;
    });

    public BotManager(GameRoom room) {
        this.room = room;
    }

    // ─── Public API ───────────────────────────────────────────────

    /**
     * Adds a bot with the given name and max pages.
     * The bot joins the room immediately as a regular player.
     *
     * @param name     display name (must be unique in room)
     * @param balance  starting balance
     * @param maxPages max tờ bot sẽ mua mỗi ván (mua ngẫu nhiên 1..maxPages)
     * @return the created BotPlayer, or null if name already taken
     */
    public synchronized BotPlayer addBot(String name, long balance, int maxPages) {
        String key = name.toLowerCase().trim();
        if (nameToConn.containsKey(key)) return null; // already exists

        String     connId  = "bot-" + UUID.randomUUID().toString().substring(0, 8);
        BotPlayer  bot     = new BotPlayer(name, balance, maxPages);
        BotHandler handler = new BotHandler(connId);

        Player joined = room.joinBot(connId, bot, handler);
        if (joined == null) return null;

        bots.put(connId, bot);
        nameToConn.put(key, connId);

        System.out.printf("[BOT] Added: name=%-10s balance=%,d maxPages=%d%n",
                name, balance, maxPages);

        // Mua giấy ngay nếu phòng đang WAITING/VOTING
        scheduleBotBuy(connId, bot);
        return bot;
    }

    /**
     * Removes a bot by name. Kicks it from the room (no refund needed — balance stays).
     * @return true if bot was found and removed
     */
    public synchronized boolean removeBot(String name) {
        String connId = nameToConn.remove(name.toLowerCase().trim());
        if (connId == null) return false;
        BotPlayer bot = bots.remove(connId);
        if (bot == null) return false;
        room.removeBot(connId, bot);
        System.out.printf("[BOT] Removed: %s%n", name);
        return true;
    }

    /** Returns an unmodifiable snapshot of all current bots. */
    public synchronized List<BotPlayer> listBots() {
        return new ArrayList<>(bots.values());
    }

    public synchronized boolean hasBot(String name) {
        return nameToConn.containsKey(name.toLowerCase().trim());
    }

    // ─── Game event hooks (called by GameRoom) ────────────────────

    /** Called when game starts — bots already bought pages during WAITING. No-op. */
    public void onGameStarted() { /* pages bought during WAITING/VOTING already */ }

    /**
     * Called every time a number is drawn. Each bot checks all its pages.
     * If any page has a winning row, the bot claims win after a short random delay
     * (50–400ms) to feel more natural.
     */
    public void onNumberDrawn(List<Integer> drawnNumbers) {
        List<Map.Entry<String, BotPlayer>> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(bots.entrySet()); }

        for (Map.Entry<String, BotPlayer> entry : snapshot) {
            String    connId = entry.getKey();
            BotPlayer bot    = entry.getValue();

            for (LotoPage page : bot.getPages()) {
                if (page.hasWinningRow(drawnNumbers)) {
                    int delayMs = 50 + rng.nextInt(350);
                    int pageId  = page.getId();
                    executor.schedule(() -> {
                        if (room.getState() == GameState.PLAYING
                                || room.getState() == GameState.ENDED) {
                            room.claimWinBot(connId, pageId);
                        }
                    }, delayMs, TimeUnit.MILLISECONDS);
                    break; // claim only the first winning page per draw
                }
            }
        }
    }

    /** Called when the room resets — bots re-buy pages for next game. */
    public synchronized void onRoomReset() {
        System.out.printf("[BOT] %d bot(s) đang mua giấy cho ván mới...%n", bots.size());
        List<Map.Entry<String, BotPlayer>> snapshot = new ArrayList<>(bots.entrySet());
        for (Map.Entry<String, BotPlayer> entry : snapshot) {
            scheduleBotBuy(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Schedules a bot to buy a random number of pages (1..maxPages) after a short delay.
     * Only executes if room is still in WAITING or VOTING state.
     */
    private void scheduleBotBuy(String connId, BotPlayer bot) {
        int pagesToBuy = 1 + rng.nextInt(bot.getMaxPages());
        int delayMs    = 300 + rng.nextInt(700); // 0.3–1s sau khi join
        executor.schedule(() -> {
            GameState st = room.getState();
            if (st == GameState.WAITING || st == GameState.VOTING) {
                room.buyPagesBot(connId, pagesToBuy);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
