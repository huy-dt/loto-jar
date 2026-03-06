package com.loto.core;

/**
 * Immutable config object passed into LotoServer.
 * All options are configurable from outside via Builder.
 */
public class ServerConfig {

    public final int  port;
    public final int  drawIntervalMs;
    public final int  reconnectTimeoutMs;
    public final int  voteThresholdPct;
    public final int  maxPagesPerBuy;
    public final long pricePerPage;       // cost to buy 1 page
    public final long initialBalance;     // balance each player starts with
    public final int  wsPort;             // WebSocket port (0 = disabled)
    public final String persistPath;      // JSON save file path (null = disabled)
    public final int  minPlayers;         // minimum players before vote/start is allowed
    public final TransportMode transportMode; // TCP / WS / BOTH
    public final boolean autoVerifyWin;      // server tự xác minh kình thay vì chờ host
    public final int  autoResetDelayMs;      // tự động reset sau xx ms khi ENDED/CANCELLED (0 = tắt)
    public final int  autoStartMs;           // tự động start sau xx ms khi đủ minPlayers (0 = tắt)
    public final String adminToken;          // token bí mật để xác thực admin qua WebSocket/TCP (null = dùng UUID)

    private ServerConfig(Builder b) {
        this.port               = b.port;
        this.drawIntervalMs     = b.drawIntervalMs;
        this.reconnectTimeoutMs = b.reconnectTimeoutMs;
        this.voteThresholdPct   = b.voteThresholdPct;
        this.maxPagesPerBuy     = b.maxPagesPerBuy;
        this.pricePerPage       = b.pricePerPage;
        this.initialBalance     = b.initialBalance;
        this.wsPort             = b.wsPort;
        this.persistPath        = b.persistPath;
        this.minPlayers         = b.minPlayers;
        this.transportMode      = b.transportMode;
        this.autoVerifyWin      = b.autoVerifyWin;
        this.autoResetDelayMs   = b.autoResetDelayMs;
        this.autoStartMs        = b.autoStartMs;
        this.adminToken         = (b.adminToken != null && !b.adminToken.isEmpty())
                                    ? b.adminToken
                                    : java.util.UUID.randomUUID().toString();
    }

    @Override
    public String toString() {
        return String.format(
            "ServerConfig{port=%d, drawInterval=%dms, reconnectTimeout=%dms, " +
            "voteThreshold=%d%%, maxPagesPerBuy=%d, pricePerPage=%d, initialBalance=%d, wsPort=%d, persist=%s, minPlayers=%d, autoResetDelay=%dms, autoStart=%s}",
            port, drawIntervalMs, reconnectTimeoutMs, voteThresholdPct,
            maxPagesPerBuy, pricePerPage, initialBalance, wsPort,
            persistPath != null ? persistPath : "off", minPlayers, autoResetDelayMs,
            autoStartMs > 0 ? autoStartMs + "ms" : "off");
    }

    // ─── Builder ──────────────────────────────────────────────────

    public static class Builder {
        private int  port               = 9000;
        private int  drawIntervalMs     = 5000;
        private int  reconnectTimeoutMs = 30_000;
        private int  voteThresholdPct   = 51;
        private int  maxPagesPerBuy     = 10;
        private long pricePerPage       = 10_000;   // 10k mỗi tờ
        private long initialBalance     = 0;        // player bắt đầu với 0, host nạp
        private int  wsPort             = 0;         // 0 = WebSocket disabled
        private String persistPath      = null;       // null = persistence disabled
        private int  minPlayers         = 1;         // min players to allow start/vote
        private TransportMode transportMode = TransportMode.BOTH;
        private boolean autoVerifyWin    = false;
        private int  autoResetDelayMs    = 0;      // 0 = auto-reset disabled
        private int  autoStartMs         = 0;      // 0 = auto-start disabled
        private String adminToken        = null;   // null = auto-generate UUID

        /** TCP port to listen on. Default: 9000 */
        public Builder port(int port) {
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
            this.port = port;
            return this;
        }

        /** Milliseconds between each number draw. Default: 5000 */
        public Builder drawIntervalMs(int ms) {
            if (ms < 200) throw new IllegalArgumentException("drawIntervalMs must be >= 200");
            this.drawIntervalMs = ms;
            return this;
        }

        /** Milliseconds a disconnected player has to reconnect before being removed. Default: 30000 */
        public Builder reconnectTimeoutMs(int ms) {
            if (ms < 0) throw new IllegalArgumentException("reconnectTimeoutMs must be >= 0");
            this.reconnectTimeoutMs = ms;
            return this;
        }

        /** Percentage of players that must vote to start the game. Default: 51 */
        public Builder voteThresholdPct(int pct) {
            if (pct < 1 || pct > 100) throw new IllegalArgumentException("voteThresholdPct must be 1–100");
            this.voteThresholdPct = pct;
            return this;
        }

        /** Maximum pages a player can buy in a single BUY_PAGE request. Default: 10 */
        public Builder maxPagesPerBuy(int max) {
            if (max < 1) throw new IllegalArgumentException("maxPagesPerBuy must be >= 1");
            this.maxPagesPerBuy = max;
            return this;
        }

        /** Cost of buying one page (deducted from player balance). Default: 10000 */
        public Builder pricePerPage(long price) {
            if (price < 0) throw new IllegalArgumentException("pricePerPage must be >= 0");
            this.pricePerPage = price;
            return this;
        }

        /** Starting balance for every new player. Default: 0 (host tops up manually) */
        public Builder initialBalance(long balance) {
            if (balance < 0) throw new IllegalArgumentException("initialBalance must be >= 0");
            this.initialBalance = balance;
            return this;
        }

        /** WebSocket port. Set to e.g. 9001 to enable WS alongside TCP. 0 = disabled. */
        public Builder wsPort(int port) {
            this.wsPort = port;
            return this;
        }

        /** Path to JSON save file. null or empty = no persistence. */
        public Builder persistPath(String path) {
            this.persistPath = (path != null && !path.isEmpty()) ? path : null;
            return this;
        }

        /**
         * --tcp  : TCP only  (wsPort forced to 0)
         * --ws   : WS only   (TCP accept loop skipped; wsPort required or defaults to port+1)
         * --both : TCP + WS  (default)
         */
        /**
         * Nếu true: server tự kiểm tra page khi nhận CLAIM_WIN.
         * Win hợp lệ → confirmWin tự động. Sai → rejectWin tự động.
         * Default: false (host xác nhận thủ công).
         */
        public Builder autoVerifyWin(boolean auto) {
            this.autoVerifyWin = auto;
            return this;
        }

        /**
         * Milliseconds after game ENDED or CANCELLED before the room auto-resets.
         * Set to 0 (default) to disable auto-reset entirely.
         * Example: autoResetDelayMs(30_000) → reset 30 seconds after game ends.
         */
        public Builder autoResetDelayMs(int ms) {
            if (ms < 0) throw new IllegalArgumentException("autoResetDelayMs must be >= 0");
            this.autoResetDelayMs = ms;
            return this;
        }

        /**
         * Milliseconds after room reaches minPlayers before auto-starting the game.
         * Set to 0 (default) to disable.  Example: autoStartMs(10_000) → 10s countdown.
         * Countdown resets if a player leaves and the room drops below minPlayers.
         */
        public Builder autoStartMs(int ms) {
            if (ms < 0) throw new IllegalArgumentException("autoStartMs must be >= 0");
            this.autoStartMs = ms;
            return this;
        }

        /** Secret admin token used to authenticate admin commands over WebSocket/TCP.
         *  If not set, a random UUID is generated at startup and printed to console. */
        public Builder adminToken(String token) {
            this.adminToken = (token != null && !token.isEmpty()) ? token : null;
            return this;
        }

        public Builder transportMode(TransportMode mode) {
            this.transportMode = mode != null ? mode : TransportMode.BOTH;
            // WS-only: make sure wsPort has a value
            if (mode == TransportMode.WS && this.wsPort == 0) {
                this.wsPort = this.port + 1;
            }
            // TCP-only: disable WS
            if (mode == TransportMode.TCP) {
                this.wsPort = 0;
            }
            return this;
        }

        /** Minimum connected players before vote / serverStart is allowed. Default: 1 */
        public Builder minPlayers(int min) {
            if (min < 1) throw new IllegalArgumentException("minPlayers must be >= 1");
            this.minPlayers = min;
            return this;
        }

        public ServerConfig build() {
            return new ServerConfig(this);
        }
    }
}
