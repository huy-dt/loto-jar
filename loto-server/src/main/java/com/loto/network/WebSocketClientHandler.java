package com.loto.network;

import com.loto.protocol.InboundMsg;
import com.loto.protocol.OutboundMsg;
import org.java_websocket.WebSocket;

/**
 * Adapts a WebSocket connection ({@link org.java_websocket.WebSocket}) to the
 * {@link IClientHandler} interface so that GameRoom/MessageDispatcher work
 * identically whether the client is connected via TCP or WebSocket.
 *
 * Lifecycle:
 *   - Created by {@link WebSocketServer} in onOpen().
 *   - onMessage() → dispatcher.dispatch()
 *   - onClose() / onError() → dispatcher.onDisconnected()
 */
public class WebSocketClientHandler implements IClientHandler {

    private final String            connectionId;
    private final WebSocket         ws;
    private final MessageDispatcher dispatcher;

    public WebSocketClientHandler(String connectionId, WebSocket ws, MessageDispatcher dispatcher) {
        this.connectionId = connectionId;
        this.ws           = ws;
        this.dispatcher   = dispatcher;

        // Attach this handler to the socket so WebSocketServer can reach it in callbacks
        ws.setAttachment(this);
    }

    // ─── Called by WebSocketServer ────────────────────────────────

    public void onMessage(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        InboundMsg msg = InboundMsg.parse(raw.trim());
        if (msg == null) {
            send(OutboundMsg.error("PARSE_ERROR", "Invalid JSON or unknown type").toJson());
            return;
        }
        dispatcher.dispatch(connectionId, msg, this);
    }

    public void onClose() {
        dispatcher.onDisconnected(connectionId);
    }

    // ─── IClientHandler ───────────────────────────────────────────

    @Override
    public synchronized void send(String json) {
        if (ws != null && ws.isOpen()) {
            ws.send(json);
        }
    }

    @Override
    public void close() {
        if (ws != null && ws.isOpen()) {
            ws.close();
        }
    }

    @Override
    public boolean isConnected() {
        return ws != null && ws.isOpen();
    }

    @Override
    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public String getRemoteIp() {
        if (ws != null && ws.getRemoteSocketAddress() instanceof java.net.InetSocketAddress) {
            return ((java.net.InetSocketAddress) ws.getRemoteSocketAddress())
                    .getAddress().getHostAddress();
        }
        return "unknown";
    }
}
