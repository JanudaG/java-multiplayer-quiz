
package quiz;

import java.io.*;
import java.net.*;

public class QuizServer {

    public static void main(String[] args) {
        try (ServerSocket server = new ServerSocket(5000)) {

            System.out.println("Quiz Server started on port 5000");

            while (true) {
                Socket client = server.accept();

                System.out.println("New client connected!");

                Thread thread = new Thread(() -> handleClient(client));
                thread.start();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleClient(Socket socket) {
        try (socket;
             BufferedReader input = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()));
             PrintWriter output = new PrintWriter(
                     socket.getOutputStream(), true)) {

            output.println("Welcome to Multiplayer Quiz!");

            String username = input.readLine();

            if (username == null || username.isBlank()) {
                return;
            }

            System.out.println(username + " joined the server.");
            output.println("Connected successfully as " + username);

            String message;
            while ((message = input.readLine()) != null) {
                if (message.equalsIgnoreCase("exit")) {
                    break;
                }

                System.out.println(username + ": " + message);
                output.println("Server received: " + message);
            }

        } catch (IOException e) {
            System.out.println("Client disconnected.");
        }
    }
}

