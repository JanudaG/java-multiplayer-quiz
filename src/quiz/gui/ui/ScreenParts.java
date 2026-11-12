package quiz.gui.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.Border;

import quiz.gui.theme.ThemeManager;
import quiz.gui.ui.components.ThemedLabel;

/**
 * Small layout helpers shared by the game screens (quiz and round results), so both
 * screens have the same padding and header as the lobby.
 */
final class ScreenParts {

    private ScreenParts() {
    }

    /** The padding around a whole screen (the same as the lobby). */
    static Border screenPadding() {
        return BorderFactory.createEmptyBorder(
                ThemeManager.SPACE_XL, ThemeManager.SPACE_XXL, ThemeManager.SPACE_XXL, ThemeManager.SPACE_XXL);
    }

    /** A screen header: title and subtitle on the left, one button on the right. */
    static JComponent header(ThemedLabel title, ThemedLabel subtitle, JComponent button) {
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(title);
        text.add(Box.createVerticalStrut(ThemeManager.SPACE_XS));
        text.add(subtitle);

        JPanel buttonHolder = new JPanel(new GridBagLayout()); // centres the button vertically
        buttonHolder.setOpaque(false);
        buttonHolder.add(button);

        JPanel header = new JPanel(new BorderLayout(ThemeManager.SPACE_L, 0));
        header.setOpaque(false);
        header.add(text, BorderLayout.CENTER);
        header.add(buttonHolder, BorderLayout.EAST);
        return header;
    }

    /** A read-only, transparent text area that wraps long text (for example a question). */
    static JTextArea wrappingText(Font font, Color color) {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setColumns(16); // small preferred width: the layout stretches it and it wraps
        area.setBorder(null);
        area.setFont(font);
        area.setForeground(color);
        // Wrapped text knows its real height only once it has a width: lay out again when it changes.
        area.addComponentListener(new ComponentAdapter() {
            private int lastWidth = -1;

            @Override
            public void componentResized(ComponentEvent e) {
                if (area.getWidth() != lastWidth) {
                    lastWidth = area.getWidth();
                    area.revalidate();
                }
            }
        });
        return area;
    }
}
