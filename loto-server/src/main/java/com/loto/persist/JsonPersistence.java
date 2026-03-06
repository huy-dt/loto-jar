package com.loto.persist;

import com.loto.model.LotoPage;
import com.loto.model.Player;
import com.loto.model.Transaction;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Saves and loads the full game state to a JSON file on disk.
 *
 * <h3>File layout</h3>
 * <pre>
 * {
 *   "savedAt": 1710000000000,
 *   "roomId":  "...",
 *   "gameState": "PLAYING",
 *   "jackpot": 150000,
 *   "drawnNumbers": [12, 47, 3, ...],
 *   "bannedIds": ["alice", ...],
 *   "players": [
 *     {
 *       "id": "...", "token": "...", "name": "...",
 *       "isHost": true, "balance": 80000,
 *       "pages": [ { "id": 1, "grid": [[1,null,...], ...] }, ... ],
 *       "transactions": [ { "timestamp":..., "type":"TOPUP", "amount":100000,
 *                           "balanceAfter":100000, "note":"..." }, ... ]
 *     }, ...
 *   ]
 * }
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>
 *   JsonPersistence persist = new JsonPersistence("loto_save.json");
 *
 *   // Save
 *   persist.save(snapshot);
 *
 *   // Load (returns null if file does not exist or is corrupted)
 *   GameSnapshot snap = persist.load();
 * </pre>
 */
public class JsonPersistence {

    private final Path filePath;

    public JsonPersistence(String filePath) {
        this.filePath = Paths.get(filePath);
    }

    // ─── Save ─────────────────────────────────────────────────────

    /**
     * Atomically writes the snapshot to disk.
     * Uses a temp-file + rename so a crash during write never corrupts the save.
     */
    public synchronized void save(GameSnapshot snap) {
        if (snap == null) return;
        try {
            JSONObject root = serializeSnapshot(snap);

            // Write to temp file then rename for atomic replace
            Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(2), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, filePath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException e) {
            System.err.println("[Persist] Save failed: " + e.getMessage());
        }
    }

    // ─── Load ─────────────────────────────────────────────────────

    /**
     * Loads and deserializes the snapshot from disk.
     *
     * @return the saved snapshot, or {@code null} if the file does not exist
     *         or cannot be parsed.
     */
    public synchronized GameSnapshot load() {
        if (!Files.exists(filePath)) return null;
        try {
            String raw  = Files.readString(filePath, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(raw);
            return deserializeSnapshot(root);
        } catch (Exception e) {
            System.err.println("[Persist] Load failed (ignoring): " + e.getMessage());
            return null;
        }
    }

    /** Deletes the save file (e.g. after game ends permanently). */
    public void delete() {
        try { Files.deleteIfExists(filePath); }
        catch (IOException e) { System.err.println("[Persist] Delete failed: " + e.getMessage()); }
    }

    public Path getFilePath() { return filePath; }

    // ─── Serialization ────────────────────────────────────────────

    private JSONObject serializeSnapshot(GameSnapshot snap) {
        JSONObject root = new JSONObject();
        root.put("savedAt",      Instant.now().toEpochMilli());
        root.put("roomId",       snap.roomId);
        root.put("gameState",    snap.gameState);
        root.put("jackpot",      snap.jackpot);
        root.put("drawnNumbers", new JSONArray(snap.drawnNumbers));
        root.put("bannedIds",    new JSONArray(snap.bannedIds));
        root.put("winnerIds",    new JSONArray(snap.winnerIds));
        root.put("drawIntervalMs",   snap.drawIntervalMs);
        root.put("pricePerPage",     snap.pricePerPage);
        root.put("autoResetDelayMs", snap.autoResetDelayMs);

        JSONArray playersArr = new JSONArray();
        for (PlayerSnapshot ps : snap.players) {
            playersArr.put(serializePlayer(ps));
        }
        root.put("players", playersArr);
        return root;
    }

    private JSONObject serializePlayer(PlayerSnapshot ps) {
        JSONObject obj = new JSONObject();
        obj.put("id",      ps.id);
        obj.put("token",   ps.token);
        obj.put("name",    ps.name);
        obj.put("isHost",  ps.isHost);
        obj.put("balance", ps.balance);

        // Pages
        JSONArray pagesArr = new JSONArray();
        for (LotoPage page : ps.pages) {
            JSONObject pageObj = new JSONObject();
            pageObj.put("id", page.getId());
            JSONArray rows = new JSONArray();
            for (List<Integer> row : page.getPage()) {
                JSONArray rowArr = new JSONArray();
                for (Integer cell : row) {
                    if (cell == null) rowArr.put(JSONObject.NULL);
                    else              rowArr.put(cell);
                }
                rows.put(rowArr);
            }
            pageObj.put("grid", rows);
            pagesArr.put(pageObj);
        }
        obj.put("pages", pagesArr);

        // Transactions
        JSONArray txArr = new JSONArray();
        for (Transaction tx : ps.transactions) {
            JSONObject t = new JSONObject();
            t.put("timestamp",    tx.getTimestamp());
            t.put("type",         tx.getType().name());
            t.put("amount",       tx.getAmount());
            t.put("balanceAfter", tx.getBalanceAfter());
            t.put("note",         tx.getNote() != null ? tx.getNote() : "");
            txArr.put(t);
        }
        obj.put("transactions", txArr);
        return obj;
    }

    // ─── Deserialization ──────────────────────────────────────────

    private GameSnapshot deserializeSnapshot(JSONObject root) {
        GameSnapshot snap = new GameSnapshot();
        snap.roomId      = root.optString("roomId", "");
        snap.gameState   = root.optString("gameState", "WAITING");
        snap.jackpot     = root.optLong("jackpot", 0);

        JSONArray drawn = root.optJSONArray("drawnNumbers");
        if (drawn != null) {
            for (int i = 0; i < drawn.length(); i++) snap.drawnNumbers.add(drawn.getInt(i));
        }

        JSONArray banned = root.optJSONArray("bannedIds");
        if (banned != null) {
            for (int i = 0; i < banned.length(); i++) snap.bannedIds.add(banned.getString(i));
        }
        snap.drawIntervalMs   = root.optInt("drawIntervalMs",   -1);
        snap.pricePerPage     = root.optLong("pricePerPage",    -1);
        snap.autoResetDelayMs = root.optInt("autoResetDelayMs", -1);
        JSONArray winners = root.optJSONArray("winnerIds");
        if (winners != null) {
            for (int i = 0; i < winners.length(); i++) snap.winnerIds.add(winners.getString(i));
        }

        JSONArray players = root.optJSONArray("players");
        if (players != null) {
            for (int i = 0; i < players.length(); i++) {
                snap.players.add(deserializePlayer(players.getJSONObject(i)));
            }
        }
        return snap;
    }

    private PlayerSnapshot deserializePlayer(JSONObject obj) {
        PlayerSnapshot ps = new PlayerSnapshot();
        ps.id      = obj.getString("id");
        ps.token   = obj.getString("token");
        ps.name    = obj.getString("name");
        ps.isHost  = obj.optBoolean("isHost", false);
        ps.balance = obj.optLong("balance", 0);

        // Pages
        JSONArray pagesArr = obj.optJSONArray("pages");
        if (pagesArr != null) {
            for (int i = 0; i < pagesArr.length(); i++) {
                JSONObject pageObj = pagesArr.getJSONObject(i);
                int pageId = pageObj.getInt("id");
                JSONArray rows = pageObj.getJSONArray("grid");
                List<List<Integer>> grid = new ArrayList<>();
                for (int r = 0; r < rows.length(); r++) {
                    JSONArray row = rows.getJSONArray(r);
                    List<Integer> rowList = new ArrayList<>();
                    for (int c = 0; c < row.length(); c++) {
                        Object val = row.get(c);
                        rowList.add(val instanceof Integer ? (Integer) val : null);
                    }
                    grid.add(rowList);
                }
                ps.pages.add(new LotoPage(pageId, grid));
            }
        }

        // Transactions
        JSONArray txArr = obj.optJSONArray("transactions");
        if (txArr != null) {
            for (int i = 0; i < txArr.length(); i++) {
                JSONObject t = txArr.getJSONObject(i);
                Transaction.Type type = Transaction.Type.valueOf(t.getString("type"));
                ps.transactions.add(new RestoredTransaction(
                        t.getLong("timestamp"),
                        type,
                        t.getLong("amount"),
                        t.getLong("balanceAfter"),
                        t.optString("note", "")));
            }
        }
        return ps;
    }

    // ─── Snapshot DTOs ────────────────────────────────────────────

    /** Full room state snapshot — passed to save() / returned by load(). */
    public static class GameSnapshot {
        public String             roomId      = "";
        public String             gameState   = "WAITING";
        public long               jackpot     = 0;
        public List<Integer>      drawnNumbers = new ArrayList<>();
        public Set<String>        bannedIds    = new LinkedHashSet<>();
        public List<String>       winnerIds    = new ArrayList<>();
        public int                drawIntervalMs  = -1;  // -1 = use config default (200ms min is valid)
        public long               pricePerPage    = -1;  // -1 = use config default (0 = free is valid)
        public int                autoResetDelayMs = -1; // -1 = use config default (0 = disabled is valid)
        public List<PlayerSnapshot> players    = new ArrayList<>();
    }

    public static class PlayerSnapshot {
        public String             id;
        public String             token;
        public String             name;
        public boolean            isHost;
        public long               balance;
        public List<LotoPage>     pages        = new ArrayList<>();
        public List<Transaction>  transactions = new ArrayList<>();
    }

    /**
     * A Transaction whose timestamp is restored from persisted data
     * rather than set to Instant.now().
     */
    public static class RestoredTransaction extends Transaction {
        private final long savedTimestamp;

        public RestoredTransaction(long timestamp, Type type, long amount,
                                   long balanceAfter, String note) {
            super(type, amount, balanceAfter, note);
            this.savedTimestamp = timestamp;
        }

        @Override
        public long getTimestamp() { return savedTimestamp; }
    }
}
