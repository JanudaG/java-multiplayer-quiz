package quiz.gui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static quiz.gui.ui.UiTestSupport.runOnEdt;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;
import javax.swing.JComponent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import quiz.gui.controller.GameController;
import quiz.gui.model.AnswerOption;
import quiz.gui.model.GamePhase;
import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;
import quiz.gui.ui.UiTestSupport.Client;
import quiz.gui.ui.UiTestSupport.LoopbackServer;
import quiz.gui.ui.UiTestSupport.StateWaiter;

/**
 * The quiz and results screens are laid out and painted off-screen in every theme:
 * painting must not fail, and the screens must use the theme's own colours (background,
 * cards, accent for the chosen answer, success for the correct answer). The images are
 * also written to {@code target/ui-snapshots} for a manual look.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class GameScreenThemeTest {

    private static final int WIDTH = 1024;
    private static final int HEIGHT = 660;

    @BeforeAll
    static void headless() {
        UiTestSupport.useHeadlessAwt();
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void gameScreensPaintInTheme(Theme theme) throws Exception {
        try (LoopbackServer server = new LoopbackServer()) {
            AtomicLong clock = new AtomicLong();
            GameController[] controller = new GameController[1];
            QuizScreen[] quiz = new QuizScreen[1];
            RoundResultsScreen[] results = new RoundResultsScreen[1];
            StateWaiter[] waiter = new StateWaiter[1];
            runOnEdt(() -> {
                ThemeManager.apply(theme);
                controller[0] = new GameController();
                quiz[0] = new QuizScreen(controller[0], clock::get);
                results[0] = new RoundResultsScreen(controller[0]);
                quiz[0].startListening();
                results[0].startListening();
                waiter[0] = new StateWaiter(controller[0]);
                assertEquals(theme.getBackground(), quiz[0].getBackground());
                assertEquals(theme.getBackground(), results[0].getBackground());
                controller[0].connect(server.host(), server.port(), "alice");
            });
            Client client = server.accept();
            client.expectLine("JOIN|alice");
            client.send("JOINED|alice", "PLAYERS|alice,bob,carol", "HOST|alice", "GAME_STARTED|5 questions",
                    QuizScreenTest.QUESTION_1);
            waiter[0].await("question", () -> controller[0].getState().getPhase() == GamePhase.QUESTION_ACTIVE);

            // Question with a chosen answer: the selected answer and Submit use the accent colour.
            runOnEdt(() -> {
                quiz[0].optionButton(AnswerOption.B).doClick(0);
                snapshot(quiz[0], theme, "quiz-selected", theme.getAccent(), theme.getSelection());
                quiz[0].submitButton().doClick(0);
            });
            client.expectLine("ANSWER|1|B");

            // Round result: the correct answer uses the success colour.
            client.send("ANSWER_ACCEPTED|1", "ROUND_RESULT|1|B", "SCORES|alice:1,bob:0,carol:0");
            waiter[0].await("scores", () -> controller[0].getState().getLeaderboard().size() == 3);
            runOnEdt(() -> snapshot(results[0], theme, "results-round", theme.getSuccess(), theme.getInputBackground()));

            // Game over (host): Play Again uses the accent colour.
            client.send("GAME_OVER|alice|1");
            waiter[0].await("game over", () -> controller[0].getState().getPhase() == GamePhase.GAME_OVER);
            runOnEdt(() -> {
                snapshot(results[0], theme, "results-game-over", theme.getAccent(), theme.getSuccess());
                controller[0].disconnect();
                quiz[0].stopListening();
                results[0].stopListening();
                waiter[0].detach();
            });
        }
    }

    /** Paints the screen, checks the theme colours, and saves the image. */
    private static void snapshot(JComponent screen, Theme theme, String name, Color... expected) throws Exception {
        UiTestSupport.layOut(screen, WIDTH, HEIGHT);
        BufferedImage image = UiTestSupport.paint(screen);

        assertEquals(rgb(theme.getBackground()), image.getRGB(2, 2) | 0xFF000000,
                name + ": the window background uses the theme");
        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                colours.add(image.getRGB(x, y) | 0xFF000000);
            }
        }
        assertTrue(colours.contains(rgb(theme.getSurface())), name + ": cards use the surface colour");
        for (Color colour : expected) {
            assertTrue(colours.contains(rgb(colour)), name + ": expected theme colour " + colour);
        }

        File dir = new File("target/ui-snapshots");
        dir.mkdirs();
        ImageIO.write(image, "png", new File(dir, theme.name().toLowerCase(Locale.ROOT) + "-" + name + ".png"));
    }

    private static int rgb(Color color) {
        return color.getRGB() | 0xFF000000;
    }
}
