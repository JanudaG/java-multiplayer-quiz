package quiz.gui.protocol;

import java.util.List;
import java.util.Objects;

import quiz.gui.model.AnswerOption;
import quiz.gui.model.Leaderboard;
import quiz.gui.model.QuestionData;

/**
 * A typed message received from the server. There is one record per
 * server message type, plus {@link Unknown} for unrecognised lines.
 *
 * <p>The interface is sealed, so a {@code switch} over a ServerMessage
 * must handle every type or it will not compile.</p>
 */
public sealed interface ServerMessage {

    /** JOINED|username */
    record Joined(String username) implements ServerMessage {
        public Joined {
            Objects.requireNonNull(username, "username");
        }
    }

    /** INFO|message (the message may be empty or contain '|') */
    record Info(String text) implements ServerMessage {
        public Info {
            Objects.requireNonNull(text, "text");
        }
    }

    /** PLAYERS|name1,name2,... */
    record PlayerList(List<String> usernames) implements ServerMessage {
        public PlayerList {
            usernames = List.copyOf(usernames);
        }
    }

    /** HOST|username */
    record HostChanged(String username) implements ServerMessage {
        public HostChanged {
            Objects.requireNonNull(username, "username");
        }
    }

    /** ERROR|message (the message may be empty or contain '|') */
    record ServerError(String text) implements ServerMessage {
        public ServerError {
            Objects.requireNonNull(text, "text");
        }
    }

    /** GAME_STARTED|description (for example "5 questions"; kept as-is) */
    record GameStarted(String description) implements ServerMessage {
        public GameStarted {
            Objects.requireNonNull(description, "description");
        }
    }

    /** QUESTION|round|total|seconds|questionText|optionA|optionB|optionC|optionD */
    record NewQuestion(QuestionData question) implements ServerMessage {
        public NewQuestion {
            Objects.requireNonNull(question, "question");
        }
    }

    /** ANSWER_ACCEPTED|round */
    record AnswerAccepted(int round) implements ServerMessage {
    }

    /** ROUND_RESULT|round|correctLetter */
    record RoundResult(int round, AnswerOption correct) implements ServerMessage {
        public RoundResult {
            Objects.requireNonNull(correct, "correct");
        }
    }

    /** SCORES|username:score,username:score,... */
    record Scores(Leaderboard leaderboard) implements ServerMessage {
        public Scores {
            Objects.requireNonNull(leaderboard, "leaderboard");
        }
    }

    /** GAME_OVER|winnerNames|bestScore (several winners means a tie) */
    record GameOver(List<String> winners, int bestScore) implements ServerMessage {
        public GameOver {
            winners = List.copyOf(winners);
        }
    }

    /** Any line whose type is not part of the protocol. It is safe to ignore. */
    record Unknown(String rawLine) implements ServerMessage {
        public Unknown {
            Objects.requireNonNull(rawLine, "rawLine");
        }
    }
}
