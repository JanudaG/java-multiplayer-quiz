
package quiz;

import java.util.Objects;

/**
 * Minimal test helper so tests run with plain javac/java (no JUnit needed).
 * Each check prints PASS or FAIL; main methods call finish() to set the exit code.
 */
final class SelfTest {

    private final String suiteName;
    private int passed;
    private int failed;

    SelfTest(String suiteName) {
        this.suiteName = suiteName;
        System.out.println("== " + suiteName + " ==");
    }

    void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name);
        }
    }

    void checkEquals(String name, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name
                    + "\n        expected: " + expected
                    + "\n        actual:   " + actual);
        }
    }

    /** Passes if the action throws an exception of the given type. */
    void checkThrows(String name, Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
            failed++;
            System.out.println("FAIL  " + name + " (no exception thrown)");
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                passed++;
                System.out.println("PASS  " + name);
            } else {
                failed++;
                System.out.println("FAIL  " + name + " (threw " + t + ")");
            }
        }
    }

    int failed() {
        return failed;
    }

    /** Prints a summary and returns true if every check passed. */
    boolean summary() {
        System.out.println(suiteName + ": " + passed + " passed, "
                + failed + " failed\n");
        return failed == 0;
    }

    /** Prints a summary and exits with status 1 if any check failed. */
    void finish() {
        if (!summary()) {
            System.exit(1);
        }
    }
}
