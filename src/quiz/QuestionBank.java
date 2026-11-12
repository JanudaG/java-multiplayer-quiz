
package quiz;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Stores the quiz questions and checks that question data is safe to send
 * over the line-based, '|'-separated network protocol.
 */
public final class QuestionBank {

    private QuestionBank() {
        // Utility class
    }

    // The five original Phase 3 questions (same text, options and answers)
    private static final List<Question> DEFAULT_QUESTIONS = validateAll(List.of(
        new Question(
            "Which Java class listens for TCP connections?",
            "Socket", "ServerSocket", "DatagramSocket", "Thread", "B"),

        new Question(
            "Which protocol provides reliable, ordered delivery?",
            "UDP", "HTTP", "TCP", "ICMP", "C"),

        new Question(
            "What does an IP address identify on a network?",
            "A network interface", "A Java method",
            "A file format", "A password", "A"),

        new Question(
            "Which Java method accepts an incoming client?",
            "connect()", "read()", "listen()", "accept()", "D"),

        new Question(
            "Which Java feature allows clients to be handled concurrently?",
            "Threads", "Packages", "Comments", "Imports", "A")
    ));

    /** Returns the validated, unmodifiable default question list. */
    public static List<Question> defaultQuestions() {
        return DEFAULT_QUESTIONS;
    }

    /**
     * Validates every question and returns an unmodifiable copy.
     *
     * @throws IllegalArgumentException if the list is null, empty, contains
     *         a null or invalid question, or repeats a question text
     */
    public static List<Question> validateAll(List<Question> questions) {

        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException(
                    "Question bank must contain at least one question");
        }

        Set<String> seenTexts = new HashSet<>();

        for (int i = 0; i < questions.size(); i++) {

            Question question = questions.get(i);

            try {
                validate(question);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Question " + (i + 1) + ": " + e.getMessage(), e);
            }

            if (!seenTexts.add(normalise(question.text()))) {
                throw new IllegalArgumentException(
                        "Question " + (i + 1) + ": duplicate question text");
            }
        }

        // List.copyOf also rejects null elements and prevents later changes
        return List.copyOf(questions);
    }

    /**
     * Checks a single question.
     *
     * @throws IllegalArgumentException if the question is invalid
     */
    public static void validate(Question question) {

        if (question == null) {
            throw new IllegalArgumentException("Question must not be null");
        }

        requireSafeText("question text", question.text());
        requireSafeText("option A", question.optionA());
        requireSafeText("option B", question.optionB());
        requireSafeText("option C", question.optionC());
        requireSafeText("option D", question.optionD());

        // Options must be distinct, ignoring case and outer spaces
        Set<String> options = new HashSet<>();
        options.add(normalise(question.optionA()));
        options.add(normalise(question.optionB()));
        options.add(normalise(question.optionC()));
        options.add(normalise(question.optionD()));

        if (options.size() != 4) {
            throw new IllegalArgumentException(
                    "the four options must be different");
        }

        String answer = question.correctAnswer();

        if (answer == null || !answer.matches("[ABCD]")) {
            throw new IllegalArgumentException(
                    "correct answer must be A, B, C or D (uppercase), got: "
                            + answer);
        }
    }

    // Text must be present and must not break the '|' / newline protocol
    private static void requireSafeText(String field, String value) {

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }

        if (value.indexOf('|') >= 0) {
            throw new IllegalArgumentException(
                    field + " must not contain '|'");
        }

        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    field + " must not contain line breaks");
        }
    }

    private static String normalise(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
