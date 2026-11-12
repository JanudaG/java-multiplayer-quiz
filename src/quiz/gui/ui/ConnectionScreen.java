package quiz.gui.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.HierarchyEvent;
import java.util.Objects;
import java.util.Optional;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

import quiz.gui.controller.GameController;
import quiz.gui.model.GamePhase;
import quiz.gui.model.GameState;
import quiz.gui.net.ServerConnection;
import quiz.gui.protocol.ClientCommands;
import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;
import quiz.gui.ui.components.AppLogo;
import quiz.gui.ui.components.RoundedPanel;
import quiz.gui.ui.components.StatusBanner;
import quiz.gui.ui.components.ThemedButton;
import quiz.gui.ui.components.ThemedLabel;

/**
 * The first screen: the player enters the server address, port and a username and connects.
 *
 * <p>Connecting is done entirely by the {@link GameController}: this screen calls
 * {@link GameController#connect}, shows the validation message it returns, and then follows
 * the controller's state changes to show progress and errors. It never opens a socket and
 * never switches screens itself; the {@link ScreenNavigator} shows the lobby once the server
 * has confirmed the JOIN.</p>
 *
 * <p>While a connection attempt is running, the inputs and the Connect button are disabled
 * so only one attempt can run at a time; Cancel ends the attempt.</p>
 */
public final class ConnectionScreen extends JPanel {

    /** Width of the form card, in pixels. */
    private static final int CARD_WIDTH = 460;

    private static final String CONNECT_TEXT = "Connect";
    private static final String CONNECTING_TEXT = "Connecting…";

    private final GameController controller;
    private final GameController.StateListener listener = this::stateChanged;
    private final Theme theme;

    private final JTextField hostField = new JTextField(ServerConnection.DEFAULT_HOST, 16);
    private final JTextField portField = new JTextField(String.valueOf(ServerConnection.DEFAULT_PORT), 6);
    private final JTextField usernameField = new JTextField(16);
    private final ThemedButton connectButton = new ThemedButton(CONNECT_TEXT, ThemedButton.Variant.PRIMARY);
    private final ThemedButton cancelButton = new ThemedButton("Cancel", ThemedButton.Variant.SECONDARY);
    private final StatusBanner statusBanner = new StatusBanner();
    private final JProgressBar progressBar = new JProgressBar();

    /** The normal text field border, restored after a field was marked invalid. */
    private final Border normalFieldBorder;
    private final Border invalidFieldBorder;

    private boolean listening;
    /** The phase seen in the previous state change, to tell what just happened. */
    private GamePhase previousPhase;
    /** "host:port" of the current or last connection attempt, for the progress message. */
    private String attemptTarget = "";

    public ConnectionScreen(GameController controller) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        this.theme = ThemeManager.current();
        setBackground(theme.getBackground());

        normalFieldBorder = usernameField.getBorder();
        invalidFieldBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.getDanger(), 2),
                BorderFactory.createEmptyBorder(ThemeManager.SPACE_S - 3, ThemeManager.SPACE_S + 1,
                        ThemeManager.SPACE_S - 3, ThemeManager.SPACE_S + 1));

        configureInputs();
        add(createScrollableContent(), BorderLayout.CENTER);

        // Put the cursor in the username field whenever this screen becomes visible.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()
                    && usernameField.isEnabled()) {
                usernameField.requestFocusInWindow();
            }
        });

        render(controller.getState());
    }

    // ------------------------------------------------------------------
    // Listener lifecycle
    // ------------------------------------------------------------------

    /** Starts following the controller while this screen is part of a window. */
    @Override
    public void addNotify() {
        super.addNotify();
        startListening();
    }

    /** Stops following the controller when the window is disposed. */
    @Override
    public void removeNotify() {
        stopListening();
        super.removeNotify();
    }

    /** Registers the state listener (once) and shows the current state. */
    void startListening() {
        if (listening) {
            return;
        }
        listening = true;
        controller.addStateListener(listener);
        previousPhase = controller.getState().getPhase();
        render(controller.getState());
    }

    void stopListening() {
        if (!listening) {
            return;
        }
        listening = false;
        controller.removeStateListener(listener);
    }

    boolean isListening() {
        return listening;
    }

    // ------------------------------------------------------------------
    // User actions
    // ------------------------------------------------------------------

    /** Validates the input through the controller and starts a connection attempt. */
    private void connect() {
        if (controller.hasActiveConnection()) {
            return; // an attempt is already running (the button is disabled, but Enter may still fire)
        }
        clearInvalidMarks();
        String host = hostField.getText();
        int port = parsePort(portField.getText());
        String username = usernameField.getText();

        // Set before connect(): the controller notifies listeners before it returns.
        attemptTarget = (host == null ? "" : host.trim()) + ":" + port;
        Optional<String> problem = controller.connect(host, port, username);
        if (problem.isPresent()) {
            statusBanner.showStatus(StatusBanner.Kind.ERROR, "Please check your details", problem.get());
            markInvalidField(host, port);
        }
    }

    /** Ends the running connection attempt. */
    private void cancel() {
        controller.disconnect();
    }

    /** The port as a number, or -1 if it is not a number (the controller then explains the rule). */
    static int parsePort(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Highlights and focuses the first field the controller's validation rejected. */
    private void markInvalidField(String host, int port) {
        JTextField field;
        if (host == null || host.isBlank()) {
            field = hostField;
        } else if (port < 1 || port > 65535) {
            field = portField;
        } else {
            field = usernameField;
        }
        field.setBorder(invalidFieldBorder);
        field.requestFocusInWindow();
        field.selectAll();
    }

    private void clearInvalidMarks() {
        for (JTextField field : new JTextField[] {hostField, portField, usernameField}) {
            field.setBorder(normalFieldBorder);
        }
    }

    // ------------------------------------------------------------------
    // State changes
    // ------------------------------------------------------------------

    private void stateChanged(GameState state) {
        GamePhase before = previousPhase;
        previousPhase = state.getPhase();
        updateStatus(before, state);
        render(state);
    }

    /** Enables or disables the controls for the current connection state. */
    private void render(GameState state) {
        boolean busy = controller.hasActiveConnection();
        hostField.setEnabled(!busy);
        portField.setEnabled(!busy);
        usernameField.setEnabled(!busy);
        connectButton.setEnabled(!busy);
        connectButton.setText(busy ? CONNECTING_TEXT : CONNECT_TEXT);
        cancelButton.setVisible(busy);

        GamePhase phase = state.getPhase();
        boolean waiting = phase == GamePhase.CONNECTING
                || (phase == GamePhase.JOINING && !state.isJoinRejected());
        progressBar.setVisible(waiting);
        progressBar.setIndeterminate(waiting);
        revalidate();
        repaint();
    }

    /** Shows what just happened, based on the previous and the new phase. */
    private void updateStatus(GamePhase before, GameState state) {
        switch (state.getPhase()) {
            case CONNECTING -> statusBanner.showStatus(StatusBanner.Kind.PROGRESS,
                    "Connecting…", "Contacting the quiz server at " + attemptTarget + ".");
            case JOINING -> {
                if (state.isJoinRejected()) {
                    showJoinRejected(state);
                } else {
                    statusBanner.showStatus(StatusBanner.Kind.PROGRESS, "Joining the lobby…",
                            "Connected. Asking to join as " + state.getRequestedUsername() + ".");
                }
            }
            case DISCONNECTED -> {
                if (before != null && before != GamePhase.DISCONNECTED) {
                    showDisconnected(before, state);
                }
                // Otherwise nothing changed for this screen: keep the current message.
            }
            default -> statusBanner.showStatus(StatusBanner.Kind.SUCCESS,
                    "Joined as " + state.getCurrentPlayer(), "Opening the lobby…");
        }
    }

    private void showDisconnected(GamePhase before, GameState state) {
        if (state.isJoinRejected()) {
            showJoinRejected(state);
            return;
        }
        Optional<String> reason = controller.getDisconnectReason();
        if (reason.isPresent()) {
            switch (before) {
                case CONNECTING -> statusBanner.showStatus(StatusBanner.Kind.ERROR, "Could not connect",
                        reason.get() + "\nCheck that the quiz server is running and that the address and port are correct.");
                case JOINING -> statusBanner.showStatus(StatusBanner.Kind.ERROR, "Could not join the game",
                        reason.get());
                default -> statusBanner.showStatus(StatusBanner.Kind.ERROR, "Disconnected from the server",
                        reason.get() + "\nYou can connect again when the server is available.");
            }
            return;
        }
        // No reason: the player ended the session.
        if (before == GamePhase.CONNECTING || before == GamePhase.JOINING) {
            statusBanner.showStatus(StatusBanner.Kind.INFO, "Connection cancelled", null);
        } else {
            statusBanner.showStatus(StatusBanner.Kind.INFO, "You left the game",
                    "Connect again whenever you are ready.");
        }
    }

    /** The server refused the JOIN; its own message is shown unchanged. */
    private void showJoinRejected(GameState state) {
        statusBanner.showStatus(StatusBanner.Kind.ERROR, "Could not join the game", state.getLastError());
        usernameField.setBorder(invalidFieldBorder);
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private void configureInputs() {
        ((AbstractDocument) portField.getDocument()).setDocumentFilter(new DigitsOnlyFilter(5));
        hostField.getAccessibleContext().setAccessibleName("Server address");
        portField.getAccessibleContext().setAccessibleName("Port");
        usernameField.getAccessibleContext().setAccessibleName("Username");
        hostField.setToolTipText("The IP address or host name of the quiz server");
        portField.setToolTipText("The server port (1-65535)");
        usernameField.setToolTipText("1-" + ClientCommands.MAX_USERNAME_LENGTH + " letters, digits, _ or -");

        // Enter in any field connects; editing a field removes its error highlight.
        for (JTextField field : new JTextField[] {hostField, portField, usernameField}) {
            field.addActionListener(e -> connect());
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    field.setBorder(normalFieldBorder);
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    field.setBorder(normalFieldBorder);
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    // attribute changes only
                }
            });
        }
        connectButton.addActionListener(e -> connect());
        cancelButton.addActionListener(e -> cancel());
        progressBar.setPreferredSize(new Dimension(10, 4));
        progressBar.setBorder(null);
        progressBar.setBorderPainted(false);
        progressBar.setVisible(false);
    }

    /** A centred card inside a scroll pane, so the form stays usable in a small window. */
    private JComponent createScrollableContent() {
        JPanel centred = new JPanel(new GridBagLayout());
        centred.setBackground(theme.getBackground());
        centred.setBorder(BorderFactory.createEmptyBorder(
                ThemeManager.SPACE_XL, ThemeManager.SPACE_XL, ThemeManager.SPACE_XL, ThemeManager.SPACE_XL));
        centred.add(createCard());

        JScrollPane scroll = new JScrollPane(centred);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(theme.getBackground());
        scroll.getVerticalScrollBar().setUnitIncrement(ThemeManager.SPACE_L);
        return scroll;
    }

    private JComponent createCard() {
        RoundedPanel card = new RoundedPanel(new GridBagLayout(), ThemeManager.SPACE_XXL) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(CARD_WIDTH, super.getPreferredSize().height);
            }
        };

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;

        card.add(createHeading(), c);

        // Server address and port share one row.
        c.gridy++;
        c.gridwidth = 1;
        c.insets = new Insets(ThemeManager.SPACE_XL, 0, ThemeManager.SPACE_XS, ThemeManager.SPACE_M);
        card.add(fieldLabel("Server address", hostField), c);
        c.gridx = 1;
        c.weightx = 0;
        c.insets = new Insets(ThemeManager.SPACE_XL, 0, ThemeManager.SPACE_XS, 0);
        card.add(fieldLabel("Port", portField), c);

        c.gridy++;
        c.gridx = 0;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 0, ThemeManager.SPACE_M);
        card.add(hostField, c);
        c.gridx = 1;
        c.weightx = 0;
        c.insets = new Insets(0, 0, 0, 0);
        card.add(portField, c);

        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;

        c.gridy++;
        c.insets = new Insets(ThemeManager.SPACE_L, 0, ThemeManager.SPACE_XS, 0);
        card.add(fieldLabel("Username", usernameField), c);

        c.gridy++;
        c.insets = new Insets(0, 0, 0, 0);
        card.add(usernameField, c);

        c.gridy++;
        c.insets = new Insets(ThemeManager.SPACE_XS, 0, 0, 0);
        card.add(new ThemedLabel("1-" + ClientCommands.MAX_USERNAME_LENGTH
                + " characters: letters, digits, _ or -", ThemedLabel.Style.CAPTION), c);

        c.gridy++;
        c.insets = new Insets(ThemeManager.SPACE_L, 0, 0, 0);
        card.add(statusBanner, c);

        c.gridy++;
        c.insets = new Insets(ThemeManager.SPACE_S, 0, 0, 0);
        card.add(progressBar, c);

        c.gridy++;
        c.insets = new Insets(ThemeManager.SPACE_XL, 0, 0, 0);
        card.add(createButtons(), c);
        return card;
    }

    private JComponent createHeading() {
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        AppLogo logo = new AppLogo(56);
        ThemedLabel title = new ThemedLabel("Join a quiz game", ThemedLabel.Style.TITLE);
        ThemedLabel subtitle = new ThemedLabel("Enter the server details and choose a username.",
                ThemedLabel.Style.SECONDARY);
        for (JComponent component : new JComponent[] {logo, title, subtitle}) {
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        heading.add(logo);
        heading.add(Box.createVerticalStrut(ThemeManager.SPACE_L));
        heading.add(title);
        heading.add(Box.createVerticalStrut(ThemeManager.SPACE_XS));
        heading.add(subtitle);
        return heading;
    }

    private JComponent createButtons() {
        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(Box.createHorizontalGlue());
        buttons.add(cancelButton);
        buttons.add(Box.createHorizontalStrut(ThemeManager.SPACE_S));
        buttons.add(connectButton);
        return buttons;
    }

    private static ThemedLabel fieldLabel(String text, JTextField field) {
        ThemedLabel label = new ThemedLabel(text, ThemedLabel.Style.BODY);
        label.setFont(ThemeManager.bodyBoldFont());
        label.setLabelFor(field);
        return label;
    }

    /** Lets only digits be typed, up to a maximum length. */
    private static final class DigitsOnlyFilter extends DocumentFilter {

        private final int maxLength;

        DigitsOnlyFilter(int maxLength) {
            this.maxLength = maxLength;
        }

        @Override
        public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs)
                throws BadLocationException {
            replace(fb, offset, 0, text, attrs);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            String digits = text == null ? "" : text.replaceAll("[^0-9]", "");
            int room = maxLength - (fb.getDocument().getLength() - length);
            if (digits.length() > room) {
                digits = digits.substring(0, Math.max(0, room));
            }
            fb.replace(offset, length, digits, attrs);
        }
    }

    // ------------------------------------------------------------------
    // Package-private access for tests
    // ------------------------------------------------------------------

    JTextField hostField() {
        return hostField;
    }

    JTextField portField() {
        return portField;
    }

    JTextField usernameField() {
        return usernameField;
    }

    ThemedButton connectButton() {
        return connectButton;
    }

    ThemedButton cancelButton() {
        return cancelButton;
    }

    StatusBanner statusBanner() {
        return statusBanner;
    }

    JProgressBar progressBar() {
        return progressBar;
    }

    boolean isFieldMarkedInvalid(JTextField field) {
        return field.getBorder() == invalidFieldBorder;
    }
}
