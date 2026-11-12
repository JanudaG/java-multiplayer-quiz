package quiz.gui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static quiz.gui.ui.UiTestSupport.onEdt;
import static quiz.gui.ui.UiTestSupport.runOnEdt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import quiz.gui.controller.GameController;
import quiz.gui.model.AnswerOption;
import quiz.gui.model.GamePhase;
import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;
import quiz.gui.ui.UiTestSupport.Client;
import quiz.gui.ui.UiTestSupport.LoopbackServer;
import quiz.gui.ui.UiTestSupport.StateWaiter;

/**
 * Phase 4D navigation: GAME_OVER goes to the game over screen, "results" stays for the
 * round results only, and Play Again from the game over screen returns to the quiz.
 * All screens listen to one real controller, as in the application.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class GameOverNavigationTest {

    private static final Predicate<String> ALL = Set.of(ScreenNavigator.CONNECTION, ScreenNavigator.LOBBY,
            ScreenNavigator.QUIZ, ScreenNavigator.RESULTS, ScreenNavigator.GAME_OVER)::contains;

    private LoopbackServer server;
    private GameController controller;
    private QuizScreen quiz;
    private RoundResultsScreen results;
    private GameOverScreen gameOver;
    private ScreenNavigator navigator;
    private StateWaiter waiter;
    private final List<String> shown = new ArrayList<>(); // only touched on the EDT

    @BeforeAll
    static void headless() {
        UiTestSupport.useHeadlessAwt();
    }

    // ------------------------------------------------------------------
    // Phase -> screen mapping
    // ------------------------------------------------------------------

    @Test
    void gameOverHasItsOwnScreenAndResultsAreOnlyForRounds() {
        assertEquals("game-over", ScreenNavigator.GAME_OVER);
        assertEquals(Optional.of(ScreenNavigator.GAME_OVER), ScreenNavigator.screenFor(GamePhase.GAME_OVER, ALL));
        assertEquals(Optional.of(ScreenNavigator.RESULTS), ScreenNavigator.screenFor(GamePhase.ROUND_ENDED, ALL));
        for (GamePhase phase : GamePhase.values()) {
            if (phase != GamePhase.ROUND_ENDED) {
                assertNotEquals(Optional.of(ScreenNavigator.RESULTS), ScreenNavigator.screenFor(phase, ALL),
                        phase + " must not use the results screen");
            }
        }
        assertEquals(Optional.of(ScreenNavigator.QUIZ), ScreenNavigator.screenFor(GamePhase.GAME_STARTING, ALL));
        assertEquals(Optional.of(ScreenNavigator.QUIZ), ScreenNavigator.screenFor(GamePhase.QUESTION_ACTIVE, ALL));
        assertEquals(Optional.of(ScreenNavigator.LOBBY), ScreenNavigator.screenFor(GamePhase.LOBBY, ALL));
    }

    @Test
    void gameOverFallsBackWhenItsScreenIsMissing() {
        Predicate<String> noGameOver = Set.of(ScreenNavigator.CONNECTION, ScreenNavigator.LOBBY,
                ScreenNavigator.QUIZ, ScreenNavigator.RESULTS)::contains;
        assertEquals(Optional.of(ScreenNavigator.RESULTS), ScreenNavigator.screenFor(GamePhase.GAME_OVER, noGameOver),
                "the Phase 4C behaviour");
        Predicate<String> lobbyOnly = Set.of(ScreenNavigator.CONNECTION, ScreenNavigator.LOBBY)::contains;
        assertEquals(Optional.of(ScreenNavigator.LOBBY), ScreenNavigator.screenFor(GamePhase.GAME_OVER, lobbyOnly));
        Predicate<String> gameOverOnly = Set.of(ScreenNavigator.CONNECTION, ScreenNavigator.LOBBY,
                ScreenNavigator.GAME_OVER)::contains;
        assertEquals(Optional.of(ScreenNavigator.LOBBY), ScreenNavigator.screenFor(GamePhase.ROUND_ENDED, gameOverOnly),
                "the game over screen never shows a running game");
    }

    // ------------------------------------------------------------------
    // A whole game against the real controller
    // ------------------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception {
        server = new LoopbackServer();
        runOnEdt(() -> {
            ThemeManager.apply(Theme.MODERN_LIGHT);
            controller = new GameController();
            quiz = new QuizScreen(controller);
            results = new RoundResultsScreen(controller);
            gameOver = new GameOverScreen(controller);
            quiz.startListening();
            results.startListening();
            gameOver.startListening();
            navigator = new ScreenNavigator(controller, ALL, shown::add);
            navigator.start();
            waiter = new StateWaiter(controller);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        runOnEdt(() -> {
            controller.disconnect();
            navigator.stop();
            quiz.stopListening();
            results.stopListening();
            gameOver.stopListening();
            waiter.detach();
        });
        server.close();
    }

    @Test
    void aWholeGameEndsOnTheGameOverScreenAndPlayAgainReturnsToTheQuiz() throws Exception {
        runOnEdt(() -> assertTrue(controller.connect(server.host(), server.port(), "alice").isEmpty()));
        Client client = server.accept();
        client.expectLine("JOIN|alice");
        client.send("JOINED|alice", "PLAYERS|alice,bob", "HOST|alice");
        waiter.await("can start", () -> controller.canStartGame());
        runOnEdt(controller::startGame);
        client.expectLine("START");
        client.send("GAME_STARTED|2 questions",
                "QUESTION|1|2|15|Which Java class listens for TCP connections?|Socket|ServerSocket|DatagramSocket|Thread");
        waiter.await("question 1", () -> controller.getState().getPhase() == GamePhase.QUESTION_ACTIVE);
        runOnEdt(() -> {
            quiz.optionButton(AnswerOption.B).doClick(0);
            quiz.submitButton().doClick(0);
        });
        client.expectLine("ANSWER|1|B");
        client.send("ANSWER_ACCEPTED|1", "ROUND_RESULT|1|B", "SCORES|alice:1,bob:0");
        waiter.await("scores 1", () -> controller.getState().getLeaderboard().size() == 2);
        assertEquals(ScreenNavigator.RESULTS, current());

        client.send("QUESTION|2|2|15|Which method waits for a client?|accept()|listen()|bind()|wait()");
        waiter.await("question 2", () -> controller.getState().getPhase() == GamePhase.QUESTION_ACTIVE);
        assertEquals(ScreenNavigator.QUIZ, current());

        // The real server's final burst.
        client.send("ROUND_RESULT|2|A", "SCORES|alice:1,bob:1", "SCORES|alice:1,bob:1", "GAME_OVER|alice,bob|1");
        waiter.await("game over", () -> controller.getState().getPhase() == GamePhase.GAME_OVER);
        assertEquals(List.of(ScreenNavigator.CONNECTION, ScreenNavigator.LOBBY, ScreenNavigator.QUIZ,
                ScreenNavigator.RESULTS, ScreenNavigator.QUIZ, ScreenNavigator.RESULTS, ScreenNavigator.GAME_OVER),
                shownNow(), "quiz -> results between rounds, then the game over screen");
        runOnEdt(() -> {
            assertEquals("It's a tie!", gameOver.headlineText());
            assertEquals("Winners: alice (you) and bob", gameOver.winnerNamesText());
            assertEquals(List.of("1. alice, you, winner, 1 point", "1. bob, winner, 1 point"),
                    gameOver.scoreRowDescriptions());
            gameOver.playAgainButton().doClick(0);
        });

        // A refused START keeps the game over screen.
        client.expectLine("START");
        client.send("ERROR|At least two players are required");
        waiter.await("refused", () -> controller.canStartGame());
        assertEquals(ScreenNavigator.GAME_OVER, current());

        // Play Again: GAME_STARTED and the first QUESTION go back to the quiz screen.
        runOnEdt(() -> gameOver.playAgainButton().doClick(0));
        client.expectLine("START");
        client.send("GAME_STARTED|2 questions");
        waiter.await("restarted", () -> controller.getState().getPhase() == GamePhase.GAME_STARTING);
        assertEquals(ScreenNavigator.QUIZ, current());
        client.send("QUESTION|1|2|15|Which protocol is reliable?|UDP|HTTP|TCP|ICMP");
        waiter.await("new question", () -> controller.getState().getPhase() == GamePhase.QUESTION_ACTIVE);
        assertEquals(ScreenNavigator.QUIZ, current());
        runOnEdt(() -> assertEquals("Question 1 of 2", quiz.titleText()));

        // The next round still goes to the round results screen.
        client.send("ROUND_RESULT|1|C", "SCORES|alice:0,bob:0");
        waiter.await("round ended", () -> controller.getState().getPhase() == GamePhase.ROUND_ENDED);
        assertEquals(ScreenNavigator.RESULTS, current());

        // A lost connection always returns to the connection screen.
        client.close();
        waiter.await("disconnected", () -> controller.getState().getPhase() == GamePhase.DISCONNECTED);
        assertEquals(ScreenNavigator.CONNECTION, current());
    }

    @Test
    void nonHostStaysOnTheGameOverScreenUntilTheHostStartsAgain() throws Exception {
        runOnEdt(() -> assertTrue(controller.connect(server.host(), server.port(), "bob").isEmpty()));
        Client client = server.accept();
        client.expectLine("JOIN|bob");
        client.send("JOINED|bob", "PLAYERS|alice,bob", "HOST|alice", "GAME_STARTED|1 questions",
                "QUESTION|1|1|15|Which protocol is reliable?|UDP|HTTP|TCP|ICMP",
                "ROUND_RESULT|1|C", "SCORES|alice:1,bob:0", "SCORES|alice:1,bob:0", "GAME_OVER|alice|1");
        waiter.await("game over", () -> controller.getState().getPhase() == GamePhase.GAME_OVER);
        assertEquals(ScreenNavigator.GAME_OVER, current());
        runOnEdt(() -> assertEquals("Waiting for alice to start a new game.", gameOver.statusBanner().getMessage()));

        client.send("GAME_STARTED|1 questions");
        waiter.await("restarted by the host", () -> controller.getState().getPhase() == GamePhase.GAME_STARTING);
        assertEquals(ScreenNavigator.QUIZ, current());
    }

    private String current() throws Exception {
        return onEdt(() -> navigator.getShownScreen().orElseThrow());
    }

    private List<String> shownNow() throws Exception {
        return onEdt(() -> List.copyOf(shown));
    }
}
