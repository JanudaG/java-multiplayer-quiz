
package quiz;

import java.io.*;
import java.net.*;
import java.util.*;

public class QuizServer {

    private static final int PORT = 5000;
    private static final Object LOCK = new Object();

    private static final Map<String, PrintWriter> players =
            new LinkedHashMap<>();

    private static boolean gameStarted = false;

    public static void main(String[] args) {

        try (ServerSocket server = new ServerSocket(PORT)) {

            System.out.println("Quiz server running on port " + PORT);

            while (true) {
                Socket socket = server.accept();

                new Thread(() -> handleClient(socket)).start();
            }

        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private static void handleClient(Socket socket) {

        String username = null;

        try (socket;
             BufferedReader input = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()));
             PrintWriter output = new PrintWriter(
                     socket.getOutputStream(), true)) {

            String join = input.readLine();

            if (join == null || !join.startsWith("JOIN|")) {
                output.println("ERROR|First message must be JOIN|username");
                return;
            }

            String requestedName = join.substring(5).trim();

            if (!requestedName.matches("[A-Za-z0-9_-]{1,16}")) {
                output.println(
                        "ERROR|Username must be 1-16 letters, digits, _ or -");
                return;
            }

            synchronized (LOCK) {

                if (gameStarted) {
                    output.println("ERROR|A game has already started");
                    return;
                }

                if (players.containsKey(requestedName)) {
                    output.println("ERROR|Username already in use");
                    return;
                }

                players.put(requestedName, output);
                username = requestedName;
            }

            System.out.println(username + " joined");

            broadcast("INFO|" + username + " joined the lobby");
            broadcastLobby();

            String command;

            while ((command = input.readLine()) != null) {

                if (command.equals("QUIT")) {
                    break;
                }

                if (command.equals("START")) {
                    tryStart(username, output);
                } else {
                    output.println("ERROR|Unknown command");
                }
            }

        } catch (IOException e) {
            System.out.println("Connection lost: " + e.getMessage());

        } finally {

            if (username != null) {
                boolean removed;

                synchronized (LOCK) {
                    removed = players.remove(username) != null;
                }

                if (removed) {
                    System.out.println(username + " left");

                    broadcast("INFO|" + username + " left the lobby");
                    broadcastLobby();
                }
            }
        }
    }

    private static void tryStart(
            String username, PrintWriter output) {

        synchronized (LOCK) {

            String host = players.keySet().iterator().next();

            if (!host.equals(username)) {
                output.println("ERROR|Only the host can start the game");
                return;
            }

            if (players.size() < 2) {
                output.println("ERROR|At least two players are required");
                return;
            }

            if (gameStarted) {
                output.println("ERROR|Game already started");
                return;
            }

            gameStarted = true;
        }

        broadcast("GAME_STARTED|The host started the game!");
    }

    private static void broadcast(String message) {

        List<PrintWriter> recipients;

        synchronized (LOCK) {
            recipients = new ArrayList<>(players.values());
        }

        for (PrintWriter writer : recipients) {
            writer.println(message);
        }
    }

    private static void broadcastLobby() {

        String names;
        String host;

        synchronized (LOCK) {

            if (players.isEmpty()) {
                return;
            }

            names = String.join(",", players.keySet());
            host = players.keySet().iterator().next();
        }

        broadcast("PLAYERS|" + names);
        broadcast("HOST|" + host);
    }
}

