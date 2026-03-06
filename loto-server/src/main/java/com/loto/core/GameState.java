package com.loto.core;

public enum GameState {
    /** Room created, waiting for players to join and buy pages. */
    WAITING,

    /** Players are voting to start. */
    VOTING,

    /** Numbers are being drawn. */
    PLAYING,

    /** Game is paused — draw timer stopped, can be resumed. */
    PAUSED,

    /** A winner has been confirmed, game over. */
    ENDED
}
