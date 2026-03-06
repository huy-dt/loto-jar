package com.loto.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages multiple {@link GameRoom} instances.
 *
 * <p>Each room gets a unique roomId. Clients include {@code roomId} in their
 * JOIN/RECONNECT messages so {@link com.loto.network.MessageDispatcher} can
 * route them to the correct room.
 *
 * <h3>Usage</h3>
 * <pre>
 *   RoomManager mgr = new RoomManager(serverConfig);
 *
 *   GameRoom room = mgr.createRoom();          // auto-generates roomId
 *   GameRoom room = mgr.createRoom("vip-1");   // custom roomId
 *
 *   mgr.getRoom("abc123");                     // look up by id
 *   mgr.closeRoom("abc123");                   // shut down + remove
 *   mgr.listRooms();                           // all active rooms
 * </pre>
 */
public class RoomManager {

    private final ServerConfig                config;
    private final Map<String, GameRoom>       rooms   = new ConcurrentHashMap<>();
    private final com.loto.callback.LotoServerCallback defaultCallback;

    public RoomManager(ServerConfig config) {
        this(config, null);
    }

    public RoomManager(ServerConfig config,
                       com.loto.callback.LotoServerCallback defaultCallback) {
        this.config          = config;
        this.defaultCallback = defaultCallback;
    }

    // ─── Room lifecycle ───────────────────────────────────────────

    /** Creates a new room with an auto-generated ID. */
    public synchronized GameRoom createRoom() {
        return createRoom(UUID.randomUUID().toString().substring(0, 8));
    }

    /** Creates a new room with the given ID. Throws if ID already exists. */
    public synchronized GameRoom createRoom(String roomId) {
        if (rooms.containsKey(roomId))
            throw new IllegalArgumentException("Room already exists: " + roomId);

        GameRoom room = new GameRoom(roomId, config);
        if (defaultCallback != null) room.setCallback(defaultCallback);
        rooms.put(roomId, room);
        System.out.printf("[RoomManager] Created room '%s' (total: %d)%n",
                roomId, rooms.size());
        return room;
    }

    /**
     * Shuts down a room and removes it from the manager.
     * All players will be disconnected naturally when their sockets drop.
     */
    public synchronized void closeRoom(String roomId) {
        GameRoom room = rooms.remove(roomId);
        if (room != null) {
            room.shutdown();
            System.out.printf("[RoomManager] Closed room '%s' (total: %d)%n",
                    roomId, rooms.size());
        }
    }

    /** Returns the room with the given ID, or null if not found. */
    public GameRoom getRoom(String roomId) {
        return rooms.get(roomId);
    }

    /** Returns an unmodifiable view of all active rooms. */
    public Map<String, GameRoom> listRooms() {
        return Collections.unmodifiableMap(rooms);
    }

    public int getRoomCount() {
        return rooms.size();
    }

    /** Shuts down all rooms. Call on server stop. */
    public synchronized void shutdownAll() {
        rooms.values().forEach(GameRoom::shutdown);
        rooms.clear();
    }
}
