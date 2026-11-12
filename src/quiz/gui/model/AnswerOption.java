package quiz.gui.model;

import java.util.Optional;

/**
 * One of the four answer choices of a quiz question.
 *
 * <p>Using an enum instead of raw "A".."D" strings means an invalid
 * letter can never reach the model or be sent to the server.</p>
 */
public enum AnswerOption {
    A, B, C, D;

    /**
     * Converts an exact upper-case letter ("A", "B", "C" or "D").
     *
     * @throws IllegalArgumentException if the text is not exactly one valid letter
     */
    public static AnswerOption fromLetter(String letter) {
        return tryFromLetter(letter).orElseThrow(() ->
                new IllegalArgumentException("Answer must be A, B, C or D but was: " + letter));
    }

    /**
     * Same as {@link #fromLetter(String)} but returns an empty Optional
     * instead of throwing. Lower-case letters, blanks and longer text are rejected.
     */
    public static Optional<AnswerOption> tryFromLetter(String letter) {
        if (letter == null) {
            return Optional.empty();
        }
        return switch (letter) {
            case "A" -> Optional.of(A);
            case "B" -> Optional.of(B);
            case "C" -> Optional.of(C);
            case "D" -> Optional.of(D);
            default -> Optional.empty();
        };
    }

    /** The letter used in the protocol, for example "B". */
    public String letter() {
        return name();
    }
}
