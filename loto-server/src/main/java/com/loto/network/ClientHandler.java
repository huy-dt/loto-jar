package com.loto.network;

import com.loto.protocol.InboundMsg;
import com.loto.protocol.OutboundMsg;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Manages the TCP connection for a single client.
 * Reads newline-delimited JSON messages and forwards them to the dispatcher.
 */
public class ClientHandler implements Runnable {

    private final String             connectionId;   // temporary id before JOIN
    private final Socket             socket;
    private final MessageDispatcher  dispatcher;
    private       PrintWriter        writer;
    private       volatile boolean   running = true;

    public ClientHandler(String connectionId, Socket socket, MessageDispatcher dispatcher) {
        this.connectionId = connectionId;
        this.socket       = socket;
        this.dispatcher   = dispatcher;
    }

    // ─── Runnable ─────────────────────────────────────────────────

    @Override
    public void run() {
        try (
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"))
        ) {
            writer = new PrintWriter(socket.getOutputStream(), true);

            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                InboundMsg msg = InboundMsg.parse(line);
                if (msg == null) {
                    send(OutboundMsg.error("PARSE_ERROR", "Invalid JSON or unknown type").toJson());
                    continue;
                }
                dispatcher.dispatch(connectionId, msg, this);
            }
        } catch (Exception e) {
            // connection dropped
        } finally {
            dispatcher.onDisconnected(connectionId);
            close();
        }
    }

    // ─── Public API ───────────────────────────────────────────────

    /** Thread-safe send. Newline-delimited so the client can split messages. */
    public synchronized void send(String json) {
        if (writer != null && !socket.isClosed()) {
            writer.println(json);
        }
    }

    public void close() {
        running = false;
        try { socket.close(); } catch (Exception ignored) {}
    }

    public boolean isConnected() {
        return !socket.isClosed() && socket.isConnected();
    }

    public String getConnectionId() {
        return connectionId;
    }
}
