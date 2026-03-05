package com.loto.client.callback;

import com.loto.client.model.ClientPage;
import com.loto.client.model.RoomPlayer;
import com.loto.client.model.WalletInfo;

import java.util.List;

/**
 * Implement in your app (PC or Android) to receive client-side game events.
 * All callbacks fire on the client's reader thread — marshal to UI thread as needed.
 */
public interface LotoClientCallback {

    // ── Connection ────────────────────────────────────────────────

    /** TCP connection established, before JOIN is sent. */
    void onConnected();

    /** Successfully joined the room. {@code isHost} = true if first player. */
    void onJoined(String playerId, String token, boolean isHost);

    /** Disconnected from server. Client will auto-reconnect if token is available. */
    void onDisconnected(boolean willRetry);

    /** Successfully reconnected and state restored. */
    void onReconnected();

    // ── Room ──────────────────────────────────────────────────────

    /** Full room snapshot (players list + game state) — fired on any change. */
    void onRoomUpdate(List<RoomPlayer> players, String gameState);

    /** Another player joined. */
    void onPlayerJoined(String playerId, String name, boolean isHost);

    /** A player left or disconnected. */
    void onPlayerLeft(String playerId);

    // ── Pages ─────────────────────────────────────────────────────

    /** Server assigned new pages after BUY_PAGE. */
    void onPagesAssigned(List<ClientPage> newPages);

    /** Not enough balance to buy pages. */
    void onInsufficientBalance(long required, long actual);

    // ── Game flow ─────────────────────────────────────────────────

    /** Vote count updated. */
    void onVoteUpdate(int current, int needed);

    /** Game is starting — draw loop begins. */
    void onGameStarting(int drawIntervalMs);

    /**
     * A number was drawn by server.
     *
     * @param number      the number just drawn
     * @param drawnSoFar  all numbers drawn so far
     * @param markedPages pages that had this number marked (hit)
     * @param wonPages    pages that just completed a row (kình!)
     */
    void onNumberDrawn(int number, List<Integer> drawnSoFar,
                       List<ClientPage> markedPages,
                       List<ClientPage> wonPages);

    /**
     * One of this client's pages just completed a row — server hasn't been told yet.
     * Client should call {@link com.loto.client.core.LotoClient#claimWin(int)} to report it,
     * or the app can auto-claim (see {@code autoClaimOnWin} config).
     */
    void onPageWon(ClientPage page, int rowIndex);

    /** Server broadcast that someone claimed a win. */
    void onClaimReceived(String playerId, String playerName, int pageId);

    /** Win was confirmed by host — game over. */
    void onWinConfirmed(String playerId, String playerName, int pageId);

    /** Win was rejected by host — game continues. */
    void onWinRejected(String playerId, int pageId);

    /** Game ended. */
    void onGameEnded(String winnerPlayerId, String winnerName);

    /** Game was cancelled by host — refunds issued. */
    void onGameCancelled(String reason);

    // ── Wallet ────────────────────────────────────────────────────

    /** Balance changed (buy page, win, top-up, refund). */
    void onBalanceUpdate(WalletInfo wallet);

    /** Full transaction history received. */
    void onWalletHistory(WalletInfo wallet);

    // ── Errors ────────────────────────────────────────────────────

    void onError(String code, String message);
}
