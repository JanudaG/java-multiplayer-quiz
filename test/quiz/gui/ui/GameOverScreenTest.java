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
 * Game over screen against the real controller and a scripted loopback server (headless),
 * using the real server's final burst: ROUND_RESULT, SCORES, SCORES, GAME_OVER.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class GameOverScreenTest {

    private static final String LAST_QUESTION = "QUESTION|5|5|15|Which port range is reserved?"
            + "|0-1023|1024-2047|8000-9000|49152-65535";

    private LoopbackServer server;
    private GameController controller;
    private GameOverScreen screen;
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
            screen = new GameOverScreen(controller);
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
    // Winners and final leaderboard
    // ------------------------------------------------------------------

    @Test
    void beforeTheGameIsOverNothingIsShown() throws Exception {
        runOnEdt(() -> {
            assertEquals("Waiting for the final results", screen.headlineText());
            assertEquals(List.of(), screen.scoreRowDescriptions());
            assertEquals("No scores yet.", screen.emptyLeaderboardText());
            assertFalse(screen.playAgainButton().isVisible());
            assertFalse(screen.statusBanner().isVisible());
            assertFalse(screen.resultBanner().isVisible());
        });
    }

    @Test
    void singleWinnerIsTheLocalPlayer() throws Exception {
        finishGame("alice", "PLAYERS|alice,bob,carol", "HOST|alice",
                "SCORES|alice:3,bob:2,carol:0", "GAME_OVER|alice|3");
        runOnEdt(() -> {
            assertEquals("Game Over", screen.titleText());
            assertEquals("Final results · 5 questions · Playing as alice", screen.subtitleText());
            assertEquals("You win!", screen.headlineText());
            assertEquals("Winner: alice (you)", screen.winnerNamesText());
            assertEquals("Winning score: 3 points", screen.winningScoreText());
            assertEquals(StatusBanner.Kind.SUCCESS, screen.resultBanner().getKind());
            assertEquals("Congratulations!", screen.resultBanner().getTitle());
            assertEquals("You won with 3 points.", screen.resultBanner().getMessage());
            assertEquals(List.of("1. alice, you, winner, 3 points", "2. bob, 2 points", "3. carol, 0 points"),
                    screen.scoreRowDescriptions());
            assertEquals("3 players in the lobby", screen.playerCountText());
        });
    }

    @Test
    void anotherPlayerWins() throws Exception {
        finishGame("bob", "PLAYERS|alice,bob", "HOST|alice", "SCORES|alice:1,bob:0", "GAME_OVER|alice|1");
        runOnEdt(() -> {
            assertEquals("alice wins!", screen.headlineText());
            assertEquals("Winner: alice", screen.winnerNamesText());
            assertEquals("Winning score: 1 point", screen.winningScoreText());
            assertEquals(StatusBanner.Kind.INFO, screen.resultBanner().getKind());
            assertEquals("Good game!", screen.resultBanner().getTitle());
            assertEquals("You scored 0 points.", screen.resultBanner().getMessage());
            assertEquals(List.of("1. alice, winner, 1 point", "2. bob, you, 0 points"), screen.scoreRowDescriptions());
        });
    }

    @Test
    void tiedWinnersAreAllShownWithTheirSharedScore() throws Exception {
        finishGame("alice", "PLAYERS|alice,bob,carol", "HOST|alice",
                "SCORES|alice:2,bob:2,carol:1", "GAME_OVER|alice,bob|2");
        runOnEdt(() -> {
            assertEquals("It's a tie!", screen.headlineText());
            assertEquals("Winners: alice (you) and bob", screen.winnerNamesText());
            assertEquals("Winning score: 2 points each", screen.winningScoreText());
            assertEquals("You share the win!", screen.resultBanner().getTitle());
            assertEquals(List.of("1. alice, you, winner, 2 points", "1. bob, winner, 2 points", "3. carol, 1 point"),
                    screen.scoreRowDescriptions(), "tied players share rank 1");
        });
    }

    @Test
    void threeWayTieAtZeroIsShownAsTheServerSentIt() throws Exception {
        finishGame("dave", "PLAYERS|alice,bob,carol,dave", "HOST|alice",
                "SCORES|alice:0,bob:0,carol:0,dave:0", "GAME_OVER|alice,bob,carol,dave|0");
        runOnEdt(() -> {
            assertEquals("It's a tie!", screen.headlineText());
            assertEquals("Winners: alice, bob, carol and dave (you)", screen.winnerNamesText());
            assertEquals("Winning score: 0 points each", screen.winningScoreText());
            assertEquals(4, screen.scoreRowDescriptions().size());
            assertTrue(screen.scoreRowDescriptions().stream().allMatch(row -> row.startsWith("1. ")));
        });
    }

    @Test
    void winnersComeFromGameOverNotFromTheScores() throws Exception {
        // The model's winners are shown as sent, even if the last SCORES would suggest otherwise.
        finishGame("alice", "PLAYERS|alice,bob", "HOST|alice", "SCORES|bob:5,alice:1", "GAME_OVER|alice|1");
        runOnEdt(() -> {
            assertEquals("You win!", screen.headlineText());
            assertEquals(List.of("1. bob, 5 points", "2. alice, you, winner, 1 point"), screen.scoreRowDescriptions());
        });
    }

    @Test
    void noWinnerAndNoScoresAreHandledSafely() throws Exception {
        finishGame("alice", "PLAYERS|alice,bob", "HOST|alice", "SCORES|", "GAME_OVER||0");
        runOnEdt(() -> {
            assertEquals("No winner", screen.headlineText());
            assertEquals("The game ended without a winner.", screen.winnerNamesText());
            assertEquals("", screen.winningScoreText());
            assertFalse(screen.resultBanner().isVisible());
            assertEquals(List.of(), screen.scoreRowDescriptions());
            assertEquals("No final scores were received.", screen.emptyLeaderboardText());
            assertTrue(screen.playAgainButton().isVisible(), "the host can still play again");
        });
    }

    @Test
    void gameOverWithoutAnyScoresStillShowsTheWinner() throws Exception {
        Client client = join("alice", "PLAYERS|alice,bob", "HOST|alice");
        client.send("GAME_STARTED|5 questions", LAST_QUESTION, "GAME_OVER|bob|1");
        waiter.await("game over", () -> controller.getState().getPhase() == GamePhase.GAME_OVER);
        runOnEdt(() -> {
            assertEquals("bob wins!", screen.headlineText());
            assertEquals("Your final score was not received.", screen.resultBanner().getMessage());
            assertEquals("No final scores were received.", screen.emptyLeaderboardText());
        });
    }

    @Test
    void finalResultsStayWhenPlayersLeaveOrTheHostChanges() throws Exception {
        Client client = finishGame("bob", "PLAYERS|alice,bob,carol", "HOST|alice",
                "SCORES|alice:3,bob:2,carol:1", "GAME_OVER|alice|3");
        client.send("PLAYERS|bob,carol", "HOST|bob", "INFO|alice left the game");
        waiter.await("new host", () -> "bob".equals(controller.getState().getHost()));
        runOnEdt(() -> {
            assertEquals("alice wins!", screen.headlineText());
            assertEquals(List.of("1. alice, winner, 3 points", "2. bob, you, 2 points", "3. carol, 1 point"),
                    screen.scoreRowDescriptions());
            assertEquals("2 players in the lobby", screen.playerCountText());
            assertTrue(screen.playAgainButton().isVisible(), "bob is now the host");
            assertTrue(screen.playAgainButton().isEnabled());
        });
    }

    // ------------------------------------------------------------------
    // Host and non-host controls
    // ------------------------------------------------------------------

    @Test
    void hostSeesAnEnabledPlayAgain() throws Exception {
        finishDefaultGame("alice");
        runOnEdt(() -> {
            assertTrue(screen.playAgainButton().isVisible());
            assertTrue(screen.playAgainButton().isEnabled());
            assertEquals(GameOverScreen.PLAY_AGAIN_TEXT, screen.playAgainButton().getText());
            assertEquals(StatusBanner.Kind.SUCCESS, screen.statusBanner().getKind());
            assertEquals("Ready to play again", screen.statusBanner().getTitle());
            assertEquals("Press Play Again to start a new game with 2 players.", screen.statusBanner().getMessage());
        });
    }

    @Test
    void otherPlayersWaitForTheNamedHost() throws Exception {
        Client client = finishDefaultGame("bob");
        runOnEdt(() -> {
            assertFalse(screen.playAgainButton().isVisible());
            assertEquals(StatusBanner.Kind.PROGRESS, screen.statusBanner().getKind());
            assertEquals("Waiting for the host", screen.statusBanner().getTitle());
            assertEquals("Waiting for alice to start a new game.", screen.statusBanner().getMessage());
            controller.disconnect();
        });
        client.expectLine("QUIT"); // nothing else was sent
    }

    @Test
    void otherPlayersWaitForTheHostWhenTheHostIsUnknown() throws Exception {
        runOnEdt(() -> assertTrue(controller.connect(server.host(), server.port(), "bob").isEmpty()));
        Client client = server.accept();
        client.expectLine("JOIN|bob");
        client.send("JOINED|bob", "PLAYERS|alice,bob", "GAME_STARTED|5 questions", LAST_QUESTION,
                "ROUND_RESULT|5|A", "SCORES|alice:1,bob:0", "GAME_OVER|alice|1");
        waiter.await("game over", () -> controller.getState().getPhase() == GamePhase.GAME_OVER);
        runOnEdt(() -> {
            assertFalse(screen.playAgainButton().isVisible());
            assertEquals("Waiting for the host to start a new game.", screen.statusBanner().getMessage());
        });
    }

    @Test
    void hostAloneIsToldWhyPlayAgainIsDisabled() throws Exception {
        Client client = finishDefaultGame("alice");
        client.send("PLAYERS|alice");
        waiter.await("bob left", () -> controller.getState().getPlayers().size() == 1);
        runOnEdt(() -> {
            assertTrue(screen.playAgainButton().isVisible());
            assertFalse(screen.playAgainButton().isEnabled());
            assertEquals(StatusBanner.Kind.WARNING, screen.statusBanner().getKind());
            assertEquals("At least two players are required to play again.", screen.statusBanner().getMessage());
            screen.playAgainButton().doClick(0); // disabled: nothing happens
            assertEquals("You win!", screen.headlineText());
            controller.disconnect();
        });
        client.expectLine("QUIT"); // no START was sent
    }

    // ------------------------------------------------------------------
    // Play Again
    // ------------------------------------------------------------------

    @Test
    void playAgainSendsOneStartThroughTheController() throws Exception {
        Client client = finishDefaultGame("alice");
        runOnEdt(() -> {
            screen.playAgainButton().doClick(0);
            assertFalse(screen.playAgainButton().isEnabled());
            assertEquals(GameOverScreen.STARTING_TEXT, screen.playAgainButton().getText());
            assertEquals("Starting a new game…", screen.statusBanner().getTitle());
            assertEquals("You win!", screen.headlineText(), "the results stay while waiting");
            screen.playAgainButton().doClick(0);
            screen.playAgainButton().doClick(0);
        });
        client.expectLine("START");
        client.send("GAME_STARTED|5 questions");
        waiter.await("new game", () -> controller.getState().getPhase() == GamePhase.GAME_STARTING);
        runOnEdt(() -> {
            assertFalse(screen.playAgainButton().isVisible());
            assertFalse(screen.statusBanner().isVisible());
            assertEquals("You win!", screen.headlineText(), "the last results stay until the screen changes");
            assertEquals(2, screen.scoreRowDescriptions().size());
            controller.disconnect();
        });
        client.expectLine("QUIT"); // proves that only one START was sent
    }

    @Test
    void theNextGameOverShowsTheNewResults() throws Exception {
        Client client = finishDefaultGame("alice");
        runOnEdt(() -> screen.playAgainButton().doClick(0));
        client.expectLine("START");
        client.send("GAME_STARTED|5 questions", LAST_QUESTION, "ROUND_RESULT|5|A",
                "SCORES|bob:4,alice:1", "SCORES|bob:4,alice:1", "GAME_OVER|bob|4");
        waiter.await("second game over", () -> controller.getState().getWinners().equals(List.of("bob")));
        runOnEdt(() -> {
            assertEquals("bob wins!", screen.headlineText());
            assertEquals(List.of("1. bob, winner, 4 points", "2. alice, you, 1 point"), screen.scoreRowDescriptions());
            assertTrue(screen.playAgainButton().isEnabled());
            assertEquals(StatusBanner.Kind.SUCCESS, screen.statusBanner().getKind());
        });
    }

    // ------------------------------------------------------------------
    // Server errors
    // ------------------------------------------------------------------

    @Test
    void refusedPlayAgainShowsTheErrorAndKeepsTheResults() throws Exception {
        Client client = finishDefaultGame("alice");
        runOnEdt(() -> screen.playAgainButton().doClick(0));
        client.expectLine("START");
        client.send("ERROR|At least two players are required");
        waiter.await("refused", () -> controller.canStartGame());
        runOnEdt(() -> {
            assertEquals(StatusBanner.Kind.ERROR, screen.statusBanner().getKind());
            assertEquals("The server refused the request", screen.statusBanner().getTitle());
            assertEquals("At least two players are required", screen.statusBanner().getMessage());
            assertTrue(screen.playAgainButton().isEnabled(), "the host may try again");
            assertEquals("You win!", screen.headlineText());
            assertEquals(List.of("1. alice, you, winner, 2 points", "2. bob, 1 point"), screen.scoreRowDescriptions());
            assertEquals(GamePhase.GAME_OVER, controller.getState().getPhase());
        });

        // The same refusal again is a new error and is shown again.
        runOnEdt(() -> {
            screen.playAgainButton().doClick(0);
            assertEquals(StatusBanner.Kind.PROGRESS, screen.statusBanner().getKind());
        });
        client.expectLine("START");
        client.send("ERROR|At least two players are required");
        waiter.await("refused again", () -> controller.canStartGame());
        runOnEdt(() -> assertEquals(StatusBanner.Kind.ERROR, screen.statusBanner().getKind()));
    }

    @Test
    void anErrorDisappearsWhenTheLobbyChanges() throws Exception {
        Client client = finishDefaultGame("alice");
        runOnEdt(() -> screen.playAgainButton().doClick(0));
        client.expectLine("START");
        client.send("ERROR|Game already running");
        waiter.await("refused", () -> controller.canStartGame());
        client.send("PLAYERS|alice,bob,carol");
        waiter.await("carol joined", () -> controller.getState().getPlayers().size() == 3);
        runOnEdt(() -> {
            assertEquals(StatusBanner.Kind.SUCCESS, screen.statusBanner().getKind());
            assertEquals("Press Play Again to start a new game with 3 players.", screen.statusBanner().getMessage());
        });
    }

    @Test
    void nonHostSeesAServerErrorWithoutLosingTheResults() throws Exception {
        Client client = finishDefaultGame("bob");
        client.send("ERROR|Unknown command");
        waiter.await("error", () -> "Unknown command".equals(controller.getState().getLastError()));
        runOnEdt(() -> {
            assertEquals(StatusBanner.Kind.ERROR, screen.statusBanner().getKind());
            assertEquals("Unknown command", screen.statusBanner().getMessage());
            assertEquals("alice wins!", screen.headlineText());
            assertEquals(2, screen.scoreRowDescriptions().size());
        });
    }

    @Test
    void anErrorFromTheGameIsNotShownOnTheGameOverScreen() throws Exception {
        Client client = join("alice", "PLAYERS|alice,bob", "HOST|alice");
        client.send("GAME_STARTED|5 questions", LAST_QUESTION, "ERROR|You already answered this round",
                "ROUND_RESULT|5|A", "SCORES|bob:1,alice:0", "SCORES|bob:1,alice:0", "GAME_OVER|bob|1");
        waiter.await("game over", () -> controller.getState().getPhase() == GamePhase.GAME_OVER);
        runOnEdt(() -> {
            assertEquals(StatusBanner.Kind.SUCCESS, screen.statusBanner().getKind());
            assertEquals("Ready to play again", screen.statusBanner().getTitle());
        });
    }

    // ------------------------------------------------------------------
    // Leaving and listener lifecycle
    // ------------------------------------------------------------------

    @Test
    void leaveGameDisconnectsThroughTheController() throws Exception {
        Client client = finishDefaultGame("alice");
        runOnEdt(() -> screen.leaveButton().doClick(0));
        client.expectLine("QUIT");
        runOnEdt(() -> assertEquals(GamePhase.DISCONNECTED, controller.getState().getPhase()));
    }

    @Test
    void stopListeningFreezesTheScreen() throws Exception {
        runOnEdt(() -> {
            screen.startListening(); // already listening: no second listener
            assertTrue(screen.isListening());
            screen.stopListening();
            assertFalse(screen.isListening());
        });
        finishDefaultGame("alice");
        runOnEdt(() -> {
            assertEquals("Waiting for the final results", screen.headlineText());
            screen.startListening(); // catches up with the current state
            assertEquals("You win!", screen.headlineText());
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @Test
    void namesAreJoinedForReading() {
        assertEquals("", GameOverScreen.joinNames(List.of(), "alice"));
        assertEquals("alice (you)", GameOverScreen.joinNames(List.of("alice"), "alice"));
        assertEquals("alice and bob", GameOverScreen.joinNames(List.of("alice", "bob"), null));
        assertEquals("alice, bob (you) and carol", GameOverScreen.joinNames(List.of("alice", "bob", "carol"), "bob"));
    }

    private Client join(String username, String... lobbyLines) throws Exception {
        runOnEdt(() -> assertTrue(controller.connect(server.host(), server.port(), username).isEmpty()));
        Client client = server.accept();
        client.expectLine("JOIN|" + username);
        client.send("JOINED|" + username);
        client.send(lobbyLines);
        waiter.await("lobby", () -> controller.getState().getHost() != null);
        return client;
    }

    /** Joins, plays the last round without answering and ends the game with the given final lines. */
    private Client finishGame(String username, String playersLine, String hostLine, String scoresLine,
                              String gameOverLine) throws Exception {
        Client client = join(username, playersLine, hostLine);
        client.send("GAME_STARTED|5 questions", LAST_QUESTION, "ROUND_RESULT|5|A",
                scoresLine, scoresLine, gameOverLine);
        waiter.await("game over", () -> controller.getState().getPhase() == GamePhase.GAME_OVER);
        return client;
    }

    /** alice (host) and bob; alice wins with 2 points. */
    private Client finishDefaultGame(String username) throws Exception {
        return finishGame(username, "PLAYERS|alice,bob", "HOST|alice", "SCORES|alice:2,bob:1", "GAME_OVER|alice|2");
    }
}
