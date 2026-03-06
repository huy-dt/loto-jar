package com.loto.client.callback;

import com.loto.client.model.ClientPage;
import com.loto.client.model.RoomPlayer;
import com.loto.client.model.WalletInfo;

import java.util.List;

/**
 * Implement in your app (PC or Android) to receive client-side game events.
 *
 * <p><b>Threading:</b> All callbacks fire on the network reader thread.
 * On Android, always marshal to UI thread:
 * <pre>
 *   runOnUiThread(() -> { ... });
 * </pre>
 */
public interface LotoClientCallback {

    // ── Connection ────────────────────────────────────────────────

    void onConnected();

    /** Joined room. {@code isHost=true} if you are the first player. */
    void onJoined(String playerId, String token, boolean isHost);

    void onDisconnected(boolean willRetry);

    void onReconnected();

    // ── Room ──────────────────────────────────────────────────────

    /** Full room snapshot (players + state) — fires on any change. */
    void onRoomUpdate(List<RoomPlayer> players, String gameState);

    void onPlayerJoined(String playerId, String name, boolean isHost);

    void onPlayerLeft(String playerId);

    // ── Pages ─────────────────────────────────────────────────────

    void onPagesAssigned(List<ClientPage> newPages);

    void onInsufficientBalance(long required, long actual);

    // ── Game flow ─────────────────────────────────────────────────

    void onVoteUpdate(int current, int needed);

    /** Game starting — draw loop begins with given interval. */
    void onGameStarting(int drawIntervalMs);

    /**
     * Server changed draw speed mid-game.
     * @param intervalMs new interval in milliseconds
     */
    void onDrawIntervalChanged(int intervalMs);

    /**
     * A number was drawn.
     * @param number      number just drawn
     * @param drawnSoFar  all numbers drawn so far
     * @param markedPages your pages that had this number (hit)
     * @param wonPages    your pages that just completed a row (kình!)
     */
    void onNumberDrawn(int number, List<Integer> drawnSoFar,
                       List<ClientPage> markedPages, List<ClientPage> wonPages);

    /**
     * One of your pages just completed a row — server hasn't been told yet.
     * Call {@code client.claimWin(page.getId())} or use {@code autoClaimOnWin}.
     */
    void onPageWon(ClientPage page, int rowIndex);

    /** Someone broadcast a win claim. */
    void onClaimReceived(String playerId, String playerName, int pageId);

    /**
     * Win confirmed. Note: prize is NOT paid yet — it's held until host resets.
     * Multiple winners can be confirmed before payout.
     */
    void onWinConfirmed(String playerId, String playerName, int pageId);

    /** Win rejected — game continues. */
    void onWinRejected(String playerId, int pageId);

    /**
     * First winner confirmed — draw stopped.
     * Others can still claim until reset().
     */
    void onGameEnded(String winnerPlayerId, String winnerName);

    void onGameCancelled(String reason);

    /**
     * Room was reset to WAITING. Jackpot has been split and paid to winners.
     * @param prizeEach   amount each winner received (0 if no winners)
     * @param winnerCount number of winners that were paid
     */
    void onRoomReset(long prizeEach, int winnerCount);

    // ── Wallet ────────────────────────────────────────────────────

    void onBalanceUpdate(WalletInfo wallet);

    void onWalletHistory(WalletInfo wallet);

    // ── Errors ────────────────────────────────────────────────────

    void onError(String code, String message);
}
