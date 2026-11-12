package quiz.gui.ui.components;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.JPanel;

import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;

/**
 * A "card": a panel with a rounded background, a thin border and inner padding.
 * Lay out its children with any layout manager, exactly like a normal {@link JPanel}.
 *
 * <p>By default it uses the current theme's surface and border colours; both can be
 * changed, for example to highlight a selected card.</p>
 */
public class RoundedPanel extends JPanel {

    private Color fillColor;
    private Color borderColor;
    private int borderWidth = 1;

    /** A card with the default padding ({@link ThemeManager#SPACE_XL}). */
    public RoundedPanel(LayoutManager layout) {
        this(layout, ThemeManager.SPACE_XL);
    }

    /** A card with the given padding on every side, in pixels. */
    public RoundedPanel(LayoutManager layout, int padding) {
        super(layout);
        Theme theme = ThemeManager.current();
        this.fillColor = theme.getSurface();
        this.borderColor = theme.getBorder();
        setOpaque(false); // the corners outside the rounded shape stay transparent
        setBorder(BorderFactory.createEmptyBorder(padding, padding, padding, padding));
    }

    public Color getFillColor() {
        return fillColor;
    }

    public void setFillColor(Color fillColor) {
        this.fillColor = fillColor;
        repaint();
    }

    public Color getBorderColor() {
        return borderColor;
    }

    /** @param borderColor the outline colour, or null for no outline */
    public void setBorderColor(Color borderColor) {
        this.borderColor = borderColor;
        repaint();
    }

    public int getBorderWidth() {
        return borderWidth;
    }

    /** @param borderWidth the outline width in pixels (1 or more) */
    public void setBorderWidth(int borderWidth) {
        this.borderWidth = Math.max(1, borderWidth);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = ThemeManager.CORNER_RADIUS;
            if (borderColor == null) {
                g2.setColor(fillColor);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                return;
            }
            // Draw the outline as a filled shape and the fill inset on top of it; this keeps
            // the edge crisp at any border width.
            g2.setColor(borderColor);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            int b = borderWidth;
            g2.setColor(fillColor);
            g2.fillRoundRect(b, b, getWidth() - 2 * b, getHeight() - 2 * b, Math.max(0, arc - b), Math.max(0, arc - b));
        } finally {
            g2.dispose();
        }
    }
}
