package quiz.gui.model;

import java.util.List;
import java.util.OptionalInt;

/**
 * An immutable list of scores in the server's order.
 *
 * <p>The server already sorts by score (highest first) and then by name,
 * so this class keeps the order exactly as received.</p>
 */
public final class Leaderboard {

    private static final Leaderboard EMPTY = new Leaderboard(List.of());

    private final List<PlayerScore> entries;

    private Leaderboard(List<PlayerScore> entries) {
        this.entries = entries;
    }

    /** A leaderboard with no entries. */
    public static Leaderboard empty() {
        return EMPTY;
    }

    /**
     * Creates a leaderboard from the given entries (copied, order kept).
     *
     * @throws IllegalArgumentException if the list or any entry is null
     */
    public static Leaderboard of(List<PlayerScore> entries) {
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        for (PlayerScore entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException("entries must not contain null");
            }
        }
        return entries.isEmpty() ? EMPTY : new Leaderboard(List.copyOf(entries));
    }

    /** The entries in server order. The returned list cannot be modified. */
    public List<PlayerScore> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /** The score of the given player, or an empty OptionalInt if they are not listed. */
    public OptionalInt scoreOf(String username) {
        for (PlayerScore entry : entries) {
            if (entry.username().equals(username)) {
                return OptionalInt.of(entry.score());
            }
        }
        return OptionalInt.empty();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof Leaderboard that && entries.equals(that.entries));
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return "Leaderboard" + entries;
    }
}
