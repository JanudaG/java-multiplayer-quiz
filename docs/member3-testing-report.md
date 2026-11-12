# Testing Report — Question Bank, Game Rules & Multiplayer Tests (Member 3)

Branch: `feature/question-bank-tests` · Baseline: `main` @ `90a4cf2` (Phase 3 quiz engine)
JDK: 21.0.12 · Platform: Windows 11 · No build tool or external libraries required.

> **Status update (`integration/full-app-test`):** the server integration steps below have been
> applied; issues 1 (console error text) and 2 (duplicate `SCORES`) are fixed; issue 3 is confirmed
> by the team as an intentional rule (the last remaining player wins). See
> [integration-report.md](integration-report.md) for current results. The rest of this report
> describes the original branch as submitted.

## How to run

From the repository root (Windows uses `;` in the classpath, macOS/Linux use `:`):

```bash
javac -encoding UTF-8 -d out/main src/quiz/*.java
javac -encoding UTF-8 -cp out/main -d out/test test/quiz/*.java

java -cp "out/main;out/test" quiz.QuestionBankTest
java -cp "out/main;out/test" quiz.GameRulesTest
java -cp "out/main;out/test" quiz.QuizServerIntegrationTest   # ~45 s, needs port 5000 free
```

Each test prints `PASS`/`FAIL` per check and exits with status 1 if anything fails.
`QuizServerIntegrationTest` starts the real, unmodified `QuizServer` in a child
process and plays three games with raw TCP clients speaking the existing protocol.

## Results

| Suite | Checks | Result |
|---|---|---|
| `QuestionBankTest` | 30 | 30 passed, 0 failed |
| `GameRulesTest` | 20 | 20 passed, 0 failed |
| `QuizServerIntegrationTest` | 59 | 59 passed, 0 failed |

The tests were also checked against a deliberately broken `GameRules`
(8 points per answer, reversed tie order); `GameRulesTest` failed both affected checks
as expected.

### Multiplayer scenarios (against the current `QuizServer` on `main`)

| # | Scenario | Result | What was checked |
|---|---|---|---|
| 1 | Two players connecting | PASS | `JOINED`, `PLAYERS\|alice,bob`, `HOST\|alice`, join `INFO` |
| 2 | Host-only game start | PASS | Non-host `START` → `ERROR\|Only the host can start` |
| 3 | Minimum two-player requirement | PASS | Lone host `START` → `ERROR\|At least two players are required` |
| 4 | Identical questions to both clients | PASS | Both clients get the same `QUESTION` line in all rounds; it equals `QuestionBank` output |
| 5 | Correct and incorrect scoring | PASS | Correct = +10, wrong = +0 (`SCORES\|alice:10,bob:0`) |
| 6 | Duplicate-answer rejection | PASS | Second answer → `ERROR\|You already answered this round` |
| 7 | Invalid round-number rejection | PASS | Rounds `2`, `0` and `x`, choices `E` and `b` all rejected; answer between rounds rejected |
| 8 | 15-second answer deadline | PASS | Round closed 15.0 s after the question; unanswered player scored 0; late answer rejected |
| 9 | Round results and score updates | PASS | `ROUND_RESULT\|n\|X` + sorted `SCORES` to both players every round |
| 10 | Game over and winner determination | PASS | Tie → `GAME_OVER\|alice,bob\|30`; single winner → `GAME_OVER\|alice\|50` |
| 11 | Duplicate username rejection | PASS | `ERROR\|Username already taken`, connection closed; invalid name also rejected |
| 12 | Mid-game join rejection | PASS | `ERROR\|Game already running; join after it finishes`, connection closed |
| 13 | Player disconnection handling | PASS | Abrupt disconnect mid-game → `INFO` left, `PLAYERS` updated, match ended, `GAME_OVER`; host hand-over on quit |
| 14 | Starting another game after completion | PASS | Host restarts; scores reset to 0; new player may join after game over |

### Not tested

- Disconnect of one player in a 3+ player game while the remaining players have all answered (early round close inside the disconnect handler).
- Host disconnecting *during* an active game (tested in the lobby only).
- Clients on separate machines; all tests used `127.0.0.1`.
- The console `QuizClient` and the Swing GUI were not driven; the tests use raw sockets.

## Issues found

1. **Console client cuts off one error message** (low severity, `QuizClient`).
   The server sends `ERROR|Send ANSWER|round|A, B, C or D`. The message text contains `|`,
   so `QuizClient` (which prints `parts[1]`) shows only `[Error] Send ANSWER`.
   *Reproduce:* start a game, then from a raw TCP client (e.g. `telnet 127.0.0.1 5000`)
   send `ANSWER|9|A`. The server replies with the full line above, but `QuizClient` would
   display only the first field. Found by reading the code; the full raw server
   message was seen in the integration test. Member 2's GUI parser takes the whole
   payload after `ERROR|`, so the GUI is not affected.
   *Suggested fix (owner's choice):* reword the server message without `|`, or have
   `QuizClient` split `ERROR`/`INFO` lines with `split("\\|", 2)`.

2. **`SCORES` is broadcast twice after the final round** (cosmetic, `QuizServer`).
   `closeRoundLocked()` sends `SCORES`, then `finishGameLocked()` sends the same `SCORES`
   again before `GAME_OVER`. Found by code reading; harmless for both clients.

3. **Team decision, not a bug:** when a game ends because only one player is left, that
   player is announced as winner (`GAME_OVER|alice|10`). Confirm that this is the intended rule.

## Integration instructions for Member 1 (QuizServer owner)

`QuizServer.java` was **not** modified on this branch. To use the new classes, apply the
change below. It was tested on a scratch copy and all 14 integration scenarios still passed.
It changes no protocol messages and no game rules.

```diff
-    private static final int POINTS_PER_CORRECT = 10;
-
-    // Question bank
-    private static final List<Question> QUESTIONS = List.of(
-        ...the five hard-coded questions...
-    );
+    // Question bank (validated by QuestionBank)
+    private static final List<Question> QUESTIONS =
+            QuestionBank.defaultQuestions();
```

In `acceptAnswer(...)` (the answer has already been validated as `[ABCD]` above, so this never throws):

```diff
-            if (parts[2].equals(question.correctAnswer())) {
-
-                scores.merge(
-                        sender.username,
-                        POINTS_PER_CORRECT,
-                        Integer::sum);
-            }
+            scores.merge(
+                    sender.username,
+                    GameRules.pointsFor(question, parts[2]),
+                    Integer::sum);
```

In `finishGameLocked()`:

```diff
-        int best = scores.values().stream()
-                .mapToInt(Integer::intValue)
-                .max()
-                .orElse(0);
-
-        String winners = scores.entrySet().stream()
-                .filter(e -> e.getValue() == best)
-                .map(Map.Entry::getKey)
-                .sorted()
-                .collect(Collectors.joining(","));
+        int best = GameRules.topScore(scores);
+
+        String winners = String.join(",", GameRules.winners(scores));
```

In `scoresTextLocked()`:

```diff
-        return scores.entrySet().stream()
-                .sorted(
-                    Comparator
-                        .<Map.Entry<String, Integer>>comparingInt(
-                            Map.Entry::getValue)
-                        .reversed()
-                        .thenComparing(Map.Entry::getKey))
+        return GameRules.leaderboard(scores).stream()
                 .map(e -> e.getKey() + ":" + e.getValue())
                 .collect(Collectors.joining(","));
```

After integrating, rerun `QuizServerIntegrationTest` to confirm.
