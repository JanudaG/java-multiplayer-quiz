package quiz.gui.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.EventQueue;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import quiz.gui.model.AnswerOption;
import quiz.gui.model.AnswerState;
import quiz.gui.model.GamePhase;
import quiz.gui.model.GameState;
import quiz.gui.model.Leaderboard;
import quiz.gui.model.PlayerScore;
import quiz.gui.model.QuestionData;

/**
 * Integration tests for the full client path:
 * {@code FakeServer (loopback TCP) -> ServerConnection -> ProtocolParser -> GameController -> GameState}.
 *
 * <p>The controller uses the real public {@code ServerConnection} constructor, so callbacks
 * arrive on the Swing EDT (headless). Every wait is event-driven and bounded; there is no
 * sleeping. "Nothing else was sent" is proven by disconnecting and checking that the next
 * line the server receives is QUIT.</p>
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class GameControllerTest {

    static final String Q1 = "QUESTION|1|5|15|Which Java class listens for TCP connections?"
            + "|Socket|ServerSocket|DatagramSocket|Thread";
    static final String Q2 = "QUESTION|2|5|15|Which protocol provides reliable, ordered delivery?"
            + "|UDP|HTTP|TCP|ICMP";

    private FakeServer server;
    private final List<Driver> drivers = new ArrayList<>();

    @BeforeAll
    static void useHeadlessAwt() {
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
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

    private Driver newDriver() throws Exception {
        Driver driver = new Driver();
        drivers.add(driver);
        return driver;
    }

    // ------------------------------------------------------------------
    // Connecting and JOIN
    // ------------------------------------------------------------------

    @Test
    void invalidConnectionDetailsAreRejectedWithoutConnecting() throws Exception {
        Driver d = newDriver();
        String host = server.host();
        int port = server.port();

        assertEquals("Please enter the server address.", d.connectProblem(null, port, "alice"));
        assertEquals("Please enter the server address.", d.connectProblem("   ", port, "alice"));
        assertEquals("Port must be between 1 and 65535.", d.connectProblem(host, 0, "alice"));
        assertEquals("Port must be between 1 and 65535.", d.connectProblem(host, 65536, "alice"));
        assertEquals("Please enter a username.", d.connectProblem(host, port, null));
        assertEquals("Please enter a username.", d.connectProblem(host, port, "  "));
        assertEquals("Username must be at most 16 characters.",
                d.connectProblem(host, port, "a".repeat(17)));
        assertEquals("Use only letters, digits, _ or - in the username.",
                d.connectProblem(host, port, "bad name"));
        assertEquals("Use only letters, digits, _ or - in the username.",
                d.connectProblem(host, port, "JOIN|x"));

        Snapshot s = d.now();
        assertEquals(GamePhase.DISCONNECTED, s.phase());
        assertFalse(s.active());
        assertEquals(0, d.notifications(), "invalid input must not change or announce state");
        assertEquals(List.of(), server.allReceivedLines());
    }

    @Test
    void joinIsSentOnlyAfterConnectedWithTrimmedUsername() throws Exception {
        Driver d = newDriver();

        Optional<String> problem = d.call(c -> c.connect(" " + server.host() + " ", server.port(), "  alice "));
        assertEquals(Optional.empty(), problem);

        FakeServer.Client client = server.accept();
        client.expectLine("JOIN|alice");
        Snapshot joining = d.awaitPhase(GamePhase.JOINING);
        assertEquals("alice", joining.requested());
        assertTrue(joining.active());

        // The first notification is CONNECTING (before the socket was open), then JOINING.
        List<GamePhase> phases = d.history().stream().map(Snapshot::phase).toList();
        assertEquals(List.of(GamePhase.CONNECTING, GamePhase.JOINING), phases);
        assertNull(d.history().get(0).requested(), "JOIN must not be marked before onConnected");
    }

    @Test
    void joinedMovesToLobby() throws Exception {
        Driver d = newDriver();
        joinLobby(d, "alice", List.of("alice"), "alice");

        Snapshot s = d.now();
        assertEquals(GamePhase.LOBBY, s.phase());
        assertEquals("alice", s.player());
        assertEquals("alice joined the lobby", s.info());
        assertNull(s.error());
        assertFalse(s.joinRejected());
    }

    @Test
    void connectWhileSessionActiveIsRejected() throws Exception {
        Driver d = newDriver();
        FakeServer.Client client = connect(d, "alice");

        assertEquals("Already connected. Disconnect first.",
                d.connectProblem(server.host(), server.port(), "bob"));

        d.run(GameController::disconnect);
        client.expectQuitThenEof();
    }

    @Test
    void rejectedJoinKeepsServerErrorAfterDisconnect() throws Exception {
        Driver d = newDriver();
        FakeServer.Client client = connect(d, "alice");

        // QuizServer: ERROR, then close the socket.
        client.send("ERROR|Username already taken");
        client.close();

        Snapshot s = d.awaitPhase(GamePhase.DISCONNECTED);
        assertTrue(s.joinRejected());
        assertEquals("Username already taken", s.error());
        assertEquals("Server closed the connection", s.reason());
        assertFalse(s.active());
        assertNull(s.player());

        // A later intentional disconnect is a no-op and keeps the explanation.
        int before = d.notifications();
        d.run(GameController::disconnect);
        assertEquals(s, d.now());
        assertEquals(before, d.notifications());

        // Retrying with a new name uses a new connection and clears the old error.
        FakeServer.Client retry = connect(d, "alice2");
        assertNotEquals(client, retry);
        Snapshot connecting = d.history().get(before);
        assertEquals(GamePhase.CONNECTING, connecting.phase());
        assertNull(connecting.error());
        assertFalse(connecting.joinRejected());
        assertNull(connecting.reason());
        retry.send("JOINED|alice2");
        assertEquals("alice2", d.awaitPhase(GamePhase.LOBBY).player());
    }

    @Test
    void connectionRefusedReportsReason() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        Driver d = newDriver();
        assertEquals(Optional.empty(), d.call(c -> c.connect(server.host(), closedPort, "alice")));

        Snapshot s = d.awaitPhase(GamePhase.DISCONNECTED);
        assertTrue(s.reason().startsWith("Could not connect to "), s.reason());
        assertEquals(s.reason(), s.error());
        assertFalse(s.joinRejected());
        assertFalse(s.active());
        assertEquals(List.of(GamePhase.CONNECTING, GamePhase.DISCONNECTED),
                d.history().stream().map(Snapshot::phase).toList());
    }

    // ------------------------------------------------------------------
    // Players and host
    // ------------------------------------------------------------------

    @Test
    void playerListAndHostUpdates() throws Exception {
        Driver d = newDriver();
        FakeServer.Client client = joinLobby(d, "alice", List.of("alice"), "alice");
        Snapshot alone = d.now();
        assertTrue(alone.isHost());
        assertFalse(alone.canStart(), "a single player cannot start");

        client.send("INFO|bob joined the lobby", "PLAYERS|alice,bob", "HOST|alice");
        Snapshot two = d.await("bob listed", s -> s.players().equals(List.of("alice", "bob")));
        assertTrue(two.isHost());
        assertTrue(two.canStart());

        client.send("PLAYERS|bob,alice", "HOST|bob");
        Snapshot notHost = d.await("bob is host", s -> "bob".equals(s.host()));
        assertEquals(List.of("bob", "alice"), notHost.players());
        assertFalse(notHost.isHost());
        assertFalse(notHost.canStart());
    }

    // ------------------------------------------------------------------
    // START
    // ------------------------------------------------------------------

    @Test
    void startIsRefusedWhenNotEligibleAndNothingIsSent() throws Exception {
        Driver d = newDriver();
        assertFalse(d.is(GameController::startGame), "not connected");

        FakeServer.Client client = connect(d, "alice");
        d.awaitPhase(GamePhase.JOINING);
        assertFalse(d.is(GameController::startGame), "not joined yet");

        client.send("JOINED|alice", "PLAYERS|alice", "HOST|alice");
        d.await("alice is host", s -> "alice".equals(s.host()));
        assertFalse(d.is(GameController::startGame), "only one player");

        client.send("PLAYERS|bob,alice", "HOST|bob");
        d.await("bob is host", s -> "bob".equals(s.host()));
        assertFalse(d.is(GameController::startGame), "not the host");

        client.send("GAME_STARTED|5 questions", "HOST|alice");
        d.await("alice host in game", s -> s.phase() == GamePhase.GAME_STARTING && s.isHost());
        assertFalse(d.is(GameController::startGame), "game already running");

        d.run(GameController::disconnect);
        client.expectQuitThenEof();
    }

    @Test
    void startIsSentOnceUntilTheServerAnswers() throws Exception {
        Driver d = newDriver();
        FakeServer.Client client = joinLobby(d, "alice", List.of("alice", "bob"), "alice");
        assertTrue(d.now().canStart());

        assertTrue(d.is(GameController::startGame));
        assertFalse(d.now().canStart(), "START is pending");
        assertFalse(d.is(GameController::startGame), "duplicate START");
        assertEquals(GamePhase.LOBBY, d.now().phase(), "the server decides when the game starts");
        client.expectLine("START");

        client.send("GAME_STARTED|5 questions");
        Snapshot started = d.awaitPhase(GamePhase.GAME_STARTING);
        assertFalse(started.canStart());

        d.run(GameController::disconnect);
        client.expectQuitThenEof(); // proves the second START was never sent
    }

    @Test
    void startRejectedByServerCanBeRetried() throws Exception {
        Driver d = newDriver();
        FakeServer.Client client = joinLobby(d, "alice", List.of("alice", "bob"), "alice");

        assertTrue(d.is(GameController::startGame));
        client.expectLine("START");
        client.send("ERROR|At least two players are required");
        Snapshot rejected = d.await("start rejected", s -> s.error() != null);
        assertEquals("At least two players are required", rejected.error());
        assertFalse(rejected.joinRejected());
        assertTrue(rejected.canStart(), "a rejected START may be retried");

        assertTrue(d.is(GameController::startGame));
        client.expectLine("START");
    }

    @Test
    void playAgainAfterGameOverSendsStartAgain() throws Exception {
        Driver d = newDriver();
        FakeServer.Client client = joinLobby(d, "alice", List.of("alice", "bob"), "alice");
        assertTrue(d.is(GameController::startGame));
        client.expectLine("START");
        client.send("GAME_STARTED|5 questions", Q1, "ROUND_RESULT|1|B",
                "SCORES|alice:0,bob:0", "SCORES|alice:0,bob:0", "GAME_OVER|alice,bob|0");
        Snapshot over = d.awaitPhase(GamePhase.GAME_OVER);
        assertTrue(over.canStart(), "host may Play Again");

        assertTrue(d.is(GameController::startGame));
        assertFalse(d.is(GameController::startGame));
        client.expectLine("START");
        client.send("GAME_STARTED|5 questions");
        Snapshot again = d.awaitPhase(GamePhase.GAME_STARTING);
        assertEquals(List.of(), again.winners());
        assertTrue(again.leaderboard().isEmpty());

        d.run(GameController::disconnect);
        client.expectQuitThenEof();
    }

    // ------------------------------------------------------------------
    // ANSWER
    // ------------------------------------------------------------------

    @Test
    void answerIsSentOncePerRoundAndFollowsServerState() throws Exception {
        Driver d = newDriver();
        assertFalse(d.is(c -> c.submitAnswer(AnswerOption.A)), "not connected");
        FakeServer.Client client = inGame(d);

        client.send(Q1);
        Snapshot open = d.awaitPhase(GamePhase.QUESTION_ACTIVE);
        assertTrue(open.canAnswer());
        assertFalse(d.is(c -> c.submitAnswer(null)), "null option");

        assertTrue(d.is(c -> c.submitAnswer(AnswerOption.B)));
        Snapshot submitted = d.now();
        assertEquals(AnswerState.SUBMITTED, submitted.answer());
        assertEquals(AnswerOption.B, submitted.selected());
        assertFalse(d.is(c -> c.submitAnswer(AnswerOption.C)), "second answer in the same round");
        client.expectLine("ANSWER|1|B");

        client.send("ANSWER_ACCEPTED|1");
        d.await("accepted", s -> s.answer() == AnswerState.ACCEPTED);
        assertFalse(d.is(c -> c.submitAnswer(AnswerOption.D)));

        client.send("ROUND_RESULT|1|B", "SCORES|alice:10,bob:0");
        Snapshot ended = d.await("scores", s -> !s.leaderboard().isEmpty());
        assertEquals(GamePhase.ROUND_ENDED, ended.phase());
        assertEquals(AnswerState.ACCEPTED, ended.answer());
        assertEquals(AnswerOption.B, ended.correct());
        assertFalse(d.is(c -> c.submitAnswer(AnswerOption.A)), "round closed");

        // Round 2: answer sent but the round closes before ANSWER_ACCEPTED -> MISSED.
        client.send(Q2);
        d.await("round 2", s -> Integer.valueOf(2).equals(s.round()));
        assertEquals(AnswerState.NOT_ANSWERED, d.now().answer());
        assertTrue(d.is(c -> c.submitAnswer(AnswerOption.C)));
        client.expectLine("ANSWER|2|C");
        client.send("ROUND_RESULT|2|C");
        Snapshot missed = d.awaitPhase(GamePhase.ROUND_ENDED);
        assertEquals(AnswerState.MISSED, missed.answer());

        d.run(GameController::disconnect);
        client.expectQuitThenEof(); // no other ANSWER was sent
    }

    @Test
    void answerRejectedByServerCannotBeResent() throws Exception {
        Driver d = newDriver();
        FakeServer.Client client = inGame(d);
        client.send(Q1);
        d.awaitPhase(GamePhase.QUESTION_ACTIVE);

        assertTrue(d.is(c -> c.submitAnswer(AnswerOption.A)));
        client.expectLine("ANSWER|1|A");
        client.send("ERROR|No active question or time has expired");
        Snapshot s = d.await("error", x -> x.error() != null);
        assertEquals(AnswerState.SUBMITTED, s.answer());
        assertFalse(s.canAnswer());
        assertFalse(d.is(c -> c.submitAnswer(AnswerOption.B)));

        client.send("ROUND_RESULT|1|B");
        assertEquals(AnswerState.MISSED, d.awaitPhase(GamePhase.ROUND_ENDED).answer());

        d.run(GameController::disconnect);
        client.expectQuitThenEof();
    }

    @Test
    void unansweredRoundBecomesMissedAndStaleAcceptIsIgnored() throws Exception {
        Driver d = newDriver();
        FakeServer.Client client = inGame(d);
        client.send(Q1, "ANSWER_ACCEPTED|1", "ROUND_RESULT|2|A", "INFO|marker");
        Snapshot s = d.await("marker", x -> "marker".equals(x.info()));
        assertEquals(GamePhase.QUESTION_ACTIVE, s.phase(), "result for another round is ignored");
        assertEquals(AnswerState.NOT_ANSWERED, s.answer(), "accept without a submitted answer is ignored");

        client.send("ROUND_RESULT|1|B");
        Snapshot ended = d.awaitPhase(GamePhase.ROUND_ENDED);
        assertEquals(AnswerState.MISSED, ended.answer());
        assertNull(ended.selected());
    }

    // ------------------------------------------------------------------
    // Message mapping and ordering
    // ------------------------------------------------------------------

    @Test
    void everyServerMessageTypeIsMappedInOrder() throws Exception {
        Driver d = newDriver();
        FakeServer.Client client = connect(d, "alice");
        d.awaitPhase(GamePhase.JOINING);
        int base = d.notifications();

        // All 12 message types in one TCP write.
        client.send(
                "JOINED|alice",                 // Joined
                "INFO|alice joined the lobby",  // Info
                "PLAYERS|alice,bob",            // PlayerList
                "HOST|alice",                   // HostChanged
                "ERROR|Unknown command",        // ServerError
                "SURPRISE|ignored",             // Unknown (no notification)
                "GAME_STARTED|5 questions",     // GameStarted
                Q1,                             // NewQuestion
                "ANSWER_ACCEPTED|1",            // AnswerAccepted (nothing submitted: ignored by the model)
                "ROUND_RESULT|1|B",             // RoundResult
                "SCORES|alice:10,bob:0",        // Scores
                "GAME_OVER|alice|10");          // GameOver

        Snapshot end = d.awaitPhase(GamePhase.GAME_OVER);
        List<Snapshot> h = d.history().subList(base, d.history().size());
        assertEquals(11, h.size(), "one notification per known message, none for Unknown");

        assertEquals(GamePhase.LOBBY, h.get(0).phase());
        assertEquals("alice", h.get(0).player());
        assertEquals("alice joined the lobby", h.get(1).info());
        assertEquals(List.of("alice", "bob"), h.get(2).players());
        assertEquals("alice", h.get(3).host());
        assertTrue(h.get(3).isHost());
        assertEquals("Unknown command", h.get(4).error());
        assertFalse(h.get(4).joinRejected(), "an ERROR after JOINED is not a join rejection");
        assertEquals(GamePhase.GAME_STARTING, h.get(5).phase());
        assertNull(h.get(5).error(), "GAME_STARTED clears the last error");
        assertEquals(GamePhase.QUESTION_ACTIVE, h.get(6).phase());
        assertEquals(Integer.valueOf(1), h.get(6).round());
        assertEquals(AnswerState.NOT_ANSWERED, h.get(7).answer());
        assertEquals(GamePhase.ROUND_ENDED, h.get(8).phase());
        assertEquals(AnswerOption.B, h.get(8).correct());
        assertEquals(AnswerState.MISSED, h.get(8).answer());
        assertEquals(scores("alice", 10, "bob", 0), h.get(9).leaderboard());
        assertEquals(GamePhase.GAME_OVER, h.get(10).phase());

        assertEquals(List.of("alice"), end.winners());
        assertEquals(10, end.best());
        assertTrue(end.winner());
        assertFalse(end.tie());
    }

    @Test
    void serverOrderIsPreservedForABurstOfMessages() throws Exception {
        Driver d = newDriver();
        FakeServer.Client client = joinLobby(d, "alice", List.of("alice"), "alice");
        int base = d.notifications();

        String[] burst = IntStream.rangeClosed(1, 200).mapToObj(i -> "INFO|m" + i).toArray(String[]::new);
        client.send(burst);

        d.await("last message", s -> "m200".equals(s.info()));
        List<String> infos = d.history().subList(base, d.history().size()).stream().map(Snapshot::info).toList();
        assertEquals(IntStream.rangeClosed(1, 200).mapToObj(i -> "m" + i).toList(), infos);
    }

    @Test
    void unknownAndMalformedLinesDoNotChangeState() throws Exception {
        Driver d = newDriver();
        FakeServer.Client client = joinLobby(d, "alice", List.of("alice", "bob"), "alice");
        Snapshot before = d.now();
        int base = d.notifications();

        client.sendRaw("SURPRISE|1|2\n"                 // unknown type
                + "QUESTION|not-a-number\r\n"          // malformed known type
                + "JOINED|bad name\n"
                + "HOST|\n"
                + "SCORES|alice:x\n"
                + "ROUND_RESULT|1|Z\n"
                + "\n"                                 // empty line
                + "INFO|after\n");

        Snapshot after = d.await("marker", s -> "after".equals(s.info()));
        assertEquals(base + 1, d.notifications(), "only INFO|after notified");
        assertEquals(before, withInfo(after, before.info()), "nothing but lastInfo changed");
        assertTrue(after.active());
    }

    // ------------------------------------------------------------------
    // Disconnecting and reconnecting
    // ------------------------------------------------------------------

    @Test
    void intentionalDisconnectSendsQuitAndIgnoresEverythingAfter() throws Exception {
        Driver d = newDriver();
        FakeServer.Client client = inGame(d);
        client.send(Q1);
        d.awaitPhase(GamePhase.QUESTION_ACTIVE);

        d.run(GameController::disconnect);
        Snapshot s = d.now();
        assertEquals(GamePhase.DISCONNECTED, s.phase());
        assertFalse(s.active());
        assertNull(s.reason(), "intentional disconnect has no reason");
        assertNull(s.error());
        assertNull(s.round());
        int after = d.notifications();

        assertFalse(d.is(c -> c.submitAnswer(AnswerOption.A)));
        assertFalse(d.is(GameController::startGame));
        assertFalse(d.is(GameController::canStartGame));
        d.run(GameController::disconnect); // second call: no-op

        try {
            client.send("INFO|late", "GAME_OVER|alice|10"); // the client may already be gone
        } catch (IOException ignored) {
            // fine: nothing can arrive then either
        }
        client.expectQuitThenEof();
        assertEquals(s, d.now(), "late server lines must not change the state");
        assertEquals(after, d.notifications());
    }

    @Test
    void unexpectedDisconnectReportsReasonAndClearsGame() throws Exception {
        Driver d = newDriver();
        FakeServer.Client client = inGame(d);
        client.send(Q1);
        d.awaitPhase(GamePhase.QUESTION_ACTIVE);

        client.close();

        Snapshot s = d.awaitPhase(GamePhase.DISCONNECTED);
        assertEquals("Server closed the connection", s.reason());
        assertEquals("Server closed the connection", s.error());
        assertFalse(s.joinRejected());
        assertFalse(s.active());
        assertNull(s.round());
        assertEquals(List.of(), s.players());
        assertFalse(d.is(c -> c.submitAnswer(AnswerOption.A)));
        assertFalse(d.is(GameController::startGame));
    }

    @Test
    void disconnectWhileConnectingThenReconnect() throws Exception {
        Driver d = newDriver();

        // connect and disconnect in one EDT task: onConnected can never run in between.
        Snapshot aborted = d.call(c -> {
            assertEquals(Optional.empty(), c.connect(server.host(), server.port(), "alice"));
            assertEquals(GamePhase.CONNECTING, c.getState().getPhase());
            c.disconnect();
            return Snapshot.of(c);
        });
        assertEquals(GamePhase.DISCONNECTED, aborted.phase());
        assertNull(aborted.reason());
        assertFalse(aborted.active());

        assertEquals(Optional.empty(), d.call(c -> c.connect(server.host(), server.port(), "bob")));
        FakeServer.Client bob = acceptClientSending("JOIN|bob");
        bob.send("JOINED|bob");
        assertEquals("bob", d.awaitPhase(GamePhase.LOBBY).player());

        assertFalse(server.allReceivedLines().contains("JOIN|alice"), "aborted attempt must not JOIN");
        List<GamePhase> phases = d.history().stream().map(Snapshot::phase).toList();
        assertEquals(List.of(GamePhase.CONNECTING, GamePhase.DISCONNECTED,
                GamePhase.CONNECTING, GamePhase.JOINING, GamePhase.LOBBY), phases);
    }

    @Test
    void reconnectAfterServerClosedUsesNewConnection() throws Exception {
        Driver d = newDriver();
        FakeServer.Client first = joinLobby(d, "alice", List.of("alice", "bob"), "alice");
        first.close();
        assertEquals("Server closed the connection", d.awaitPhase(GamePhase.DISCONNECTED).reason());

        FakeServer.Client second = joinLobby(d, "alice", List.of("alice", "bob"), "alice");
        assertNotEquals(first, second);
        Snapshot s = d.now();
        assertNull(s.reason());
        assertNull(s.error());
        assertTrue(s.canStart());

        assertTrue(d.is(GameController::startGame));
        second.expectLine("START");
        d.run(GameController::disconnect);
        second.expectQuitThenEof();
        assertEquals(List.of("JOIN|alice"), first.received(), "the old connection is never reused");
    }

    @Test
    void disconnectWithoutSessionDoesNothing() throws Exception {
        Driver d = newDriver();
        d.run(GameController::disconnect);
        assertEquals(0, d.notifications());
        assertEquals(GamePhase.DISCONNECTED, d.now().phase());
        assertEquals(Optional.empty(), d.call(GameController::getDisconnectReason));
    }

    // ------------------------------------------------------------------
    // Threading and listeners
    // ------------------------------------------------------------------

    @Test
    void everyPublicMethodRequiresTheEdt() throws Exception {
        assertFalse(EventQueue.isDispatchThread());
        assertThrows(IllegalStateException.class, GameController::new);

        GameController c = onEdt(GameController::new);
        GameController.StateListener listener = state -> { };
        List<Executable> calls = List.of(
                c::getState,
                () -> c.addStateListener(listener),
                () -> c.removeStateListener(listener),
                c::hasActiveConnection,
                c::getDisconnectReason,
                () -> c.connect(server.host(), server.port(), "alice"),
                c::canStartGame,
                c::startGame,
                () -> c.submitAnswer(AnswerOption.A),
                c::disconnect);
        for (Executable call : calls) {
            IllegalStateException e = assertThrows(IllegalStateException.class, call);
            assertTrue(e.getMessage().contains("event dispatch thread"), e.getMessage());
        }
        assertEquals(GamePhase.DISCONNECTED, onEdt(() -> c.getState().getPhase()));
        assertFalse(onEdt(c::hasActiveConnection).booleanValue(), "the off-EDT connect must not have started");
        assertEquals(List.of(), server.allReceivedLines());
    }

    @Test
    void listenersRunOnEdtAndAFailingListenerDoesNotStopOthers() throws Exception {
        Driver d = newDriver();
        List<Boolean> onEdt = new CopyOnWriteArrayList<>();
        List<GamePhase> second = new CopyOnWriteArrayList<>();
        AtomicReference<GameState> seen = new AtomicReference<>();
        GameController.StateListener failing = state -> {
            throw new IllegalStateException("listener bug (expected in this test)");
        };
        GameController.StateListener recording = state -> {
            onEdt.add(EventQueue.isDispatchThread());
            second.add(state.getPhase());
            seen.set(state);
        };
        d.run(c -> {
            c.addStateListener(failing);
            c.addStateListener(recording);
        });

        FakeServer.Client client = joinLobby(d, "alice", List.of("alice"), "alice");
        assertTrue(second.contains(GamePhase.LOBBY), "listener after the failing one still notified");
        assertFalse(onEdt.contains(false), "all notifications on the EDT");
        assertSame(d.call(GameController::getState), seen.get());

        d.run(c -> c.removeStateListener(recording));
        int count = second.size();
        client.send("INFO|after removal");
        d.await("info", s -> "after removal".equals(s.info()));
        assertEquals(count, second.size(), "removed listener is not notified");
    }

    // ------------------------------------------------------------------
    // Helpers shared with GameControllerTranscriptTest
    // ------------------------------------------------------------------

    /** Starts a session and waits until the server has received JOIN. */
    FakeServer.Client connect(Driver d, String username) throws Exception {
        assertEquals(Optional.empty(), d.call(c -> c.connect(server.host(), server.port(), username)));
        FakeServer.Client client = server.accept();
        client.expectLine("JOIN|" + username);
        return client;
    }

    /** Connects and replays what QuizServer sends to a new player. */
    FakeServer.Client joinLobby(Driver d, String username, List<String> players, String host)
            throws Exception {
        FakeServer.Client client = connect(d, username);
        client.send("JOINED|" + username, "INFO|" + username + " joined the lobby",
                "PLAYERS|" + String.join(",", players), "HOST|" + host);
        d.await("lobby with " + players, s -> s.phase() == GamePhase.LOBBY
                && s.players().equals(players) && host.equals(s.host()));
        return client;
    }

    /** alice (host) and bob in the lobby, START sent by alice, GAME_STARTED received. */
    private FakeServer.Client inGame(Driver d) throws Exception {
        FakeServer.Client client = joinLobby(d, "alice", List.of("alice", "bob"), "alice");
        assertTrue(d.is(GameController::startGame));
        client.expectLine("START");
        client.send("GAME_STARTED|5 questions");
        d.awaitPhase(GamePhase.GAME_STARTING);
        return client;
    }

    /** Accepts clients until one sends the expected first line; others must send nothing. */
    private FakeServer.Client acceptClientSending(String firstLine) throws Exception {
        while (true) {
            FakeServer.Client client = server.accept();
            String line = client.nextLineOrEof();
            if (firstLine.equals(line)) {
                return client;
            }
            assertNull(line, "an aborted connection must not send anything");
        }
    }

    static Leaderboard scores(Object... nameScorePairs) {
        List<PlayerScore> entries = new ArrayList<>();
        for (int i = 0; i < nameScorePairs.length; i += 2) {
            entries.add(new PlayerScore((String) nameScorePairs[i], (Integer) nameScorePairs[i + 1]));
        }
        return Leaderboard.of(entries);
    }

    private static Snapshot withInfo(Snapshot s, String info) {
        return new Snapshot(s.phase(), s.answer(), s.requested(), s.player(), s.players(), s.host(),
                s.isHost(), s.round(), s.selected(), s.correct(), s.leaderboard(), s.winners(), s.best(),
                s.winner(), s.tie(), info, s.error(), s.joinRejected(), s.canStart(), s.canAnswer(),
                s.active(), s.reason());
    }

    // ------------------------------------------------------------------
    // EDT helpers
    // ------------------------------------------------------------------

    /** Runs the task on the EDT, waits for it, and rethrows anything it threw. */
    static <T> T onEdt(Callable<T> task) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(task.call());
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        Throwable t = failure.get();
        if (t instanceof Exception e) {
            throw e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return result.get();
    }

    /** An immutable copy of everything the UI can observe, taken on the EDT. */
    record Snapshot(GamePhase phase, AnswerState answer, String requested, String player,
                    List<String> players, String host, boolean isHost, Integer round,
                    AnswerOption selected, AnswerOption correct, Leaderboard leaderboard,
                    List<String> winners, int best, boolean winner, boolean tie, String info,
                    String error, boolean joinRejected, boolean canStart, boolean canAnswer,
                    boolean active, String reason) {

        static Snapshot of(GameController c) {
            GameState s = c.getState();
            QuestionData q = s.getCurrentQuestion();
            return new Snapshot(s.getPhase(), s.getAnswerState(), s.getRequestedUsername(),
                    s.getCurrentPlayer(), s.getPlayers(), s.getHost(), s.isHost(),
                    q == null ? null : q.round(), s.getSelectedAnswer(), s.getCorrectAnswer(),
                    s.getLeaderboard(), s.getWinners(), s.getBestScore(), s.isWinner(), s.isTie(),
                    s.getLastInfo(), s.getLastError(), s.isJoinRejected(), c.canStartGame(),
                    s.canSubmitAnswer(), c.hasActiveConnection(), c.getDisconnectReason().orElse(null));
        }
    }

    /**
     * Drives one GameController from the test thread. Every controller call runs on the EDT;
     * a listener records a snapshot per notification and wakes up {@link #await}.
     */
    static final class Driver {
        private final GameController controller;
        private final List<Snapshot> history = new CopyOnWriteArrayList<>();
        private final BlockingQueue<Snapshot> wakeups = new LinkedBlockingQueue<>();

        Driver() throws Exception {
            controller = onEdt(GameController::new);
            run(c -> c.addStateListener(state -> {
                Snapshot snapshot = Snapshot.of(c);
                history.add(snapshot);
                wakeups.add(snapshot);
            }));
        }

        <T> T call(Function<GameController, T> action) throws Exception {
            return onEdt(() -> action.apply(controller));
        }

        /** Like {@link #call} for boolean results (avoids assertTrue's BooleanSupplier overload). */
        boolean is(Predicate<GameController> action) throws Exception {
            return onEdt(() -> action.test(controller));
        }

        void run(java.util.function.Consumer<GameController> action) throws Exception {
            onEdt(() -> {
                action.accept(controller);
                return null;
            });
        }

        String connectProblem(String host, int port, String username) throws Exception {
            return call(c -> c.connect(host, port, username)).orElseThrow();
        }

        Snapshot now() throws Exception {
            return call(Snapshot::of);
        }

        /** Number of notifications so far (read after all pending EDT work has run). */
        int notifications() throws Exception {
            return onEdt(history::size);
        }

        /** Notification snapshots so far, oldest first. */
        List<Snapshot> history() throws Exception {
            return onEdt(() -> List.copyOf(history));
        }

        Snapshot awaitPhase(GamePhase phase) throws Exception {
            return await("phase " + phase, s -> s.phase() == phase);
        }

        /** Waits (bounded, event-driven) until the condition holds for the current state. */
        Snapshot await(String what, Predicate<Snapshot> condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FakeServer.WAIT_MS);
            while (true) {
                wakeups.clear();
                Snapshot current = now();
                if (condition.test(current)) {
                    return current;
                }
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0 || wakeups.poll(remaining, TimeUnit.MILLISECONDS) == null) {
                    fail("timed out waiting for " + what + "; state: " + now());
                }
            }
        }

        void close() throws Exception {
            run(GameController::disconnect);
        }
    }
}
