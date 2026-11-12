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
import quiz.gui.model.GamePhase;
import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;
import quiz.gui.ui.UiTestSupport.Client;
import quiz.gui.ui.UiTestSupport.LoopbackServer;
import quiz.gui.ui.UiTestSupport.StateWaiter;
import quiz.gui.ui.components.StatusBanner;

/**
 * Lobby screen against the real controller and a scripted loopback server (headless).
 * "No extra command was sent" is proven by disconnecting and checking that the next line
 * the server receives is QUIT.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class LobbyScreenTest {

    private LoopbackServer server;
    private GameController controller;
    private LobbyScreen screen;
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
            screen = new LobbyScreen(controller);
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

    @Test
    void hostAloneCannotStartYet() throws Exception {
        join("alice", "PLAYERS|alice", "HOST|alice");
        runOnEdt(() -> {
            assertEquals("Playing as alice", screen.subtitleText());
            assertEquals("Host", screen.rolePillText());
            assertEquals("You are the host", screen.roleTitleText());
            assertEquals("1 player", screen.playerCountText());
            assertEquals(List.of("alice, host, you"), screen.playerRowDescriptions());
            assertTrue(screen.startButton().isVisible());
            assertFalse(screen.startButton().isEnabled());
            assertEquals("Start Game", screen.startButton().getText());
            assertEquals(StatusBanner.Kind.WARNING, screen.statusBanner().getKind());
            assertTrue(screen.statusBanner().getMessage().startsWith("At least two players are required"));
        });
    }

    @Test
    void hostStartsOnceAndDuplicateClicksAreIgnored() throws Exception {
        Client client = join("alice", "PLAYERS|alice,bob", "HOST|alice", "INFO|bob joined the lobby");
        waiter.await("two players", () -> controller.getState().getPlayers().size() == 2);
        runOnEdt(() -> {
            assertEquals("2 players", screen.playerCountText());
            assertEquals(List.of("alice, host, you", "bob"), screen.playerRowDescriptions());
            assertEquals("bob joined the lobby", screen.latestActivityText());
            assertTrue(screen.startButton().isEnabled());
            assertEquals(StatusBanner.Kind.SUCCESS, screen.statusBanner().getKind());
            assertEquals("Ready to start", screen.statusBanner().getTitle());

            screen.startButton().doClick(0);
            // Disabled at once, while the server has not answered yet.
            assertFalse(screen.startButton().isEnabled());
            assertEquals("Starting…", screen.startButton().getText());
            assertEquals(StatusBanner.Kind.PROGRESS, screen.statusBanner().getKind());
            screen.startButton().doClick(0);
            screen.startButton().doClick(0);
        });
        client.expectLine("START");

        client.send("GAME_STARTED|5 questions");
        waiter.await("game started", () -> controller.getState().getPhase() == GamePhase.GAME_STARTING);
        runOnEdt(() -> {
            assertFalse(screen.startButton().isEnabled());
            assertEquals("Game in progress", screen.statusBanner().getTitle());
            controller.disconnect();
        });
        client.expectLine("QUIT"); // proves no second START was sent
    }

    @Test
    void nonHostWaitsAndCannotStart() throws Exception {
        Client client = join("bob", "PLAYERS|alice,bob", "HOST|alice");
        waiter.await("host known", () -> "alice".equals(controller.getState().getHost()));
        runOnEdt(() -> {
            assertEquals("Player", screen.rolePillText());
            assertEquals("You are a player", screen.roleTitleText());
            assertEquals("alice is the host. Only the host can start the game.", screen.roleDescriptionText());
            assertEquals(List.of("alice, host", "bob, you"), screen.playerRowDescriptions());
            assertFalse(screen.startButton().isVisible());
            assertFalse(screen.startButton().isEnabled());
            assertEquals(StatusBanner.Kind.PROGRESS, screen.statusBanner().getKind());
            assertEquals("Waiting for alice to start the game.", screen.statusBanner().getMessage());

            screen.startButton().doClick(0); // disabled: nothing happens
            controller.disconnect();
        });
        client.expectLine("QUIT");
    }

    @Test
    void hostChangeAndPlayersLeavingUpdateTheLobby() throws Exception {
        Client client = join("bob", "PLAYERS|alice,bob,carol", "HOST|alice");
        waiter.await("three players", () -> controller.getState().getPlayers().size() == 3);
        runOnEdt(() -> assertFalse(screen.startButton().isVisible()));

        client.send("INFO|alice left the lobby", "PLAYERS|bob,carol", "HOST|bob");
        waiter.await("bob is host", () -> controller.getState().isHost());
        runOnEdt(() -> {
            assertEquals("Host", screen.rolePillText());
            assertEquals("2 players", screen.playerCountText());
            assertEquals(List.of("bob, host, you", "carol"), screen.playerRowDescriptions());
            assertEquals("alice left the lobby", screen.latestActivityText());
            assertTrue(screen.startButton().isVisible());
            assertTrue(screen.startButton().isEnabled());
        });

        client.send("PLAYERS|bob");
        waiter.await("one player", () -> controller.getState().getPlayers().size() == 1);
        runOnEdt(() -> {
            assertFalse(screen.startButton().isEnabled());
            assertEquals("Waiting for more players", screen.statusBanner().getTitle());
        });
    }

    @Test
    void serverErrorIsShownVerbatimUntilTheLobbyChanges() throws Exception {
        Client client = join("alice", "PLAYERS|alice,bob", "HOST|alice");
        waiter.await("can start", () -> controller.canStartGame());
        runOnEdt(() -> screen.startButton().doClick(0));
        client.expectLine("START");

        client.send("ERROR|Game already running");
        waiter.await("start answered", () -> controller.canStartGame());
        runOnEdt(() -> {
            assertEquals(StatusBanner.Kind.ERROR, screen.statusBanner().getKind());
            assertEquals("The server refused the request", screen.statusBanner().getTitle());
            assertEquals("Game already running", screen.statusBanner().getMessage());
            assertTrue(screen.startButton().isEnabled(), "the host may try again");
            assertEquals("Start Game", screen.startButton().getText());
        });

        client.send("PLAYERS|alice,bob,carol");
        waiter.await("carol", () -> controller.getState().getPlayers().size() == 3);
        runOnEdt(() -> assertEquals("Ready to start", screen.statusBanner().getTitle()));
    }

    @Test
    void playAgainAfterGameOverUsesTheSameStartRules() throws Exception {
        Client client = join("alice", "PLAYERS|alice,bob", "HOST|alice");
        waiter.await("can start", () -> controller.canStartGame());
        runOnEdt(() -> screen.startButton().doClick(0));
        client.expectLine("START");
        client.send("GAME_STARTED|5 questions", "GAME_OVER|alice|3");
        waiter.await("game over", () -> controller.getState().getPhase() == GamePhase.GAME_OVER);

        runOnEdt(() -> {
            assertEquals("Play Again", screen.startButton().getText());
            assertTrue(screen.startButton().isEnabled());
            assertEquals("Ready to play again", screen.statusBanner().getTitle());
            screen.startButton().doClick(0);
            assertFalse(screen.startButton().isEnabled());
        });
        client.expectLine("START");
    }

    @Test
    void nonHostSeesGameOverWaitingMessage() throws Exception {
        Client client = join("bob", "PLAYERS|alice,bob", "HOST|alice");
        client.send("GAME_STARTED|5 questions", "GAME_OVER|alice|3");
        waiter.await("game over", () -> controller.getState().getPhase() == GamePhase.GAME_OVER);
        runOnEdt(() -> {
            assertFalse(screen.startButton().isVisible());
            assertEquals("Game over", screen.statusBanner().getTitle());
            assertEquals("Waiting for alice to start a new game.", screen.statusBanner().getMessage());
        });
    }

    @Test
    void disconnectButtonEndsTheSession() throws Exception {
        Client client = join("alice", "PLAYERS|alice", "HOST|alice");
        runOnEdt(() -> {
            screen.disconnectButton().doClick(0);
            assertFalse(controller.hasActiveConnection());
            assertEquals(GamePhase.DISCONNECTED, controller.getState().getPhase());
        });
        client.expectLine("QUIT");
    }

    @Test
    void listenerIsRemovedWhenStopped() throws Exception {
        Client client = join("alice", "PLAYERS|alice", "HOST|alice");
        runOnEdt(() -> {
            screen.stopListening();
            assertFalse(screen.isListening());
        });
        client.send("PLAYERS|alice,bob");
        waiter.await("bob", () -> controller.getState().getPlayers().size() == 2);
        runOnEdt(() -> {
            assertEquals("1 player", screen.playerCountText(), "not updated while stopped");
            screen.startListening();
            assertEquals("2 players", screen.playerCountText());
        });
    }

    /** Connects through the controller (as the connection screen would) and joins. */
    private Client join(String username, String... afterJoined) throws Exception {
        runOnEdt(() -> assertTrue(controller.connect(server.host(), server.port(), username).isEmpty()));
        Client client = server.accept();
        client.expectLine("JOIN|" + username);
        String[] lines = new String[afterJoined.length + 1];
        lines[0] = "JOINED|" + username;
        System.arraycopy(afterJoined, 0, lines, 1, afterJoined.length);
        client.send(lines);
        waiter.await("lobby", () -> controller.getState().getPhase() == GamePhase.LOBBY
                && controller.getState().getHost() != null);
        return client;
    }
}
