package quiz.gui.ui.components;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;

import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;

/**
 * The application's logo mark: a rounded accent-coloured square with a "Q".
 * It is drawn in code, so no image files are needed, and it is also used as the
 * window/taskbar icon via {@link #iconImages()}.
 */
public class AppLogo extends JComponent {

    private static final int[] ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128};

    private final int size;
    private final Theme theme;

    /** @param size the width and height of the logo, in pixels */
    public AppLogo(int size) {
        this.size = size;
        this.theme = ThemeManager.current();
        Dimension dimension = new Dimension(size, size);
        setPreferredSize(dimension);
        setMinimumSize(dimension);
        setMaximumSize(dimension);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            int x = (getWidth() - size) / 2;
            int y = (getHeight() - size) / 2;
            g2.translate(x, y);
            paintLogo(g2, theme, size);
        } finally {
            g2.dispose();
        }
    }

    /** Window icons in several sizes, so the operating system can pick the sharpest one. */
    public static List<Image> iconImages() {
        Theme theme = ThemeManager.current();
        List<Image> images = new ArrayList<>();
        for (int iconSize : ICON_SIZES) {
            BufferedImage image = new BufferedImage(iconSize, iconSize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = image.createGraphics();
            try {
                paintLogo(g2, theme, iconSize);
            } finally {
                g2.dispose();
            }
            images.add(image);
        }
        return images;
    }

    private static void paintLogo(Graphics2D g2, Theme theme, int size) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        int arc = Math.max(4, Math.round(size * 0.3f));
        g2.setColor(theme.getAccent());
        g2.fillRoundRect(0, 0, size, size, arc, arc);

        Font font = ThemeManager.titleFont().deriveFont(Font.BOLD, size * 0.62f);
        g2.setFont(font);
        g2.setColor(theme.getOnAccent());
        FontMetrics metrics = g2.getFontMetrics();
        String letter = "Q";
        int textX = (size - metrics.stringWidth(letter)) / 2;
        int textY = (size - metrics.getHeight()) / 2 + metrics.getAscent();
        g2.drawString(letter, textX, textY);
    }
}
