package com.loto.core;

import com.loto.model.Player;
import com.loto.persist.JsonPersistence;

import java.util.concurrent.TimeUnit;

/**
 * Handles debounced and immediate persistence for {@link GameRoom}.
 */
public class GameRoomPersistence {

    private final GameRoomState s;

    GameRoomPersistence(GameRoomState s) { this.s = s; }

    /** Schedules a save 500ms from now, cancelling any pending save. */
    public void saveState() {
        if (s.persistence == null) return;
        if (s.saveDebounce != null && !s.saveDebounce.isDone()) s.saveDebounce.cancel(false);
        s.saveDebounce = s.scheduler.schedule(this::saveStateNow,
                GameRoomState.SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /** Bypasses debounce — writes immediately. Used for critical state changes. */
    public void saveStateNow() {
        if (s.persistence == null) return;
        JsonPersistence.GameSnapshot snap = buildSnapshot();
        s.persistence.save(snap);
    }

    private JsonPersistence.GameSnapshot buildSnapshot() {
        JsonPersistence.GameSnapshot snap = new JsonPersistence.GameSnapshot();
        snap.roomId            = s.roomId;
        snap.gameState         = s.state.name();
        snap.jackpot           = s.jackpot;
        snap.drawnNumbers.addAll(s.drawnNumbers);
        snap.bannedIds.addAll(s.bannedIds);
        snap.winnerIds.addAll(s.winnerIds);
        snap.drawIntervalMs    = s.currentDrawIntervalMs;
        snap.pricePerPage      = s.currentPricePerPage;
        snap.autoResetDelayMs  = s.currentAutoResetDelayMs;

        for (Player p : s.playersByToken.values()) {
            JsonPersistence.PlayerSnapshot ps = new JsonPersistence.PlayerSnapshot();
            ps.id           = p.getId();
            ps.token        = p.getToken();
            ps.name         = p.getName();
            ps.isHost       = p.isHost();
            ps.balance      = p.getBalance();
            ps.pages.addAll(p.getPages());
            ps.transactions.addAll(p.getTransactions());
            snap.players.add(ps);
        }
        return snap;
    }
}
