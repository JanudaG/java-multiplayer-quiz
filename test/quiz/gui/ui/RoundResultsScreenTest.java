package quiz.gui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static quiz.gui.ui.UiTestSupport.runOnEdt;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import quiz.gui.controller.GameController;
import quiz.gui.model.AnswerOption;
import quiz.gui.model.AnswerState;
import quiz.gui.model.GamePhase;
import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;
import quiz.gui.ui.UiTestSupport.Client;
import quiz.gui.ui.UiTestSupport.LoopbackServer;
import quiz.gui.ui.UiTestSupport.StateWaiter;
import quiz.gui.ui.components.AnswerButton;
import quiz.gui.ui.components.StatusBanner;

/**
 * Round results screen against the real controller and a scripted loopback server
 * (headless), using the real server's message order: ROUND_RESULT, SCORES, then the next
 * QUESTION; after the last round ROUND_RESULT, SCORES, SCORES, GAME_OVER in one burst.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RoundResultsScreenTest {

    private static final String QUESTION_1 = QuizScreenTest.QUESTION_1;
    private static final String LAST_QUESTION = "QUESTION|5|5|15|Which port range is reserved?"
            + "|0-1023|1024-2047|8000-9000|49152-65535";

    private LoopbackServer server;
    private GameController controller;
    private RoundResultsScreen screen;
    private StateWaiter waiter;

    @BeforeAll
    static void headless() {
        UiTestSupport.useHeadlessAwt();
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new LoopbackServer();
        runOnEdt(() -> {
            ThemeManager.apply(Theme.MODERN_LIGHT);
            controller = new GameController();
            screen = new RoundResultsScreen(controller);
            screen.startListening();
            waiter = new StateWaiter(controller);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        runOnEdt(() -> {
            controller.disconnect();
            screen.stopListening();
            waiter.detach();
        });
        server.close();
    }

    // ------------------------------------------------------------------
    // Round result
    // ------------------------------------------------------------------

    @Test
    void correctAnswerThenScoresThenWaitingForTheNextQuestion() throws Exception {
        Client client = answer("alice", QUESTION_1, AnswerOption.B);
        client.send("ANSWER_ACCEPTED|1", "ROUND_RESULT|1|B");
        waiter.await("round ended", () -> controller.getState().getPhase() == GamePhase.ROUND_ENDED);

        // ROUND_RESULT comes before SCORES: the result is shown, the scores are awaited.
        runOnEdt(() -> {
            assertEquals("Round 1 Results", screen.titleText());
            assertEquals("Question 1 of 5 · Playing as alice", screen.subtitleText());
            assertEquals("Which Java class listens for TCP connections?", screen.questionTextValue());
            assertAnswer(screen.correctAnswerView(), "B", "ServerSocket", AnswerButton.Look.CORRECT);
            assertAnswer(screen.yourAnswerView(), "B", "ServerSocket", AnswerButton.Look.CORRECT);
            assertEquals(StatusBanner.Kind.SUCCESS, screen.resultBanner().getKind());
            assertEquals("Correct!", screen.resultBanner().getTitle());
            assertEquals(List.of(), screen.scoreRowDescriptions(), "no SCORES yet");
            assertEquals(StatusBanner.Kind.PROGRESS, screen.statusBanner().getKind());
            assertEquals("Waiting for the next question", screen.statusBanner().getTitle());
            assertEquals("Question 2 of 5 starts in a moment.", screen.statusBanner().getMessage());
            assertFalse(screen.playAgainButton().isVisible());
        });

        client.send("SCORES|alice:1,bob:0");
        waiter.await("scores", () -> controller.getState().getLeaderboard().size() == 2);
        runOnEdt(() -> {
            assertEquals(List.of("1. alice, you, 1 point", "2. bob, 0 points"), screen.scoreRowDescriptions());
            assertEquals("2 players", screen.playerCountText());
            assertEquals("Waiting for the next question", screen.statusBanner().getTitle());
        });
    }

    @Test
    void wrongAnswerShowsBothAnswers() throws Exception {
        Client client = answer("alice", QUESTION_1, AnswerOption.A);
        client.send("ANSWER_ACCEPTED|1", "ROUND_RESULT|1|B", "SCORES|bob:1,alice:0");
        waiter.await("scores", () -> controller.getState().getLeaderboard().size() == 2);
        runOnEdt(() -> {
            assertAnswer(screen.correctAnswerView(), "B", "ServerSocket", AnswerButton.Look.CORRECT);
            assertAnswer(screen.yourAnswerView(), "A", "Socket", AnswerButton.Look.WRONG);
            assertEquals(StatusBanner.Kind.ERROR, screen.resultBanner().getKind());
            assertEquals("Not quite", screen.resultBanner().getTitle());
            assertEquals("You answered A. The correct answer was B.", screen.resultBanner().getMessage());
            assertEquals(List.of("1. bob, 1 point", "2. alice, you, 0 points"), screen.scoreRowDescriptions());
        });
    }

    @Test
    void noAnswerBeforeTheRoundClosedIsMissed() throws Exception {
        Client client = join("alice", "PLAYERS|alice,bob", "HOST|alice");
        client.send("GAME_STARTED|5 questions", QUESTION_1, "ROUND_RESULT|1|B", "SCORES|alice:0,bob:0");
        waiter.await("scores", () -> controller.getState().getLeaderboard().size() == 2);
        runOnEdt(() -> {
            assertEquals(AnswerState.MISSED, controller.getState().getAnswerState());
            assertAnswer(screen.yourAnswerView(), "–", "No answer", AnswerButton.Look.MUTED);
            assertEquals(StatusBanner.Kind.WARNING, screen.resultBanner().getKind());
            assertEquals("Time's up", screen.resultBanner().getTitle());
            assertEquals("You did not answer this round. The correct answer was B.",
                    screen.resultBanner().getMessage());
            assertEquals(List.of("1. alice, you, 0 points", "1. bob, 0 points"), screen.scoreRowDescriptions(),
                    "equal scores share a rank");
        });
    }

    @Test
    void anAnswerTheServerNeverAcceptedIsNotCounted() throws Exception {
        Client client = answer("alice", QUESTION_1, AnswerOption.B);
        client.send("ERROR|No active question or time has expired", "ROUND_RESULT|1|B");
        waiter.await("round ended", () -> controller.getState().getPhase() == GamePhase.ROUND_ENDED);
        runOnEdt(() -> {
            assertAnswer(screen.yourAnswerView(), "B", "ServerSocket", AnswerButton.Look.MUTED);
            assertEquals(StatusBanner.Kind.WARNING, screen.resultBanner().getKind());
            assertEquals("Answer not counted", screen.resultBanner().getTitle());
        });
    }

    @Test
    void newScoresReplaceTheLeaderboardInsteadOfAppending() throws Exception {
        Client client = join("carol", "PLAYERS|alice,bob,carol", "HOST|alice");
        client.send("GAME_STARTED|5 questions", QUESTION_1, "ROUND_RESULT|1|B", "SCORES|alice:1,bob:0,carol:0");
        waiter.await("scores", () -> controller.getState().getLeaderboard().size() == 3);
        runOnEdt(() -> assertEquals(List.of("1. alice, 1 point", "2. bob, 0 points", "2. carol, you, 0 points"),
                screen.scoreRowDescriptions()));

        client.send("SCORES|carol:2,alice:1,bob:0");
        waiter.await("new scores", () -> controller.getState().getLeaderboard().scoreOf("carol").orElse(0) == 2);
        runOnEdt(() -> assertEquals(List.of("1. carol, you, 2 points", "2. alice, 1 point", "3. bob, 0 points"),
                screen.scoreRowDescriptions()));
    }

    // ------------------------------------------------------------------
    // Last round and GAME_OVER
    // ------------------------------------------------------------------

    @Test
    void lastRoundWaitsForTheFinalResults() throws Exception {
        Client client = join("alice", "PLAYERS|alice,bob", "HOST|alice");
        client.send("GAME_STARTED|5 questions", LAST_QUESTION, "ROUND_RESULT|5|A");
        waiter.await("round ended", () -> controller.getState().getPhase() == GamePhase.ROUND_ENDED);
        runOnEdt(() -> {
            assertEquals("That was the last question", screen.statusBanner().getTitle());
            assertFalse(screen.playAgainButton().isVisible());
        });
    }

    @Test
    void gameOverKeepsTheFinalRoundAndScoresWithoutDuplicates() throws Exception {
        Client client = answer("alice", LAST_QUESTION, AnswerOption.A);
        // The real server's burst after the last round.
        client.send("ANSWER_ACCEPTED|5", "ROUND_RESULT|5|A", "SCORES|alice:3,bob:2",
                "SCORES|alice:3,bob:2", "GAME_OVER|alice|3");
        waiter.await("game over", () -> controller.getState().getPhase() == GamePhase.GAME_OVER);
        runOnEdt(() -> {
            assertEquals("Round 5 Results", screen.titleText());
            assertAnswer(screen.correctAnswerView(), "A", "0-1023", AnswerButton.Look.CORRECT);
            assertAnswer(screen.yourAnswerView(), "A", "0-1023", AnswerButton.Look.CORRECT);
            assertEquals("Correct!", screen.resultBanner().getTitle());
            assertEquals(List.of("1. alice, you, 3 points", "2. bob, 2 points"), screen.scoreRowDescriptions());
            assertEquals(StatusBanner.Kind.SUCCESS, screen.statusBanner().getKind());
            assertEquals("Game over", screen.statusBanner().getTitle());
            assertTrue(screen.playAgainButton().isVisible());
            assertTrue(screen.playAgainButton().isEnabled());
            assertEquals(RoundResultsScreen.PLAY_AGAIN_TEXT, screen.playAgainButton().getText());
        });
    }

    @Test
    void hostPlaysAgainOnceAndDuplicateClicksAreIgnored() throws Exception {
        Client client = finishGame("alice", "HOST|alice");
        runOnEdt(() -> {
            screen.playAgainButton().doClick(0);
            assertFalse(screen.playAgainButton().isEnabled());
            assertEquals(RoundResultsScreen.STARTING_TEXT, screen.playAgainButton().getText());
            assertEquals("Starting a new game…", screen.statusBanner().getTitle());
            screen.playAgainButton().doClick(0);
            screen.playAgainButton().doClick(0);
        });
        client.expectLine("START");
        client.send("GAME_STARTED|5 questions");
        waiter.await("new game", () -> controller.getState().getPhase() == GamePhase.GAME_STARTING);
        runOnEdt(() -> {
            assertFalse(screen.playAgainButton().isVisible());
            assertEquals(List.of(), screen.scoreRowDescriptions(), "a new game starts with empty scores");
            controller.disconnect();
        });
        client.expectLine("QUIT"); // proves that only one START was sent
    }

    @Test
    void refusedPlayAgainShowsTheServerError() throws Exception {
        Client client = finishGame("alice", "HOST|alice");
        runOnEdt(() -> screen.playAgainButton().doClick(0));
        client.expectLine("START");
        client.send("ERROR|At least two players are required");
        waiter.await("refused", () -> controller.canStartGame());
        runOnEdt(() -> {
            assertEquals(StatusBanner.Kind.ERROR, screen.statusBanner().getKind());
            assertEquals("The server refused the request", screen.statusBanner().getTitle());
            assertEquals("At least two players are required", screen.statusBanner().getMessage());
            assertTrue(screen.playAgainButton().isEnabled(), "the host may try again");
        });
    }

    @Test
    void anAnswerErrorFromTheGameIsNotShownAsAPlayAgainRefusal() throws Exception {
        Client client = join("alice", "PLAYERS|alice,bob", "HOST|alice");
        client.send("GAME_STARTED|5 questions", LAST_QUESTION, "ERROR|You already answered this round",
                "ROUND_RESULT|5|A", "SCORES|bob:1,alice:0", "SCORES|bob:1,alice:0", "GAME_OVER|bob|1");
        waiter.await("game over", () -> controller.getState().getPhase() == GamePhase.GAME_OVER);
        runOnEdt(() -> assertEquals(StatusBanner.Kind.SUCCESS, screen.statusBanner().getKind()));
    }

    @Test
    void otherPlayersWaitForTheHostAfterGameOver() throws Exception {
        Client client = finishGame("bob", "HOST|alice");
        runOnEdt(() -> {
            assertFalse(screen.playAgainButton().isVisible());
            assertEquals(StatusBanner.Kind.PROGRESS, screen.statusBanner().getKind());
            assertEquals("Waiting for alice to start a new game.", screen.statusBanner().getMessage());
            controller.disconnect();
        });
        client.expectLine("QUIT");
    }

    @Test
    void hostAloneAfterGameOverIsToldWhyPlayAgainIsDisabled() throws Exception {
        Client client = finishGame("alice", "HOST|alice");
        client.send("PLAYERS|alice");
        waiter.await("bob left", () -> controller.getState().getPlayers().size() == 1);
        runOnEdt(() -> {
            assertFalse(screen.playAgainButton().isEnabled());
            assertEquals(StatusBanner.Kind.WARNING, screen.statusBanner().getKind());
            assertEquals("At least two players are required to play again.", screen.statusBanner().getMessage());
        });
    }

    @Test
    void leaveGameDisconnectsThroughTheController() throws Exception {
        Client client = finishGame("alice", "HOST|alice");
        runOnEdt(() -> screen.leaveButton().doClick(0));
        client.expectLine("QUIT");
        runOnEdt(() -> assertEquals(GamePhase.DISCONNECTED, controller.getState().getPhase()));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Client join(String username, String... lobbyLines) throws Exception {
        runOnEdt(() -> assertTrue(controller.connect(server.host(), server.port(), username).isEmpty()));
        Client client = server.accept();
        client.expectLine("JOIN|" + username);
        client.send("JOINED|" + username);
        client.send(lobbyLines);
        waiter.await("lobby", () -> controller.getState().getHost() != null);
        return client;
    }

    /** Joins as {@code username} (with alice and bob), opens the question and submits the answer. */
    private Client answer(String username, String questionLine, AnswerOption option) throws Exception {
        Client client = join(username, "PLAYERS|alice,bob", "HOST|alice");
        client.send("GAME_STARTED|5 questions", questionLine);
        waiter.await("question", () -> controller.getState().getPhase() == GamePhase.QUESTION_ACTIVE);
        runOnEdt(() -> assertTrue(controller.submitAnswer(option)));
        int round = questionLine.startsWith("QUESTION|5|") ? 5 : 1;
        client.expectLine("ANSWER|" + round + "|" + option.letter());
        return client;
    }

    /** Plays the last round without answering and ends the game. */
    private Client finishGame(String username, String hostLine) throws Exception {
        Client client = join(username, "PLAYERS|alice,bob", hostLine);
        client.send("GAME_STARTED|5 questions", LAST_QUESTION, "ROUND_RESULT|5|A",
                "SCORES|alice:2,bob:1", "SCORES|alice:2,bob:1", "GAME_OVER|alice|2");
        waiter.await("game over", () -> controller.getState().getPhase() == GamePhase.GAME_OVER);
        return client;
    }

    private static void assertAnswer(AnswerButton view, String letter, String text, AnswerButton.Look look) {
        assertEquals(letter, view.getLetter());
        assertEquals(text, view.getText());
        assertEquals(look, view.getLook());
        assertFalse(view.isEnabled(), "the results screen only displays answers");
    }
}
