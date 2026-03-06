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
 * Main client entry point.
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
 * <p><b>Android:</b> call {@code connect()} on a background thread (e.g. via
 * {@code Executors.newSingleThreadExecutor()}). All callbacks fire on the
 * network thread — marshal to UI thread with {@code runOnUiThread()} as needed.
 */
public class LotoClient {

    // ── Config ────────────────────────────────────────────────────
    private final String               host;
    private final int                  port;
    private final String               wsUrl;          // null = TCP mode
    private final String               roomId;         // null = single-room server
    private final String               playerName;
    private final boolean              autoReconnect;
    private final int                  reconnectDelayMs;
    private final int                  maxReconnectAttempts;
    private final boolean              autoClaimOnWin;
    private final LotoClientCallback   callback;

    // ── State ─────────────────────────────────────────────────────
    private volatile ClientState       state        = ClientState.DISCONNECTED;
    private volatile String            playerId;
    private volatile String            token;
    private volatile boolean           isHost;
    private volatile int               currentDrawIntervalMs;
    private volatile long              currentPricePerPage;
    private volatile int               currentAutoResetDelayMs;

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
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    /**
     * Connects to the server and blocks on the read loop.
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

                // JOIN or RECONNECT
                if (token != null) {
                    conn.send(ClientMsgBuilder.reconnect(token));
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

    public void disconnect() {
        running = false;
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

    public void buyPages(int count)        { send(ClientMsgBuilder.buyPage(count)); }
    public void voteStart()                { send(ClientMsgBuilder.voteStart()); }
    public void claimWin(int pageId)       { send(ClientMsgBuilder.claimWin(pageId)); }
    public void requestWalletHistory()     { send(ClientMsgBuilder.getWallet()); }

    // ── Host-only actions ─────────────────────────────────────────

    public void confirmWin(String playerId, int pageId) {
        send(ClientMsgBuilder.confirmWin(playerId, pageId));
    }
    public void rejectWin(String playerId, int pageId) {
        send(ClientMsgBuilder.rejectWin(playerId, pageId));
    }
    public void topUp(String playerId, long amount, String note) {
        send(ClientMsgBuilder.topUp(playerId, amount, note));
    }
    public void cancelGame(String reason)  { send(ClientMsgBuilder.cancelGame(reason)); }
    public void kick(String playerId, String reason) {
        send(ClientMsgBuilder.kick(playerId, reason));
    }
    public void ban(String playerId, String reason) {
        send(ClientMsgBuilder.ban(playerId, reason));
    }
    public void unban(String name)         { send(ClientMsgBuilder.unban(name)); }

    /**
     * Change draw speed live — host only.
     * @param intervalMs milliseconds between numbers (min 200)
     */
    public void setDrawInterval(int intervalMs) {
        send(ClientMsgBuilder.setDrawInterval(intervalMs));
    }

    /**
     * Change price per page — host only.
     * Only accepted by server while jackpot == 0 (no pages bought yet).
     * @param price new price in đồng (>= 0)
     */
    public void setPricePerPage(long price) {
        send(ClientMsgBuilder.setPricePerPage(price));
    }

    /**
     * Set auto-reset delay after game ends — host only.
     * @param delayMs milliseconds (0 = disable)
     */
    public void setAutoReset(int delayMs) {
        send(ClientMsgBuilder.setAutoReset(delayMs));
    }

    // ── State accessors ───────────────────────────────────────────

    public ClientState       getState()                  { return state; }
    public String            getPlayerId()               { return playerId; }
    public boolean           isHost()                    { return isHost; }
    public int               getCurrentDrawIntervalMs()  { return currentDrawIntervalMs; }
    public long              getCurrentPricePerPage()    { return currentPricePerPage; }
    public int               getCurrentAutoResetDelayMs(){ return currentAutoResetDelayMs; }
    public List<ClientPage>  getPages()                  { return Collections.unmodifiableList(pages); }
    public List<Integer>     getDrawnNumbers()           { return Collections.unmodifiableList(drawnNumbers); }
    public WalletInfo        getWallet()                 { return wallet; }

    // ── Message dispatcher ────────────────────────────────────────

    private void handleMessage(String raw) {
        try {
            JSONObject msg     = new JSONObject(raw);
            String     type    = msg.getString("type");
            JSONObject payload = msg.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();

            switch (type) {
                case "WELCOME":              handleWelcome(payload);         break;
                case "ROOM_UPDATE":          handleRoomUpdate(payload);      break;
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
                case "PAGES_ASSIGNED":       handlePagesAssigned(payload);   break;
                case "VOTE_UPDATE":
                    if (callback != null)
                        callback.onVoteUpdate(
                                payload.getInt("voteCount"),
                                payload.getInt("needed"));
                    break;
                case "GAME_STARTING":
                    currentDrawIntervalMs = payload.optInt("drawIntervalMs", 5000);
                    setState(ClientState.IN_GAME);
                    if (callback != null) callback.onGameStarting(currentDrawIntervalMs);
                    break;
                case "DRAW_INTERVAL_CHANGED":
                    currentDrawIntervalMs = payload.optInt("intervalMs", currentDrawIntervalMs);
                    if (callback != null) callback.onDrawIntervalChanged(currentDrawIntervalMs);
                    break;
                case "PRICE_PER_PAGE_CHANGED":
                    currentPricePerPage = payload.optLong("price", currentPricePerPage);
                    if (callback != null) callback.onPricePerPageChanged(currentPricePerPage);
                    break;
                case "AUTO_RESET_SCHEDULED":
                    currentAutoResetDelayMs = payload.optInt("delayMs", 0);
                    if (callback != null) callback.onAutoResetScheduled(currentAutoResetDelayMs);
                    break;
                case "NUMBER_DRAWN":         handleNumberDrawn(payload);     break;
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
                    // Draw stopped — others can still claim until ROOM_RESET
                    if (callback != null)
                        callback.onGameEnded(
                                payload.optString("winnerPlayerId", ""),
                                payload.optString("winnerName", ""));
                    break;
                case "GAME_ENDED_BY_SERVER":
                    if (callback != null)
                        callback.onGameEndedByServer(payload.optString("reason", ""));
                    break;
                case "ROOM_RESET":
                    // Jackpot paid, pages cleared — back to WAITING
                    handleRoomReset(payload);
                    break;
                case "GAME_CANCELLED":
                    drawnNumbers.clear();
                    pages.clear();
                    setState(ClientState.IN_GAME);   // stays connected
                    if (callback != null)
                        callback.onGameCancelled(payload.optString("reason", ""));
                    break;
                case "BALANCE_UPDATE":       handleBalanceUpdate(payload);   break;
                case "WALLET_HISTORY":       handleWalletHistory(payload);   break;
                case "KICKED":
                    running = false;
                    if (connection != null) connection.close();
                    if (callback != null) callback.onKicked(payload.optString("reason", ""));
                    break;
                case "BANNED":
                    running = false;
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

    // ── Specific handlers ─────────────────────────────────────────

    private void handleWelcome(JSONObject p) {
        playerId = p.getString("playerId");
        token    = p.getString("token");
        isHost   = p.optBoolean("isHost", false);

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
        boolean wasReconnect = !drawnNumbers.isEmpty();
        if (wasReconnect) {
            if (callback != null) callback.onReconnected();
        } else {
            if (callback != null) callback.onJoined(playerId, token, isHost);
        }
    }

    private void handleRoomUpdate(JSONObject p) {
        List<RoomPlayer> players = new ArrayList<>();
        JSONArray arr = p.optJSONArray("players");
        if (arr != null) {
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
        }
        // Sync room-level settings if server included them
        if (p.has("pricePerPage"))     currentPricePerPage     = p.getLong("pricePerPage");
        if (p.has("autoResetDelayMs")) currentAutoResetDelayMs = p.getInt("autoResetDelayMs");

        if (callback != null)
            callback.onRoomUpdate(players, p.optString("gameState", "WAITING"));
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
        int       number   = p.getInt("number");
        JSONArray drawnArr = p.getJSONArray("drawnList");

        drawnNumbers.clear();
        for (int i = 0; i < drawnArr.length(); i++) drawnNumbers.add(drawnArr.getInt(i));

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

    private void handleRoomReset(JSONObject p) {
        // Server paid out jackpot — clear local game state
        long prizeEach   = p.optLong("prizeEach", 0);
        int  winnerCount = p.optInt("winnerCount", 0);

        pages.clear();
        drawnNumbers.clear();
        currentDrawIntervalMs = 0;
        setState(ClientState.IN_GAME);   // still connected, waiting for next game

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

        /** TCP host. Default: localhost */
        public Builder host(String host)                    { this.host = host; return this; }

        /** TCP port. Default: 9000 */
        public Builder port(int port)                       { this.port = port; return this; }

        /**
         * Connect via WebSocket instead of TCP.
         * Example: "ws://192.168.1.10:9001"
         * Setting this overrides host/port for transport.
         */
        public Builder wsUrl(String wsUrl)                  { this.wsUrl = wsUrl; return this; }

        /** Room ID for multi-room servers. Null = single-room (default). */
        public Builder roomId(String roomId)                { this.roomId = roomId; return this; }

        public Builder playerName(String name)              { this.playerName = name; return this; }
        public Builder autoReconnect(boolean v)             { this.autoReconnect = v; return this; }
        public Builder reconnectDelayMs(int ms)             { this.reconnectDelayMs = ms; return this; }
        public Builder maxReconnectAttempts(int n)          { this.maxReconnectAttempts = n; return this; }
        public Builder autoClaimOnWin(boolean v)            { this.autoClaimOnWin = v; return this; }
        public Builder callback(LotoClientCallback cb)      { this.callback = cb; return this; }

        public LotoClient build() {
            if (playerName == null || playerName.trim().isEmpty())
                throw new IllegalArgumentException("playerName is required");
            return new LotoClient(this);
        }
    }
}
