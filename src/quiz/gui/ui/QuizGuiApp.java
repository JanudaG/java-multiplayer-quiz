package quiz.gui.ui;

import java.util.Optional;

import javax.swing.SwingUtilities;

import quiz.gui.controller.GameController;
import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;

/**
 * Entry point of the Swing client.
 *
 * <ol>
 *   <li>Everything runs on the Swing event dispatch thread (EDT).</li>
 *   <li>The theme selection dialog is shown first, in the default theme.</li>
 *   <li>If the user cancels, nothing else is opened and the application exits.</li>
 *   <li>Otherwise the chosen theme is applied and the main window opens.</li>
 * </ol>
 * No network connection is made at startup: the {@link GameController} only connects
 * when a screen asks it to.
 *
 * <p>Build and run with JDK 21 from the repository root (start {@code quiz.QuizServer} first):</p>
 * <ul>
 *   <li>Plain javac (Git Bash / macOS / Linux):
 *       {@code javac -encoding UTF-8 -d out/main $(find src -name '*.java')}, then
 *       {@code java -cp out/main quiz.gui.ui.QuizGuiApp}</li>
 *   <li>Plain javac (PowerShell):
 *       {@code javac -encoding UTF-8 -d out/main (Get-ChildItem -Recurse -Filter *.java src).FullName},
 *       then {@code java -cp out/main quiz.gui.ui.QuizGuiApp}</li>
 *   <li>Maven: {@code mvn compile}, then {@code java -cp target/classes quiz.gui.ui.QuizGuiApp}</li>
 * </ul>
 */
public final class QuizGuiApp {

    private QuizGuiApp() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(QuizGuiApp::start);
    }

    /** Must run on the EDT. */
    private static void start() {
        ThemeManager.apply(Theme.DEFAULT);
        Optional<Theme> chosen = ThemeSelectionDialog.showDialog();
        if (chosen.isEmpty()) {
            // The dialog was cancelled or closed. No window is left open, so the JVM exits normally.
            return;
        }

        ThemeManager.apply(chosen.get());
        GameController controller = new GameController();
        MainFrame frame = new MainFrame(controller);
        frame.registerScreen(ScreenNavigator.CONNECTION, new ConnectionScreen(controller));
        frame.registerScreen(ScreenNavigator.LOBBY, new LobbyScreen(controller));
        frame.registerScreen(ScreenNavigator.QUIZ, new QuizScreen(controller));
        frame.registerScreen(ScreenNavigator.RESULTS, new RoundResultsScreen(controller));
        frame.registerScreen(ScreenNavigator.GAME_OVER, new GameOverScreen(controller));
        // Register every screen before the navigator starts.
        ScreenNavigator.install(frame); // shows the connection screen first
        frame.setVisible(true);
    }
}
