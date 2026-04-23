package dev.callisto.validation;

import dev.callisto.config.TestConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Detects and executes the target project's test suite as a subprocess.
 *
 * D-01: Auto-detect by pom.xml / build.gradle / build.gradle.kts.
 * D-01: Override via TestConfig.command.
 * T-3-01: ProcessBuilder(List<String>) — never shell string concatenation.
 * Pitfall 2 (RESEARCH.md): 10-min timeout via Process.waitFor(timeout, TimeUnit).
 * Pitfall 3 (RESEARCH.md): IOException on start → throws IOException with clear message.
 */
public class TestRunner {

    private static final int TIMEOUT_MINUTES = 10;
    private static final int MAX_OUTPUT_BYTES = 4096;

    /**
     * Detects the project's test command.
     *
     * @param projectDir root directory of the target project
     * @param config     test configuration (may have override via command)
     * @return command as string (e.g. "mvn test"), or null if not detected
     */
    public String detectTestCommand(Path projectDir, TestConfig config) {
        // Explicit override takes priority (D-01)
        if (config.getCommand() != null && !config.getCommand().isBlank()) {
            return config.getCommand();
        }
        // Auto-detect by file presence
        if (Files.exists(projectDir.resolve("pom.xml"))) {
            return "mvn test";
        }
        if (Files.exists(projectDir.resolve("build.gradle")) ||
            Files.exists(projectDir.resolve("build.gradle.kts"))) {
            return "./gradlew test";
        }
        return null; // caller should throw error with clear message
    }

    /**
     * Executes the test command as a subprocess.
     *
     * T-3-01: uses parseCommand() for List<String> — never a single string.
     * Pitfall 2: waitFor with 10-minute timeout — exit code -1 if timeout.
     * redirectErrorStream(true) combines stdout+stderr (test output mixes both).
     *
     * @param projectDir  root directory of the project (CWD of the subprocess)
     * @param testCommand full command (e.g. "mvn test" or "npm test --coverage")
     * @return TestResult with exit code and output truncated to 4KB
     */
    public TestResult run(Path projectDir, String testCommand) throws IOException, InterruptedException {
        // T-3-01: List<String> — no shell string concatenation
        List<String> args = parseCommand(testCommand);
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(projectDir.toFile());
        pb.redirectErrorStream(true); // combine stdout+stderr

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            // Pitfall 3: IOException on process start (e.g. gradlew without execute permission)
            throw new IOException(
                "[Callisto] Failed to start test runner '" + args.get(0) + "': " + e.getMessage() +
                ". If using Gradle, ensure gradlew has execute permission: chmod +x gradlew", e
            );
        }

        // Read output before waitFor to avoid deadlock on full buffers
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Pitfall 2: 10-minute timeout
        boolean finished = proc.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        int exitCode;
        if (!finished) {
            proc.destroyForcibly();
            exitCode = -1;
            output = output + "\n... [timeout after " + TIMEOUT_MINUTES + " minutes — process killed]";
        } else {
            exitCode = proc.exitValue();
        }

        return new TestResult(exitCode, truncate(output, MAX_OUTPUT_BYTES));
    }

    /**
     * Parses a command string into a list of space-separated tokens.
     * T-3-01: tokens passed individually to ProcessBuilder.
     * WR-03: detects quotes and throws IllegalArgumentException with a clear message before
     *        starting the subprocess — avoids a cryptic OS failure.
     * Does not support quotes — for complex commands, use a shell wrapper script.
     *
     * @param command command as "mvn test -pl my-module"
     * @return list of tokens ["mvn", "test", "-pl", "my-module"]
     * @throws IllegalArgumentException if the command contains single or double quotes
     */
    public List<String> parseCommand(String command) {
        if (command.contains("\"") || command.contains("'")) {
            throw new IllegalArgumentException(
                "[Callisto] test.command contains quotes which are not supported by the simple tokenizer. " +
                "Use a shell wrapper script instead: test.command = \"./run-tests.sh\""
            );
        }
        return new ArrayList<>(Arrays.asList(command.trim().split("\\s+")));
    }

    /**
     * Truncates output to maxBytes (head — first bytes, where failures appear).
     * D-04: simple head — first 4KB with suffix if truncated.
     */
    private String truncate(String output, int maxBytes) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return output;
        return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8) + "\n... [output truncated]";
    }
}
