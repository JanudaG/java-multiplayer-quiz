package quiz.gui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static quiz.gui.ui.UiTestSupport.runOnEdt;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
 * Quiz screen against the real controller and a scripted loopback server (headless).
 *
 * <p>The countdown uses a fake clock that only moves when a test moves it, and the tests
 * call {@code tickCountdown()} themselves, so no test depends on real time passing.
 * "No extra command was sent" is proven by disconnecting and checking that the next line
 * the server receives is QUIT.</p>
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class QuizScreenTest {

    static final String QUESTION_1 = "QUESTION|1|5|15|Which Java class listens for TCP connections?"
            + "|Socket|ServerSocket|DatagramSocket|Thread";
    static final String QUESTION_2 = "QUESTION|2|5|15|Which method waits for a client?"
            + "|accept()|listen()|bind()|wait()";

    private LoopbackServer server;
    private GameController controller;
    private QuizScreen screen;
    private StateWaiter waiter;
    /** Fake nanosecond clock for the countdown. */
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);

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
            screen = new QuizScreen(controller, clock::get);
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
    // Showing the question
    // ------------------------------------------------------------------

    @Test
    void waitsForTheFirstQuestionAfterGameStarted() throws Exception {
        Client client = joinAndStart();
        client.send("GAME_STARTED|5 questions");
        waiter.await("game started", () -> controller.getState().getPhase() == GamePhase.GAME_STARTING);
        runOnEdt(() -> {
            assertEquals("Get Ready", screen.titleText());
            assertEquals("Playing as alice", screen.subtitleText());
            assertEquals(StatusBanner.Kind.PROGRESS, screen.statusBanner().getKind());
            assertEquals("The game has started", screen.statusBanner().getTitle());
            assertFalse(screen.submitButton().isVisible());
            assertFalse(screen.optionButton(AnswerOption.A).isEnabled());
            assertEquals("–", screen.countdownText());
            assertFalse(screen.isCountdownRunning());
        });
    }

    @Test
    void showsQuestionNumberTextAndFourAnswers() throws Exception {
        openQuestion(QUESTION_1);
        runOnEdt(() -> {
            assertEquals("Question 1 of 5", screen.titleText());
            assertEquals("Which Java class listens for TCP connections?", screen.questionTextValue());
            assertEquals("Socket", screen.optionButton(AnswerOption.A).getText());
            assertEquals("ServerSocket", screen.optionButton(AnswerOption.B).getText());
            assertEquals("DatagramSocket", screen.optionButton(AnswerOption.C).getText());
            assertEquals("Thread", screen.optionButton(AnswerOption.D).getText());
            for (AnswerOption option : AnswerOption.values()) {
                AnswerButton button = screen.optionButton(option);
                assertEquals(option.letter(), button.getLetter());
                assertTrue(button.isEnabled());
                assertEquals(AnswerButton.Look.NORMAL, button.getLook());
            }
            assertTrue(screen.submitButton().isVisible());
            assertFalse(screen.submitButton().isEnabled(), "nothing selected yet");
            assertEquals(QuizScreen.SUBMIT_TEXT, screen.submitButton().getText());
            assertEquals("Choose an answer", screen.statusBanner().getTitle());
            assertEquals("15 s", screen.countdownText());
            assertEquals(15_000, screen.countdownBar().getMaximum());
            assertEquals(15_000, screen.countdownBar().getValue());
            assertTrue(screen.isCountdownRunning());
        });
    }

    // ------------------------------------------------------------------
    // Selecting and submitting
    // ------------------------------------------------------------------

    @Test
    void selectingIsLocalAndSubmitSendsExactlyOneAnswer() throws Exception {
        Client client = openQuestion(QUESTION_1);
        runOnEdt(() -> {
            screen.optionButton(AnswerOption.B).doClick(0);
            assertEquals(AnswerOption.B, screen.chosenOption());
            assertEquals(AnswerButton.Look.SELECTED, screen.optionButton(AnswerOption.B).getLook());
            assertTrue(screen.submitButton().isEnabled());
            assertEquals("Ready to submit", screen.statusBanner().getTitle());

            // The choice can still change before submitting.
            screen.optionButton(AnswerOption.C).doClick(0);
            assertEquals(AnswerOption.C, screen.chosenOption());
            assertEquals(AnswerButton.Look.NORMAL, screen.optionButton(AnswerOption.B).getLook());
            assertEquals(AnswerButton.Look.SELECTED, screen.optionButton(AnswerOption.C).getLook());
            assertEquals(AnswerState.NOT_ANSWERED, controller.getState().getAnswerState(), "nothing sent yet");

            screen.submitButton().doClick(0);
            assertEquals(AnswerState.SUBMITTED, controller.getState().getAnswerState());
            assertEquals(AnswerOption.C, controller.getState().getSelectedAnswer());
            assertFalse(screen.submitButton().isEnabled());
            assertEquals(QuizScreen.SENDING_TEXT, screen.submitButton().getText());
            assertEquals(StatusBanner.Kind.PROGRESS, screen.statusBanner().getKind());
            assertEquals("Answer sent", screen.statusBanner().getTitle());
            for (AnswerOption option : AnswerOption.values()) {
                assertFalse(screen.optionButton(option).isEnabled(), "answers are locked after submitting");
            }

            // Duplicate submissions and late changes are ignored.
            screen.submitButton().doClick(0);
            screen.optionButton(AnswerOption.A).doClick(0);
            screen.submitButton().doClick(0);
            assertEquals(AnswerOption.C, controller.getState().getSelectedAnswer());
        });
        client.expectLine("ANSWER|1|C"); // the first line after JOIN: clicking B and C sent nothing

        client.send("ANSWER_ACCEPTED|1");
        waiter.await("accepted", () -> controller.getState().getAnswerState() == AnswerState.ACCEPTED);
        runOnEdt(() -> {
            assertEquals(StatusBanner.Kind.SUCCESS, screen.statusBanner().getKind());
            assertEquals("Answer locked in", screen.statusBanner().getTitle());
            assertEquals("You answered C. Waiting for the round result.", screen.statusBanner().getMessage());
            assertEquals(QuizScreen.LOCKED_TEXT, screen.submitButton().getText());
            assertFalse(screen.submitButton().isEnabled());
            assertEquals(AnswerButton.Look.LOCKED, screen.optionButton(AnswerOption.C).getLook());
            assertEquals(AnswerButton.Look.MUTED, screen.optionButton(AnswerOption.A).getLook());
            controller.disconnect();
        });
        client.expectLine("QUIT"); // proves that no second ANSWER was sent
    }

    @Test
    void submitDoesNothingWithoutASelection() throws Exception {
        Client client = openQuestion(QUESTION_1);
        runOnEdt(() -> {
            screen.submitButton().doClick(0);
            assertEquals(AnswerState.NOT_ANSWERED, controller.getState().getAnswerState());
            controller.disconnect();
        });
        client.expectLine("QUIT");
    }

    @Test
    void rejectedAnswerShowsTheServerErrorAndStaysLocked() throws Exception {
        Client client = openQuestion(QUESTION_1);
        runOnEdt(() -> {
            screen.optionButton(AnswerOption.A).doClick(0);
            screen.submitButton().doClick(0);
        });
        client.expectLine("ANSWER|1|A");
        client.send("ERROR|No active question or time has expired");
        waiter.await("error", () -> controller.getState().getLastError() != null);
        runOnEdt(() -> {
            assertEquals(StatusBanner.Kind.ERROR, screen.statusBanner().getKind());
            assertEquals("Your answer was not accepted", screen.statusBanner().getTitle());
            assertEquals("No active question or time has expired", screen.statusBanner().getMessage());
            assertFalse(screen.submitButton().isEnabled());
            assertFalse(screen.optionButton(AnswerOption.B).isEnabled());
        });
    }

    @Test
    void anOldErrorIsNotShownAsTheRejectionOfANewAnswer() throws Exception {
        Client client = openQuestion(QUESTION_1);
        client.send("ERROR|Unknown command"); // before this round's answer
        waiter.await("error", () -> controller.getState().getLastError() != null);
        runOnEdt(() -> {
            screen.optionButton(AnswerOption.B).doClick(0);
            screen.submitButton().doClick(0);
            assertEquals("Answer sent", screen.statusBanner().getTitle());
        });
        client.expectLine("ANSWER|1|B");
    }

    @Test
    void leaveGameDisconnectsThroughTheController() throws Exception {
        Client client = openQuestion(QUESTION_1);
        runOnEdt(() -> {
            screen.leaveButton().doClick(0);
            assertEquals(GamePhase.DISCONNECTED, controller.getState().getPhase());
            assertFalse(screen.isCountdownRunning());
        });
        client.expectLine("QUIT");
    }

    // ------------------------------------------------------------------
    // Countdown (fake clock, no real waiting)
    // ------------------------------------------------------------------

    @Test
    void countdownFollowsTheClockAndTimeUpBlocksAnswering() throws Exception {
        Client client = openQuestion(QUESTION_1);
        runOnEdt(() -> {
            screen.optionButton(AnswerOption.D).doClick(0); // chosen but never submitted

            advance(4_200);
            screen.tickCountdown();
            assertEquals("11 s", screen.countdownText());
            assertEquals(10_800, screen.countdownBar().getValue());
            assertTrue(screen.optionButton(AnswerOption.A).isEnabled());

            advance(5_800); // 10 s elapsed
            screen.tickCountdown();
            assertEquals("5 s", screen.countdownText());
            assertEquals(Theme.MODERN_DARK.getWarning(), screen.countdownBar().getForeground(), "low time warning");

            advance(5_000); // 15 s elapsed: local time is up
            screen.tickCountdown();
            assertEquals("0 s", screen.countdownText());
            assertEquals(StatusBanner.Kind.WARNING, screen.statusBanner().getKind());
            assertEquals("Time's up", screen.statusBanner().getTitle());
            assertFalse(screen.submitButton().isEnabled());
            for (AnswerOption option : AnswerOption.values()) {
                assertFalse(screen.optionButton(option).isEnabled());
            }
            assertFalse(screen.isCountdownRunning());
            screen.submitButton().doClick(0);
            assertEquals(AnswerState.NOT_ANSWERED, controller.getState().getAnswerState());
            // The server decides when the round ends: the local countdown changes nothing.
            assertEquals(GamePhase.QUESTION_ACTIVE, controller.getState().getPhase());
        });

        client.send("ROUND_RESULT|1|B");
        waiter.await("round ended", () -> controller.getState().getPhase() == GamePhase.ROUND_ENDED);
        runOnEdt(() -> {
            assertEquals(AnswerState.MISSED, controller.getState().getAnswerState());
            controller.disconnect();
        });
        client.expectLine("QUIT"); // no ANSWER was ever sent
    }

    @Test
    void lastCountdownTickLandsExactlyOnZero() throws Exception {
        assertEquals(200, QuizScreen.nextTickMillis(15_000), "a whole tick while plenty of time is left");
        assertEquals(150, QuizScreen.nextTickMillis(150), "the last tick is shortened to end on zero");
        assertEquals(1, QuizScreen.nextTickMillis(0), "never a zero or negative delay");

        openQuestion(QUESTION_1);
        runOnEdt(() -> {
            // A repeating Swing timer schedules its next tick before the tick runs, so a
            // shortened delay would only apply one tick too late.
            assertTrue(screen.isCountdownOneShot(), "each tick schedules the next one");
            assertEquals(200, screen.countdownDelayMillis());

            advance(14_850); // 150 ms left
            screen.tickCountdown();
            assertEquals("1 s", screen.countdownText());
            assertEquals(150, screen.countdownDelayMillis(), "the next tick is due exactly at zero");
            assertTrue(screen.isCountdownRunning());

            advance(150); // that tick: time is up at once, not up to 200 ms later
            screen.tickCountdown();
            assertEquals("Time's up", screen.statusBanner().getTitle());
            assertFalse(screen.isCountdownRunning());
        });
    }

    @Test
    void timeUpIsNotShownAfterTheServerEndedTheRound() throws Exception {
        Client client = openQuestion(QUESTION_1);
        runOnEdt(() -> advance(10_000));
        // The server closes the round before the local countdown reaches zero.
        client.send("ROUND_RESULT|1|B");
        waiter.await("round ended", () -> controller.getState().getPhase() == GamePhase.ROUND_ENDED);
        runOnEdt(() -> {
            assertFalse(screen.isCountdownRunning());
            advance(10_000); // local zero passes after the round ended
            screen.tickCountdown();
            assertEquals("Round over", screen.statusBanner().getTitle());
            assertFalse(screen.isCountdownRunning());
        });
    }

    @Test
    void aNewQuestionResetsTheChoiceAndRestartsTheCountdown() throws Exception {
        Client client = openQuestion(QUESTION_1);
        runOnEdt(() -> {
            screen.optionButton(AnswerOption.B).doClick(0);
            screen.submitButton().doClick(0);
        });
        client.expectLine("ANSWER|1|B");
        client.send("ANSWER_ACCEPTED|1", "ROUND_RESULT|1|B", "SCORES|alice:1,bob:0");
        waiter.await("round ended", () -> controller.getState().getPhase() == GamePhase.ROUND_ENDED);

        runOnEdt(() -> advance(20_000));
        client.send(QUESTION_2);
        waiter.await("question 2", () -> controller.getState().getPhase() == GamePhase.QUESTION_ACTIVE);
        runOnEdt(() -> {
            assertEquals("Question 2 of 5", screen.titleText());
            assertEquals("Which method waits for a client?", screen.questionTextValue());
            assertEquals("accept()", screen.optionButton(AnswerOption.A).getText());
            assertNull(screen.chosenOption());
            for (AnswerOption option : AnswerOption.values()) {
                assertTrue(screen.optionButton(option).isEnabled());
                assertEquals(AnswerButton.Look.NORMAL, screen.optionButton(option).getLook());
            }
            assertEquals(QuizScreen.SUBMIT_TEXT, screen.submitButton().getText());
            assertEquals("15 s", screen.countdownText(), "the countdown starts again with the new question");
            assertEquals("Choose an answer", screen.statusBanner().getTitle());
            assertTrue(screen.isCountdownRunning());
        });
    }

    @Test
    void finishedRoundIsShownWhenNoResultsScreenIsRegistered() throws Exception {
        Client client = openQuestion(QUESTION_1);
        runOnEdt(() -> {
            screen.optionButton(AnswerOption.C).doClick(0);
            screen.submitButton().doClick(0);
        });
        client.expectLine("ANSWER|1|C");
        client.send("ANSWER_ACCEPTED|1", "ROUND_RESULT|1|B");
        waiter.await("round ended", () -> controller.getState().getPhase() == GamePhase.ROUND_ENDED);
        runOnEdt(() -> {
            assertEquals(AnswerButton.Look.CORRECT, screen.optionButton(AnswerOption.B).getLook());
            assertEquals(AnswerButton.Look.WRONG, screen.optionButton(AnswerOption.C).getLook());
            assertEquals(AnswerButton.Look.MUTED, screen.optionButton(AnswerOption.A).getLook());
            assertEquals("Round over", screen.statusBanner().getTitle());
            assertFalse(screen.isCountdownRunning());
        });
    }

    @Test
    void stopListeningStopsTheCountdown() throws Exception {
        openQuestion(QUESTION_1);
        runOnEdt(() -> {
            assertTrue(screen.isListening());
            assertTrue(screen.isCountdownRunning());
            screen.stopListening();
            assertFalse(screen.isListening());
            assertFalse(screen.isCountdownRunning());
            screen.startListening();
            assertTrue(screen.isCountdownRunning());
        });
    }

    @Test
    void countdownArithmetic() {
        assertEquals(15_000, QuizScreen.remainingMillis(15, 0));
        assertEquals(15_000, QuizScreen.remainingMillis(15, -50), "a clock step backwards is ignored");
        assertEquals(999, QuizScreen.remainingMillis(15, 14_001));
        assertEquals(0, QuizScreen.remainingMillis(15, 15_000));
        assertEquals(0, QuizScreen.remainingMillis(15, 60_000));
        assertEquals(15, QuizScreen.secondsToShow(15_000));
        assertEquals(15, QuizScreen.secondsToShow(14_001));
        assertEquals(14, QuizScreen.secondsToShow(14_000));
        assertEquals(1, QuizScreen.secondsToShow(1));
        assertEquals(0, QuizScreen.secondsToShow(0));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Joins as alice (host, with bob in the lobby). */
    private Client joinAndStart() throws Exception {
        runOnEdt(() -> assertTrue(controller.connect(server.host(), server.port(), "alice").isEmpty()));
        Client client = server.accept();
        client.expectLine("JOIN|alice");
        client.send("JOINED|alice", "PLAYERS|alice,bob", "HOST|alice");
        waiter.await("lobby", () -> "alice".equals(controller.getState().getHost()));
        return client;
    }

    /** Joins, starts the game and opens the given question. */
    private Client openQuestion(String questionLine) throws Exception {
        Client client = joinAndStart();
        client.send("GAME_STARTED|5 questions", questionLine);
        waiter.await("question", () -> controller.getState().getPhase() == GamePhase.QUESTION_ACTIVE);
        return client;
    }

    private void advance(long millis) {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }
}
