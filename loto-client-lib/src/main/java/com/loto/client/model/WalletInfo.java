package com.loto.client.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Client-side wallet snapshot received from server. */
public class WalletInfo {

    public static class TxRecord {
        public final long   timestamp;
        public final String type;
        public final long   amount;
        public final long   balanceAfter;
        public final String note;

        public TxRecord(long timestamp, String type, long amount,
                        long balanceAfter, String note) {
            this.timestamp    = timestamp;
            this.type         = type;
            this.amount       = amount;
            this.balanceAfter = balanceAfter;
            this.note         = note;
        }
    }

    private       long           balance;
    private final List<TxRecord> transactions = new ArrayList<>();

    public WalletInfo(long balance) {
        this.balance = balance;
    }

    public void update(long balance, TxRecord latest) {
        this.balance = balance;
        if (latest != null) transactions.add(0, latest); // newest first
    }

    public void setHistory(long balance, List<TxRecord> history) {
        this.balance = balance;
        transactions.clear();
        transactions.addAll(history);
    }

    public long           getBalance()      { return balance; }
    public List<TxRecord> getTransactions() { return Collections.unmodifiableList(transactions); }
}
