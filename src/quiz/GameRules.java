
package quiz;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stateless scoring and ranking rules shared by the quiz.
 *
 * The server remains responsible for game state, rounds and deadlines;
 * these methods only calculate points, winners and leaderboard order.
 */
public final class GameRules {

    public static final int POINTS_PER_CORRECT = 10;
    public static final int POINTS_PER_INCORRECT = 0;

    // Highest score first, then username A-Z for equal scores
    public static final Comparator<Map.Entry<String, Integer>> LEADERBOARD_ORDER =
            Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                    .reversed()
                    .thenComparing(Map.Entry::getKey);

    private GameRules() {
        // Utility class
    }

    /** True only for the uppercase choices A, B, C or D. */
    public static boolean isValidChoice(String choice) {
        return choice != null && choice.matches("[ABCD]");
    }

    /**
     * Points earned for an answer: 10 if correct, 0 if incorrect.
     *
     * @throws IllegalArgumentException if the choice is not A, B, C or D
     */
    public static int pointsFor(Question question, String choice) {

        Objects.requireNonNull(question, "question");

        if (!isValidChoice(choice)) {
            throw new IllegalArgumentException(
                    "Answer must be A, B, C or D, got: " + choice);
        }

        return choice.equals(question.correctAnswer())
                ? POINTS_PER_CORRECT
                : POINTS_PER_INCORRECT;
    }

    /** Highest score in the map, or 0 when there are no players. */
    public static int topScore(Map<String, Integer> scores) {

        return scores.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
    }

    /**
     * All players sharing the highest score, sorted by username.
     * Returns more than one name for a tie and an empty list for no players.
     */
    public static List<String> winners(Map<String, Integer> scores) {

        int best = topScore(scores);

        return scores.entrySet().stream()
                .filter(e -> e.getValue() == best)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Entries sorted by descending score, then ascending username. */
    public static List<Map.Entry<String, Integer>> leaderboard(
            Map<String, Integer> scores) {

        return scores.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue()))
                .sorted(LEADERBOARD_ORDER)
                .toList();
    }
}
