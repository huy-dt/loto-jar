package com.loto.core;

import com.loto.callback.LotoServerCallback;
import com.loto.model.BotPlayer;
import com.loto.model.Player;
import com.loto.model.PlayerInfo;
import com.loto.network.IClientHandler;
import com.loto.persist.JsonPersistence;
import com.loto.protocol.OutboundMsg;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Public API surface for a single Loto game room.
 *
 * <p>Delegates to sub-managers:
 * <ul>
 *   <li>{@link GameRoomState}         — shared mutable state</li>
 *   <li>{@link GameRoomBroadcaster}   — outbound messaging</li>
 *   <li>{@link GameRoomPersistence}   — save / load</li>
 *   <li>{@link GameRoomPlayerManager} — player lifecycle</li>
 *   <li>{@link GameRoomGameFlow}      — game logic + auto-start/reset</li>
 * </ul>
 */
public class GameRoom {

    private final GameRoomState         st;
    private final GameRoomBroadcaster   broadcaster;
    private final GameRoomPersistence   persist;
    private final GameRoomPlayerManager players;
    private final GameRoomGameFlow      flow;

    public GameRoom(String roomId, ServerConfig config) {
        this.st          = new GameRoomState(roomId, config);
        this.broadcaster = new GameRoomBroadcaster(st);
        this.persist     = new GameRoomPersistence(st);
        this.players     = new GameRoomPlayerManager(st, broadcaster, persist, this);
        this.flow        = new GameRoomGameFlow(st, broadcaster, persist);
    }

    // ─── Configuration ────────────────────────────────────────────

    public void setCallback(LotoServerCallback cb)  { st.callback    = cb; }
    public void setPersistence(JsonPersistence p)   { st.persistence = p; }

    public BotManager getBotManager() {
        if (st.botManagerRef == null) st.botManagerRef = new BotManager(this);
        return st.botManagerRef;
    }

    // ─── Snapshot ─────────────────────────────────────────────────

    public synchronized void restoreFromSnapshot(JsonPersistence.GameSnapshot snap) {
        st.restoreFromSnapshot(snap);
    }

    public void saveState()    { persist.saveState(); }
    public void saveStateNow() { persist.saveStateNow(); }

    // ─── Player lifecycle ─────────────────────────────────────────

    public synchronized Player join(String connId, String name, IClientHandler h)           { return players.join(connId, name, h); }
    public synchronized Player joinBot(String connId, BotPlayer bot, IClientHandler h)       { return players.joinBot(connId, bot, h); }
    public synchronized void   removeBot(String connId, BotPlayer bot)                       { players.removeBot(connId, bot); }
    public synchronized void   buyPagesBot(String connId, int count)                         { players.buyPagesBot(connId, count); }
    public synchronized Player reconnect(String connId, String token, IClientHandler h)      { return players.reconnect(connId, token, h); }
    public synchronized void   onConnectionLost(String connId)                               { players.onConnectionLost(connId); }
    public synchronized void   buyPages(String connId, int count)                            { players.buyPages(connId, count); }
    public synchronized void   sendWalletHistory(String connId)                              { players.sendWalletHistory(connId); }
    public synchronized void   topUp(String playerId, long amount, String note)              { players.topUp(playerId, amount, note); }

    public synchronized void claimWinBot(String connId, int pageId) {
        Player p = st.playersByConnId.get(connId);
        if (p instanceof BotPlayer) flow.claimWin(connId, pageId);
    }

    // ─── Voting ───────────────────────────────────────────────────

    public synchronized void voteStart(String connId)  { flow.voteStart(connId); }

    // ─── Game flow ────────────────────────────────────────────────

    public synchronized void serverStart()                { flow.serverStart(); }
    public synchronized void serverEnd(String reason)     { flow.serverEnd(reason); }
    public synchronized void serverCancel(String reason)  { flow.cancelGame(reason != null ? reason : "Server hủy game"); }
    public synchronized void cancelGame(String reason)    { flow.cancelGame(reason); }
    public synchronized void pauseGame()                  { flow.pauseGame(); }
    public synchronized void resumeGame()                 { flow.resumeGame(); }
    public synchronized void reset()                      { flow.reset(); }

    // ─── Win management ───────────────────────────────────────────

    public synchronized void claimWin(String connId, int pageId)         { flow.claimWin(connId, pageId); }
    public synchronized void confirmWin(String playerId, int pageId)     { flow.confirmWin(playerId, pageId); }
    public synchronized void rejectWin(String playerId, int pageId)      { flow.rejectWin(playerId, pageId); }

    // ─── Kick / Ban ───────────────────────────────────────────────

    public synchronized void kick(String playerId, String reason)  { players.kick(playerId, reason); }
    public synchronized void ban(String playerId, String reason)   { players.ban(playerId, reason); }
    public synchronized void banIp(String ip)                      { players.banIp(ip); }
    public synchronized void unbanIp(String ip)                    { players.unbanIp(ip); }
    public synchronized void unban(String name)                    { players.unban(name); }
    public Set<String>       getBannedIds()                        { return players.getBannedIds(); }
    public Set<String>       getBannedIps()                        { return players.getBannedIps(); }

    // ─── Settings ─────────────────────────────────────────────────

    public synchronized void setDrawInterval(int ms)         { flow.setDrawInterval(ms); }
    public int  getCurrentDrawIntervalMs()                   { return st.currentDrawIntervalMs; }

    public synchronized void setPricePerPage(long price)     { flow.setPricePerPage(price); }
    public boolean canChangePricePerPage()                   { return flow.canChangePricePerPage(); }
    public long    getCurrentPricePerPage()                  { return st.currentPricePerPage; }
    /** Giá pending sẽ áp dụng từ ván sau (-1 nếu không có). */
    public long    getPendingPricePerPage()                  { return st.pendingPricePerPage; }

    public synchronized void setAutoResetDelay(int delayMs)  { flow.setAutoResetDelay(delayMs); }
    public synchronized void scheduleAutoReset(int delayMs)  { flow.scheduleAutoReset(delayMs); }
    public synchronized void cancelAutoReset()               { flow.cancelAutoReset(); }
    public synchronized void cancelAutoStart()               { flow.cancelAutoStart(); }
    public int  getCurrentAutoResetDelayMs()                 { return st.currentAutoResetDelayMs; }

    /** Changes the auto-start delay at runtime (0 = disable). */
    public synchronized void setAutoStartMs(int ms)          { flow.setAutoStartMs(ms); }
    public int  getCurrentAutoStartMs()                      { return st.currentAutoStartMs; }

    // ─── Admin auth ───────────────────────────────────────────────

    public synchronized boolean adminAuth(String connId, String token, IClientHandler h) {
        if (token != null && token.equals(st.config.adminToken)) {
            st.adminConnIds.add(connId);
            h.send(OutboundMsg.adminAuthOk().toJson());
            System.out.println("[GameRoom] Admin authenticated: connId=" + connId);
            return true;
        }
        h.send(OutboundMsg.error("AUTH_FAILED", "Invalid admin token").toJson());
        return false;
    }

    public boolean isAdmin(String connId)          { return st.adminConnIds.contains(connId); }
    public void    removeAdminSession(String connId) { st.adminConnIds.remove(connId); }

    // ─── Broadcast helpers ────────────────────────────────────────

    public void broadcast(String json, String excludeConnId)  { broadcaster.broadcast(json, excludeConnId); }
    public void sendTo(String connId, String json)            { broadcaster.sendTo(connId, json); }

    // ─── Room snapshot ────────────────────────────────────────────

    public synchronized List<PlayerInfo> getRoomSnapshot() {
        return st.playersByToken.values().stream()
                .map(PlayerInfo::new)
                .collect(Collectors.toList());
    }

    public long        getJackpot()   { return st.jackpot; }
    public GameState   getState()     { return st.state; }
    public String      getRoomId()    { return st.roomId; }
    public ServerConfig getConfig()   { return st.config; }

    // ─── Internal hooks ───────────────────────────────────────────

    /** Called by PlayerManager after join/disconnect to re-evaluate auto-start. */
    void checkAutoStart() { flow.checkAutoStart(); }

    public Player getPlayerByConnId(String connId) { return st.playersByConnId.get(connId); }

    // ─── Shutdown ─────────────────────────────────────────────────

    public void shutdown() {
        flow.stopDrawing();
        if (st.botManagerRef != null) st.botManagerRef.shutdown();
        st.scheduler.shutdownNow();
    }
}
