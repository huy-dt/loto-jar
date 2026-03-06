package com.loto.network;

import com.loto.core.GameRoom;
import com.loto.core.RoomManager;
import com.loto.model.Player;
import com.loto.protocol.InboundMsg;
import com.loto.protocol.OutboundMsg;
import com.loto.network.IClientHandler;

/**
 * Routes parsed inbound messages to the correct GameRoom action.
 * One dispatcher is shared across all ClientHandler threads.
 */
public class MessageDispatcher {

    private final GameRoom     room;
    private final RoomManager  roomManager;   // null = single-room mode

    /** Single-room mode (original behaviour). */
    public MessageDispatcher(GameRoom room) {
        this.room        = room;
        this.roomManager = null;
    }

    /** Multi-room mode — JOIN payload must include "roomId". */
    public MessageDispatcher(RoomManager roomManager) {
        this.room        = null;
        this.roomManager = roomManager;
    }

    /** Returns the room for a connId, looking up via roomManager if needed. */
    private GameRoom roomFor(String connId) {
        if (roomManager == null) return room;
        // In multi-room mode, find which room owns this connId
        for (GameRoom r : roomManager.listRooms().values()) {
            if (r.getPlayerByConnId(connId) != null) return r;
        }
        return null;
    }

    public void dispatch(String connId, InboundMsg msg, IClientHandler handler) {
        // Resolve the room for this connection (single-room: always `room`)
        // For non-JOIN messages, resolve room or reject
        final GameRoom _r = roomFor(connId);
        switch (msg.getType()) {

            case JOIN: {
                String name = msg.getString("name");
                if (name == null || name.trim().isEmpty()) {
                    handler.send(OutboundMsg.error("MISSING_NAME", "name is required").toJson());
                    return;
                }
                GameRoom target;
                if (roomManager != null) {
                    String roomId = msg.getString("roomId");
                    if (roomId == null) {
                        handler.send(OutboundMsg.error("MISSING_ROOM_ID",
                                "roomId required in multi-room mode").toJson());
                        return;
                    }
                    target = roomManager.getRoom(roomId);
                    if (target == null) {
                        handler.send(OutboundMsg.error("ROOM_NOT_FOUND",
                                "No room with id: " + roomId).toJson());
                        return;
                    }
                } else {
                    target = room;
                }
                target.join(connId, name.trim(), handler);
                break;
            }

            case RECONNECT: {
                String token = msg.getString("token");
                if (token == null) {
                    handler.send(OutboundMsg.error("MISSING_TOKEN", "token is required").toJson());
                    return;
                }
                if (_r.reconnect(connId, token, handler) == null) {
                    handler.send(OutboundMsg.error("INVALID_TOKEN", "Token not found or expired").toJson());
                }
                break;
            }

            case BUY_PAGE: {
                int count = msg.getInt("count", 1);
                if (count < 1) {
                    handler.send(OutboundMsg.error("INVALID_COUNT", "count must be >= 1").toJson());
                    return;
                }
                _r.buyPages(connId, count);
                break;
            }

            case VOTE_START: {
                _r.voteStart(connId);
                break;
            }

            case CLAIM_WIN: {
                int pageId = msg.getInt("pageId", -1);
                if (pageId < 0) {
                    handler.send(OutboundMsg.error("MISSING_PAGE_ID", "pageId is required").toJson());
                    return;
                }
                _r.claimWin(connId, pageId);
                break;
            }

            case CONFIRM_WIN: {
                if (!requireHost(connId, handler)) return;
                String playerId = msg.getString("playerId");
                int    pageId   = msg.getInt("pageId", -1);
                if (playerId == null || pageId < 0) {
                    handler.send(OutboundMsg.error("MISSING_FIELDS", "playerId and pageId required").toJson());
                    return;
                }
                _r.confirmWin(playerId, pageId);
                break;
            }

            case REJECT_WIN: {
                if (!requireHost(connId, handler)) return;
                String playerId = msg.getString("playerId");
                int    pageId   = msg.getInt("pageId", -1);
                if (playerId == null || pageId < 0) {
                    handler.send(OutboundMsg.error("MISSING_FIELDS", "playerId and pageId required").toJson());
                    return;
                }
                _r.rejectWin(playerId, pageId);
                break;
            }

            case TOPUP: {
                if (!requireHost(connId, handler)) return;
                // { "type": "TOPUP", "payload": { "playerId": "xxx", "amount": 50000, "note": "..." } }
                String playerId = msg.getString("playerId");
                long   amount   = msg.getLong("amount", -1);
                String note     = msg.getString("note");
                if (playerId == null || amount <= 0) {
                    handler.send(OutboundMsg.error("MISSING_FIELDS", "playerId and amount > 0 required").toJson());
                    return;
                }
                _r.topUp(playerId, amount, note);
                break;
            }

            case CANCEL_GAME: {
                if (!requireHost(connId, handler)) return;
                // { "type": "CANCEL_GAME", "payload": { "reason": "..." } }
                String reason = msg.getString("reason");
                _r.cancelGame(reason != null ? reason : "Host hủy game");
                break;
            }

            case GET_WALLET: {
                _r.sendWalletHistory(connId);
                break;
            }

            case KICK: {
                if (!requireHost(connId, handler)) return;
                // { "type": "KICK", "payload": { "playerId": "xxx", "reason": "..." } }
                String playerId = msg.getString("playerId");
                if (playerId == null) {
                    handler.send(OutboundMsg.error("MISSING_FIELDS", "playerId required").toJson());
                    return;
                }
                _r.kick(playerId, msg.getString("reason"));
                break;
            }

            case BAN: {
                if (!requireHost(connId, handler)) return;
                // { "type": "BAN", "payload": { "playerId": "xxx", "reason": "..." } }
                String playerId = msg.getString("playerId");
                if (playerId == null) {
                    handler.send(OutboundMsg.error("MISSING_FIELDS", "playerId required").toJson());
                    return;
                }
                _r.ban(playerId, msg.getString("reason"));
                break;
            }

            case UNBAN: {
                if (!requireHost(connId, handler)) return;
                // { "type": "UNBAN", "payload": { "name": "xxx" } }
                String name = msg.getString("name");
                if (name == null) {
                    handler.send(OutboundMsg.error("MISSING_FIELDS", "name required").toJson());
                    return;
                }
                _r.unban(name);
                break;
            }

            case SET_DRAW_INTERVAL: {
                // { "type": "SET_DRAW_INTERVAL", "payload": { "intervalMs": 3000 } }
                if (!requireHost(connId, handler)) return;
                int intervalMs = msg.getInt("intervalMs", -1);
                if (intervalMs < 200) {
                    handler.send(OutboundMsg.error("INVALID_INTERVAL",
                            "intervalMs must be >= 200").toJson());
                    return;
                }
                _r.setDrawInterval(intervalMs);
                break;
            }

            default:
                handler.send(OutboundMsg.error("UNKNOWN_TYPE",
                        "Unhandled message type: " + msg.getType()).toJson());
        }
    }

    // ─── Room resolver ───────────────────────────────────────────────

    // Thread-local connId trick: we pass connId explicitly instead
    // Simple approach: inline resolve per dispatch call via local helper
    // _room() is resolved per-call in dispatch — see roomFor()

    // ─── Host guard ───────────────────────────────────────────────

    /**
     * Returns true if the player associated with connId is the host.
     * Sends an ERROR to the handler and returns false if not.
     */
    private boolean requireHost(String connId, IClientHandler handler) {
        GameRoom r = roomFor(connId);
        Player player = r != null ? r.getPlayerByConnId(connId) : null;
        if (player == null || !player.isHost()) {
            handler.send(OutboundMsg.error("NOT_HOST",
                    "Only the host can perform this action").toJson());
            return false;
        }
        return true;
    }

    public void onDisconnected(String connId) {
        GameRoom r = roomFor(connId);
        if (r != null) r.onConnectionLost(connId);
        else if (room != null) room.onConnectionLost(connId);
    }
}
