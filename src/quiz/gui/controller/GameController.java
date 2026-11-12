package quiz.gui.controller;

import java.awt.EventQueue;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import quiz.gui.model.AnswerOption;
import quiz.gui.model.GameState;
import quiz.gui.model.QuestionData;
import quiz.gui.net.ConnectionListener;
import quiz.gui.net.ServerConnection;
import quiz.gui.protocol.ClientCommands;
import quiz.gui.protocol.ServerMessage;

/**
 * Connects the network layer to the model: one {@link ServerConnection} per session,
 * one {@link GameState} for the lifetime of the controller.
 *
 * <pre>
 * server line --ServerConnection/ProtocolParser--&gt; ServerMessage --GameController--&gt; GameState --&gt; StateListener
 * UI intent   --GameController--&gt; ClientCommands --&gt; ServerConnection.send(...)
 * </pre>
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>Construction and every public method must run on the event dispatch thread;
 *       otherwise {@link IllegalStateException} is thrown.</li>
 *   <li>{@link ServerConnection} delivers its callbacks on the EDT, one at a time and
 *       in server order, so every {@link GameState} change and every
 *       {@link StateListener} notification happens on the EDT, in that same order.</li>
 *   <li>No method blocks: connecting, sending and closing all happen in the background.</li>
 * </ul>
 *
 * <h2>Sessions</h2>
 * {@link #connect} starts a new session with a <em>new</em> {@code ServerConnection}
 * (a connection is single-use). {@link #disconnect} ends it intentionally; after that no
 * command is sent and no callback from the old connection reaches the state.
 *
 * <p>This class does not depend on any Swing component; the UI observes it through
 * {@link StateListener} and reads {@link #getState()}.</p>
 */
public final class GameController {

    /** Notified on the EDT after every change of the game state. */
    @FunctionalInterface
    public interface StateListener {
        /**
         * @param state the controller's state; read it, but do not call its mutating methods
         */
        void stateChanged(GameState state);
    }

    private static final System.Logger LOG = System.getLogger(GameController.class.getName());

    private final GameState state = new GameState();
    private final List<StateListener> listeners = new CopyOnWriteArrayList<>();

    /** The connection of the current session, or null when there is none. */
    private ServerConnection connection;
    /** The listener bound to {@link #connection}; null when there is no session. */
    private SessionListener currentListener;
    /** The trimmed username to send with JOIN once the current connection is open. */
    private String pendingUsername;
    /** START was sent and the server has not answered it yet (GAME_STARTED or ERROR). */
    private boolean startRequested;
    /** Why the last session ended unexpectedly; null if it was intentional or still running. */
    private String disconnectReason;

    /** @throws IllegalStateException if not called on the event dispatch thread */
    public GameController() {
        requireEdt();
    }

    // ------------------------------------------------------------------
    // State access and listeners
    // ------------------------------------------------------------------

    /** The game state. The same instance is kept across sessions. Read-only for callers. */
    public GameState getState() {
        requireEdt();
        return state;
    }

    public void addStateListener(StateListener listener) {
        requireEdt();
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeStateListener(StateListener listener) {
        requireEdt();
        listeners.remove(listener);
    }

    /** True from {@link #connect} until the session ends (intentionally or not). */
    public boolean hasActiveConnection() {
        requireEdt();
        return connection != null;
    }

    /**
     * The reason the last session ended unexpectedly (connection failed or was lost),
     * suitable for showing to the user. Empty while a session is active, after an
     * intentional {@link #disconnect()}, or if no session has ended yet.
     *
     * <p>A server ERROR that came before the disconnect (for example a rejected JOIN)
     * is kept separately in {@link GameState#getLastError()}.</p>
     */
    public Optional<String> getDisconnectReason() {
        requireEdt();
        return Optional.ofNullable(disconnectReason);
    }

    // ------------------------------------------------------------------
    // User intents
    // ------------------------------------------------------------------

    /**
     * Validates the input and, if it is valid, starts a new session: a new connection is
     * opened in the background and JOIN is sent once it is connected.
     *
     * @return a friendly explanation of what is wrong (nothing is changed or started), or
     *         an empty Optional if the connection attempt has started
     */
    public Optional<String> connect(String host, int port, String username) {
        requireEdt();
        Optional<String> problem = connectionProblem(host, port, username);
        if (problem.isPresent()) {
            return problem;
        }
        if (connection != null) {
            return Optional.of("Already connected. Disconnect first.");
        }

        pendingUsername = username.trim();
        startRequested = false;
        disconnectReason = null;
        state.markConnecting();

        SessionListener sessionListener = new SessionListener();
        ServerConnection newConnection = new ServerConnection(host.trim(), port, sessionListener);
        connection = newConnection;
        currentListener = sessionListener;
        newConnection.start();
        notifyListeners();
        return Optional.empty();
    }

    /**
     * True if START may be sent now: the player has joined, is the host, enough players
     * are in the lobby, no game is running, and no START is already waiting for an answer.
     */
    public boolean canStartGame() {
        requireEdt();
        return connection != null && !startRequested && state.canStartGame();
    }

    /**
     * Sends START (also used for Play Again) if {@link #canStartGame()} allows it.
     * Further calls do nothing until the server answers with GAME_STARTED or ERROR.
     *
     * @return true if START was queued
     */
    public boolean startGame() {
        requireEdt();
        if (!canStartGame() || !connection.send(ClientCommands.start())) {
            return false;
        }
        startRequested = true;
        notifyListeners();
        return true;
    }

    /**
     * Sends the answer for the open round, at most once per round.
     *
     * @return true if ANSWER was queued; false if answering is not allowed now
     *         (no open question, already answered this round, not connected, or null option)
     */
    public boolean submitAnswer(AnswerOption option) {
        requireEdt();
        if (connection == null || option == null || !state.canSubmitAnswer()) {
            return false;
        }
        QuestionData question = state.getCurrentQuestion();
        if (!connection.send(ClientCommands.answer(question.round(), option))) {
            return false;
        }
        state.markAnswerSubmitted(option);
        notifyListeners();
        return true;
    }

    /**
     * Ends the current session intentionally. QUIT is sent by the connection if it is open;
     * no further command is sent and no further server event changes the state. A server
     * error recorded earlier (for example a rejected JOIN) is kept. Does nothing if there
     * is no active session.
     */
    public void disconnect() {
        requireEdt();
        if (connection == null) {
            return;
        }
        ServerConnection closing = connection;
        endSession(null);
        closing.close();
        notifyListeners();
    }

    // ------------------------------------------------------------------
    // Connection callbacks (on the EDT, in order)
    // ------------------------------------------------------------------

    /** One listener per connection, so callbacks from an old connection are recognised. */
    private final class SessionListener implements ConnectionListener {

        /** close() already suppresses late callbacks; this also guards against any that slip through. */
        private boolean isCurrent() {
            return currentListener == this;
        }

        @Override
        public void onConnected() {
            requireEdt();
            if (!isCurrent()) {
                return;
            }
            handleConnected();
        }

        @Override
        public void onMessage(ServerMessage message) {
            requireEdt();
            if (!isCurrent()) {
                return;
            }
            handleMessage(message);
        }

        @Override
        public void onDisconnected(String reason) {
            requireEdt();
            if (!isCurrent()) {
                return;
            }
            handleDisconnected(reason);
        }
    }

    private void handleConnected() {
        state.markJoining(pendingUsername);
        if (!connection.send(ClientCommands.join(pendingUsername))) {
            LOG.log(System.Logger.Level.WARNING, "JOIN could not be queued; waiting for disconnect");
        }
        notifyListeners();
    }

    private void handleMessage(ServerMessage message) {
        switch (message) {
            case ServerMessage.Joined m -> state.onJoined(m.username());
            case ServerMessage.Info m -> state.onInfo(m.text());
            case ServerMessage.PlayerList m -> state.onPlayers(m.usernames());
            case ServerMessage.HostChanged m -> state.onHost(m.username());
            case ServerMessage.ServerError m -> {
                // The server answers START with either GAME_STARTED or ERROR.
                startRequested = false;
                state.onError(m.text());
            }
            case ServerMessage.GameStarted m -> {
                startRequested = false;
                state.onGameStarted();
            }
            case ServerMessage.NewQuestion m -> state.onQuestion(m.question());
            case ServerMessage.AnswerAccepted m -> state.onAnswerAccepted(m.round());
            case ServerMessage.RoundResult m -> state.onRoundResult(m.round(), m.correct());
            case ServerMessage.Scores m -> state.onScores(m.leaderboard());
            case ServerMessage.GameOver m -> state.onGameOver(m.winners(), m.bestScore());
            case ServerMessage.Unknown m -> {
                LOG.log(System.Logger.Level.DEBUG, "Ignoring unknown server message: " + m.rawLine());
                return; // no state change, so no notification
            }
        }
        notifyListeners();
    }

    private void handleDisconnected(String reason) {
        endSession(reason);
        notifyListeners();
    }

    /** Forgets the current connection and marks the state disconnected. */
    private void endSession(String reason) {
        connection = null;
        currentListener = null;
        pendingUsername = null;
        startRequested = false;
        disconnectReason = reason;
        state.markDisconnected(reason);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Validation used by {@link #connect}; returns a friendly message or empty if valid. */
    static Optional<String> connectionProblem(String host, int port, String username) {
        if (host == null || host.isBlank()) {
            return Optional.of("Please enter the server address.");
        }
        if (port < 1 || port > 65535) {
            return Optional.of("Port must be between 1 and 65535.");
        }
        return ClientCommands.usernameProblem(username);
    }

    private void notifyListeners() {
        for (StateListener listener : listeners) {
            try {
                listener.stateChanged(state);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "State listener failed: " + listener, e);
            }
        }
    }

    private static void requireEdt() {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("GameController must be used on the event dispatch thread");
        }
    }
}
