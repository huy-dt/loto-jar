package com.loto.model;

import java.time.Instant;

/**
 * Immutable record of a single money movement for a player.
 */
public class Transaction {

    public enum Type {
        TOPUP,          // host nạp tiền thủ công
        BUY_PAGE,       // mua tờ
        WIN_PRIZE,      // thắng jackpot
        REFUND,         // hoàn tiền khi game hủy
    }

    private final long    timestamp;
    private final Type    type;
    private final long    amount;     // dương = vào, âm = ra
    private final long    balanceAfter;
    private final String  note;

    public Transaction(Type type, long amount, long balanceAfter, String note) {
        this.timestamp    = Instant.now().toEpochMilli();
        this.type         = type;
        this.amount       = amount;
        this.balanceAfter = balanceAfter;
        this.note         = note;
    }

    public long   getTimestamp()    { return timestamp; }
    public Type   getType()         { return type; }
    public long   getAmount()       { return amount; }
    public long   getBalanceAfter() { return balanceAfter; }
    public String getNote()         { return note; }

    @Override
    public String toString() {
        return String.format("[%s] %s %+d → balance=%d (%s)",
                timestamp, type, amount, balanceAfter, note);
    }
}
