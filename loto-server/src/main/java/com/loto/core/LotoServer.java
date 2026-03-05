package com.loto.core;

import com.loto.callback.LotoServerCallback;
import com.loto.network.ClientHandler;
import com.loto.network.MessageDispatcher;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point for the Loto server library.
 *
 * <pre>
 * // Minimal
 * LotoServer server = new LotoServer.Builder().build();
 *
 * // Fully configured
 * LotoServer server = new LotoServer.Builder()
 *         .config(new ServerConfig.Builder()
 *                 .port(9000)
 *                 .drawIntervalMs(4000)
 *                 .reconnectTimeoutMs(20_000)
 *                 .voteThresholdPct(60)
 *                 .maxPagesPerBuy(5)
 *                 .build())
 *         .callback(myCallback)
 *         .build();
 *
 * new Thread(server::startSafe).start();
 * </pre>
 */
public class LotoServer {

    private final ServerConfig          config;
    private final GameRoom              room;
    private final MessageDispatcher     dispatcher;
    private final ExecutorService       threadPool;
    private final AtomicBoolean         running = new AtomicBoolean(false);
    private       ServerSocket          serverSocket;

    // ─── Constructor (use Builder) ────────────────────────────────

    private LotoServer(Builder builder) {
        this.config     = builder.config;
        this.room       = new GameRoom(UUID.randomUUID().toString(), config);
        this.dispatcher = new MessageDispatcher(room);
        this.threadPool = Executors.newCachedThreadPool();

        if (builder.callback != null) {
            room.setCallback(builder.callback);
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────

    /**
     * Blocks the calling thread while accepting connections.
     * Run on a background thread (especially on Android).
     *
     * @throws IOException if the server socket cannot be opened.
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(config.port);
        running.set(true);

        System.out.println("[LotoServer] " + config);

        while (running.get()) {
            try {
                Socket        socket  = serverSocket.accept();
                String        connId  = UUID.randomUUID().toString().substring(0, 8);
                ClientHandler handler = new ClientHandler(connId, socket, dispatcher);
                threadPool.execute(handler);
            } catch (IOException e) {
                if (!running.get()) break;
                System.err.println("[LotoServer] Accept error: " + e.getMessage());
            }
        }
    }

    /** Convenience wrapper — catches IOException so it can be passed to Thread / Runnable. */
    public void startSafe() {
        try { start(); }
        catch (IOException e) { System.err.println("[LotoServer] Failed to start: " + e.getMessage()); }
    }

    /** Stops the server and cleans up all resources. */
    public void stop() {
        running.set(false);
        room.shutdown();
        threadPool.shutdownNow();
        try { if (serverSocket != null) serverSocket.close(); }
        catch (IOException ignored) {}
    }

    public GameRoom      getRoom()   { return room; }
    public ServerConfig  getConfig() { return config; }

    // ─── Builder ──────────────────────────────────────────────────

    public static class Builder {
        private ServerConfig         config   = new ServerConfig.Builder().build();
        private LotoServerCallback   callback;

        /**
         * Pass a fully built {@link ServerConfig}.
         * If not set, all defaults are used (port=9000, interval=5s, …).
         */
        public Builder config(ServerConfig config) {
            this.config = config;
            return this;
        }

        /** Shortcut — wraps a default config with just the port changed. */
        public Builder port(int port) {
            this.config = new ServerConfig.Builder().port(port).build();
            return this;
        }

        public Builder callback(LotoServerCallback callback) {
            this.callback = callback;
            return this;
        }

        public LotoServer build() {
            return new LotoServer(this);
        }
    }
}
