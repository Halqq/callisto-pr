package dev.callisto.validation;

/** Result of the test suite execution via subprocess. */
public class TestResult {
    private final int exitCode;
    private final String output;

    public TestResult(int exitCode, String output) {
        this.exitCode = exitCode;
        this.output = output;
    }

    /** 0 = tests passed; != 0 = failed; -1 = timeout */
    public int getExitCode() { return exitCode; }

    /** stdout+stderr combined, truncated to 4KB */
    public String getOutput() { return output; }
}
