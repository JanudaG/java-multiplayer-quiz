package quiz.gui.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import quiz.gui.model.AnswerOption;
import quiz.gui.model.Leaderboard;
import quiz.gui.model.PlayerScore;
import quiz.gui.model.QuestionData;

class ProtocolParserTest {

    /** Parses a line that is expected to be valid. */
    private static ServerMessage parse(String line) throws ProtocolException {
        return ProtocolParser.parse(line);
    }

    private static ProtocolException assertMalformed(String line) {
        return assertThrows(ProtocolException.class, () -> ProtocolParser.parse(line),
                "expected ProtocolException for: " + line);
    }

    // ------------------------------------------------------------------
    @Nested
    class Joined {
        @Test
        void validJoined() throws Exception {
            assertEquals(new ServerMessage.Joined("alice"), parse("JOINED|alice"));
        }

        @Test
        void usernameWithDigitsUnderscoreAndDash() throws Exception {
            assertEquals(new ServerMessage.Joined("Bob_99-x"), parse("JOINED|Bob_99-x"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "JOINED",                    // no field
                "JOINED|",                   // empty username
                "JOINED|alice|",             // trailing empty field
                "JOINED|alice|bob",          // too many fields
                "JOINED|bad name",           // space
                "JOINED| alice",             // leading space (no trimming)
                "JOINED|abcdefghijklmnopq",  // 17 characters
                "JOINED|al:ice",
                "JOINED|al,ice"
        })
        void malformedJoined(String line) {
            assertMalformed(line);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class InfoAndError {
        @Test
        void simpleInfo() throws Exception {
            assertEquals(new ServerMessage.Info("alice joined the lobby"), parse("INFO|alice joined the lobby"));
        }

        @Test
        void infoWithExtraPipesKeepsFullText() throws Exception {
            assertEquals(new ServerMessage.Info("a|b|c"), parse("INFO|a|b|c"));
        }

        @Test
        void infoWithTrailingPipeKeepsIt() throws Exception {
            assertEquals(new ServerMessage.Info("done|"), parse("INFO|done|"));
        }

        @Test
        void emptyInfo() throws Exception {
            assertEquals(new ServerMessage.Info(""), parse("INFO|"));
        }

        @Test
        void serverErrorWithPipeFromRealServer() throws Exception {
            assertEquals(new ServerMessage.ServerError("First message must be JOIN|username"),
                    parse("ERROR|First message must be JOIN|username"));
        }

        @Test
        void serverErrorWithManyPipes() throws Exception {
            assertEquals(new ServerMessage.ServerError("Send ANSWER|round|A, B, C or D"),
                    parse("ERROR|Send ANSWER|round|A, B, C or D"));
        }

        @Test
        void emptyError() throws Exception {
            assertEquals(new ServerMessage.ServerError(""), parse("ERROR|"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"INFO", "ERROR"})
        void missingPipeIsMalformed(String line) {
            assertMalformed(line);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class Players {
        @Test
        void severalPlayersInServerOrder() throws Exception {
            assertEquals(new ServerMessage.PlayerList(List.of("carol", "alice", "bob")),
                    parse("PLAYERS|carol,alice,bob"));
        }

        @Test
        void singlePlayer() throws Exception {
            assertEquals(new ServerMessage.PlayerList(List.of("alice")), parse("PLAYERS|alice"));
        }

        @Test
        void emptyPlayerList() throws Exception {
            assertEquals(new ServerMessage.PlayerList(List.of()), parse("PLAYERS|"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "PLAYERS",
                "PLAYERS|a,,b",          // empty segment
                "PLAYERS|a,b,",          // trailing empty name
                "PLAYERS|a b",
                "PLAYERS|alice|bob",     // wrong field count
                "PLAYERS|alice|"         // trailing empty field
        })
        void malformedPlayers(String line) {
            assertMalformed(line);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class Host {
        @Test
        void validHost() throws Exception {
            assertEquals(new ServerMessage.HostChanged("alice"), parse("HOST|alice"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"HOST", "HOST|", "HOST|a|b", "HOST|alice|", "HOST|bad name"})
        void malformedHost(String line) {
            assertMalformed(line);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class GameStarted {
        @Test
        void descriptionKeptAsIs() throws Exception {
            assertEquals(new ServerMessage.GameStarted("5 questions"), parse("GAME_STARTED|5 questions"));
        }

        @Test
        void missingPipeIsMalformed() {
            assertMalformed("GAME_STARTED");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class Question {
        private static final String VALID =
                "QUESTION|1|5|15|Which Java class listens for TCP connections?"
                        + "|Socket|ServerSocket|DatagramSocket|Thread";

        @Test
        void validQuestion() throws Exception {
            ServerMessage message = parse(VALID);
            QuestionData q = assertInstanceOf(ServerMessage.NewQuestion.class, message).question();
            assertEquals(1, q.round());
            assertEquals(5, q.totalRounds());
            assertEquals(15, q.secondsLimit());
            assertEquals("Which Java class listens for TCP connections?", q.text());
            assertEquals(List.of("Socket", "ServerSocket", "DatagramSocket", "Thread"), q.options());
        }

        @Test
        void lastRoundEqualToTotalIsValid() throws Exception {
            ServerMessage message = parse("QUESTION|5|5|15|Q|a|b|c|d");
            assertEquals(5, assertInstanceOf(ServerMessage.NewQuestion.class, message).question().round());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "QUESTION|1|5|15|Q|a|b|c",          // 8 fields
                "QUESTION|1|5|15|Q|a|b|c|d|e",      // 10 fields
                "QUESTION|1|5|15|Q|a|b|c|d|",       // trailing empty field -> 10 fields
                "QUESTION|1|5|15|Q|a|b|c|",         // 9 fields but option D empty
                "QUESTION|1|5|15||a|b|c|d",         // empty question text
                "QUESTION|1|5|15|Q|a| |c|d",        // blank option
                "QUESTION",
                "QUESTION|x|5|15|Q|a|b|c|d",        // non-numeric round
                "QUESTION|1|y|15|Q|a|b|c|d",        // non-numeric total
                "QUESTION|1|5|z|Q|a|b|c|d",         // non-numeric seconds
                "QUESTION|0|5|15|Q|a|b|c|d",        // round 0
                "QUESTION|-1|5|15|Q|a|b|c|d",       // negative round
                "QUESTION|+1|5|15|Q|a|b|c|d",       // sign not allowed
                "QUESTION| 1|5|15|Q|a|b|c|d",       // space not allowed
                "QUESTION|1|0|15|Q|a|b|c|d",        // total 0
                "QUESTION|6|5|15|Q|a|b|c|d",        // round > total
                "QUESTION|1|5|0|Q|a|b|c|d",         // seconds 0
                "QUESTION|1|5|-15|Q|a|b|c|d",       // negative seconds
                "QUESTION|1|5|99999999999|Q|a|b|c|d", // overflow
                "QUESTION|1|5||Q|a|b|c|d"           // empty seconds
        })
        void malformedQuestion(String line) {
            assertMalformed(line);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class AnswerAccepted {
        @Test
        void validAnswerAccepted() throws Exception {
            assertEquals(new ServerMessage.AnswerAccepted(3), parse("ANSWER_ACCEPTED|3"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "ANSWER_ACCEPTED", "ANSWER_ACCEPTED|", "ANSWER_ACCEPTED|0", "ANSWER_ACCEPTED|-2",
                "ANSWER_ACCEPTED|abc", "ANSWER_ACCEPTED|1|", "ANSWER_ACCEPTED|1|2"
        })
        void malformedAnswerAccepted(String line) {
            assertMalformed(line);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class RoundResult {
        @ParameterizedTest
        @ValueSource(strings = {"A", "B", "C", "D"})
        void everyValidLetter(String letter) throws Exception {
            assertEquals(new ServerMessage.RoundResult(2, AnswerOption.valueOf(letter)),
                    parse("ROUND_RESULT|2|" + letter));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "ROUND_RESULT|1|E",       // invalid letter
                "ROUND_RESULT|1|a",       // lower case
                "ROUND_RESULT|1|AB",      // two letters
                "ROUND_RESULT|1|",        // empty letter (trailing empty field)
                "ROUND_RESULT|1| A",      // space
                "ROUND_RESULT|1",         // missing field
                "ROUND_RESULT|1|A|",      // extra trailing empty field
                "ROUND_RESULT|x|A",       // non-numeric round
                "ROUND_RESULT|0|A"        // round 0
        })
        void malformedRoundResult(String line) {
            assertMalformed(line);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class Scores {
        @Test
        void emptyScores() throws Exception {
            assertEquals(new ServerMessage.Scores(Leaderboard.empty()), parse("SCORES|"));
        }

        @Test
        void singleEntry() throws Exception {
            assertEquals(new ServerMessage.Scores(Leaderboard.of(List.of(new PlayerScore("alice", 10)))),
                    parse("SCORES|alice:10"));
        }

        @Test
        void multipleEntriesKeepServerOrder() throws Exception {
            ServerMessage message = parse("SCORES|bob:30,alice:20,carol:20,dave:0");
            Leaderboard board = assertInstanceOf(ServerMessage.Scores.class, message).leaderboard();
            assertEquals(List.of(
                    new PlayerScore("bob", 30),
                    new PlayerScore("alice", 20),
                    new PlayerScore("carol", 20),
                    new PlayerScore("dave", 0)), board.entries());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "SCORES",
                "SCORES|alice",            // missing ':'
                "SCORES|alice:x",          // bad score
                "SCORES|alice:-5",         // negative score
                "SCORES|alice:",           // empty score
                "SCORES|:10",              // empty name
                "SCORES|alice:1:2",        // two ':'
                "SCORES|alice:10,",        // empty trailing entry
                "SCORES|alice:10,,bob:5",  // empty middle entry
                "SCORES|bad name:10",      // invalid username
                "SCORES|alice:10|"         // trailing empty field
        })
        void malformedScores(String line) {
            assertMalformed(line);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class GameOver {
        @Test
        void singleWinner() throws Exception {
            assertEquals(new ServerMessage.GameOver(List.of("alice"), 40), parse("GAME_OVER|alice|40"));
        }

        @Test
        void tiedWinners() throws Exception {
            assertEquals(new ServerMessage.GameOver(List.of("alice", "bob"), 20), parse("GAME_OVER|alice,bob|20"));
        }

        @Test
        void emptyWinnersAndZeroScore() throws Exception {
            assertEquals(new ServerMessage.GameOver(List.of(), 0), parse("GAME_OVER||0"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "GAME_OVER",
                "GAME_OVER|alice",          // missing score
                "GAME_OVER|alice|",         // empty score (trailing empty field)
                "GAME_OVER|alice|x",        // bad score
                "GAME_OVER|alice|-1",       // negative score
                "GAME_OVER|alice|10|",      // extra trailing empty field
                "GAME_OVER|alice,,bob|10",  // empty winner name
                "GAME_OVER|bad name|10"
        })
        void malformedGameOver(String line) {
            assertMalformed(line);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class UnknownAndGeneral {
        @ParameterizedTest
        @ValueSource(strings = {"HELLO|x", "HELLO", "info|lower case", "START", "JOIN|alice", "|leading pipe"})
        void unknownTypesReturnUnknown(String line) throws Exception {
            assertEquals(new ServerMessage.Unknown(line), parse(line));
        }

        @Test
        void nullLineThrows() {
            ProtocolException e = assertMalformed(null);
            assertNull(e.getRawLine());
        }

        @Test
        void emptyLineThrows() {
            assertMalformed("");
        }

        @Test
        void onlyCarriageReturnThrows() {
            assertMalformed("\r");
        }

        @Test
        void trailingCarriageReturnIsTolerated() throws Exception {
            assertEquals(new ServerMessage.HostChanged("alice"), parse("HOST|alice\r"));
        }

        @Test
        void exceptionKeepsRawLineAndReason() {
            ProtocolException e = assertMalformed("ROUND_RESULT|1|E");
            assertEquals("ROUND_RESULT|1|E", e.getRawLine());
            assertTrue(e.getReason().contains("A, B, C or D"));
            assertTrue(e.getMessage().endsWith("in: ROUND_RESULT|1|E"));
        }

        @Test
        void modelValidationErrorIsWrappedWithCause() {
            ProtocolException e = assertMalformed("QUESTION|6|5|15|Q|a|b|c|d");
            assertInstanceOf(IllegalArgumentException.class, e.getCause());
        }
    }
}
