package com.loto.callback;

import com.loto.model.Player;
import com.loto.model.LotoPage;
import com.loto.model.Transaction;

import java.util.List;

/**
 * Implement this interface in your application (PC or Android) to receive
 * game events from the server.
 *
 * All callbacks are invoked on the server's internal thread pool.
 * If you update UI from these callbacks (Android), use runOnUiThread() / Handler.
 */
public interface LotoServerCallback {

    // ── Player lifecycle ──────────────────────────────────────────

    /** A new player has joined the room. */
    void onPlayerJoined(Player player);

    /** A player disconnected. {@code permanent} = true if timeout expired. */
    void onPlayerLeft(Player player, boolean permanent);

    /** A player reconnected after dropping. */
    void onPlayerReconnected(Player player);

    // ── Pages ─────────────────────────────────────────────────────

    /** A player bought pages (balance already deducted). */
    void onPagesBought(Player player, List<LotoPage> newPages);

    /** A player tried to buy pages but had insufficient balance. */
    void onInsufficientBalance(Player player, long required, long actual);

    // ── Voting & game flow ────────────────────────────────────────

    /** Vote count changed. {@code needed} = how many votes are required to start. */
    void onVoteUpdate(int voteCount, int needed);

    /** All votes reached threshold — game is about to start. */
    void onGameStarting();

    /** A number was drawn. */
    void onNumberDrawn(int number, List<Integer> drawnSoFar);

    /**
     * A player claims they have won with the given pageId.
     * Call {@link com.loto.core.GameRoom#confirmWin} or
     * {@link com.loto.core.GameRoom#rejectWin} from the host side.
     */
    void onClaimReceived(Player player, int pageId);

    /** Host confirmed a win — jackpot paid out. */
    void onWinConfirmed(Player winner, int pageId, long jackpot);

    /** Host rejected a claim — game continues. */
    void onWinRejected(Player player, int pageId);

    /** Game ended (after win confirmed). */
    void onGameEnded(Player winner, long jackpot);

    // ── Money ─────────────────────────────────────────────────────

    /** Host topped up a player's balance. */
    void onTopUp(Player player, long amount);

    /** Game was cancelled by host — refunds issued. */
    void onGameCancelled(String reason, long totalRefunded);

    /** A player was kicked by host. */
    void onPlayerKicked(Player player, String reason);

    /** A player was banned by host. */
    void onPlayerBanned(String playerId, String reason);

    /** A player was unbanned. */
    void onPlayerUnbanned(String playerId);

    // ── Errors ────────────────────────────────────────────────────

    /** A generic server error occurred. */
    void onServerError(String message);

    /** Server ended the game programmatically (no winner, no prize). */
    void onServerGameEnded(String reason);

    /** Game was paused — draw timer stopped. */
    void onGamePaused();

    /** Game was resumed after a pause — draw timer restarted. */
    void onGameResumed();

    /** Room was reset to WAITING — new game can begin. */
    void onRoomReset();

    /**
     * Jackpot was split and paid out to all confirmed winners at reset time.
     * @param winnerIds list of playerId that received prizes
     * @param prizeEach amount each winner received (jackpot / count)
     */
    void onJackpotPaid(java.util.List<String> winnerIds, long prizeEach);

    /**
     * Draw interval was changed live (via {@code setDrawInterval()} or SET_DRAW_INTERVAL message).
     * @param oldMs previous interval in ms
     * @param newMs new interval in ms
     */
    void onDrawIntervalChanged(int oldMs, int newMs);

    /**
     * Price per page was changed by host (via {@code setPricePerPage()} or SET_PRICE_PER_PAGE message).
     * Only fires when game is in WAITING state.
     * @param oldPrice previous price
     * @param newPrice new price
     */
    void onPricePerPageChanged(long oldPrice, long newPrice);

    /**
     * Auto-reset delay was changed at runtime via {@code setAutoResetDelay()}.
     * @param oldMs previous delay (0 = was disabled)
     * @param newMs new delay (0 = now disabled)
     */
    void onAutoResetDelayChanged(int oldMs, int newMs);

    /**
     * An auto-reset has been scheduled.
     * @param delayMs milliseconds until the room resets automatically
     */
    void onAutoResetScheduled(int delayMs);
}
