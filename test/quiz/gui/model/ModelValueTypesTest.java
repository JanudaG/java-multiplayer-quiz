package quiz.gui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ModelValueTypesTest {

    @Nested
    class AnswerOptionTests {
        @Test
        void fromLetterAcceptsAllFourLetters() {
            assertEquals(AnswerOption.A, AnswerOption.fromLetter("A"));
            assertEquals(AnswerOption.B, AnswerOption.fromLetter("B"));
            assertEquals(AnswerOption.C, AnswerOption.fromLetter("C"));
            assertEquals(AnswerOption.D, AnswerOption.fromLetter("D"));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"E", "a", "d", "AB", " A", "A ", "1"})
        void invalidLetters(String letter) {
            assertThrows(IllegalArgumentException.class, () -> AnswerOption.fromLetter(letter));
            assertEquals(Optional.empty(), AnswerOption.tryFromLetter(letter));
        }

        @Test
        void tryFromLetterValid() {
            assertEquals(Optional.of(AnswerOption.C), AnswerOption.tryFromLetter("C"));
        }

        @Test
        void letterIsTheProtocolLetter() {
            assertEquals("B", AnswerOption.B.letter());
        }
    }

    @Nested
    class QuestionDataTests {
        private QuestionData sample() {
            return new QuestionData(2, 5, 15, "Which protocol is reliable?", "UDP", "HTTP", "TCP", "ICMP");
        }

        @Test
        void validQuestionKeepsFields() {
            QuestionData q = sample();
            assertEquals(2, q.round());
            assertEquals(5, q.totalRounds());
            assertEquals(15, q.secondsLimit());
            assertEquals("Which protocol is reliable?", q.text());
        }

        @Test
        void optionByAnswerOption() {
            QuestionData q = sample();
            assertEquals("UDP", q.option(AnswerOption.A));
            assertEquals("HTTP", q.option(AnswerOption.B));
            assertEquals("TCP", q.option(AnswerOption.C));
            assertEquals("ICMP", q.option(AnswerOption.D));
        }

        @Test
        void optionsInOrder() {
            assertEquals(List.of("UDP", "HTTP", "TCP", "ICMP"), sample().options());
        }

        @Test
        void nullOptionIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> sample().option(null));
        }

        @Test
        void roundEqualToTotalIsValid() {
            assertEquals(5, new QuestionData(5, 5, 1, "Q", "a", "b", "c", "d").round());
        }

        @Test
        void invalidNumbersAreRejected() {
            assertThrows(IllegalArgumentException.class, () -> new QuestionData(0, 5, 15, "Q", "a", "b", "c", "d"));
            assertThrows(IllegalArgumentException.class, () -> new QuestionData(1, 0, 15, "Q", "a", "b", "c", "d"));
            assertThrows(IllegalArgumentException.class, () -> new QuestionData(6, 5, 15, "Q", "a", "b", "c", "d"));
            assertThrows(IllegalArgumentException.class, () -> new QuestionData(1, 5, 0, "Q", "a", "b", "c", "d"));
            assertThrows(IllegalArgumentException.class, () -> new QuestionData(1, 5, -1, "Q", "a", "b", "c", "d"));
        }

        @Test
        void blankTextOrOptionsAreRejected() {
            assertThrows(IllegalArgumentException.class, () -> new QuestionData(1, 5, 15, null, "a", "b", "c", "d"));
            assertThrows(IllegalArgumentException.class, () -> new QuestionData(1, 5, 15, " ", "a", "b", "c", "d"));
            assertThrows(IllegalArgumentException.class, () -> new QuestionData(1, 5, 15, "Q", "", "b", "c", "d"));
            assertThrows(IllegalArgumentException.class, () -> new QuestionData(1, 5, 15, "Q", "a", null, "c", "d"));
            assertThrows(IllegalArgumentException.class, () -> new QuestionData(1, 5, 15, "Q", "a", "b", " ", "d"));
            assertThrows(IllegalArgumentException.class, () -> new QuestionData(1, 5, 15, "Q", "a", "b", "c", ""));
        }
    }

    @Nested
    class PlayerScoreTests {
        @Test
        void validScore() {
            PlayerScore score = new PlayerScore("alice", 30);
            assertEquals("alice", score.username());
            assertEquals(30, score.score());
        }

        @Test
        void zeroScoreIsValid() {
            assertEquals(0, new PlayerScore("bob", 0).score());
        }

        @Test
        void invalidValuesAreRejected() {
            assertThrows(IllegalArgumentException.class, () -> new PlayerScore(null, 0));
            assertThrows(IllegalArgumentException.class, () -> new PlayerScore("", 0));
            assertThrows(IllegalArgumentException.class, () -> new PlayerScore("  ", 0));
            assertThrows(IllegalArgumentException.class, () -> new PlayerScore("alice", -1));
        }
    }
}
