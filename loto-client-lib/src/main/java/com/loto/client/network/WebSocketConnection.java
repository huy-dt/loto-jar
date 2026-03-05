package com.loto.client.network;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public class WebSocketConnection implements WebSocket.Listener {

    private final String url;
    private final Consumer<String> onMessage;
    private final Runnable onDisconnect;

    private WebSocket webSocket;

    public WebSocketConnection(String url,
                               Consumer<String> onMessage,
                               Runnable onDisconnect) {
        this.url = url;
        this.onMessage = onMessage;
        this.onDisconnect = onDisconnect;
    }

    // ── Connect ─────────────────────────────────────

    public void connect() {
        HttpClient client = HttpClient.newHttpClient();

        client.newWebSocketBuilder()
                .buildAsync(URI.create(url), this)
                .thenAccept(ws -> {
                    webSocket = ws;
                    System.out.println("Connected to server");
                })
                .join();
    }

    // ── Receive message ─────────────────────────────

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        onMessage.accept(data.toString());
        return WebSocket.Listener.super.onText(ws, data, last);
    }

    // ── Disconnect ──────────────────────────────────

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        System.out.println("Disconnected: " + reason);
        onDisconnect.run();
        return WebSocket.Listener.super.onClose(ws, statusCode, reason);
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        error.printStackTrace();
        onDisconnect.run();
    }

    // ── Send ────────────────────────────────────────

    public void send(String message) {
        if (webSocket != null) {
            webSocket.sendText(message, true);
        }
    }

    // ── Close ───────────────────────────────────────

    public void close() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }
}