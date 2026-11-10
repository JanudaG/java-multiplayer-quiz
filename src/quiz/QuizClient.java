

package quiz;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

public class QuizClient {

    public static void main(String[] args) {

        String host = args.length > 0
                ? args[0] : "127.0.0.1";

        AtomicInteger activeRound = new AtomicInteger(0);

        try (Socket socket = new Socket(host, 5000);

             BufferedReader input = new BufferedReader(
                 new InputStreamReader(
                     socket.getInputStream(),
                     StandardCharsets.UTF_8));

             PrintWriter output = new PrintWriter(
                 new OutputStreamWriter(
                     socket.getOutputStream(),
                     StandardCharsets.UTF_8), true);

             Scanner scanner = new Scanner(System.in)) {

            System.out.print("Enter username: ");

            String username = scanner.nextLine().trim();

            output.println("JOIN|" + username);

            // Listener receives messages in the background
            Thread listener = new Thread(() -> {

                try {
                    String message;

                    while ((message = input.readLine()) != null) {

                        String[] parts = message.split("\\|", -1);

                        switch (parts[0]) {

                            case "JOINED" ->
                                System.out.println(
                                    "Welcome, " + parts[1]);

                            case "PLAYERS" ->
                                System.out.println(
                                    "Online: " + parts[1]);

                            case "HOST" ->
                                System.out.println(
                                    "Host: " + parts[1]);

                            case "INFO" ->
                                System.out.println(
                                    "[Info] " + parts[1]);

                            case "GAME_STARTED" ->
                                System.out.println(
                                    "Game started: " + parts[1]);

                            case "QUESTION" -> {

                                activeRound.set(
                                        Integer.parseInt(parts[1]));

                                System.out.println(
                                    "\nQUESTION " + parts[1]
                                    + "/" + parts[2]
                                    + " (" + parts[3] + " seconds)");

                                System.out.println(parts[4]);

                                System.out.println(
                                    "A. " + parts[5]);

                                System.out.println(
                                    "B. " + parts[6]);

                                System.out.println(
                                    "C. " + parts[7]);

                                System.out.println(
                                    "D. " + parts[8]);

                                System.out.println(
                                    "Type A, B, C, or D:");
                            }

                            case "ANSWER_ACCEPTED" ->
                                System.out.println(
                                    "Answer recorded for round "
                                    + parts[1]);

                            case "ROUND_RESULT" -> {

                                activeRound.set(0);

                                System.out.println(
                                    "Round " + parts[1]
                                    + " finished. Correct answer: "
                                    + parts[2]);
                            }

                            case "SCORES" ->
                                System.out.println(
                                    "Leaderboard: " + parts[1]);

                            case "GAME_OVER" -> {

                                activeRound.set(0);

                                System.out.println(
                                    "GAME OVER! Winner(s): "
                                    + parts[1]
                                    + " | Points: " + parts[2]);

                                System.out.println(
                                    "Host can type /start to play again.");
                            }

                            case "ERROR" ->
                                System.out.println(
                                    "[Error] " + parts[1]);

                            default ->
                                System.out.println(
                                    "Server: " + message);
                        }
                    }

                } catch (IOException e) {

                    System.out.println(
                        "Connection lost: " + e.getMessage());

                } finally {
                    System.out.println(
                        "Disconnected from server.");
                }
            });

            listener.setDaemon(true);
            listener.start();

            System.out.println(
                "Commands: /start (host), A/B/C/D (answer), /quit");

            // Accept player commands
            while (scanner.hasNextLine()) {

                String command = scanner.nextLine().trim();

                if (command.equalsIgnoreCase("/quit")) {

                    output.println("QUIT");
                    break;

                } else if (command.equalsIgnoreCase("/start")) {

                    output.println("START");

                } else if (command.matches("(?i)[ABCD]")) {

                    int round = activeRound.get();

                    if (round == 0) {

                        System.out.println(
                            "Wait for the next question.");

                    } else {

                        output.println(
                            "ANSWER|" + round + "|"
                            + command.toUpperCase());
                    }

                } else {

                    System.out.println(
                        "Use /start, A, B, C, D or /quit.");
                }
            }

        } catch (IOException e) {

            System.out.println(
                "Could not connect: " + e.getMessage());
        }
    }
}
