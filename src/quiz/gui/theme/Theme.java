package quiz.gui.theme;

import java.awt.Color;

/**
 * The colour palettes the application can use.
 *
 * <p>A theme is chosen once, before the main window opens, and stays the same until the
 * application is closed (there is no runtime theme toggle). Fonts and spacing are the
 * same for every theme and live in {@link ThemeManager}.</p>
 *
 * <p>Every colour has a clear job, so screens never need to invent their own colours:
 * use {@code background} for windows, {@code surface} for cards, {@code accent} for the
 * main action, and so on.</p>
 */
public enum Theme {

    MODERN_DARK(
            "Modern Dark",
            "Easy on the eyes in low light.",
            true,
            rgb(0x0F1115), // background      - window background
            rgb(0x181B22), // surface         - cards and panels
            rgb(0x222631), // hover           - hovered cards and secondary buttons
            rgb(0x13161C), // inputBackground - text fields and lists
            rgb(0x2E3340), // border          - card and input borders, separators
            rgb(0xE8EAF0), // textPrimary
            rgb(0x9AA3B2), // textSecondary
            rgb(0x5C6370), // textDisabled
            rgb(0x5A5FE0), // accent          - primary buttons, focus, selection
            rgb(0x6C71EA), // accentHover
            rgb(0x4B50CC), // accentPressed
            rgb(0xFFFFFF), // onAccent        - text drawn on the accent colour
            rgb(0x2F3270), // selection       - selected text and list rows
            rgb(0x22C55E), // success
            rgb(0xF59E0B), // warning
            rgb(0xEF4444)  // danger
    ),

    MODERN_LIGHT(
            "Modern Light",
            "Clean and bright for daytime play.",
            false,
            rgb(0xF4F6FA), // background
            rgb(0xFFFFFF), // surface
            rgb(0xEEF1F6), // hover
            rgb(0xFFFFFF), // inputBackground
            rgb(0xD5DAE3), // border
            rgb(0x111827), // textPrimary
            rgb(0x5B6475), // textSecondary
            rgb(0xA0A7B4), // textDisabled
            rgb(0x4F46E5), // accent
            rgb(0x4338CA), // accentHover
            rgb(0x3730A3), // accentPressed
            rgb(0xFFFFFF), // onAccent
            rgb(0xC7D2FE), // selection
            rgb(0x16A34A), // success
            rgb(0xD97706), // warning
            rgb(0xDC2626)  // danger
    );

    /** The theme that is pre-selected in the theme selection dialog. */
    public static final Theme DEFAULT = MODERN_DARK;

    private final String displayName;
    private final String description;
    private final boolean dark;

    private final Color background;
    private final Color surface;
    private final Color hover;
    private final Color inputBackground;
    private final Color border;
    private final Color textPrimary;
    private final Color textSecondary;
    private final Color textDisabled;
    private final Color accent;
    private final Color accentHover;
    private final Color accentPressed;
    private final Color onAccent;
    private final Color selection;
    private final Color success;
    private final Color warning;
    private final Color danger;

    Theme(String displayName, String description, boolean dark,
          Color background, Color surface, Color hover, Color inputBackground, Color border,
          Color textPrimary, Color textSecondary, Color textDisabled,
          Color accent, Color accentHover, Color accentPressed, Color onAccent, Color selection,
          Color success, Color warning, Color danger) {
        this.displayName = displayName;
        this.description = description;
        this.dark = dark;
        this.background = background;
        this.surface = surface;
        this.hover = hover;
        this.inputBackground = inputBackground;
        this.border = border;
        this.textPrimary = textPrimary;
        this.textSecondary = textSecondary;
        this.textDisabled = textDisabled;
        this.accent = accent;
        this.accentHover = accentHover;
        this.accentPressed = accentPressed;
        this.onAccent = onAccent;
        this.selection = selection;
        this.success = success;
        this.warning = warning;
        this.danger = danger;
    }

    private static Color rgb(int hex) {
        return new Color(hex);
    }

    /** The name shown to the user, for example "Modern Dark". */
    public String getDisplayName() {
        return displayName;
    }

    /** A one-sentence description shown in the theme selection dialog. */
    public String getDescription() {
        return description;
    }

    /** True for dark themes (light text on a dark background). */
    public boolean isDark() {
        return dark;
    }

    public Color getBackground() {
        return background;
    }

    public Color getSurface() {
        return surface;
    }

    public Color getHover() {
        return hover;
    }

    public Color getInputBackground() {
        return inputBackground;
    }

    public Color getBorder() {
        return border;
    }

    public Color getTextPrimary() {
        return textPrimary;
    }

    public Color getTextSecondary() {
        return textSecondary;
    }

    public Color getTextDisabled() {
        return textDisabled;
    }

    public Color getAccent() {
        return accent;
    }

    public Color getAccentHover() {
        return accentHover;
    }

    public Color getAccentPressed() {
        return accentPressed;
    }

    public Color getOnAccent() {
        return onAccent;
    }

    public Color getSelection() {
        return selection;
    }

    public Color getSuccess() {
        return success;
    }

    public Color getWarning() {
        return warning;
    }

    public Color getDanger() {
        return danger;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
