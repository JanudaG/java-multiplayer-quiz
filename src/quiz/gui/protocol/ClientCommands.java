package quiz.gui.protocol;

import java.util.Optional;

import quiz.gui.model.AnswerOption;

/**
 * Builds the lines the client sends to the server.
 *
 * <p>The returned strings never contain a newline; the network layer adds it.
 * Invalid input throws {@link IllegalArgumentException}, so an invalid
 * protocol line can never be produced. The UI should call
 * {@link #usernameProblem(String)} first to show a friendly message.</p>
 */
public final class ClientCommands {

    /** The username rule used by the server (QuizServer, JOIN handling). */
    public static final String USERNAME_REGEX = "[A-Za-z0-9_-]{1,16}";

    /** The maximum username length allowed by the server. */
    public static final int MAX_USERNAME_LENGTH = 16;

    private ClientCommands() {
        // utility class
    }

    /** True if the username (after trimming, like the server does) is valid. */
    public static boolean isValidUsername(String username) {
        return username != null && username.trim().matches(USERNAME_REGEX);
    }

    /**
     * Returns a friendly explanation of what is wrong with the username,
     * or an empty Optional if it is valid.
     */
    public static Optional<String> usernameProblem(String username) {
        if (username == null || username.isBlank()) {
            return Optional.of("Please enter a username.");
        }
        String trimmed = username.trim();
        if (trimmed.length() > MAX_USERNAME_LENGTH) {
            return Optional.of("Username must be at most " + MAX_USERNAME_LENGTH + " characters.");
        }
        if (!trimmed.matches(USERNAME_REGEX)) {
            return Optional.of("Use only letters, digits, _ or - in the username.");
        }
        return Optional.empty();
    }

    /**
     * JOIN|username (the username is trimmed).
     *
     * @throws IllegalArgumentException if the username is not valid
     */
    public static String join(String username) {
        if (!isValidUsername(username)) {
            throw new IllegalArgumentException("Invalid username: " + username);
        }
        return "JOIN|" + username.trim();
    }

    /** START */
    public static String start() {
        return "START";
    }

    /**
     * ANSWER|round|letter, for example "ANSWER|3|B".
     *
     * @throws IllegalArgumentException if round is less than 1 or option is null
     */
    public static String answer(int round, AnswerOption option) {
        if (round < 1) {
            throw new IllegalArgumentException("round must be at least 1 but was " + round);
        }
        if (option == null) {
            throw new IllegalArgumentException("option must not be null");
        }
        return "ANSWER|" + round + "|" + option.letter();
    }

    /** QUIT */
    public static String quit() {
        return "QUIT";
    }
}
