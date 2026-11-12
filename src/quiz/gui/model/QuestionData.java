package quiz.gui.model;

import java.util.List;

/**
 * A question as the client sees it (the correct answer is never included).
 *
 * <p>The compact constructor checks the basic rules, so an invalid
 * question can never exist in the model.</p>
 */
public record QuestionData(int round, int totalRounds, int secondsLimit, String text,
                           String optionA, String optionB, String optionC, String optionD) {

    public QuestionData {
        if (round < 1) {
            throw new IllegalArgumentException("round must be at least 1 but was " + round);
        }
        if (totalRounds < 1) {
            throw new IllegalArgumentException("totalRounds must be at least 1 but was " + totalRounds);
        }
        if (round > totalRounds) {
            throw new IllegalArgumentException(
                    "round " + round + " is greater than totalRounds " + totalRounds);
        }
        if (secondsLimit < 1) {
            throw new IllegalArgumentException("secondsLimit must be at least 1 but was " + secondsLimit);
        }
        requireText(text, "question text");
        requireText(optionA, "option A");
        requireText(optionB, "option B");
        requireText(optionC, "option C");
        requireText(optionD, "option D");
    }

    /** Returns the text of the given option. */
    public String option(AnswerOption option) {
        if (option == null) {
            throw new IllegalArgumentException("option must not be null");
        }
        return switch (option) {
            case A -> optionA;
            case B -> optionB;
            case C -> optionC;
            case D -> optionD;
        };
    }

    /** The four option texts in A, B, C, D order. */
    public List<String> options() {
        return List.of(optionA, optionB, optionC, optionD);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
