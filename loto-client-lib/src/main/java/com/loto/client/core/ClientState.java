package com.loto.client.core;

public enum ClientState {
    DISCONNECTED,   // chưa kết nối hoặc đã ngắt
    CONNECTING,     // đang thử kết nối / reconnect
    CONNECTED,      // đã kết nối, chưa JOIN
    IN_GAME,        // đã JOIN, đang chơi
}
