package quiz.gui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

class LeaderboardTest {

    private static final PlayerScore BOB = new PlayerScore("bob", 30);
    private static final PlayerScore ALICE = new PlayerScore("alice", 20);
    private static final PlayerScore CAROL = new PlayerScore("carol", 20);

    @Test
    void emptyLeaderboard() {
        Leaderboard board = Leaderboard.empty();
        assertTrue(board.isEmpty());
        assertEquals(0, board.size());
        assertEquals(List.of(), board.entries());
    }

    @Test
    void ofEmptyListEqualsEmpty() {
        assertEquals(Leaderboard.empty(), Leaderboard.of(List.of()));
    }

    @Test
    void keepsServerOrder() {
        Leaderboard board = Leaderboard.of(List.of(BOB, ALICE, CAROL));
        assertEquals(List.of(BOB, ALICE, CAROL), board.entries());
        assertEquals(3, board.size());
        assertFalse(board.isEmpty());
    }

    @Test
    void entriesCannotBeModified() {
        Leaderboard board = Leaderboard.of(List.of(BOB, ALICE));
        assertThrows(UnsupportedOperationException.class, () -> board.entries().add(CAROL));
        assertThrows(UnsupportedOperationException.class, () -> board.entries().clear());
    }

    @Test
    void changingTheSourceListDoesNotChangeTheLeaderboard() {
        List<PlayerScore> source = new ArrayList<>(List.of(BOB));
        Leaderboard board = Leaderboard.of(source);
        source.add(ALICE);
        assertEquals(List.of(BOB), board.entries());
    }

    @Test
    void scoreOfPresentAndAbsentPlayers() {
        Leaderboard board = Leaderboard.of(List.of(BOB, ALICE));
        assertEquals(OptionalInt.of(30), board.scoreOf("bob"));
        assertEquals(OptionalInt.of(20), board.scoreOf("alice"));
        assertEquals(OptionalInt.empty(), board.scoreOf("zoe"));
        assertEquals(OptionalInt.empty(), board.scoreOf(null));
    }

    @Test
    void equalityAndHashCode() {
        Leaderboard first = Leaderboard.of(List.of(BOB, ALICE));
        Leaderboard same = Leaderboard.of(List.of(new PlayerScore("bob", 30), new PlayerScore("alice", 20)));
        Leaderboard otherOrder = Leaderboard.of(List.of(ALICE, BOB));
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, otherOrder);
        assertNotEquals(first, Leaderboard.empty());
    }

    @Test
    void nullInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Leaderboard.of(null));
        assertThrows(IllegalArgumentException.class, () -> Leaderboard.of(Arrays.asList(BOB, null)));
    }

    @Test
    void toStringShowsEntries() {
        assertTrue(Leaderboard.of(List.of(BOB)).toString().contains("bob"));
    }
}
