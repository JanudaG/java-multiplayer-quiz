
package quiz;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class QuizServer {

    private static final int PORT = 5000;
    private static final int QUESTION_SECONDS = 15;

    // Question bank (validated by QuestionBank)
    private static final List<Question> QUESTIONS =
            QuestionBank.defaultQuestions();

    // Shared multiplayer state
    private final Object lock = new Object();

    private final Map<String, ClientHandler> players =
            new LinkedHashMap<>();

    private final Map<String, Integer> scores = new HashMap<>();
    private final Set<String> answered = new HashSet<>();

    // Server-side timer
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private boolean gameStarted = false;
    private boolean roundOpen = false;
    private int questionIndex = -1;
    private int sessionId = 0;
    private long deadlineNanos;

    private ScheduledFuture<?> roundTimer;

    public static void main(String[] args) {
        new QuizServer().run();
    }

    private void run() {

        try (ServerSocket server = new ServerSocket(PORT)) {

            System.out.println(
                    "Quiz server listening on port " + PORT);

            while (true) {
                Socket socket = server.accept();

                new Thread(new ClientHandler(socket)).start();
            }

        } catch (IOException e) {
            System.err.println(
                    "Server error: " + e.getMessage());

        } finally {
            scheduler.shutdownNow();
        }
    }

    // Each connected client has a handler thread
    private final class ClientHandler implements Runnable {

        private final Socket socket;
        private PrintWriter output;
        private String username;

        private ClientHandler(Socket socket) {
            this.socket = socket;
        }

        private void send(String message) {
            output.println(message);
        }

        @Override
        public void run() {

            try (socket;
                 BufferedReader input = new BufferedReader(
                     new InputStreamReader(
                         socket.getInputStream(),
                         StandardCharsets.UTF_8));
                 PrintWriter writer = new PrintWriter(
                     new OutputStreamWriter(
                         socket.getOutputStream(),
                         StandardCharsets.UTF_8), true)) {

                output = writer;

                String joinMessage = input.readLine();

                if (joinMessage == null
                        || !joinMessage.startsWith("JOIN|")) {
                    send("ERROR|First message must be JOIN|username");
                    return;
                }

                String requested =
                        joinMessage.substring(5).trim();

                if (!requested.matches("[A-Za-z0-9_-]{1,16}")) {
                    send("ERROR|Use a username of 1-16 letters, digits, _ or -");
                    return;
                }

                synchronized (lock) {

                    if (gameStarted) {
                        send("ERROR|Game already running; join after it finishes");
                        return;
                    }

                    if (players.containsKey(requested)) {
                        send("ERROR|Username already taken");
                        return;
                    }

                    username = requested;

                    players.put(username, this);
                    scores.put(username, 0);

                    send("JOINED|" + username);

                    broadcastLocked(
                            "INFO|" + username + " joined the lobby");

                    broadcastLobbyLocked();
                }

                String command;

                while ((command = input.readLine()) != null) {

                    if (command.equals("QUIT")) {
                        break;
                    }

                    if (command.equals("START")) {
                        startGame(this);

                    } else if (command.startsWith("ANSWER|")) {
                        acceptAnswer(this, command);

                    } else {
                        synchronized (lock) {
                            send("ERROR|Unknown command");
                        }
                    }
                }

            } catch (IOException e) {
                System.out.println(
                        "Connection closed: " + e.getMessage());

            } finally {

                synchronized (lock) {

                    if (username != null
                            && players.get(username) == this) {

                        players.remove(username);
                        scores.remove(username);
                        answered.remove(username);

                        System.out.println(username + " disconnected");

                        broadcastLocked(
                                "INFO|" + username + " left the lobby");

                        broadcastLobbyLocked();

                        if (gameStarted && players.size() < 2) {

                            if (roundTimer != null) {
                                roundTimer.cancel(false);
                            }

                            roundOpen = false;

                            broadcastLocked(
                                    "INFO|Match ended: not enough players");

                            finishGameLocked();

                        } else if (roundOpen
                                && answered.size() == players.size()) {

                            closeRoundLocked();
                        }
                    }
                }
            }
        }
    }

    // Only the host may start a game
    private void startGame(ClientHandler sender) {

        synchronized (lock) {

            if (gameStarted) {
                sender.send("ERROR|Game already running");
                return;
            }

            if (players.isEmpty()
                    || players.values().iterator().next() != sender) {

                sender.send("ERROR|Only the host can start");
                return;
            }

            if (players.size() < 2) {
                sender.send("ERROR|At least two players are required");
                return;
            }

            gameStarted = true;
            questionIndex = -1;
            sessionId++;

            scores.replaceAll((name, oldScore) -> 0);

            broadcastLocked(
                    "GAME_STARTED|" + QUESTIONS.size() + " questions");

            beginNextRoundLocked();
        }
    }

    // Broadcast the next question
    private void beginNextRoundLocked() {

        questionIndex++;
        answered.clear();
        roundOpen = true;

        int round = questionIndex + 1;
        int currentSession = sessionId;

        deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(QUESTION_SECONDS);

        broadcastLocked(
                QUESTIONS.get(questionIndex).toMessage(
                        round, QUESTIONS.size(), QUESTION_SECONDS));

        roundTimer = scheduler.schedule(() -> {

            synchronized (lock) {

                if (gameStarted && roundOpen
                        && sessionId == currentSession
                        && questionIndex + 1 == round) {

                    closeRoundLocked();
                }
            }

        }, QUESTION_SECONDS, TimeUnit.SECONDS);
    }

    // Validate and score an answer
    private void acceptAnswer(
            ClientHandler sender, String command) {

        String[] parts = command.split("\\|", -1);

        synchronized (lock) {

            if (!gameStarted || !roundOpen
                    || System.nanoTime() >= deadlineNanos) {

                sender.send(
                        "ERROR|No active question or time has expired");
                return;
            }

            if (parts.length != 3
                    || !parts[1].equals(
                        String.valueOf(questionIndex + 1))
                    || !parts[2].matches("[ABCD]")) {

                sender.send(
                        "ERROR|Send ANSWER|round|A, B, C or D");
                return;
            }

            if (!answered.add(sender.username)) {
                sender.send(
                        "ERROR|You already answered this round");
                return;
            }

            Question question = QUESTIONS.get(questionIndex);

            scores.merge(
                    sender.username,
                    GameRules.pointsFor(question, parts[2]),
                    Integer::sum);

            sender.send(
                    "ANSWER_ACCEPTED|" + (questionIndex + 1));

            // Finish early if everyone has answered
            if (answered.size() == players.size()) {
                closeRoundLocked();
            }
        }
    }

    // Finish the current question
    private void closeRoundLocked() {

        if (!roundOpen) {
            return;
        }

        roundOpen = false;

        if (roundTimer != null) {
            roundTimer.cancel(false);
        }

        Question question = QUESTIONS.get(questionIndex);

        broadcastLocked(
                "ROUND_RESULT|" + (questionIndex + 1)
                        + "|" + question.correctAnswer());

        broadcastLocked(
                "SCORES|" + scoresTextLocked());

        // Check if this was the last question
        if (questionIndex == QUESTIONS.size() - 1) {

            finishGameLocked();

        } else {

            int finishedRound = questionIndex + 1;
            int currentSession = sessionId;

            // Give players 3 seconds to see the results
            scheduler.schedule(() -> {

                synchronized (lock) {

                    if (gameStarted && !roundOpen
                            && sessionId == currentSession
                            && questionIndex + 1 == finishedRound) {

                        beginNextRoundLocked();
                    }
                }

            }, 3, TimeUnit.SECONDS);
        }
    }

    // Calculate the winner
    private void finishGameLocked() {

        roundOpen = false;

        if (roundTimer != null) {
            roundTimer.cancel(false);
        }

        int best = GameRules.topScore(scores);

        String winners = String.join(",", GameRules.winners(scores));

        broadcastLocked(
                "SCORES|" + scoresTextLocked());

        broadcastLocked(
                "GAME_OVER|" + winners + "|" + best);

        gameStarted = false;
    }

    // Prepare leaderboard sorted by score
    private String scoresTextLocked() {

        return GameRules.leaderboard(scores).stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(","));
    }

    private void broadcastLobbyLocked() {

        if (players.isEmpty()) {
            return;
        }

        broadcastLocked(
                "PLAYERS|" + String.join(",", players.keySet()));

        broadcastLocked(
                "HOST|" + players.keySet().iterator().next());
    }

    // Send a message to all connected players
    private void broadcastLocked(String message) {

        for (ClientHandler player : players.values()) {
            player.send(message);
        }
    }
}

