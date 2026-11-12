package quiz.gui.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import javax.swing.text.DefaultCaret;

import quiz.gui.controller.GameController;
import quiz.gui.model.AnswerOption;
import quiz.gui.model.AnswerState;
import quiz.gui.model.GamePhase;
import quiz.gui.model.GameState;
import quiz.gui.model.Leaderboard;
import quiz.gui.model.PlayerScore;
import quiz.gui.model.QuestionData;
import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;
import quiz.gui.ui.components.AnswerButton;
import quiz.gui.ui.components.RoundedPanel;
import quiz.gui.ui.components.StatusBanner;
import quiz.gui.ui.components.ThemedButton;
import quiz.gui.ui.components.ThemedLabel;

/**
 * The round results screen: the correct answer, the player's own result, and the latest
 * leaderboard. It is shown after ROUND_RESULT and stays until the next QUESTION.
 *
 * <h2>Server order</h2>
 * The server sends {@code ROUND_RESULT}, then {@code SCORES}, and about three seconds
 * later the next {@code QUESTION}. After the last round it sends {@code ROUND_RESULT},
 * {@code SCORES}, {@code SCORES} and {@code GAME_OVER} at once. This screen simply shows
 * the current {@link GameState} after every change, so:
 * <ul>
 *   <li>the leaderboard is rebuilt from the latest SCORES (never appended to), so a
 *       repeated SCORES never creates duplicate rows;</li>
 *   <li>GAME_OVER keeps the last round's result and the final scores on screen
 *       ({@link GameState#onGameOver} does not clear them).</li>
 * </ul>
 *
 * <h2>After the game</h2>
 * After GAME_OVER the {@link ScreenNavigator} shows the {@link GameOverScreen} (winners,
 * final leaderboard and Play Again), so this screen is no longer visible then. Only if no
 * game over screen is registered does this screen stay visible as a fallback: the host
 * gets Play Again ({@link GameController#startGame()}, the same rules as the lobby) and the
 * other players see who they are waiting for. Winners are not shown here.
 *
 * <h2>Small windows</h2>
 * Below {@link #COMPACT_BELOW_HEIGHT} (for example at the 800x600 minimum window size) the
 * screen uses tighter vertical spacing: the same content in the same order, with less padding.
 * The round card's content also sits in a transparent scroll pane, so content that still
 * does not fit (a very long question) scrolls inside the card instead of being cut off.
 *
 * <p>This screen reads the controller's {@link GameState} and never changes it; it is
 * updated on the event dispatch thread through the controller's state listener.</p>
 */
public final class RoundResultsScreen extends JPanel {

    static final String PLAY_AGAIN_TEXT = "Play Again";
    static final String STARTING_TEXT = "Starting…";

    /**
     * Screens shorter than this many pixels use the compact spacing. At the 800x600 minimum
     * window the screen is about 506 px tall; at the default 1024x720 window about 626 px,
     * which fits the regular spacing.
     */
    static final int COMPACT_BELOW_HEIGHT = 620;

    /** Base width of each column card; the remaining width is shared by the column weights. */
    private static final int COLUMN_BASE_WIDTH = 280;

    private final GameController controller;
    private final GameController.StateListener listener = this::render;
    private final Theme theme;

    private final ThemedLabel title = new ThemedLabel(" ", ThemedLabel.Style.TITLE);
    private final ThemedLabel subtitle = new ThemedLabel(" ", ThemedLabel.Style.SECONDARY);
    private final ThemedButton leaveButton = new ThemedButton("Leave Game", ThemedButton.Variant.SECONDARY);

    private final JTextArea questionText;
    private final AnswerButton correctAnswer = new AnswerButton("?");
    private final AnswerButton yourAnswer = new AnswerButton("?");
    private final StatusBanner resultBanner = new StatusBanner();
    private final StatusBanner statusBanner = new StatusBanner();
    private final ThemedButton playAgainButton = new ThemedButton(PLAY_AGAIN_TEXT, ThemedButton.Variant.PRIMARY);

    private final ThemedLabel playerCount = new ThemedLabel(" ", ThemedLabel.Style.CAPTION);
    private final JPanel scoreRows = new JPanel();

    private boolean listening;

    // Layout parts whose spacing changes in compact mode (see applyCompact).
    private final List<RoundedPanel> columnCards = new ArrayList<>();
    private final RoundContent roundContent = new RoundContent();
    private final JScrollPane roundScroll = new JScrollPane(roundContent,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    /** Gaps above the round card's rows: regular and compact top inset per row. */
    private final List<RowGap> rowGaps = new ArrayList<>();
    private final Border regularAnswerBorder = correctAnswer.getBorder();
    private final Border regularBannerBorder = resultBanner.getBorder();
    private boolean compact;
    /** The question shown now (compared by identity: every QUESTION line is a new object). */
    private QuestionData shownQuestion;

    /** What the score rows were built from; they are only rebuilt when this changes. */
    private Leaderboard shownLeaderboard;
    private String shownSelf;

    /** True after Play Again: a new server ERROR is then shown as the refusal. */
    private boolean watchingStartError;
    /** The last error before Play Again; a different one came from the server after it. */
    private String errorBeforeStart;

    public RoundResultsScreen(GameController controller) {
        super(new BorderLayout(0, ThemeManager.SPACE_XL));
        this.controller = Objects.requireNonNull(controller, "controller");
        this.theme = ThemeManager.current();
        this.questionText = ScreenParts.wrappingText(ThemeManager.bodyFont(), theme.getTextSecondary());
        setBackground(theme.getBackground());
        setBorder(ScreenParts.screenPadding());

        // These two only display an answer; they cannot be clicked or focused.
        for (AnswerButton display : List.of(correctAnswer, yourAnswer)) {
            display.setEnabled(false);
            display.setFocusable(false);
        }
        correctAnswer.setName("correct-answer");
        yourAnswer.setName("your-answer");

        playAgainButton.addActionListener(e -> playAgain());
        leaveButton.addActionListener(e -> controller.disconnect());
        leaveButton.setToolTipText("Leave the game and return to the connection screen");

        add(ScreenParts.header(title, subtitle, leaveButton), BorderLayout.NORTH);
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

    /** Chooses the regular or compact spacing for the current height, then lays out. */
    @Override
    public void doLayout() {
        int height = getHeight();
        if (height > 0) {
            applyCompact(height < COMPACT_BELOW_HEIGHT);
        }
        super.doLayout();
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
        watchingStartError = true;
        errorBeforeStart = state.getLastError();
        controller.startGame(); // notifies listeners, which disables the button
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void render(GameState state) {
        GamePhase phase = state.getPhase();
        boolean gameOver = phase == GamePhase.GAME_OVER;
        QuestionData question = state.getCurrentQuestion();
        AnswerOption correct = state.getCorrectAnswer();
        AnswerOption sent = state.getSelectedAnswer();
        AnswerState answerState = state.getAnswerState();
        String self = state.getCurrentPlayer();

        // Header
        title.setText(question != null ? "Round " + question.round() + " Results" : "Round Results");
        String playingAs = self != null ? "Playing as " + self : "";
        subtitle.setText(question != null
                ? "Question " + question.round() + " of " + question.totalRounds()
                        + (playingAs.isEmpty() ? "" : " · " + playingAs)
                : playingAs.isEmpty() ? " " : playingAs);

        // This round (a new round starts at the top of the card, if it ever had to scroll)
        if (question != shownQuestion) {
            shownQuestion = question;
            roundScroll.getViewport().setViewPosition(new Point(0, 0));
        }
        questionText.setText(question != null ? question.text() : "");
        showAnswer(correctAnswer, question, correct,
                correct != null ? AnswerButton.Look.CORRECT : AnswerButton.Look.MUTED, "Waiting for the result…");
        showAnswer(yourAnswer, question, sent, yourAnswerLook(sent, correct, answerState), "No answer");
        showResult(sent, correct, answerState);

        // Leaderboard (rebuilt only when the server sent different scores)
        Leaderboard leaderboard = state.getLeaderboard();
        int count = leaderboard.size();
        playerCount.setText(count == 0 ? " " : count == 1 ? "1 player" : count + " players");
        if (!leaderboard.equals(shownLeaderboard) || !Objects.equals(self, shownSelf)) {
            rebuildScoreRows(leaderboard, self);
        }

        // What happens next
        if (!gameOver) {
            watchingStartError = false;
        }
        updateStatus(state, phase, question);
        revalidate();
        repaint();
    }

    private static void showAnswer(AnswerButton button, QuestionData question, AnswerOption option,
                                   AnswerButton.Look look, String emptyText) {
        if (question == null || option == null) {
            button.setLetter("–");
            button.setText(emptyText);
            button.setLook(AnswerButton.Look.MUTED);
        } else {
            button.setLetter(option.letter());
            button.setText(question.option(option));
            button.setLook(look);
        }
    }

    private static AnswerButton.Look yourAnswerLook(AnswerOption sent, AnswerOption correct, AnswerState answerState) {
        if (sent == null) {
            return AnswerButton.Look.MUTED;
        }
        if (correct == null) {
            return AnswerButton.Look.LOCKED;
        }
        if (answerState != AnswerState.ACCEPTED) {
            return AnswerButton.Look.MUTED; // sent, but the server did not count it
        }
        return sent == correct ? AnswerButton.Look.CORRECT : AnswerButton.Look.WRONG;
    }

    /** The player's own result for this round. */
    private void showResult(AnswerOption sent, AnswerOption correct, AnswerState answerState) {
        if (correct == null) {
            resultBanner.showStatus(StatusBanner.Kind.INFO, "No result for this round",
                    "The server did not send the correct answer for this round.");
        } else if (answerState == AnswerState.ACCEPTED && sent == correct) {
            resultBanner.showStatus(StatusBanner.Kind.SUCCESS, "Correct!",
                    "You answered " + sent.letter() + ".");
        } else if (answerState == AnswerState.ACCEPTED && sent != null) {
            resultBanner.showStatus(StatusBanner.Kind.ERROR, "Not quite",
                    "You answered " + sent.letter() + ". The correct answer was " + correct.letter() + ".");
        } else if (sent != null) {
            resultBanner.showStatus(StatusBanner.Kind.WARNING, "Answer not counted",
                    "Your answer " + sent.letter() + " was not accepted before the round closed.");
        } else {
            resultBanner.showStatus(StatusBanner.Kind.WARNING, "Time's up",
                    "You did not answer this round. The correct answer was " + correct.letter() + ".");
        }
    }

    /** The waiting state between rounds, or the Play Again state after GAME_OVER. */
    private void updateStatus(GameState state, GamePhase phase, QuestionData question) {
        boolean gameOver = phase == GamePhase.GAME_OVER;
        boolean isHost = state.isHost();
        // START was sent and the server has not answered yet (see LobbyScreen).
        boolean startPending = gameOver && controller.hasActiveConnection()
                && state.canStartGame() && !controller.canStartGame();

        playAgainButton.setVisible(gameOver && isHost);
        playAgainButton.setEnabled(gameOver && isHost && controller.canStartGame());
        playAgainButton.setText(startPending ? STARTING_TEXT : PLAY_AGAIN_TEXT);

        if (!gameOver) {
            // ROUND_RESULT is only accepted for the current question, so it is never null here.
            if (phase != GamePhase.ROUND_ENDED || question == null) {
                statusBanner.clear();
            } else if (question.round() >= question.totalRounds()) {
                statusBanner.showStatus(StatusBanner.Kind.PROGRESS, "That was the last question",
                        "Waiting for the final results from the server.");
            } else {
                statusBanner.showStatus(StatusBanner.Kind.PROGRESS, "Waiting for the next question",
                        "Question " + (question.round() + 1) + " of " + question.totalRounds()
                                + " starts in a moment.");
            }
            return;
        }

        String error = state.getLastError();
        boolean refused = watchingStartError && error != null && error != errorBeforeStart; // new ERROR line
        String host = state.getHost() != null ? state.getHost() : "the host";
        Optional<String> blocked = state.startBlockedReason();

        if (startPending) {
            statusBanner.showStatus(StatusBanner.Kind.PROGRESS, "Starting a new game…",
                    "Waiting for the server to confirm.");
        } else if (refused) {
            statusBanner.showStatus(StatusBanner.Kind.ERROR, "The server refused the request", error);
        } else if (!isHost) {
            statusBanner.showStatus(StatusBanner.Kind.PROGRESS, "Game over",
                    "Waiting for " + host + " to start a new game.");
        } else if (blocked.isPresent()) {
            statusBanner.showStatus(StatusBanner.Kind.WARNING, "Game over", blocked.get() + " to play again.");
        } else {
            statusBanner.showStatus(StatusBanner.Kind.SUCCESS, "Game over",
                    "Press Play Again to start a new game with everyone in the lobby.");
        }
    }

    /** Replaces all score rows with the given leaderboard (never appends). */
    private void rebuildScoreRows(Leaderboard leaderboard, String self) {
        shownLeaderboard = leaderboard;
        shownSelf = self;

        scoreRows.removeAll();
        if (leaderboard.isEmpty()) {
            ThemedLabel empty = new ThemedLabel("Waiting for scores…", ThemedLabel.Style.SECONDARY);
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
            JComponent row = scoreRow(rank, entry, entry.username().equals(self));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            scoreRows.add(row);
            scoreRows.add(Box.createVerticalStrut(ThemeManager.SPACE_S));
        }
        scoreRows.revalidate();
        scoreRows.repaint();
    }

    private JComponent scoreRow(int rank, PlayerScore entry, boolean isSelf) {
        RoundedPanel row = new RoundedPanel(new BorderLayout(ThemeManager.SPACE_M, 0), ThemeManager.SPACE_S + 2);
        row.setFillColor(theme.getInputBackground());
        if (isSelf) {
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

        int score = entry.score();
        ThemedLabel scoreLabel = new ThemedLabel(score == 1 ? "1 pt" : score + " pts", ThemedLabel.Style.BODY);
        scoreLabel.setFont(ThemeManager.bodyBoldFont());
        scoreLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        row.add(rankLabel, BorderLayout.WEST);
        row.add(nameLabel, BorderLayout.CENTER);
        row.add(scoreLabel, BorderLayout.EAST);
        row.getAccessibleContext().setAccessibleName(rank + ". " + entry.username()
                + (isSelf ? ", you" : "") + ", " + (score == 1 ? "1 point" : score + " points"));
        // Rows keep their natural height; extra space stays below the list.
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    /** The round on the left, the leaderboard on the right. */
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
        body.add(createRoundCard(), c);

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
    private RoundedPanel columnCard(LayoutManager layout) {
        RoundedPanel card = new RoundedPanel(layout) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(COLUMN_BASE_WIDTH, super.getPreferredSize().height);
            }

            @Override
            public Dimension getMinimumSize() {
                return new Dimension(COLUMN_BASE_WIDTH, super.getMinimumSize().height);
            }
        };
        columnCards.add(card); // its padding follows the compact mode
        return card;
    }

    private JComponent createRoundCard() {
        RoundedPanel card = columnCard(new BorderLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;

        roundContent.add(new ThemedLabel("This round", ThemedLabel.Style.HEADING), c);

        // Each row: component, gap above it (regular), gap above it (compact).
        addRow(questionText, c, ThemeManager.SPACE_XS, ThemeManager.SPACE_XS);
        addRow(new ThemedLabel("Correct answer", ThemedLabel.Style.CAPTION), c, ThemeManager.SPACE_L, ThemeManager.SPACE_S);
        addRow(correctAnswer, c, ThemeManager.SPACE_XS, ThemeManager.SPACE_XS);
        addRow(new ThemedLabel("Your answer", ThemedLabel.Style.CAPTION), c, ThemeManager.SPACE_M, ThemeManager.SPACE_S);
        addRow(yourAnswer, c, ThemeManager.SPACE_XS, ThemeManager.SPACE_XS);
        addRow(resultBanner, c, ThemeManager.SPACE_L, ThemeManager.SPACE_M);
        addRow(statusBanner, c, ThemeManager.SPACE_M, ThemeManager.SPACE_S);
        addRow(playAgainButton, c, ThemeManager.SPACE_L, ThemeManager.SPACE_M);

        // Push everything to the top of the card.
        c.gridy++;
        c.weighty = 1;
        c.insets = new Insets(0, 0, 0, 0);
        roundContent.add(Box.createVerticalGlue(), c);

        // The read-only text areas must not scroll the card when their text changes (a text
        // area's caret normally scrolls itself into view).
        for (JComponent part : List.of(questionText, resultBanner, statusBanner)) {
            keepCaretsStill(part);
        }

        // Transparent and without a border, like the leaderboard's scroll pane: a scroll bar
        // only appears if the content is taller than the card.
        roundScroll.setBorder(null);
        roundScroll.setOpaque(false);
        roundScroll.getViewport().setOpaque(false);
        roundScroll.getVerticalScrollBar().setUnitIncrement(ThemeManager.SPACE_L);
        card.add(roundScroll, BorderLayout.CENTER);
        return card;
    }

    /** Stops the carets of all text areas in {@code root} from moving (and scrolling) on text changes. */
    private static void keepCaretsStill(Component root) {
        if (root instanceof JTextArea area && area.getCaret() instanceof DefaultCaret caret) {
            caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                keepCaretsStill(child);
            }
        }
    }

    /** Adds the next row of the round card, remembering its regular and compact gap above. */
    private void addRow(JComponent component, GridBagConstraints c, int regularGap, int compactGap) {
        c.gridy++;
        c.insets = new Insets(regularGap, 0, 0, 0);
        roundContent.add(component, c);
        rowGaps.add(new RowGap(component, regularGap, compactGap));
    }

    /**
     * Switches between the regular and the compact spacing. Only vertical padding and gaps
     * change; the content, its order, the colours and the widths stay the same.
     */
    private void applyCompact(boolean compactNow) {
        if (compactNow == compact) {
            return;
        }
        compact = compactNow;

        // Screen padding and the gap between the header and the cards
        setBorder(compactNow
                ? BorderFactory.createEmptyBorder(ThemeManager.SPACE_M, ThemeManager.SPACE_XXL,
                        ThemeManager.SPACE_L, ThemeManager.SPACE_XXL)
                : ScreenParts.screenPadding());
        ((BorderLayout) getLayout()).setVgap(compactNow ? ThemeManager.SPACE_M : ThemeManager.SPACE_XL);

        // Card padding (top and bottom only, so text wraps at the same width)
        int cardPadding = compactNow ? ThemeManager.SPACE_M : ThemeManager.SPACE_XL;
        for (RoundedPanel card : columnCards) {
            card.setBorder(BorderFactory.createEmptyBorder(
                    cardPadding, ThemeManager.SPACE_XL, cardPadding, ThemeManager.SPACE_XL));
        }

        // Gaps between the round card's rows
        GridBagLayout layout = (GridBagLayout) roundContent.getLayout();
        for (RowGap row : rowGaps) {
            GridBagConstraints c = layout.getConstraints(row.component());
            c.insets = new Insets(compactNow ? row.compact() : row.regular(), 0, 0, 0);
            layout.setConstraints(row.component(), c);
        }

        // Answer and banner padding (top and bottom only)
        for (AnswerButton display : List.of(correctAnswer, yourAnswer)) {
            display.setBorder(compactNow
                    ? BorderFactory.createEmptyBorder(ThemeManager.SPACE_XS + 2, ThemeManager.SPACE_L,
                            ThemeManager.SPACE_XS + 2, ThemeManager.SPACE_L)
                    : regularAnswerBorder);
        }
        for (StatusBanner banner : List.of(resultBanner, statusBanner)) {
            banner.setBorder(compactNow
                    ? BorderFactory.createEmptyBorder(ThemeManager.SPACE_S, ThemeManager.SPACE_M,
                            ThemeManager.SPACE_S, ThemeManager.SPACE_M)
                    : regularBannerBorder);
        }
        roundContent.invalidate();
    }

    /** The gap above one row of the round card, in regular and in compact mode. */
    private record RowGap(JComponent component, int regular, int compact) {
    }

    /**
     * The round card's content. It always has the viewport's width, so text wraps instead of
     * scrolling sideways, and fills the viewport's height when it is shorter than the card.
     */
    private static final class RoundContent extends JPanel implements Scrollable {

        RoundContent() {
            super(new GridBagLayout());
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return ThemeManager.SPACE_L;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
        }
    }

    private JComponent createLeaderboardCard() {
        RoundedPanel card = columnCard(new BorderLayout(0, ThemeManager.SPACE_L));

        JPanel heading = new JPanel(new BorderLayout(ThemeManager.SPACE_S, 0));
        heading.setOpaque(false);
        heading.add(new ThemedLabel("Leaderboard", ThemedLabel.Style.HEADING), BorderLayout.WEST);
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
    // Package-private access for tests
    // ------------------------------------------------------------------

    String titleText() {
        return title.getText();
    }

    String subtitleText() {
        return subtitle.getText();
    }

    String questionTextValue() {
        return questionText.getText();
    }

    AnswerButton correctAnswerView() {
        return correctAnswer;
    }

    AnswerButton yourAnswerView() {
        return yourAnswer;
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

    /** True while the compact spacing is used (a short screen). */
    boolean isCompact() {
        return compact;
    }

    /** The scroll pane around the round card's content. */
    JScrollPane roundScrollPane() {
        return roundScroll;
    }

    /** The score rows as "rank. name[, you], N points", in display order. */
    List<String> scoreRowDescriptions() {
        List<String> rows = new ArrayList<>();
        for (Component component : scoreRows.getComponents()) {
            if (component instanceof RoundedPanel row && row.getName() != null) {
                rows.add(row.getAccessibleContext().getAccessibleName());
            }
        }
        return rows;
    }
}
