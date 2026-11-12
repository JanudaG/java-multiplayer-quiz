package quiz.gui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static quiz.gui.ui.UiTestSupport.runOnEdt;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import quiz.gui.controller.GameController;
import quiz.gui.model.GamePhase;
import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;
import quiz.gui.ui.UiTestSupport.Client;
import quiz.gui.ui.UiTestSupport.LoopbackServer;
import quiz.gui.ui.UiTestSupport.StateWaiter;

/**
 * The game over screen is laid out and painted off-screen in every theme (Dark and Light):
 * painting must not fail and the screen must use the theme's own colours (background,
 * cards, success for the winners, accent for Play Again). The images are also written to
 * {@code target/ui-snapshots} for a manual look.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class GameOverScreenThemeTest {

    private static final int WIDTH = 1024;
    private static final int HEIGHT = 660;

    @BeforeAll
    static void headless() {
        UiTestSupport.useHeadlessAwt();
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void hostWinnerScreenPaintsInTheme(Theme theme) throws Exception {
        paintGameOver(theme, "alice", "GAME_OVER|alice,bob|2", "host-tie",
                theme.getSuccess(), theme.getAccent());
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void nonHostScreenPaintsInTheme(Theme theme) throws Exception {
        paintGameOver(theme, "carol", "GAME_OVER|alice,bob|2", "player",
                theme.getSuccess(), theme.getInputBackground());
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void noWinnerScreenPaintsInTheme(Theme theme) throws Exception {
        paintGameOver(theme, "alice", "GAME_OVER||0", "no-winner", theme.getAccent());
    }

    private static void paintGameOver(Theme theme, String self, String gameOverLine, String name,
                                      Color... expected) throws Exception {
        try (LoopbackServer server = new LoopbackServer()) {
            GameController[] controller = new GameController[1];
            GameOverScreen[] screen = new GameOverScreen[1];
            StateWaiter[] waiter = new StateWaiter[1];
            runOnEdt(() -> {
                ThemeManager.apply(theme);
                controller[0] = new GameController();
                screen[0] = new GameOverScreen(controller[0]);
                screen[0].startListening();
                waiter[0] = new StateWaiter(controller[0]);
                assertEquals(theme.getBackground(), screen[0].getBackground());
                controller[0].connect(server.host(), server.port(), self);
            });
            Client client = server.accept();
            client.expectLine("JOIN|" + self);
            client.send("JOINED|" + self, "PLAYERS|alice,bob,carol", "HOST|alice", "GAME_STARTED|5 questions",
                    "QUESTION|5|5|15|Which port range is reserved?|0-1023|1024-2047|8000-9000|49152-65535",
                    "ROUND_RESULT|5|A", "SCORES|alice:2,bob:2,carol:1", "SCORES|alice:2,bob:2,carol:1", gameOverLine);
            waiter[0].await("game over", () -> controller[0].getState().getPhase() == GamePhase.GAME_OVER);
            runOnEdt(() -> {
                assertEquals("alice".equals(self), screen[0].playAgainButton().isVisible());
                assertFalse(screen[0].headlineText().isBlank());
                snapshot(screen[0], theme, "game-over-" + name, expected);
                controller[0].disconnect();
                screen[0].stopListening();
                waiter[0].detach();
            });
        }
    }

    /** Paints the screen, checks the theme colours, and saves the image. */
    private static void snapshot(GameOverScreen screen, Theme theme, String name, Color... expected) throws Exception {
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
