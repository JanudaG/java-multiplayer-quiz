package quiz.gui.model;

/**
 * The single source of truth for "where" the client currently is.
 * The UI uses it to decide which screen to show.
 */
public enum GamePhase {
    /** No socket is open. */
    DISCONNECTED,
    /** The socket is being opened. */
    CONNECTING,
    /** JOIN was sent; waiting for JOINED or ERROR. */
    JOINING,
    /** Joined the lobby; no game is running. */
    LOBBY,
    /** GAME_STARTED received; the first QUESTION has not arrived yet. */
    GAME_STARTING,
    /** A QUESTION was received and the round is open. */
    QUESTION_ACTIVE,
    /** ROUND_RESULT received; waiting for the next QUESTION or GAME_OVER. */
    ROUND_ENDED,
    /** GAME_OVER received; the server is back in lobby mode (Play Again is possible). */
    GAME_OVER
}
