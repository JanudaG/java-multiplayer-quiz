# Java Multiplayer Quiz Game

A real-time multiplayer quiz game built with Java TCP sockets, multithreading and Swing.
One server hosts the game; players join from the Swing GUI (or the console client),
answer the same timed questions and compete on a live leaderboard.

## Features

- Lobby with a host (the first player to join); only the host can start, with at least two players
- Five multiple-choice questions, 15 seconds each, enforced by the server
- 10 points per correct answer, 0 otherwise; leaderboard after every round
- Winner or tie announced at the end; the host can start another game
- Duplicate usernames and joins during a running game are rejected
- If a player disconnects and fewer than two remain, the match ends
- Swing GUI with themes, a countdown, round results and a final leaderboard

## Requirements

- **JDK 21** (`java -version` and `javac -version` should both report 21)
- No build tool or external libraries are needed to compile and run the game
- Port **5000** free on the server machine

## Project structure

```
src/quiz/                 QuizServer, QuizClient (console), Question,
                          QuestionBank, GameRules
src/quiz/gui/             Swing client: controller, model, net, protocol, theme, ui
test/quiz/                Plain-Java self-tests and server integration test
test/quiz/gui/            JUnit 5 GUI tests and the full-app GUI end-to-end test
docs/                     Testing and integration reports
pom.xml                   Optional Maven build (src/ and test/, JUnit 5)
```

## Compile

Run from the repository root.

**PowerShell (Windows)**

```powershell
javac -encoding UTF-8 -d out/main (Get-ChildItem -Recurse -Filter *.java src).FullName
```

**Git Bash / macOS / Linux**

```bash
javac -encoding UTF-8 -d out/main $(find src -name '*.java')
```

## Run

Start the server first, then each client in its own terminal:

```bash
java -cp out/main quiz.QuizServer          # server on port 5000
java -cp out/main quiz.gui.ui.QuizGuiApp   # Swing client (start one per player)
java -cp out/main quiz.QuizClient [host]   # optional console client (default host 127.0.0.1)
```

In the GUI, pick a theme, then enter the server host (`127.0.0.1` on the same machine),
port `5000` and a username (1–16 letters, digits, `_` or `-`). The first player becomes
the host and can press **Start Game** once a second player has joined.

Console client commands: `/start` (host), `A`/`B`/`C`/`D` to answer, `/quit`.

## Network protocol (UTF-8, one message per line, fields separated by `|`)

| Direction | Messages |
|---|---|
| Client → server | `JOIN\|name`, `START`, `ANSWER\|round\|A-D`, `QUIT` |
| Server → client | `JOINED\|name`, `PLAYERS\|a,b`, `HOST\|name`, `INFO\|text`, `GAME_STARTED\|5 questions`, `QUESTION\|round\|total\|seconds\|text\|A\|B\|C\|D`, `ANSWER_ACCEPTED\|round`, `ROUND_RESULT\|round\|X`, `SCORES\|name:points,...`, `GAME_OVER\|winner1,winner2\|points`, `ERROR\|text` |

## Tests

Compile the tests after compiling the sources (above). Member 2's GUI tests use JUnit 5,
so download the JUnit console launcher once into `lib/` (git-ignored):

```bash
curl -L -o lib/junit-platform-console-standalone-1.13.4.jar --create-dirs https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.13.4/junit-platform-console-standalone-1.13.4.jar
```

(In Windows PowerShell 5.1 type `curl.exe` instead of `curl`.)

Compile the tests. These commands use `;` in the classpath (Windows); use `:` on macOS/Linux.

```powershell
# PowerShell
javac -encoding UTF-8 -cp "out/main;lib/junit-platform-console-standalone-1.13.4.jar" -d out/test (Get-ChildItem -Recurse -Filter *.java test).FullName
```

```bash
# Git Bash on Windows
javac -encoding UTF-8 -cp "out/main;lib/junit-platform-console-standalone-1.13.4.jar" -d out/test $(find test -name '*.java')
```

Run them:

```bash
# Question bank, game rules and server integration (plain Java, no libraries)
java -cp "out/main;out/test" quiz.QuestionBankTest
java -cp "out/main;out/test" quiz.GameRulesTest
java -cp "out/main;out/test" quiz.QuizServerIntegrationTest   # ~45 s, needs port 5000 free

# GUI unit/component tests (JUnit 5)
java -jar lib/junit-platform-console-standalone-1.13.4.jar execute --class-path "out/main;out/test" --select-package quiz.gui

# Full application: real server + real GUI windows, clicked automatically
java -cp "out/main;out/test" quiz.gui.ui.FullAppGuiE2E [screenshot-dir]   # ~45 s, needs a desktop and port 5000
```

Each plain-Java test prints `PASS`/`FAIL` per check and exits with status 1 if anything fails.
`FullAppGuiE2E` opens up to four windows while it runs; do not click them.
See [docs/integration-report.md](docs/integration-report.md) for the latest results.

With Maven installed, `mvn test` compiles everything and runs the JUnit tests
(the plain-Java tests above are not run by Maven).

## Development team

| Member | Responsibility |
|---|---|
| Member 1 | Server, lobby and quiz engine (`QuizServer`, `QuizClient`, `Question`) |
| Member 2 | Swing GUI client (`src/quiz/gui`, `test/quiz/gui`) |
| Member 3 | Question bank, game rules and testing (`QuestionBank`, `GameRules`, `test/quiz`) |

## Status

Phases 1–3 (TCP communication, lobby, timed quiz engine), the Swing GUI and the question
bank/tests are integrated on `integration/full-app-test`. Known issues are listed in
[docs/integration-report.md](docs/integration-report.md).
