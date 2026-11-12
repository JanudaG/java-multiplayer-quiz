package quiz.gui.ui.components;

import java.awt.Color;
import java.awt.Font;

import javax.swing.JLabel;

import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;

/**
 * A label that uses one of the application's text styles, so every screen has the same
 * typography. Colours come from the theme that is current when the label is created.
 */
public class ThemedLabel extends JLabel {

    /** The text styles used across the application. */
    public enum Style {
        /** Large screen title. */
        TITLE,
        /** Section heading or card title. */
        HEADING,
        /** Normal text. */
        BODY,
        /** Less important normal-size text. */
        SECONDARY,
        /** Small hints and captions. */
        CAPTION
    }

    public ThemedLabel(String text, Style style) {
        super(text);
        Theme theme = ThemeManager.current();
        setFont(fontFor(style));
        setForeground(colorFor(style, theme));
    }

    private static Font fontFor(Style style) {
        return switch (style) {
            case TITLE -> ThemeManager.titleFont();
            case HEADING -> ThemeManager.headingFont();
            case BODY, SECONDARY -> ThemeManager.bodyFont();
            case CAPTION -> ThemeManager.smallFont();
        };
    }

    private static Color colorFor(Style style, Theme theme) {
        return switch (style) {
            case TITLE, HEADING, BODY -> theme.getTextPrimary();
            case SECONDARY, CAPTION -> theme.getTextSecondary();
        };
    }
}
