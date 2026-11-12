package quiz.gui.model;

/**
 * One leaderboard entry: a player name and their score.
 */
public record PlayerScore(String username, int score) {

    public PlayerScore {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (score < 0) {
            throw new IllegalArgumentException("score must not be negative but was " + score);
        }
    }
}
