package com.loto.network;

import com.loto.core.GameRoom;
import com.loto.protocol.InboundMsg;
import com.loto.protocol.OutboundMsg;

/**
 * Routes parsed inbound messages to the correct GameRoom action.
 * One dispatcher is shared across all ClientHandler threads.
 */
public class MessageDispatcher {

    private final GameRoom room;

    public MessageDispatcher(GameRoom room) {
        this.room = room;
    }

    public void dispatch(String connId, InboundMsg msg, ClientHandler handler) {
        switch (msg.getType()) {

            case JOIN: {
                String name = msg.getString("name");
                if (name == null || name.trim().isEmpty()) {
                    handler.send(OutboundMsg.error("MISSING_NAME", "name is required").toJson());
                    return;
                }
                room.join(connId, name.trim(), handler);
                break;
            }

            case RECONNECT: {
                String token = msg.getString("token");
                if (token == null) {
                    handler.send(OutboundMsg.error("MISSING_TOKEN", "token is required").toJson());
                    return;
                }
                if (room.reconnect(connId, token, handler) == null) {
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
                room.buyPages(connId, count);
                break;
            }

            case VOTE_START: {
                room.voteStart(connId);
                break;
            }

            case CLAIM_WIN: {
                int pageId = msg.getInt("pageId", -1);
                if (pageId < 0) {
                    handler.send(OutboundMsg.error("MISSING_PAGE_ID", "pageId is required").toJson());
                    return;
                }
                room.claimWin(connId, pageId);
                break;
            }

            case CONFIRM_WIN: {
                String playerId = msg.getString("playerId");
                int    pageId   = msg.getInt("pageId", -1);
                if (playerId == null || pageId < 0) {
                    handler.send(OutboundMsg.error("MISSING_FIELDS", "playerId and pageId required").toJson());
                    return;
                }
                room.confirmWin(playerId, pageId);
                break;
            }

            case REJECT_WIN: {
                String playerId = msg.getString("playerId");
                int    pageId   = msg.getInt("pageId", -1);
                if (playerId == null || pageId < 0) {
                    handler.send(OutboundMsg.error("MISSING_FIELDS", "playerId and pageId required").toJson());
                    return;
                }
                room.rejectWin(playerId, pageId);
                break;
            }

            case TOPUP: {
                // { "type": "TOPUP", "payload": { "playerId": "xxx", "amount": 50000, "note": "..." } }
                String playerId = msg.getString("playerId");
                long   amount   = msg.getLong("amount", -1);
                String note     = msg.getString("note");
                if (playerId == null || amount <= 0) {
                    handler.send(OutboundMsg.error("MISSING_FIELDS", "playerId and amount > 0 required").toJson());
                    return;
                }
                room.topUp(playerId, amount, note);
                break;
            }

            case CANCEL_GAME: {
                // { "type": "CANCEL_GAME", "payload": { "reason": "..." } }
                String reason = msg.getString("reason");
                room.cancelGame(reason != null ? reason : "Host hủy game");
                break;
            }

            case GET_WALLET: {
                room.sendWalletHistory(connId);
                break;
            }

            case KICK: {
                // { "type": "KICK", "payload": { "playerId": "xxx", "reason": "..." } }
                String playerId = msg.getString("playerId");
                if (playerId == null) {
                    handler.send(OutboundMsg.error("MISSING_FIELDS", "playerId required").toJson());
                    return;
                }
                room.kick(playerId, msg.getString("reason"));
                break;
            }

            case BAN: {
                // { "type": "BAN", "payload": { "playerId": "xxx", "reason": "..." } }
                String playerId = msg.getString("playerId");
                if (playerId == null) {
                    handler.send(OutboundMsg.error("MISSING_FIELDS", "playerId required").toJson());
                    return;
                }
                room.ban(playerId, msg.getString("reason"));
                break;
            }

            case UNBAN: {
                // { "type": "UNBAN", "payload": { "name": "xxx" } }
                String name = msg.getString("name");
                if (name == null) {
                    handler.send(OutboundMsg.error("MISSING_FIELDS", "name required").toJson());
                    return;
                }
                room.unban(name);
                break;
            }

            default:
                handler.send(OutboundMsg.error("UNKNOWN_TYPE",
                        "Unhandled message type: " + msg.getType()).toJson());
        }
    }

    public void onDisconnected(String connId) {
        room.onConnectionLost(connId);
    }
}
