
package quiz;

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class QuizClient {

    public static void main(String[] args) {

        try (Socket socket = new Socket("localhost", 5000);
             BufferedReader input = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()));
             PrintWriter output = new PrintWriter(
                     socket.getOutputStream(), true);
             Scanner scanner = new Scanner(System.in)) {

            System.out.println(input.readLine());

            System.out.print("Enter your username: ");
            String username = scanner.nextLine();
            output.println(username);

            System.out.println(input.readLine());

            while (true) {
                System.out.print("Enter message (or exit): ");
                String message = scanner.nextLine();

                output.println(message);

                if (message.equalsIgnoreCase("exit")) {
                    break;
                }

                System.out.println(input.readLine());
            }

        } catch (IOException e) {
            System.out.println("Could not connect to server.");
            e.printStackTrace();
        }
    }
}
