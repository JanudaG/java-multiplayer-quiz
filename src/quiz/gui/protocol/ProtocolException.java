package quiz.gui.protocol;

/**
 * Thrown when a line from the server has a known message type
 * but its fields are malformed (wrong count, bad number, bad name, ...).
 *
 * <p>It is a checked exception so the caller must decide what to do.
 * The recommended handling is: log it, skip the line, keep reading.</p>
 */
public class ProtocolException extends Exception {

    private final String reason;
    private final String rawLine;

    public ProtocolException(String reason, String rawLine) {
        super(reason + " in: " + rawLine);
        this.reason = reason;
        this.rawLine = rawLine;
    }

    /** Same as {@link #ProtocolException(String, String)} but keeps the original cause. */
    public ProtocolException(String reason, String rawLine, Throwable cause) {
        super(reason + " in: " + rawLine, cause);
        this.reason = reason;
        this.rawLine = rawLine;
    }

    /** The offending line exactly as it was given to the parser (useful for logging). */
    public String getRawLine() {
        return rawLine;
    }

    /** A short explanation of what was wrong. */
    public String getReason() {
        return reason;
    }
}
