package com.loto.client.core;

import com.loto.client.callback.LotoClientCallback;
import com.loto.client.model.ClientPage;
import com.loto.client.model.RoomPlayer;
import com.loto.client.model.WalletInfo;
import com.loto.client.model.WalletInfo.TxRecord;
import com.loto.client.network.IConnection;
import com.loto.client.network.TcpConnection;
import com.loto.client.network.WebSocketConnection;
import com.loto.client.protocol.ClientMsgBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.*;

/**
 * Main client entry point — supports TCP and WebSocket transports.
 *
 * <pre>
 * // TCP
 * LotoClient client = new LotoClient.Builder()
 *         .host("192.168.1.10").port(9000)
 *         .playerName("Nguyen")
 *         .autoReconnect(true).autoClaimOnWin(true)
 *         .callback(myCallback).build();
 *
 * // WebSocket
 * LotoClient client = new LotoClient.Builder()
 *         .wsUrl("ws://192.168.1.10:9001")
 *         .playerName("Nguyen")
 *         .callback(myCallback).build();
 *
 * new Thread(client::connect).start();
 * </pre>
 *
 * <p><b>Android:</b> call {@code connect()} on a background thread.
 * All callbacks fire on the network thread — marshal to UI with {@code runOnUiThread()}.
 */
public class LotoClient {

    // ── Config ────────────────────────────────────────────────────
    private final String               host;
    private final int                  port;
    private final String               wsUrl;
    private final String               roomId;
    private final String               playerName;
    private final boolean              autoReconnect;
    private final int                  reconnectDelayMs;
    private final int                  maxReconnectAttempts;
    private final boolean              autoClaimOnWin;
    private final LotoClientCallback   callback;
    private final String               initialToken;     // null = fresh JOIN, non-null = RECONNECT ngay từ đầu

    // ── State ─────────────────────────────────────────────────────
    private volatile ClientState       state                 = ClientState.DISCONNECTED;
    private volatile String            playerId;
    private volatile String            token;
    private volatile boolean           isHost;
    private volatile boolean           isAdmin;
    private volatile boolean           isPaused;
    private volatile String            currentGameState      = "WAITING";
    private volatile int               voteCount;
    private volatile int               voteNeeded;
    private volatile int               currentDrawIntervalMs;
    private volatile long              currentPricePerPage;
    private volatile long              pendingPricePerPage   = -1;
    private volatile int               currentAutoResetDelayMs;
    private volatile int               currentAutoStartDelayMs;

    private final List<ClientPage>     pages        = new CopyOnWriteArrayList<>();
    private final List<Integer>        drawnNumbers = new CopyOnWriteArrayList<>();
    private final WalletInfo           wallet       = new WalletInfo(0);

    // ── Network ───────────────────────────────────────────────────
    private volatile IConnection       connection;
    private volatile boolean           running = false;

    // ── Constructor ───────────────────────────────────────────────

    private LotoClient(Builder b) {
        this.host                 = b.host;
        this.port                 = b.port;
        this.wsUrl                = b.wsUrl;
        this.roomId               = b.roomId;
        this.playerName           = b.playerName;
        this.autoReconnect        = b.autoReconnect;
        this.reconnectDelayMs     = b.reconnectDelayMs;
        this.maxReconnectAttempts = b.maxReconnectAttempts;
        this.autoClaimOnWin       = b.autoClaimOnWin;
        this.callback             = b.callback;
        this.initialToken         = b.initialToken;
        // Nếu có token truyền vào → gửi RECONNECT ngay từ lần connect đầu tiên
        if (b.initialToken != null) this.token = b.initialToken;
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    /**
     * Connects to the server and blocks on the read loop.
     * Automatically sends RECONNECT if a token is held from a prior session.
     * Run this on a background thread.
     */
    public void connect() {
        running = true;
        int attempts = 0;

        while (running) {
            attempts++;
            setState(ClientState.CONNECTING);

            try {
                IConnection conn = buildConnection();
                conn.connect();
                connection = conn;
                setState(ClientState.CONNECTED);
                if (callback != null) callback.onConnected();

                // Always use JOIN — include token for reconnect, omit for fresh join.
                // RECONNECT is legacy; server handles both via JOIN.
                if (token != null) {
                    conn.send(ClientMsgBuilder.joinWithToken(playerName, token, roomId));
                } else {
                    conn.send(roomId != null
                            ? ClientMsgBuilder.join(playerName, roomId)
                            : ClientMsgBuilder.join(playerName));
                }

                attempts = 0;
                conn.readLoop();   // blocks until disconnected

            } catch (Exception e) {
                setState(ClientState.DISCONNECTED);
                boolean willRetry = autoReconnect
                        && (maxReconnectAttempts <= 0 || attempts < maxReconnectAttempts);

                if (callback != null) callback.onDisconnected(willRetry);
                if (!willRetry || !running) break;

                try { Thread.sleep(reconnectDelayMs); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Disconnects and clears the reconnect token so the next {@code connect()} does a fresh JOIN.
     */
    public void disconnect() {
        running = false;
        token   = null;
        if (connection != null) connection.close();
    }

    private IConnection buildConnection() {
        if (wsUrl != null) {
            return new WebSocketConnection(wsUrl, this::handleMessage, this::onConnectionDropped);
        }
        return new TcpConnection(host, port, this::handleMessage, this::onConnectionDropped);
    }

    private void onConnectionDropped() {
        setState(ClientState.DISCONNECTED);
    }

    // ── Public actions — all players ──────────────────────────────

    public void buyPages(int count)    { send(ClientMsgBuilder.buyPage(count)); }
    public void voteStart()            { send(ClientMsgBuilder.voteStart()); }
    public void claimWin(int pageId)   { send(ClientMsgBuilder.claimWin(pageId)); }
    public void requestWalletHistory() { send(ClientMsgBuilder.getWallet()); }

    // ── Admin authentication ──────────────────────────────────────

    /**
     * Authenticate as admin using the server's secret token.
     * Server responds with {@code ADMIN_AUTH_OK}; {@code onAdminAuthOk()} fires.
     *
     * @param adminToken the token shown in the server banner at startup
     */
    public void adminAuth(String adminToken) {
        send(ClientMsgBuilder.adminAuth(adminToken));
    }

    // ── Admin-only actions ────────────────────────────────────────

    public void confirmWin(String playerId, int pageId) { send(ClientMsgBuilder.confirmWin(playerId, pageId)); }
    public void rejectWin(String playerId, int pageId)  { send(ClientMsgBuilder.rejectWin(playerId, pageId)); }
    public void topUp(String playerId, long amount, String note) { send(ClientMsgBuilder.topUp(playerId, amount, note)); }
    public void cancelGame(String reason)               { send(ClientMsgBuilder.cancelGame(reason)); }
    public void kick(String playerId, String reason)    { send(ClientMsgBuilder.kick(playerId, reason)); }
    public void ban(String playerId, String reason)     { send(ClientMsgBuilder.ban(playerId, reason)); }
    public void unban(String name)                      { send(ClientMsgBuilder.unban(name)); }

    /** Pause the draw loop mid-game — admin only. */
    public void pauseGame()                             { send(ClientMsgBuilder.pauseGame()); }

    /** Resume a paused game — admin only. */
    public void resumeGame()                            { send(ClientMsgBuilder.resumeGame()); }

    /** Change draw speed live — admin only. @param intervalMs min 200 */
    public void setDrawInterval(int intervalMs)         { send(ClientMsgBuilder.setDrawInterval(intervalMs)); }

    /** Change price per page — admin only. Only when no pages bought yet. */
    public void setPricePerPage(long price)             { send(ClientMsgBuilder.setPricePerPage(price)); }

    /** Set auto-reset delay — admin only. @param delayMs 0 = disable */
    public void setAutoReset(int delayMs)               { send(ClientMsgBuilder.setAutoReset(delayMs)); }

    /** Set auto-start delay — admin only. @param delayMs 0 = disable */
    public void setAutoStart(int delayMs)               { send(ClientMsgBuilder.setAutoStart(delayMs)); }

    /** Start game ngay — bypass vote. Admin only. */
    public void serverStart()                           { send(ClientMsgBuilder.serverStart()); }

    /** Kết thúc game không có winner. Admin only. */
    public void serverEnd(String reason)                { send(ClientMsgBuilder.serverEnd(reason)); }

    /** Reset phòng về WAITING, chia jackpot cho winner hiện tại. Admin only. */
    public void resetRoom()                             { send(ClientMsgBuilder.resetRoom()); }

    /** Cấm kết nối từ IP này. Admin only. */
    public void banIp(String ip)                        { send(ClientMsgBuilder.banIp(ip)); }

    /** Gỡ cấm IP. Admin only. */
    public void unbanIp(String ip)                      { send(ClientMsgBuilder.unbanIp(ip)); }

    /** Lấy danh sách tên + IP đang bị cấm. Server trả về onBanList(). Admin only. */
    public void getBanList()                            { send(ClientMsgBuilder.getBanList()); }

    // ── State accessors ───────────────────────────────────────────

    public ClientState       getState()                   { return state; }
    public String            getPlayerId()                { return playerId; }
    public String            getPlayerName()              { return playerName; }
    public boolean           isHost()                     { return isHost; }
    public boolean           isAdmin()                    { return isAdmin; }
    public boolean           isPaused()                   { return isPaused; }
    public String            getCurrentGameState()        { return currentGameState; }
    public int               getVoteCount()               { return voteCount; }
    public int               getVoteNeeded()              { return voteNeeded; }
    public int               getCurrentDrawIntervalMs()   { return currentDrawIntervalMs; }
    public long              getCurrentPricePerPage()     { return currentPricePerPage; }
    /** Pending price set during PLAYING — applies next game. -1 = none. */
    public long              getPendingPricePerPage()     { return pendingPricePerPage; }
    public int               getCurrentAutoResetDelayMs() { return currentAutoResetDelayMs; }
    public int               getCurrentAutoStartDelayMs() { return currentAutoStartDelayMs; }
    public List<ClientPage>  getPages()                   { return Collections.unmodifiableList(pages); }
    public List<Integer>     getDrawnNumbers()            { return Collections.unmodifiableList(drawnNumbers); }
    public WalletInfo        getWallet()                  { return wallet; }

    // ── Message dispatcher ────────────────────────────────────────

    private void handleMessage(String raw) {
        try {
            JSONObject msg     = new JSONObject(raw);
            String     type    = msg.getString("type");
            JSONObject payload = msg.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();

            switch (type) {
                case "WELCOME":           handleWelcome(payload);       break;
                case "ROOM_UPDATE":       handleRoomUpdate(payload);    break;
                case "PLAYER_JOINED":
                    if (callback != null)
                        callback.onPlayerJoined(
                                payload.getString("playerId"),
                                payload.getString("name"),
                                payload.optBoolean("isHost", false));
                    break;
                case "PLAYER_LEFT":
                    if (callback != null)
                        callback.onPlayerLeft(payload.getString("playerId"));
                    break;
                case "PAGES_ASSIGNED":    handlePagesAssigned(payload); break;
                case "VOTE_UPDATE":
                    voteCount  = payload.getInt("voteCount");
                    voteNeeded = payload.getInt("needed");
                    if (callback != null) callback.onVoteUpdate(voteCount, voteNeeded);
                    break;
                case "GAME_STARTING":
                    currentDrawIntervalMs = payload.optInt("drawIntervalMs", 5000);
                    currentGameState      = "PLAYING";
                    isPaused              = false;
                    setState(ClientState.IN_GAME);
                    if (callback != null) callback.onGameStarting(currentDrawIntervalMs);
                    break;
                case "DRAW_INTERVAL_CHANGED":
                    currentDrawIntervalMs = payload.optInt("intervalMs", currentDrawIntervalMs);
                    if (callback != null) callback.onDrawIntervalChanged(currentDrawIntervalMs);
                    break;
                case "PRICE_PER_PAGE_CHANGED":
                    currentPricePerPage = payload.optLong("pricePerPage", currentPricePerPage);
                    if (callback != null) callback.onPricePerPageChanged(currentPricePerPage);
                    break;
                case "AUTO_RESET_SCHEDULED":
                    currentAutoResetDelayMs = payload.optInt("delayMs", 0);
                    if (callback != null) callback.onAutoResetScheduled(currentAutoResetDelayMs);
                    break;
                case "AUTO_START_SCHEDULED":
                    currentAutoStartDelayMs = payload.optInt("delayMs", 0);
                    if (callback != null) callback.onAutoStartScheduled(currentAutoStartDelayMs);
                    break;
                case "GAME_PAUSED":
                    isPaused = true;
                    if (callback != null) callback.onGamePaused();
                    break;
                case "GAME_RESUMED":
                    isPaused              = false;
                    currentDrawIntervalMs = payload.optInt("drawIntervalMs", currentDrawIntervalMs);
                    if (callback != null) callback.onGameResumed();
                    break;
                case "NUMBER_DRAWN":      handleNumberDrawn(payload);   break;
                case "CLAIM_RECEIVED":
                    if (callback != null)
                        callback.onClaimReceived(
                                payload.getString("playerId"),
                                payload.getString("playerName"),
                                payload.getInt("pageId"));
                    break;
                case "WIN_CONFIRMED":
                    if (callback != null)
                        callback.onWinConfirmed(
                                payload.getString("playerId"),
                                payload.getString("playerName"),
                                payload.getInt("pageId"));
                    break;
                case "WIN_REJECTED":
                    if (callback != null)
                        callback.onWinRejected(
                                payload.getString("playerId"),
                                payload.getInt("pageId"));
                    break;
                case "GAME_ENDED":
                    currentGameState = "ENDED";
                    setState(ClientState.IN_GAME);
                    String winnerId   = payload.optString("winnerPlayerId", "");
                    String winnerName = payload.optString("winnerName", "");
                    String endReason  = payload.optString("reason", "");
                    if (!winnerId.isEmpty()) {
                        // WIN_CONFIRMED triggered game end
                        if (callback != null) callback.onGameEnded(winnerId, winnerName);
                    } else {
                        // Server ended without winner (all 90 drawn, SERVER_END)
                        if (callback != null) callback.onGameEndedByServer(endReason);
                    }
                    break;
                case "ROOM_RESET":        handleRoomReset(payload);     break;
                case "GAME_CANCELLED":
                    currentGameState    = "WAITING";
                    drawnNumbers.clear();
                    pages.clear();
                    pendingPricePerPage = -1;
                    isPaused            = false;
                    voteCount           = 0;
                    voteNeeded          = 0;
                    if (callback != null)
                        callback.onGameCancelled(payload.optString("reason", ""));
                    break;
                case "ADMIN_AUTH_OK":
                    isAdmin = true;
                    if (callback != null) callback.onAdminAuthOk();
                    break;
                case "BAN_LIST":          handleBanList(payload);       break;
                case "BALANCE_UPDATE":    handleBalanceUpdate(payload); break;
                case "WALLET_HISTORY":    handleWalletHistory(payload); break;
                case "KICKED":
                    running = false;
                    token   = null;
                    if (connection != null) connection.close();
                    if (callback != null) callback.onKicked(payload.optString("reason", ""));
                    break;
                case "BANNED":
                    running = false;
                    token   = null;
                    if (connection != null) connection.close();
                    if (callback != null) callback.onBanned(payload.optString("reason", ""));
                    break;
                case "ERROR":
                    String code    = payload.optString("code", "UNKNOWN");
                    String message = payload.optString("message", "");
                    if ("INSUFFICIENT_BALANCE".equals(code) && callback != null)
                        callback.onInsufficientBalance(0, wallet.getBalance());
                    if (callback != null) callback.onError(code, message);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            if (callback != null) callback.onError("PARSE_ERROR", e.getMessage());
        }
    }

    // ── Message handlers ──────────────────────────────────────────

    private void handleWelcome(JSONObject p) {
        playerId = p.getString("playerId");
        token    = p.getString("token");
        isHost   = p.optBoolean("isHost", false);

        // ── Restore drawnNumbers FIRST — pages need them for markAll() ──
        JSONArray drawnArr = p.optJSONArray("drawnNumbers");
        if (drawnArr != null) {
            drawnNumbers.clear();
            for (int i = 0; i < drawnArr.length(); i++) drawnNumbers.add(drawnArr.getInt(i));
        }

        // Restore room settings included in full-state WELCOME
        if (p.has("drawIntervalMs"))  currentDrawIntervalMs   = p.getInt("drawIntervalMs");
        if (p.has("pricePerPage"))    currentPricePerPage     = p.getLong("pricePerPage");
        if (p.has("isPaused"))        isPaused                = p.getBoolean("isPaused");
        voteCount        = p.optInt("voteCount", 0);
        voteNeeded       = p.optInt("voteNeeded", 0);
        currentGameState = p.optString("gameState", "WAITING");

        // Restore own pages — drawnNumbers already populated above
        pages.clear();
        JSONArray pagesArr = p.optJSONArray("pages");
        if (pagesArr != null) {
            for (int i = 0; i < pagesArr.length(); i++) {
                ClientPage page = parseClientPage(pagesArr.getJSONObject(i));
                if (page != null) {
                    page.markAll(drawnNumbers);
                    pages.add(page);
                }
            }
        }

        setState(ClientState.IN_GAME);

        // Distinguish fresh JOIN vs RECONNECT
        boolean wasReconnect = !drawnNumbers.isEmpty()
                || !"WAITING".equals(currentGameState);

        if (wasReconnect) {
            List<RoomPlayer> roomPlayers = parsePlayers(p.optJSONArray("players"));
            if (callback != null)
                callback.onReconnected(currentGameState, roomPlayers, drawnNumbers);
        } else {
            if (callback != null) callback.onJoined(playerId, token, isHost);
        }
    }

    private void handleRoomUpdate(JSONObject p) {
        List<RoomPlayer> players = parsePlayers(p.optJSONArray("players"));

        if (p.has("pricePerPage"))       currentPricePerPage     = p.getLong("pricePerPage");
        // Server sends autoResetDelaySec (seconds), convert to ms
        if (p.has("autoResetDelaySec"))  currentAutoResetDelayMs = p.getInt("autoResetDelaySec") * 1000;
        // autoStartDelayMs not included in ROOM_UPDATE — delivered via AUTO_START_SCHEDULED

        currentGameState = p.optString("gameState", currentGameState);

        if (callback != null)
            callback.onRoomUpdate(players, currentGameState);
    }

    private void handlePagesAssigned(JSONObject p) {
        List<ClientPage> newPages = new ArrayList<>();
        JSONArray arr = p.optJSONArray("pages");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                ClientPage page = parseClientPage(arr.getJSONObject(i));
                if (page != null) {
                    page.markAll(drawnNumbers);
                    pages.add(page);
                    newPages.add(page);
                }
            }
        }
        if (callback != null) callback.onPagesAssigned(newPages);
    }

    private void handleNumberDrawn(JSONObject p) {
        int number = p.getInt("number");

        // Server only emits the new number — client appends to its local list.
        // Full drawnNumbers list is delivered via WELCOME on (re)connect.
        if (!drawnNumbers.contains(number)) drawnNumbers.add(number);

        List<ClientPage> markedPages = new ArrayList<>();
        List<ClientPage> wonPages    = new ArrayList<>();
        for (ClientPage page : pages) {
            boolean hit = page.mark(number);
            if (hit) {
                markedPages.add(page);
                if (page.hasWon()) wonPages.add(page);
            }
        }

        if (callback != null)
            callback.onNumberDrawn(number, new ArrayList<>(drawnNumbers), markedPages, wonPages);

        for (ClientPage wonPage : wonPages) {
            int rowIndex = wonPage.getWinningRowIndex();
            if (callback != null) callback.onPageWon(wonPage, rowIndex);
            if (autoClaimOnWin) claimWin(wonPage.getId());
        }
    }

    private void handleBanList(JSONObject p) {
        List<String> names = new ArrayList<>();
        List<String> ips   = new ArrayList<>();
        JSONArray na = p.optJSONArray("bannedNames");
        JSONArray ia = p.optJSONArray("bannedIps");
        if (na != null) for (int i = 0; i < na.length(); i++) names.add(na.getString(i));
        if (ia != null) for (int i = 0; i < ia.length(); i++) ips.add(ia.getString(i));
        if (callback != null) callback.onBanList(names, ips);
    }

    private void handleRoomReset(JSONObject p) {
        long prizeEach   = p.optLong("prizeEach", 0);
        int  winnerCount = p.optInt("winnerCount", 0);

        pages.clear();
        drawnNumbers.clear();
        currentDrawIntervalMs = 0;
        currentGameState      = "WAITING";
        pendingPricePerPage   = -1;
        isPaused              = false;
        voteCount             = 0;
        voteNeeded            = 0;
        setState(ClientState.IN_GAME);

        if (callback != null) callback.onRoomReset(prizeEach, winnerCount);
    }

    private void handleBalanceUpdate(JSONObject p) {
        long balance = p.optLong("balance", 0);
        TxRecord tx  = null;
        JSONObject t = p.optJSONObject("transaction");
        if (t != null) {
            tx = new TxRecord(
                    t.optLong("timestamp", 0),
                    t.optString("type", ""),
                    t.optLong("amount", 0),
                    t.optLong("balanceAfter", 0),
                    t.optString("note", ""));
        }
        wallet.update(balance, tx);
        if (callback != null) callback.onBalanceUpdate(wallet);
    }

    private void handleWalletHistory(JSONObject p) {
        long balance = p.optLong("balance", 0);
        List<TxRecord> history = new ArrayList<>();
        JSONArray arr = p.optJSONArray("transactions");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                history.add(new TxRecord(
                        t.optLong("timestamp", 0),
                        t.optString("type", ""),
                        t.optLong("amount", 0),
                        t.optLong("balanceAfter", 0),
                        t.optString("note", "")));
            }
        }
        wallet.setHistory(balance, history);
        if (callback != null) callback.onWalletHistory(wallet);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private List<RoomPlayer> parsePlayers(JSONArray arr) {
        List<RoomPlayer> players = new ArrayList<>();
        if (arr == null) return players;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            players.add(new RoomPlayer(
                    obj.getString("playerId"),
                    obj.getString("name"),
                    obj.optBoolean("isHost", false),
                    obj.optBoolean("isBot", false),
                    obj.optBoolean("isConnected", true),
                    obj.optInt("pageCount", 0),
                    obj.optLong("balance", 0)));
        }
        return players;
    }

    private ClientPage parseClientPage(JSONObject obj) {
        try {
            int       id      = obj.getInt("id");
            JSONArray gridArr = obj.getJSONArray("grid");
            List<List<Integer>> grid = new ArrayList<>();
            for (int r = 0; r < gridArr.length(); r++) {
                JSONArray rowArr = gridArr.getJSONArray(r);
                List<Integer> row = new ArrayList<>();
                for (int c = 0; c < rowArr.length(); c++)
                    row.add(rowArr.isNull(c) ? null : rowArr.getInt(c));
                grid.add(row);
            }
            return new ClientPage(id, grid);
        } catch (Exception e) { return null; }
    }

    private void setState(ClientState newState) { state = newState; }

    private void send(String json) {
        IConnection conn = connection;
        if (conn != null && conn.isConnected()) conn.send(json);
    }

    // ── Builder ───────────────────────────────────────────────────

    public static class Builder {
        private String             host                 = "localhost";
        private int                port                 = 9000;
        private String             wsUrl                = null;
        private String             roomId               = null;
        private String             playerName           = "Player";
        private boolean            autoReconnect        = true;
        private int                reconnectDelayMs     = 3_000;
        private int                maxReconnectAttempts = 0;
        private boolean            autoClaimOnWin       = false;
        private LotoClientCallback callback;
        private String             initialToken         = null;  // để RECONNECT ngay từ đầu

        public Builder host(String host)               { this.host = host; return this; }
        public Builder port(int port)                  { this.port = port; return this; }
        public Builder wsUrl(String wsUrl)             { this.wsUrl = wsUrl; return this; }
        public Builder roomId(String roomId)           { this.roomId = roomId; return this; }
        public Builder playerName(String name)         { this.playerName = name; return this; }
        public Builder autoReconnect(boolean v)        { this.autoReconnect = v; return this; }
        public Builder reconnectDelayMs(int ms)        { this.reconnectDelayMs = ms; return this; }
        public Builder maxReconnectAttempts(int n)     { this.maxReconnectAttempts = n; return this; }
        public Builder autoClaimOnWin(boolean v)       { this.autoClaimOnWin = v; return this; }
        public Builder callback(LotoClientCallback cb) { this.callback = cb; return this; }
        /**
         * Truyền token từ session trước để RECONNECT ngay khi connect().
         * Dùng khi chạy CLI với --token <token>.
         */
        public Builder token(String token)             { this.initialToken = (token != null && !token.isBlank()) ? token : null; return this; }

        public LotoClient build() {
            if (playerName == null || playerName.trim().isEmpty())
                throw new IllegalArgumentException("playerName is required");
            return new LotoClient(this);
        }
    }
}