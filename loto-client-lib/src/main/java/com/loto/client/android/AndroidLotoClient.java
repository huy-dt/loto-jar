package com.loto.client.android;

import com.loto.client.callback.LotoClientCallback;
import com.loto.client.core.LotoClient;
import com.loto.client.model.ClientPage;
import com.loto.client.model.RoomPlayer;
import com.loto.client.model.WalletInfo;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android-friendly wrapper for {@link LotoClient}.
 *
 * <p>Callbacks are dispatched via a {@link UiDispatcher} — inject whichever
 * mechanism your platform uses to post to the UI thread:
 *
 * <h3>Android (Activity / Fragment)</h3>
 * <pre>
 * AndroidLotoClient client = new AndroidLotoClient(
 *     new LotoClient.Builder()
 *         .wsUrl("ws://192.168.1.10:9001")
 *         .playerName("Nam"),
 *     runnable -> runOnUiThread(runnable),   // UiDispatcher
 *     myCallback
 * );
 * client.connect();    // non-blocking, starts background thread
 *
 * // onDestroy():
 * client.disconnect();
 * </pre>
 *
 * <h3>Non-Android (PC / headless test)</h3>
 * <pre>
 * AndroidLotoClient client = new AndroidLotoClient(
 *     builder,
 *     Runnable::run,   // inline dispatcher
 *     myCallback
 * );
 * </pre>
 *
 * <h3>Gradle dependency</h3>
 * <pre>
 *   implementation 'org.java-websocket:Java-WebSocket:1.5.4'
 *   implementation 'org.json:json:20231013'
 * </pre>
 */
public class AndroidLotoClient {

    /**
     * Functional interface for posting a {@link Runnable} to the UI / main thread.
     *
     * <p>On Android: {@code runnable -> activity.runOnUiThread(runnable)}
     * <br>On PC / test: {@code Runnable::run}
     */
    public interface UiDispatcher {
        void post(Runnable runnable);
    }

    // ── Fields ────────────────────────────────────────────────────

    private final LotoClient      client;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "loto-client-net");
        t.setDaemon(true);
        return t;
    });

    // ── Constructor ───────────────────────────────────────────────

    /**
     * @param builder      {@link LotoClient.Builder} — do NOT call {@code .callback()} on it,
     *                     the wrapper sets the callback internally.
     * @param uiDispatcher posts runnables to the UI thread (see class javadoc)
     * @param uiCallback   your callback — all methods will be called on the UI thread
     */
    public AndroidLotoClient(LotoClient.Builder builder,
                             UiDispatcher uiDispatcher,
                             LotoClientCallback uiCallback) {
        this.client = builder
                .callback(new DispatchingCallback(uiCallback, uiDispatcher))
                .build();
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    /** Connects in background. Returns immediately. Safe to call from onCreate(). */
    public void connect() {
        executor.submit(client::connect);
    }

    /** Disconnects and shuts down the background thread. Call from onDestroy(). */
    public void disconnect() {
        client.disconnect();
        executor.shutdownNow();
    }

    // ── Delegate actions ──────────────────────────────────────────

    public void buyPages(int count)                              { client.buyPages(count); }
    public void voteStart()                                      { client.voteStart(); }
    public void claimWin(int pageId)                             { client.claimWin(pageId); }
    public void requestWalletHistory()                           { client.requestWalletHistory(); }

    /** Authenticate as admin. Server responds with onAdminAuthOk() on success. */
    public void adminAuth(String adminToken)                     { client.adminAuth(adminToken); }

    public void confirmWin(String playerId, int pageId)          { client.confirmWin(playerId, pageId); }
    public void rejectWin(String playerId, int pageId)           { client.rejectWin(playerId, pageId); }
    public void topUp(String playerId, long amount, String note) { client.topUp(playerId, amount, note); }
    public void cancelGame(String reason)                        { client.cancelGame(reason); }
    public void kick(String playerId, String reason)             { client.kick(playerId, reason); }
    public void ban(String playerId, String reason)              { client.ban(playerId, reason); }
    public void unban(String name)                               { client.unban(name); }
    public void pauseGame()                                      { client.pauseGame(); }
    public void resumeGame()                                     { client.resumeGame(); }
    public void setDrawInterval(int ms)                          { client.setDrawInterval(ms); }
    public void setPricePerPage(long price)                      { client.setPricePerPage(price); }
    public void setAutoReset(int delayMs)                        { client.setAutoReset(delayMs); }
    public void setAutoStart(int delayMs)                        { client.setAutoStart(delayMs); }
    public void serverStart()                                    { client.serverStart(); }
    public void serverEnd(String reason)                         { client.serverEnd(reason); }
    public void resetRoom()                                      { client.resetRoom(); }
    public void banIp(String ip)                                 { client.banIp(ip); }
    public void unbanIp(String ip)                               { client.unbanIp(ip); }
    public void getBanList()                                     { client.getBanList(); }

    // ── State accessors ───────────────────────────────────────────

    public com.loto.client.core.ClientState getState()           { return client.getState(); }
    public String           getPlayerId()                        { return client.getPlayerId(); }
    public String           getToken()                           { return client.getToken(); }
    public String           getPlayerName()                      { return client.getPlayerName(); }
    public boolean          isHost()                             { return client.isHost(); }
    public boolean          isAdmin()                            { return client.isAdmin(); }
    public boolean          isPaused()                           { return client.isPaused(); }
    public String           getCurrentGameState()                { return client.getCurrentGameState(); }
    public int              getVoteCount()                       { return client.getVoteCount(); }
    public int              getVoteNeeded()                      { return client.getVoteNeeded(); }
    public int              getCurrentDrawIntervalMs()           { return client.getCurrentDrawIntervalMs(); }
    public long             getCurrentPricePerPage()             { return client.getCurrentPricePerPage(); }
    public long             getPendingPricePerPage()             { return client.getPendingPricePerPage(); }
    public int              getCurrentAutoResetDelayMs()         { return client.getCurrentAutoResetDelayMs(); }
    public int              getCurrentAutoStartDelayMs()         { return client.getCurrentAutoStartDelayMs(); }
    public List<ClientPage> getPages()                           { return client.getPages(); }
    public List<Integer>    getDrawnNumbers()                    { return client.getDrawnNumbers(); }
    public WalletInfo       getWallet()                          { return client.getWallet(); }
    public long             getJackpot()                         { return client.getJackpot(); }

    // ── Dispatching callback ──────────────────────────────────────

    /**
     * Wraps a {@link LotoClientCallback} and dispatches every method call
     * through the provided {@link UiDispatcher}.
     * Pure Java — no android.os dependency.
     */
    private static class DispatchingCallback implements LotoClientCallback {
        private final LotoClientCallback delegate;
        private final UiDispatcher       ui;

        DispatchingCallback(LotoClientCallback delegate, UiDispatcher ui) {
            this.delegate = delegate;
            this.ui       = ui;
        }

        private void ui(Runnable r) { ui.post(r); }

        @Override public void onConnected()                                    { ui(() -> delegate.onConnected()); }
        @Override public void onJoined(String id, String token, boolean host,
                                       List<RoomPlayer> players, long pricePerPage, long balance) {
            ui(() -> delegate.onJoined(id, token, host, players, pricePerPage, balance));
        }
        @Override public void onDisconnected(boolean retry)                    { ui(() -> delegate.onDisconnected(retry)); }
        @Override public void onReconnected(String gs, List<RoomPlayer> p,
                                            List<Integer> drawn, long balance) { ui(() -> delegate.onReconnected(gs, p, drawn, balance)); }
        @Override public void onAdminAuthOk()                                  { ui(() -> delegate.onAdminAuthOk()); }
        @Override public void onBanList(List<String> n, List<String> ip)        { ui(() -> delegate.onBanList(n, ip)); }
        @Override public void onRoomUpdate(List<RoomPlayer> p, String s)       { ui(() -> delegate.onRoomUpdate(p, s)); }
        @Override public void onPlayerJoined(String id, String n, boolean h)   { ui(() -> delegate.onPlayerJoined(id, n, h)); }
        @Override public void onPlayerLeft(String id)                          { ui(() -> delegate.onPlayerLeft(id)); }
        @Override public void onPagesAssigned(List<ClientPage> p)              { ui(() -> delegate.onPagesAssigned(p)); }
        @Override public void onInsufficientBalance(long r, long a)            { ui(() -> delegate.onInsufficientBalance(r, a)); }
        @Override public void onVoteUpdate(int c, int n)                       { ui(() -> delegate.onVoteUpdate(c, n)); }
        @Override public void onGameStarting(int ms)                           { ui(() -> delegate.onGameStarting(ms)); }
        @Override public void onDrawIntervalChanged(int ms)                    { ui(() -> delegate.onDrawIntervalChanged(ms)); }
        @Override public void onPricePerPageChanged(long price)                { ui(() -> delegate.onPricePerPageChanged(price)); }
        @Override public void onAutoResetScheduled(int delayMs)                { ui(() -> delegate.onAutoResetScheduled(delayMs)); }
        @Override public void onAutoStartScheduled(int delayMs)                { ui(() -> delegate.onAutoStartScheduled(delayMs)); }
        @Override public void onGamePaused()                                   { ui(() -> delegate.onGamePaused()); }
        @Override public void onGameResumed()                                  { ui(() -> delegate.onGameResumed()); }
        @Override public void onNumberDrawn(int n, List<Integer> drawn,
                                            List<ClientPage> marked,
                                            List<ClientPage> won)              { ui(() -> delegate.onNumberDrawn(n, drawn, marked, won)); }
        @Override public void onPageWon(ClientPage p, int row)                 { ui(() -> delegate.onPageWon(p, row)); }
        @Override public void onClaimReceived(String id, String n, int pg)     { ui(() -> delegate.onClaimReceived(id, n, pg)); }
        @Override public void onWinConfirmed(String id, String n, int pg)      { ui(() -> delegate.onWinConfirmed(id, n, pg)); }
        @Override public void onWinRejected(String id, int pg)                 { ui(() -> delegate.onWinRejected(id, pg)); }
        @Override public void onGameEnded(String id, String n)                 { ui(() -> delegate.onGameEnded(id, n)); }
        @Override public void onGameCancelled(String r)                        { ui(() -> delegate.onGameCancelled(r)); }
        @Override public void onGameEndedByServer(String r)                    { ui(() -> delegate.onGameEndedByServer(r)); }
        @Override public void onKicked(String r)                               { ui(() -> delegate.onKicked(r)); }
        @Override public void onBanned(String r)                               { ui(() -> delegate.onBanned(r)); }
        @Override public void onRoomReset(long prize, int count)               { ui(() -> delegate.onRoomReset(prize, count)); }
        @Override public void onBalanceUpdate(WalletInfo w)                    { ui(() -> delegate.onBalanceUpdate(w)); }
        @Override public void onWalletHistory(WalletInfo w)                    { ui(() -> delegate.onWalletHistory(w)); }
        @Override public void onError(String code, String msg)                 { ui(() -> delegate.onError(code, msg)); }
    }
}