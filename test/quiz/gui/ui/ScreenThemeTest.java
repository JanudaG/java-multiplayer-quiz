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
 * Both screens are laid out and painted off-screen in every theme: painting must not fail,
 * the screens must use the theme's colours, and the cards must be visible on the background.
 * The images are also written to {@code target/ui-snapshots} for a manual look.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ScreenThemeTest {

    private static final int WIDTH = 1024;
    private static final int HEIGHT = 660;

    @BeforeAll
    static void headless() {
        UiTestSupport.useHeadlessAwt();
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void screensPaintInTheme(Theme theme) throws Exception {
        try (LoopbackServer server = new LoopbackServer()) {
            GameController[] controller = new GameController[1];
            ConnectionScreen[] connection = new ConnectionScreen[1];
            LobbyScreen[] lobby = new LobbyScreen[1];
            StateWaiter[] waiter = new StateWaiter[1];
            runOnEdt(() -> {
                ThemeManager.apply(theme);
                controller[0] = new GameController();
                connection[0] = new ConnectionScreen(controller[0]);
                lobby[0] = new LobbyScreen(controller[0]);
                connection[0].startListening();
                lobby[0].startListening();
                waiter[0] = new StateWaiter(controller[0]);
                assertEquals(theme.getBackground(), connection[0].getBackground());
                assertEquals(theme.getBackground(), lobby[0].getBackground());
                snapshot(connection[0], theme, "connection-empty");
            });

            // A rejected JOIN: the connection screen shows a wrapped error banner.
            runOnEdt(() -> {
                connection[0].hostField().setText(server.host());
                connection[0].portField().setText(String.valueOf(server.port()));
                connection[0].usernameField().setText("alice");
                connection[0].connectButton().doClick(0);
            });
            Client rejected = server.accept();
            rejected.expectLine("JOIN|alice");
            rejected.send("ERROR|Username already taken");
            rejected.close();
            waiter[0].await("rejected", () -> !controller[0].hasActiveConnection());
            runOnEdt(() -> snapshot(connection[0], theme, "connection-error"));

            // A lobby with three players, the local player is the host.
            runOnEdt(() -> controller[0].connect(server.host(), server.port(), "alice"));
            Client client = server.accept();
            client.expectLine("JOIN|alice");
            client.send("JOINED|alice", "INFO|carol joined the lobby", "PLAYERS|alice,bob,carol", "HOST|alice");
            waiter[0].await("lobby", () -> controller[0].getState().getPhase() == GamePhase.LOBBY
                    && "alice".equals(controller[0].getState().getHost()));
            runOnEdt(() -> {
                snapshot(lobby[0], theme, "lobby-host");
                controller[0].disconnect();
                connection[0].stopListening();
                lobby[0].stopListening();
                waiter[0].detach();
            });
        }
    }

    /** Paints the screen, checks the theme colours, and saves the image. */
    private static void snapshot(javax.swing.JComponent screen, Theme theme, String name) throws Exception {
        UiTestSupport.layOut(screen, WIDTH, HEIGHT);
        BufferedImage image = UiTestSupport.paint(screen);

        assertEquals(theme.getBackground().getRGB(), image.getRGB(2, 2) | 0xFF000000,
                name + ": the window background uses the theme");
        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < HEIGHT; y += 4) {
            for (int x = 0; x < WIDTH; x += 4) {
                colours.add(image.getRGB(x, y) | 0xFF000000);
            }
        }
        assertTrue(colours.contains(rgb(theme.getSurface())), name + ": cards use the surface colour");
        assertTrue(colours.contains(rgb(theme.getAccent())), name + ": the accent colour is used");

        File dir = new File("target/ui-snapshots");
        dir.mkdirs();
        ImageIO.write(image, "png", new File(dir, theme.name().toLowerCase(Locale.ROOT) + "-" + name + ".png"));
    }

    private static int rgb(Color color) {
        return color.getRGB() | 0xFF000000;
    }
}
