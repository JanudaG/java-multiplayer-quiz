package quiz.gui.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The client's view of the game.
 *
 * <p>This class only knows about model types. It does NOT depend on the
 * protocol package: the controller (Phase 3C) parses server lines and then
 * calls the matching {@code on...} method here.</p>
 *
 * <p>Thread safety: this class is NOT thread-safe. It must only be used
 * from one thread (the Swing Event Dispatch Thread).</p>
 *
 * <p>The server is authoritative. This class never closes a round on a local
 * timer; only {@link #onRoundResult} or {@link #onGameOver} do that.</p>
 */
public final class GameState {

    /** Matches the minimum used by the server's START check. */
    public static final int MIN_PLAYERS_TO_START = 2;

    private GamePhase phase = GamePhase.DISCONNECTED;

    private String requestedUsername;
    private String currentPlayer;
    private List<String> players = List.of();
    private String host;

    private QuestionData currentQuestion;
    private AnswerState answerState = AnswerState.NOT_ANSWERED;
    private AnswerOption selectedAnswer;
    private AnswerOption correctAnswer;

    private Leaderboard leaderboard = Leaderboard.empty();
    private List<String> winners = List.of();
    private int bestScore;

    private String lastInfo;
    private String lastError;
    private boolean joinRejected;

    // ------------------------------------------------------------------
    // Local intents (called by the controller)
    // ------------------------------------------------------------------

    /** A new connection attempt starts: everything from the previous session is cleared. */
    public void markConnecting() {
        resetSession();
        lastError = null;
        joinRejected = false;
        phase = GamePhase.CONNECTING;
    }

    /** JOIN has been sent with the given username. */
    public void markJoining(String requestedUsername) {
        this.requestedUsername = Objects.requireNonNull(requestedUsername, "requestedUsername");
        joinRejected = false;
        phase = GamePhase.JOINING;
    }

    /**
     * The connection is closed. All game data is cleared, but the last error
     * and the join-rejected flag are kept so the UI can explain what happened.
     * If no error was recorded yet, {@code reason} (if not null) becomes the last error.
     */
    public void markDisconnected(String reason) {
        resetSession();
        if (lastError == null) {
            lastError = reason;
        }
        phase = GamePhase.DISCONNECTED;
    }

    /**
     * Records that the player chose an answer and it is about to be sent.
     *
     * @return true if the answer may be sent; false if answering is not allowed
     *         (wrong phase, or an answer was already submitted this round)
     */
    public boolean markAnswerSubmitted(AnswerOption option) {
        if (option == null || !canSubmitAnswer()) {
            return false;
        }
        selectedAnswer = option;
        answerState = AnswerState.SUBMITTED;
        return true;
    }

    // ------------------------------------------------------------------
    // Server events (called by the controller after parsing a line).
    // Every event is ignored while DISCONNECTED (late lines after a disconnect).
    // ------------------------------------------------------------------

    /** JOINED|username */
    public void onJoined(String username) {
        if (isIgnoringServerEvents()) {
            return;
        }
        currentPlayer = Objects.requireNonNull(username, "username");
        lastError = null;
        joinRejected = false;
        phase = GamePhase.LOBBY;
    }

    /** INFO|message */
    public void onInfo(String text) {
        if (isIgnoringServerEvents()) {
            return;
        }
        lastInfo = text;
    }

    /** PLAYERS|name1,name2,... (replaces the previous list) */
    public void onPlayers(List<String> usernames) {
        if (isIgnoringServerEvents()) {
            return;
        }
        players = List.copyOf(usernames);
    }

    /** HOST|username (the host can change at any time, even mid-game) */
    public void onHost(String username) {
        if (isIgnoringServerEvents()) {
            return;
        }
        host = Objects.requireNonNull(username, "username");
    }

    /**
     * ERROR|message. Does NOT change the answer state: after a rejected answer
     * the player stays SUBMITTED, so another answer cannot be sent this round.
     */
    public void onError(String text) {
        if (isIgnoringServerEvents()) {
            return;
        }
        lastError = text;
        if (phase == GamePhase.JOINING) {
            joinRejected = true;
        }
    }

    /** GAME_STARTED|... clears all results of the previous game. */
    public void onGameStarted() {
        if (isIgnoringServerEvents()) {
            return;
        }
        clearGameData();
        lastError = null;
        phase = GamePhase.GAME_STARTING;
    }

    /** QUESTION|... opens a new round and resets the answer state. */
    public void onQuestion(QuestionData question) {
        if (isIgnoringServerEvents()) {
            return;
        }
        currentQuestion = Objects.requireNonNull(question, "question");
        answerState = AnswerState.NOT_ANSWERED;
        selectedAnswer = null;
        correctAnswer = null;
        phase = GamePhase.QUESTION_ACTIVE;
    }

    /** ANSWER_ACCEPTED|round. Ignored if it is for another round or nothing was submitted. */
    public void onAnswerAccepted(int round) {
        if (isIgnoringServerEvents() || !isCurrentRound(round)) {
            return;
        }
        if (answerState == AnswerState.SUBMITTED) {
            answerState = AnswerState.ACCEPTED;
        }
    }

    /** ROUND_RESULT|round|letter. Ignored if it is for another round. */
    public void onRoundResult(int round, AnswerOption correct) {
        if (isIgnoringServerEvents() || !isCurrentRound(round)) {
            return;
        }
        correctAnswer = Objects.requireNonNull(correct, "correct");
        closeOpenAnswer();
        phase = GamePhase.ROUND_ENDED;
    }

    /** SCORES|... replaces (never merges with) the previous leaderboard. */
    public void onScores(Leaderboard newLeaderboard) {
        if (isIgnoringServerEvents()) {
            return;
        }
        leaderboard = Objects.requireNonNull(newLeaderboard, "newLeaderboard");
    }

    /** GAME_OVER|winners|bestScore. Works even if no ROUND_RESULT came first. */
    public void onGameOver(List<String> winnerNames, int bestScore) {
        if (isIgnoringServerEvents()) {
            return;
        }
        winners = List.copyOf(winnerNames);
        this.bestScore = bestScore;
        closeOpenAnswer();
        phase = GamePhase.GAME_OVER;
    }

    // ------------------------------------------------------------------
    // Queries for the UI
    // ------------------------------------------------------------------

    public GamePhase getPhase() {
        return phase;
    }

    /** True once the socket is open (from JOINING onwards). */
    public boolean isConnected() {
        return phase != GamePhase.DISCONNECTED && phase != GamePhase.CONNECTING;
    }

    /** The confirmed username (from JOINED), or null before joining. */
    public String getCurrentPlayer() {
        return currentPlayer;
    }

    /** The username sent with JOIN, or null. */
    public String getRequestedUsername() {
        return requestedUsername;
    }

    /** The players in the lobby, in server order. Cannot be modified. */
    public List<String> getPlayers() {
        return players;
    }

    public String getHost() {
        return host;
    }

    /** True if the local player is the current host. */
    public boolean isHost() {
        return currentPlayer != null && currentPlayer.equals(host);
    }

    public QuestionData getCurrentQuestion() {
        return currentQuestion;
    }

    public AnswerState getAnswerState() {
        return answerState;
    }

    public AnswerOption getSelectedAnswer() {
        return selectedAnswer;
    }

    /** The correct option of the current round, or null until ROUND_RESULT arrives. */
    public AnswerOption getCorrectAnswer() {
        return correctAnswer;
    }

    public Leaderboard getLeaderboard() {
        return leaderboard;
    }

    /** The winners from GAME_OVER (may contain several names for a tie). Cannot be modified. */
    public List<String> getWinners() {
        return winners;
    }

    public int getBestScore() {
        return bestScore;
    }

    public boolean isGameOver() {
        return phase == GamePhase.GAME_OVER;
    }

    /** True if the local player is one of the winners. */
    public boolean isWinner() {
        return currentPlayer != null && winners.contains(currentPlayer);
    }

    /** True if more than one player shares the best score. */
    public boolean isTie() {
        return winners.size() > 1;
    }

    public String getLastInfo() {
        return lastInfo;
    }

    public String getLastError() {
        return lastError;
    }

    /** True if the server answered our JOIN with an ERROR. */
    public boolean isJoinRejected() {
        return joinRejected;
    }

    /** True if the local player may send START now. The server still has the final say. */
    public boolean canStartGame() {
        return startBlockedReason().isEmpty();
    }

    /**
     * Explains why START is not allowed, using the same wording as the server,
     * or returns an empty Optional if START is allowed.
     */
    public Optional<String> startBlockedReason() {
        switch (phase) {
            case LOBBY, GAME_OVER -> {
                // a game can be started (or Play Again) from these phases
            }
            case GAME_STARTING, QUESTION_ACTIVE, ROUND_ENDED -> {
                return Optional.of("Game already running");
            }
            default -> {
                return Optional.of("Not connected to the lobby");
            }
        }
        if (!isHost()) {
            return Optional.of("Only the host can start");
        }
        if (players.size() < MIN_PLAYERS_TO_START) {
            return Optional.of("At least two players are required");
        }
        return Optional.empty();
    }

    /** True if a question is open and the player has not answered it yet. */
    public boolean canSubmitAnswer() {
        return phase == GamePhase.QUESTION_ACTIVE
                && currentQuestion != null
                && answerState == AnswerState.NOT_ANSWERED;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private boolean isIgnoringServerEvents() {
        return phase == GamePhase.DISCONNECTED;
    }

    private boolean isCurrentRound(int round) {
        return currentQuestion != null && currentQuestion.round() == round;
    }

    /** An answer that was not accepted before the round closed becomes MISSED. */
    private void closeOpenAnswer() {
        if (currentQuestion != null
                && (answerState == AnswerState.NOT_ANSWERED || answerState == AnswerState.SUBMITTED)) {
            answerState = AnswerState.MISSED;
        }
    }

    /** Clears question, answers, scores and results of the current/previous game. */
    private void clearGameData() {
        currentQuestion = null;
        answerState = AnswerState.NOT_ANSWERED;
        selectedAnswer = null;
        correctAnswer = null;
        leaderboard = Leaderboard.empty();
        winners = List.of();
        bestScore = 0;
    }

    /** Clears everything that belongs to one connection (but not lastError/joinRejected). */
    private void resetSession() {
        clearGameData();
        requestedUsername = null;
        currentPlayer = null;
        players = List.of();
        host = null;
        lastInfo = null;
    }
}
