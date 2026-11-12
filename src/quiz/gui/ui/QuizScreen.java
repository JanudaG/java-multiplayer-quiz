package quiz.gui.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import javax.swing.Box;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.Timer;

import quiz.gui.controller.GameController;
import quiz.gui.model.AnswerOption;
import quiz.gui.model.AnswerState;
import quiz.gui.model.GamePhase;
import quiz.gui.model.GameState;
import quiz.gui.model.QuestionData;
import quiz.gui.theme.Theme;
import quiz.gui.theme.ThemeManager;
import quiz.gui.ui.components.AnswerButton;
import quiz.gui.ui.components.RoundedPanel;
import quiz.gui.ui.components.StatusBanner;
import quiz.gui.ui.components.ThemedButton;
import quiz.gui.ui.components.ThemedLabel;

/**
 * The question screen: question number, question text, the four answers, a countdown and
 * the Submit Answer button.
 *
 * <h2>Answering</h2>
 * <ol>
 *   <li>Clicking an answer only <em>selects</em> it (a choice that stays on this screen;
 *       it can be changed).</li>
 *   <li>Submit Answer sends it with {@link GameController#submitAnswer}. The controller
 *       allows one answer per round; afterwards the answers and the button are disabled.</li>
 *   <li>The status shows "Answer sent" until the server's ANSWER_ACCEPTED, then
 *       "Answer locked in". A server ERROR for the answer is shown with its own wording.</li>
 * </ol>
 *
 * <h2>Countdown</h2>
 * The countdown starts when this screen sees a new QUESTION and counts down the seconds
 * that came with it. It is only an approximation: when it reaches zero the answers are
 * disabled and "Time's up" is shown, but the round only ends when the server sends
 * ROUND_RESULT (the {@link ScreenNavigator} then shows the round results screen). The
 * clock can be replaced in tests, so no test has to wait for real time.
 *
 * <p>The server's own timer starts when it <em>sends</em> the QUESTION, and it sends
 * ROUND_RESULT as soon as that timer ends; this countdown starts when the QUESTION
 * <em>arrives</em>. So the local zero is never before the server's deadline ("Time's up" is
 * never shown while the server still accepts answers), but ROUND_RESULT usually arrives at
 * about the same moment, and the results screen then replaces this screen. The "Time's up"
 * state here is only visible when ROUND_RESULT is late (a slow network or a busy server);
 * otherwise the results screen shows "Time's up" for an unanswered round.</p>
 *
 * <p>This screen reads the controller's {@link GameState} and never changes it; it is
 * updated on the event dispatch thread through the controller's state listener.</p>
 */
public final class QuizScreen extends JPanel {

    static final String SUBMIT_TEXT = "Submit Answer";
    static final String SENDING_TEXT = "Sending…";
    static final String LOCKED_TEXT = "Answer Locked In";

    /** How often the countdown is refreshed, in milliseconds. */
    private static final int TICK_MS = 200;
    /** From this many seconds left, the countdown is shown in the warning colour. */
    private static final int LOW_TIME_SECONDS = 5;

    private final GameController controller;
    private final GameController.StateListener listener = this::render;
    private final Theme theme;
    /** Nanosecond clock, {@link System#nanoTime()} outside tests. */
    private final LongSupplier clock;
    private final Timer countdownTimer;

    private final ThemedLabel title = new ThemedLabel(" ", ThemedLabel.Style.TITLE);
    private final ThemedLabel subtitle = new ThemedLabel(" ", ThemedLabel.Style.SECONDARY);
    private final ThemedButton leaveButton = new ThemedButton("Leave Game", ThemedButton.Variant.SECONDARY);

    private final ThemedLabel countdownLabel = new ThemedLabel(" ", ThemedLabel.Style.HEADING);
    private final JProgressBar countdownBar = new JProgressBar();
    private final JTextArea questionText;
    private final JPanel optionsPanel = new JPanel(new GridLayout(2, 2, ThemeManager.SPACE_M, ThemeManager.SPACE_M));
    private final Map<AnswerOption, AnswerButton> optionButtons = new EnumMap<>(AnswerOption.class);
    private final StatusBanner statusBanner = new StatusBanner();
    private final ThemedButton submitButton = new ThemedButton(SUBMIT_TEXT, ThemedButton.Variant.PRIMARY);

    private boolean listening;

    /** The question shown now (compared by identity: every QUESTION line is a new object). */
    private QuestionData shownQuestion;
    /** When this screen first saw {@link #shownQuestion}, from {@link #clock}. */
    private long questionSeenAtNanos;
    /** The answer the player clicked but has not submitted yet. Local to this screen. */
    private AnswerOption chosen;
    /** True after Submit: a new server ERROR is then shown as the answer's rejection. */
    private boolean watchingAnswerError;
    /** The last error before Submit; a different one came from the server after it. */
    private String errorBeforeSubmit;
    /** Whether "time is up" was shown by the last render (the tick re-renders when it changes). */
    private boolean timeUpShown;

    public QuizScreen(GameController controller) {
        this(controller, System::nanoTime);
    }

    /** @param clock a nanosecond clock; tests pass a fake one */
    QuizScreen(GameController controller, LongSupplier clock) {
        super(new BorderLayout(0, ThemeManager.SPACE_XL));
        this.controller = Objects.requireNonNull(controller, "controller");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.theme = ThemeManager.current();
        this.questionText = ScreenParts.wrappingText(ThemeManager.headingFont(), theme.getTextPrimary());
        this.countdownTimer = new Timer(TICK_MS, e -> tickCountdown());
        countdownTimer.setRepeats(false); // every tick schedules the next one (see updateTimer)
        setBackground(theme.getBackground());
        setBorder(ScreenParts.screenPadding());

        for (AnswerOption option : AnswerOption.values()) {
            AnswerButton button = new AnswerButton(option.letter());
            button.setName("option:" + option.letter());
            button.addActionListener(e -> choose(option));
            optionButtons.put(option, button);
            optionsPanel.add(button);
        }
        optionsPanel.setOpaque(false);

        countdownBar.setStringPainted(false);
        countdownBar.setPreferredSize(new Dimension(120, 10));
        countdownBar.getAccessibleContext().setAccessibleName("Time left");

        submitButton.addActionListener(e -> submit());
        leaveButton.addActionListener(e -> controller.disconnect());
        leaveButton.setToolTipText("Leave the game and return to the connection screen");

        add(ScreenParts.header(title, subtitle, leaveButton), BorderLayout.NORTH);
        add(createQuestionCard(), BorderLayout.CENTER);
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

    /** Stops following the controller (and the countdown) when the window is disposed. */
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
        countdownTimer.stop();
    }

    boolean isListening() {
        return listening;
    }

    // ------------------------------------------------------------------
    // User actions
    // ------------------------------------------------------------------

    /** Selects an answer (nothing is sent yet). */
    private void choose(AnswerOption option) {
        GameState state = controller.getState();
        if (canChoose(state)) {
            chosen = option;
        }
        render(state);
    }

    /** Sends the selected answer through the controller, at most once per round. */
    private void submit() {
        GameState state = controller.getState();
        AnswerOption answer = chosen;
        if (answer == null || !canChoose(state)) {
            render(state);
            return;
        }
        watchingAnswerError = true;
        errorBeforeSubmit = state.getLastError();
        // submitAnswer notifies the listeners, which renders the "Answer sent" state.
        if (!controller.submitAnswer(answer)) {
            watchingAnswerError = false;
            render(state);
        }
    }

    /** True while an answer may be selected and submitted (the controller still checks again). */
    private boolean canChoose(GameState state) {
        return state.getPhase() == GamePhase.QUESTION_ACTIVE
                && state.canSubmitAnswer()
                && controller.hasActiveConnection()
                && !isTimeUp(state);
    }

    // ------------------------------------------------------------------
    // Countdown
    // ------------------------------------------------------------------

    /** Milliseconds left on the local countdown (0 when there is no question). */
    long remainingMillis() {
        if (shownQuestion == null) {
            return 0;
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - questionSeenAtNanos);
        return remainingMillis(shownQuestion.secondsLimit(), elapsed);
    }

    /** The time left of a {@code secondsLimit} countdown after {@code elapsedMillis}; never negative. */
    static long remainingMillis(int secondsLimit, long elapsedMillis) {
        return Math.max(0, secondsLimit * 1000L - Math.max(0, elapsedMillis));
    }

    /** The whole seconds to display: rounded up, so "1" is shown until the very end. */
    static int secondsToShow(long remainingMillis) {
        return (int) ((Math.max(0, remainingMillis) + 999) / 1000);
    }

    private boolean isTimeUp(GameState state) {
        return state.getPhase() == GamePhase.QUESTION_ACTIVE && shownQuestion != null && remainingMillis() == 0;
    }

    /** Called by the Swing timer (and directly by tests) to refresh the countdown. */
    void tickCountdown() {
        GameState state = controller.getState();
        if (shownQuestion == null || state.getPhase() != GamePhase.QUESTION_ACTIVE) {
            countdownTimer.stop();
            return;
        }
        if (isTimeUp(state) != timeUpShown) {
            render(state); // time just ran out: disable the answers and explain why
        } else {
            long remaining = remainingMillis();
            showCountdown(remaining, true);
            updateTimer(remaining > 0);
        }
    }

    /** Milliseconds until the next countdown tick: one tick, or less so the last tick lands on zero. */
    static int nextTickMillis(long remainingMillis) {
        return (int) Math.max(1, Math.min(TICK_MS, remainingMillis));
    }

    boolean isCountdownRunning() {
        return countdownTimer.isRunning();
    }

    /** The delay of the countdown timer's next tick, in milliseconds. */
    int countdownDelayMillis() {
        return countdownTimer.getInitialDelay();
    }

    /** True if every tick schedules the next one itself (the timer does not repeat). */
    boolean isCountdownOneShot() {
        return !countdownTimer.isRepeats();
    }

    private void showCountdown(long remaining, boolean running) {
        int limitMillis = shownQuestion == null ? 1 : shownQuestion.secondsLimit() * 1000;
        int seconds = secondsToShow(remaining);
        boolean low = running && seconds <= LOW_TIME_SECONDS;
        countdownLabel.setText(running ? seconds + " s" : "–");
        countdownLabel.setForeground(low ? theme.getWarning() : theme.getTextPrimary());
        countdownBar.setMaximum(limitMillis);
        countdownBar.setValue(running ? (int) Math.min(remaining, limitMillis) : 0);
        countdownBar.setForeground(low ? theme.getWarning() : theme.getAccent());
        countdownBar.getAccessibleContext().setAccessibleDescription(running ? seconds + " seconds left" : "");
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void render(GameState state) {
        GamePhase phase = state.getPhase();
        QuestionData question = state.getCurrentQuestion();
        if (question != shownQuestion) {
            showNewQuestion(question);
        }

        String self = state.getCurrentPlayer();
        String playingAs = self != null ? "Playing as " + self : " ";

        if (question == null) {
            // GAME_STARTED arrived; the first QUESTION has not.
            title.setText("Get Ready");
            subtitle.setText(playingAs);
            for (AnswerButton button : optionButtons.values()) {
                button.setEnabled(false);
                button.setLook(AnswerButton.Look.MUTED);
            }
            optionsPanel.setVisible(false);
            submitButton.setVisible(false);
            showCountdown(0, false);
            statusBanner.showStatus(StatusBanner.Kind.PROGRESS, "The game has started",
                    "Waiting for the first question from the server.");
            timeUpShown = false;
            updateTimer(false);
            revalidate();
            repaint();
            return;
        }

        boolean questionOpen = phase == GamePhase.QUESTION_ACTIVE;
        boolean timeUp = isTimeUp(state);
        boolean canChoose = canChoose(state);
        AnswerState answerState = state.getAnswerState();
        AnswerOption sent = state.getSelectedAnswer();
        // Only known once the round is closed. Normally the results screen shows it; this
        // screen only does if no results screen is registered.
        AnswerOption correct = questionOpen ? null : state.getCorrectAnswer();

        title.setText("Question " + question.round() + " of " + question.totalRounds());
        subtitle.setText(playingAs);
        optionsPanel.setVisible(true);
        for (AnswerOption option : AnswerOption.values()) {
            AnswerButton button = optionButtons.get(option);
            button.setEnabled(canChoose);
            button.setLook(lookFor(option, sent, correct, canChoose));
        }

        submitButton.setVisible(true);
        submitButton.setEnabled(canChoose && chosen != null);
        submitButton.setText(switch (answerState) {
            case SUBMITTED -> SENDING_TEXT;
            case ACCEPTED -> LOCKED_TEXT;
            case NOT_ANSWERED, MISSED -> SUBMIT_TEXT;
        });

        long remaining = remainingMillis();
        showCountdown(remaining, questionOpen);
        updateStatus(state, answerState, sent, correct, timeUp);

        timeUpShown = timeUp;
        updateTimer(questionOpen && remaining > 0);
        revalidate();
        repaint();
    }

    /** A new round: reset the local choice and restart the countdown. */
    private void showNewQuestion(QuestionData question) {
        shownQuestion = question;
        chosen = null;
        watchingAnswerError = false;
        errorBeforeSubmit = null;
        questionSeenAtNanos = clock.getAsLong();
        questionText.setText(question == null ? "The first question is on its way…" : question.text());
        for (AnswerOption option : AnswerOption.values()) {
            optionButtons.get(option).setText(question == null ? " " : question.option(option));
        }
    }

    private AnswerButton.Look lookFor(AnswerOption option, AnswerOption sent, AnswerOption correct, boolean canChoose) {
        if (correct != null) {
            if (option == correct) {
                return AnswerButton.Look.CORRECT;
            }
            return option == sent ? AnswerButton.Look.WRONG : AnswerButton.Look.MUTED;
        }
        if (sent != null) {
            return option == sent ? AnswerButton.Look.LOCKED : AnswerButton.Look.MUTED;
        }
        if (option == chosen) {
            return AnswerButton.Look.SELECTED;
        }
        return canChoose ? AnswerButton.Look.NORMAL : AnswerButton.Look.MUTED;
    }

    private void updateStatus(GameState state, AnswerState answerState, AnswerOption sent,
                              AnswerOption correct, boolean timeUp) {
        String error = state.getLastError();
        boolean answerRejected = watchingAnswerError && answerState == AnswerState.SUBMITTED
                && error != null && error != errorBeforeSubmit; // a new ERROR line is a new String

        if (correct != null) {
            statusBanner.showStatus(StatusBanner.Kind.INFO, "Round over",
                    "The correct answer was " + correct.letter() + ".");
        } else if (answerRejected) {
            statusBanner.showStatus(StatusBanner.Kind.ERROR, "Your answer was not accepted", error);
        } else if (answerState == AnswerState.SUBMITTED) {
            statusBanner.showStatus(StatusBanner.Kind.PROGRESS, "Answer sent",
                    "Waiting for the server to confirm answer " + sent.letter() + ".");
        } else if (answerState == AnswerState.ACCEPTED) {
            statusBanner.showStatus(StatusBanner.Kind.SUCCESS, "Answer locked in",
                    "You answered " + sent.letter() + ". Waiting for the round result.");
        } else if (!controller.hasActiveConnection()) {
            statusBanner.showStatus(StatusBanner.Kind.WARNING, "Not connected",
                    "Answers cannot be sent without a connection.");
        } else if (timeUp) {
            statusBanner.showStatus(StatusBanner.Kind.WARNING, "Time's up",
                    "Waiting for the server to close the round.");
        } else if (chosen != null) {
            statusBanner.showStatus(StatusBanner.Kind.INFO, "Ready to submit",
                    "You chose " + chosen.letter() + ". Press Submit Answer to lock it in.");
        } else {
            statusBanner.showStatus(StatusBanner.Kind.INFO, "Choose an answer",
                    "Select one answer, then press Submit Answer. You can change your choice until you submit.");
        }
    }

    private void updateTimer(boolean shouldRun) {
        if (shouldRun && listening) {
            // One tick at a time: each tick is due after one tick interval, or exactly when the
            // countdown reaches zero if that is sooner, so "Time's up" is shown at once instead
            // of up to one tick later. (A repeating timer would schedule its next tick before
            // this delay could change it.)
            countdownTimer.setInitialDelay(nextTickMillis(remainingMillis()));
            countdownTimer.restart();
        } else {
            countdownTimer.stop();
        }
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private RoundedPanel createQuestionCard() {
        RoundedPanel card = new RoundedPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;

        // Countdown: caption and seconds on one line, the bar below.
        JPanel timerRow = new JPanel(new BorderLayout(ThemeManager.SPACE_M, 0));
        timerRow.setOpaque(false);
        timerRow.add(new ThemedLabel("Time left", ThemedLabel.Style.CAPTION), BorderLayout.WEST);
        timerRow.add(countdownLabel, BorderLayout.EAST);
        card.add(timerRow, c);

        c.gridy++;
        c.insets = new Insets(ThemeManager.SPACE_XS, 0, 0, 0);
        card.add(countdownBar, c);

        c.gridy++;
        c.insets = new Insets(ThemeManager.SPACE_XL, 0, 0, 0);
        card.add(questionText, c);

        c.gridy++;
        c.insets = new Insets(ThemeManager.SPACE_XL, 0, 0, 0);
        card.add(optionsPanel, c);

        // Status on the left, Submit on the right.
        JPanel submitHolder = new JPanel(new GridBagLayout()); // centres the button vertically
        submitHolder.setOpaque(false);
        submitHolder.add(submitButton);
        JPanel actionRow = new JPanel(new BorderLayout(ThemeManager.SPACE_L, 0));
        actionRow.setOpaque(false);
        actionRow.add(statusBanner, BorderLayout.CENTER);
        actionRow.add(submitHolder, BorderLayout.EAST);

        c.gridy++;
        c.insets = new Insets(ThemeManager.SPACE_XL, 0, 0, 0);
        card.add(actionRow, c);

        // Push everything to the top of the card.
        c.gridy++;
        c.weighty = 1;
        c.insets = new Insets(0, 0, 0, 0);
        card.add(Box.createVerticalGlue(), c);
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

    AnswerButton optionButton(AnswerOption option) {
        return optionButtons.get(option);
    }

    ThemedButton submitButton() {
        return submitButton;
    }

    ThemedButton leaveButton() {
        return leaveButton;
    }

    StatusBanner statusBanner() {
        return statusBanner;
    }

    String countdownText() {
        return countdownLabel.getText();
    }

    JProgressBar countdownBar() {
        return countdownBar;
    }

    /** The selected but not yet submitted answer, or null. */
    AnswerOption chosenOption() {
        return chosen;
    }
}
