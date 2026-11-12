package quiz.gui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Phase 4C navigation: a whole game against the real controller, with the real quiz and
 * results screens listening to the same controller, following the real server's order.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class GameScreensNavigationTest {

    private static final Predicate<String> ALL = Set.of(ScreenNavigator.CONNECTION, ScreenNavigator.LOBBY,
            ScreenNavigator.QUIZ, ScreenNavigator.RESULTS)::contains;

    private LoopbackServer server;
    private GameController controller;
    private QuizScreen quiz;
    private RoundResultsScreen results;
    private ScreenNavigator navigator;
    private StateWaiter waiter;
    private final List<String> shown = new ArrayList<>(); // only touched on the EDT

    @BeforeAll
    static void headless() {
        UiTestSupport.useHeadlessAwt();
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new LoopbackServer();
        runOnEdt(() -> {
            ThemeManager.apply(Theme.MODERN_DARK);
            controller = new GameController();
            quiz = new QuizScreen(controller);
            results = new RoundResultsScreen(controller);
            quiz.startListening();
            results.startListening();
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
            waiter.detach();
        });
        server.close();
    }

    @Test
    void roundEndedFallsBackToTheQuizScreenWithoutAResultsScreen() {
        Predicate<String> noResults = Set.of(ScreenNavigator.CONNECTION, ScreenNavigator.LOBBY,
                ScreenNavigator.QUIZ)::contains;
        assertEquals(Optional.of(ScreenNavigator.QUIZ), ScreenNavigator.screenFor(GamePhase.ROUND_ENDED, noResults));
        assertEquals(Optional.of(ScreenNavigator.LOBBY), ScreenNavigator.screenFor(GamePhase.GAME_OVER, noResults));
    }

    @Test
    void aWholeGameMovesBetweenQuizAndResults() throws Exception {
        runOnEdt(() -> assertTrue(controller.connect(server.host(), server.port(), "alice").isEmpty()));
        Client client = server.accept();
        client.expectLine("JOIN|alice");
        client.send("JOINED|alice", "PLAYERS|alice,bob", "HOST|alice");
        waiter.await("can start", () -> controller.canStartGame());
        assertEquals(List.of(ScreenNavigator.CONNECTION, ScreenNavigator.LOBBY), shownNow());

        runOnEdt(controller::startGame);
        client.expectLine("START");
        client.send("GAME_STARTED|2 questions");
        waiter.await("started", () -> controller.getState().getPhase() == GamePhase.GAME_STARTING);
        assertEquals(ScreenNavigator.QUIZ, current());

        // Round 1: answer through the quiz screen.
        client.send("QUESTION|1|2|15|Which Java class listens for TCP connections?"
                + "|Socket|ServerSocket|DatagramSocket|Thread");
        waiter.await("question 1", () -> controller.getState().getPhase() == GamePhase.QUESTION_ACTIVE);
        runOnEdt(() -> {
            quiz.optionButton(AnswerOption.B).doClick(0);
            quiz.submitButton().doClick(0);
        });
        client.expectLine("ANSWER|1|B");
        client.send("ANSWER_ACCEPTED|1", "ROUND_RESULT|1|B", "SCORES|alice:1,bob:0");
        waiter.await("scores 1", () -> controller.getState().getLeaderboard().size() == 2);
        assertEquals(ScreenNavigator.RESULTS, current());
        runOnEdt(() -> {
            assertEquals("Correct!", results.resultBanner().getTitle());
            assertEquals("Waiting for the next question", results.statusBanner().getTitle());
        });

        // Round 2 (the last one): no answer, then the real server's final burst.
        client.send("QUESTION|2|2|15|Which method waits for a client?|accept()|listen()|bind()|wait()");
        waiter.await("question 2", () -> controller.getState().getPhase() == GamePhase.QUESTION_ACTIVE);
        assertEquals(ScreenNavigator.QUIZ, current());
        runOnEdt(() -> assertEquals("Question 2 of 2", quiz.titleText()));

        client.send("ROUND_RESULT|2|A", "SCORES|alice:1,bob:1", "SCORES|alice:1,bob:1", "GAME_OVER|alice,bob|1");
        waiter.await("game over", () -> controller.getState().getPhase() == GamePhase.GAME_OVER);
        assertEquals(List.of(ScreenNavigator.CONNECTION, ScreenNavigator.LOBBY, ScreenNavigator.QUIZ,
                ScreenNavigator.RESULTS, ScreenNavigator.QUIZ, ScreenNavigator.RESULTS), shownNow(),
                "each screen is shown once per change, and GAME_OVER stays on the results");
        runOnEdt(() -> {
            assertEquals("Round 2 Results", results.titleText());
            assertEquals("Time's up", results.resultBanner().getTitle());
            assertEquals(List.of("1. alice, you, 1 point", "1. bob, 1 point"), results.scoreRowDescriptions());
            results.playAgainButton().doClick(0);
        });

        // Play Again goes back to the quiz screen once the server confirms.
        client.expectLine("START");
        client.send("GAME_STARTED|2 questions");
        waiter.await("restarted", () -> controller.getState().getPhase() == GamePhase.GAME_STARTING);
        assertEquals(ScreenNavigator.QUIZ, current());

        // A lost connection always returns to the connection screen.
        client.close();
        waiter.await("disconnected", () -> controller.getState().getPhase() == GamePhase.DISCONNECTED);
        assertEquals(ScreenNavigator.CONNECTION, current());
    }

    private String current() throws Exception {
        return onEdt(() -> navigator.getShownScreen().orElseThrow());
    }

    private List<String> shownNow() throws Exception {
        return onEdt(() -> List.copyOf(shown));
    }
}
