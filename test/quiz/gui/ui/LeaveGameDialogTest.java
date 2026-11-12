package quiz.gui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static quiz.gui.ui.UiTestSupport.runOnEdt;

import java.awt.event.KeyEvent;
import java.util.concurrent.TimeUnit;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;
import quiz.gui.ui.components.ThemedButton;

/**
 * The "Leave Game?" question (finding F2): the application's own rounded buttons instead of
 * the stock Swing ones, with the same answers and keyboard access. The modal dialog itself
 * needs a screen, so these tests check the option pane that MainFrame shows.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class LeaveGameDialogTest {

    @BeforeAll
    static void headless() {
        UiTestSupport.useHeadlessAwt();
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void usesThemedYesAndNoButtons(Theme theme) throws Exception {
        runOnEdt(() -> {
            ThemeManager.apply(theme);
            JOptionPane pane = MainFrame.createLeavePane();
            assertEquals(MainFrame.LEAVE_MESSAGE, pane.getMessage());
            assertEquals(JOptionPane.QUESTION_MESSAGE, pane.getMessageType());

            Object[] options = pane.getOptions();
            assertEquals(2, options.length);
            ThemedButton yes = (ThemedButton) options[0];
            ThemedButton no = (ThemedButton) options[1];
            assertEquals("Yes", yes.getText());
            assertEquals("No", no.getText());
            assertEquals(ThemedButton.Variant.PRIMARY, yes.getVariant());
            assertEquals(ThemedButton.Variant.SECONDARY, no.getVariant());
            assertSame(yes, pane.getInitialValue(), "Yes keeps the initial focus, as in the stock dialog");
            assertEquals(yes.getPreferredSize(), no.getPreferredSize(), "both buttons have the same size");

            // No stock focus rectangle: the themed button paints its own rounded focus ring.
            assertFalse(yes.isFocusPainted());
            assertFalse(no.isFocusPainted());
            assertTrue(yes.isFocusable());
            assertTrue(no.isFocusable());
            assertEquals(KeyEvent.VK_Y, yes.getMnemonic());
            assertEquals(KeyEvent.VK_N, no.getMnemonic());
        });
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void yesAndNoGiveTheRightAnswer(Theme theme) throws Exception {
        runOnEdt(() -> {
            ThemeManager.apply(theme);

            JOptionPane yesPane = MainFrame.createLeavePane();
            assertFalse(MainFrame.isYes(yesPane), "nothing chosen yet");
            ((ThemedButton) yesPane.getOptions()[0]).doClick(0);
            assertTrue(MainFrame.isYes(yesPane));

            JOptionPane noPane = MainFrame.createLeavePane();
            ((ThemedButton) noPane.getOptions()[1]).doClick(0);
            assertFalse(MainFrame.isYes(noPane));

            // Escape and the window's close button set these values; both mean No.
            JOptionPane closed = MainFrame.createLeavePane();
            closed.setValue(JOptionPane.CLOSED_OPTION);
            assertFalse(MainFrame.isYes(closed));
            closed.setValue(null);
            assertFalse(MainFrame.isYes(closed));
        });
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void enterChoosesTheFocusedButton(Theme theme) throws Exception {
        runOnEdt(() -> {
            ThemeManager.apply(theme);
            JOptionPane pane = MainFrame.createLeavePane();
            ThemedButton no = (ThemedButton) pane.getOptions()[1];
            KeyStroke enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
            Object actionKey = no.getInputMap(JComponent.WHEN_FOCUSED).get(enter);
            assertNotNull(actionKey, "Enter is bound on the focused button");
            no.getActionMap().get(actionKey).actionPerformed(null);
            assertFalse(MainFrame.isYes(pane), "Tab to No, then Enter: stay in the game");
            assertSame(no, pane.getValue());
        });
    }
}
