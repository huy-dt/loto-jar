package com.loto.network;

import com.loto.core.GameRoom;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * WebSocket transport layer for the Loto server.
 *
 * Shares the same {@link GameRoom} and {@link MessageDispatcher} as the TCP server,
 * so TCP and WebSocket clients play in the same room seamlessly.
 *
 * Usage:
 * <pre>
 *   WebSocketServer wsServer = new WebSocketServer(room, 9001);
 *   new Thread(wsServer::startSafe).start();
 * </pre>
 *
 * Requires: org.java-websocket:Java-WebSocket (Maven/Gradle dep).
 */
public class WebSocketServer extends org.java_websocket.server.WebSocketServer {

    private final MessageDispatcher dispatcher;

    public WebSocketServer(GameRoom room, int port) {
        super(new InetSocketAddress(port));
        this.dispatcher = new MessageDispatcher(room);
        setReuseAddr(true);
    }

    /** Share a pre-existing dispatcher (e.g. same instance as TCP server). */
    public WebSocketServer(MessageDispatcher dispatcher, int port) {
        super(new InetSocketAddress(port));
        this.dispatcher = dispatcher;
        setReuseAddr(true);
    }

    // ─── WebSocketServer callbacks ────────────────────────────────

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String connId = UUID.randomUUID().toString().substring(0, 8);
        new WebSocketClientHandler(connId, conn, dispatcher);
        System.out.printf("[WS] Connected  connId=%s  remote=%s%n",
                connId, conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        WebSocketClientHandler handler = conn.getAttachment();
        if (handler != null) handler.onMessage(message);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        WebSocketClientHandler handler = conn.getAttachment();
        if (handler != null) {
            System.out.printf("[WS] Disconnected connId=%s code=%d remote=%b%n",
                    handler.getConnectionId(), code, remote);
            handler.onClose();
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[WS] Error: " + ex.getMessage());
        if (conn != null) {
            WebSocketClientHandler handler = conn.getAttachment();
            if (handler != null) handler.onClose();
        }
    }

    @Override
    public void onStart() {
        System.out.printf("[WS] WebSocket server started on port %d%n",
                getPort());
    }

    // ─── Safe start (mirrors TCP's startSafe) ─────────────────────

    public void startSafe() {
        try {
            start();
        } catch (Exception e) {
            System.err.println("[WS] Failed to start: " + e.getMessage());
        }
    }
}
