package quiz.gui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static quiz.gui.ui.UiTestSupport.onEdt;
import static quiz.gui.ui.UiTestSupport.runOnEdt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import quiz.gui.controller.GameController;
import quiz.gui.model.GamePhase;
import quiz.gui.ui.UiTestSupport.Client;
import quiz.gui.ui.UiTestSupport.LoopbackServer;
import quiz.gui.ui.UiTestSupport.StateWaiter;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ScreenNavigatorTest {

    private static final Predicate<String> PHASE_4B = Set.of(ScreenNavigator.CONNECTION, ScreenNavigator.LOBBY)::contains;
    private static final Predicate<String> ALL = Set.of(ScreenNavigator.CONNECTION, ScreenNavigator.LOBBY,
            ScreenNavigator.QUIZ, ScreenNavigator.RESULTS)::contains;

    @BeforeAll
    static void headless() {
        UiTestSupport.useHeadlessAwt();
    }

    // ------------------------------------------------------------------
    // Phase -> screen mapping
    // ------------------------------------------------------------------

    @Test
    void connectionScreenUntilJoinIsConfirmed() {
        for (GamePhase phase : List.of(GamePhase.DISCONNECTED, GamePhase.CONNECTING, GamePhase.JOINING)) {
            assertEquals(Optional.of(ScreenNavigator.CONNECTION), ScreenNavigator.screenFor(phase, ALL));
        }
        assertEquals(Optional.of(ScreenNavigator.LOBBY), ScreenNavigator.screenFor(GamePhase.LOBBY, ALL));
    }

    @Test
    void gamePhasesUseGameScreensWhenRegistered() {
        for (GamePhase phase : List.of(GamePhase.GAME_STARTING, GamePhase.QUESTION_ACTIVE)) {
            assertEquals(Optional.of(ScreenNavigator.QUIZ), ScreenNavigator.screenFor(phase, ALL));
        }
        // Phase 4C: the round results screen follows ROUND_RESULT and keeps the final round after GAME_OVER.
        for (GamePhase phase : List.of(GamePhase.ROUND_ENDED, GamePhase.GAME_OVER)) {
            assertEquals(Optional.of(ScreenNavigator.RESULTS), ScreenNavigator.screenFor(phase, ALL));
        }
    }

    @Test
    void gamePhasesFallBackToLobbyUntilGameScreensExist() {
        for (GamePhase phase : List.of(GamePhase.GAME_STARTING, GamePhase.QUESTION_ACTIVE,
                GamePhase.ROUND_ENDED, GamePhase.GAME_OVER)) {
            assertEquals(Optional.of(ScreenNavigator.LOBBY), ScreenNavigator.screenFor(phase, PHASE_4B));
        }
    }

    @Test
    void nothingToShowWithoutRegisteredScreens() {
        Predicate<String> none = name -> false;
        for (GamePhase phase : GamePhase.values()) {
            assertEquals(Optional.empty(), ScreenNavigator.screenFor(phase, none));
        }
        assertEquals(Optional.empty(), ScreenNavigator.screenFor(GamePhase.LOBBY, ScreenNavigator.CONNECTION::equals));
    }

    // ------------------------------------------------------------------
    // Following a real controller
    // ------------------------------------------------------------------

    private LoopbackServer server;
    private GameController controller;
    private StateWaiter waiter;
    private ScreenNavigator navigator;
    private final Set<String> registered = new CopyOnWriteArraySet<>(
            Set.of(ScreenNavigator.CONNECTION, ScreenNavigator.LOBBY));
    private final List<String> shown = new ArrayList<>(); // only touched on the EDT

    @BeforeEach
    void setUp() throws Exception {
        server = new LoopbackServer();
        runOnEdt(() -> {
            controller = new GameController();
            waiter = new StateWaiter(controller);
            navigator = new ScreenNavigator(controller, registered::contains, shown::add);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        runOnEdt(() -> {
            controller.disconnect();
            navigator.stop();
            waiter.detach();
        });
        server.close();
    }

    @Test
    void mustBeUsedOnTheEdt() {
        assertThrows(IllegalStateException.class, () -> navigator.start());
    }

    @Test
    void followsTheSessionFromConnectionToLobbyAndBack() throws Exception {
        runOnEdt(() -> {
            navigator.start();
            navigator.start(); // only one listener
            assertEquals(List.of(ScreenNavigator.CONNECTION), shown, "connection screen first");
            assertTrue(controller.connect(server.host(), server.port(), "alice").isEmpty());
        });
        Client client = server.accept();
        client.expectLine("JOIN|alice");
        waiter.await("joining", () -> controller.getState().getPhase() == GamePhase.JOINING);
        assertEquals(List.of(ScreenNavigator.CONNECTION), shownNow(), "no lobby before JOINED");

        client.send("JOINED|alice", "PLAYERS|alice,bob", "HOST|alice");
        waiter.await("lobby", () -> "alice".equals(controller.getState().getHost()));
        assertEquals(List.of(ScreenNavigator.CONNECTION, ScreenNavigator.LOBBY), shownNow(),
                "lobby shown once, not again for PLAYERS/HOST");

        // No quiz screen yet (Phase 4C): GAME_STARTED keeps the lobby.
        runOnEdt(controller::startGame);
        client.expectLine("START");
        client.send("GAME_STARTED|5 questions");
        waiter.await("started", () -> controller.getState().getPhase() == GamePhase.GAME_STARTING);
        assertEquals(List.of(ScreenNavigator.CONNECTION, ScreenNavigator.LOBBY), shownNow());

        // Once a quiz screen is registered, the next game event goes there.
        registered.add(ScreenNavigator.QUIZ);
        client.send("QUESTION|1|5|15|Which Java class listens for TCP connections?"
                + "|Socket|ServerSocket|DatagramSocket|Thread");
        waiter.await("question", () -> controller.getState().getPhase() == GamePhase.QUESTION_ACTIVE);
        assertEquals(ScreenNavigator.QUIZ, onEdt(() -> navigator.getShownScreen().orElseThrow()));

        // Unexpected disconnect: back to the connection screen.
        client.close();
        waiter.await("disconnected", () -> controller.getState().getPhase() == GamePhase.DISCONNECTED);
        assertEquals(List.of(ScreenNavigator.CONNECTION, ScreenNavigator.LOBBY, ScreenNavigator.QUIZ,
                ScreenNavigator.CONNECTION), shownNow());
    }

    @Test
    void rejectedJoinNeverShowsTheLobby() throws Exception {
        runOnEdt(() -> {
            navigator.start();
            controller.connect(server.host(), server.port(), "alice");
        });
        Client client = server.accept();
        client.expectLine("JOIN|alice");
        client.send("ERROR|Username already taken");
        client.close();
        waiter.await("disconnected", () -> !controller.hasActiveConnection());
        assertEquals(List.of(ScreenNavigator.CONNECTION), shownNow());
    }

    @Test
    void stopRemovesTheListener() throws Exception {
        runOnEdt(() -> {
            navigator.start();
            navigator.stop();
            controller.connect(server.host(), server.port(), "alice");
        });
        Client client = server.accept();
        client.expectLine("JOIN|alice");
        client.send("JOINED|alice");
        waiter.await("lobby", () -> controller.getState().getPhase() == GamePhase.LOBBY);
        assertEquals(List.of(ScreenNavigator.CONNECTION), shownNow());
    }

    private List<String> shownNow() throws Exception {
        return onEdt(() -> List.copyOf(shown));
    }
}
