package com.loto.client.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side representation of a loto page.
 *
 * grid: 9×9, null = ô trống, Integer = số
 * marked: ô nào đã được đánh dấu (server đã quay số đó)
 */
public class ClientPage {

    private final int                       id;
    private final List<List<Integer>>       grid;     // 9 rows × 9 cols, null = blank
    private final boolean[][]               marked;   // true = đã được đánh
    private       int                       markedCount = 0;

    // ─── Factory from server JSON grid ────────────────────────────

    public ClientPage(int id, List<List<Integer>> grid) {
        this.id     = id;
        this.grid   = grid;
        this.marked = new boolean[grid.size()][grid.isEmpty() ? 9 : grid.get(0).size()];
    }

    // ─── Auto-mark ────────────────────────────────────────────────

    /**
     * Marks all cells matching {@code number}.
     * Returns true if at least one cell was marked.
     */
    public boolean mark(int number) {
        boolean hit = false;
        for (int r = 0; r < grid.size(); r++) {
            List<Integer> row = grid.get(r);
            for (int c = 0; c < row.size(); c++) {
                Integer cell = row.get(c);
                if (cell != null && cell == number && !marked[r][c]) {
                    marked[r][c] = true;
                    markedCount++;
                    hit = true;
                }
            }
        }
        return hit;
    }

    /**
     * Re-marks all cells from a list of already-drawn numbers.
     * Used on reconnect to restore state.
     */
    public void markAll(List<Integer> drawnNumbers) {
        // reset first
        for (boolean[] row : marked) java.util.Arrays.fill(row, false);
        markedCount = 0;
        for (int num : drawnNumbers) mark(num);
    }

    // ─── Win detection ────────────────────────────────────────────

    /**
     * Returns the index of the first complete row (all 5 numbers marked),
     * or -1 if no row is complete yet.
     */
    public int getWinningRowIndex() {
        for (int r = 0; r < grid.size(); r++) {
            if (isRowComplete(r)) return r;
        }
        return -1;
    }

    public boolean hasWon() {
        return getWinningRowIndex() >= 0;
    }

    private boolean isRowComplete(int rowIndex) {
        List<Integer> row = grid.get(rowIndex);
        for (int c = 0; c < row.size(); c++) {
            Integer cell = row.get(c);
            if (cell != null && !marked[rowIndex][c]) return false;
        }
        // must have at least one non-null cell
        return row.stream().anyMatch(v -> v != null);
    }

    // ─── Accessors ────────────────────────────────────────────────

    public int getId()                        { return id; }
    public List<List<Integer>> getGrid()      { return Collections.unmodifiableList(grid); }
    public boolean isMarked(int row, int col) { return marked[row][col]; }
    public int getMarkedCount()               { return markedCount; }

    /** Returns all row indices that are fully complete. */
    public List<Integer> getCompletedRows() {
        List<Integer> result = new ArrayList<>();
        for (int r = 0; r < grid.size(); r++) {
            if (isRowComplete(r)) result.add(r);
        }
        return result;
    }
}
