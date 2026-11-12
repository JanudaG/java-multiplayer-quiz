package quiz.gui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GameState}. Only model types are used here;
 * the protocol layer is tested separately.
 */
class GameStateTest {

    private GameState state;

    @BeforeEach
    void setUp() {
        state = new GameState();
    }

    // ---------------- helpers ----------------

    private static QuestionData question(int round) {
        return new QuestionData(round, 5, 15, "Question " + round, "a", "b", "c", "d");
    }

    /** Connects and joins as {@code me}, then applies PLAYERS and HOST. */
    private void joinLobby(String me, List<String> players, String host) {
        state.markConnecting();
        state.markJoining(me);
        state.onJoined(me);
        state.onPlayers(players);
        state.onHost(host);
    }

    /** Joins as host "alice" with "bob", starts a game and opens round 1. */
    private void startGameAtRoundOne() {
        joinLobby("alice", List.of("alice", "bob"), "alice");
        state.onGameStarted();
        state.onQuestion(question(1));
    }

    // ------------------------------------------------------------------
    @Nested
    class ConnectionAndJoin {
        @Test
        void initialState() {
            assertEquals(GamePhase.DISCONNECTED, state.getPhase());
            assertFalse(state.isConnected());
            assertNull(state.getCurrentPlayer());
            assertEquals(List.of(), state.getPlayers());
            assertTrue(state.getLeaderboard().isEmpty());
            assertEquals(AnswerState.NOT_ANSWERED, state.getAnswerState());
            assertFalse(state.canStartGame());
            assertFalse(state.canSubmitAnswer());
        }

        @Test
        void connectingThenJoining() {
            state.markConnecting();
            assertEquals(GamePhase.CONNECTING, state.getPhase());
            assertFalse(state.isConnected());

            state.markJoining("alice");
            assertEquals(GamePhase.JOINING, state.getPhase());
            assertTrue(state.isConnected());
            assertEquals("alice", state.getRequestedUsername());
        }

        @Test
        void joinedMovesToLobby() {
            joinLobby("alice", List.of("alice"), "alice");
            assertEquals(GamePhase.LOBBY, state.getPhase());
            assertEquals("alice", state.getCurrentPlayer());
            assertEquals(List.of("alice"), state.getPlayers());
        }

        @Test
        void infoIsStored() {
            joinLobby("alice", List.of("alice"), "alice");
            state.onInfo("bob joined the lobby");
            assertEquals("bob joined the lobby", state.getLastInfo());
        }

        @Test
        void joinErrorMarksJoinRejectedAndSurvivesDisconnect() {
            state.markConnecting();
            state.markJoining("alice");
            state.onError("Username already taken");
            assertTrue(state.isJoinRejected());
            assertEquals("Username already taken", state.getLastError());

            state.markDisconnected("Connection closed");
            assertEquals(GamePhase.DISCONNECTED, state.getPhase());
            assertTrue(state.isJoinRejected());
            assertEquals("Username already taken", state.getLastError());
        }

        @Test
        void disconnectReasonUsedWhenNoErrorYet() {
            joinLobby("alice", List.of("alice"), "alice");
            state.markDisconnected("Server closed the connection");
            assertEquals("Server closed the connection", state.getLastError());
            assertNull(state.getCurrentPlayer());
            assertEquals(List.of(), state.getPlayers());
            assertNull(state.getHost());
        }

        @Test
        void errorInLobbyIsNotAJoinRejection() {
            joinLobby("alice", List.of("alice"), "alice");
            state.onError("Unknown command");
            assertFalse(state.isJoinRejected());
            assertEquals("Unknown command", state.getLastError());
        }

        @Test
        void newConnectionClearsOldErrorAndRejection() {
            state.markConnecting();
            state.markJoining("alice");
            state.onError("Username already taken");
            state.markDisconnected(null);

            state.markConnecting();
            assertNull(state.getLastError());
            assertFalse(state.isJoinRejected());
        }

        @Test
        void messagesAfterDisconnectAreIgnored() {
            joinLobby("alice", List.of("alice", "bob"), "alice");
            state.markDisconnected("bye");

            state.onPlayers(List.of("x", "y"));
            state.onHost("x");
            state.onGameStarted();
            state.onQuestion(question(1));
            state.onScores(Leaderboard.of(List.of(new PlayerScore("x", 10))));
            state.onGameOver(List.of("x"), 10);

            assertEquals(GamePhase.DISCONNECTED, state.getPhase());
            assertEquals(List.of(), state.getPlayers());
            assertNull(state.getHost());
            assertNull(state.getCurrentQuestion());
            assertTrue(state.getLeaderboard().isEmpty());
            assertEquals(List.of(), state.getWinners());
        }

        @Test
        void playersListCannotBeModified() {
            joinLobby("alice", List.of("alice", "bob"), "alice");
            assertThrows(UnsupportedOperationException.class, () -> state.getPlayers().add("eve"));
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class HostIdentification {
        @Test
        void hostMessageNamingMeMakesMeHost() {
            joinLobby("alice", List.of("alice", "bob"), "alice");
            assertTrue(state.isHost());
            assertEquals("alice", state.getHost());
        }

        @Test
        void hostMessageNamingSomeoneElse() {
            joinLobby("bob", List.of("alice", "bob"), "alice");
            assertFalse(state.isHost());
        }

        @Test
        void hostChangesFromOtherPlayerToMe() {
            joinLobby("bob", List.of("alice", "bob"), "alice");
            state.onPlayers(List.of("bob"));
            state.onHost("bob");
            assertTrue(state.isHost());
        }

        @Test
        void hostChangesFromMeToOtherPlayer() {
            joinLobby("alice", List.of("alice", "bob"), "alice");
            state.onHost("bob");
            assertFalse(state.isHost());
            assertEquals("bob", state.getHost());
        }

        @Test
        void hostChangeMidGameIsApplied() {
            startGameAtRoundOne();
            state.onHost("bob");
            assertEquals("bob", state.getHost());
            assertFalse(state.isHost());
            assertEquals(GamePhase.QUESTION_ACTIVE, state.getPhase());
        }

        @Test
        void noHostBeforeJoining() {
            assertFalse(state.isHost());
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class StartRule {
        @Test
        void hostAloneCannotStart() {
            joinLobby("alice", List.of("alice"), "alice");
            assertFalse(state.canStartGame());
            assertEquals(Optional.of("At least two players are required"), state.startBlockedReason());
        }

        @Test
        void hostWithTwoPlayersCanStart() {
            joinLobby("alice", List.of("alice", "bob"), "alice");
            assertTrue(state.canStartGame());
            assertEquals(Optional.empty(), state.startBlockedReason());
        }

        @Test
        void nonHostCannotStartEvenWithThreePlayers() {
            joinLobby("bob", List.of("alice", "bob", "carol"), "alice");
            assertFalse(state.canStartGame());
            assertEquals(Optional.of("Only the host can start"), state.startBlockedReason());
        }

        @Test
        void cannotStartWhileGameRunning() {
            startGameAtRoundOne();
            assertFalse(state.canStartGame());
            assertEquals(Optional.of("Game already running"), state.startBlockedReason());
        }

        @Test
        void cannotStartWhenNotJoined() {
            assertFalse(state.canStartGame());
            state.markConnecting();
            assertFalse(state.canStartGame());
            state.markJoining("alice");
            assertFalse(state.canStartGame());
        }

        @Test
        void startRuleUpdatesWhenAPlayerLeaves() {
            joinLobby("alice", List.of("alice", "bob"), "alice");
            assertTrue(state.canStartGame());
            state.onPlayers(List.of("alice"));
            assertFalse(state.canStartGame());
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class Answers {
        @Test
        void questionOpensRound() {
            startGameAtRoundOne();
            assertEquals(GamePhase.QUESTION_ACTIVE, state.getPhase());
            assertEquals(1, state.getCurrentQuestion().round());
            assertTrue(state.canSubmitAnswer());
        }

        @Test
        void onlyOneAnswerPerRound() {
            startGameAtRoundOne();
            assertTrue(state.markAnswerSubmitted(AnswerOption.B));
            assertFalse(state.markAnswerSubmitted(AnswerOption.C));
            assertEquals(AnswerOption.B, state.getSelectedAnswer());
            assertEquals(AnswerState.SUBMITTED, state.getAnswerState());
            assertFalse(state.canSubmitAnswer());
        }

        @Test
        void errorAfterSubmissionDoesNotUnlockAnotherAnswer() {
            startGameAtRoundOne();
            state.markAnswerSubmitted(AnswerOption.A);
            state.onError("No active question or time has expired");

            assertEquals(AnswerState.SUBMITTED, state.getAnswerState());
            assertFalse(state.canSubmitAnswer());
            assertFalse(state.markAnswerSubmitted(AnswerOption.B));
            assertEquals(AnswerOption.A, state.getSelectedAnswer());
        }

        @Test
        void nullAnswerIsRejected() {
            startGameAtRoundOne();
            assertFalse(state.markAnswerSubmitted(null));
            assertEquals(AnswerState.NOT_ANSWERED, state.getAnswerState());
        }

        @Test
        void cannotAnswerOutsideAnActiveQuestion() {
            joinLobby("alice", List.of("alice", "bob"), "alice");
            assertFalse(state.markAnswerSubmitted(AnswerOption.A));

            state.onGameStarted();
            assertFalse(state.markAnswerSubmitted(AnswerOption.A));

            state.onQuestion(question(1));
            state.onRoundResult(1, AnswerOption.B);
            assertFalse(state.markAnswerSubmitted(AnswerOption.A));
        }

        @Test
        void answerAcceptedForCurrentRound() {
            startGameAtRoundOne();
            state.markAnswerSubmitted(AnswerOption.B);
            state.onAnswerAccepted(1);
            assertEquals(AnswerState.ACCEPTED, state.getAnswerState());
            assertFalse(state.canSubmitAnswer());
        }

        @Test
        void answerAcceptedForWrongRoundIsIgnored() {
            startGameAtRoundOne();
            state.markAnswerSubmitted(AnswerOption.B);
            state.onAnswerAccepted(2);
            assertEquals(AnswerState.SUBMITTED, state.getAnswerState());
        }

        @Test
        void answerAcceptedWithoutSubmissionIsIgnored() {
            startGameAtRoundOne();
            state.onAnswerAccepted(1);
            assertEquals(AnswerState.NOT_ANSWERED, state.getAnswerState());
        }

        @Test
        void newQuestionResetsAnswerState() {
            startGameAtRoundOne();
            state.markAnswerSubmitted(AnswerOption.B);
            state.onAnswerAccepted(1);
            state.onRoundResult(1, AnswerOption.B);

            state.onQuestion(question(2));
            assertEquals(AnswerState.NOT_ANSWERED, state.getAnswerState());
            assertNull(state.getSelectedAnswer());
            assertNull(state.getCorrectAnswer());
            assertTrue(state.canSubmitAnswer());
            assertEquals(GamePhase.QUESTION_ACTIVE, state.getPhase());
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class RoundResults {
        @Test
        void unansweredRoundBecomesMissed() {
            startGameAtRoundOne();
            state.onRoundResult(1, AnswerOption.C);
            assertEquals(AnswerState.MISSED, state.getAnswerState());
            assertEquals(AnswerOption.C, state.getCorrectAnswer());
            assertEquals(GamePhase.ROUND_ENDED, state.getPhase());
        }

        @Test
        void acceptedAnswerStaysAccepted() {
            startGameAtRoundOne();
            state.markAnswerSubmitted(AnswerOption.C);
            state.onAnswerAccepted(1);
            state.onRoundResult(1, AnswerOption.C);
            assertEquals(AnswerState.ACCEPTED, state.getAnswerState());
        }

        @Test
        void submittedButNotAcceptedBecomesMissed() {
            startGameAtRoundOne();
            state.markAnswerSubmitted(AnswerOption.C);
            state.onError("No active question or time has expired");
            state.onRoundResult(1, AnswerOption.C);
            assertEquals(AnswerState.MISSED, state.getAnswerState());
        }

        @Test
        void roundResultForWrongRoundIsIgnored() {
            startGameAtRoundOne();
            state.onRoundResult(2, AnswerOption.A);
            assertEquals(GamePhase.QUESTION_ACTIVE, state.getPhase());
            assertNull(state.getCorrectAnswer());
            assertEquals(AnswerState.NOT_ANSWERED, state.getAnswerState());
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class ScoresAndGameOver {
        @Test
        void scoresReplaceTheLeaderboard() {
            startGameAtRoundOne();
            state.onScores(Leaderboard.of(List.of(new PlayerScore("alice", 10), new PlayerScore("bob", 0))));
            state.onScores(Leaderboard.of(List.of(new PlayerScore("bob", 20), new PlayerScore("alice", 10))));

            assertEquals(List.of(new PlayerScore("bob", 20), new PlayerScore("alice", 10)),
                    state.getLeaderboard().entries());
            assertEquals(2, state.getLeaderboard().size());
        }

        @Test
        void scoresWithFewerPlayersReplaceNotAppend() {
            startGameAtRoundOne();
            state.onScores(Leaderboard.of(List.of(new PlayerScore("alice", 10), new PlayerScore("bob", 0))));
            state.onScores(Leaderboard.of(List.of(new PlayerScore("alice", 10))));
            assertEquals(1, state.getLeaderboard().size());
            assertTrue(state.getLeaderboard().scoreOf("bob").isEmpty());
        }

        @Test
        void emptyScoresEmptyTheLeaderboard() {
            startGameAtRoundOne();
            state.onScores(Leaderboard.of(List.of(new PlayerScore("alice", 10))));
            state.onScores(Leaderboard.empty());
            assertTrue(state.getLeaderboard().isEmpty());
        }

        @Test
        void gameOverWithoutRoundResult() {
            startGameAtRoundOne();
            state.onGameOver(List.of("alice"), 0);

            assertEquals(GamePhase.GAME_OVER, state.getPhase());
            assertTrue(state.isGameOver());
            assertFalse(state.canSubmitAnswer());
            assertFalse(state.markAnswerSubmitted(AnswerOption.A));
            assertEquals(AnswerState.MISSED, state.getAnswerState());
            assertEquals(List.of("alice"), state.getWinners());
            assertEquals(0, state.getBestScore());
            assertNull(state.getCorrectAnswer());
        }

        @Test
        void gameOverKeepsAcceptedAnswer() {
            startGameAtRoundOne();
            state.markAnswerSubmitted(AnswerOption.A);
            state.onAnswerAccepted(1);
            state.onGameOver(List.of("alice"), 10);
            assertEquals(AnswerState.ACCEPTED, state.getAnswerState());
        }

        @Test
        void singleWinnerIsMe() {
            startGameAtRoundOne();
            state.onGameOver(List.of("alice"), 50);
            assertTrue(state.isWinner());
            assertFalse(state.isTie());
            assertEquals(50, state.getBestScore());
        }

        @Test
        void singleWinnerIsSomeoneElse() {
            startGameAtRoundOne();
            state.onGameOver(List.of("bob"), 50);
            assertFalse(state.isWinner());
            assertFalse(state.isTie());
        }

        @Test
        void tiedWinners() {
            startGameAtRoundOne();
            state.onGameOver(List.of("alice", "bob"), 20);
            assertTrue(state.isTie());
            assertTrue(state.isWinner());
            assertEquals(List.of("alice", "bob"), state.getWinners());
        }

        @Test
        void tiedWinnersWithoutMe() {
            joinLobby("carol", List.of("alice", "bob", "carol"), "alice");
            state.onGameStarted();
            state.onQuestion(question(1));
            state.onGameOver(List.of("alice", "bob"), 20);
            assertTrue(state.isTie());
            assertFalse(state.isWinner());
        }

        @Test
        void winnersListCannotBeModified() {
            startGameAtRoundOne();
            state.onGameOver(List.of("alice"), 10);
            assertThrows(UnsupportedOperationException.class, () -> state.getWinners().add("bob"));
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class NewGame {
        @Test
        void gameStartedClearsPreviousGame() {
            startGameAtRoundOne();
            state.markAnswerSubmitted(AnswerOption.A);
            state.onAnswerAccepted(1);
            state.onRoundResult(1, AnswerOption.A);
            state.onScores(Leaderboard.of(List.of(new PlayerScore("alice", 10), new PlayerScore("bob", 0))));
            state.onGameOver(List.of("alice"), 10);
            state.onError("some old error");

            state.onGameStarted();

            assertEquals(GamePhase.GAME_STARTING, state.getPhase());
            assertNull(state.getCurrentQuestion());
            assertNull(state.getSelectedAnswer());
            assertNull(state.getCorrectAnswer());
            assertEquals(AnswerState.NOT_ANSWERED, state.getAnswerState());
            assertTrue(state.getLeaderboard().isEmpty());
            assertEquals(List.of(), state.getWinners());
            assertEquals(0, state.getBestScore());
            assertFalse(state.isGameOver());
            assertNull(state.getLastError());
            // Lobby data is kept.
            assertEquals("alice", state.getCurrentPlayer());
            assertEquals(List.of("alice", "bob"), state.getPlayers());
            assertTrue(state.isHost());
        }

        @Test
        void hostCanPlayAgainAfterGameOver() {
            startGameAtRoundOne();
            state.onGameOver(List.of("alice"), 10);
            assertTrue(state.canStartGame());
        }

        @Test
        void nonHostCannotPlayAgain() {
            joinLobby("bob", List.of("alice", "bob"), "alice");
            state.onGameStarted();
            state.onQuestion(question(1));
            state.onGameOver(List.of("alice"), 10);
            assertFalse(state.canStartGame());
            assertEquals(Optional.of("Only the host can start"), state.startBlockedReason());
        }

        @Test
        void playAgainNeedsTwoPlayers() {
            startGameAtRoundOne();
            state.onPlayers(List.of("alice"));
            state.onGameOver(List.of("alice"), 0);
            assertFalse(state.canStartGame());
            assertEquals(Optional.of("At least two players are required"), state.startBlockedReason());
        }
    }
}
