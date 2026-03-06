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

            case ADMIN_AUTH: {
                // { "type": "ADMIN_AUTH", "payload": { "token": "secret" } }
                String token = msg.getString("token");
                if (token == null) {
                    handler.send(OutboundMsg.error("MISSING_TOKEN", "token is required").toJson());
                    return;
                }
                GameRoom authRoom = (_r != null) ? _r : room;
                if (authRoom == null) {
                    handler.send(OutboundMsg.error("NO_ROOM", "No room available").toJson());
                    return;
                }
                authRoom.adminAuth(connId, token, handler);
                break;
            }

            case CONFIRM_WIN: {
                if (!requireAdmin(connId, _r, handler)) return;
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
                if (!requireAdmin(connId, _r, handler)) return;
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
                if (!requireAdmin(connId, _r, handler)) return;
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
                if (!requireAdmin(connId, _r, handler)) return;
                String reason = msg.getString("reason");
                _r.cancelGame(reason != null ? reason : "Admin hủy game");
                break;
            }

            case KICK: {
                if (!requireAdmin(connId, _r, handler)) return;
                String playerId = msg.getString("playerId");
                if (playerId == null) {
                    handler.send(OutboundMsg.error("MISSING_FIELDS", "playerId required").toJson());
                    return;
                }
                _r.kick(playerId, msg.getString("reason"));
                break;
            }

            case BAN: {
                if (!requireAdmin(connId, _r, handler)) return;
                String playerId = msg.getString("playerId");
                if (playerId == null) {
                    handler.send(OutboundMsg.error("MISSING_FIELDS", "playerId required").toJson());
                    return;
                }
                _r.ban(playerId, msg.getString("reason"));
                break;
            }

            case UNBAN: {
                if (!requireAdmin(connId, _r, handler)) return;
                String name = msg.getString("name");
                if (name == null) {
                    handler.send(OutboundMsg.error("MISSING_FIELDS", "name required").toJson());
                    return;
                }
                _r.unban(name);
                break;
            }

            case SET_DRAW_INTERVAL: {
                if (!requireAdmin(connId, _r, handler)) return;
                int intervalMs = msg.getInt("intervalMs", -1);
                if (intervalMs < 200) {
                    handler.send(OutboundMsg.error("INVALID_INTERVAL",
                            "intervalMs must be >= 200").toJson());
                    return;
                }
                _r.setDrawInterval(intervalMs);
                break;
            }

            case SET_PRICE_PER_PAGE: {
                if (!requireAdmin(connId, _r, handler)) return;
                long price = msg.getLong("price", -1);
                if (price < 0) {
                    handler.send(OutboundMsg.error("INVALID_PRICE",
                            "price must be >= 0").toJson());
                    return;
                }
                if (!_r.canChangePricePerPage()) {
                    handler.send(OutboundMsg.error("PRICE_LOCKED",
                            "Cannot change price while waiting and pages already purchased").toJson());
                    return;
                }
                _r.setPricePerPage(price);
                break;
            }

            case SET_AUTO_RESET: {
                if (!requireAdmin(connId, _r, handler)) return;
                int delayMs = msg.getInt("delayMs", -1);
                if (delayMs < 0) {
                    handler.send(OutboundMsg.error("INVALID_DELAY",
                            "delayMs must be >= 0 (0 = disable auto-reset)").toJson());
                    return;
                }
                _r.setAutoResetDelay(delayMs);
                break;
            }

            case SET_AUTO_START: {
                if (!requireAdmin(connId, _r, handler)) return;
                int delayMs = msg.getInt("delayMs", -1);
                if (delayMs < 0) {
                    handler.send(OutboundMsg.error("INVALID_DELAY",
                            "delayMs must be >= 0 (0 = disable auto-start)").toJson());
                    return;
                }
                _r.setAutoStartMs(delayMs);
                break;
            }

            case PAUSE_GAME: {
                if (!requireAdmin(connId, _r, handler)) return;
                if (_r.getState() != com.loto.core.GameState.PLAYING) {
                    handler.send(OutboundMsg.error("INVALID_STATE",
                            "Game must be PLAYING to pause").toJson());
                    return;
                }
                _r.pauseGame();
                break;
            }

            case RESUME_GAME: {
                if (!requireAdmin(connId, _r, handler)) return;
                if (_r.getState() != com.loto.core.GameState.PAUSED) {
                    handler.send(OutboundMsg.error("INVALID_STATE",
                            "Game must be PAUSED to resume").toJson());
                    return;
                }
                _r.resumeGame();
                break;
            }

            case GET_WALLET: {
                if (_r == null) { handler.send(OutboundMsg.error("NO_ROOM", "Not in a room").toJson()); return; }
                _r.sendWalletHistory(connId);
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

    // ─── Admin guard ──────────────────────────────────────────────

    /**
     * Returns true if the connId has been authenticated as admin (via ADMIN_AUTH).
     * Sends an ERROR to the handler and returns false otherwise.
     * Admin does NOT need to be a joined player in the room.
     */
    private boolean requireAdmin(String connId, GameRoom r, IClientHandler handler) {
        if (r != null && r.isAdmin(connId)) return true;
        handler.send(OutboundMsg.error("NOT_ADMIN",
                "Admin authentication required. Send ADMIN_AUTH with the server token first.").toJson());
        return false;
    }

    public void onDisconnected(String connId) {
        GameRoom r = roomFor(connId);
        if (r != null) r.onConnectionLost(connId);
        else if (room != null) room.onConnectionLost(connId);
    }
}
