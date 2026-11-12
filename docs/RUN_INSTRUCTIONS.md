# Running the Java Multiplayer Quiz on Windows

A step-by-step guide for running the quiz on a Windows laptop: one **server** and two
**players** (Swing GUI windows). No build tool is needed — only the Java Development Kit (JDK).

All commands below are typed in **PowerShell** (the default terminal in VS Code on Windows).

## Contents

1. [Prerequisites](#1-prerequisites)
2. [Opening the project](#2-opening-the-project)
3. [Checking Java](#3-checking-java)
4. [Compiling the application](#4-compiling-the-application)
5. [Starting the server](#5-starting-the-server)
6. [Starting Player 1](#6-starting-player-1)
7. [Starting Player 2](#7-starting-player-2)
8. [Playing the quiz](#8-playing-the-quiz)
9. [Stopping the application](#9-stopping-the-application)
10. [Troubleshooting](#10-troubleshooting)
11. [Playing on two computers](#11-playing-on-two-computers)

## 1. Prerequisites

| Software | Notes |
|---|---|
| **Windows 10 or 11** | |
| **JDK 21** | The Java Development Kit, version 21 (for example Oracle JDK 21 or Eclipse Temurin 21). The JDK includes `javac`, the compiler; a JRE alone is not enough. |
| **VS Code** | With the **Extension Pack for Java** (by Microsoft) installed from the Extensions view (Ctrl+Shift+X). |
| **Git** | Git for Windows, used to download (clone) the project. |

> VS Code's Java extension may bring its own Java runtime for the editor. That does not matter:
> the commands in this guide use the JDK found on your `PATH`, which must be **version 21**
> (see [section 3](#3-checking-java)).

## 2. Opening the project

### Clone the repository (first time only)

Skip this if you already have the project folder.

1. Open PowerShell (press **Start**, type `PowerShell`, press Enter).
2. Go to the folder where you want the project, for example your Desktop:

   ```powershell
   cd $HOME\Desktop
   ```

3. Download the project and enter its folder:

   ```powershell
   git clone https://github.com/JanudaG/java-multiplayer-quiz.git
   cd java-multiplayer-quiz
   ```

If you already cloned it earlier, get the latest version from inside the project folder with:

```powershell
git pull
```

### Open it in VS Code

- In VS Code choose **File → Open Folder…** and select the `java-multiplayer-quiz` folder, **or**
- from PowerShell inside the project folder, run `code .`

The project folder (the "repository root") is the one that contains `README.md`, `src` and `docs`.

### Open a PowerShell terminal

1. In VS Code choose **Terminal → New Terminal** (shortcut: **Ctrl+`**, the key left of `1`).
2. The terminal opens at the bottom, already in the project folder. Check that the label on the
   right of the terminal panel says **powershell**. If it says something else, click the **˅**
   arrow next to **+** and choose **PowerShell**.
3. To open more terminals later, click **+** in the terminal panel. You will need **three**:
   one for the server and one for each player.

## 3. Checking Java

In the terminal, run:

```powershell
java -version
javac -version
```

Both must report version **21**, for example:

```
java version "21.0.12.1" 2026-08-18 LTS
javac 21.0.12.1
```

If either command is "not recognized" or shows a different version, see
[Troubleshooting](#java-or-javac-is-not-recognized-or-shows-the-wrong-version).

## 4. Compiling the application

From the repository root, run this **one** command:

```powershell
javac -encoding UTF-8 -d out/main (Get-ChildItem -Recurse -Filter *.java src).FullName
```

- It compiles every Java file under `src` into the `out\main` folder (created automatically).
- **No output means success.** It takes a few seconds.
- Compile again whenever you change the code or run `git pull`.

## 5. Starting the server

In **terminal 1**:

```powershell
java -cp out/main quiz.QuizServer
```

You should see:

```
Quiz server listening on port 5000
```

Leave this terminal open — the server keeps running and prints a line when a player disconnects.

> The first time, **Windows Defender Firewall** may ask whether to allow Java to communicate on
> networks. For playing on one laptop you can cancel; for [two computers](#11-playing-on-two-computers)
> allow it on **Private networks**.

## 6. Starting Player 1

Open **terminal 2** (click **+** in the terminal panel) and run:

```powershell
java -cp out/main quiz.gui.ui.QuizGuiApp
```

1. A **Choose a Theme** window opens. Pick **Modern Dark** or **Modern Light** and click **Continue**.
2. The **Join a quiz game** screen opens. Enter:

   | Field | Value |
   |---|---|
   | Server address | `127.0.0.1` (already filled in) |
   | Port | `5000` (already filled in) |
   | Username | `Alice` |

3. Click **Connect**. Alice enters the **Lobby** and is marked **Host**, because she joined first.

## 7. Starting Player 2

Open **terminal 3** and run the same command:

```powershell
java -cp out/main quiz.gui.ui.QuizGuiApp
```

1. Choose a theme and click **Continue**.
2. Enter:

   | Field | Value |
   |---|---|
   | Server address | `127.0.0.1` |
   | Port | `5000` |
   | Username | `Bob` |

3. Click **Connect**. Both windows now show **Alice** and **Bob** in the lobby.

The two windows may open on top of each other; drag one aside so you can see both.

## 8. Playing the quiz

### Starting a game

- The **first player to connect is the host** (Alice). Only the host can start a game.
- At least **two players** are needed. While the host is alone, **Start Game** is disabled.
- When both players are in the lobby, **Alice clicks Start Game**.

### Answering questions

- Every game has **five questions**. Both players see the same question at the same time.
- Each question has **four choices (A, B, C, D)** and a **15-second** countdown.
- Click a choice, then click **Submit Answer**. You can change your choice until you submit;
  after that your answer is final for that round.
- The round ends when the time runs out or as soon as both players have answered. The correct
  answer and the scores are shown for about 3 seconds, then the next question appears.

### Scoring and winners

- A **correct answer earns 10 points**. A wrong answer, or no answer before the deadline, earns 0.
- The leaderboard lists the highest score first (equal scores are listed alphabetically).
- After the fifth question the **Game Over** screen shows the winner. The **highest score wins**,
  and **ties are supported**: if both players have the same score, both are winners
  ("It's a tie!").
- **Disconnects:** if a player leaves during a game (clicks **Leave Game**, closes the window, or
  loses the connection) and only one player remains, the match ends immediately and the
  **remaining player wins**, whatever their score.

### Playing again

- On the **Game Over** screen the host clicks **Play Again** to start a new game with everyone in
  the lobby. All scores start again at 0.
- Other players see "Waiting for the host" until the host starts.
- **Play Again** needs at least two connected players. If someone left, start another player
  ([section 7](#7-starting-player-2)) and the host can start again. New players can join only
  between games, not while a game is running.

## 9. Stopping the application

1. Close both player windows (click the window's **X**). If a player is still connected, a
   **Leave Game?** question appears — click **Yes**.
2. In the **server** terminal (terminal 1), press **Ctrl+C** to stop the server.
3. You can close the extra terminals with the trash-can icon in the terminal panel.

## 10. Troubleshooting

### `java` or `javac` is not recognized, or shows the wrong version

Message: `The term 'java' is not recognized as the name of a cmdlet...` or a version other than 21.

1. Install **JDK 21** (not only a JRE).
2. Add its `bin` folder to `PATH`: press **Start**, type **environment variables**, open
   **Edit the system environment variables → Environment Variables…**, select **Path**,
   click **Edit → New**, and add the JDK's `bin` folder, for example
   `C:\Program Files\Java\jdk-21\bin` (use your actual folder name). Use **Move Up** so it comes
   before any other Java entry.
3. **Close and reopen VS Code** (and all terminals), then run `java -version` and
   `javac -version` again.

### Compilation errors

| Message | Fix |
|---|---|
| `File not found - *.java` and `error: no source files` | You are not in the repository root, or you used a Git Bash command. Run `cd` to the `java-multiplayer-quiz` folder and use the PowerShell command from [section 4](#4-compiling-the-application). |
| Errors mentioning preview features, `release version`, or unknown syntax | Your `javac` is older than 21. Check `javac -version` and fix `PATH` (above). |
| `Could not find or load main class quiz.QuizServer` (or `quiz.gui.ui.QuizGuiApp`) | Compile first, and run the command from the repository root so that `out/main` is found. Check the spelling: the names are case-sensitive. |

### Port 5000 is already in use

The server prints `Server error: Address already in use: bind` and stops.
Another server (often one you started earlier in another terminal) is still running.

- Look in your other terminals for a running server and press **Ctrl+C** there, **or**
- find and stop the program that owns port 5000:

  ```powershell
  Get-NetTCPConnection -LocalPort 5000 | Select-Object LocalAddress, State, OwningProcess
  Stop-Process -Id <OwningProcess>
  ```

  Replace `<OwningProcess>` with the number shown. Only do this if it is your quiz server.

### Connection refused

The player window shows **Could not connect** with
`Could not connect to 127.0.0.1:5000 (Connection refused: getsockopt)`.

- The server is not running. Start it first ([section 5](#5-starting-the-server)) and check that
  it printed `Quiz server listening on port 5000`, then click **Connect** again.
- Check that the port is `5000`.

### Incorrect server IP address

If the server address is wrong or unreachable, the player window shows **Could not connect**
after about 5 seconds, for example
`Could not connect to 192.168.1.99:5000 (Connect timed out)`.

- On the same laptop, use `127.0.0.1`.
- On another computer, use the server laptop's IPv4 address
  ([section 11](#11-playing-on-two-computers)) — not `127.0.0.1`, which always means "this computer".

### Username problems

| Message | Fix |
|---|---|
| `Username already taken` | Another connected player has that name (for example two players called `Alice`). Choose a different username. |
| `Please enter a username.`, `Use only letters, digits, _ or - in the username.` or `Username must be at most 16 characters.` | Use 1–16 characters: letters, digits, `_` or `-` only (no spaces). |
| `Game already running; join after it finishes` | A game is in progress. Wait until it ends, then click **Connect** again. |

### Cannot start the game

- **Start Game** is disabled and the host sees **Waiting for more players**: at least two
  players must be connected. Start the second player ([section 7](#7-starting-player-2)).
- Bob has no **Start Game** button enabled: only the host (the first player who joined) can start.
  If the host leaves, the next player who joined becomes the host.

## 11. Playing on two computers

Both computers must be on the **same network** (for example the same Wi-Fi) and have the project
and **JDK 21** set up as in sections 1–4.

1. **On the server laptop**, find its local IPv4 address:

   ```powershell
   ipconfig
   ```

   Under your active adapter (for example **Wireless LAN adapter Wi-Fi**), note the
   **IPv4 Address**, for example `192.168.1.23`.

2. **On the server laptop**, start the server ([section 5](#5-starting-the-server)). When Windows
   Defender Firewall asks, allow Java on **Private networks**. If you dismissed that prompt, open
   **Windows Security → Firewall & network protection → Allow an app through firewall**, and allow
   Java on **Private** networks (it is listed as **Java(TM) Platform SE binary** for Oracle JDK, or
   **OpenJDK Platform binary** for other JDKs).

3. **Players** start the GUI on either computer
   ([sections 6](#6-starting-player-1) and [7](#7-starting-player-2)):
   - on the **server laptop**, Server address `127.0.0.1`
   - on the **other computer**, Server address = the IPv4 address from step 1 (for example `192.168.1.23`)
   - Port `5000` and a **different username** on each.

If the other computer cannot connect:

- Check both computers are on the same network, and that the network is set to **Private**
  in Windows settings (public Wi-Fi networks often block connections between devices).
- Check the firewall permission in step 2.
- Check the IPv4 address again — it can change after reconnecting to Wi-Fi.

For automated tests, the network protocol and project details, see the [README](../README.md).
