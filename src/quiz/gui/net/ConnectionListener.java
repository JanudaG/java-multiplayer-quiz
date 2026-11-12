package quiz.gui.net;

import quiz.gui.protocol.ServerMessage;

/**
 * Receives connection events from a {@link ServerConnection}.
 *
 * <p>Guarantees given by {@link ServerConnection}:</p>
 * <ul>
 *   <li>Callbacks run one at a time, in the order the events happened. With the
 *       public {@code ServerConnection} constructors they run on the Swing event
 *       dispatch thread, so implementations may update the UI directly.</li>
 *   <li>{@link #onConnected()} is called at most once, before any
 *       {@link #onMessage(ServerMessage)}.</li>
 *   <li>{@link #onDisconnected(String)} is called at most once and is always the
 *       last callback. It is also used when the connection attempt fails, in which
 *       case {@code onConnected()} is never called.</li>
 *   <li>After {@link ServerConnection#close()} has been called, no further
 *       callbacks are made, including ones that were already queued.</li>
 * </ul>
 */
public interface ConnectionListener {

    /** The TCP connection is open. The client should now send JOIN. */
    void onConnected();

    /**
     * One successfully parsed line from the server. Malformed lines are logged and
     * skipped, never delivered. {@link ServerMessage.Unknown} lines are delivered.
     */
    void onMessage(ServerMessage message);

    /**
     * The connection failed or was lost without {@link ServerConnection#close()}
     * having been called (connect failure, server closed the connection, I/O error).
     *
     * @param reason a short human-readable explanation, suitable for showing to the user
     */
    void onDisconnected(String reason);
}
