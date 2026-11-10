
package quiz;

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class QuizClient {

    public static void main(String[] args) {

        String serverAddress =
                args.length > 0 ? args[0] : "127.0.0.1";

        try (Socket socket = new Socket(serverAddress, 5000);
             BufferedReader input = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()));
             PrintWriter output = new PrintWriter(
                     socket.getOutputStream(), true);
             Scanner scanner = new Scanner(System.in)) {

            System.out.print("Enter username: ");
            String username = scanner.nextLine().trim();

            output.println("JOIN|" + username);

            Thread listener = new Thread(() -> {

                try {
                    String message;

                    while ((message = input.readLine()) != null) {

                        String[] parts = message.split("\\|", 2);

                        if (parts.length < 2) {
                            continue;
                        }

                        switch (parts[0]) {

                            case "PLAYERS" ->
                                System.out.println(
                                    "Online players: " + parts[1]);

                            case "HOST" ->
                                System.out.println(
                                    "Host: " + parts[1]);

                            case "GAME_STARTED" ->
                                System.out.println(
                                    "GAME STARTED: " + parts[1]);

                            case "ERROR" ->
                                System.out.println(
                                    "Error: " + parts[1]);

                            default ->
                                System.out.println(parts[1]);
                        }
                    }

                } catch (IOException ignored) {

                } finally {
                    System.out.println("Disconnected from server.");
                }
            });

            listener.setDaemon(true);
            listener.start();

            System.out.println("Type /start (host only) or /quit");

            while (scanner.hasNextLine()) {

                String command = scanner.nextLine().trim();

                if (command.equalsIgnoreCase("/quit")) {
                    output.println("QUIT");
                    break;

                } else if (command.equalsIgnoreCase("/start")) {
                    output.println("START");

                } else {
                    System.out.println(
                        "Unknown command. Use /start or /quit");
                }
            }

        } catch (IOException e) {
            System.out.println(
                "Connection failed: " + e.getMessage());
        }
    }
}

