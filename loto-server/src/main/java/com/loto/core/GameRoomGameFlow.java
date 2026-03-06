package com.loto.core;

import com.loto.model.Player;
import com.loto.protocol.OutboundMsg;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages all game flow: voting, start, draw loop, win claims,
 * pause/resume, cancel, reset, auto-reset, and <b>auto-start</b>.
 */
public class GameRoomGameFlow {

    private final GameRoomState       s;
    private final GameRoomBroadcaster bc;
    private final GameRoomPersistence persist;

    GameRoomGameFlow(GameRoomState s, GameRoomBroadcaster bc, GameRoomPersistence persist) {
        this.s       = s;
        this.bc      = bc;
        this.persist = persist;
    }

    // ─── Auto-start ───────────────────────────────────────────────

    /**
     * Checks whether the auto-start countdown should start, reset, or cancel.
     * Called after every buyPages and disconnect event.
     *
     * Trigger condition: autoStartMs > 0 AND game is WAITING/VOTING
     * AND at least 1 real (non-bot) player has bought at least 1 page.
     * Countdown cancels if that condition drops to false (e.g. player kicked before game).
     */
    public synchronized void checkAutoStart() {
        if (s.currentAutoStartMs <= 0) return;
        if (s.state == GameState.PLAYING || s.state == GameState.ENDED || s.state == GameState.PAUSED) return;

        boolean hasRealBuyer = s.playersByToken.values().stream()
                .anyMatch(p -> !p.isBot() && p.isConnected() && !p.getPages().isEmpty());

        if (hasRealBuyer) {
            if (s.autoStartTask == null || s.autoStartTask.isDone()) {
                scheduleAutoStart(s.currentAutoStartMs);
            }
        } else {
            cancelAutoStart();
        }
    }

    private void scheduleAutoStart(int delayMs) {
        cancelAutoStart();
        bc.broadcast(OutboundMsg.autoStartScheduled(delayMs).toJson(), null);
        if (s.callback != null) s.callback.onAutoStartScheduled(delayMs);
        System.out.printf("[GameRoom] Auto-start em %d ms...%n", delayMs);

        s.autoStartTask = s.scheduler.schedule(() -> {
            synchronized (GameRoomGameFlow.this) {
                if (s.state == GameState.WAITING || s.state == GameState.VOTING) {
                    boolean hasRealBuyer = s.playersByToken.values().stream()
                            .anyMatch(p -> !p.isBot() && p.isConnected() && !p.getPages().isEmpty());
                    if (hasRealBuyer) {
                        startGame();
                    }
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Cancels the auto-start countdown (e.g. player left, room drops below minPlayers). */
    public synchronized void cancelAutoStart() {
        if (s.autoStartTask != null && !s.autoStartTask.isDone()) {
            s.autoStartTask.cancel(false);
        }
        s.autoStartTask = null;
        bc.broadcast(OutboundMsg.autoStartScheduled(0).toJson(), null);
        if (s.callback != null) s.callback.onAutoStartScheduled(0);
        System.out.println("[GameRoom] Auto-start cancelled.");
    }

    /**
     * Updates the auto-start delay at runtime.
     * If game is WAITING with enough players, cancels old timer and starts a new one.
     */
    public synchronized void setAutoStartMs(int ms) {
        if (ms < 0) ms = 0;
        int old = s.currentAutoStartMs;
        s.currentAutoStartMs = ms;
        persist.saveState();
        if (s.callback != null) s.callback.onAutoStartMsChanged(old, ms);

        if (ms == 0) {
            cancelAutoStart();
        } else if (s.state == GameState.WAITING || s.state == GameState.VOTING) {
            // Re-evaluate with new delay
            if (s.realPlayerCount() >= s.config.minPlayers) scheduleAutoStart(ms);
        }
    }

    public int getCurrentAutoStartMs() { return s.currentAutoStartMs; }

    // ─── Voting ───────────────────────────────────────────────────

    public synchronized void voteStart(String connId) {
        Player player = s.playersByConnId.get(connId);
        if (player == null || s.state == GameState.PLAYING || s.state == GameState.ENDED) return;
        if (player.isBot()) return;

        s.state = GameState.VOTING;
        s.votedPlayerIds.add(player.getId());

        int  needed    = s.voteThreshold();
        long realVotes = s.votedPlayerIds.stream()
                .map(s::findPlayerById)
                .filter(p -> p != null && !p.isBot())
                .count();

        bc.broadcast(OutboundMsg.voteUpdate((int) realVotes, needed).toJson(), null);
        if (s.callback != null) s.callback.onVoteUpdate((int) realVotes, needed);

        if (realVotes >= needed) startGame();
    }

    // ─── Game start ───────────────────────────────────────────────

    synchronized void startGame() {
        if (s.state == GameState.PLAYING) return;
        s.state = GameState.PLAYING;
        cancelAutoStart();   // no longer needed

        bc.broadcast(OutboundMsg.gameStarting(s.currentDrawIntervalMs).toJson(), null);
        bc.broadcastRoomUpdate();
        if (s.callback != null) s.callback.onGameStarting();

        BotManager bm = s.botManagerRef;
        if (bm != null) bm.onGameStarted();

        s.drawTask = s.scheduler.scheduleAtFixedRate(
                this::drawNextNumber,
                s.currentDrawIntervalMs,
                s.currentDrawIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    // ─── Draw loop ────────────────────────────────────────────────

    private synchronized void drawNextNumber() {
        if (s.state != GameState.PLAYING) { stopDrawing(); return; }
        if (s.numberPool.isEmpty()) {
            stopDrawing();
            s.state = GameState.ENDED;
            bc.broadcast(OutboundMsg.gameEndedByServer("Hết 90 số — không có người thắng").toJson(), null);
            bc.broadcastRoomUpdate();
            persist.saveStateNow();
            if (s.callback != null) s.callback.onServerGameEnded("Hết 90 số — không có người thắng");
            if (s.currentAutoResetDelayMs > 0) scheduleAutoReset(s.currentAutoResetDelayMs);
            return;
        }
        int number = s.numberPool.remove(s.numberPool.size() - 1);
        s.drawnNumbers.add(number);

        bc.broadcast(OutboundMsg.numberDrawn(number, new ArrayList<>(s.drawnNumbers)).toJson(), null);
        if (s.callback != null) s.callback.onNumberDrawn(number, new ArrayList<>(s.drawnNumbers));

        BotManager bm = s.botManagerRef;
        if (bm != null) bm.onNumberDrawn(new ArrayList<>(s.drawnNumbers));
    }

    // ─── Win claim ────────────────────────────────────────────────

    public synchronized void claimWin(String connId, int pageId) {
        Player player = s.playersByConnId.get(connId);
        if (player == null) return;
        if (s.state != GameState.PLAYING && s.state != GameState.ENDED && s.state != GameState.PAUSED) return;

        if (s.winnerIds.contains(player.getId())) {
            bc.sendTo(connId, OutboundMsg.error("ALREADY_CLAIMED", "Bạn đã được xác nhận thắng rồi").toJson());
            return;
        }

        var page = player.getPageById(pageId);
        if (page == null) {
            bc.sendTo(connId, OutboundMsg.error("INVALID_PAGE", "Page not found").toJson());
            return;
        }

        bc.broadcast(OutboundMsg.claimReceived(player.getId(), player.getName(), pageId).toJson(), null);
        if (s.callback != null) s.callback.onClaimReceived(player, pageId);

        if (s.config.autoVerifyWin) {
            if (page.hasWinningRow(new ArrayList<>(s.drawnNumbers))) confirmWin(player.getId(), pageId);
            else rejectWin(player.getId(), pageId);
        }
    }

    public synchronized void confirmWin(String playerId, int pageId) {
        Player player = s.findPlayerById(playerId);
        if (player == null) return;
        if (s.state != GameState.PLAYING && s.state != GameState.PAUSED && s.state != GameState.ENDED) return;

        if (s.state == GameState.PLAYING || s.state == GameState.PAUSED) {
            stopDrawing();
            s.state = GameState.ENDED;
        }

        if (!s.winnerIds.contains(playerId)) s.winnerIds.add(playerId);

        bc.broadcast(OutboundMsg.winConfirmed(player.getId(), player.getName(), pageId).toJson(), null);
        if (s.winnerIds.size() == 1) bc.broadcast(OutboundMsg.gameEnded(player.getId(), player.getName()).toJson(), null);
        bc.broadcastRoomUpdate();

        if (s.callback != null) {
            s.callback.onWinConfirmed(player, pageId, 0);
            if (s.winnerIds.size() == 1) s.callback.onGameEnded(player, 0);
        }
        persist.saveStateNow();

        if (s.winnerIds.size() == 1 && s.currentAutoResetDelayMs > 0) scheduleAutoReset(s.currentAutoResetDelayMs);
    }

    public synchronized void rejectWin(String playerId, int pageId) {
        Player player = s.findPlayerById(playerId);
        if (player == null) return;
        bc.broadcast(OutboundMsg.winRejected(player.getId(), pageId).toJson(), null);
        if (s.callback != null) s.callback.onWinRejected(player, pageId);
    }

    // ─── Pause / Resume ───────────────────────────────────────────

    public synchronized void pauseGame() {
        if (s.state != GameState.PLAYING) return;
        stopDrawing();
        s.state = GameState.PAUSED;
        bc.broadcast(OutboundMsg.gamePaused().toJson(), null);
        bc.broadcastRoomUpdate();
        persist.saveState();
        if (s.callback != null) s.callback.onGamePaused();
        System.out.println("[GameRoom] Game PAUSED — " + s.drawnNumbers.size() + " numbers drawn so far.");
    }

    public synchronized void resumeGame() {
        if (s.state != GameState.PAUSED) return;
        s.state = GameState.PLAYING;
        s.drawTask = s.scheduler.scheduleAtFixedRate(
                this::drawNextNumber,
                s.currentDrawIntervalMs, s.currentDrawIntervalMs, TimeUnit.MILLISECONDS);
        bc.broadcast(OutboundMsg.gameResumed(s.currentDrawIntervalMs).toJson(), null);
        bc.broadcastRoomUpdate();
        persist.saveState();
        if (s.callback != null) s.callback.onGameResumed();
        System.out.println("[GameRoom] Game RESUMED.");
    }

    // ─── Server-controlled start/end/cancel ──────────────────────

    public synchronized void serverStart() {
        if (s.state == GameState.ENDED) {
            System.err.println("[GameRoom] serverStart() ignored — game ENDED. Call reset() first.");
            return;
        }
        if (s.state == GameState.PLAYING) return;
        startGame();
    }

    public synchronized void serverEnd(String reason) {
        if (s.state != GameState.PLAYING && s.state != GameState.PAUSED) return;
        stopDrawing();
        s.state = GameState.ENDED;
        String msg = reason != null ? reason : "Server kết thúc game";
        bc.broadcast(OutboundMsg.gameEndedByServer(msg).toJson(), null);
        bc.broadcastRoomUpdate();
        if (s.callback != null) s.callback.onServerGameEnded(msg);
        if (s.currentAutoResetDelayMs > 0) scheduleAutoReset(s.currentAutoResetDelayMs);
    }

    public synchronized void cancelGame(String reason) {
        if (s.state == GameState.ENDED) return;
        stopDrawing();
        s.state = GameState.ENDED;

        long totalRefunded = 0;
        for (Player player : s.playersByToken.values()) {
            int  pages     = player.getPages().size();
            long refundAmt = pages * s.currentPricePerPage;
            if (refundAmt > 0) {
                player.refund(refundAmt, String.format("Hoàn tiền — game hủy (%d tờ × %d)", pages, s.currentPricePerPage));
                totalRefunded += refundAmt;
                String connId = s.getConnIdForPlayer(player);
                if (connId != null) bc.sendBalanceUpdate(connId, player);
            }
        }
        s.jackpot = 0;
        s.pendingPricePerPage = -1; // discard pending price, game was cancelled
        bc.broadcastRoomUpdate();

        if (s.callback != null) s.callback.onGameCancelled(reason, totalRefunded);
        persist.saveStateNow();
        // Không auto-reset khi hủy thủ công — admin tự quyết định reset lúc nào
    }

    // ─── Reset ────────────────────────────────────────────────────

    public synchronized void reset() {
        stopDrawing();
        cancelAutoReset();

        long prizeEach   = 0;
        int  winnerCount = s.winnerIds.size();
        if (winnerCount > 0 && s.jackpot > 0) {
            prizeEach = s.jackpot / winnerCount;
            for (String wid : s.winnerIds) {
                Player w = s.findPlayerById(wid);
                if (w == null) continue;
                w.addPrize(prizeEach, String.format("Jackpot chia %d người: %,d", winnerCount, prizeEach));
                String wConn = s.getConnIdForPlayer(w);
                if (wConn != null) bc.sendBalanceUpdate(wConn, w);
            }
            if (s.callback != null) s.callback.onJackpotPaid(new ArrayList<>(s.winnerIds), prizeEach);
        }

        s.state = GameState.WAITING;
        s.drawnNumbers.clear();
        s.buildNumberPool();
        s.jackpot = 0;
        s.votedPlayerIds.clear();
        s.pageIdCounter.set(1);
        s.winnerIds.clear();

        // Áp dụng giá pending (được set trong lúc đang chơi) cho ván mới
        if (s.pendingPricePerPage >= 0) {
            s.currentPricePerPage = s.pendingPricePerPage;
            s.pendingPricePerPage = -1;
        }

        for (Player p : s.playersByToken.values()) p.clearPages();

        bc.broadcast(OutboundMsg.roomReset(prizeEach, winnerCount).toJson(), null);
        bc.broadcastRoomUpdate();
        persist.saveStateNow();

        if (s.callback != null) s.callback.onRoomReset();
        BotManager bm = s.botManagerRef;
        if (bm != null) bm.onRoomReset();
    }

    // ─── Draw speed ───────────────────────────────────────────────

    public synchronized void setDrawInterval(int intervalMs) {
        if (intervalMs < 200) intervalMs = 200;
        int old = s.currentDrawIntervalMs;
        s.currentDrawIntervalMs = intervalMs;
        if (s.state == GameState.PLAYING) {
            stopDrawing();
            s.drawTask = s.scheduler.scheduleAtFixedRate(
                    this::drawNextNumber, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
        bc.broadcast(OutboundMsg.drawIntervalChanged(intervalMs).toJson(), null);
        persist.saveState();
        if (s.callback != null) s.callback.onDrawIntervalChanged(old, intervalMs);
    }

    // ─── Price per page ───────────────────────────────────────────

    public synchronized void setPricePerPage(long newPrice) {
        if (newPrice < 0) newPrice = 0;
        long old = s.currentPricePerPage;

        // Nếu đã có người thật mua tờ (bất kể state) → lưu pending, áp dụng ván sau
        boolean realPlayerBought = s.playersByToken.values().stream()
                .anyMatch(p -> !p.isBot() && p.isConnected() && !p.getPages().isEmpty());

        if (realPlayerBought || s.state == GameState.PLAYING || s.state == GameState.PAUSED) {
            s.pendingPricePerPage = newPrice;
            bc.broadcast(OutboundMsg.pricePerPageChanged(newPrice).toJson(), null);
            persist.saveState();
            if (s.callback != null) s.callback.onPricePerPageChanged(old, newPrice);
            return;
        }

        // Chưa ai mua + không đang chơi → áp dụng ngay
        s.currentPricePerPage = newPrice;
        s.pendingPricePerPage = -1;
        bc.broadcast(OutboundMsg.pricePerPageChanged(newPrice).toJson(), null);
        persist.saveState();
        if (s.callback != null) s.callback.onPricePerPageChanged(old, newPrice);
    }

    public boolean canChangePricePerPage() {
        return true; // luôn cho phép — pending nếu đã có người mua
    }

    // ─── Auto-reset ───────────────────────────────────────────────

    public synchronized void setAutoResetDelay(int delayMs) {
        if (delayMs < 0) delayMs = 0;
        int old = s.currentAutoResetDelayMs;
        s.currentAutoResetDelayMs = delayMs;
        persist.saveState();
        if (s.callback != null) s.callback.onAutoResetDelayChanged(old, delayMs);

        if (delayMs == 0) {
            cancelAutoReset();
            bc.broadcast(OutboundMsg.autoResetScheduled(0).toJson(), null);
        } else if (s.state == GameState.ENDED) {
            scheduleAutoReset(delayMs);
        } else {
            bc.broadcast(OutboundMsg.autoResetScheduled(delayMs).toJson(), null);
        }
    }

    public synchronized void scheduleAutoReset(int delayMs) {
        cancelAutoReset();
        bc.broadcast(OutboundMsg.autoResetScheduled(delayMs).toJson(), null);
        if (s.callback != null) s.callback.onAutoResetScheduled(delayMs);
        if (delayMs <= 0) { reset(); return; }
        s.autoResetTask = s.scheduler.schedule(() -> {
            synchronized (GameRoomGameFlow.this) { reset(); }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void cancelAutoReset() {
        if (s.autoResetTask != null && !s.autoResetTask.isDone()) {
            s.autoResetTask.cancel(false);
        }
        s.autoResetTask = null;
        bc.broadcast(OutboundMsg.autoResetScheduled(0).toJson(), null);
        if (s.callback != null) s.callback.onAutoResetScheduled(0);
    }

    // ─── Internal helpers ─────────────────────────────────────────

    void stopDrawing() {
        if (s.drawTask != null && !s.drawTask.isDone()) s.drawTask.cancel(false);
    }
}
