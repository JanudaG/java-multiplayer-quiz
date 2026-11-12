package quiz.gui.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;

import quiz.Question;
import quiz.QuestionBank;
import quiz.gui.controller.GameController;
import quiz.gui.model.AnswerOption;
import quiz.gui.model.GamePhase;
import quiz.gui.model.GameState;
import quiz.gui.model.PlayerScore;
import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;

/**
 * Full-application end-to-end test: starts the real {@code QuizServer} in a child process,
 * opens real Swing client windows (built exactly like {@link QuizGuiApp}, minus the theme
 * dialog) and plays complete games by clicking the real buttons.
 *
 * <p>Not a JUnit test (plain {@code main}, no dependencies), so Maven/Surefire ignores it.
 * Needs a desktop session (not headless) and a free port 5000. Takes about 45 seconds and
 * opens up to four windows. Exit code: 0 = all checks passed, 1 = a check failed,
 * 2 = cannot run here.</p>
 *
 * <pre>
 * java -cp "out/main;out/test" quiz.gui.ui.FullAppGuiE2E [screenshot-dir]
 * </pre>
 * (use {@code :} instead of {@code ;} on macOS/Linux). If a directory is given, a PNG of
 * each window is saved at key moments.
 */
public final class FullAppGuiE2E {

    private static final int PORT = 5000;
    private static final Map<String, Boolean> scenarios = new LinkedHashMap<>();
    private static File screenshotDir;
    private static int checks;
    private static int failures;

    /** One GUI client window, wired the same way as {@link QuizGuiApp}. */
    private static final class App {
        final String name;
        GameController controller;
        MainFrame frame;
        ConnectionScreen connection;
        LobbyScreen lobby;
        QuizScreen quiz;
        GameOverScreen gameOver;

        App(String name, int x) throws Exception {
            this.name = name;
            edt(() -> {
                controller = new GameController();
                frame = new MainFrame(controller);
                connection = new ConnectionScreen(controller);
                lobby = new LobbyScreen(controller);
                quiz = new QuizScreen(controller);
                gameOver = new GameOverScreen(controller);
                frame.registerScreen(ScreenNavigator.CONNECTION, connection);
                frame.registerScreen(ScreenNavigator.LOBBY, lobby);
                frame.registerScreen(ScreenNavigator.QUIZ, quiz);
                frame.registerScreen(ScreenNavigator.RESULTS, new RoundResultsScreen(controller));
                frame.registerScreen(ScreenNavigator.GAME_OVER, gameOver);
                ScreenNavigator.install(frame);
                frame.setLocation(x, 40);
                frame.setVisible(true);
                return null;
            });
        }

        /** Types into the connection form and clicks Connect. */
        void connect(String username) throws Exception {
            edt(() -> {
                connection.hostField().setText("127.0.0.1");
                connection.portField().setText(String.valueOf(PORT));
                connection.usernameField().setText(username);
                connection.connectButton().doClick();
                return null;
            });
        }

        /** Clicks an answer option, then Submit Answer. */
        void answer(String letter) throws Exception {
            edt(() -> {
                ((AbstractButton) find(quiz, "option:" + letter)).doClick();
                quiz.submitButton().doClick();
                return null;
            });
        }

        GameState state() throws Exception {
            return edt(controller::getState);
        }

        GamePhase phase() throws Exception {
            return edt(() -> controller.getState().getPhase());
        }

        String screen() throws Exception {
            return edt(() -> frame.getCurrentScreen().orElse("?"));
        }

        int round() throws Exception {
            return edt(() -> {
                var q = controller.getState().getCurrentQuestion();
                return q == null ? 0 : q.round();
            });
        }

        /** Leaderboard as shown by this client, e.g. "alice:10,bob:0". */
        String scores() throws Exception {
            return edt(() -> {
                List<String> parts = new ArrayList<>();
                for (PlayerScore s : controller.getState().getLeaderboard().entries()) {
                    parts.add(s.username() + ":" + s.score());
                }
                return String.join(",", parts);
            });
        }

        void screenshot(String label) throws Exception {
            if (screenshotDir == null) {
                return;
            }
            BufferedImage image = edt(() -> {
                BufferedImage img = new BufferedImage(
                        frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
                frame.paint(img.getGraphics());
                return img;
            });
            ImageIO.write(image, "png", new File(screenshotDir, label + "-" + name + ".png"));
        }

        void close() throws Exception {
            edt(() -> {
                controller.disconnect();
                frame.dispose();
                return null;
            });
        }
    }

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("CANNOT RUN: this test opens real windows and needs a desktop session.");
            System.exit(2);
        }
        if (!portIsFree()) {
            System.out.println("CANNOT RUN: port " + PORT + " is in use. Stop any running QuizServer first.");
            System.exit(2);
        }
        if (args.length > 0) {
            screenshotDir = new File(args[0]);
            screenshotDir.mkdirs();
        }

        Process server = startServer();
        try {
            edt(() -> {
                ThemeManager.apply(Theme.DEFAULT);
                return null;
            });
            play();
        } catch (Exception e) {
            System.out.println("ABORTED: " + e);
            failures++;
        } finally {
            server.destroy();
            server.waitFor(5, TimeUnit.SECONDS);
            server.destroyForcibly();
        }

        System.out.println("\n---- GUI end-to-end scenarios ----");
        scenarios.forEach((name, ok) -> System.out.printf("%-30s %s%n", name, ok ? "PASS" : "FAIL"));
        System.out.println("FullAppGuiE2E: " + (checks - failures) + " passed, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void play() throws Exception {
        App alice = new App("alice", 0);
        App bob = new App("bob", 960);

        // ---- Lobby, host selection, two-player minimum ----
        alice.connect("alice");
        waitFor("alice in lobby", () -> alice.phase() == GamePhase.LOBBY, 5000);
        check("Lobby joining", "alice sees lobby screen", ScreenNavigator.LOBBY, alice.screen());
        check("Host selection", "alice is host", true, alice.state().isHost());
        check("Two-player minimum", "Start disabled while alone", false,
                edt(() -> alice.lobby.startButton().isEnabled()));

        bob.connect("bob");
        waitFor("bob in lobby", () -> bob.phase() == GamePhase.LOBBY, 5000);
        waitFor("alice sees bob", () -> alice.state().getPlayers().size() == 2, 5000);
        check("Lobby joining", "alice sees both players", List.of("alice", "bob"), alice.state().getPlayers());
        check("Lobby joining", "bob sees both players", List.of("alice", "bob"), bob.state().getPlayers());
        check("Host selection", "bob sees alice as host", "alice", bob.state().getHost());
        check("Host selection", "Start disabled for non-host", false,
                edt(() -> bob.lobby.startButton().isEnabled()));
        check("Host selection", "Start enabled for host", true,
                edt(() -> alice.lobby.startButton().isEnabled()));
        alice.screenshot("1-lobby");

        // ---- Duplicate username ----
        App duplicate = new App("duplicate", 480);
        duplicate.connect("alice");
        waitFor("duplicate rejected", () -> duplicate.state().getLastError() != null, 5000);
        check("Duplicate username", "server error shown", "Username already taken",
                duplicate.state().getLastError());
        waitFor("duplicate disconnected", () -> duplicate.phase() == GamePhase.DISCONNECTED, 5000);
        check("Duplicate username", "stays on connection screen", ScreenNavigator.CONNECTION,
                duplicate.screen());
        duplicate.close();

        // ---- Start game ----
        edt(() -> {
            alice.lobby.startButton().doClick();
            return null;
        });
        waitFor("question 1", () -> alice.phase() == GamePhase.QUESTION_ACTIVE
                && bob.phase() == GamePhase.QUESTION_ACTIVE, 5000);
        check("Starting games", "both on quiz screen", List.of(ScreenNavigator.QUIZ, ScreenNavigator.QUIZ),
                List.of(alice.screen(), bob.screen()));
        Question first = QuestionBank.defaultQuestions().get(0);
        check("Starting games", "alice shows QuestionBank question 1", first.text(),
                edt(() -> alice.quiz.questionTextValue()));
        check("Starting games", "bob shows the same question", first.text(),
                edt(() -> bob.quiz.questionTextValue()));

        // ---- Mid-game join ----
        App late = new App("carol", 480);
        late.connect("carol");
        waitFor("carol rejected", () -> late.state().getLastError() != null, 5000);
        check("Mid-game join", "server error shown", "Game already running; join after it finishes",
                late.state().getLastError());
        late.close();

        // ---- Countdown ----
        long before = edt(() -> alice.quiz.remainingMillis());
        String beforeText = edt(() -> alice.quiz.countdownText());
        Thread.sleep(2100);
        long after = edt(() -> alice.quiz.remainingMillis());
        String afterText = edt(() -> alice.quiz.countdownText());
        System.out.println("    countdown: '" + beforeText + "' -> '" + afterText + "'");
        check("Countdown", "starts at no more than 15 s", true, before <= 15_000 && before > 10_000);
        check("Countdown", "drops about 2 s in 2.1 s", true, before - after >= 1500 && before - after <= 2800);
        check("Countdown", "label updates", true, !beforeText.equals(afterText));
        alice.screenshot("2-question");

        // ---- Round 1: alice correct (B), bob wrong ----
        alice.answer("B");
        check("Answering", "second submit blocked", false,
                edt(() -> alice.controller.submitAnswer(AnswerOption.C)));
        bob.answer("A");
        waitFor("round 1 results", () -> alice.phase() == GamePhase.ROUND_ENDED
                && bob.phase() == GamePhase.ROUND_ENDED && !alice.scores().isEmpty(), 5000);
        check("Answering", "both on results screen",
                List.of(ScreenNavigator.RESULTS, ScreenNavigator.RESULTS), List.of(alice.screen(), bob.screen()));
        check("Scoring", "after round 1", "alice:10,bob:0", alice.scores());
        check("Leaderboard", "bob sees same leaderboard", "alice:10,bob:0", bob.scores());
        alice.screenshot("3-round-result");

        // ---- Rounds 2-4 (answers: C, A, D) ----
        String[][] plan = {
            {"C", "C", "alice:20,bob:10"},   // both correct
            {"B", "A", "alice:20,bob:20"},   // tie, ordered by name
            {"A", "D", "bob:30,alice:20"},   // bob leads
        };
        for (int i = 0; i < plan.length; i++) {
            int round = i + 2;
            String expected = plan[i][2];
            waitFor("question " + round, () -> alice.phase() == GamePhase.QUESTION_ACTIVE
                    && bob.phase() == GamePhase.QUESTION_ACTIVE && alice.round() == round, 6000);
            alice.answer(plan[i][0]);
            bob.answer(plan[i][1]);
            waitFor("round " + round + " scores", () -> alice.phase() == GamePhase.ROUND_ENDED
                    && expected.equals(alice.scores()), 5000);
            check("Scoring", "after round " + round, expected, alice.scores());
            check("Leaderboard", "bob's order after round " + round, expected, bob.scores());
        }

        // ---- Round 5: bob does not answer, so the 15 s deadline closes it ----
        waitFor("question 5", () -> alice.phase() == GamePhase.QUESTION_ACTIVE && alice.round() == 5, 6000);
        long start = System.nanoTime();
        alice.answer("A");
        waitFor("game over", () -> alice.phase() == GamePhase.GAME_OVER
                && bob.phase() == GamePhase.GAME_OVER, 20_000);
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("    round 5 closed after %.1f s%n", seconds);
        check("Countdown", "unanswered round closes at ~15 s", true, seconds > 13.5 && seconds < 17);

        // ---- Winners and ties ----
        check("Winners and ties", "both on game over screen",
                List.of(ScreenNavigator.GAME_OVER, ScreenNavigator.GAME_OVER), List.of(alice.screen(), bob.screen()));
        check("Winners and ties", "tie headline", "It's a tie!", edt(() -> alice.gameOver.headlineText()));
        check("Winners and ties", "both winners", List.of("alice", "bob"), alice.state().getWinners());
        check("Winners and ties", "winning score shown", true,
                edt(() -> alice.gameOver.winningScoreText()).contains("30"));
        check("Leaderboard", "final scores", "alice:30,bob:30", bob.scores());
        bob.screenshot("4-game-over-tie");

        // ---- Replay ----
        check("Replay", "Play Again enabled for host", true,
                edt(() -> alice.gameOver.playAgainButton().isEnabled()));
        check("Replay", "Play Again disabled for non-host", false,
                edt(() -> bob.gameOver.playAgainButton().isEnabled()));
        edt(() -> {
            alice.gameOver.playAgainButton().doClick();
            return null;
        });
        waitFor("replay question 1", () -> alice.phase() == GamePhase.QUESTION_ACTIVE
                && bob.phase() == GamePhase.QUESTION_ACTIVE && alice.round() == 1, 5000);
        alice.answer("B");
        bob.answer("D");
        waitFor("replay round 1", () -> alice.phase() == GamePhase.ROUND_ENDED && !alice.scores().isEmpty(), 5000);
        check("Replay", "scores reset for new game", "alice:10,bob:0", alice.scores());

        // ---- Disconnect: bob clicks Leave Game during question 2 ----
        waitFor("replay question 2", () -> alice.phase() == GamePhase.QUESTION_ACTIVE && alice.round() == 2, 6000);
        edt(() -> {
            bob.quiz.leaveButton().doClick();
            return null;
        });
        waitFor("alice game over", () -> alice.phase() == GamePhase.GAME_OVER, 5000);
        check("Disconnect handling", "bob back on connection screen", ScreenNavigator.CONNECTION, bob.screen());
        check("Disconnect handling", "alice moved to game over", ScreenNavigator.GAME_OVER, alice.screen());
        check("Disconnect handling", "remaining player declared winner", List.of("alice"),
                alice.state().getWinners());
        check("Disconnect handling", "player list updated", List.of("alice"), alice.state().getPlayers());
        check("Disconnect handling", "Play Again disabled with one player", false,
                edt(() -> alice.gameOver.playAgainButton().isEnabled()));
        alice.screenshot("5-after-disconnect");

        bob.connect("bob");
        waitFor("bob rejoins", () -> bob.phase() == GamePhase.LOBBY, 5000);
        waitFor("alice sees bob again", () -> alice.state().getPlayers().size() == 2, 5000);
        check("Disconnect handling", "player can rejoin after the game", List.of("alice", "bob"),
                bob.state().getPlayers());

        // ---- Host hand-over ----
        alice.close();
        waitFor("bob becomes host", () -> "bob".equals(bob.state().getHost()), 5000);
        check("Host selection", "host leaves, next player becomes host", true, bob.state().isHost());
        bob.close();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void check(String scenario, String name, Object expected, Object actual) {
        boolean ok = Objects.equals(expected, actual);
        checks++;
        if (!ok) {
            failures++;
        }
        scenarios.merge(scenario, ok, Boolean::logicalAnd);
        System.out.println((ok ? "PASS  " : "FAIL  ") + "[" + scenario + "] " + name
                + (ok ? "" : "\n        expected: " + expected + "\n        actual:   " + actual));
    }

    private interface Condition {
        boolean holds() throws Exception;
    }

    private static void waitFor(String what, Condition condition, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (condition.holds()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("timed out waiting for: " + what);
    }

    /** Runs a task on the Swing event thread and returns its result. */
    private static <T> T edt(Callable<T> task) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(task.call());
            } catch (Exception e) {
                error.set(e);
            }
        });
        if (error.get() != null) {
            throw error.get();
        }
        return value.get();
    }

    private static Component find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container container) {
                Component found = find(container, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean portIsFree() {
        try {
            new ServerSocket(PORT).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Starts "java quiz.QuizServer" with this program's classpath. */
    private static Process startServer() throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "quiz.QuizServer")
                .redirectErrorStream(true)
                .start();
        CountDownLatch ready = new CountDownLatch(1);
        Thread output = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
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
}
