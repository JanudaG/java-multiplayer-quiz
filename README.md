# Java Multiplayer Quiz Game

A real-time multiplayer quiz game written in Java. One **server** runs the game over TCP;
players connect with a **Swing GUI client** (or a simple console client), answer the same
timed multiple-choice questions at the same time, and compete on a live leaderboard.

The server is the single authority for the game: it keeps the players, scores, rounds and
deadlines. Clients only display what the server sends and send the player's actions.

## Quick Start

New to the project or running it on Windows? Follow the step-by-step guide
**[docs/RUN_INSTRUCTIONS.md](docs/RUN_INSTRUCTIONS.md)**. It covers installing JDK 21, compiling, starting the
server and two players, playing, troubleshooting, and playing on two computers.

In short (JDK 21, PowerShell, from the repository root):

```powershell
javac -encoding UTF-8 -d out/main (Get-ChildItem -Recurse -Filter *.java src).FullName
java -cp out/main quiz.QuizServer          # terminal 1
java -cp out/main quiz.gui.ui.QuizGuiApp   # terminals 2 and 3: one per player
```

## Main features

- Lobby with a host: the first player to join is the host and is the only one who can start a game
- At least two players are needed to start
- Five multiple-choice questions (A–D), 15 seconds each, with a live countdown in the GUI
- 10 points per correct answer; round results and leaderboard after every question
- Winner or tie announced at the end; the host can start a new game ("Play Again")
- Duplicate usernames and joins during a running game are rejected
- Disconnects handled during the lobby and during a game (see [Disconnects](#disconnects))
- Swing GUI with a theme picker (dark and light), lobby, quiz, round-results and game-over screens
- Console client for quick testing without the GUI

## Technologies

- **Java 21** (records, switch expressions, pattern matching)
- **TCP sockets** (`java.net.ServerSocket` / `Socket`) with a line-based text protocol
- **Multithreading**: one thread per client on the server, a scheduled executor for deadlines,
  a background reader thread in each client
- **Java Swing** for the GUI client
- **JUnit 5** for the GUI tests only (optional; not needed to build or run the game)
- **Maven** (optional) via `pom.xml`

## Project structure

```
java-multiplayer-quiz/
├── src/quiz/
│   ├── QuizServer.java        Game server: lobby, rounds, deadlines, scoring (Member 1)
│   ├── QuizClient.java        Console client (Member 1)
│   ├── Question.java          Question record and its QUESTION message (Member 1)
│   ├── QuestionBank.java      The five questions and their validation (Member 3)
│   ├── GameRules.java         Points, winners and leaderboard order (Member 3)
│   └── gui/                   Swing client (Member 2)
│       ├── controller/        GameController: connects the UI to the server
│       ├── model/             GameState, leaderboard and question data
│       ├── net/               ServerConnection: socket reader/writer threads
│       ├── protocol/          Parses server messages, builds client commands
│       ├── theme/             Dark and light themes
│       └── ui/                QuizGuiApp (entry point), screens and components
├── test/quiz/                 Plain-Java tests: question bank, rules, server, console client (Member 3)
├── test/quiz/gui/             JUnit 5 GUI tests (Member 2) and the full-app GUI end-to-end test
├── docs/                      Testing and integration reports
├── pom.xml                    Optional Maven build
└── README.md
```

## Requirements

- **JDK 21** — check with `java -version` and `javac -version` (both should say 21)
- Nothing else is needed to compile and run the game
- TCP port **5000** free on the server machine
- For the optional tests: the JUnit console launcher jar (download command below) and a desktop
  session for the GUI tests

## Compile

Run from the repository root. Compiled classes go to `out/main` (git-ignored).

**PowerShell (Windows)**

```powershell
javac -encoding UTF-8 -d out/main (Get-ChildItem -Recurse -Filter *.java src).FullName
```

**Git Bash / macOS / Linux**

```bash
javac -encoding UTF-8 -d out/main $(find src -name '*.java')
```

**Maven (optional)**: `mvn compile` puts the classes in `target/classes`; use
`-cp target/classes` instead of `-cp out/main` in the commands below.

## Run the game

Use a separate terminal for the server and for each client.

**1. Start the server**

```bash
java -cp out/main quiz.QuizServer
```

It prints `Quiz server listening on port 5000` and keeps running until you press Ctrl+C.

**2. Start two Swing GUI clients** (one per player, in two more terminals)

```bash
java -cp out/main quiz.gui.ui.QuizGuiApp
java -cp out/main quiz.gui.ui.QuizGuiApp
```

In each window:

1. Pick a theme and continue.
2. Enter the server host (`127.0.0.1` when the server is on the same computer, otherwise the
   server's IP address), port `5000`, and a username.
3. Press **Connect**. The first player becomes the host.
4. When at least two players are in the lobby, the host presses **Start Game**.
5. Choose an answer and press **Submit Answer** before the countdown ends.
6. After the last question the host can press **Play Again**.

**Console client (optional)**

```bash
java -cp out/main quiz.QuizClient [server-host]
```

Type a username, then `/start` (host only), `A`/`B`/`C`/`D` to answer, or `/quit`.

## Game rules

- **Usernames**: 1–16 characters, using letters, digits, `_` or `-`; each name must be unique.
- **Host**: the first player in the lobby. If the host leaves, the next player who joined becomes host.
- **Starting**: only the host can start, and only with at least two players. Players cannot join
  while a game is running; they can join again when it is over.
- **Rounds**: every player receives the same question at the same time. Each player can submit
  **one** answer per round (in the GUI you can change your choice until you press Submit).
- **Deadline**: each question lasts **15 seconds**, enforced by the server. A round ends when the
  time runs out or as soon as every player has answered. Results are shown for 3 seconds before
  the next question.

### Scoring and winners

- Correct answer: **10 points**. Wrong answer or no answer: **0 points**.
- After every round the server sends the correct answer and the leaderboard.
- The leaderboard is ordered by **score (highest first)**, then by **username (A–Z)** for equal scores.
- At the end, **every player with the highest score wins** — a tie produces several winners
  (for example `GAME_OVER|alice,bob|30`).
- **Play Again** starts a new game with everyone in the lobby; all scores are reset to 0.

### Disconnects

- **In the lobby**: the player is removed from the list; if it was the host, the next player becomes host.
- **During a game, with two or more players left**: the game continues without that player, who
  is removed from the leaderboard. If everyone left has already answered, the round ends at once.
- **During a game, with only one player left**: the match ends immediately and the **remaining player
  is declared the winner, regardless of their current score**. This is an intentional team rule.
  The server sends `INFO|Match ended: not enough players`, the final `SCORES`, then `GAME_OVER`.
  The GUI shows the game-over screen; **Play Again** becomes available once another player joins.

## Network protocol

UTF-8 text over TCP, one message per line, fields separated by `|`. Free text (in `INFO` and
`ERROR`) is everything after the first `|` and may itself contain `|`.

| Direction | Messages |
|---|---|
| Client → server | `JOIN\|name`, `START`, `ANSWER\|round\|A-D`, `QUIT` |
| Server → client | `JOINED\|name`, `PLAYERS\|a,b`, `HOST\|name`, `INFO\|text`, `GAME_STARTED\|5 questions`, `QUESTION\|round\|total\|seconds\|text\|A\|B\|C\|D`, `ANSWER_ACCEPTED\|round`, `ROUND_RESULT\|round\|X`, `SCORES\|name:points,...`, `GAME_OVER\|winner1,winner2\|points`, `ERROR\|text` |

After each round the server sends `ROUND_RESULT` then `SCORES`; after the final round it sends
`ROUND_RESULT`, the final `SCORES` once, then `GAME_OVER`.

## Automated tests

Compile the sources first (see [Compile](#compile)). The GUI tests use JUnit 5, so download the
JUnit console launcher once into `lib/` (git-ignored):

```bash
curl -L -o lib/junit-platform-console-standalone-1.13.4.jar --create-dirs https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.13.4/junit-platform-console-standalone-1.13.4.jar
```

(In Windows PowerShell 5.1 type `curl.exe` instead of `curl`.)

**Compile the tests** (the classpath separator is `;` on Windows, `:` on macOS/Linux):

```powershell
# PowerShell
javac -encoding UTF-8 -cp "out/main;lib/junit-platform-console-standalone-1.13.4.jar" -d out/test (Get-ChildItem -Recurse -Filter *.java test).FullName
```

```bash
# Git Bash on Windows
javac -encoding UTF-8 -cp "out/main;lib/junit-platform-console-standalone-1.13.4.jar" -d out/test $(find test -name '*.java')
```

**Run the tests** (stop any running `QuizServer` first — several tests use port 5000):

```bash
java -cp "out/main;out/test" quiz.QuestionBankTest            # question data and validation
java -cp "out/main;out/test" quiz.GameRulesTest               # points, winners, leaderboard
java -cp "out/main;out/test" quiz.QuizServerIntegrationTest   # real server, 14 multiplayer scenarios (~45 s)
java -cp "out/main;out/test" quiz.QuizClientConsoleTest       # console client output
java -jar lib/junit-platform-console-standalone-1.13.4.jar execute --class-path "out/main;out/test" --select-package quiz.gui
java -cp "out/main;out/test" quiz.gui.ui.FullAppGuiE2E [screenshot-dir]   # real server + real GUI windows (~45 s)
```

The plain-Java tests print `PASS`/`FAIL` for every check and exit with status 1 if any check
fails (2 if they cannot run, for example when port 5000 is busy). `FullAppGuiE2E` opens up to
four windows while it runs; do not click them. Some JUnit GUI tests print stack traces on
purpose ("expected in this test"); they are not failures.

With Maven installed, `mvn test` runs only the JUnit tests. Latest results are in
[docs/integration-report.md](docs/integration-report.md).

## Troubleshooting

| Problem | Cause and fix |
|---|---|
| `'javac' is not recognized` / `command not found` | The JDK is not on `PATH`. Install JDK 21 and add its `bin` folder to `PATH`. |
| `UnsupportedClassVersionError` | Classes were compiled with a newer JDK than the one running them. Use JDK 21 for both, then recompile. |
| `File not found - *.java` and `javac: error: no source files` in PowerShell | `$(find ...)` is Git Bash syntax; in PowerShell use the `Get-ChildItem` command shown above. |
| `package org.junit.jupiter.api does not exist` | The JUnit jar is missing; run the download command, or compile only `src` to play the game. |
| `Server error: Address already in use: bind` | Another program (often an earlier server) uses port 5000. Close it, or on Windows find it with `netstat -ano \| findstr :5000` and stop it with `taskkill /PID <pid> /F`. |
| `ABORT: port 5000 is in use` / `CANNOT RUN` from a test | Stop the running `QuizServer` before running the tests; `FullAppGuiE2E` also needs a desktop (not headless). |
| `Could not connect: Connection refused` / GUI cannot connect | Start the server first and check the host and port `5000`. For other computers, use the server's IP address and allow port 5000 in its firewall. |
| `Username already taken` | Another player in the lobby uses that name; choose another. |
| `Use a username of 1-16 letters, digits, _ or -` | The name contains spaces or other characters. |
| `Game already running; join after it finishes` | Wait for the current game to end, then connect again. |
| `Only the host can start` / Start button disabled | Only the host can start, and only with at least two players. |
| `No active question or time has expired` | The answer arrived after the 15-second deadline or between rounds. |
| `ClassNotFoundException: quiz.gui.ui.QuizGuiApp` | Wrong classpath: compile first and run from the repository root with `-cp out/main`. |

## Development team

| Member | Responsibility |
|---|---|
| Member 1 | Server, lobby and quiz engine (`QuizServer`, `QuizClient`, `Question`) |
| Member 2 | Swing GUI client (`src/quiz/gui`, `test/quiz/gui`) |
| Member 3 | Question bank, game rules and testing (`QuestionBank`, `GameRules`, `test/quiz`) |
