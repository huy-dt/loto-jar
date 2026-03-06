package com.loto.network;

/**
 * Common interface for both TCP and WebSocket client connections.
 * GameRoom and MessageDispatcher only depend on this interface,
 * so they work transparently regardless of transport.
 */
public interface IClientHandler {

    /** Send a JSON string to this client. Thread-safe. */
    void send(String json);

    /** Close the underlying connection. */
    void close();

    /** Returns true if the connection is still alive. */
    boolean isConnected();

    /** Returns the unique connection ID assigned at accept time. */
    String getConnectionId();

    /** Returns the remote IP address (used for IP-based banning). */
    String getRemoteIp();
}
