package com.loto.client.network;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * WebSocket connection — implements {@link IConnection}.
 * Uses org.java-websocket (same lib as server side).
 *
 * Dependency: org.java-websocket:Java-WebSocket:1.5.4
 *
 * Android note: replace with OkHttp WebSocket if targeting Android <Java11.
 */
public class WebSocketConnection implements IConnection {

    private final String           url;
    private final Consumer<String> onMessage;
    private final Runnable         onDisconnect;

    private       WsClient         wsClient;
    private volatile boolean       running = false;

    // readLoop() blocks on this until the socket closes
    private final CountDownLatch   closeLatch = new CountDownLatch(1);
    private final BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();

    public WebSocketConnection(String url,
                               Consumer<String> onMessage,
                               Runnable onDisconnect) {
        this.url         = url;
        this.onMessage   = onMessage;
        this.onDisconnect = onDisconnect;
    }

    @Override
    public void connect() throws Exception {
        wsClient = new WsClient(new URI(url));
        boolean ok = wsClient.connectBlocking(10, TimeUnit.SECONDS);
        if (!ok) throw new IOException("WebSocket connect timeout: " + url);
        running = true;

        // writer thread: drains sendQueue → websocket
        Thread writer = new Thread(() -> {
            try {
                while (running) {
                    String msg = sendQueue.take();
                    if (wsClient != null && wsClient.isOpen()) wsClient.send(msg);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "loto-ws-writer");
        writer.setDaemon(true);
        writer.start();
    }

    @Override
    public void readLoop() {
        // WsClient callbacks fire on its own thread.
        // We just block here until the connection closes.
        try {
            closeLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
            onDisconnect.run();
        }
    }

    @Override
    public void send(String json) {
        if (running) sendQueue.offer(json);
    }

    @Override
    public void close() {
        running = false;
        if (wsClient != null) wsClient.close();
        closeLatch.countDown();
    }

    @Override
    public boolean isConnected() {
        return running && wsClient != null && wsClient.isOpen();
    }

    // ── Inner WebSocketClient ─────────────────────────────────────

    private class WsClient extends WebSocketClient {
        WsClient(URI uri) { super(uri); }

        @Override public void onOpen(ServerHandshake h) {
            // connected — readLoop() is already waiting on closeLatch
        }

        @Override public void onMessage(String msg) {
            if (msg != null && !msg.trim().isEmpty()) onMessage.accept(msg.trim());
        }

        @Override public void onClose(int code, String reason, boolean remote) {
            running = false;
            closeLatch.countDown();
        }

        @Override public void onError(Exception e) {
            running = false;
            closeLatch.countDown();
        }
    }
}
