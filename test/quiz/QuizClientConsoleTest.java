
package quiz;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Regression test for the console client: runs the real QuizClient in a child process
 * against a scripted fake server on port 5000 and checks what it prints.
 *
 * Run: java -cp "out/main;out/test" quiz.QuizClientConsoleTest   (needs port 5000 free)
 */
public final class QuizClientConsoleTest {

    private static final int PORT = 5000;

    public static void main(String[] args) throws Exception {

        SelfTest t = new SelfTest("QuizClientConsoleTest");

        ServerSocket server;
        try {
            server = new ServerSocket(PORT);
        } catch (IOException e) {
            System.out.println("ABORT: port " + PORT + " is in use. Stop any running QuizServer first.");
            System.exit(2);
            return;
        }

        String java = ProcessHandle.current().info().command().orElse("java");
        Process client = new ProcessBuilder(java, "-cp",
                System.getProperty("java.class.path"), "quiz.QuizClient", "127.0.0.1")
                .redirectErrorStream(true)
                .start();

        StringBuffer output = new StringBuffer();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    client.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // Client stopped
            }
        });
        reader.setDaemon(true);
        reader.start();

        PrintWriter keyboard = new PrintWriter(new OutputStreamWriter(
                client.getOutputStream(), StandardCharsets.UTF_8), true);

        try (server) {
            server.setSoTimeout(10_000);
            keyboard.println("tester");

            try (Socket socket = server.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(
                         socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

                socket.setSoTimeout(10_000);
                t.checkEquals("client sends JOIN", "JOIN|tester", in.readLine());

                // Messages exactly as QuizServer sends them
                for (String line : List.of(
                        "JOINED|tester",
                        "PLAYERS|tester,bob",
                        "HOST|tester",
                        "INFO|bob joined the lobby",
                        "GAME_STARTED|5 questions",
                        "QUESTION|1|5|15|Which protocol is reliable?|UDP|HTTP|TCP|ICMP",
                        "ERROR|Send ANSWER|round|A, B, C or D",
                        "ERROR|You already answered this round",
                        "ERROR|",
                        "ANSWER_ACCEPTED|1",
                        "ROUND_RESULT|1|C",
                        "SCORES|tester:10,bob:0",
                        "GAME_OVER|bob,tester|30",
                        "INFO|Match ended: not enough players",
                        "ERROR",
                        "SOMETHING_NEW|x|y",
                        "INFO|last message")) {
                    out.println(line);
                }

                waitFor(output, "[Info] last message");

                keyboard.println("/quit");
                t.checkEquals("client sends QUIT", "QUIT", in.readLine());
            }
        }

        client.waitFor(10, TimeUnit.SECONDS);
        client.destroyForcibly();
        reader.join(2000);
        String text = output.toString();
        System.out.println("---- client output ----\n" + text + "-----------------------");

        // Issue 1 regression: the full text, including '|', must be shown
        t.check("ERROR with '|' shown in full",
                text.contains("[Error] Send ANSWER|round|A, B, C or D\n"));
        t.check("ordinary ERROR shown", text.contains("[Error] You already answered this round\n"));
        t.check("empty ERROR payload handled",
                text.contains("[Error] \nAnswer recorded for round 1\n"));
        t.check("ERROR without '|' handled",
                text.contains("[Info] Match ended: not enough players\n[Error] \nServer: SOMETHING_NEW|x|y\n"));

        // Other messages are unchanged
        t.check("JOINED", text.contains("Welcome, tester\n"));
        t.check("PLAYERS", text.contains("Online: tester,bob\n"));
        t.check("HOST", text.contains("Host: tester\n"));
        t.check("INFO", text.contains("[Info] bob joined the lobby\n"));
        t.check("GAME_STARTED", text.contains("Game started: 5 questions\n"));
        t.check("QUESTION header", text.contains("QUESTION 1/5 (15 seconds)\n"));
        t.check("QUESTION text and options", text.contains(
                "Which protocol is reliable?\nA. UDP\nB. HTTP\nC. TCP\nD. ICMP\n"));
        t.check("ANSWER_ACCEPTED", text.contains("Answer recorded for round 1\n"));
        t.check("ROUND_RESULT", text.contains("Round 1 finished. Correct answer: C\n"));
        t.check("SCORES", text.contains("Leaderboard: tester:10,bob:0\n"));
        t.check("GAME_OVER", text.contains("GAME OVER! Winner(s): bob,tester | Points: 30\n"));
        t.check("unknown message printed raw", text.contains("Server: SOMETHING_NEW|x|y\n"));
        t.check("client kept running after malformed lines", text.contains("[Info] last message\n"));

        t.finish();
    }

    private static void waitFor(StringBuffer output, String text) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end && output.indexOf(text) < 0) {
            Thread.sleep(50);
        }
    }
}
