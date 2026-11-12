package quiz.gui.ui;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import quiz.gui.controller.GameController;

/**
 * Helpers for the screen tests: running code on the EDT, waiting (bounded, event-driven)
 * for controller state, a scripted loopback server, and off-screen painting.
 * Nothing leaves the machine.
 */
final class UiTestSupport {

    static final long WAIT_MS = 5000;

    private UiTestSupport() {
    }

    static void useHeadlessAwt() {
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
    }

    /** Runs the task on the EDT, waits for it, and rethrows anything it threw. */
    static <T> T onEdt(Callable<T> task) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(task.call());
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        Throwable t = failure.get();
        if (t instanceof Exception e) {
            throw e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return result.get();
    }

    @FunctionalInterface
    interface EdtAction {
        void run() throws Exception;
    }

    static void runOnEdt(EdtAction action) throws Exception {
        onEdt(() -> {
            action.run();
            return null;
        });
    }

    /** Lays out the whole component tree (twice, so wrapped text gets its final height). */
    static void layOut(Component root, int width, int height) {
        root.setSize(width, height);
        for (int pass = 0; pass < 2; pass++) {
            layOutTree(root);
        }
    }

    private static void layOutTree(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) {
                layOutTree(child);
            }
        }
    }

    /** Paints the component (already laid out) into an image. */
    static BufferedImage paint(Component root) {
        BufferedImage image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            root.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Wakes up waiting tests whenever the controller's state changes. */
    static final class StateWaiter {

        private final BlockingQueue<Object> wakeups = new LinkedBlockingQueue<>();
        private final GameController.StateListener listener = state -> wakeups.add(Boolean.TRUE);
        private final GameController controller;

        /** Must be called on the EDT. */
        StateWaiter(GameController controller) {
            this.controller = controller;
            controller.addStateListener(listener);
        }

        /** Must be called on the EDT. */
        void detach() {
            controller.removeStateListener(listener);
        }

        /** Waits until the condition (evaluated on the EDT) holds. */
        void await(String what, Callable<Boolean> condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
            while (true) {
                wakeups.clear();
                if (Boolean.TRUE.equals(onEdt(condition))) {
                    return;
                }
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0 || wakeups.poll(remaining, TimeUnit.MILLISECONDS) == null) {
                    fail("timed out waiting for " + what);
                }
            }
        }
    }

    /** A scripted quiz server on the loopback interface. It implements no game rules. */
    static final class LoopbackServer implements AutoCloseable {

        private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

        private final ServerSocket serverSocket;
        private final BlockingQueue<Client> accepted = new LinkedBlockingQueue<>();
        private final List<Client> allClients = new CopyOnWriteArrayList<>();

        LoopbackServer() throws IOException {
            serverSocket = new ServerSocket(0, 50, LOOPBACK);
            Thread acceptor = new Thread(this::acceptLoop, "ui-test-server-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String host() {
            return LOOPBACK.getHostAddress();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        Client accept() throws InterruptedException {
            Client client = accepted.poll(WAIT_MS, TimeUnit.MILLISECONDS);
            assertNotNull(client, "timed out waiting for a client to connect");
            return client;
        }

        /** Number of connections accepted so far. */
        int acceptedCount() {
            return allClients.size();
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
                    Client client = new Client(serverSocket.accept());
                    allClients.add(client);
                    accepted.add(client);
                }
            } catch (IOException e) {
                // server socket closed
            }
        }

        /** A loopback port with nothing listening on it (for "connection refused"). */
        static int unusedPort() throws IOException {
            try (ServerSocket probe = new ServerSocket(0, 1, LOOPBACK)) {
                return probe.getLocalPort();
            }
        }
    }

    /** The server side of one client connection. */
    static final class Client implements AutoCloseable {

        /** Marks end of stream. Compared by identity. */
        private static final String EOF = new String("<eof>");

        private final Socket socket;
        private final OutputStream out;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        private Client(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        lines.add(line);
                    }
                } catch (IOException e) {
                    // closed
                } finally {
                    lines.add(EOF);
                }
            }, "ui-test-server-reader");
            reader.setDaemon(true);
            reader.start();
        }

        /** Sends the lines in one write. */
        void send(String... protocolLines) throws IOException {
            StringBuilder text = new StringBuilder();
            for (String line : protocolLines) {
                text.append(line).append('\n');
            }
            out.write(text.toString().getBytes(UTF_8));
            out.flush();
        }

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

        /** Server hangs up. */
        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
