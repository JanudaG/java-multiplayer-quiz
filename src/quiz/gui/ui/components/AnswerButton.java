package quiz.gui.ui.components;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.JButton;

import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;

/**
 * One answer choice: a rounded card with a round letter badge ("A".."D") and the answer
 * text. The quiz screen uses four of them as clickable buttons; the results screen uses
 * disabled ones to show the correct answer and the player's answer.
 *
 * <p>The {@link Look} decides the colours (normal, selected, correct, ...). Everything is
 * painted here, so a disabled button keeps the colours of its look. Long answer texts are
 * shortened with "…" and shown in full in the tooltip.</p>
 *
 * <p>Colours come from the theme that is current when the button is created.</p>
 */
public class AnswerButton extends JButton {

    /** How the answer is shown. */
    public enum Look {
        /** Can be chosen. */
        NORMAL,
        /** Chosen, but not submitted yet. */
        SELECTED,
        /** Submitted as the player's answer. */
        LOCKED,
        /** The correct answer of a finished round. */
        CORRECT,
        /** The player's answer, and it was wrong. */
        WRONG,
        /** Not relevant any more (another answer was chosen, or time is up). */
        MUTED
    }

    private static final int BADGE_SIZE = 28;
    private static final int FOCUS_RING_WIDTH = 2;
    /** How strongly the success/danger colour tints the card for CORRECT and WRONG. */
    private static final float TINT = 0.16f;

    private final Theme theme;
    private String letter;
    private Look look = Look.NORMAL;

    /** @param letter the short label in the badge, for example "A" */
    public AnswerButton(String letter) {
        super(" ");
        this.theme = ThemeManager.current();
        this.letter = Objects.requireNonNull(letter, "letter");
        setFont(ThemeManager.bodyFont());
        setForeground(theme.getTextPrimary());
        // We paint the background, border, badge and text ourselves (see paintComponent).
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setBorder(BorderFactory.createEmptyBorder(
                ThemeManager.SPACE_M, ThemeManager.SPACE_L, ThemeManager.SPACE_M, ThemeManager.SPACE_L));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    public String getLetter() {
        return letter;
    }

    public void setLetter(String letter) {
        this.letter = Objects.requireNonNull(letter, "letter");
        updateAccessibleName();
        repaint();
    }

    public Look getLook() {
        return look;
    }

    public void setLook(Look look) {
        this.look = Objects.requireNonNull(look, "look");
        repaint();
    }

    /** Sets the answer text; the full text is also used as the tooltip. */
    @Override
    public void setText(String text) {
        super.setText(text);
        setToolTipText(text == null || text.isBlank() ? null : text);
        updateAccessibleName();
        revalidate();
        repaint();
    }

    /** Only an enabled button shows the hand cursor. */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    @Override
    public Dimension getPreferredSize() {
        Insets insets = getInsets();
        FontMetrics metrics = getFontMetrics(getFont());
        String text = getText() == null ? "" : getText();
        int width = insets.left + BADGE_SIZE + ThemeManager.SPACE_M + metrics.stringWidth(text) + insets.right;
        int height = insets.top + Math.max(BADGE_SIZE, metrics.getHeight()) + insets.bottom;
        return new Dimension(width, height);
    }

    @Override
    public Dimension getMinimumSize() {
        Dimension preferred = getPreferredSize();
        Insets insets = getInsets();
        return new Dimension(insets.left + BADGE_SIZE + insets.right, preferred.height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int arc = ThemeManager.CORNER_RADIUS;
            int w = getWidth();
            int h = getHeight();
            boolean hovered = isEnabled() && getModel().isRollover();
            boolean strong = look != Look.NORMAL && look != Look.MUTED;

            // Card: the outline is a filled shape with the fill inset on top (crisp at any width).
            int outline = strong ? 2 : 1;
            g2.setColor(borderColor(hovered));
            g2.fillRoundRect(0, 0, w, h, arc, arc);
            g2.setColor(fillColor(hovered));
            g2.fillRoundRect(outline, outline, w - 2 * outline, h - 2 * outline,
                    Math.max(0, arc - outline), Math.max(0, arc - outline));

            if (isFocusOwner() && isEnabled()) {
                g2.setColor(theme.getAccent());
                g2.setStroke(new BasicStroke(FOCUS_RING_WIDTH));
                g2.drawRoundRect(1, 1, w - 3, h - 3, arc, arc);
            }

            // Letter badge
            Insets insets = getInsets();
            int badgeX = insets.left;
            int badgeY = (h - BADGE_SIZE) / 2;
            g2.setColor(badgeColor());
            g2.fillOval(badgeX, badgeY, BADGE_SIZE, BADGE_SIZE);
            g2.setFont(ThemeManager.bodyBoldFont());
            g2.setColor(badgeTextColor());
            FontMetrics badgeMetrics = g2.getFontMetrics();
            g2.drawString(letter, badgeX + (BADGE_SIZE - badgeMetrics.stringWidth(letter)) / 2,
                    badgeY + (BADGE_SIZE - badgeMetrics.getHeight()) / 2 + badgeMetrics.getAscent());

            // Answer text, shortened to the available width
            Font font = strong ? getFont().deriveFont(Font.BOLD) : getFont();
            g2.setFont(font);
            g2.setColor(look == Look.MUTED ? theme.getTextSecondary() : theme.getTextPrimary());
            FontMetrics metrics = g2.getFontMetrics();
            int textX = badgeX + BADGE_SIZE + ThemeManager.SPACE_M;
            int available = w - textX - insets.right;
            String text = shorten(getText() == null ? "" : getText(), metrics, available);
            g2.drawString(text, textX, (h - metrics.getHeight()) / 2 + metrics.getAscent());
        } finally {
            g2.dispose();
        }
    }

    private Color fillColor(boolean hovered) {
        return switch (look) {
            case NORMAL -> hovered ? theme.getHover() : theme.getInputBackground();
            case SELECTED, LOCKED -> theme.getSelection();
            case CORRECT -> blend(theme.getSurface(), theme.getSuccess(), TINT);
            case WRONG -> blend(theme.getSurface(), theme.getDanger(), TINT);
            case MUTED -> theme.getInputBackground();
        };
    }

    private Color borderColor(boolean hovered) {
        return switch (look) {
            case NORMAL -> hovered ? theme.getAccent() : theme.getBorder();
            case SELECTED, LOCKED -> theme.getAccent();
            case CORRECT -> theme.getSuccess();
            case WRONG -> theme.getDanger();
            case MUTED -> theme.getBorder();
        };
    }

    private Color badgeColor() {
        return switch (look) {
            case NORMAL, MUTED -> theme.getHover();
            case SELECTED, LOCKED -> theme.getAccent();
            case CORRECT -> theme.getSuccess();
            case WRONG -> theme.getDanger();
        };
    }

    private Color badgeTextColor() {
        return switch (look) {
            case NORMAL -> theme.getTextPrimary();
            case MUTED -> theme.getTextSecondary();
            case SELECTED, LOCKED, CORRECT, WRONG -> theme.getOnAccent();
        };
    }

    private void updateAccessibleName() {
        String text = getText() == null ? "" : getText().trim();
        getAccessibleContext().setAccessibleName(text.isEmpty() ? letter : letter + ": " + text);
    }

    /** Cuts the text and adds "…" if it is wider than {@code width} pixels. */
    static String shorten(String text, FontMetrics metrics, int width) {
        if (metrics.stringWidth(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + ellipsis) > width) {
            end--;
        }
        return end == 0 ? ellipsis : text.substring(0, end).stripTrailing() + ellipsis;
    }

    /** Mixes {@code base} with {@code amount} of {@code tint}. */
    private static Color blend(Color base, Color tint, float amount) {
        float keep = 1f - amount;
        return new Color(
                Math.round(base.getRed() * keep + tint.getRed() * amount),
                Math.round(base.getGreen() * keep + tint.getGreen() * amount),
                Math.round(base.getBlue() * keep + tint.getBlue() * amount));
    }
}
