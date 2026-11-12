package quiz.gui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Replays the event sequences that QuizServer really sends, directly on
 * {@link GameState} (model only, no parser).
 *
 * <p>Each comment shows the server line that the event method stands for.
 * The parser + GameState end-to-end transcript test is deferred to Phase 3C,
 * when the controller that connects the two exists.</p>
 */
class GameStateTranscriptTest {

    /** The five questions of the real server, in order, with their correct letters. */
    private static final List<QuestionData> QUESTIONS = List.of(
            new QuestionData(1, 5, 15, "Which Java class listens for TCP connections?",
                    "Socket", "ServerSocket", "DatagramSocket", "Thread"),
            new QuestionData(2, 5, 15, "Which protocol provides reliable, ordered delivery?",
                    "UDP", "HTTP", "TCP", "ICMP"),
            new QuestionData(3, 5, 15, "What does an IP address identify on a network?",
                    "A network interface", "A Java method", "A file format", "A password"),
            new QuestionData(4, 5, 15, "Which Java method accepts an incoming client?",
                    "connect()", "read()", "listen()", "accept()"),
            new QuestionData(5, 5, 15, "Which Java feature allows clients to be handled concurrently?",
                    "Threads", "Packages", "Comments", "Imports"));

    private static final List<AnswerOption> CORRECT = List.of(
            AnswerOption.B, AnswerOption.C, AnswerOption.A, AnswerOption.D, AnswerOption.A);

    private GameState alice;

    @BeforeEach
    void setUp() {
        alice = new GameState();
    }

    private static Leaderboard scores(Object... nameScorePairs) {
        PlayerScore[] entries = new PlayerScore[nameScorePairs.length / 2];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = new PlayerScore((String) nameScorePairs[2 * i], (Integer) nameScorePairs[2 * i + 1]);
        }
        return Leaderboard.of(List.of(entries));
    }

    /** alice joins first (so she is host), then bob joins. */
    private void aliceAndBobInLobby() {
        alice.markConnecting();
        alice.markJoining("alice");
        alice.onJoined("alice");                        // JOINED|alice
        alice.onInfo("alice joined the lobby");         // INFO|alice joined the lobby
        alice.onPlayers(List.of("alice"));              // PLAYERS|alice
        alice.onHost("alice");                          // HOST|alice
        alice.onInfo("bob joined the lobby");           // INFO|bob joined the lobby
        alice.onPlayers(List.of("alice", "bob"));       // PLAYERS|alice,bob
        alice.onHost("alice");                          // HOST|alice
    }

    @Test
    void joinAndFullFiveQuestionGame() {
        aliceAndBobInLobby();
        assertEquals(GamePhase.LOBBY, alice.getPhase());
        assertTrue(alice.isHost());
        assertTrue(alice.canStartGame());

        alice.onGameStarted();                          // GAME_STARTED|5 questions
        assertEquals(GamePhase.GAME_STARTING, alice.getPhase());

        int aliceScore = 0;
        int bobScore = 0;
        for (int i = 0; i < 5; i++) {
            int round = i + 1;
            alice.onQuestion(QUESTIONS.get(i));         // QUESTION|round|5|15|...
            assertTrue(alice.canSubmitAnswer());

            // alice always answers correctly; bob never answers.
            assertTrue(alice.markAnswerSubmitted(CORRECT.get(i)));   // client sends ANSWER|round|X
            alice.onAnswerAccepted(round);              // ANSWER_ACCEPTED|round
            assertEquals(AnswerState.ACCEPTED, alice.getAnswerState());
            aliceScore += 10;

            alice.onRoundResult(round, CORRECT.get(i)); // ROUND_RESULT|round|X
            assertEquals(GamePhase.ROUND_ENDED, alice.getPhase());
            assertEquals(alice.getSelectedAnswer(), alice.getCorrectAnswer());

            alice.onScores(scores("alice", aliceScore, "bob", bobScore));  // SCORES|alice:..,bob:0
            assertEquals(2, alice.getLeaderboard().size());
        }

        // After the last round the server sends SCORES again, then GAME_OVER.
        alice.onScores(scores("alice", 50, "bob", 0));  // SCORES|alice:50,bob:0
        alice.onGameOver(List.of("alice"), 50);         // GAME_OVER|alice|50

        assertTrue(alice.isGameOver());
        assertTrue(alice.isWinner());
        assertFalse(alice.isTie());
        assertEquals(50, alice.getBestScore());
        assertEquals(scores("alice", 50, "bob", 0), alice.getLeaderboard());
        assertTrue(alice.canStartGame());               // Play Again for the host
    }

    @Test
    void playerLeavesMidGameEndsMatchWithoutRoundResult() {
        aliceAndBobInLobby();
        alice.onGameStarted();                          // GAME_STARTED|5 questions
        alice.onQuestion(QUESTIONS.get(0));             // QUESTION|1|5|15|...
        alice.markAnswerSubmitted(AnswerOption.B);
        alice.onAnswerAccepted(1);                      // ANSWER_ACCEPTED|1
        alice.onRoundResult(1, AnswerOption.B);         // ROUND_RESULT|1|B
        alice.onScores(scores("alice", 10, "bob", 0));  // SCORES|alice:10,bob:0
        alice.onQuestion(QUESTIONS.get(1));             // QUESTION|2|5|15|...

        // bob disconnects during round 2.
        alice.onInfo("bob left the lobby");             // INFO|bob left the lobby
        alice.onPlayers(List.of("alice"));              // PLAYERS|alice
        alice.onHost("alice");                          // HOST|alice
        alice.onInfo("Match ended: not enough players"); // INFO|Match ended: not enough players
        alice.onScores(scores("alice", 10));            // SCORES|alice:10
        alice.onGameOver(List.of("alice"), 10);         // GAME_OVER|alice|10

        assertEquals(GamePhase.GAME_OVER, alice.getPhase());
        assertEquals(AnswerState.MISSED, alice.getAnswerState());
        assertNull(alice.getCorrectAnswer());
        assertEquals(scores("alice", 10), alice.getLeaderboard());
        assertEquals("Match ended: not enough players", alice.getLastInfo());
        assertTrue(alice.isWinner());
        assertFalse(alice.canStartGame());              // only one player left
        assertEquals("At least two players are required", alice.startBlockedReason().orElseThrow());
    }

    @Test
    void hostLeavesMidGameAndHostMovesToNextPlayer() {
        GameState bob = new GameState();
        bob.markConnecting();
        bob.markJoining("bob");
        bob.onJoined("bob");                            // JOINED|bob
        bob.onPlayers(List.of("alice", "bob", "carol")); // PLAYERS|alice,bob,carol
        bob.onHost("alice");                            // HOST|alice
        assertFalse(bob.isHost());

        bob.onGameStarted();                            // GAME_STARTED|5 questions
        bob.onQuestion(QUESTIONS.get(0));               // QUESTION|1|5|15|...

        bob.onInfo("alice left the lobby");             // INFO|alice left the lobby
        bob.onPlayers(List.of("bob", "carol"));         // PLAYERS|bob,carol
        bob.onHost("bob");                              // HOST|bob

        assertTrue(bob.isHost());
        assertEquals(GamePhase.QUESTION_ACTIVE, bob.getPhase()); // game continues
        assertTrue(bob.canSubmitAnswer());
        assertFalse(bob.canStartGame());                // a game is still running
    }

    @Test
    void rejectedJoin() {
        alice.markConnecting();
        alice.markJoining("alice");
        alice.onError("Username already taken");        // ERROR|Username already taken
        alice.markDisconnected("Connection closed by server"); // server closes the socket

        assertEquals(GamePhase.DISCONNECTED, alice.getPhase());
        assertTrue(alice.isJoinRejected());
        assertEquals("Username already taken", alice.getLastError());
        assertNull(alice.getCurrentPlayer());
    }

    @Test
    void playAgainAfterGameOver() {
        aliceAndBobInLobby();
        alice.onGameStarted();                          // GAME_STARTED|5 questions
        for (int i = 0; i < 5; i++) {                   // nobody answers: every round times out
            alice.onQuestion(QUESTIONS.get(i));         // QUESTION|round|5|15|...
            alice.onRoundResult(i + 1, CORRECT.get(i)); // ROUND_RESULT|round|X
            assertEquals(AnswerState.MISSED, alice.getAnswerState());
            alice.onScores(scores("alice", 0, "bob", 0)); // SCORES|alice:0,bob:0
        }
        alice.onScores(scores("alice", 0, "bob", 0));   // SCORES|alice:0,bob:0
        alice.onGameOver(List.of("alice", "bob"), 0);   // GAME_OVER|alice,bob|0 (tie)
        assertTrue(alice.isTie());
        assertTrue(alice.canStartGame());

        // Host presses Play Again -> client sends START -> server replies:
        alice.onGameStarted();                          // GAME_STARTED|5 questions
        assertEquals(GamePhase.GAME_STARTING, alice.getPhase());
        assertTrue(alice.getLeaderboard().isEmpty());
        assertEquals(List.of(), alice.getWinners());
        assertFalse(alice.isTie());

        alice.onQuestion(QUESTIONS.get(0));             // QUESTION|1|5|15|...
        assertTrue(alice.canSubmitAnswer());
        assertEquals(AnswerState.NOT_ANSWERED, alice.getAnswerState());
    }
}
