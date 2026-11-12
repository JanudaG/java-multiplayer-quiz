package quiz.gui.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import quiz.gui.model.AnswerOption;

class ClientCommandsTest {

    @Test
    void joinWithValidName() {
        assertEquals("JOIN|alice", ClientCommands.join("alice"));
    }

    @Test
    void joinTrimsLikeTheServer() {
        assertEquals("JOIN|alice", ClientCommands.join("  alice  "));
    }

    @Test
    void joinAcceptsSixteenCharacters() {
        assertEquals("JOIN|abcdefghijklmnop", ClientCommands.join("abcdefghijklmnop"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "abcdefghijklmnopq",   // 17 characters
            "bad name",
            "a|b",
            "a,b",
            "a:b",
            "élan",                // non-ASCII
            "名前"
    })
    void joinRejectsInvalidUsernames(String username) {
        assertFalse(ClientCommands.isValidUsername(username));
        assertThrows(IllegalArgumentException.class, () -> ClientCommands.join(username));
        assertTrue(ClientCommands.usernameProblem(username).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "Bob_99", "x-y", "A", "  padded  "})
    void validUsernamesHaveNoProblem(String username) {
        assertTrue(ClientCommands.isValidUsername(username));
        assertEquals(Optional.empty(), ClientCommands.usernameProblem(username));
    }

    @Test
    void usernameProblemMessages() {
        assertEquals(Optional.of("Please enter a username."), ClientCommands.usernameProblem(""));
        assertEquals(Optional.of("Username must be at most 16 characters."),
                ClientCommands.usernameProblem("abcdefghijklmnopq"));
        assertEquals(Optional.of("Use only letters, digits, _ or - in the username."),
                ClientCommands.usernameProblem("bad name"));
    }

    @ParameterizedTest
    @EnumSource(AnswerOption.class)
    void answerWithEveryOption(AnswerOption option) {
        assertEquals("ANSWER|3|" + option.name(), ClientCommands.answer(3, option));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void answerRejectsInvalidRound(int round) {
        assertThrows(IllegalArgumentException.class, () -> ClientCommands.answer(round, AnswerOption.A));
    }

    @Test
    void answerRejectsNullOption() {
        assertThrows(IllegalArgumentException.class, () -> ClientCommands.answer(1, null));
    }

    @Test
    void startAndQuitExactStrings() {
        assertEquals("START", ClientCommands.start());
        assertEquals("QUIT", ClientCommands.quit());
    }

    @Test
    void commandsNeverContainNewlines() {
        String[] commands = {
                ClientCommands.join("alice"), ClientCommands.start(),
                ClientCommands.answer(1, AnswerOption.D), ClientCommands.quit()
        };
        for (String command : commands) {
            assertFalse(command.contains("\n") || command.contains("\r"), command);
        }
    }
}
