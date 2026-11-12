package quiz.gui.ui.components;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JButton;

import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;

/**
 * A flat, rounded button in the application's style, with hover, pressed, disabled and
 * keyboard-focus states. Use {@link Variant#PRIMARY} for the main action of a screen and
 * {@link Variant#SECONDARY} for the others.
 *
 * <p>Colours come from the theme that is current when the button is created.</p>
 */
public class ThemedButton extends JButton {

    public enum Variant {
        /** Filled with the accent colour. */
        PRIMARY,
        /** Outlined, on the surface colour. */
        SECONDARY
    }

    private static final int FOCUS_RING_WIDTH = 2;

    private final Variant variant;
    private final Theme theme;

    public ThemedButton(String text, Variant variant) {
        super(text);
        this.variant = variant;
        this.theme = ThemeManager.current();

        setFont(ThemeManager.bodyBoldFont());
        setForeground(variant == Variant.PRIMARY ? theme.getOnAccent() : theme.getTextPrimary());
        // We paint the background and border ourselves (see paintComponent).
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setBorder(BorderFactory.createEmptyBorder(
                ThemeManager.SPACE_S + 2, ThemeManager.SPACE_XL, ThemeManager.SPACE_S + 2, ThemeManager.SPACE_XL));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    public Variant getVariant() {
        return variant;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = ThemeManager.CORNER_RADIUS;
            int inset = FOCUS_RING_WIDTH; // room for the focus ring
            int w = getWidth() - 2 * inset;
            int h = getHeight() - 2 * inset;

            g2.setColor(fillColor());
            g2.fillRoundRect(inset, inset, w, h, arc, arc);

            if (variant == Variant.SECONDARY) {
                g2.setColor(theme.getBorder());
                g2.drawRoundRect(inset, inset, w - 1, h - 1, arc, arc);
            }
            if (isFocusOwner() && isEnabled()) {
                g2.setColor(theme.getAccent());
                g2.setStroke(new BasicStroke(FOCUS_RING_WIDTH));
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, arc + 2, arc + 2);
            }
        } finally {
            g2.dispose();
        }
        super.paintComponent(g); // paints the text (and icon, if any)
    }

    private Color fillColor() {
        ButtonModel model = getModel();
        if (variant == Variant.PRIMARY) {
            if (!isEnabled()) {
                return theme.getBorder();
            }
            if (model.isPressed()) {
                return theme.getAccentPressed();
            }
            return model.isRollover() ? theme.getAccentHover() : theme.getAccent();
        }
        if (!isEnabled()) {
            return theme.getSurface();
        }
        if (model.isPressed()) {
            return theme.getBorder();
        }
        return model.isRollover() ? theme.getHover() : theme.getSurface();
    }
}
