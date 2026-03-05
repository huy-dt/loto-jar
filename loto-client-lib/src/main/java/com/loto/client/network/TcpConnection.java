package com.loto.client.network;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Manages a single TCP connection to the loto server.
 *
 * - Reads newline-delimited JSON messages from server
 * - Writes messages via a non-blocking send queue
 * - Calls {@code onMessage} for each received line
 * - Calls {@code onDisconnect} when connection drops
 */
public class TcpConnection {

    private final String              host;
    private final int                 port;
    private final Consumer<String>    onMessage;
    private final Runnable            onDisconnect;

    private       Socket              socket;
    private       PrintWriter         writer;
    private       volatile boolean    running = false;

    private final BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();

    public TcpConnection(String host, int port,
                         Consumer<String> onMessage,
                         Runnable onDisconnect) {
        this.host         = host;
        this.port         = port;
        this.onMessage    = onMessage;
        this.onDisconnect = onDisconnect;
    }

    // ── Connect ───────────────────────────────────────────────────

    /**
     * Opens the TCP connection. Blocks until connected or throws.
     */
    public void connect() throws IOException {
        socket  = new Socket(host, port);
        writer  = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
        running = true;

        // Writer thread: drains sendQueue → socket
        Thread writerThread = new Thread(this::writeLoop, "loto-client-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    /**
     * Reads messages synchronously — call this on a dedicated thread.
     * Returns when the connection drops.
     */
    public void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), "UTF-8"))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) onMessage.accept(line);
            }
        } catch (IOException ignored) {
            // connection dropped
        } finally {
            running = false;
            close();
            onDisconnect.run();
        }
    }

    private void writeLoop() {
        try {
            while (running) {
                String msg = sendQueue.take();
                if (writer != null) writer.println(msg);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Send ──────────────────────────────────────────────────────

    /** Non-blocking — queues message for sending. */
    public void send(String json) {
        if (running) sendQueue.offer(json);
    }

    // ── Close ─────────────────────────────────────────────────────

    public void close() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    public boolean isConnected() {
        return running && socket != null && !socket.isClosed();
    }
}
