
package quiz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Self-tests for QuestionBank. Run: java -cp out/main;out/test quiz.QuestionBankTest */
public final class QuestionBankTest {

    public static void main(String[] args) {
        SelfTest t = new SelfTest("QuestionBankTest");
        run(t);
        t.finish();
    }

    static void run(SelfTest t) {

        // ---- Default questions ----
        List<Question> questions = QuestionBank.defaultQuestions();

        t.checkEquals("default bank has the five Phase 3 questions",
                5, questions.size());

        t.checkEquals("question 1 preserved",
                new Question("Which Java class listens for TCP connections?",
                        "Socket", "ServerSocket", "DatagramSocket", "Thread", "B"),
                questions.get(0));

        t.checkEquals("question 3 preserved",
                new Question("What does an IP address identify on a network?",
                        "A network interface", "A Java method",
                        "A file format", "A password", "A"),
                questions.get(2));

        t.checkEquals("correct answers preserved in order",
                List.of("B", "C", "A", "D", "A"),
                questions.stream().map(Question::correctAnswer).toList());

        boolean allValid = true;
        for (Question q : questions) {
            try {
                QuestionBank.validate(q);
            } catch (IllegalArgumentException e) {
                allValid = false;
            }
        }
        t.check("every default question passes validation", allValid);

        t.checkThrows("default bank is unmodifiable",
                UnsupportedOperationException.class,
                () -> questions.add(valid()));

        t.check("default question messages have exactly 9 protocol fields",
                questions.stream().allMatch(q ->
                        q.toMessage(1, 5, 15).split("\\|", -1).length == 9));

        // ---- Valid question ----
        try {
            QuestionBank.validate(valid());
            t.check("valid question accepted", true);
        } catch (IllegalArgumentException e) {
            t.check("valid question accepted (" + e.getMessage() + ")", false);
        }

        // ---- Invalid questions ----
        t.checkThrows("null question rejected", IllegalArgumentException.class,
                () -> QuestionBank.validate(null));

        t.checkThrows("null text rejected", IllegalArgumentException.class,
                () -> QuestionBank.validate(q(null, "A1", "B1", "C1", "D1", "A")));

        t.checkThrows("blank text rejected", IllegalArgumentException.class,
                () -> QuestionBank.validate(q("   ", "A1", "B1", "C1", "D1", "A")));

        t.checkThrows("empty option rejected", IllegalArgumentException.class,
                () -> QuestionBank.validate(q("Text?", "A1", "", "C1", "D1", "A")));

        t.checkThrows("null option rejected", IllegalArgumentException.class,
                () -> QuestionBank.validate(q("Text?", "A1", "B1", "C1", null, "A")));

        t.checkThrows("duplicate options rejected", IllegalArgumentException.class,
                () -> QuestionBank.validate(q("Text?", "TCP", "UDP", "TCP", "IP", "A")));

        t.checkThrows("duplicate options differing only by case/spaces rejected",
                IllegalArgumentException.class,
                () -> QuestionBank.validate(q("Text?", "TCP", " tcp ", "UDP", "IP", "A")));

        t.checkThrows("'|' in question text rejected", IllegalArgumentException.class,
                () -> QuestionBank.validate(q("A|B?", "A1", "B1", "C1", "D1", "A")));

        t.checkThrows("'|' in option rejected", IllegalArgumentException.class,
                () -> QuestionBank.validate(q("Text?", "A1", "B|1", "C1", "D1", "A")));

        t.checkThrows("newline in question text rejected", IllegalArgumentException.class,
                () -> QuestionBank.validate(q("Line1\nLine2", "A1", "B1", "C1", "D1", "A")));

        t.checkThrows("carriage return in option rejected", IllegalArgumentException.class,
                () -> QuestionBank.validate(q("Text?", "A1", "B1", "C1\r", "D1", "A")));

        t.checkThrows("lowercase correct answer rejected", IllegalArgumentException.class,
                () -> QuestionBank.validate(q("Text?", "A1", "B1", "C1", "D1", "a")));

        t.checkThrows("correct answer E rejected", IllegalArgumentException.class,
                () -> QuestionBank.validate(q("Text?", "A1", "B1", "C1", "D1", "E")));

        t.checkThrows("multi-letter correct answer rejected", IllegalArgumentException.class,
                () -> QuestionBank.validate(q("Text?", "A1", "B1", "C1", "D1", "AB")));

        t.checkThrows("null correct answer rejected", IllegalArgumentException.class,
                () -> QuestionBank.validate(q("Text?", "A1", "B1", "C1", "D1", null)));

        // ---- Whole bank validation ----
        t.checkThrows("null bank rejected", IllegalArgumentException.class,
                () -> QuestionBank.validateAll(null));

        t.checkThrows("empty bank rejected", IllegalArgumentException.class,
                () -> QuestionBank.validateAll(List.of()));

        t.checkThrows("bank containing a null question rejected",
                IllegalArgumentException.class,
                () -> QuestionBank.validateAll(Arrays.asList(valid(), null)));

        t.checkThrows("bank containing an invalid question rejected",
                IllegalArgumentException.class,
                () -> QuestionBank.validateAll(List.of(valid(),
                        q("Bad|text", "A1", "B1", "C1", "D1", "A"))));

        t.checkThrows("bank with duplicate question text rejected",
                IllegalArgumentException.class,
                () -> QuestionBank.validateAll(List.of(valid(), valid())));

        List<Question> source = new ArrayList<>(List.of(valid()));
        List<Question> copy = QuestionBank.validateAll(source);
        source.add(q("Another?", "A1", "B1", "C1", "D1", "B"));
        t.checkEquals("validateAll returns a copy unaffected by later changes",
                1, copy.size());

        String message = null;
        try {
            QuestionBank.validateAll(List.of(valid(),
                    q("Text?", "A1", "B1", "C1", "D1", "Z")));
        } catch (IllegalArgumentException e) {
            message = e.getMessage();
        }
        t.check("error message names the failing question number",
                message != null && message.startsWith("Question 2:"));
    }

    private static Question valid() {
        return q("What is 2 + 2?", "3", "4", "5", "22", "B");
    }

    private static Question q(String text, String a, String b,
                              String c, String d, String answer) {
        return new Question(text, a, b, c, d, answer);
    }
}
