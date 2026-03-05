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

    private ServerConfig(Builder b) {
        this.port               = b.port;
        this.drawIntervalMs     = b.drawIntervalMs;
        this.reconnectTimeoutMs = b.reconnectTimeoutMs;
        this.voteThresholdPct   = b.voteThresholdPct;
        this.maxPagesPerBuy     = b.maxPagesPerBuy;
        this.pricePerPage       = b.pricePerPage;
        this.initialBalance     = b.initialBalance;
    }

    @Override
    public String toString() {
        return String.format(
            "ServerConfig{port=%d, drawInterval=%dms, reconnectTimeout=%dms, " +
            "voteThreshold=%d%%, maxPagesPerBuy=%d, pricePerPage=%d, initialBalance=%d}",
            port, drawIntervalMs, reconnectTimeoutMs, voteThresholdPct,
            maxPagesPerBuy, pricePerPage, initialBalance);
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

        /** TCP port to listen on. Default: 9000 */
        public Builder port(int port) {
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
            this.port = port;
            return this;
        }

        /** Milliseconds between each number draw. Default: 5000 */
        public Builder drawIntervalMs(int ms) {
            if (ms < 500) throw new IllegalArgumentException("drawIntervalMs must be >= 500");
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

        public ServerConfig build() {
            return new ServerConfig(this);
        }
    }
}
