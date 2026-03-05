package com.loto.client.model;

/** Snapshot of one player in the room, as broadcast by server (ROOM_UPDATE). */
public class RoomPlayer {
    public final String  playerId;
    public final String  name;
    public final boolean isHost;
    public final boolean isConnected;
    public final int     pageCount;
    public final long    balance;

    public RoomPlayer(String playerId, String name, boolean isHost,
                      boolean isConnected, int pageCount, long balance) {
        this.playerId    = playerId;
        this.name        = name;
        this.isHost      = isHost;
        this.isConnected = isConnected;
        this.pageCount   = pageCount;
        this.balance     = balance;
    }
}
