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

    /** Joined room fresh. {@code isHost=true} if you are the first player. */
    void onJoined(String playerId, String token, boolean isHost);

    void onDisconnected(boolean willRetry);

    /**
     * Reconnected to an active session.
     * State is fully restored before this fires — pages are marked and drawnNumbers
     * is populated, so the UI can re-render immediately.
     *
     * @param gameState    current server game state ("WAITING", "PLAYING", "ENDED", etc.)
     * @param players      current room snapshot (empty if server didn't include it)
     * @param drawnNumbers all numbers drawn so far (empty if game not started)
     */
    void onReconnected(String gameState, List<RoomPlayer> players, List<Integer> drawnNumbers);

    // ── Room ──────────────────────────────────────────────────────

    /** Full room snapshot (players + state) — fires on any change. */
    void onRoomUpdate(List<RoomPlayer> players, String gameState);

    void onPlayerJoined(String playerId, String name, boolean isHost);

    void onPlayerLeft(String playerId);

    // ── Pages ─────────────────────────────────────────────────────

    void onPagesAssigned(List<ClientPage> newPages);

    void onInsufficientBalance(long required, long actual);

    // ── Admin ─────────────────────────────────────────────────────

    /**
     * Admin authentication succeeded.
     * After this fires, admin-only commands will be accepted by the server.
     */
    void onAdminAuthOk();

    /**
     * Response to {@code getBanList()} — danh sách tên và IP đang bị cấm.
     * @param bannedNames tên player bị ban
     * @param bannedIps   địa chỉ IP bị ban
     */
    void onBanList(List<String> bannedNames, List<String> bannedIps);

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
     * Host changed price per page.
     * @param newPrice new price in đồng
     */
    void onPricePerPageChanged(long newPrice);

    /**
     * Auto-reset was scheduled or cancelled.
     * @param delayMs ms until reset (0 = cancelled / disabled)
     */
    void onAutoResetScheduled(int delayMs);

    /**
     * Auto-start was scheduled or cancelled.
     * @param delayMs ms until game auto-starts (0 = cancelled / disabled)
     */
    void onAutoStartScheduled(int delayMs);

    /** Game was paused by host/admin. */
    void onGamePaused();

    /** Game was resumed by host/admin. */
    void onGameResumed();

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
     * Win confirmed. Prize is held until host resets.
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
     * Server ended the game without a winner (e.g. all 90 numbers drawn).
     * @param reason description from server
     */
    void onGameEndedByServer(String reason);

    /**
     * You were kicked from the room. Connection is closed after this.
     * @param reason kick reason from host
     */
    void onKicked(String reason);

    /**
     * You were banned from the room. Connection is closed after this.
     * @param reason ban reason
     */
    void onBanned(String reason);

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
