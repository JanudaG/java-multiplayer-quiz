
package quiz;

public record Question(
        String text,
        String optionA,
        String optionB,
        String optionC,
        String optionD,
        String correctAnswer) {

    public String toMessage(int round, int total, int seconds) {
        return "QUESTION|" + round + "|" + total + "|" + seconds + "|"
                + text + "|" + optionA + "|" + optionB + "|"
                + optionC + "|" + optionD;
    }
}

