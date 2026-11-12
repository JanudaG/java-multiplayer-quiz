package quiz.gui.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A scripted quiz server on the loopback interface, for controller integration tests.
 *
 * <p>It does not implement any game rules: the test decides exactly which lines are sent
 * and checks exactly which lines the client sent. Every wait is bounded by
 * {@link #WAIT_MS}. Nothing leaves the machine.</p>
 */
final class FakeServer implements AutoCloseable {

    static final long WAIT_MS = 5000;

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private final ServerSocket serverSocket;
    private final BlockingQueue<Client> accepted = new LinkedBlockingQueue<>();
    private final List<Client> allClients = new CopyOnWriteArrayList<>();
    private final Thread acceptor;

    FakeServer() throws IOException {
        serverSocket = new ServerSocket(0, 50, LOOPBACK);
        acceptor = new Thread(this::acceptLoop, "fake-server-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String host() {
        return LOOPBACK.getHostAddress();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    /** Waits for the next accepted client connection. */
    Client accept() throws InterruptedException {
        Client client = accepted.poll(WAIT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(client, "timed out waiting for a client to connect");
        return client;
    }

    /** Every line received so far, from every client, in per-client order. */
    List<String> allReceivedLines() {
        return allClients.stream().flatMap(c -> c.received.stream()).toList();
    }

    /** Sends the same lines to several clients, like QuizServer's broadcast. */
    static void broadcast(List<Client> clients, String... lines) throws IOException {
        for (Client client : clients) {
            client.send(lines);
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        for (Client client : allClients) {
            client.close();
        }
    }

    private void acceptLoop() {
        try {
            while (true) {
                Socket socket = serverSocket.accept();
                Client client = new Client(socket);
                allClients.add(client);
                accepted.add(client);
            }
        } catch (IOException e) {
            // server socket closed: stop accepting
        }
    }

    /** The server side of one client connection. */
    static final class Client implements AutoCloseable {

        /** Marks end of stream in {@link #lines}. Compared by identity. */
        private static final String EOF = new String("<eof>");

        private final Socket socket;
        private final OutputStream out;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final List<String> received = new CopyOnWriteArrayList<>();

        private Client(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
            Thread reader = new Thread(() -> readLoop(in), "fake-server-client-reader");
            reader.setDaemon(true);
            reader.start();
        }

        private void readLoop(BufferedReader in) {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    received.add(line);
                    lines.add(line);
                }
            } catch (IOException e) {
                // reset or closed locally: treat as end of stream
            } finally {
                lines.add(EOF);
                closeQuietly(); // like QuizServer: hang up once the client has finished
            }
        }

        private void closeQuietly() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing more to do
            }
        }

        /** Waits for the next line; returns null if the client closed the connection instead. */
        String nextLineOrEof() throws InterruptedException {
            String line = lines.poll(WAIT_MS, TimeUnit.MILLISECONDS);
            assertNotNull(line, "timed out waiting for the client");
            return line == EOF ? null : line;
        }

        /** Sends each line terminated with LF, all in one write so they arrive together. */
        void send(String... protocolLines) throws IOException {
            StringBuilder text = new StringBuilder();
            for (String line : protocolLines) {
                text.append(line).append('\n');
            }
            sendRaw(text.toString());
        }

        /** Sends text exactly as given (the caller adds terminators). */
        void sendRaw(String text) throws IOException {
            out.write(text.getBytes(UTF_8));
            out.flush();
        }

        /** Waits for the next line from the client. Fails on timeout or end of stream. */
        String nextLine() throws InterruptedException {
            String line = lines.poll(WAIT_MS, TimeUnit.MILLISECONDS);
            if (line == null) {
                fail("timed out waiting for a line from the client");
            }
            if (line == EOF) {
                fail("client closed the connection instead of sending a line");
            }
            return line;
        }

        void expectLine(String expected) throws InterruptedException {
            assertEquals(expected, nextLine());
        }

        /** Waits until the client closes its side of the connection, with no line before it. */
        void expectEof() throws InterruptedException {
            String line = lines.poll(WAIT_MS, TimeUnit.MILLISECONDS);
            assertNotNull(line, "timed out waiting for the client to close");
            assertSame(EOF, line, () -> "expected end of stream but got: " + line);
        }

        /**
         * The client was disconnected intentionally: the next line must be QUIT (so no other
         * command was sent before it), followed by end of stream.
         */
        void expectQuitThenEof() throws InterruptedException {
            expectLine("QUIT");
            expectEof();
        }

        /** Every line received from this client so far. */
        List<String> received() {
            return List.copyOf(received);
        }

        /** Server hangs up (the client sees "Server closed the connection"). */
        @Override
        public void close() throws IOException {
            try {
                socket.close();
            } catch (SocketException ignored) {
                // already closed
            }
        }
    }
}
