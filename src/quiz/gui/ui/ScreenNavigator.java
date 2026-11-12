package quiz.gui.ui;

import java.awt.EventQueue;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import quiz.gui.controller.GameController;
import quiz.gui.model.GamePhase;

/**
 * Decides which screen is visible, based only on the controller's {@link GamePhase}.
 *
 * <p>Screens never switch screens themselves: they send user actions to the
 * {@link GameController}, the server answers, the phase changes, and this class shows the
 * matching screen. So, for example, the lobby only appears after the server confirmed the
 * JOIN, and a lost connection always leads back to the connection screen.</p>
 *
 * <table>
 *   <caption>Phase to screen</caption>
 *   <tr><th>Phase</th><th>Screen</th></tr>
 *   <tr><td>DISCONNECTED, CONNECTING, JOINING</td><td>{@link #CONNECTION}</td></tr>
 *   <tr><td>LOBBY</td><td>{@link #LOBBY}</td></tr>
 *   <tr><td>GAME_STARTING, QUESTION_ACTIVE</td><td>{@link #QUIZ}</td></tr>
 *   <tr><td>ROUND_ENDED</td><td>{@link #RESULTS}</td></tr>
 *   <tr><td>GAME_OVER</td><td>{@link #GAME_OVER}</td></tr>
 * </table>
 * Fallbacks when a screen is not registered: ROUND_ENDED uses the quiz screen; GAME_OVER
 * uses the results screen (which then keeps the final round and offers Play Again, as in
 * Phase 4C). If no game screen is registered at all, the game phases fall back to the
 * lobby, which shows the game status and Play Again.
 *
 * <p>Use on the event dispatch thread.</p>
 */
public final class ScreenNavigator {

    /** The connection screen ({@link ConnectionScreen}). */
    public static final String CONNECTION = "connection";
    /** The lobby screen ({@link LobbyScreen}). */
    public static final String LOBBY = "lobby";
    /** The quiz screen ({@link QuizScreen}): the open question. */
    public static final String QUIZ = "quiz";
    /** The results screen ({@link RoundResultsScreen}): the result of the last round and the scores. */
    public static final String RESULTS = "results";
    /** The game over screen ({@link GameOverScreen}): the winners and the final leaderboard. */
    public static final String GAME_OVER = "game-over";

    private final GameController controller;
    private final Predicate<String> hasScreen;
    private final Consumer<String> showScreen;
    private final GameController.StateListener listener = state -> navigate();

    private boolean listening;
    /** The screen this navigator showed last, or null before the first one. */
    private String shownScreen;

    /**
     * @param hasScreen  tells whether a screen with that name is registered
     * @param showScreen makes the named screen visible
     */
    ScreenNavigator(GameController controller, Predicate<String> hasScreen, Consumer<String> showScreen) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.hasScreen = Objects.requireNonNull(hasScreen, "hasScreen");
        this.showScreen = Objects.requireNonNull(showScreen, "showScreen");
    }

    /**
     * Shows the screen for the current phase (the connection screen at startup) and keeps
     * following the controller until the frame is closed. Register the screens first.
     */
    public static ScreenNavigator install(MainFrame frame) {
        ScreenNavigator navigator = new ScreenNavigator(frame.getController(), frame::hasScreen, frame::showScreen);
        navigator.start();
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                navigator.stop();
            }
        });
        return navigator;
    }

    /** Starts following the controller and shows the screen for the current phase. */
    void start() {
        requireEdt();
        if (listening) {
            return;
        }
        listening = true;
        controller.addStateListener(listener);
        navigate();
    }

    /** Stops following the controller. The visible screen is left as it is. */
    void stop() {
        requireEdt();
        if (!listening) {
            return;
        }
        listening = false;
        controller.removeStateListener(listener);
    }

    /** The screen this navigator showed last, or empty before the first one. */
    Optional<String> getShownScreen() {
        return Optional.ofNullable(shownScreen);
    }

    private void navigate() {
        Optional<String> target = screenFor(controller.getState().getPhase(), hasScreen);
        if (target.isPresent() && !target.get().equals(shownScreen)) {
            shownScreen = target.get();
            showScreen.accept(shownScreen);
        }
    }

    /**
     * The screen to show for a phase, taking into account which screens are registered.
     *
     * @return the screen name, or empty if no suitable screen is registered
     */
    static Optional<String> screenFor(GamePhase phase, Predicate<String> hasScreen) {
        String preferred = switch (phase) {
            case DISCONNECTED, CONNECTING, JOINING -> CONNECTION;
            case LOBBY -> LOBBY;
            case GAME_STARTING, QUESTION_ACTIVE -> QUIZ;
            case ROUND_ENDED -> RESULTS;
            case GAME_OVER -> ScreenNavigator.GAME_OVER; // the screen name, not the phase
        };
        if (hasScreen.test(preferred)) {
            return Optional.of(preferred);
        }
        // The quiz screen can show a finished round itself (correct answer highlighted).
        if (phase == GamePhase.ROUND_ENDED && hasScreen.test(QUIZ)) {
            return Optional.of(QUIZ);
        }
        // Without a game over screen, the results screen keeps the final round and offers Play Again.
        if (phase == GamePhase.GAME_OVER && hasScreen.test(RESULTS)) {
            return Optional.of(RESULTS);
        }
        boolean inGame = preferred.equals(QUIZ) || preferred.equals(RESULTS)
                || preferred.equals(ScreenNavigator.GAME_OVER);
        if (inGame && hasScreen.test(LOBBY)) {
            return Optional.of(LOBBY);
        }
        return Optional.empty();
    }

    private static void requireEdt() {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ScreenNavigator must be used on the event dispatch thread");
        }
    }
}
