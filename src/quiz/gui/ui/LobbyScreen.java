package quiz.gui.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;

import quiz.gui.controller.GameController;
import quiz.gui.model.GamePhase;
import quiz.gui.model.GameState;
import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;
import quiz.gui.ui.components.RoundedPanel;
import quiz.gui.ui.components.StatusBanner;
import quiz.gui.ui.components.ThemedButton;
import quiz.gui.ui.components.ThemedLabel;

/**
 * The lobby: who is connected, who the host is, and (for the host) the Start Game button.
 *
 * <p>Everything shown here is read from the controller's {@link GameState} whenever it
 * changes. The only actions are {@link GameController#startGame()} and
 * {@link GameController#disconnect()}; screen changes (to the game, or back to the
 * connection screen) are made by the {@link ScreenNavigator}.</p>
 *
 * <ul>
 *   <li>Only the host sees the Start button. It is enabled only while
 *       {@link GameController#canStartGame()} is true, so a second START cannot be sent
 *       while the first one is waiting for the server's answer.</li>
 *   <li>After GAME_OVER the same button becomes "Play Again" and follows the same rules.</li>
 *   <li>An ERROR from the server (for example a refused START) is shown with the server's
 *       own wording until the lobby changes (a player joins or leaves, or the host changes).</li>
 * </ul>
 */
public final class LobbyScreen extends JPanel {

    private static final String START_TEXT = "Start Game";
    private static final String PLAY_AGAIN_TEXT = "Play Again";
    private static final String STARTING_TEXT = "Starting…";

    /** Base width of each column card; the remaining width is shared by the column weights. */
    private static final int COLUMN_BASE_WIDTH = 280;

    private final GameController controller;
    private final GameController.StateListener listener = this::render;
    private final Theme theme;

    private final ThemedLabel subtitle = new ThemedLabel(" ", ThemedLabel.Style.SECONDARY);
    private final Pill rolePill;
    private final ThemedButton disconnectButton = new ThemedButton("Disconnect", ThemedButton.Variant.SECONDARY);

    private final ThemedLabel playerCount = new ThemedLabel(" ", ThemedLabel.Style.CAPTION);
    private final JPanel playerRows = new JPanel();
    private final ThemedLabel latestActivity = new ThemedLabel(" ", ThemedLabel.Style.CAPTION);

    private final ThemedLabel roleTitle = new ThemedLabel(" ", ThemedLabel.Style.HEADING);
    private final JTextArea roleDescription = wrappingText();
    private final ThemedButton startButton = new ThemedButton(START_TEXT, ThemedButton.Variant.PRIMARY);
    private final StatusBanner statusBanner = new StatusBanner();

    private boolean listening;

    /** What the player rows were built from; they are only rebuilt when this changes. */
    private List<String> shownPlayers;
    private String shownHost;
    private String shownSelf;

    /** The server error being shown, and the lobby it was shown for (see {@link #currentError}). */
    private String trackedError;
    private List<String> errorPlayers;
    private String errorHost;

    public LobbyScreen(GameController controller) {
        super(new BorderLayout(0, ThemeManager.SPACE_XL));
        this.controller = Objects.requireNonNull(controller, "controller");
        this.theme = ThemeManager.current();
        this.rolePill = new Pill(" ", theme.getAccent(), theme.getOnAccent(), null);
        setBackground(theme.getBackground());
        setBorder(BorderFactory.createEmptyBorder(
                ThemeManager.SPACE_XL, ThemeManager.SPACE_XXL, ThemeManager.SPACE_XXL, ThemeManager.SPACE_XXL));

        startButton.addActionListener(e -> startGame());
        disconnectButton.addActionListener(e -> controller.disconnect());
        disconnectButton.setToolTipText("Leave the lobby and return to the connection screen");

        add(createHeader(), BorderLayout.NORTH);
        add(createBody(), BorderLayout.CENTER);
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

    void startListening() {
        if (listening) {
            return;
        }
        listening = true;
        controller.addStateListener(listener);
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

    private void startGame() {
        if (!controller.canStartGame()) {
            render(controller.getState()); // e.g. a player just left: show why
            return;
        }
        trackedError = null; // a new answer from the server must be shown, even with the same text
        controller.startGame(); // notifies listeners, which disables the button
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void render(GameState state) {
        String self = state.getCurrentPlayer();
        String host = state.getHost();
        boolean isHost = state.isHost();
        GamePhase phase = state.getPhase();
        boolean gameOver = phase == GamePhase.GAME_OVER;
        boolean inGame = phase == GamePhase.GAME_STARTING
                || phase == GamePhase.QUESTION_ACTIVE
                || phase == GamePhase.ROUND_ENDED;
        // START was sent and the server has not answered yet: the model would allow it,
        // but the controller does not, because one START is already waiting.
        boolean startPending = controller.hasActiveConnection()
                && state.canStartGame() && !controller.canStartGame();

        // Header
        subtitle.setText(self != null ? "Playing as " + self : "Joining the lobby…");
        rolePill.setText(isHost ? "Host" : "Player");
        rolePill.setVisible(self != null);

        // Players
        List<String> players = state.getPlayers();
        int count = players.size();
        playerCount.setText(count == 1 ? "1 player" : count + " players");
        if (!players.equals(shownPlayers) || !Objects.equals(host, shownHost) || !Objects.equals(self, shownSelf)) {
            rebuildPlayerRows(players, host, self);
        }
        String info = state.getLastInfo();
        latestActivity.setText(info != null && !info.isBlank() ? info : " ");

        // Role and Start button
        roleTitle.setText(isHost ? "You are the host" : "You are a player");
        if (isHost) {
            roleDescription.setText(gameOver
                    ? "Start a new game with everyone in the lobby when they are ready."
                    : "Start the game once everyone has joined.");
        } else if (host != null) {
            roleDescription.setText(host + " is the host. Only the host can start the game.");
        } else {
            roleDescription.setText("The server has not chosen a host yet. Only the host can start the game.");
        }
        startButton.setVisible(isHost);
        startButton.setEnabled(isHost && controller.canStartGame());
        startButton.setText(startPending ? STARTING_TEXT : gameOver ? PLAY_AGAIN_TEXT : START_TEXT);

        updateStatus(state, isHost, host, gameOver, inGame, startPending);
        revalidate();
        repaint();
    }

    private void updateStatus(GameState state, boolean isHost, String host,
                              boolean gameOver, boolean inGame, boolean startPending) {
        Optional<String> error = currentError(state);
        int count = state.getPlayers().size();
        String hostName = host != null ? host : "the host";

        if (startPending) {
            statusBanner.showStatus(StatusBanner.Kind.PROGRESS, "Starting the game…",
                    "Waiting for the server to confirm.");
        } else if (error.isPresent()) {
            statusBanner.showStatus(StatusBanner.Kind.ERROR, "The server refused the request", error.get());
        } else if (inGame) {
            statusBanner.showStatus(StatusBanner.Kind.INFO, "Game in progress", "The quiz has started.");
        } else if (!isHost) {
            statusBanner.showStatus(StatusBanner.Kind.PROGRESS,
                    gameOver ? "Game over" : "Waiting for the host",
                    gameOver
                            ? "Waiting for " + hostName + " to start a new game."
                            : "Waiting for " + hostName + " to start the game.");
        } else {
            Optional<String> blocked = state.startBlockedReason();
            if (blocked.isPresent()) {
                statusBanner.showStatus(StatusBanner.Kind.WARNING, "Waiting for more players",
                        blocked.get() + ". Ask a friend to connect to the same server.");
            } else {
                statusBanner.showStatus(StatusBanner.Kind.SUCCESS,
                        gameOver ? "Ready to play again" : "Ready to start",
                        count + " players are in the lobby.");
            }
        }
    }

    /**
     * The server error to show, if any. An error is shown until the lobby changes (players
     * or host), so an old refusal such as "At least two players are required" disappears
     * once a second player has joined.
     */
    private Optional<String> currentError(GameState state) {
        String error = state.getLastError();
        GamePhase phase = state.getPhase();
        if (error == null || (phase != GamePhase.LOBBY && phase != GamePhase.GAME_OVER)) {
            trackedError = null;
            return Optional.empty();
        }
        if (!error.equals(trackedError)) {
            trackedError = error;
            errorPlayers = state.getPlayers();
            errorHost = state.getHost();
            return Optional.of(error);
        }
        boolean lobbyChanged = !state.getPlayers().equals(errorPlayers) || !Objects.equals(state.getHost(), errorHost);
        return lobbyChanged ? Optional.empty() : Optional.of(error);
    }

    private void rebuildPlayerRows(List<String> players, String host, String self) {
        shownPlayers = List.copyOf(players);
        shownHost = host;
        shownSelf = self;

        playerRows.removeAll();
        if (players.isEmpty()) {
            ThemedLabel empty = new ThemedLabel("Waiting for the player list…", ThemedLabel.Style.SECONDARY);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            playerRows.add(empty);
        }
        for (String name : players) {
            JComponent row = playerRow(name, name.equals(host), name.equals(self));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            playerRows.add(row);
            playerRows.add(Box.createVerticalStrut(ThemeManager.SPACE_S));
        }
        playerRows.revalidate();
        playerRows.repaint();
    }

    private JComponent playerRow(String name, boolean isHost, boolean isSelf) {
        RoundedPanel row = new RoundedPanel(new BorderLayout(ThemeManager.SPACE_M, 0), ThemeManager.SPACE_S + 2);
        row.setFillColor(theme.getInputBackground());
        if (isSelf) {
            row.setBorderColor(theme.getAccent());
        }
        row.setName("player:" + name);

        ThemedLabel nameLabel = new ThemedLabel(name, ThemedLabel.Style.BODY);
        if (isSelf) {
            nameLabel.setFont(ThemeManager.bodyBoldFont());
        }

        JPanel badges = new JPanel(new GridBagLayout()); // centres the badges vertically
        badges.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, ThemeManager.SPACE_XS, 0, 0);
        if (isHost) {
            badges.add(new Pill("Host", theme.getAccent(), theme.getOnAccent(), null), c);
        }
        if (isSelf) {
            badges.add(new Pill("You", theme.getSurface(), theme.getTextPrimary(), theme.getBorder()), c);
        }

        row.add(new Avatar(name, isSelf), BorderLayout.WEST);
        row.add(nameLabel, BorderLayout.CENTER);
        row.add(badges, BorderLayout.EAST);
        row.getAccessibleContext().setAccessibleName(
                name + (isHost ? ", host" : "") + (isSelf ? ", you" : ""));
        // Rows keep their natural height; extra space stays below the list.
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private JComponent createHeader() {
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        titleRow.setOpaque(false);
        titleRow.add(new ThemedLabel("Lobby", ThemedLabel.Style.TITLE));
        titleRow.add(Box.createHorizontalStrut(ThemeManager.SPACE_M));
        titleRow.add(rolePill);

        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(titleRow);
        text.add(Box.createVerticalStrut(ThemeManager.SPACE_XS));
        text.add(subtitle);

        JPanel buttonHolder = new JPanel(new GridBagLayout()); // centres the button vertically
        buttonHolder.setOpaque(false);
        buttonHolder.add(disconnectButton);

        JPanel header = new JPanel(new BorderLayout(ThemeManager.SPACE_L, 0));
        header.setOpaque(false);
        header.add(text, BorderLayout.CENTER);
        header.add(buttonHolder, BorderLayout.EAST);
        return header;
    }

    /** Players on the left (wider), the game card on the right. */
    private JComponent createBody() {
        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.fill = GridBagConstraints.BOTH;
        c.weighty = 1;

        c.gridx = 0;
        c.weightx = 0.55;
        c.insets = new Insets(0, 0, 0, ThemeManager.SPACE_XL);
        body.add(createPlayersCard(), c);

        c.gridx = 1;
        c.weightx = 0.45;
        c.insets = new Insets(0, 0, 0, 0);
        body.add(createGameCard(), c);
        return body;
    }

    /**
     * A card whose preferred width is fixed, so the two columns share the width by their
     * GridBag weights instead of by their content (wrapped text would otherwise widen one).
     */
    private static RoundedPanel columnCard(LayoutManager layout) {
        return new RoundedPanel(layout) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(COLUMN_BASE_WIDTH, super.getPreferredSize().height);
            }

            @Override
            public Dimension getMinimumSize() {
                return new Dimension(COLUMN_BASE_WIDTH, super.getMinimumSize().height);
            }
        };
    }

    private JComponent createPlayersCard() {
        RoundedPanel card = columnCard(new BorderLayout(0, ThemeManager.SPACE_L));

        JPanel heading = new JPanel(new BorderLayout(ThemeManager.SPACE_S, 0));
        heading.setOpaque(false);
        heading.add(new ThemedLabel("Players", ThemedLabel.Style.HEADING), BorderLayout.WEST);
        playerCount.setHorizontalAlignment(SwingConstants.RIGHT);
        heading.add(playerCount, BorderLayout.EAST);

        playerRows.setOpaque(false);
        playerRows.setLayout(new BoxLayout(playerRows, BoxLayout.Y_AXIS));
        JPanel rowsHolder = new JPanel(new BorderLayout());
        rowsHolder.setOpaque(false);
        rowsHolder.add(playerRows, BorderLayout.NORTH); // rows stay at the top

        JScrollPane scroll = new JScrollPane(rowsHolder,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(ThemeManager.SPACE_L);
        scroll.setPreferredSize(new Dimension(240, 200));

        card.add(heading, BorderLayout.NORTH);
        card.add(scroll, BorderLayout.CENTER);
        card.add(latestActivity, BorderLayout.SOUTH);
        return card;
    }

    private JComponent createGameCard() {
        RoundedPanel card = columnCard(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;

        card.add(roleTitle, c);

        c.gridy++;
        c.insets = new Insets(ThemeManager.SPACE_XS, 0, 0, 0);
        card.add(roleDescription, c);

        c.gridy++;
        c.insets = new Insets(ThemeManager.SPACE_L, 0, 0, 0);
        card.add(statusBanner, c);

        c.gridy++;
        c.insets = new Insets(ThemeManager.SPACE_L, 0, 0, 0);
        card.add(startButton, c);

        // Push everything to the top of the card.
        c.gridy++;
        c.weighty = 1;
        c.insets = new Insets(0, 0, 0, 0);
        card.add(Box.createVerticalGlue(), c);
        return card;
    }

    private JTextArea wrappingText() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setColumns(16);
        area.setBorder(null);
        area.setFont(ThemeManager.bodyFont());
        area.setForeground(ThemeManager.current().getTextSecondary());
        // Wrapped text knows its real height only once it has a width: lay out again when it changes.
        area.addComponentListener(new ComponentAdapter() {
            private int lastWidth = -1;

            @Override
            public void componentResized(ComponentEvent e) {
                if (area.getWidth() != lastWidth) {
                    lastWidth = area.getWidth();
                    area.revalidate();
                }
            }
        });
        return area;
    }

    // ------------------------------------------------------------------
    // Small painted pieces
    // ------------------------------------------------------------------

    /** A small rounded badge, for example "Host" or "You". */
    private static final class Pill extends ThemedLabel {

        private final Color fill;
        private final Color outline;

        Pill(String text, Color fill, Color textColor, Color outline) {
            super(text, Style.CAPTION);
            this.fill = fill;
            this.outline = outline;
            setFont(ThemeManager.smallFont().deriveFont(Font.BOLD));
            setForeground(textColor);
            setBorder(BorderFactory.createEmptyBorder(2, ThemeManager.SPACE_S, 2, ThemeManager.SPACE_S));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int h = getHeight();
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth(), h, h, h);
                if (outline != null) {
                    g2.setColor(outline);
                    g2.drawRoundRect(0, 0, getWidth() - 1, h - 1, h, h);
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** A circle with the player's first letter. */
    private final class Avatar extends JComponent {

        private static final int SIZE = 32;

        private final String initial;
        private final boolean highlighted;

        Avatar(String name, boolean highlighted) {
            this.initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            this.highlighted = highlighted;
            Dimension size = new Dimension(SIZE, SIZE);
            setPreferredSize(size);
            setMinimumSize(size);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int x = (getWidth() - SIZE) / 2;
                int y = (getHeight() - SIZE) / 2;
                g2.setColor(highlighted ? theme.getAccent() : theme.getHover());
                g2.fillOval(x, y, SIZE, SIZE);
                g2.setFont(ThemeManager.bodyBoldFont());
                g2.setColor(highlighted ? theme.getOnAccent() : theme.getTextPrimary());
                FontMetrics metrics = g2.getFontMetrics();
                g2.drawString(initial, x + (SIZE - metrics.stringWidth(initial)) / 2,
                        y + (SIZE - metrics.getHeight()) / 2 + metrics.getAscent());
            } finally {
                g2.dispose();
            }
        }
    }

    // ------------------------------------------------------------------
    // Package-private access for tests
    // ------------------------------------------------------------------

    ThemedButton startButton() {
        return startButton;
    }

    ThemedButton disconnectButton() {
        return disconnectButton;
    }

    StatusBanner statusBanner() {
        return statusBanner;
    }

    String subtitleText() {
        return subtitle.getText();
    }

    String rolePillText() {
        return rolePill.isVisible() ? rolePill.getText() : "";
    }

    String roleTitleText() {
        return roleTitle.getText();
    }

    String roleDescriptionText() {
        return roleDescription.getText();
    }

    String playerCountText() {
        return playerCount.getText();
    }

    String latestActivityText() {
        return latestActivity.getText().trim();
    }

    /** The player rows as "name" plus " [host]" / " [you]" markers, in display order. */
    List<String> playerRowDescriptions() {
        List<String> rows = new ArrayList<>();
        for (Component component : playerRows.getComponents()) {
            if (component instanceof RoundedPanel row && row.getName() != null) {
                rows.add(row.getAccessibleContext().getAccessibleName());
            }
        }
        return rows;
    }
}
