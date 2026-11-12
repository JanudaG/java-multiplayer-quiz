# Integration Report — `integration/full-app-test`

Baseline: `main` @ `90a4cf2`. JDK 21.0.12, Windows 11. Prepared by Member 3.

## Branches

| Branch | Status |
|---|---|
| `feature/quiz-engine` | Already in `main` (same commit `90a4cf2`); not merged again |
| `feature/lobby` | Already in `main` via PR #1; not merged again |
| `feature/member-2-client-gui` (`fc9783c`) | Merged; no conflicts |
| `feature/question-bank-tests` (`d9d8add`) | Merged; no conflicts |

The two merged branches only add new files and share none, so no conflicts arose.

## Changes made during integration (need review)

| File | Owner | Change | Reviewer |
|---|---|---|---|
| `src/quiz/QuizServer.java` | Member 1 | Uses `QuestionBank.defaultQuestions()` for questions and `GameRules` for points, winners and leaderboard (the steps in `docs/member3-testing-report.md`). Questions, scoring, deadlines and every protocol message are unchanged. | **Member 1** |
| `src/quiz/gui/controller/GameController.java` | Member 2 | `case ServerMessage.GameStarted _` changed to `GameStarted m`. The unnamed variable `_` needs Java 22+ and stopped the GUI from compiling on JDK 21. No behaviour change. | **Member 2** |
| `pom.xml` | Member 2 | `maven.compiler.release` 27 → 21 (JDK 21 cannot build release 27). | **Member 2** |
| `test/quiz/gui/ui/FullAppGuiE2E.java` | new | Full-application GUI end-to-end test (see below). It sits in Member 2's test package to use the screens' package-private accessors; it does not change any GUI code. | Member 2 (FYI) |
| `README.md`, `.gitignore`, `docs/integration-report.md` | shared | Build/run/test instructions for JDK 21; `lib/` ignored for the downloaded JUnit jar. | All |

Member 2 should also apply the `GameController`/`pom.xml` fixes on their own branch, or accept them through this integration.

## Bug fixes and documentation (second integration round)

| File | Owner | Change | Reviewer |
|---|---|---|---|
| `src/quiz/QuizClient.java` | Member 1 | **Issue 1 fixed.** `INFO`, `ERROR` and other single-field messages now print everything after the first `\|`, so `ERROR\|Send ANSWER\|round\|A, B, C or D` is shown in full (as is `ERROR\|First message must be JOIN\|username`). A malformed line is now printed raw instead of crashing the listener thread; before, a bare `ERROR` line stopped the client from showing any later message. Output wording and the protocol are unchanged. | **Member 1** |
| `src/quiz/QuizServer.java` | Member 1 | **Issue 2 fixed.** `closeRoundLocked()` no longer sends `SCORES` after the last round; `finishGameLocked()` sends the final leaderboard once, then `GAME_OVER`. Message order, scores, ties and the disconnect path are unchanged. Comment documents the disconnect winner rule. | **Member 1** |
| `src/quiz/gui/ui/QuizGuiApp.java` | Member 2 | **Issue 4 fixed** (comment only): run instructions now cover plain `javac` (Git Bash and PowerShell) and Maven, for JDK 21. | **Member 2** |
| `src/quiz/GameRules.java` | Member 3 | Comment only: documents the disconnect winner rule. | — |
| `test/quiz/QuizClientConsoleTest.java` | new | Regression test: runs the real `QuizClient` against a scripted fake server and checks its output for every message type, including the Issue 1 message and malformed lines. | Member 1 (FYI) |
| `test/quiz/QuizServerIntegrationTest.java` | Member 3 | Regression checks: after the final round exactly one `SCORES` then `GAME_OVER` (tie and single-winner games); the disconnect path still sends one final `SCORES` before the remaining player's `GAME_OVER`. | — |
| `README.md`, `docs/member3-testing-report.md` | shared | README rewritten for the finished project (features, rules, scoring, disconnects, tests, troubleshooting); status note added to the Member 3 report. | All |

**Confirmed team rule (Issue 3, unchanged on purpose):** if a player disconnects during a game
and only one player remains, the match ends and the remaining player is declared the winner,
regardless of their current score.

Both fixes were test-first: before the code change, `QuizClientConsoleTest` failed 4 checks
(truncated error, and the listener died on a bare `ERROR` line) and `QuizServerIntegrationTest`
failed 2 checks (`SCORES` received twice before `GAME_OVER`). After the change both suites pass.

## Test results

Latest run: clean rebuild with JDK 21.0.12 (37 source files, 30 test files) after the bug
fixes, on Windows 11, port 5000 confirmed free before the run and no server left afterwards.

| Suite | Type | Result |
|---|---|---|
| `QuestionBankTest` | Automated, plain Java | 30 / 30 passed |
| `GameRulesTest` | Automated, plain Java | 20 / 20 passed |
| `QuizServerIntegrationTest` | Automated, real server + raw TCP clients | 59 / 59 passed (14 / 14 scenarios) |
| `QuizClientConsoleTest` (new) | Automated, real console client + scripted server | 19 / 19 passed |
| GUI tests (`quiz.gui`, Member 2) | Automated, JUnit 5.13.4 | 389 / 389 passed |
| `FullAppGuiE2E` | Automated, real server + real Swing windows | 43 / 43 passed (13 / 13 scenarios) |

The 109 Member 3 checks (30 + 20 + 59) still pass with the integrated server.
Some GUI unit tests print stack traces on purpose ("listener bug (expected in this test)");
they are not failures.

Fix verification in this run:

- **Issue 1:** the console client printed `[Error] Send ANSWER|round|A, B, C or D` in full, and kept
  running after malformed lines.
- **Issue 2:** after the final round each client received exactly `SCORES` then `GAME_OVER`
  (tie game `SCORES|alice:30,bob:30` → `GAME_OVER|alice,bob|30`; single winner
  `SCORES|alice:50,dave:0` → `GAME_OVER|alice|50`).
- **Rules unchanged:** 10 / 0 points, 15 s deadline (measured 15.0 s server-side, 15.1 s in the GUI),
  host-only start, two-player minimum, tie handling, score reset on replay, and the disconnect rule
  (`SCORES|alice:10` → `GAME_OVER|alice|10` for the remaining player).

### Full application (GUI end-to-end)

`FullAppGuiE2E` starts the real server, opens two real GUI windows and plays two games by
clicking the real buttons; extra windows test rejected joins.

| Scenario | Result |
|---|---|
| Lobby joining | PASS |
| Host selection (incl. host hand-over when the host leaves) | PASS |
| Two-player minimum (Start disabled when alone) | PASS |
| Duplicate username | PASS |
| Starting games (same `QuestionBank` question on both windows) | PASS |
| Mid-game join rejected | PASS |
| Countdown (15 s, ticking; unanswered round closed at ~15 s) | PASS |
| Answering (second submit blocked; results screen) | PASS |
| Scoring after every round | PASS |
| Leaderboard order, including ties | PASS |
| Winners and ties ("It's a tie!", 30 points each) | PASS |
| Replay (Play Again only for host; scores reset) | PASS |
| Disconnect handling (Leave Game mid-game, rejoin afterwards) | PASS |

The console `QuizClient` was smoke-tested against the integrated server (join, host, start
refused with one player) and is covered by `QuizClientConsoleTest`.

### Not tested

- Manual testing by a person clicking through the GUI (all GUI checks above are automated).
- Clients on separate machines (everything used `127.0.0.1`).
- `mvn test` (Maven is not installed on the test machine; JUnit was run with the console launcher).
- Disconnects in games with three or more players; host disconnecting during a game.

## Known issues

| # | Issue | Status |
|---|---|---|
| 1 | Console client truncated `ERROR\|Send ANSWER\|round\|A, B, C or D` to `[Error] Send ANSWER`. | **Fixed** in `QuizClient`; regression test `QuizClientConsoleTest` |
| 2 | `SCORES` sent twice after the final round. | **Fixed** in `QuizServer`; regression checks in `QuizServerIntegrationTest` |
| 3 | Remaining player wins when the opponent disconnects mid-game. | **Intended** — confirmed team rule; documented in README and code |
| 4 | `QuizGuiApp` comment gave Maven-only run instructions. | **Fixed** (comment only) |
| 5 | `QuizServerIntegrationTest`, `QuizClientConsoleTest` and `FullAppGuiE2E` need port 5000 free because the port is fixed; they exit with status 2 if it is busy. | Open (by design) |
| 6 | On the game-over screen after a disconnect win, the subtitle still says "5 questions" although fewer rounds were played. | Open, cosmetic (Member 2) |
