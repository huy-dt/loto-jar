package com.loto.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Represents a connected (or recently disconnected) player.
 * Tracks balance and transaction history in RAM.
 */
public class Player {

    private final String         id;
    private final String         token;
    private       String         name;
    private       boolean        isHost;
    private       boolean        connected;
    private       long           balance;
    private final List<LotoPage>    pages        = new ArrayList<>();
    private final List<Transaction> transactions = new ArrayList<>();

    public Player(String name, boolean isHost, long initialBalance) {
        this.id      = UUID.randomUUID().toString().substring(0, 8);
        this.token   = UUID.randomUUID().toString();
        this.name    = name;
        this.isHost  = isHost;
        this.connected = true;
        this.balance = initialBalance;
    }

    // ─── Accessors ────────────────────────────────────────────────

    public String  getId()        { return id; }
    public String  getToken()     { return token; }
    public String  getName()      { return name; }
    public boolean isHost()       { return isHost; }
    public boolean isConnected()  { return connected; }
    public long    getBalance()   { return balance; }

    public List<LotoPage> getPages() {
        return Collections.unmodifiableList(pages);
    }

    public List<Transaction> getTransactions() {
        return Collections.unmodifiableList(transactions);
    }

    // ─── Mutators ─────────────────────────────────────────────────

    public void setConnected(boolean connected) { this.connected = connected; }
    public void setHost(boolean host)           { this.isHost = host; }

    public void addPages(List<LotoPage> newPages) {
        pages.addAll(newPages);
    }

    public LotoPage getPageById(int pageId) {
        return pages.stream()
                    .filter(p -> p.getId() == pageId)
                    .findFirst()
                    .orElse(null);
    }

    // ─── Money operations ─────────────────────────────────────────

    /**
     * Deducts {@code amount} from balance.
     * Returns false (and does nothing) if balance is insufficient.
     */
    public boolean deduct(long amount, String note) {
        if (balance < amount) return false;
        balance -= amount;
        transactions.add(new Transaction(Transaction.Type.BUY_PAGE, -amount, balance, note));
        return true;
    }

    /** Adds prize money to balance. */
    public void addPrize(long amount, String note) {
        balance += amount;
        transactions.add(new Transaction(Transaction.Type.WIN_PRIZE, amount, balance, note));
    }

    /** Host manually tops up balance. */
    public void topUp(long amount, String note) {
        balance += amount;
        transactions.add(new Transaction(Transaction.Type.TOPUP, amount, balance, note));
    }

    /** Refunds amount (e.g. game cancelled). */
    public void refund(long amount, String note) {
        balance += amount;
        transactions.add(new Transaction(Transaction.Type.REFUND, amount, balance, note));
    }
}
