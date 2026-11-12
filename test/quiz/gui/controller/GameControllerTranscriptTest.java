package quiz.gui.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static quiz.gui.controller.GameControllerTest.scores;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import quiz.gui.controller.GameControllerTest.Driver;
import quiz.gui.controller.GameControllerTest.Snapshot;
import quiz.gui.model.AnswerOption;
import quiz.gui.model.AnswerState;
import quiz.gui.model.GamePhase;
import quiz.gui.model.Leaderboard;

/**
 * Replays what QuizServer really sends, line for line, to several controllers at once,
 * through the whole client path:
 * {@code FakeServer (loopback TCP) -> ServerConnection -> ProtocolParser -> GameController -> GameState}.
 *
 * <p>Every client command is produced by a controller call and checked on the server side.</p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class GameControllerTranscriptTest {

    /** The five questions of QuizServer, as it sends them. */
    private static final List<String> QUESTIONS = List.of(
            "QUESTION|1|5|15|Which Java class listens for TCP connections?"
                    + "|Socket|ServerSocket|DatagramSocket|Thread",
            "QUESTION|2|5|15|Which protocol provides reliable, ordered delivery?|UDP|HTTP|TCP|ICMP",
            "QUESTION|3|5|15|What does an IP address identify on a network?"
                    + "|A network interface|A Java method|A file format|A password",
            "QUESTION|4|5|15|Which Java method accepts an incoming client?"
                    + "|connect()|read()|listen()|accept()",
            "QUESTION|5|5|15|Which Java feature allows clients to be handled concurrently?"
                    + "|Threads|Packages|Comments|Imports");

    private static final List<AnswerOption> CORRECT = List.of(
            AnswerOption.B, AnswerOption.C, AnswerOption.A, AnswerOption.D, AnswerOption.A);

    /** One connected player: its controller and the server side of its connection. */
    private record Player(String name, Driver driver, FakeServer.Client client) {
    }

    private FakeServer server;
    private final List<Driver> drivers = new ArrayList<>();

    @BeforeAll
    static void useHeadlessAwt() {
        GameControllerTest.useHeadlessAwt();
    }

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeServer();
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (Driver driver : drivers) {
            driver.close();
        }
        server.close();
    }

    // ------------------------------------------------------------------
    // Transcripts
    // ------------------------------------------------------------------

    @Test
    void fullFiveQuestionGameThenPlayAgain() throws Exception {
        List<Player> players = lobby("alice", "bob");
        Player alice = players.get(0);
        Player bob = players.get(1);
        assertTrue(alice.driver().now().canStart());
        assertFalse(bob.driver().now().canStart(), "bob is not the host");
        assertFalse(bob.driver().is(GameController::startGame));

        startGame(alice, players);

        // alice is always right; bob is right in rounds 1 and 3 only.
        int aliceScore = 0;
        int bobScore = 0;
        for (int i = 0; i < 5; i++) {
            AnswerOption bobAnswer = (i == 0 || i == 2) ? CORRECT.get(i) : wrong(CORRECT.get(i));
            aliceScore += 10;
            bobScore += bobAnswer == CORRECT.get(i) ? 10 : 0;
            playRound(i + 1, players,
                    Map.of(alice, CORRECT.get(i), bob, bobAnswer),
                    "SCORES|alice:" + aliceScore + ",bob:" + bobScore);
        }
        assertEquals(50, aliceScore);
        assertEquals(20, bobScore);

        // After the last round QuizServer sends SCORES again, then GAME_OVER.
        FakeServer.broadcast(clients(players), "SCORES|alice:50,bob:20", "GAME_OVER|alice|50");
        Snapshot aliceOver = alice.driver().awaitPhase(GamePhase.GAME_OVER);
        Snapshot bobOver = bob.driver().awaitPhase(GamePhase.GAME_OVER);
        for (Snapshot s : List.of(aliceOver, bobOver)) {
            assertEquals(List.of("alice"), s.winners());
            assertEquals(50, s.best());
            assertFalse(s.tie());
            assertEquals(scores("alice", 50, "bob", 20), s.leaderboard());
        }
        assertTrue(aliceOver.winner());
        assertFalse(bobOver.winner());
        assertTrue(aliceOver.canStart(), "host may Play Again");
        assertFalse(bobOver.canStart());

        // Play Again.
        startGame(alice, players);
        for (Player p : players) {
            Snapshot s = p.driver().now();
            assertEquals(List.of(), s.winners());
            assertTrue(s.leaderboard().isEmpty());
        }
        FakeServer.broadcast(clients(players), QUESTIONS.get(0));
        for (Player p : players) {
            Snapshot s = awaitRound(p, 1);
            assertEquals(AnswerState.NOT_ANSWERED, s.answer());
            assertTrue(s.canAnswer());
        }

        disconnectAll(players);
    }

    @Test
    void matchEndsEarlyWhenAPlayerLeaves() throws Exception {
        List<Player> players = lobby("alice", "bob");
        Player alice = players.get(0);
        Player bob = players.get(1);
        startGame(alice, players);
        playRound(1, players, Map.of(alice, AnswerOption.B, bob, AnswerOption.A), "SCORES|alice:10,bob:0");

        FakeServer.broadcast(clients(players), QUESTIONS.get(1));
        awaitRound(alice, 2);
        awaitRound(bob, 2);

        // bob quits during round 2.
        bob.driver().run(GameController::disconnect);
        bob.client().expectQuitThenEof();
        Snapshot bobAfter = bob.driver().now();
        assertEquals(GamePhase.DISCONNECTED, bobAfter.phase());
        assertNull(bobAfter.reason());

        alice.client().send(
                "INFO|bob left the lobby",
                "PLAYERS|alice",
                "HOST|alice",
                "INFO|Match ended: not enough players",
                "SCORES|alice:10",
                "GAME_OVER|alice|10");
        Snapshot s = alice.driver().awaitPhase(GamePhase.GAME_OVER);
        assertEquals("Match ended: not enough players", s.info());
        assertEquals(List.of("alice"), s.players());
        assertEquals(AnswerState.MISSED, s.answer(), "the open round closed without an answer");
        assertNull(s.correct(), "no ROUND_RESULT for the aborted round");
        assertEquals(scores("alice", 10), s.leaderboard());
        assertTrue(s.winner());
        assertFalse(s.canStart(), "only one player is left");
        assertFalse(alice.driver().is(GameController::startGame));
        assertFalse(alice.driver().is(c -> c.submitAnswer(AnswerOption.C)));

        alice.driver().run(GameController::disconnect);
        alice.client().expectQuitThenEof(); // nothing else was sent
    }

    @Test
    void hostLeavesMidGameAndTheNextPlayerBecomesHost() throws Exception {
        List<Player> players = lobby("alice", "bob", "carol");
        Player alice = players.get(0);
        Player bob = players.get(1);
        Player carol = players.get(2);
        startGame(alice, players);

        FakeServer.broadcast(clients(players), QUESTIONS.get(0));
        for (Player p : players) {
            awaitRound(p, 1);
        }

        // The host leaves during round 1; the game continues with two players.
        alice.driver().run(GameController::disconnect);
        alice.client().expectQuitThenEof();
        List<Player> remaining = List.of(bob, carol);
        FakeServer.broadcast(clients(remaining), "INFO|alice left the lobby", "PLAYERS|bob,carol", "HOST|bob");
        for (Player p : remaining) {
            Snapshot s = p.driver().await("bob is host", x -> "bob".equals(x.host()));
            assertEquals(GamePhase.QUESTION_ACTIVE, s.phase(), "the game continues");
            assertTrue(s.canAnswer());
            assertFalse(s.canStart(), "a game is running");
        }
        assertTrue(bob.driver().now().isHost());
        assertFalse(carol.driver().now().isHost());
        assertFalse(bob.driver().is(GameController::startGame));

        // Round 1 closes when both remaining players have answered.
        answer(bob, 1, AnswerOption.B);
        answer(carol, 1, AnswerOption.C);
        closeRound(1, remaining, "SCORES|bob:10,carol:0");

        // Rounds 2-5 time out: nobody answers.
        for (int round = 2; round <= 5; round++) {
            FakeServer.broadcast(clients(remaining), QUESTIONS.get(round - 1));
            for (Player p : remaining) {
                awaitRound(p, round);
            }
            closeRound(round, remaining, "SCORES|bob:10,carol:0");
            for (Player p : remaining) {
                assertEquals(AnswerState.MISSED, p.driver().now().answer());
            }
        }
        FakeServer.broadcast(clients(remaining), "SCORES|bob:10,carol:0", "GAME_OVER|bob|10");
        Snapshot bobOver = bob.driver().awaitPhase(GamePhase.GAME_OVER);
        Snapshot carolOver = carol.driver().awaitPhase(GamePhase.GAME_OVER);
        assertTrue(bobOver.winner());
        assertFalse(carolOver.winner());

        // The new host can Play Again; the other player cannot.
        assertTrue(bobOver.canStart());
        assertFalse(carolOver.canStart());
        assertFalse(carol.driver().is(GameController::startGame));
        startGame(bob, remaining);

        disconnectAll(remaining);
    }

    @Test
    void tieThenReplayClearsResults() throws Exception {
        List<Player> players = lobby("alice", "bob");
        Player alice = players.get(0);
        Player bob = players.get(1);
        startGame(alice, players);

        int score = 0;
        for (int i = 0; i < 5; i++) {
            score += 10;
            playRound(i + 1, players, Map.of(alice, CORRECT.get(i), bob, CORRECT.get(i)),
                    "SCORES|alice:" + score + ",bob:" + score);
        }
        FakeServer.broadcast(clients(players), "SCORES|alice:50,bob:50", "GAME_OVER|alice,bob|50");
        for (Player p : players) {
            Snapshot s = p.driver().awaitPhase(GamePhase.GAME_OVER);
            assertTrue(s.tie());
            assertTrue(s.winner());
            assertEquals(List.of("alice", "bob"), s.winners());
            assertEquals(50, s.best());
        }

        // Replay: one START, both clients reset, the first round is answerable again.
        startGame(alice, players);
        FakeServer.broadcast(clients(players), QUESTIONS.get(0));
        for (Player p : players) {
            Snapshot s = awaitRound(p, 1);
            assertFalse(s.tie());
            assertEquals(0, s.best());
            assertNull(s.correct());
            assertTrue(s.canAnswer());
        }
        answer(alice, 1, AnswerOption.A);
        assertFalse(alice.driver().is(c -> c.submitAnswer(AnswerOption.B)));

        disconnectAll(players);
    }

    // ------------------------------------------------------------------
    // Transcript steps
    // ------------------------------------------------------------------

    /** Players join one after another, like QuizServer: the first one is the host. */
    private List<Player> lobby(String... names) throws Exception {
        List<Player> joined = new ArrayList<>();
        List<String> roster = new ArrayList<>();
        for (String name : names) {
            Driver driver = new Driver();
            drivers.add(driver);
            assertEquals(Optional.empty(),
                    driver.call(c -> c.connect(server.host(), server.port(), name)));
            FakeServer.Client client = server.accept();
            client.expectLine("JOIN|" + name);
            Player player = new Player(name, driver, client);

            roster.add(name);
            String host = roster.get(0);
            client.send("JOINED|" + name);
            joined.add(player);
            FakeServer.broadcast(clients(joined), "INFO|" + name + " joined the lobby",
                    "PLAYERS|" + String.join(",", roster), "HOST|" + host);
            List<String> expectedRoster = List.copyOf(roster);
            for (Player p : joined) {
                Snapshot s = p.driver().await(name + " joined",
                        x -> x.phase() == GamePhase.LOBBY && x.players().equals(expectedRoster)
                                && host.equals(x.host()));
                assertEquals(p.name(), s.player());
                assertEquals(host, s.host());
                assertEquals(p.name().equals(host), s.isHost());
            }
        }
        return joined;
    }

    /** The host presses Start (or Play Again); the server broadcasts GAME_STARTED. */
    private void startGame(Player host, List<Player> players) throws Exception {
        assertTrue(host.driver().is(GameController::startGame));
        assertFalse(host.driver().is(GameController::startGame), "duplicate START");
        host.client().expectLine("START");
        FakeServer.broadcast(clients(players), "GAME_STARTED|5 questions");
        for (Player p : players) {
            Snapshot s = p.driver().awaitPhase(GamePhase.GAME_STARTING);
            assertFalse(s.canStart());
            assertNull(s.error());
        }
    }

    /** One complete round: question, everyone in {@code answers} answers, result and scores. */
    private void playRound(int round, List<Player> players, Map<Player, AnswerOption> answers,
                           String scoresLine) throws Exception {
        FakeServer.broadcast(clients(players), QUESTIONS.get(round - 1));
        for (Player p : players) {
            awaitRound(p, round);
        }
        for (Player p : players) {
            AnswerOption option = answers.get(p);
            if (option != null) {
                answer(p, round, option);
            }
        }
        closeRound(round, players, scoresLine);
        for (Player p : players) {
            Snapshot s = p.driver().now();
            AnswerOption chosen = answers.get(p);
            assertEquals(chosen == null ? AnswerState.MISSED : AnswerState.ACCEPTED, s.answer());
            assertEquals(chosen, s.selected());
        }
    }

    /** The player answers through the controller; the server checks the line and accepts it. */
    private static void answer(Player p, int round, AnswerOption option) throws Exception {
        assertTrue(p.driver().is(c -> c.submitAnswer(option)));
        assertEquals(AnswerState.SUBMITTED, p.driver().now().answer());
        assertFalse(p.driver().is(c -> c.submitAnswer(option)), "only one answer per round");
        p.client().expectLine("ANSWER|" + round + "|" + option.letter());
        p.client().send("ANSWER_ACCEPTED|" + round);
        p.driver().await(p.name() + " accepted", s -> s.answer() == AnswerState.ACCEPTED);
    }

    /** ROUND_RESULT then SCORES to everyone, as QuizServer's closeRoundLocked does. */
    private static void closeRound(int round, List<Player> players, String scoresLine) throws Exception {
        AnswerOption correct = CORRECT.get(round - 1);
        FakeServer.broadcast(clients(players), "ROUND_RESULT|" + round + "|" + correct.letter(), scoresLine);
        Leaderboard expected = parseScores(scoresLine);
        for (Player p : players) {
            Snapshot s = p.driver().await("round " + round + " scores",
                    x -> x.phase() == GamePhase.ROUND_ENDED && x.leaderboard().equals(expected));
            assertEquals(correct, s.correct());
            assertFalse(s.canAnswer());
        }
    }

    private static Snapshot awaitRound(Player p, int round) throws Exception {
        return p.driver().await(p.name() + " round " + round,
                s -> s.phase() == GamePhase.QUESTION_ACTIVE && Integer.valueOf(round).equals(s.round()));
    }

    private static void disconnectAll(List<Player> players) throws Exception {
        for (Player p : players) {
            p.driver().run(GameController::disconnect);
            p.client().expectQuitThenEof(); // proves no unexpected command was sent
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static List<FakeServer.Client> clients(List<Player> players) {
        return players.stream().map(Player::client).toList();
    }

    private static AnswerOption wrong(AnswerOption correct) {
        return correct == AnswerOption.A ? AnswerOption.B : AnswerOption.A;
    }

    /** "SCORES|a:1,b:2" -> Leaderboard, for comparing with what the client parsed. */
    private static Leaderboard parseScores(String scoresLine) {
        String csv = scoresLine.substring("SCORES|".length());
        List<Object> pairs = new ArrayList<>();
        for (String entry : csv.split(",")) {
            String[] parts = entry.split(":");
            pairs.add(parts[0]);
            pairs.add(Integer.parseInt(parts[1]));
        }
        return scores(pairs.toArray());
    }
}
