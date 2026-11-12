package quiz.gui.net;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

import quiz.gui.protocol.ClientCommands;
import quiz.gui.protocol.ProtocolException;
import quiz.gui.protocol.ProtocolParser;
import quiz.gui.protocol.ServerMessage;

/**
 * One TCP connection to the quiz server, using UTF-8 text lines.
 *
 * <h2>Lifecycle (one use only)</h2>
 * <pre>NEW --start()--&gt; CONNECTING --&gt; CONNECTED --&gt; CLOSED</pre>
 * <ul>
 *   <li>{@link #start()} returns immediately; connecting happens in the background.
 *       Calling it a second time, or after {@link #close()}, throws
 *       {@link IllegalStateException}. To reconnect, create a new instance.</li>
 *   <li>{@link #close()} may be called at any time, any number of times. It never
 *       blocks and never causes {@link ConnectionListener#onDisconnected(String)}.</li>
 *   <li>A failed or lost connection moves to CLOSED and reports
 *       {@code onDisconnected} exactly once.</li>
 * </ul>
 *
 * <h2>Threads</h2>
 * <ul>
 *   <li><b>Reader thread</b>: connects, reads and parses lines, posts callbacks, and is
 *       the only thread that reports a disconnect.</li>
 *   <li><b>Writer thread</b> (started once connected): takes lines from a queue and
 *       writes them in order, one whole line at a time.</li>
 *   <li><b>Closer thread</b> (started by {@code close()}): waits briefly for QUIT to be
 *       sent and the server to hang up, then force-closes the socket.</li>
 * </ul>
 * No method of this class performs network I/O or waits on the calling thread.
 *
 * <p>There is deliberately no read timeout: the server sends no heartbeat, so a quiet
 * lobby is normal.</p>
 */
public final class ServerConnection {

    /** Default server address, matching QuizClient. */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /** Default server port, matching QuizServer. */
    public static final int DEFAULT_PORT = 5000;

    /** How long a connection attempt may take before it fails. */
    public static final int CONNECT_TIMEOUT_MS = 5000;

    /** How long close() lets QUIT be sent and the server hang up before force-closing. */
    static final long CLOSE_GRACE_MS = 1000;

    private static final System.Logger LOG = System.getLogger(ServerConnection.class.getName());

    /** Queue marker telling the writer thread to stop. Compared by identity. */
    private static final String STOP_WRITER = new String("<stop-writer>");

    private enum State { NEW, CONNECTING, CONNECTED, CLOSED }

    private final String host;
    private final int port;
    private final ConnectionListener listener;
    private final Executor callbackExecutor;
    private final int connectTimeoutMs;
    private final Supplier<Socket> socketFactory;

    private final BlockingQueue<String> outgoing = new LinkedBlockingQueue<>();
    private final Object lock = new Object();

    // Guarded by lock.
    private State state = State.NEW;
    private Socket socket;
    private Thread readerThread;
    private Thread writerThread;
    private Thread closerThread;
    private String writeFailure;

    /** Set by close(); suppresses every callback not yet run, including queued ones. */
    private volatile boolean closeRequested;

    /** Connects to {@value #DEFAULT_HOST}:{@value #DEFAULT_PORT}; callbacks run on the Swing EDT. */
    public ServerConnection(ConnectionListener listener) {
        this(DEFAULT_HOST, DEFAULT_PORT, listener);
    }

    /**
     * Callbacks run on the Swing event dispatch thread (this also works in headless mode).
     *
     * @throws IllegalArgumentException if host is blank or port is not 1-65535
     * @throws NullPointerException if host or listener is null
     */
    public ServerConnection(String host, int port, ConnectionListener listener) {
        this(host, port, listener, SwingUtilities::invokeLater, CONNECT_TIMEOUT_MS, Socket::new);
    }

    /**
     * For tests: choose where callbacks run, the connect timeout, and how sockets are created.
     * The executor must run tasks one at a time, in submission order.
     */
    ServerConnection(String host, int port, ConnectionListener listener, Executor callbackExecutor,
                     int connectTimeoutMs, Supplier<Socket> socketFactory) {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be 1-65535 but was " + port);
        }
        if (connectTimeoutMs < 1) {
            throw new IllegalArgumentException("connectTimeoutMs must be positive but was " + connectTimeoutMs);
        }
        this.host = host.trim();
        this.port = port;
        this.listener = Objects.requireNonNull(listener, "listener");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.connectTimeoutMs = connectTimeoutMs;
        this.socketFactory = Objects.requireNonNull(socketFactory, "socketFactory");
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /**
     * Starts connecting in the background and returns immediately. The result is
     * reported through {@code onConnected()} or {@code onDisconnected(reason)}.
     *
     * @throws IllegalStateException if already started or closed
     */
    public void start() {
        synchronized (lock) {
            if (state != State.NEW) {
                throw new IllegalStateException(state == State.CLOSED
                        ? "Connection is closed and cannot be restarted"
                        : "Connection has already been started");
            }
            state = State.CONNECTING;
            readerThread = newDaemon(this::runReader, "quiz-net-reader");
            readerThread.start();
        }
    }

    /**
     * Queues one protocol line (without line terminator) for the writer thread.
     * Never blocks and never touches the socket.
     *
     * @return {@code true} if the connection is CONNECTED and the line was queued; queued
     *         lines are written in call order. This does <b>not</b> mean the server received
     *         it: if the connection fails first, that is reported via {@code onDisconnected}.
     *         {@code false} if not connected yet or already closed; nothing was queued.
     * @throws NullPointerException if line is null
     * @throws IllegalArgumentException if line contains CR or LF
     */
    public boolean send(String line) {
        Objects.requireNonNull(line, "line");
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("line must not contain CR or LF");
        }
        synchronized (lock) {
            if (state != State.CONNECTED) {
                return false;
            }
            outgoing.add(line);
            return true;
        }
    }

    /**
     * Closes the connection intentionally. Never blocks. If connected, QUIT is queued and
     * given up to {@link #CLOSE_GRACE_MS} to be sent before the socket is closed. A
     * connection attempt in progress is aborted. No further callbacks are made, and
     * {@code onDisconnected} is not called. Safe to call repeatedly and before start().
     */
    public void close() {
        synchronized (lock) {
            closeRequested = true;
            State previous = state;
            if (previous == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            if (previous == State.NEW) {
                return;
            }
            boolean graceful = previous == State.CONNECTED;
            if (graceful) {
                outgoing.add(ClientCommands.quit());
                outgoing.add(STOP_WRITER);
            }
            closerThread = newDaemon(() -> finishClose(graceful), "quiz-net-closer");
            closerThread.start();
        }
    }

    /**
     * For tests: waits until the reader, writer and closer threads have finished.
     *
     * @return true if all threads finished within the timeout
     */
    boolean awaitTermination(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Thread reader;
        synchronized (lock) {
            reader = readerThread;
        }
        if (!join(reader, deadline)) {
            return false;
        }
        Thread writer;
        Thread closer;
        synchronized (lock) {
            writer = writerThread;
            closer = closerThread;
        }
        return join(writer, deadline) && join(closer, deadline);
    }

    // ------------------------------------------------------------------
    // Reader thread
    // ------------------------------------------------------------------

    private void runReader() {
        String reason = null;
        try {
            reason = connectAndRead();
        } finally {
            closeSocketQuietly();
            outgoing.add(STOP_WRITER);
            reportDisconnect(reason != null ? reason : "Connection closed unexpectedly");
        }
    }

    /** Returns the disconnect reason, or null if close() was called while connecting. */
    private String connectAndRead() {
        Socket s;
        synchronized (lock) {
            if (state != State.CONNECTING) {
                return null;
            }
            s = socketFactory.get();
            socket = s;
        }

        BufferedReader in;
        BufferedWriter out;
        try {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "Could not connect to " + host + ":" + port + " (" + describe(e) + ")";
        }

        synchronized (lock) {
            if (state != State.CONNECTING) {
                return null; // close() won the race; the finally block closes the socket
            }
            state = State.CONNECTED;
            writerThread = newDaemon(() -> runWriter(out), "quiz-net-writer");
            writerThread.start();
        }
        deliver(listener::onConnected);

        try {
            String line;
            // readLine() accepts both LF and CRLF terminators.
            while ((line = in.readLine()) != null) {
                handleLine(line);
            }
            return "Server closed the connection";
        } catch (IOException e) {
            return "Connection lost (" + describe(e) + ")";
        }
    }

    private void handleLine(String line) {
        ServerMessage message;
        try {
            message = ProtocolParser.parse(line);
        } catch (ProtocolException e) {
            LOG.log(System.Logger.Level.WARNING, "Skipping malformed line from server: " + e.getMessage());
            return;
        }
        deliver(() -> listener.onMessage(message));
    }

    /** Reports an unexpected disconnect once; does nothing after close(). */
    private void reportDisconnect(String reason) {
        String finalReason;
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            finalReason = writeFailure != null ? writeFailure : reason;
        }
        deliver(() -> listener.onDisconnected(finalReason));
    }

    // ------------------------------------------------------------------
    // Writer thread
    // ------------------------------------------------------------------

    private void runWriter(BufferedWriter out) {
        try {
            while (true) {
                String line = outgoing.take();
                if (line == STOP_WRITER) {
                    // After QUIT: half-close so the server sees end-of-stream while
                    // the reader keeps draining until the server hangs up.
                    halfCloseQuietly();
                    return;
                }
                out.write(line);
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            synchronized (lock) {
                if (!closeRequested && writeFailure == null) {
                    writeFailure = "Connection lost while sending (" + describe(e) + ")";
                }
            }
            // Wakes the reader, which then reports the disconnect exactly once.
            closeSocketQuietly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Closer thread and helpers
    // ------------------------------------------------------------------

    private void finishClose(boolean graceful) {
        if (graceful) {
            Thread reader;
            synchronized (lock) {
                reader = readerThread;
            }
            join(reader, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_GRACE_MS));
        }
        closeSocketQuietly();
    }

    /** Posts a callback unless close() has been (or is by then) requested. */
    private void deliver(Runnable callback) {
        if (closeRequested) {
            return;
        }
        callbackExecutor.execute(() -> {
            if (!closeRequested) {
                callback.run();
            }
        });
    }

    private void halfCloseQuietly() {
        Socket s;
        synchronized (lock) {
            s = socket;
        }
        try {
            if (s != null && !s.isClosed()) {
                s.shutdownOutput();
            }
        } catch (IOException ignored) {
            // The closer thread or reader closes the socket anyway.
        }
    }

    private void closeSocketQuietly() {
        Socket s;
        synchronized (lock) {
            s = socket;
        }
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing more to do
            }
        }
    }

    /** Joins until the nanoTime deadline; returns true if the thread is null or finished. */
    private static boolean join(Thread thread, long deadlineNanos) {
        if (thread == null) {
            return true;
        }
        try {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs > 0) {
                thread.join(remainingMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    private static Thread newDaemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
