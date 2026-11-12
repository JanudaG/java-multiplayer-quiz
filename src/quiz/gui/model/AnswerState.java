package quiz.gui.model;

/**
 * The local player's answer status for the current round.
 */
public enum AnswerState {
    /** The player may still answer. */
    NOT_ANSWERED,
    /** ANSWER was sent; waiting for ANSWER_ACCEPTED. No further answer is allowed. */
    SUBMITTED,
    /** The server confirmed the answer with ANSWER_ACCEPTED. */
    ACCEPTED,
    /** The round closed (ROUND_RESULT or GAME_OVER) without an accepted answer. */
    MISSED
}
