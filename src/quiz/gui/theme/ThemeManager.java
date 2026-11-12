package quiz.gui.theme;

import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.border.Border;
import javax.swing.plaf.BorderUIResource;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.IconUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;

/**
 * Applies a {@link Theme} to Swing and provides the shared fonts and spacing.
 *
 * <p>Only plain Java Swing is used: the cross-platform Metal look and feel is given the
 * theme's colours, and the most common component defaults are overridden through
 * {@link UIManager}. Standard components (labels, text fields, lists, scroll bars,
 * option panes, ...) therefore pick up the theme automatically; the custom components in
 * {@code quiz.gui.ui.components} read the colours from {@link #current()}.</p>
 *
 * <h2>Usage</h2>
 * <ol>
 *   <li>Call {@link #apply(Theme)} on the event dispatch thread <em>before</em> creating
 *       the windows that should use the theme.</li>
 *   <li>Read colours from {@link #current()} and fonts and spacing from the static
 *       members of this class.</li>
 * </ol>
 * There is no runtime theme toggle: windows that already exist are not restyled.
 */
public final class ThemeManager {

    // ------------------------------------------------------------------
    // Spacing (in pixels). Use these instead of magic numbers in layouts.
    // ------------------------------------------------------------------

    public static final int SPACE_XS = 4;
    public static final int SPACE_S = 8;
    public static final int SPACE_M = 12;
    public static final int SPACE_L = 16;
    public static final int SPACE_XL = 24;
    public static final int SPACE_XXL = 32;

    /** Corner radius of cards, buttons and other rounded shapes. */
    public static final int CORNER_RADIUS = 12;

    // ------------------------------------------------------------------
    // Typography
    // ------------------------------------------------------------------

    /** Preferred font families, best first; the first one installed is used. */
    private static final List<String> PREFERRED_FONTS =
            List.of("Segoe UI", "Inter", "Helvetica Neue", "Ubuntu", "Noto Sans", "DejaVu Sans");

    private static final String FONT_FAMILY = pickFontFamily();

    private static final Font TITLE_FONT = new Font(FONT_FAMILY, Font.BOLD, 26);
    private static final Font HEADING_FONT = new Font(FONT_FAMILY, Font.BOLD, 18);
    private static final Font BODY_FONT = new Font(FONT_FAMILY, Font.PLAIN, 14);
    private static final Font BODY_BOLD_FONT = new Font(FONT_FAMILY, Font.BOLD, 14);
    private static final Font SMALL_FONT = new Font(FONT_FAMILY, Font.PLAIN, 12);

    private static Theme current = Theme.DEFAULT;

    private ThemeManager() {
    }

    /** The theme that was applied most recently ({@link Theme#DEFAULT} before the first apply). */
    public static Theme current() {
        return current;
    }

    /** Large screen titles. */
    public static Font titleFont() {
        return TITLE_FONT;
    }

    /** Section headings and card titles. */
    public static Font headingFont() {
        return HEADING_FONT;
    }

    /** Normal text. */
    public static Font bodyFont() {
        return BODY_FONT;
    }

    /** Emphasised normal-size text, for example button labels. */
    public static Font bodyBoldFont() {
        return BODY_BOLD_FONT;
    }

    /** Captions, hints and other secondary text. */
    public static Font smallFont() {
        return SMALL_FONT;
    }

    // ------------------------------------------------------------------
    // Applying a theme
    // ------------------------------------------------------------------

    /**
     * Installs the theme as the Swing look and feel. Components created afterwards use it.
     *
     * @throws IllegalStateException if not called on the event dispatch thread
     */
    public static void apply(Theme theme) {
        Objects.requireNonNull(theme, "theme");
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ThemeManager.apply must be called on the event dispatch thread");
        }

        // Metal with plain (not bold) fonts; the Metal theme gives every Metal-painted
        // detail (scroll bar bumps, focus rectangles, menus, ...) the palette's colours.
        UIManager.put("swing.boldMetal", Boolean.FALSE);
        MetalLookAndFeel.setCurrentTheme(new PaletteMetalTheme(theme));
        try {
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (UnsupportedLookAndFeelException e) {
            // Metal is part of every Java runtime, so this cannot normally happen.
            throw new IllegalStateException("The Metal look and feel is not available", e);
        }
        installDefaults(theme);
        current = theme;
    }

    /** Overrides the component defaults that Metal does not take from the Metal theme. */
    private static void installDefaults(Theme t) {
        ColorUIResource background = color(t.getBackground());
        ColorUIResource surface = color(t.getSurface());
        ColorUIResource input = color(t.getInputBackground());
        ColorUIResource text = color(t.getTextPrimary());
        ColorUIResource secondaryText = color(t.getTextSecondary());
        ColorUIResource disabledText = color(t.getTextDisabled());
        ColorUIResource borderColor = color(t.getBorder());
        ColorUIResource accent = color(t.getAccent());
        ColorUIResource selection = color(t.getSelection());
        FontUIResource body = new FontUIResource(BODY_FONT);
        FontUIResource bodyBold = new FontUIResource(BODY_BOLD_FONT);
        FontUIResource small = new FontUIResource(SMALL_FONT);

        // Containers
        put(background, "Panel.background", "OptionPane.background", "RootPane.background",
                "Viewport.background", "ScrollPane.background", "SplitPane.background",
                "TabbedPane.background", "ToolBar.background");
        put(text, "Panel.foreground", "OptionPane.messageForeground", "TitledBorder.titleColor",
                "TabbedPane.foreground");
        UIManager.put("ScrollPane.border", uiBorder(BorderFactory.createLineBorder(borderColor)));
        UIManager.put("TitledBorder.border", uiBorder(BorderFactory.createLineBorder(borderColor)));
        UIManager.put("OptionPane.messageFont", body);
        UIManager.put("OptionPane.buttonFont", bodyBold);
        UIManager.put("OptionPane.questionIcon", new IconUIResource(new SymbolIcon("?", t.getAccent(), t.getOnAccent())));
        UIManager.put("OptionPane.informationIcon", new IconUIResource(new SymbolIcon("i", t.getAccent(), t.getOnAccent())));
        UIManager.put("OptionPane.warningIcon", new IconUIResource(new SymbolIcon("!", t.getWarning(), Color.BLACK)));
        UIManager.put("OptionPane.errorIcon", new IconUIResource(new SymbolIcon("!", t.getDanger(), Color.WHITE)));

        // Labels and tooltips
        UIManager.put("Label.foreground", text);
        UIManager.put("Label.disabledForeground", disabledText);
        UIManager.put("Label.font", body);
        UIManager.put("ToolTip.background", surface);
        UIManager.put("ToolTip.foreground", text);
        UIManager.put("ToolTip.font", small);
        UIManager.put("ToolTip.border", uiBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor),
                BorderFactory.createEmptyBorder(SPACE_XS, SPACE_S, SPACE_XS, SPACE_S))));

        // Standard buttons (custom screens use ThemedButton; this covers dialogs such as JOptionPane)
        UIManager.put("Button.background", color(t.getHover()));
        UIManager.put("Button.foreground", text);
        UIManager.put("Button.disabledText", disabledText);
        UIManager.put("Button.select", borderColor);
        UIManager.put("Button.focus", accent);
        UIManager.put("Button.font", bodyBold);
        UIManager.put("Button.border", uiBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor),
                BorderFactory.createEmptyBorder(SPACE_S - 2, SPACE_L, SPACE_S - 2, SPACE_L))));
        put(background, "CheckBox.background", "RadioButton.background");
        put(text, "CheckBox.foreground", "RadioButton.foreground");
        put(body, "CheckBox.font", "RadioButton.font", "ToggleButton.font");

        // Text input
        Border inputBorder = uiBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor),
                BorderFactory.createEmptyBorder(SPACE_S - 2, SPACE_S + 2, SPACE_S - 2, SPACE_S + 2)));
        for (String prefix : List.of("TextField", "PasswordField", "FormattedTextField", "TextArea")) {
            UIManager.put(prefix + ".background", input);
            UIManager.put(prefix + ".foreground", text);
            UIManager.put(prefix + ".caretForeground", text);
            UIManager.put(prefix + ".selectionBackground", selection);
            UIManager.put(prefix + ".selectionForeground", text);
            UIManager.put(prefix + ".inactiveForeground", disabledText);
            UIManager.put(prefix + ".inactiveBackground", surface);
            UIManager.put(prefix + ".font", body);
            if (!prefix.equals("TextArea")) { // a text area normally sits in a bordered scroll pane
                UIManager.put(prefix + ".border", inputBorder);
            }
        }

        // Lists, tables and combo boxes
        for (String prefix : List.of("List", "Table", "ComboBox")) {
            UIManager.put(prefix + ".background", input);
            UIManager.put(prefix + ".foreground", text);
            UIManager.put(prefix + ".selectionBackground", selection);
            UIManager.put(prefix + ".selectionForeground", text);
            UIManager.put(prefix + ".font", body);
        }
        Border cellPadding = uiBorder(BorderFactory.createEmptyBorder(SPACE_XS, SPACE_S, SPACE_XS, SPACE_S));
        put(cellPadding, "List.cellNoFocusBorder", "List.focusCellHighlightBorder",
                "List.focusSelectedCellHighlightBorder");
        UIManager.put("ComboBox.disabledForeground", disabledText);
        UIManager.put("ComboBox.buttonBackground", input);
        UIManager.put("Table.gridColor", borderColor);
        UIManager.put("TableHeader.background", surface);
        UIManager.put("TableHeader.foreground", secondaryText);
        UIManager.put("TableHeader.font", bodyBold);

        // Scroll bars: flat track, thumb in the border colour
        put(background, "ScrollBar.background", "ScrollBar.track", "ScrollBar.trackHighlight");
        put(borderColor, "ScrollBar.thumb", "ScrollBar.thumbHighlight", "ScrollBar.thumbShadow",
                "ScrollBar.thumbDarkShadow", "ScrollBar.shadow", "ScrollBar.highlight", "ScrollBar.darkShadow");
        UIManager.put("ScrollBar.width", 12);

        // Progress bars (for example a question timer)
        UIManager.put("ProgressBar.background", surface);
        UIManager.put("ProgressBar.foreground", accent);
        UIManager.put("ProgressBar.selectionBackground", text);
        UIManager.put("ProgressBar.selectionForeground", color(t.getOnAccent()));
        UIManager.put("ProgressBar.border", uiBorder(BorderFactory.createLineBorder(borderColor)));
        UIManager.put("ProgressBar.font", small);

        // Separators
        put(borderColor, "Separator.foreground", "Separator.background");
    }

    private static void put(Object value, String... keys) {
        Arrays.stream(keys).forEach(key -> UIManager.put(key, value));
    }

    /** UIResource values are replaced on a look-and-feel change; colours set in code are kept. */
    private static ColorUIResource color(Color color) {
        return new ColorUIResource(color);
    }

    private static Border uiBorder(Border border) {
        return new BorderUIResource(border);
    }

    private static String pickFontFamily() {
        try {
            List<String> installed = Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            for (String family : PREFERRED_FONTS) {
                if (installed.contains(family)) {
                    return family;
                }
            }
        } catch (RuntimeException e) {
            // Font lookup is only a nicety; fall through to the logical font.
        }
        return Font.SANS_SERIF;
    }

    // ------------------------------------------------------------------
    // Option pane icons
    // ------------------------------------------------------------------

    /** A coloured circle with a symbol, replacing Metal's dated message icons. */
    private static final class SymbolIcon implements Icon {

        private static final int SIZE = 32;

        private final String symbol;
        private final Color circle;
        private final Color symbolColor;

        SymbolIcon(String symbol, Color circle, Color symbolColor) {
            this.symbol = symbol;
            this.circle = circle;
            this.symbolColor = symbolColor;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(circle);
                g2.fillOval(x, y, SIZE, SIZE);
                g2.setFont(BODY_BOLD_FONT.deriveFont(20f));
                g2.setColor(symbolColor);
                FontMetrics metrics = g2.getFontMetrics();
                int textX = x + (SIZE - metrics.stringWidth(symbol)) / 2;
                int textY = y + (SIZE - metrics.getHeight()) / 2 + metrics.getAscent();
                g2.drawString(symbol, textX, textY);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }

    // ------------------------------------------------------------------
    // Metal theme
    // ------------------------------------------------------------------

    /**
     * Maps the palette onto the colour slots of the Metal look and feel. Metal derives
     * most of its colours (borders, focus, menu selection, text) from these slots.
     */
    private static final class PaletteMetalTheme extends DefaultMetalTheme {

        private final Theme theme;
        private final FontUIResource controlFont = new FontUIResource(BODY_FONT);
        private final FontUIResource smallFont = new FontUIResource(SMALL_FONT);

        PaletteMetalTheme(Theme theme) {
            this.theme = theme;
        }

        @Override
        public String getName() {
            return theme.getDisplayName();
        }

        // primary1-3: the "active" colours (focus, selection, highlighted borders)
        @Override
        protected ColorUIResource getPrimary1() {
            return color(theme.getAccentPressed());
        }

        @Override
        protected ColorUIResource getPrimary2() {
            return color(theme.getAccent());
        }

        @Override
        protected ColorUIResource getPrimary3() {
            return color(theme.getSelection());
        }

        // secondary1-3: the "inactive" colours (control borders and control backgrounds)
        @Override
        protected ColorUIResource getSecondary1() {
            return color(theme.getBorder());
        }

        @Override
        protected ColorUIResource getSecondary2() {
            return color(theme.getBorder());
        }

        @Override
        protected ColorUIResource getSecondary3() {
            return color(theme.getBackground());
        }

        /** Metal uses "black" for text. */
        @Override
        protected ColorUIResource getBlack() {
            return color(theme.getTextPrimary());
        }

        /** Metal uses "white" for text field and list backgrounds and for highlights. */
        @Override
        protected ColorUIResource getWhite() {
            return color(theme.getInputBackground());
        }

        @Override
        public ColorUIResource getInactiveControlTextColor() {
            return color(theme.getTextDisabled());
        }

        @Override
        public ColorUIResource getInactiveSystemTextColor() {
            return color(theme.getTextDisabled());
        }

        @Override
        public FontUIResource getControlTextFont() {
            return controlFont;
        }

        @Override
        public FontUIResource getSystemTextFont() {
            return controlFont;
        }

        @Override
        public FontUIResource getUserTextFont() {
            return controlFont;
        }

        @Override
        public FontUIResource getMenuTextFont() {
            return controlFont;
        }

        @Override
        public FontUIResource getWindowTitleFont() {
            return controlFont;
        }

        @Override
        public FontUIResource getSubTextFont() {
            return smallFont;
        }
    }
}
