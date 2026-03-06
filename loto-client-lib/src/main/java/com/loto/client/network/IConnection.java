package com.loto.client.network;

/**
 * Common interface for TCP and WebSocket connections.
 * LotoClient only depends on this — transport is swappable.
 */
public interface IConnection {
    /** Connect to server. Blocks until connected or throws. */
    void connect() throws Exception;

    /** Start reading messages. Blocks until disconnected. */
    void readLoop();

    /** Non-blocking send. */
    void send(String json);

    /** Close the connection. */
    void close();

    boolean isConnected();
}
