package com.loto.core;

/**
 * Which network transport(s) the server accepts connections on.
 */
public enum TransportMode {
    /** Raw TCP only (original behaviour). */
    TCP,
    /** WebSocket only. */
    WS,
    /** Both TCP and WebSocket simultaneously. */
    BOTH
}
