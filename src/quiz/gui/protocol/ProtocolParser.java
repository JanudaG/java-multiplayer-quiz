package quiz.gui.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import quiz.gui.model.AnswerOption;
import quiz.gui.model.Leaderboard;
import quiz.gui.model.PlayerScore;
import quiz.gui.model.QuestionData;

/**
 * Converts one line received from the server into a typed {@link ServerMessage}.
 *
 * <ul>
 *   <li>A malformed line of a known type throws {@link ProtocolException}.</li>
 *   <li>A line with an unknown type returns {@link ServerMessage.Unknown}.</li>
 *   <li>It never returns null.</li>
 * </ul>
 *
 * <p>Fields are split with {@code split("\\|", -1)} so trailing empty
 * fields are kept and counted (for example "JOINED|alice|" has 3 fields).</p>
 */
public final class ProtocolParser {

    /** 1 to 9 digits only: no sign, no spaces, no overflow. */
    private static final String DIGITS = "\\d{1,9}";

    private ProtocolParser() {
        // utility class
    }

    /**
     * Parses one server line (without the line terminator).
     *
     * @throws ProtocolException if the line is null, empty, or a known type with invalid fields
     */
    public static ServerMessage parse(String line) throws ProtocolException {
        if (line == null) {
            throw new ProtocolException("Line is null", null);
        }
        // Defensive: tolerate one trailing carriage return.
        String text = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        if (text.isEmpty()) {
            throw new ProtocolException("Line is empty", line);
        }

        int firstPipe = text.indexOf('|');
        String type = firstPipe < 0 ? text : text.substring(0, firstPipe);

        try {
            return switch (type) {
                case "JOINED" -> new ServerMessage.Joined(
                        requireUsername(splitExact(text, 2)[1], text));
                case "INFO" -> new ServerMessage.Info(payloadAfterType(text));
                case "ERROR" -> new ServerMessage.ServerError(payloadAfterType(text));
                case "PLAYERS" -> new ServerMessage.PlayerList(
                        parseNameList(splitExact(text, 2)[1], text));
                case "HOST" -> new ServerMessage.HostChanged(
                        requireUsername(splitExact(text, 2)[1], text));
                case "GAME_STARTED" -> new ServerMessage.GameStarted(payloadAfterType(text));
                case "QUESTION" -> parseQuestion(text);
                case "ANSWER_ACCEPTED" -> new ServerMessage.AnswerAccepted(
                        parsePositiveInt(splitExact(text, 2)[1], "round", text));
                case "ROUND_RESULT" -> parseRoundResult(text);
                case "SCORES" -> new ServerMessage.Scores(parseScores(splitExact(text, 2)[1], text));
                case "GAME_OVER" -> parseGameOver(text);
                default -> new ServerMessage.Unknown(text);
            };
        } catch (IllegalArgumentException e) {
            // A model constructor rejected the values (for example round > total).
            throw new ProtocolException(e.getMessage(), text, e);
        }
    }

    // ------------------------------------------------------------------
    // Message-specific parsing
    // ------------------------------------------------------------------

    /** QUESTION|round|total|seconds|questionText|optionA|optionB|optionC|optionD */
    private static ServerMessage parseQuestion(String line) throws ProtocolException {
        String[] f = splitExact(line, 9);
        int round = parsePositiveInt(f[1], "round", line);
        int total = parsePositiveInt(f[2], "total", line);
        int seconds = parsePositiveInt(f[3], "seconds", line);
        // QuestionData checks round <= total and that text/options are not blank.
        return new ServerMessage.NewQuestion(
                new QuestionData(round, total, seconds, f[4], f[5], f[6], f[7], f[8]));
    }

    /** ROUND_RESULT|round|correctLetter */
    private static ServerMessage parseRoundResult(String line) throws ProtocolException {
        String[] f = splitExact(line, 3);
        int round = parsePositiveInt(f[1], "round", line);
        Optional<AnswerOption> correct = AnswerOption.tryFromLetter(f[2]);
        if (correct.isEmpty()) {
            throw new ProtocolException("Correct answer must be A, B, C or D but was '" + f[2] + "'", line);
        }
        return new ServerMessage.RoundResult(round, correct.get());
    }

    /** GAME_OVER|winnerNames|bestScore */
    private static ServerMessage parseGameOver(String line) throws ProtocolException {
        String[] f = splitExact(line, 3);
        List<String> winners = parseNameList(f[1], line);
        int bestScore = parseNonNegativeInt(f[2], "bestScore", line);
        return new ServerMessage.GameOver(winners, bestScore);
    }

    /** "" gives an empty leaderboard; otherwise "name:score,name:score,...". */
    private static Leaderboard parseScores(String csv, String line) throws ProtocolException {
        if (csv.isEmpty()) {
            return Leaderboard.empty();
        }
        List<PlayerScore> entries = new ArrayList<>();
        for (String entry : csv.split(",", -1)) {
            entries.add(parseScoreEntry(entry, line));
        }
        return Leaderboard.of(entries);
    }

    /** One "name:score" entry with exactly one ':'. */
    private static PlayerScore parseScoreEntry(String entry, String line) throws ProtocolException {
        String[] parts = entry.split(":", -1);
        if (parts.length != 2) {
            throw new ProtocolException("Score entry must be name:score but was '" + entry + "'", line);
        }
        String username = requireUsername(parts[0], line);
        int score = parseNonNegativeInt(parts[1], "score", line);
        return new PlayerScore(username, score);
    }

    // ------------------------------------------------------------------
    // Field helpers
    // ------------------------------------------------------------------

    /** Splits on '|' keeping trailing empty fields, and checks the exact field count. */
    private static String[] splitExact(String line, int expectedFields) throws ProtocolException {
        String[] fields = line.split("\\|", -1);
        if (fields.length != expectedFields) {
            throw new ProtocolException(
                    "Expected " + expectedFields + " fields but found " + fields.length, line);
        }
        return fields;
    }

    /**
     * Everything after the first '|', kept exactly (may be empty or contain more '|').
     * Used by INFO, ERROR and GAME_STARTED.
     */
    private static String payloadAfterType(String line) throws ProtocolException {
        int firstPipe = line.indexOf('|');
        if (firstPipe < 0) {
            throw new ProtocolException("Missing '|' and message text", line);
        }
        return line.substring(firstPipe + 1);
    }

    private static int parsePositiveInt(String value, String field, String line) throws ProtocolException {
        int number = parseNonNegativeInt(value, field, line);
        if (number < 1) {
            throw new ProtocolException(field + " must be at least 1 but was " + number, line);
        }
        return number;
    }

    private static int parseNonNegativeInt(String value, String field, String line) throws ProtocolException {
        if (!value.matches(DIGITS)) {
            throw new ProtocolException(field + " is not a valid number: '" + value + "'", line);
        }
        return Integer.parseInt(value);
    }

    /** Usernames from the server must match the server's own rule exactly (no trimming). */
    private static String requireUsername(String value, String line) throws ProtocolException {
        if (!value.matches(ClientCommands.USERNAME_REGEX)) {
            throw new ProtocolException("Invalid username '" + value + "'", line);
        }
        return value;
    }

    /** "" gives an empty list; otherwise a comma-separated list of valid usernames. */
    private static List<String> parseNameList(String csv, String line) throws ProtocolException {
        if (csv.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String name : csv.split(",", -1)) {
            names.add(requireUsername(name, line));
        }
        return names;
    }
}
