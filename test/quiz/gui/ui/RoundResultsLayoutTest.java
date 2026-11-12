package quiz.gui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static quiz.gui.ui.UiTestSupport.runOnEdt;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.concurrent.TimeUnit;

import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

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
import quiz.gui.ui.components.StatusBanner;

/**
 * The round results screen fits its content at the 800x600 minimum window size, the default
 * size and a maximised window, in every theme (finding F1: at 800x600 the "This round"
 * heading was squeezed and the "Waiting for the next question" banner was cut off at the
 * bottom of the card). The sizes are the screen's own size inside the main window.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RoundResultsLayoutTest {

    /** The results screen inside an 800x600 window (the minimum size). */
    private static final int MIN_WIDTH = 786;
    private static final int MIN_HEIGHT = 506;
    /** Inside the default 1024x720 window. */
    private static final int DEFAULT_WIDTH = 1010;
    private static final int DEFAULT_HEIGHT = 626;
    /** Inside a maximised window on a 1550x830 work area. */
    private static final int MAX_WIDTH = 1536;
    private static final int MAX_HEIGHT = 736;

    private static final String LONG_QUESTION = "QUESTION|1|5|15|"
            + "Which of these statements about TCP sockets, server sockets, threads, streams and the"
            + " line based protocol used by this quiz is correct when many players answer at the"
            + " same moment and the server has to keep every connection responsive?"
            + "|Socket|ServerSocket|DatagramSocket|Thread";

    @BeforeAll
    static void headless() {
        UiTestSupport.useHeadlessAwt();
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void roundResultFitsAtMinimumDefaultAndMaximisedSizes(Theme theme) throws Exception {
        try (Session session = new Session(theme, QuizScreenTest.QUESTION_1)) {
            runOnEdt(() -> {
                RoundResultsScreen screen = session.screen;
                assertEquals("Waiting for the next question", screen.statusBanner().getTitle());
                assertEquals("Time's up", screen.resultBanner().getTitle(), "unanswered: the tallest result banner");

                assertFits(screen, MIN_WIDTH, MIN_HEIGHT, true);
                assertFits(screen, DEFAULT_WIDTH, DEFAULT_HEIGHT, false);
                assertFits(screen, MAX_WIDTH, MAX_HEIGHT, false);
                // And back to the minimum size: the compact spacing comes back.
                assertFits(screen, MIN_WIDTH, MIN_HEIGHT, true);
            });
        }
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void aVeryLongQuestionScrollsInsideTheCardInsteadOfBeingCutOff(Theme theme) throws Exception {
        try (Session session = new Session(theme, LONG_QUESTION)) {
            runOnEdt(() -> {
                RoundResultsScreen screen = session.screen;
                UiTestSupport.layOut(screen, MIN_WIDTH, MIN_HEIGHT);
                JScrollPane scroll = screen.roundScrollPane();
                assertTrue(scroll.getVerticalScrollBar().isVisible(), "the round card scrolls");
                assertInsideCard(screen);
                // The banner is reachable by scrolling, and is not squeezed.
                StatusBanner banner = screen.statusBanner();
                assertEquals(banner.getPreferredSize().height, banner.getHeight());
                assertEquals(heading(screen).getPreferredSize().height, heading(screen).getHeight());
            });
        }
    }

    // ------------------------------------------------------------------
    // Checks
    // ------------------------------------------------------------------

    private static void assertFits(RoundResultsScreen screen, int width, int height, boolean compact) {
        String size = width + "x" + height;
        UiTestSupport.layOut(screen, width, height);
        assertEquals(compact, screen.isCompact(), size + ": compact spacing");
        assertFalse(screen.roundScrollPane().getVerticalScrollBar().isVisible(), size + ": no scroll bar needed");

        JLabel heading = heading(screen);
        assertEquals(heading.getPreferredSize().height, heading.getHeight(), size + ": 'This round' is not squeezed");
        assertTrue(heading.getVisibleRect().height == heading.getHeight(), size + ": 'This round' is fully visible");

        for (StatusBanner banner : new StatusBanner[] {screen.resultBanner(), screen.statusBanner()}) {
            assertEquals(banner.getPreferredSize().height, banner.getHeight(), size + ": " + banner.getTitle() + " height");
            assertEquals(banner.getHeight(), banner.getVisibleRect().height, size + ": " + banner.getTitle() + " fully visible");
        }
        assertInsideCard(screen);
    }

    /** The round card's scroll pane lies inside the card's padding, and the card inside the screen. */
    private static void assertInsideCard(RoundResultsScreen screen) {
        JScrollPane scroll = screen.roundScrollPane();
        Container card = scroll.getParent();
        Rectangle inner = new Rectangle(card.getInsets().left, card.getInsets().top,
                card.getWidth() - card.getInsets().left - card.getInsets().right,
                card.getHeight() - card.getInsets().top - card.getInsets().bottom);
        assertTrue(inner.contains(scroll.getBounds()), "scroll pane " + scroll.getBounds() + " inside " + inner);

        Rectangle cardInScreen = SwingUtilities.convertRectangle(card.getParent(), card.getBounds(), screen);
        int screenBottom = screen.getHeight() - screen.getInsets().bottom;
        assertTrue(cardInScreen.y + cardInScreen.height <= screenBottom,
                "card bottom " + (cardInScreen.y + cardInScreen.height) + " inside the screen (" + screenBottom + ")");
    }

    private static JLabel heading(RoundResultsScreen screen) {
        JLabel found = find(screen.roundScrollPane(), "This round");
        assertNotNull(found, "'This round' heading");
        return found;
    }

    private static JLabel find(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && text.equals(label.getText())) {
                return label;
            }
            if (child instanceof Container container) {
                JLabel found = find(container, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // A round that ended without an answer (the server's real message order)
    // ------------------------------------------------------------------

    private static final class Session implements AutoCloseable {

        private final LoopbackServer server = new LoopbackServer();
        private GameController controller;
        private RoundResultsScreen screen;
        private StateWaiter waiter;

        Session(Theme theme, String question) throws Exception {
            runOnEdt(() -> {
                ThemeManager.apply(theme);
                controller = new GameController();
                screen = new RoundResultsScreen(controller);
                screen.startListening();
                waiter = new StateWaiter(controller);
                controller.connect(server.host(), server.port(), "alice");
            });
            Client client = server.accept();
            client.expectLine("JOIN|alice");
            client.send("JOINED|alice", "PLAYERS|alice,bob,carol", "HOST|alice", "GAME_STARTED|5 questions", question);
            waiter.await("question", () -> controller.getState().getPhase() == GamePhase.QUESTION_ACTIVE);
            client.send("ROUND_RESULT|1|B", "SCORES|bob:10,carol:10,alice:0");
            waiter.await("scores", () -> controller.getState().getLeaderboard().size() == 3);
        }

        @Override
        public void close() throws Exception {
            runOnEdt(() -> {
                controller.disconnect();
                screen.stopListening();
                waiter.detach();
            });
            server.close();
        }
    }
}
