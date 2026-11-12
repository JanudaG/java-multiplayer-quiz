package quiz.gui.ui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;

/**
 * A small coloured message box for loading, waiting, success and error feedback.
 * It has a short title and an optional message that wraps over several lines.
 *
 * <p>Call {@link #showStatus} to change what it says and {@link #clear()} to hide it. The
 * colours come from the theme that is current when the banner is created.</p>
 */
public class StatusBanner extends RoundedPanel {

    /** What kind of feedback is shown; it decides the colour. */
    public enum Kind {
        /** Neutral information. */
        INFO,
        /** Something is in progress (connecting, waiting for the server). */
        PROGRESS,
        /** Something worked. */
        SUCCESS,
        /** Something needs attention but is not an error. */
        WARNING,
        /** Something went wrong. */
        ERROR
    }

    /** How strongly the kind colour tints the card background (0 = none, 1 = full). */
    private static final float TINT = 0.12f;

    private final Theme theme;
    private final Dot dot = new Dot();
    private final ThemedLabel title = new ThemedLabel(" ", ThemedLabel.Style.BODY);
    private final JTextArea message = new JTextArea();
    private Kind kind = Kind.INFO;

    public StatusBanner() {
        super(new BorderLayout(ThemeManager.SPACE_M, 0), ThemeManager.SPACE_M);
        this.theme = ThemeManager.current();

        title.setFont(ThemeManager.bodyBoldFont());

        // A read-only, transparent text area wraps long messages (for example a server error).
        message.setEditable(false);
        message.setFocusable(false);
        message.setOpaque(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setColumns(16); // small preferred width: the layout stretches it and it wraps
        message.setFont(ThemeManager.bodyFont());
        message.setForeground(theme.getTextSecondary());
        message.setBorder(null);
        // A wrapped text area knows its real height only once it has a width. When the
        // width changes, lay out again so the banner grows to fit every line.
        message.addComponentListener(new ComponentAdapter() {
            private int lastWidth = -1;

            @Override
            public void componentResized(ComponentEvent e) {
                if (message.getWidth() != lastWidth) {
                    lastWidth = message.getWidth();
                    revalidate();
                }
            }
        });

        JPanel text = new JPanel(new BorderLayout(0, ThemeManager.SPACE_XS / 2));
        text.setOpaque(false);
        text.add(title, BorderLayout.NORTH);
        text.add(message, BorderLayout.CENTER);

        JPanel dotHolder = new JPanel(new BorderLayout());
        dotHolder.setOpaque(false);
        dotHolder.add(dot, BorderLayout.NORTH);

        add(dotHolder, BorderLayout.WEST);
        add(text, BorderLayout.CENTER);
        setVisible(false);
    }

    /**
     * Shows the banner.
     *
     * @param kind    decides the colour
     * @param title   a short headline, for example "Could not connect"
     * @param details more information, shown exactly as given; may be null or blank
     */
    public void showStatus(Kind kind, String title, String details) {
        this.kind = kind;
        Color color = colorFor(kind);
        setFillColor(blend(theme.getSurface(), color, TINT));
        setBorderColor(blend(theme.getBorder(), color, 0.45f));
        this.title.setText(title);
        boolean hasDetails = details != null && !details.isBlank();
        message.setText(hasDetails ? details : "");
        message.setVisible(hasDetails);
        dot.repaint();
        getAccessibleContext().setAccessibleName(hasDetails ? title + ". " + details : title);
        setVisible(true);
        revalidate();
        repaint();
    }

    /** Hides the banner. */
    public void clear() {
        setVisible(false);
        title.setText(" ");
        message.setText("");
        revalidate();
    }

    public Kind getKind() {
        return kind;
    }

    public String getTitle() {
        return title.getText();
    }

    /** The detail text, or an empty string if there is none. */
    public String getMessage() {
        return message.getText();
    }

    private Color colorFor(Kind k) {
        return switch (k) {
            case INFO, PROGRESS -> theme.getAccent();
            case SUCCESS -> theme.getSuccess();
            case WARNING -> theme.getWarning();
            case ERROR -> theme.getDanger();
        };
    }

    /** Mixes {@code base} with {@code amount} of {@code tint}. */
    private static Color blend(Color base, Color tint, float amount) {
        float keep = 1f - amount;
        return new Color(
                Math.round(base.getRed() * keep + tint.getRed() * amount),
                Math.round(base.getGreen() * keep + tint.getGreen() * amount),
                Math.round(base.getBlue() * keep + tint.getBlue() * amount));
    }

    /** The coloured status dot on the left. A ring is drawn while something is in progress. */
    private final class Dot extends JComponent {

        private static final int SIZE = 10;

        Dot() {
            // Same height as one line of bold body text, so the dot lines up with the title.
            int height = getFontMetrics(ThemeManager.bodyBoldFont()).getHeight();
            setPreferredSize(new Dimension(SIZE, height));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int y = (getHeight() - SIZE) / 2;
                g2.setColor(colorFor(kind));
                if (kind == Kind.PROGRESS) {
                    g2.drawOval(0, y, SIZE - 1, SIZE - 1);
                    g2.fillOval(3, y + 3, SIZE - 6, SIZE - 6);
                } else {
                    g2.fillOval(0, y, SIZE, SIZE);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
