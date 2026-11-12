package quiz.gui.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

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
import quiz.gui.model.Leaderboard;
import quiz.gui.model.PlayerScore;
import quiz.gui.model.QuestionData;
import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;
import quiz.gui.ui.components.RoundedPanel;
import quiz.gui.ui.components.StatusBanner;
import quiz.gui.ui.components.ThemedButton;
import quiz.gui.ui.components.ThemedLabel;

/**
 * The game over screen: the winner (or the tied winners), the winning score, the final
 * leaderboard and, for the host, Play Again. It is shown after GAME_OVER and stays until
 * the next game starts.
 *
 * <h2>Where the data comes from</h2>
 * Everything is read from the controller's {@link GameState}:
 * <ul>
 *   <li>the winners and the winning score come from GAME_OVER
 *       ({@link GameState#getWinners()}, {@link GameState#getBestScore()},
 *       {@link GameState#isTie()}, {@link GameState#isWinner()}); this screen never works
 *       them out from the scores;</li>
 *   <li>the final leaderboard is the last SCORES, in the server's order. Equal scores share
 *       a rank (1, 1, 3), the same as on the round results screen.</li>
 * </ul>
 * The final results are only read while the phase is GAME_OVER. When the next game starts
 * (GAME_STARTED clears the scores in the model) the screen keeps showing the last results
 * until the {@link ScreenNavigator} switches to the quiz screen.
 *
 * <h2>Play Again</h2>
 * Only the host sees Play Again. It calls {@link GameController#startGame()} and is enabled
 * only while {@link GameController#canStartGame()} is true, so a second START cannot be
 * sent while the first one waits for the server's answer. The other players see who they
 * are waiting for. A server ERROR that arrives after GAME_OVER (for example a refused
 * START) is shown below the results, which stay on screen; it disappears when the lobby
 * changes (a player joins or leaves, or the host changes) or when Play Again is pressed again.
 *
 * <p>This screen reads the controller's {@link GameState} and never changes it; it is
 * updated on the event dispatch thread through the controller's state listener.</p>
 */
public final class GameOverScreen extends JPanel {

    static final String PLAY_AGAIN_TEXT = "Play Again";
    static final String STARTING_TEXT = "Starting…";

    /** Base width of each column card; the remaining width is shared by the column weights. */
    private static final int COLUMN_BASE_WIDTH = 280;

    private final GameController controller;
    private final GameController.StateListener listener = this::render;
    private final Theme theme;

    private final ThemedLabel title = new ThemedLabel("Game Over", ThemedLabel.Style.TITLE);
    private final ThemedLabel subtitle = new ThemedLabel(" ", ThemedLabel.Style.SECONDARY);
    private final ThemedButton leaveButton = new ThemedButton("Leave Game", ThemedButton.Variant.SECONDARY);

    private final Trophy trophy = new Trophy();
    private final ThemedLabel headline = new ThemedLabel(" ", ThemedLabel.Style.HEADING);
    private final JTextArea winnerNames;
    private final ThemedLabel winningScore = new ThemedLabel(" ", ThemedLabel.Style.BODY);
    private final StatusBanner resultBanner = new StatusBanner();
    private final StatusBanner statusBanner = new StatusBanner();
    private final ThemedButton playAgainButton = new ThemedButton(PLAY_AGAIN_TEXT, ThemedButton.Variant.PRIMARY);

    private final ThemedLabel playerCount = new ThemedLabel(" ", ThemedLabel.Style.CAPTION);
    private final JPanel scoreRows = new JPanel();

    private boolean listening;
    /** True if the phase was GAME_OVER at the last render; used to notice a new game over. */
    private boolean wasGameOver;

    /** What the score rows were built from; they are only rebuilt when this changes. */
    private Leaderboard shownLeaderboard;
    private List<String> shownWinners;
    private String shownSelf;

    /*
     * Server errors. Errors are compared by identity (==): every ERROR line from the server
     * is a new String, so the same text sent twice still counts as a new error, and an
     * error from before GAME_OVER (for example a rejected answer) is never shown here.
     */
    /** The last error when GAME_OVER arrived or Play Again was pressed; it is not shown. */
    private String errorBaseline;
    /** The newer error being shown, and the lobby it was shown for. */
    private String shownError;
    private List<String> errorPlayers;
    private String errorHost;

    public GameOverScreen(GameController controller) {
        super(new BorderLayout(0, ThemeManager.SPACE_XL));
        this.controller = Objects.requireNonNull(controller, "controller");
        this.theme = ThemeManager.current();
        this.winnerNames = ScreenParts.wrappingText(ThemeManager.bodyFont(), theme.getTextSecondary());
        setBackground(theme.getBackground());
        setBorder(ScreenParts.screenPadding());

        playAgainButton.addActionListener(e -> playAgain());
        playAgainButton.setToolTipText("Start a new game with everyone in the lobby");
        leaveButton.addActionListener(e -> controller.disconnect());
        leaveButton.setToolTipText("Leave the game and return to the connection screen");

        add(ScreenParts.header(title, subtitle, leaveButton), BorderLayout.NORTH);
        add(createBody(), BorderLayout.CENTER);
        showNoResultsYet();
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

    private void playAgain() {
        GameState state = controller.getState();
        if (!controller.canStartGame()) {
            render(state); // e.g. a player just left: show why
            return;
        }
        // Only an error that arrives after this click is the server's answer to it.
        errorBaseline = state.getLastError();
        shownError = null;
        controller.startGame(); // notifies listeners, which disables the button
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void render(GameState state) {
        boolean gameOver = state.getPhase() == GamePhase.GAME_OVER;
        if (gameOver) {
            if (!wasGameOver) {
                // A new game over: errors from before it belong to the game, not to this screen.
                errorBaseline = state.getLastError();
                shownError = null;
            }
            showFinalResults(state);
        }
        // Otherwise keep the last final results on screen (see the class comment).
        wasGameOver = gameOver;
        updateStatus(state, gameOver);
        revalidate();
        repaint();
    }

    /** Before the first GAME_OVER there is nothing to show. */
    private void showNoResultsYet() {
        headline.setText("Waiting for the final results");
        winnerNames.setText("The results appear here when the game is over.");
        winningScore.setText(" ");
        trophy.setWon(false);
        resultBanner.clear();
        rebuildScoreRows(Leaderboard.empty(), List.of(), null, "No scores yet.");
    }

    private void showFinalResults(GameState state) {
        List<String> winners = state.getWinners();
        String self = state.getCurrentPlayer();
        int bestScore = state.getBestScore();
        QuestionData lastQuestion = state.getCurrentQuestion();

        // Header
        StringBuilder sub = new StringBuilder("Final results");
        if (lastQuestion != null) {
            int total = lastQuestion.totalRounds();
            sub.append(" · ").append(total == 1 ? "1 question" : total + " questions");
        }
        if (self != null) {
            sub.append(" · Playing as ").append(self);
        }
        subtitle.setText(sub.toString());

        // Winner card
        trophy.setWon(!winners.isEmpty());
        if (winners.isEmpty()) {
            headline.setText("No winner");
            winnerNames.setText("The game ended without a winner.");
            winningScore.setText(" ");
        } else if (state.isTie()) {
            headline.setText("It's a tie!");
            winnerNames.setText("Winners: " + joinNames(winners, self));
            winningScore.setText("Winning score: " + points(bestScore) + " each");
        } else {
            String winner = winners.get(0);
            headline.setText(winner.equals(self) ? "You win!" : winner + " wins!");
            winnerNames.setText("Winner: " + joinNames(winners, self));
            winningScore.setText("Winning score: " + points(bestScore));
        }
        showOwnResult(state, winners, self, bestScore);

        // Final leaderboard (rebuilt only when it changed)
        Leaderboard leaderboard = state.getLeaderboard();
        if (!leaderboard.equals(shownLeaderboard) || !winners.equals(shownWinners)
                || !Objects.equals(self, shownSelf)) {
            rebuildScoreRows(leaderboard, winners, self, "No final scores were received.");
        }
    }

    /** The local player's own result, below the winner. */
    private void showOwnResult(GameState state, List<String> winners, String self, int bestScore) {
        if (self == null || winners.isEmpty()) {
            resultBanner.clear();
        } else if (state.isWinner() && state.isTie()) {
            resultBanner.showStatus(StatusBanner.Kind.SUCCESS, "You share the win!",
                    "You finished joint first with " + points(bestScore) + ".");
        } else if (state.isWinner()) {
            resultBanner.showStatus(StatusBanner.Kind.SUCCESS, "Congratulations!",
                    "You won with " + points(bestScore) + ".");
        } else {
            OptionalInt own = state.getLeaderboard().scoreOf(self);
            resultBanner.showStatus(StatusBanner.Kind.INFO, "Good game!",
                    own.isPresent() ? "You scored " + points(own.getAsInt()) + "." : "Your final score was not received.");
        }
    }

    /** Play Again for the host, a waiting message for everyone else, and server errors. */
    private void updateStatus(GameState state, boolean gameOver) {
        boolean isHost = state.isHost();
        // START was sent and the server has not answered yet (see LobbyScreen).
        boolean startPending = gameOver && controller.hasActiveConnection()
                && state.canStartGame() && !controller.canStartGame();

        playAgainButton.setVisible(gameOver && isHost);
        playAgainButton.setEnabled(gameOver && isHost && controller.canStartGame());
        playAgainButton.setText(startPending ? STARTING_TEXT : PLAY_AGAIN_TEXT);

        int count = state.getPlayers().size();
        playerCount.setText(gameOver ? (count == 1 ? "1 player in the lobby" : count + " players in the lobby") : " ");

        if (!gameOver) {
            statusBanner.clear();
            return;
        }

        Optional<String> error = currentError(state);
        Optional<String> blocked = state.startBlockedReason();
        String host = state.getHost();

        if (startPending) {
            statusBanner.showStatus(StatusBanner.Kind.PROGRESS, "Starting a new game…",
                    "Waiting for the server to confirm.");
        } else if (error.isPresent()) {
            statusBanner.showStatus(StatusBanner.Kind.ERROR, "The server refused the request", error.get());
        } else if (!isHost) {
            statusBanner.showStatus(StatusBanner.Kind.PROGRESS, "Waiting for the host",
                    "Waiting for " + (host != null ? host : "the host") + " to start a new game.");
        } else if (blocked.isPresent()) {
            statusBanner.showStatus(StatusBanner.Kind.WARNING, "Waiting for more players",
                    blocked.get() + " to play again.");
        } else {
            statusBanner.showStatus(StatusBanner.Kind.SUCCESS, "Ready to play again",
                    "Press Play Again to start a new game with " + count + " players.");
        }
    }

    /**
     * A server error that arrived after GAME_OVER (or after the last Play Again click), if
     * it is still relevant: it is hidden once the lobby changes, so for example "At least
     * two players are required" disappears when a second player joins.
     */
    private Optional<String> currentError(GameState state) {
        String error = state.getLastError();
        if (error == null || error == errorBaseline) {
            return Optional.empty();
        }
        if (error != shownError) {
            // A new ERROR line from the server.
            shownError = error;
            errorPlayers = state.getPlayers();
            errorHost = state.getHost();
        }
        boolean lobbyChanged = !state.getPlayers().equals(errorPlayers)
                || !Objects.equals(state.getHost(), errorHost);
        return lobbyChanged ? Optional.empty() : Optional.of(error);
    }

    /** Replaces all score rows with the given leaderboard (never appends). */
    private void rebuildScoreRows(Leaderboard leaderboard, List<String> winners, String self, String emptyText) {
        shownLeaderboard = leaderboard;
        shownWinners = winners;
        shownSelf = self;

        scoreRows.removeAll();
        if (leaderboard.isEmpty()) {
            ThemedLabel empty = new ThemedLabel(emptyText, ThemedLabel.Style.SECONDARY);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            scoreRows.add(empty);
        }
        List<PlayerScore> entries = leaderboard.entries();
        int rank = 0;
        for (int i = 0; i < entries.size(); i++) {
            PlayerScore entry = entries.get(i);
            // Equal scores share a rank (1, 1, 3); the server already sorted the list.
            if (i == 0 || entry.score() != entries.get(i - 1).score()) {
                rank = i + 1;
            }
            String name = entry.username();
            JComponent row = scoreRow(rank, entry, name.equals(self), winners.contains(name));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            scoreRows.add(row);
            scoreRows.add(Box.createVerticalStrut(ThemeManager.SPACE_S));
        }
        scoreRows.revalidate();
        scoreRows.repaint();
    }

    private JComponent scoreRow(int rank, PlayerScore entry, boolean isSelf, boolean isWinner) {
        RoundedPanel row = new RoundedPanel(new BorderLayout(ThemeManager.SPACE_M, 0), ThemeManager.SPACE_S + 2);
        row.setFillColor(theme.getInputBackground());
        if (isWinner) {
            row.setBorderColor(theme.getSuccess());
            row.setBorderWidth(2);
        } else if (isSelf) {
            row.setBorderColor(theme.getAccent());
        }
        row.setName("score:" + entry.username());

        ThemedLabel rankLabel = new ThemedLabel(String.valueOf(rank), ThemedLabel.Style.SECONDARY);
        rankLabel.setFont(ThemeManager.bodyBoldFont());
        rankLabel.setPreferredSize(new Dimension(24, rankLabel.getPreferredSize().height));

        ThemedLabel nameLabel = new ThemedLabel(entry.username() + (isSelf ? " (you)" : ""), ThemedLabel.Style.BODY);
        if (isSelf) {
            nameLabel.setFont(ThemeManager.bodyBoldFont());
        }

        JPanel right = new JPanel(new BorderLayout(ThemeManager.SPACE_M, 0));
        right.setOpaque(false);
        if (isWinner) {
            ThemedLabel badge = new ThemedLabel("Winner", ThemedLabel.Style.CAPTION);
            badge.setFont(ThemeManager.smallFont().deriveFont(Font.BOLD));
            badge.setForeground(theme.getSuccess());
            right.add(badge, BorderLayout.WEST);
        }
        int score = entry.score();
        ThemedLabel scoreLabel = new ThemedLabel(score == 1 ? "1 pt" : score + " pts", ThemedLabel.Style.BODY);
        scoreLabel.setFont(ThemeManager.bodyBoldFont());
        scoreLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        right.add(scoreLabel, BorderLayout.EAST);

        row.add(rankLabel, BorderLayout.WEST);
        row.add(nameLabel, BorderLayout.CENTER);
        row.add(right, BorderLayout.EAST);
        row.getAccessibleContext().setAccessibleName(rank + ". " + entry.username()
                + (isSelf ? ", you" : "") + (isWinner ? ", winner" : "") + ", " + points(entry.score()));
        // Rows keep their natural height; extra space stays below the list.
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** "alice", "alice and bob", "alice, bob and carol"; the local player is marked "(you)". */
    static String joinNames(List<String> names, String self) {
        List<String> shown = new ArrayList<>();
        for (String name : names) {
            shown.add(name.equals(self) ? name + " (you)" : name);
        }
        if (shown.size() <= 1) {
            return String.join("", shown);
        }
        return String.join(", ", shown.subList(0, shown.size() - 1)) + " and " + shown.get(shown.size() - 1);
    }

    private static String points(int score) {
        return score == 1 ? "1 point" : score + " points";
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    /** The winner on the left, the final leaderboard on the right. */
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
        body.add(createWinnerCard(), c);

        c.gridx = 1;
        c.weightx = 0.45;
        c.insets = new Insets(0, 0, 0, 0);
        body.add(createLeaderboardCard(), c);
        return body;
    }

    /**
     * A card whose preferred width is fixed, so the two columns share the width by their
     * GridBag weights instead of by their content.
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

    private JComponent createWinnerCard() {
        RoundedPanel card = columnCard(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;

        // Trophy on the left, the winner text next to it.
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        for (JComponent part : List.of(headline, winnerNames, winningScore)) {
            part.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        winningScore.setFont(ThemeManager.bodyBoldFont());
        text.add(headline);
        text.add(Box.createVerticalStrut(ThemeManager.SPACE_XS));
        text.add(winnerNames);
        text.add(Box.createVerticalStrut(ThemeManager.SPACE_XS));
        text.add(winningScore);

        JPanel winner = new JPanel(new BorderLayout(ThemeManager.SPACE_L, 0));
        winner.setOpaque(false);
        JPanel trophyHolder = new JPanel(new BorderLayout());
        trophyHolder.setOpaque(false);
        trophyHolder.add(trophy, BorderLayout.NORTH);
        winner.add(trophyHolder, BorderLayout.WEST);
        winner.add(text, BorderLayout.CENTER);
        card.add(winner, c);

        c.gridy++;
        c.insets = new Insets(ThemeManager.SPACE_L, 0, 0, 0);
        card.add(resultBanner, c);

        c.gridy++;
        c.insets = new Insets(ThemeManager.SPACE_M, 0, 0, 0);
        card.add(statusBanner, c);

        c.gridy++;
        c.insets = new Insets(ThemeManager.SPACE_L, 0, 0, 0);
        card.add(playAgainButton, c);

        // Push everything to the top of the card.
        c.gridy++;
        c.weighty = 1;
        c.insets = new Insets(0, 0, 0, 0);
        card.add(Box.createVerticalGlue(), c);
        return card;
    }

    private JComponent createLeaderboardCard() {
        RoundedPanel card = columnCard(new BorderLayout(0, ThemeManager.SPACE_L));

        JPanel heading = new JPanel(new BorderLayout(ThemeManager.SPACE_S, 0));
        heading.setOpaque(false);
        heading.add(new ThemedLabel("Final Leaderboard", ThemedLabel.Style.HEADING), BorderLayout.WEST);
        playerCount.setHorizontalAlignment(SwingConstants.RIGHT);
        heading.add(playerCount, BorderLayout.EAST);

        scoreRows.setOpaque(false);
        scoreRows.setLayout(new BoxLayout(scoreRows, BoxLayout.Y_AXIS));
        JPanel rowsHolder = new JPanel(new BorderLayout());
        rowsHolder.setOpaque(false);
        rowsHolder.add(scoreRows, BorderLayout.NORTH); // rows stay at the top

        JScrollPane scroll = new JScrollPane(rowsHolder,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(ThemeManager.SPACE_L);
        scroll.setPreferredSize(new Dimension(240, 200));

        card.add(heading, BorderLayout.NORTH);
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    // ------------------------------------------------------------------
    // Small painted pieces
    // ------------------------------------------------------------------

    /** A simple trophy cup: the success colour when there is a winner, muted otherwise. */
    private final class Trophy extends JComponent {

        private static final int SIZE = 56;

        private boolean won;

        Trophy() {
            Dimension size = new Dimension(SIZE, SIZE);
            setPreferredSize(size);
            setMinimumSize(size);
        }

        void setWon(boolean won) {
            this.won = won;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int x = (getWidth() - SIZE) / 2;
                int y = (getHeight() - SIZE) / 2;
                Color color = won ? theme.getSuccess() : theme.getBorder();
                g2.setColor(color);
                // Bowl: a rectangle with a half circle below it.
                g2.fillRect(x + 14, y + 6, 28, 14);
                g2.fillArc(x + 14, y + 6, 28, 28, 180, 180);
                // Handles
                g2.setStroke(new BasicStroke(3));
                g2.drawArc(x + 6, y + 9, 14, 14, 90, 180);
                g2.drawArc(x + 36, y + 9, 14, 14, -90, 180);
                // Stem and base
                g2.fillRect(x + 25, y + 33, 6, 9);
                g2.fillRoundRect(x + 16, y + 42, 24, 7, 4, 4);
            } finally {
                g2.dispose();
            }
        }
    }

    // ------------------------------------------------------------------
    // Package-private access for tests
    // ------------------------------------------------------------------

    String titleText() {
        return title.getText();
    }

    String subtitleText() {
        return subtitle.getText();
    }

    String headlineText() {
        return headline.getText();
    }

    String winnerNamesText() {
        return winnerNames.getText();
    }

    String winningScoreText() {
        return winningScore.getText().trim();
    }

    StatusBanner resultBanner() {
        return resultBanner;
    }

    StatusBanner statusBanner() {
        return statusBanner;
    }

    ThemedButton playAgainButton() {
        return playAgainButton;
    }

    ThemedButton leaveButton() {
        return leaveButton;
    }

    String playerCountText() {
        return playerCount.getText().trim();
    }

    /** The score rows as "rank. name[, you][, winner], N points", in display order. */
    List<String> scoreRowDescriptions() {
        List<String> rows = new ArrayList<>();
        for (Component component : scoreRows.getComponents()) {
            if (component instanceof RoundedPanel row && row.getName() != null) {
                rows.add(row.getAccessibleContext().getAccessibleName());
            }
        }
        return rows;
    }

    /** The text shown when the leaderboard has no rows, or empty if there are rows. */
    String emptyLeaderboardText() {
        for (Component component : scoreRows.getComponents()) {
            if (component instanceof ThemedLabel label) {
                return label.getText();
            }
        }
        return "";
    }
}
