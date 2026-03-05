package com.loto.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LotoPage {

    // ─── Constants ────────────────────────────────────────────────
    private static final int COLS            = 9;
    private static final int ROWS            = 9;
    private static final int MAX_NUM_PER_COL = 10;
    private static final int N_NUMS          = 5;

    // ─── Fields ───────────────────────────────────────────────────
    private final int                  id;
    private       List<List<Integer>>  page;
    private final List<List<Integer>>  defPage;

    // ─── Constructors ─────────────────────────────────────────────

    /** Restore a page from persisted data (e.g. from JSON deserialization). */
    public LotoPage(int id, List<List<Integer>> page) {
        this.id      = id;
        this.defPage = generateDefPage();
        this.page    = page;
    }

    /** Generate a new random page. */
    public LotoPage(int id) {
        this.id      = id;
        this.defPage = generateDefPage();
        makePage();
    }

    // ─── Public API ───────────────────────────────────────────────

    public int getId() {
        return id;
    }

    public List<List<Integer>> getPage() {
        return Collections.unmodifiableList(page);
    }

    public void makePage() {
        page = new ArrayList<>();
        for (int row = 0; row < ROWS; row++) {
            page.add(buildRow(row));
        }
    }

    /**
     * Returns true if all non-null numbers in ANY row of this page
     * are contained in {@code drawnNumbers}.
     */
    public boolean hasWinningRow(List<Integer> drawnNumbers) {
        for (int row = 0; row < ROWS; row++) {
            if (isWinningRow(row, drawnNumbers)) return true;
        }
        return false;
    }

    // ─── Private helpers ──────────────────────────────────────────

    private List<List<Integer>> generateDefPage() {
        List<List<Integer>> defPage = new ArrayList<>();
        for (int col = 0; col < COLS; col++) {
            List<Integer> column = new ArrayList<>();
            for (int num = 0; num < MAX_NUM_PER_COL; num++) {
                column.add(col * MAX_NUM_PER_COL + num);
            }
            if (col == 0)          column.remove(Integer.valueOf(0));
            if (col == COLS - 1)   column.add(COLS * MAX_NUM_PER_COL);
            Collections.shuffle(column);
            defPage.add(column);
        }
        return defPage;
    }

    private List<Integer> buildRow(int rowIndex) {
        List<Integer> row = new ArrayList<>(Collections.nCopies(COLS, null));

        List<Integer> colIndexes = new ArrayList<>();
        for (int col = 0; col < COLS; col++) colIndexes.add(col);
        Collections.shuffle(colIndexes);

        List<Integer> chosenCols = new ArrayList<>(colIndexes.subList(0, N_NUMS));
        Collections.sort(chosenCols);

        for (int col : chosenCols) {
            row.set(col, defPage.get(col).get(rowIndex));
        }
        return row;
    }

    private boolean isWinningRow(int rowIndex, List<Integer> drawnNumbers) {
        List<Integer> numbers = getRowNumbers(rowIndex);
        return numbers.size() == N_NUMS && drawnNumbers.containsAll(numbers);
    }

    private List<Integer> getRowNumbers(int rowIndex) {
        List<Integer> numbers = new ArrayList<>();
        for (Integer cell : page.get(rowIndex)) {
            if (cell != null) numbers.add(cell);
        }
        return numbers;
    }
}
