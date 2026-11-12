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

## Test results

| Suite | Type | Result |
|---|---|---|
| `QuestionBankTest` | Automated, plain Java | 30 / 30 passed |
| `GameRulesTest` | Automated, plain Java | 20 / 20 passed |
| `QuizServerIntegrationTest` | Automated, real server + raw TCP clients | 59 / 59 passed (14 / 14 scenarios) |
| GUI tests (`quiz.gui`, Member 2) | Automated, JUnit 5.13.4 | 389 / 389 passed |
| `FullAppGuiE2E` | Automated, real server + real Swing windows | 43 / 43 passed (13 / 13 scenarios) |

The 109 Member 3 checks (30 + 20 + 59) still pass with the integrated server.
Some GUI unit tests print stack traces on purpose ("listener bug (expected in this test)");
they are not failures.

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

The console `QuizClient` was also smoke-tested against the integrated server (join, host, start refused with one player).

### Not tested

- Manual testing by a person clicking through the GUI (all GUI checks above are automated).
- Clients on separate machines (everything used `127.0.0.1`).
- `mvn test` (Maven is not installed on the test machine; JUnit was run with the console launcher).
- Disconnects in games with three or more players; host disconnecting during a game.

## Known issues

| # | Issue | Area | Owner |
|---|---|---|---|
| 1 | The console client shows `[Error] Send ANSWER` instead of the full message, because the server text `ERROR\|Send ANSWER\|round\|A, B, C or D` contains `\|`. The GUI shows it correctly. | `QuizClient` / `QuizServer` | Member 1 |
| 2 | `SCORES` is sent twice after the final round (`closeRoundLocked` and `finishGameLocked`). Cosmetic. | `QuizServer` | Member 1 |
| 3 | When the opponent leaves mid-game, the remaining player gets "You win! Congratulations!" after only one or two rounds, and the screen still says "5 questions". Needs a team decision: keep, or show "match ended" instead. | Server rule + GUI | Members 1 and 2 |
| 4 | `QuizGuiApp` Javadoc says to run from `target/classes` (Maven only); the README now gives the plain `javac` commands. | GUI docs | Member 2 |
| 5 | `FullAppGuiE2E` and `QuizServerIntegrationTest` need port 5000 free because the server port is fixed. | Tests | — |
