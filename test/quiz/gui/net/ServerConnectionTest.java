package quiz.gui.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import quiz.gui.model.AnswerOption;
import quiz.gui.protocol.ClientCommands;
import quiz.gui.protocol.ServerMessage;

/**
 * Tests ServerConnection against an in-process loopback ServerSocket ("peer").
 *
 * <p>Most tests run callbacks directly on the reader thread so that, once
 * {@code awaitTermination} returns, every callback has already happened and
 * "no more events" can be asserted without sleeping.</p>
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ServerConnectionTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final String HOST = LOOPBACK.getHostAddress();
    private static final long WAIT_MS = 5000;
    private static final Executor DIRECT = Runnable::run;

    private ServerSocket server;
    private final List<ServerConnection> connections = new ArrayList<>();
    private final List<Peer> peers = new ArrayList<>();

    @BeforeAll
    static void useHeadlessAwt() {
        // Must happen before AWT initialises; no other test in this module uses AWT.
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
    }

    @BeforeEach
    void openServer() throws IOException {
        server = new ServerSocket(0, 50, LOOPBACK);
        server.setSoTimeout((int) WAIT_MS);
    }

    @AfterEach
    void cleanUp() throws IOException {
        for (Peer peer : peers) {
            peer.close();
        }
        for (ServerConnection connection : connections) {
            connection.close();
        }
        for (ServerConnection connection : connections) {
            connection.awaitTermination(WAIT_MS);
        }
        server.close();
    }

    // ------------------------------------------------------------------
    // Connecting and receiving
    // ------------------------------------------------------------------

    @Test
    void connectsAndReportsConnected() throws Exception {
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = newConnection(listener);

        startAndAccept(connection, listener);

        listener.assertNoMoreEvents();
    }

    @Test
    void sendsExactJoinLine() throws Exception {
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = newConnection(listener);
        Peer peer = startAndAccept(connection, listener);

        assertTrue(connection.send(ClientCommands.join("alice")));

        assertEquals("JOIN|alice\n", peer.readRawLine());
    }

    @Test
    void parsesUtf8AndCrlfLinesInOrder() throws Exception {
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = newConnection(listener);
        Peer peer = startAndAccept(connection, listener);

        peer.sendRaw("INFO|Café – 世界 ✓\r\nJOINED|alice\r\nPLAYERS|alice,bob\nHOST|alice\r\n");
        // A multi-byte character split across two TCP writes.
        byte[] split = "INFO|é\r\n".getBytes(UTF_8);
        peer.sendBytes(split, 0, 6);
        peer.sendBytes(split, 6, split.length - 6);

        assertEquals(new ServerMessage.Info("Café – 世界 ✓"), listener.expectMessage());
        assertEquals(new ServerMessage.Joined("alice"), listener.expectMessage());
        assertEquals(new ServerMessage.PlayerList(List.of("alice", "bob")), listener.expectMessage());
        assertEquals(new ServerMessage.HostChanged("alice"), listener.expectMessage());
        assertEquals(new ServerMessage.Info("é"), listener.expectMessage());
        listener.assertNoMoreEvents();
    }

    @Test
    void skipsMalformedLinesAndKeepsReading() throws Exception {
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = newConnection(listener);
        Peer peer = startAndAccept(connection, listener);

        peer.sendRaw("QUESTION|not-a-number\r\n\r\nJOINED|bad name\r\nINFO|still here\r\n");

        assertEquals(new ServerMessage.Info("still here"), listener.expectMessage());
        listener.assertNoMoreEvents();
    }

    @Test
    void deliversUnknownMessages() throws Exception {
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = newConnection(listener);
        Peer peer = startAndAccept(connection, listener);

        peer.sendRaw("SURPRISE|1|2\r\n");

        assertEquals(new ServerMessage.Unknown("SURPRISE|1|2"), listener.expectMessage());
    }

    // ------------------------------------------------------------------
    // Unexpected disconnects
    // ------------------------------------------------------------------

    @Test
    void reportsServerEofExactlyOnce() throws Exception {
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = newConnection(listener);
        Peer peer = startAndAccept(connection, listener);

        peer.close();

        assertEquals("Server closed the connection", listener.expectDisconnected());
        assertTrue(connection.awaitTermination(WAIT_MS));
        listener.assertNoMoreEvents();
        assertFalse(connection.send(ClientCommands.start()));
    }

    @Test
    void rejectedJoinDeliversErrorBeforeDisconnect() throws Exception {
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = newConnection(listener);
        Peer peer = startAndAccept(connection, listener);

        assertTrue(connection.send(ClientCommands.join("alice")));
        assertEquals("JOIN|alice", peer.readLine());
        // Same behaviour as QuizServer: send ERROR, then close.
        peer.sendRaw("ERROR|Username already taken\r\n");
        peer.close();

        assertEquals(new ServerMessage.ServerError("Username already taken"), listener.expectMessage());
        listener.expectDisconnected();
        assertTrue(connection.awaitTermination(WAIT_MS));
        listener.assertNoMoreEvents();
    }

    @Test
    void reportsConnectionRefused() throws Exception {
        int closedPort;
        try (ServerSocket temporary = new ServerSocket(0, 1, LOOPBACK)) {
            closedPort = temporary.getLocalPort();
        }
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = new ServerConnection(HOST, closedPort, listener, DIRECT,
                ServerConnection.CONNECT_TIMEOUT_MS, Socket::new);
        connections.add(connection);

        connection.start();

        // Allow longer than the connect timeout: Windows retries refused loopback connects.
        Event event = listener.next(ServerConnection.CONNECT_TIMEOUT_MS + WAIT_MS);
        assertEquals("disconnected", event.kind(), () -> "unexpected " + event);
        assertTrue(event.reason().startsWith("Could not connect to " + HOST + ":" + closedPort),
                event.reason());
        assertTrue(connection.awaitTermination(WAIT_MS));
        listener.assertNoMoreEvents();
    }

    @Test
    void asyncWriteFailureReportsDisconnectExactlyOnce() throws Exception {
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = newConnection(listener, DIRECT, FailingWriteSocket::new);
        startAndAccept(connection, listener);

        assertTrue(connection.send(ClientCommands.start()), "queued; the write fails later");
        for (int i = 0; i < 20; i++) {
            connection.send(ClientCommands.start()); // true or false, but never throws
        }

        String reason = listener.expectDisconnected();
        assertTrue(reason.contains("simulated write failure"), reason);
        assertTrue(connection.awaitTermination(WAIT_MS));
        listener.assertNoMoreEvents();
        assertFalse(connection.send(ClientCommands.start()));
    }

    // ------------------------------------------------------------------
    // Intentional close
    // ------------------------------------------------------------------

    @Test
    void closeSendsQuitAndDoesNotReportDisconnect() throws Exception {
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = newConnection(listener);
        Peer peer = startAndAccept(connection, listener);

        connection.close();

        assertEquals("QUIT", peer.readLine());
        assertNull(peer.readRawLine(), "client should half-close after QUIT");
        peer.close();
        assertTrue(connection.awaitTermination(WAIT_MS));
        listener.assertNoMoreEvents();
        assertFalse(connection.send(ClientCommands.start()));
    }

    @Test
    void noCallbacksAfterCloseEvenIfAlreadyQueued() throws Exception {
        RecordingListener listener = new RecordingListener();
        ManualExecutor executor = new ManualExecutor();
        ServerConnection connection = newConnection(listener, executor, Socket::new);
        connection.start();
        Peer peer = accept();
        executor.runNext();
        listener.expectConnected();

        peer.sendRaw("INFO|queued before close\r\n");
        Runnable queued = executor.takeNext(); // posted, but not yet run
        connection.close();
        queued.run();

        assertEquals("QUIT", peer.readLine());
        peer.sendRaw("INFO|sent after close\r\n");
        assertNull(peer.readRawLine());
        peer.close();
        assertTrue(connection.awaitTermination(WAIT_MS));
        assertTrue(executor.tasks.isEmpty(), "nothing should be posted after close()");
        listener.assertNoMoreEvents();
    }

    @Test
    void closeTwiceWhileConnectedSendsQuitOnce() throws Exception {
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = newConnection(listener);
        Peer peer = startAndAccept(connection, listener);

        connection.close();
        connection.close();

        assertEquals("QUIT", peer.readLine());
        assertNull(peer.readRawLine());
        peer.close();
        assertTrue(connection.awaitTermination(WAIT_MS));
        listener.assertNoMoreEvents();
    }

    @Test
    void closeBeforeStartIsSafeAndPreventsStart() {
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = newConnection(listener);

        connection.close();
        connection.close();

        assertFalse(connection.send(ClientCommands.start()));
        assertThrows(IllegalStateException.class, connection::start);
        assertTrue(connection.awaitTermination(0));
        listener.assertNoMoreEvents();
    }

    @Test
    void closeDuringConnectAbortsTheAttempt() throws Exception {
        RecordingListener listener = new RecordingListener();
        StallingConnectSocket socket = new StallingConnectSocket();
        ServerConnection connection = newConnection(listener, DIRECT, () -> socket);
        connection.start();
        assertTrue(socket.connectEntered.await(WAIT_MS, TimeUnit.MILLISECONDS));

        assertFalse(connection.send(ClientCommands.join("alice")), "not connected yet");
        connection.close();

        assertTrue(connection.awaitTermination(WAIT_MS));
        assertTrue(socket.isClosed());
        listener.assertNoMoreEvents();
        assertThrows(IllegalStateException.class, connection::start);
    }

    // ------------------------------------------------------------------
    // send() and start() contracts
    // ------------------------------------------------------------------

    @Test
    void sendReturnsFalseBeforeConnectionAndAfterClose() throws Exception {
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = newConnection(listener);

        assertFalse(connection.send(ClientCommands.start()), "before start()");
        Peer peer = startAndAccept(connection, listener);
        assertTrue(connection.send(ClientCommands.start()), "while connected");
        assertEquals("START", peer.readLine());
        connection.close();
        assertFalse(connection.send(ClientCommands.start()), "after close()");
    }

    @ParameterizedTest
    @ValueSource(strings = {"START\n", "START\r", "JOIN|a\r\nSTART", "\n", "\r\n"})
    void rejectsLinesContainingLineBreaks(String line) {
        ServerConnection connection = newConnection(new RecordingListener());

        assertThrows(IllegalArgumentException.class, () -> connection.send(line));
    }

    @Test
    void rejectsNullLine() {
        ServerConnection connection = newConnection(new RecordingListener());

        assertThrows(NullPointerException.class, () -> connection.send(null));
    }

    @Test
    void startTwiceThrows() throws Exception {
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = newConnection(listener);
        startAndAccept(connection, listener);

        assertThrows(IllegalStateException.class, connection::start);
    }

    @Test
    void constructorValidatesArguments() {
        RecordingListener l = new RecordingListener();
        Supplier<Socket> sockets = Socket::new;
        int timeout = ServerConnection.CONNECT_TIMEOUT_MS;

        assertThrows(NullPointerException.class, () -> new ServerConnection(null, 5000, l));
        assertThrows(IllegalArgumentException.class, () -> new ServerConnection("  ", 5000, l));
        assertThrows(IllegalArgumentException.class, () -> new ServerConnection(HOST, 0, l));
        assertThrows(IllegalArgumentException.class, () -> new ServerConnection(HOST, -1, l));
        assertThrows(IllegalArgumentException.class, () -> new ServerConnection(HOST, 65536, l));
        assertThrows(NullPointerException.class, () -> new ServerConnection(HOST, 5000, null));
        assertThrows(NullPointerException.class, () -> new ServerConnection((ConnectionListener) null));
        assertThrows(NullPointerException.class,
                () -> new ServerConnection(HOST, 5000, l, null, timeout, sockets));
        assertThrows(NullPointerException.class,
                () -> new ServerConnection(HOST, 5000, l, DIRECT, timeout, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerConnection(HOST, 5000, l, DIRECT, 0, sockets));
    }

    @Test
    void defaultsMatchTheServer() {
        ServerConnection connection = new ServerConnection(new RecordingListener());

        assertEquals("127.0.0.1", connection.getHost());
        assertEquals(5000, connection.getPort());
        assertEquals(5000, ServerConnection.CONNECT_TIMEOUT_MS);
        assertEquals("localhost", new ServerConnection(" localhost ", 1, new RecordingListener()).getHost());
    }

    // ------------------------------------------------------------------
    // Writer thread ordering
    // ------------------------------------------------------------------

    @Test
    void writerPreservesCommandOrder() throws Exception {
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = newConnection(listener);
        Peer peer = startAndAccept(connection, listener);

        List<String> expected = new ArrayList<>();
        AnswerOption[] options = AnswerOption.values();
        for (int round = 1; round <= 500; round++) {
            String command = ClientCommands.answer(round, options[round % options.length]);
            expected.add(command);
            assertTrue(connection.send(command));
        }

        for (String command : expected) {
            assertEquals(command, peer.readLine());
        }
    }

    @Test
    void concurrentSendersNeverInterleaveLines() throws Exception {
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = newConnection(listener);
        Peer peer = startAndAccept(connection, listener);

        int senders = 4;
        int perSender = 100;
        CountDownLatch go = new CountDownLatch(1);
        Queue<String> failures = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int s = 1; s <= senders; s++) {
            int sender = s;
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                    for (int i = 1; i <= perSender; i++) {
                        if (!connection.send(ClientCommands.answer(sender * 1000 + i, AnswerOption.A))) {
                            failures.add("send returned false for sender " + sender);
                        }
                    }
                } catch (InterruptedException e) {
                    failures.add("interrupted");
                }
            });
            threads.add(thread);
            thread.start();
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join(WAIT_MS);
        }

        Pattern answer = Pattern.compile("ANSWER\\|(\\d+)\\|A");
        Map<Integer, Integer> lastPerSender = new HashMap<>();
        for (int n = 0; n < senders * perSender; n++) {
            String line = peer.readLine();
            Matcher matcher = answer.matcher(line);
            assertTrue(matcher.matches(), "corrupted line: " + line);
            int round = Integer.parseInt(matcher.group(1));
            int sender = round / 1000;
            int index = round % 1000;
            assertEquals(lastPerSender.getOrDefault(sender, 0) + 1, index, "order for sender " + sender);
            lastPerSender.put(sender, index);
        }
        assertEquals(List.of(), List.copyOf(failures));
    }

    // ------------------------------------------------------------------
    // Swing EDT delivery (public constructor)
    // ------------------------------------------------------------------

    @Test
    void publicConstructorDeliversCallbacksOnEdtInHeadlessMode() throws Exception {
        assertTrue(GraphicsEnvironment.isHeadless(), "test is meant to run headless");
        RecordingListener listener = new RecordingListener();
        ServerConnection connection = new ServerConnection(HOST, server.getLocalPort(), listener);
        connections.add(connection);

        connection.start();
        Peer peer = accept();
        Event connected = listener.next();
        assertEquals("connected", connected.kind());
        assertTrue(connected.onEdt(), "onConnected on EDT");

        peer.sendRaw("INFO|hello\r\n");
        Event message = listener.next();
        assertEquals(new ServerMessage.Info("hello"), message.message());
        assertTrue(message.onEdt(), "onMessage on EDT");

        peer.close();
        Event disconnected = listener.next();
        assertEquals("disconnected", disconnected.kind());
        assertTrue(disconnected.onEdt(), "onDisconnected on EDT");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ServerConnection newConnection(ConnectionListener listener) {
        return newConnection(listener, DIRECT, Socket::new);
    }

    private ServerConnection newConnection(ConnectionListener listener, Executor executor,
                                           Supplier<Socket> sockets) {
        ServerConnection connection = new ServerConnection(HOST, server.getLocalPort(), listener,
                executor, ServerConnection.CONNECT_TIMEOUT_MS, sockets);
        connections.add(connection);
        return connection;
    }

    private Peer accept() throws IOException {
        Socket socket = server.accept();
        socket.setSoTimeout((int) WAIT_MS);
        Peer peer = new Peer(socket);
        peers.add(peer);
        return peer;
    }

    /** Starts the connection, accepts it on the peer side and consumes onConnected. */
    private Peer startAndAccept(ServerConnection connection, RecordingListener listener) throws Exception {
        connection.start();
        Peer peer = accept();
        listener.expectConnected();
        return peer;
    }

    record Event(String kind, ServerMessage message, String reason, boolean onEdt) {
    }

    static final class RecordingListener implements ConnectionListener {
        final BlockingQueue<Event> events = new LinkedBlockingQueue<>();

        @Override
        public void onConnected() {
            events.add(new Event("connected", null, null, SwingUtilities.isEventDispatchThread()));
        }

        @Override
        public void onMessage(ServerMessage message) {
            events.add(new Event("message", message, null, SwingUtilities.isEventDispatchThread()));
        }

        @Override
        public void onDisconnected(String reason) {
            events.add(new Event("disconnected", null, reason, SwingUtilities.isEventDispatchThread()));
        }

        Event next() throws InterruptedException {
            return next(WAIT_MS);
        }

        Event next(long timeoutMs) throws InterruptedException {
            Event event = events.poll(timeoutMs, TimeUnit.MILLISECONDS);
            assertNotNull(event, "timed out waiting for a callback");
            return event;
        }

        void expectConnected() throws InterruptedException {
            Event event = next();
            assertEquals("connected", event.kind(), () -> "unexpected " + event);
        }

        ServerMessage expectMessage() throws InterruptedException {
            Event event = next();
            assertEquals("message", event.kind(), () -> "unexpected " + event);
            return event.message();
        }

        String expectDisconnected() throws InterruptedException {
            Event event = next();
            assertEquals("disconnected", event.kind(), () -> "unexpected " + event);
            return event.reason();
        }

        void assertNoMoreEvents() {
            assertEquals(List.of(), List.copyOf(events));
        }
    }

    /** Collects posted callbacks so the test decides when (or whether) they run. */
    static final class ManualExecutor implements Executor {
        final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        Runnable takeNext() throws InterruptedException {
            Runnable task = tasks.poll(WAIT_MS, TimeUnit.MILLISECONDS);
            assertNotNull(task, "timed out waiting for a posted callback");
            return task;
        }

        void runNext() throws InterruptedException {
            takeNext().run();
        }
    }

    /** The server side of one accepted connection. */
    static final class Peer implements Closeable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Peer(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        /** The next line including its terminator, or null at end of stream. */
        String readRawLine() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                buffer.write(b);
                if (b == '\n') {
                    break;
                }
            }
            return buffer.size() == 0 ? null : buffer.toString(UTF_8);
        }

        /** The next line, which must end with exactly "\n"; null at end of stream. */
        String readLine() throws IOException {
            String raw = readRawLine();
            if (raw == null) {
                return null;
            }
            assertTrue(raw.endsWith("\n") && raw.indexOf('\r') < 0, () -> "bad terminator: " + raw);
            return raw.substring(0, raw.length() - 1);
        }

        void sendRaw(String text) throws IOException {
            byte[] bytes = text.getBytes(UTF_8);
            sendBytes(bytes, 0, bytes.length);
        }

        void sendBytes(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            out.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** A real socket whose writes always fail, to simulate an asynchronous write error. */
    static final class FailingWriteSocket extends Socket {
        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("simulated write failure");
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    throw new IOException("simulated write failure");
                }
            };
        }
    }

    /** A socket whose connect() waits until close() is called, then fails like a real one. */
    static final class StallingConnectSocket extends Socket {
        final CountDownLatch connectEntered = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            connectEntered.countDown();
            try {
                if (!closed.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
                    throw new IOException("test: close() was never called");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
            super.connect(endpoint, timeout); // the socket is closed, so this throws
        }

        @Override
        public void close() throws IOException {
            super.close();
            closed.countDown();
        }
    }
}
