package com.loto.model;

/**
 * A bot player — behaves like a normal Player but is flagged as a bot.
 * The bot's AI logic lives in BotManager; this class is purely data.
 */
public class BotPlayer extends Player {

    /** Max pages the bot will buy at the start of each game (1..maxPages random). */
    private final int maxPages;

    public BotPlayer(String name, long initialBalance, int maxPages) {
        super(name, false, initialBalance);
        this.maxPages = Math.max(1, maxPages);
        setConnected(true);
    }

    public int getMaxPages() { return maxPages; }

    @Override
    public boolean isBot() { return true; }
}
