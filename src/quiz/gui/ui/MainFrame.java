package quiz.gui.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;

import quiz.gui.controller.GameController;
import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;
import quiz.gui.ui.components.AppLogo;
import quiz.gui.ui.components.RoundedPanel;
import quiz.gui.ui.components.ThemedButton;
import quiz.gui.ui.components.ThemedLabel;

/**
 * The main application window: a header bar and a content area that shows one screen at
 * a time (using a {@link CardLayout}).
 *
 * <h2>Screens</h2>
 * Screens are ordinary Swing components. Register each one once under a unique name with
 * {@link #registerScreen}, then switch between them with {@link #showScreen}:
 * <pre>
 * frame.registerScreen("connection", new ConnectionScreen(frame.getController()));
 * frame.showScreen("connection");
 * </pre>
 * Until a screen is shown, a neutral empty state is displayed.
 *
 * <h2>Game logic</h2>
 * The frame owns no game logic. Screens receive the {@link GameController} (see
 * {@link #getController()}): they read the game state from it, react to its state
 * listener, and send user actions through its methods. Screens never change the
 * {@code GameState} directly.
 *
 * <h2>Threading and theme</h2>
 * Create and use the frame on the event dispatch thread, after
 * {@link ThemeManager#apply(Theme)}; it uses the theme that is current at that time.
 */
public final class MainFrame extends JFrame {

    public static final String APP_TITLE = "Java Multiplayer Quiz";

    static final String LEAVE_TITLE = "Leave Game?";
    static final String LEAVE_MESSAGE =
            "You are still connected to the quiz server.\nLeave the game and close the application?";
    static final String YES_TEXT = "Yes";
    static final String NO_TEXT = "No";
    private static final String CHOOSE_FOCUSED = "quiz.chooseFocused";

    private static final Dimension DEFAULT_SIZE = new Dimension(1024, 720);
    private static final Dimension MINIMUM_SIZE = new Dimension(800, 600);

    /** Card name of the built-in empty state; no screen may use it. */
    private static final String EMPTY_STATE = "quiz.empty-state";

    private final GameController controller;
    private final Theme theme;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel screenArea = new JPanel(cardLayout);
    private final Map<String, JComponent> screens = new LinkedHashMap<>();
    /** Name of the visible screen, or null while the empty state is shown. */
    private String currentScreen;

    /**
     * @param controller the controller that screens will use; it is not connected here
     */
    public MainFrame(GameController controller) {
        super(APP_TITLE);
        this.controller = Objects.requireNonNull(controller, "controller");
        this.theme = ThemeManager.current();

        setIconImages(AppLogo.iconImages());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE); // handled by requestClose()
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestClose();
            }
        });

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(theme.getBackground());
        root.add(createHeader(), BorderLayout.NORTH);
        screenArea.setBackground(theme.getBackground());
        screenArea.add(createEmptyState(), EMPTY_STATE);
        root.add(screenArea, BorderLayout.CENTER);
        setContentPane(root);

        applyWindowSize();
    }

    // ------------------------------------------------------------------
    // Integration points
    // ------------------------------------------------------------------

    /** The controller that screens use to read the game state and send user actions. */
    public GameController getController() {
        return controller;
    }

    /** The theme this window was built with. */
    public Theme getTheme() {
        return theme;
    }

    /**
     * Adds a screen. It is not shown until {@link #showScreen} is called with its name.
     *
     * @throws IllegalArgumentException if the name is blank or already registered
     */
    public void registerScreen(String name, JComponent screen) {
        Objects.requireNonNull(screen, "screen");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Screen name must not be blank");
        }
        if (name.equals(EMPTY_STATE) || screens.containsKey(name)) {
            throw new IllegalArgumentException("A screen named '" + name + "' is already registered");
        }
        screens.put(name, screen);
        screenArea.add(screen, name);
    }

    /**
     * Makes the named screen the visible one.
     *
     * @throws IllegalArgumentException if no screen with that name is registered
     */
    public void showScreen(String name) {
        if (!screens.containsKey(name)) {
            throw new IllegalArgumentException(
                    "Unknown screen '" + name + "'. Registered screens: " + screens.keySet());
        }
        cardLayout.show(screenArea, name);
        currentScreen = name;
    }

    public boolean hasScreen(String name) {
        return screens.containsKey(name);
    }

    /** The name of the visible screen, or empty while no screen has been shown yet. */
    public Optional<String> getCurrentScreen() {
        return Optional.ofNullable(currentScreen);
    }

    /** The names of all registered screens, in registration order. */
    public Set<String> getScreenNames() {
        return Collections.unmodifiableSet(screens.keySet());
    }

    /**
     * Closes the window, as if the user clicked the close button. If a game session is
     * active, the user is asked first and the session is ended through the controller.
     * When this was the last window, the application exits normally.
     */
    public void requestClose() {
        if (controller.hasActiveConnection()) {
            if (!confirmLeave()) {
                return;
            }
            controller.disconnect();
        }
        dispose();
    }

    /** Shows the modal "Leave Game?" question; true only if the player chose Yes. */
    private boolean confirmLeave() {
        JOptionPane pane = createLeavePane();
        JDialog dialog = pane.createDialog(this, LEAVE_TITLE);
        dialog.setVisible(true); // blocks until Yes, No, Escape or the close button
        dialog.dispose();
        return isYes(pane);
    }

    /**
     * The "Leave Game?" question with the application's own rounded buttons instead of the
     * stock Swing ones. Yes is the default button and has the initial focus, as in a standard
     * confirm dialog: Enter or Space chooses the focused button, Tab moves between Yes and No,
     * Alt+Y / Alt+N choose directly, and Escape or the close button counts as No.
     */
    static JOptionPane createLeavePane() {
        ThemedButton yes = new ThemedButton(YES_TEXT, ThemedButton.Variant.PRIMARY);
        ThemedButton no = new ThemedButton(NO_TEXT, ThemedButton.Variant.SECONDARY);
        yes.setMnemonic(KeyEvent.VK_Y);
        no.setMnemonic(KeyEvent.VK_N);
        // Both buttons as wide as the wider one, like the stock dialog buttons.
        Dimension size = new Dimension(
                Math.max(yes.getPreferredSize().width, no.getPreferredSize().width),
                Math.max(yes.getPreferredSize().height, no.getPreferredSize().height));
        for (ThemedButton button : List.of(yes, no)) {
            button.setPreferredSize(size);
            // Enter chooses the focused button (Metal would always choose the default, Yes,
            // even after Tab moved the focus to No).
            button.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), CHOOSE_FOCUSED);
            button.getActionMap().put(CHOOSE_FOCUSED, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    button.doClick();
                }
            });
        }

        JOptionPane pane = new JOptionPane(LEAVE_MESSAGE, JOptionPane.QUESTION_MESSAGE,
                JOptionPane.YES_NO_OPTION, null, new Object[] {yes, no}, yes);
        // Option panes only close by themselves for buttons they created; these set the value.
        yes.addActionListener(e -> pane.setValue(yes));
        no.addActionListener(e -> pane.setValue(no));
        return pane;
    }

    /** True if the pane was closed with its Yes button. */
    static boolean isYes(JOptionPane pane) {
        Object[] options = pane.getOptions();
        return options != null && options.length > 0 && pane.getValue() == options[0];
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private JComponent createHeader() {
        JPanel header = new JPanel(new BorderLayout(ThemeManager.SPACE_M, 0));
        header.setBackground(theme.getSurface());
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, theme.getBorder()),
                BorderFactory.createEmptyBorder(
                        ThemeManager.SPACE_M, ThemeManager.SPACE_XL, ThemeManager.SPACE_M, ThemeManager.SPACE_XL)));
        header.add(new AppLogo(32), BorderLayout.WEST);
        header.add(new ThemedLabel(APP_TITLE, ThemedLabel.Style.HEADING), BorderLayout.CENTER);
        return header;
    }

    /** Shown in the content area until the first screen is shown. Contains no game functions. */
    private JComponent createEmptyState() {
        RoundedPanel card = new RoundedPanel(null, ThemeManager.SPACE_XXL);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        AppLogo logo = new AppLogo(64);
        ThemedLabel title = new ThemedLabel(APP_TITLE, ThemedLabel.Style.TITLE);
        ThemedLabel message = new ThemedLabel("There is nothing to show yet.", ThemedLabel.Style.SECONDARY);
        for (JComponent c : new JComponent[] {logo, title, message}) {
            c.setAlignmentX(Component.CENTER_ALIGNMENT);
        }
        card.add(logo);
        card.add(Box.createVerticalStrut(ThemeManager.SPACE_L));
        card.add(title);
        card.add(Box.createVerticalStrut(ThemeManager.SPACE_XS));
        card.add(message);

        JPanel centered = new JPanel(new GridBagLayout()); // GridBagLayout centres a single child
        centered.setOpaque(false);
        centered.add(card);
        return centered;
    }

    /** Uses the default size, shrunk to fit small screens, and centres the window. */
    private void applyWindowSize() {
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(
                Math.min(MINIMUM_SIZE.width, usable.width), Math.min(MINIMUM_SIZE.height, usable.height)));
        setSize(Math.min(DEFAULT_SIZE.width, usable.width), Math.min(DEFAULT_SIZE.height, usable.height));
        setLocationRelativeTo(null);
    }
}
