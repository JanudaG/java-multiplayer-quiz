
package quiz;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end test: starts the real QuizServer on port 5000 in a separate
 * process and plays games with raw TCP clients using the existing protocol.
 *
 * Takes about 45 seconds (one round deliberately waits for the 15 s deadline).
 * Run: java -cp out/main;out/test quiz.QuizServerIntegrationTest
 */
public final class QuizServerIntegrationTest {

    private static final int PORT = 5000;
    private static final long WAIT_MS = 5_000;

    // Correct answers of the default bank: B, C, A, D, A
    private static final String[] CORRECT = {"B", "C", "A", "D", "A"};

    private static final String[] SCENARIOS = {
        null,
        "Two players connecting",
        "Host-only game start",
        "Minimum two-player requirement",
        "Identical questions delivered to both clients",
        "Correct and incorrect scoring",
        "Duplicate-answer rejection",
        "Invalid round-number rejection",
        "15-second answer deadline",
        "Round results and score updates",
        "Game-over and winner determination",
        "Duplicate username rejection",
        "Mid-game join rejection",
        "Player disconnection handling",
        "Starting another game after completion",
    };

    // null = NOT TESTED, TRUE = all checks passed, FALSE = a check failed
    private static final Boolean[] status = new Boolean[SCENARIOS.length];
    private static final SelfTest t = new SelfTest("QuizServerIntegrationTest");

    public static void main(String[] args) throws Exception {

        if (!portIsFree()) {
            System.out.println("ABORT: port " + PORT
                    + " is already in use. Stop any running QuizServer first.");
            System.exit(2);
        }

        Process server = startServer();
        boolean ok;

        try {
            playScenarios();
        } catch (Exception e) {
            System.out.println("ABORTED: " + e);
        } finally {
            server.destroy();
            server.waitFor(5, TimeUnit.SECONDS);
            server.destroyForcibly();
        }

        System.out.println("\n---- Scenario results ----");
        for (int i = 1; i < SCENARIOS.length; i++) {
            String result = status[i] == null ? "NOT TESTED"
                    : status[i] ? "PASS" : "FAIL";
            System.out.printf("%2d. %-48s %s%n", i, SCENARIOS[i], result);
        }
        System.out.println();

        ok = t.summary();
        for (int i = 1; i < SCENARIOS.length; i++) {
            ok &= Boolean.TRUE.equals(status[i]);
        }
        System.exit(ok ? 0 : 1);
    }

    private static void playScenarios() throws Exception {

        // ---- Lobby ----
        TestClient alice = TestClient.join("alice");
        check(1, "alice receives JOINED|alice", "JOINED|alice", alice.await("JOINED"));
        check(1, "alice is host", "HOST|alice", alice.await("HOST"));

        alice.send("START");
        check(3, "host alone cannot start", "ERROR|At least two players are required",
                alice.await("ERROR"));

        TestClient bob = TestClient.join("bob");
        check(1, "bob receives JOINED|bob", "JOINED|bob", bob.await("JOINED"));
        check(1, "bob sees both players", "PLAYERS|alice,bob", bob.await("PLAYERS"));
        check(1, "bob sees alice as host", "HOST|alice", bob.await("HOST"));
        check(1, "alice told bob joined", "INFO|bob joined the lobby", alice.await("INFO|bob"));
        check(1, "alice sees both players", "PLAYERS|alice,bob", alice.await("PLAYERS"));

        TestClient dup = TestClient.join("alice");
        check(11, "duplicate username rejected", "ERROR|Username already taken",
                dup.await("ERROR"));
        check(11, "duplicate client is disconnected", true, dup.awaitClosed());

        TestClient bad = TestClient.join("bad name!");
        check(11, "invalid username rejected", true,
                startsWith(bad.await("ERROR"), "ERROR|Use a username"));
        bad.close();

        bob.send("START");
        check(2, "non-host cannot start", "ERROR|Only the host can start", bob.await("ERROR"));

        // ---- Game 1 ----
        alice.send("START");
        check(2, "host starts game", "GAME_STARTED|5 questions", alice.await("GAME_STARTED"));
        bob.await("GAME_STARTED");

        String qa = alice.await("QUESTION");
        String qb = bob.await("QUESTION");
        check(4, "both clients receive the same question", qa, qb);
        check(4, "question matches QuestionBank question 1",
                QuestionBank.defaultQuestions().get(0).toMessage(1, 5, 15), qa);

        TestClient carol = TestClient.join("carol");
        check(12, "join during game rejected",
                "ERROR|Game already running; join after it finishes", carol.await("ERROR"));
        check(12, "rejected client is disconnected", true, carol.awaitClosed());

        bob.send("ANSWER|2|A");
        check(7, "future round number rejected", true,
                startsWith(bob.await("ERROR"), "ERROR|Send ANSWER"));
        bob.send("ANSWER|0|A");
        check(7, "round 0 rejected", true, startsWith(bob.await("ERROR"), "ERROR|Send ANSWER"));
        bob.send("ANSWER|x|A");
        check(7, "non-numeric round rejected", true,
                startsWith(bob.await("ERROR"), "ERROR|Send ANSWER"));
        bob.send("ANSWER|1|E");
        check(7, "invalid choice E rejected", true,
                startsWith(bob.await("ERROR"), "ERROR|Send ANSWER"));
        bob.send("ANSWER|1|b");
        check(7, "lowercase choice rejected", true,
                startsWith(bob.await("ERROR"), "ERROR|Send ANSWER"));

        alice.send("ANSWER|1|B");                              // correct
        check(5, "correct answer accepted", "ANSWER_ACCEPTED|1", alice.await("ANSWER_ACCEPTED"));
        alice.send("ANSWER|1|C");
        check(6, "second answer in same round rejected",
                "ERROR|You already answered this round", alice.await("ERROR"));

        bob.send("ANSWER|1|A");                                // wrong
        check(5, "incorrect answer accepted", "ANSWER_ACCEPTED|1", bob.await("ANSWER_ACCEPTED"));

        check(9, "round 1 result sent to alice", "ROUND_RESULT|1|B", alice.await("ROUND_RESULT"));
        check(9, "round 1 result sent to bob", "ROUND_RESULT|1|B", bob.await("ROUND_RESULT"));
        check(5, "scores after round 1 (10 / 0)", "SCORES|alice:10,bob:0", alice.await("SCORES"));
        check(9, "bob receives same scores", "SCORES|alice:10,bob:0", bob.await("SCORES"));

        alice.send("ANSWER|1|B");
        check(7, "answer between rounds rejected", "ERROR|No active question or time has expired",
                alice.await("ERROR"));

        // Round 2: both correct
        playRound(alice, bob, 2, CORRECT[1], CORRECT[1], "SCORES|alice:20,bob:10");
        // Round 3: alice wrong, bob correct (tie, sorted by name)
        playRound(alice, bob, 3, "B", CORRECT[2], "SCORES|alice:20,bob:20");
        // Round 4: alice wrong, bob correct (bob leads)
        playRound(alice, bob, 4, "A", CORRECT[3], "SCORES|bob:30,alice:20");

        // Round 5: only alice answers; bob must time out after 15 seconds
        alice.await("QUESTION|5");
        long start = System.nanoTime();
        bob.await("QUESTION|5");
        alice.send("ANSWER|5|" + CORRECT[4]);
        alice.await("ANSWER_ACCEPTED");
        String result5 = alice.await("ROUND_RESULT", 20_000);
        double seconds = (System.nanoTime() - start) / 1e9;
        check(8, "round closes on deadline", "ROUND_RESULT|5|A", result5);
        check(8, "deadline is about 15 s (was " + String.format("%.1f", seconds) + " s)",
                true, seconds >= 14.5 && seconds <= 16.5);
        check(8, "unanswered player scores 0", "SCORES|alice:30,bob:30", alice.await("SCORES"));

        check(10, "tie announced with both winners", "GAME_OVER|alice,bob|30",
                alice.await("GAME_OVER"));
        check(9, "bob receives round 5 result", "ROUND_RESULT|5|A", bob.await("ROUND_RESULT"));
        // Regression: the final leaderboard must be sent exactly once, then GAME_OVER
        check(10, "final SCORES sent once, then GAME_OVER (tie)",
                List.of("SCORES|alice:30,bob:30", "GAME_OVER|alice,bob|30"),
                bob.linesUntil("GAME_OVER"));

        // Messages arrive in order, so read GAME_OVER before sending the late answer
        bob.send("ANSWER|5|A");
        check(8, "late answer rejected", "ERROR|No active question or time has expired",
                bob.await("ERROR"));

        // ---- Game 2: restart, then a disconnect ----
        alice.send("START");
        check(14, "host can start again", "GAME_STARTED|5 questions", alice.await("GAME_STARTED"));
        alice.await("QUESTION|1");
        bob.await("QUESTION|1");
        alice.send("ANSWER|1|" + CORRECT[0]);
        bob.send("ANSWER|1|A");
        check(14, "scores reset for new game", "SCORES|alice:10,bob:0", alice.await("SCORES"));

        alice.await("QUESTION|2");
        bob.close();                                           // abrupt disconnect
        check(13, "alice told bob left", "INFO|bob left the lobby", alice.await("INFO|bob left"));
        check(13, "player list updated", "PLAYERS|alice", alice.await("PLAYERS"));
        check(13, "match ended", "INFO|Match ended: not enough players",
                alice.await("INFO|Match ended"));
        // Confirmed rule: the last remaining player wins, after one final SCORES message
        check(13, "remaining player declared winner after one final SCORES",
                List.of("SCORES|alice:10", "GAME_OVER|alice|10"),
                alice.linesUntil("GAME_OVER"));

        // ---- Game 3: new player joins after game over, single winner ----
        TestClient dave = TestClient.join("dave");
        check(14, "join allowed after game over", "JOINED|dave", dave.await("JOINED"));
        alice.send("START");
        check(14, "third game starts", "GAME_STARTED|5 questions", dave.await("GAME_STARTED"));

        for (int round = 1; round <= 5; round++) {
            String wrong = CORRECT[round - 1].equals("A") ? "B" : "A";
            alice.await("QUESTION|" + round);
            dave.await("QUESTION|" + round);
            alice.send("ANSWER|" + round + "|" + CORRECT[round - 1]);
            dave.send("ANSWER|" + round + "|" + wrong);
            dave.await("ROUND_RESULT|" + round);
        }
        check(10, "single winner: final SCORES sent once, then GAME_OVER",
                List.of("SCORES|alice:50,dave:0", "GAME_OVER|alice|50"),
                dave.linesUntil("GAME_OVER"));

        alice.send("QUIT");
        check(13, "host leaves: next player becomes host", "HOST|dave", dave.await("HOST"));
        dave.close();
        alice.close();
    }

    // Wait for a round, send both answers, and check the scores that follow
    private static void playRound(TestClient alice, TestClient bob, int round,
                                  String aliceAnswer, String bobAnswer,
                                  String expectedScores) throws InterruptedException {

        String qa = alice.await("QUESTION|" + round);
        String qb = bob.await("QUESTION|" + round);
        check(4, "round " + round + " question identical for both", qa, qb);
        check(4, "round " + round + " matches QuestionBank",
                QuestionBank.defaultQuestions().get(round - 1).toMessage(round, 5, 15), qa);

        alice.send("ANSWER|" + round + "|" + aliceAnswer);
        bob.send("ANSWER|" + round + "|" + bobAnswer);

        check(9, "round " + round + " result", "ROUND_RESULT|" + round + "|" + CORRECT[round - 1],
                alice.await("ROUND_RESULT"));
        check(9, "scores after round " + round, expectedScores, alice.await("SCORES"));
        bob.await("SCORES");
    }

    private static void check(int scenario, String name, Object expected, Object actual) {
        int before = t.failed();
        t.checkEquals("[S" + scenario + "] " + name, expected, actual);
        boolean passed = t.failed() == before;
        status[scenario] = (status[scenario] == null || status[scenario]) && passed;
    }

    private static boolean startsWith(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }

    private static boolean portIsFree() {
        try {
            new ServerSocket(PORT).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // Launch "java quiz.QuizServer" with this test's classpath
    private static Process startServer() throws Exception {

        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(java, "-cp",
                System.getProperty("java.class.path"), "quiz.QuizServer")
                .redirectErrorStream(true)
                .start();

        CountDownLatch ready = new CountDownLatch(1);
        Thread output = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    System.out.println("    [server] " + line);
                    if (line.contains("listening")) {
                        ready.countDown();
                    }
                }
            } catch (IOException ignored) {
                // Server stopped
            }
        });
        output.setDaemon(true);
        output.start();

        if (!ready.await(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("QuizServer did not start");
        }
        return process;
    }

    /** A raw protocol client that queues every line received from the server. */
    private static final class TestClient {

        private static final String CLOSED = "\u0000closed";

        private final String name;
        private final Socket socket;
        private final PrintWriter out;
        private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        private final List<String> skipped = new ArrayList<>();
        private List<String> lastSkipped = new ArrayList<>();

        private TestClient(String name) throws IOException {
            this.name = name;
            socket = new Socket("127.0.0.1", PORT);
            out = new PrintWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8), true);

            BufferedReader in = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));

            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        inbox.add(line);
                    }
                } catch (IOException ignored) {
                    // Socket closed
                }
                inbox.add(CLOSED);
            });
            reader.setDaemon(true);
            reader.start();
        }

        static TestClient join(String username) throws IOException {
            TestClient client = new TestClient(username);
            client.send("JOIN|" + username);
            return client;
        }

        void send(String line) {
            out.println(line);
        }

        String await(String prefix) throws InterruptedException {
            return await(prefix, WAIT_MS);
        }

        /** Next message starting with prefix (earlier messages are skipped), or null. */
        String await(String prefix, long timeoutMs) throws InterruptedException {
            long end = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long left = end - System.currentTimeMillis();
                String line = left > 0 ? inbox.poll(left, TimeUnit.MILLISECONDS) : null;
                if (line == null || line.equals(CLOSED)) {
                    if (line != null) {
                        inbox.add(CLOSED);
                    }
                    System.out.println("    [" + name + "] no '" + prefix
                            + "' message; skipped " + skipped);
                    return null;
                }
                if (line.startsWith(prefix)) {
                    lastSkipped = new ArrayList<>(skipped);
                    skipped.clear();
                    return line;
                }
                skipped.add(line);
            }
        }

        /** Every message up to and including the next one starting with prefix (exact order). */
        List<String> linesUntil(String prefix) throws InterruptedException {
            String match = await(prefix);
            List<String> lines = new ArrayList<>(lastSkipped);
            lines.add(match);
            return lines;
        }

        /** True if the server closes this connection within the timeout. */
        boolean awaitClosed() throws InterruptedException {
            long end = System.currentTimeMillis() + WAIT_MS;
            while (System.currentTimeMillis() < end) {
                String line = inbox.poll(end - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                if (CLOSED.equals(line)) {
                    close();
                    return true;
                }
            }
            return false;
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed
            }
        }
    }
}
