
package quiz;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Self-tests for GameRules. Run: java -cp out/main;out/test quiz.GameRulesTest */
public final class GameRulesTest {

    public static void main(String[] args) {
        SelfTest t = new SelfTest("GameRulesTest");
        run(t);
        t.finish();
    }

    static void run(SelfTest t) {

        Question question = new Question("Which protocol is reliable?",
                "UDP", "HTTP", "TCP", "ICMP", "C");

        // ---- Scoring ----
        t.checkEquals("correct answer scores 10",
                10, GameRules.pointsFor(question, "C"));

        t.checkEquals("incorrect answer scores 0",
                0, GameRules.pointsFor(question, "A"));

        t.checkEquals("every wrong option scores 0",
                List.of(0, 0, 0),
                List.of(GameRules.pointsFor(question, "A"),
                        GameRules.pointsFor(question, "B"),
                        GameRules.pointsFor(question, "D")));

        t.checkEquals("constant matches the server's 10 points",
                10, GameRules.POINTS_PER_CORRECT);

        // ---- Invalid choices ----
        t.check("A-D are valid choices",
                GameRules.isValidChoice("A") && GameRules.isValidChoice("B")
                        && GameRules.isValidChoice("C") && GameRules.isValidChoice("D"));

        t.check("lowercase, E, empty, multi-letter and null are invalid",
                !GameRules.isValidChoice("c") && !GameRules.isValidChoice("E")
                        && !GameRules.isValidChoice("") && !GameRules.isValidChoice("AB")
                        && !GameRules.isValidChoice(" A") && !GameRules.isValidChoice(null));

        t.checkThrows("pointsFor rejects lowercase choice",
                IllegalArgumentException.class,
                () -> GameRules.pointsFor(question, "c"));

        t.checkThrows("pointsFor rejects choice E",
                IllegalArgumentException.class,
                () -> GameRules.pointsFor(question, "E"));

        t.checkThrows("pointsFor rejects null choice",
                IllegalArgumentException.class,
                () -> GameRules.pointsFor(question, null));

        t.checkThrows("pointsFor rejects null question",
                NullPointerException.class,
                () -> GameRules.pointsFor(null, "A"));

        // ---- Winners ----
        t.checkEquals("single winner",
                List.of("bob"),
                GameRules.winners(scores("alice", 20, "bob", 40, "carol", 10)));

        t.checkEquals("tied winners sorted by username",
                List.of("alice", "carol"),
                GameRules.winners(scores("carol", 30, "bob", 10, "alice", 30)));

        t.checkEquals("all players tied on zero are all winners",
                List.of("alice", "bob"),
                GameRules.winners(scores("bob", 0, "alice", 0)));

        t.checkEquals("no players means no winners",
                List.of(), GameRules.winners(new HashMap<>()));

        t.checkEquals("top score of tie",
                30, GameRules.topScore(scores("carol", 30, "alice", 30)));

        t.checkEquals("top score with no players is 0",
                0, GameRules.topScore(new HashMap<>()));

        // ---- Leaderboard ----
        List<Map.Entry<String, Integer>> board = GameRules.leaderboard(
                scores("dave", 10, "carol", 30, "bob", 10, "alice", 50, "eve", 30));

        t.checkEquals("leaderboard: descending score, ascending name on ties",
                List.of("alice:50", "carol:30", "eve:30", "bob:10", "dave:10"),
                board.stream().map(e -> e.getKey() + ":" + e.getValue()).toList());

        t.checkEquals("leaderboard of empty scores is empty",
                List.of(), GameRules.leaderboard(new HashMap<>()));

        Map<String, Integer> live = scores("alice", 10, "bob", 20);
        List<Map.Entry<String, Integer>> snapshot = GameRules.leaderboard(live);
        live.put("alice", 99);
        t.checkEquals("leaderboard is a snapshot, not a live view",
                10, snapshot.get(1).getValue());

        // Same order as the SCORES message built by QuizServer.scoresTextLocked()
        t.checkEquals("leaderboard text matches server SCORES format",
                "bob:20,alice:10",
                String.join(",", GameRules.leaderboard(scores("alice", 10, "bob", 20))
                        .stream().map(e -> e.getKey() + ":" + e.getValue()).toList()));
    }

    // Builds a mutable map from name/score pairs
    private static Map<String, Integer> scores(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }
}
