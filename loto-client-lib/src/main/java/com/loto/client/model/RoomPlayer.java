package com.loto.client.model;

import java.util.Collections;
import java.util.List;

/**
 * Snapshot của một người chơi trong phòng.
 *
 * <p>Được populate từ {@code roomInfo.players[]} trong WELCOME
 * và từ {@code players[]} trong ROOM_UPDATE.</p>
 *
 * <p>Field {@code pages} chỉ có giá trị trong WELCOME (server gửi full grid).
 * Trong ROOM_UPDATE server chỉ gửi {@code pageCount} nên {@code pages} sẽ là {@code null}.</p>
 */
public class RoomPlayer {
    public final String            playerId;
    public final String            name;
    public final boolean           isHost;
    public final boolean           isBot;
    public final boolean           isConnected;
    public final int               pageCount;
    public final long              balance;
    /** Grid tờ của player — có giá trị khi parse từ WELCOME, null khi từ ROOM_UPDATE. */
    public final List<ClientPage>  pages;

    /** Constructor đầy đủ — dùng khi parse WELCOME (có pages). */
    public RoomPlayer(String playerId, String name, boolean isHost, boolean isBot,
                      boolean isConnected, int pageCount, long balance,
                      List<ClientPage> pages) {
        this.playerId    = playerId;
        this.name        = name;
        this.isHost      = isHost;
        this.isBot       = isBot;
        this.isConnected = isConnected;
        this.pageCount   = pageCount;
        this.balance     = balance;
        this.pages       = pages != null ? Collections.unmodifiableList(pages) : null;
    }

    /** Constructor không có pages — dùng khi parse ROOM_UPDATE. */
    public RoomPlayer(String playerId, String name, boolean isHost, boolean isBot,
                      boolean isConnected, int pageCount, long balance) {
        this(playerId, name, isHost, isBot, isConnected, pageCount, balance, null);
    }
}
