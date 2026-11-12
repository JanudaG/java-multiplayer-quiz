package quiz.gui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static quiz.gui.ui.UiTestSupport.runOnEdt;

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
 * Connection screen against the real controller and a scripted loopback server
 * (headless; buttons are clicked with {@code doClick}).
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ConnectionScreenTest {

    private LoopbackServer server;
    private GameController controller;
    private ConnectionScreen screen;
    private StateWaiter waiter;

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
            screen = new ConnectionScreen(controller);
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
    // Initial state and validation
    // ------------------------------------------------------------------

    @Test
    void startsWithDefaultsAndEnabledControls() throws Exception {
        runOnEdt(() -> {
            assertEquals("127.0.0.1", screen.hostField().getText());
            assertEquals("5000", screen.portField().getText());
            assertEquals("", screen.usernameField().getText());
            assertTrue(screen.connectButton().isEnabled());
            assertEquals("Connect", screen.connectButton().getText());
            assertFalse(screen.cancelButton().isVisible());
            assertFalse(screen.progressBar().isVisible());
            assertFalse(screen.statusBanner().isVisible());
            assertTrue(screen.isListening());
        });
    }

    @Test
    void blankUsernameShowsControllerMessageAndDoesNotConnect() throws Exception {
        fill(server.host(), String.valueOf(server.port()), "   ");
        clickConnect();
        runOnEdt(() -> {
            assertBanner(StatusBanner.Kind.ERROR, "Please check your details", "Please enter a username.");
            assertTrue(screen.isFieldMarkedInvalid(screen.usernameField()));
            assertFalse(controller.hasActiveConnection());
            assertEquals(GamePhase.DISCONNECTED, controller.getState().getPhase());
            assertTrue(screen.connectButton().isEnabled());
        });
        assertEquals(0, server.acceptedCount());
    }

    @Test
    void invalidHostPortAndUsernameUseControllerWording() throws Exception {
        fill("  ", "5000", "alice");
        clickConnect();
        runOnEdt(() -> {
            assertEquals("Please enter the server address.", screen.statusBanner().getMessage());
            assertTrue(screen.isFieldMarkedInvalid(screen.hostField()));
        });

        fill("127.0.0.1", "", "alice");
        clickConnect();
        runOnEdt(() -> {
            assertEquals("Port must be between 1 and 65535.", screen.statusBanner().getMessage());
            assertTrue(screen.isFieldMarkedInvalid(screen.portField()));
            assertFalse(screen.isFieldMarkedInvalid(screen.hostField()), "old marks are cleared");
        });

        fill("127.0.0.1", "70000", "alice");
        clickConnect();
        runOnEdt(() -> assertEquals("Port must be between 1 and 65535.", screen.statusBanner().getMessage()));

        fill("127.0.0.1", "5000", "bad name");
        clickConnect();
        runOnEdt(() -> {
            assertEquals("Use only letters, digits, _ or - in the username.", screen.statusBanner().getMessage());
            assertTrue(screen.isFieldMarkedInvalid(screen.usernameField()));
            screen.usernameField().setText("alice");
            assertFalse(screen.isFieldMarkedInvalid(screen.usernameField()), "editing clears the mark");
            assertFalse(controller.hasActiveConnection());
        });
        assertEquals(0, server.acceptedCount());
    }

    @Test
    void portFieldAcceptsAtMostFiveDigits() throws Exception {
        runOnEdt(() -> {
            screen.portField().setText("50a0x12345");
            assertEquals("50012", screen.portField().getText());
        });
        assertEquals(-1, ConnectionScreen.parsePort(""));
        assertEquals(-1, ConnectionScreen.parsePort("abc"));
        assertEquals(5000, ConnectionScreen.parsePort(" 5000 "));
    }

    // ------------------------------------------------------------------
    // Connecting
    // ------------------------------------------------------------------

    @Test
    void successfulJoinShowsProgressThenSuccessAndAllowsOnlyOneAttempt() throws Exception {
        fill(server.host(), String.valueOf(server.port()), "  alice ");
        clickConnect();
        runOnEdt(() -> {
            // Disabled synchronously, before any network event.
            assertFalse(screen.connectButton().isEnabled());
            assertEquals("Connecting…", screen.connectButton().getText());
            assertFalse(screen.hostField().isEnabled());
            assertFalse(screen.portField().isEnabled());
            assertFalse(screen.usernameField().isEnabled());
            assertTrue(screen.cancelButton().isVisible());
            assertTrue(screen.progressBar().isVisible());
            assertEquals(StatusBanner.Kind.PROGRESS, screen.statusBanner().getKind());
        });

        Client client = server.accept();
        client.expectLine("JOIN|alice");
        waiter.await("joining banner", () -> "Joining the lobby…".equals(screen.statusBanner().getTitle()));

        // Repeated attempts (button and Enter) are ignored while connecting.
        runOnEdt(() -> {
            screen.connectButton().doClick(0);
            screen.usernameField().postActionEvent();
            assertTrue(controller.hasActiveConnection());
        });

        client.send("JOINED|alice", "PLAYERS|alice", "HOST|alice");
        waiter.await("lobby", () -> controller.getState().getPhase() == GamePhase.LOBBY);
        runOnEdt(() -> {
            assertBanner(StatusBanner.Kind.SUCCESS, "Joined as alice", "Opening the lobby…");
            assertFalse(screen.progressBar().isVisible());
        });
        assertEquals(1, server.acceptedCount(), "only one connection may be opened");
    }

    @Test
    void rejectedJoinShowsServerMessageUnchangedAndReEnablesControls() throws Exception {
        Client client = connectThroughScreen("alice");
        client.send("ERROR|Username already taken");
        client.close();

        waiter.await("disconnected", () -> controller.getState().getPhase() == GamePhase.DISCONNECTED);
        runOnEdt(() -> {
            assertBanner(StatusBanner.Kind.ERROR, "Could not join the game", "Username already taken");
            assertTrue(screen.isFieldMarkedInvalid(screen.usernameField()));
            assertControlsEnabled();
        });
    }

    @Test
    void refusedConnectionExplainsTheProblem() throws Exception {
        int port = LoopbackServer.unusedPort();
        fill(server.host(), String.valueOf(port), "alice");
        clickConnect();

        waiter.await("connect failure", () -> !controller.hasActiveConnection());
        runOnEdt(() -> {
            StatusBanner banner = screen.statusBanner();
            assertEquals(StatusBanner.Kind.ERROR, banner.getKind());
            assertEquals("Could not connect", banner.getTitle());
            assertTrue(banner.getMessage().startsWith("Could not connect to " + server.host() + ":" + port),
                    banner.getMessage());
            assertTrue(banner.getMessage().contains("Check that the quiz server is running"));
            assertControlsEnabled();
        });
    }

    @Test
    void lostConnectionAfterJoiningIsReported() throws Exception {
        Client client = connectThroughScreen("alice");
        client.send("JOINED|alice");
        waiter.await("lobby", () -> controller.getState().getPhase() == GamePhase.LOBBY);

        client.close();
        waiter.await("disconnected", () -> controller.getState().getPhase() == GamePhase.DISCONNECTED);
        runOnEdt(() -> {
            assertEquals(StatusBanner.Kind.ERROR, screen.statusBanner().getKind());
            assertEquals("Disconnected from the server", screen.statusBanner().getTitle());
            assertTrue(screen.statusBanner().getMessage().startsWith("Server closed the connection"));
            assertControlsEnabled();
            assertEquals("alice", screen.usernameField().getText(), "input is kept for reconnecting");
        });
    }

    @Test
    void cancelEndsTheAttempt() throws Exception {
        Client client = connectThroughScreen("alice");
        runOnEdt(() -> screen.cancelButton().doClick(0));
        client.expectLine("QUIT");
        runOnEdt(() -> {
            assertBanner(StatusBanner.Kind.INFO, "Connection cancelled", "");
            assertControlsEnabled();
        });
    }

    @Test
    void leavingTheLobbyIsNotShownAsAnError() throws Exception {
        Client client = connectThroughScreen("alice");
        client.send("JOINED|alice");
        waiter.await("lobby", () -> controller.getState().getPhase() == GamePhase.LOBBY);

        runOnEdt(controller::disconnect); // e.g. the lobby's Disconnect button
        client.expectLine("QUIT");
        runOnEdt(() -> {
            assertBanner(StatusBanner.Kind.INFO, "You left the game", "Connect again whenever you are ready.");
            assertControlsEnabled();
        });
    }

    @Test
    void listenerIsRemovedWhenStopped() throws Exception {
        runOnEdt(() -> {
            screen.stopListening();
            assertFalse(screen.isListening());
            assertTrue(controller.connect(server.host(), server.port(), "alice").isEmpty());
            // No longer following the controller: nothing was updated.
            assertTrue(screen.connectButton().isEnabled());
            assertFalse(screen.statusBanner().isVisible());

            screen.startListening(); // shows the current state again
            assertFalse(screen.connectButton().isEnabled());
            screen.startListening(); // registering twice has no effect
        });
        server.accept().expectLine("JOIN|alice");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Client connectThroughScreen(String username) throws Exception {
        fill(server.host(), String.valueOf(server.port()), username);
        clickConnect();
        Client client = server.accept();
        client.expectLine("JOIN|" + username);
        waiter.await("joining", () -> controller.getState().getPhase() == GamePhase.JOINING);
        return client;
    }

    private void fill(String host, String port, String username) throws Exception {
        runOnEdt(() -> {
            screen.hostField().setText(host);
            screen.portField().setText(port);
            screen.usernameField().setText(username);
        });
    }

    private void clickConnect() throws Exception {
        runOnEdt(() -> screen.connectButton().doClick(0));
    }

    private void assertBanner(StatusBanner.Kind kind, String title, String message) {
        StatusBanner banner = screen.statusBanner();
        assertTrue(banner.isVisible());
        assertEquals(kind, banner.getKind());
        assertEquals(title, banner.getTitle());
        assertEquals(message, banner.getMessage());
    }

    private void assertControlsEnabled() {
        assertTrue(screen.connectButton().isEnabled());
        assertEquals("Connect", screen.connectButton().getText());
        assertTrue(screen.hostField().isEnabled());
        assertTrue(screen.portField().isEnabled());
        assertTrue(screen.usernameField().isEnabled());
        assertFalse(screen.cancelButton().isVisible());
        assertFalse(screen.progressBar().isVisible());
    }
}
