package dev.callisto.cli;

import dev.callisto.Cli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SummaryCommand CLI output — Wave 2 GREEN phase.
 *
 * CLI-01: total count, classification breakdown, top exception types.
 */
class SummaryCommandTest {

    @TempDir
    Path tempDir;

    private int exitCode;

    private static final String INTERNAL_NPE =
        "{\"id\":\"BUG-001\",\"exceptionType\":\"java.lang.NullPointerException\",\"occurrenceCount\":1,\"classification\":\"INTERNAL\"}";
    private static final String EXTERNAL_SOCK =
        "{\"id\":\"BUG-002\",\"exceptionType\":\"java.net.SocketTimeoutException\",\"occurrenceCount\":1,\"classification\":\"EXTERNAL\"}";
    private static final String NO_CLASS =
        "{\"id\":\"BUG-003\",\"exceptionType\":\"java.lang.IllegalStateException\",\"occurrenceCount\":1}";

    private String runSummary(Path dir) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exitCode = new CommandLine(new Cli())
            .setExecutionStrategy(new CommandLine.RunAll())
            .setOut(pw)
            .execute("summary", "--dir", dir.toString());
        pw.flush();
        return sw.toString();
    }

    private Path createBugsJsonl(Path dir, String... jsonLines) throws Exception {
        Path callistoDir = dir.resolve(".callisto");
        Files.createDirectories(callistoDir);
        Path bugsFile = callistoDir.resolve("bugs.jsonl");
        Files.write(bugsFile, Arrays.asList(jsonLines), StandardCharsets.UTF_8);
        return bugsFile;
    }

    @Test
    void summary_printsTotalBugCount() throws Exception {
        createBugsJsonl(tempDir, INTERNAL_NPE, EXTERNAL_SOCK, NO_CLASS);
        String out = runSummary(tempDir);
        assertTrue(out.contains("Total bugs:"), "output: " + out);
        assertTrue(out.contains("3"), "output: " + out);
        assertEquals(0, exitCode);
    }

    @Test
    void summary_showsClassificationBreakdown() throws Exception {
        createBugsJsonl(tempDir, INTERNAL_NPE, INTERNAL_NPE.replace("BUG-001", "BUG-004"), EXTERNAL_SOCK);
        String out = runSummary(tempDir);
        assertTrue(out.contains("INTERNAL"), "output: " + out);
        assertTrue(out.contains("EXTERNAL"), "output: " + out);
    }

    @Test
    void summary_coercesNullClassificationToUNCERTAIN() throws Exception {
        createBugsJsonl(tempDir, NO_CLASS);
        String out = runSummary(tempDir);
        assertTrue(out.contains("UNCERTAIN"), "output: " + out);
    }

    @Test
    void summary_showsSimpleExceptionTypeName() throws Exception {
        createBugsJsonl(tempDir, INTERNAL_NPE);
        String out = runSummary(tempDir);
        assertTrue(out.contains("NullPointerException"), "output: " + out);
        assertFalse(out.contains("java.lang.NullPointerException"), "FQCN must not appear: " + out);
    }

    @Test
    void summary_showsTopExceptionTypes() throws Exception {
        // 3 NPE, 2 SocketTimeout
        createBugsJsonl(tempDir,
            INTERNAL_NPE,
            INTERNAL_NPE.replace("BUG-001", "BUG-A"),
            INTERNAL_NPE.replace("BUG-001", "BUG-B"),
            EXTERNAL_SOCK,
            EXTERNAL_SOCK.replace("BUG-002", "BUG-C")
        );
        String out = runSummary(tempDir);
        int npeIdx = out.indexOf("NullPointerException");
        int sockIdx = out.indexOf("SocketTimeoutException");
        assertTrue(npeIdx >= 0, "NPE not found: " + out);
        assertTrue(sockIdx >= 0, "Sock not found: " + out);
        assertTrue(npeIdx < sockIdx, "NPE should appear before SocketTimeout in: " + out);
    }

    @Test
    void summary_emptyStore_showsZeroOrMessage() throws Exception {
        createBugsJsonl(tempDir); // empty file — no lines
        String out = runSummary(tempDir);
        assertEquals(0, exitCode, "exit code: " + exitCode);
        assertTrue(out.contains("Total bugs:  0") || out.contains("(no bugs captured yet)"),
            "Expected zero count or message, got: " + out);
    }

    @Test
    void summary_missingDir_exitsOneWithMessage() {
        Path nonexistent = tempDir.resolve("nonexistent");
        StringWriter sw = new StringWriter();
        StringWriter errSw = new StringWriter();
        exitCode = new CommandLine(new Cli())
            .setExecutionStrategy(new CommandLine.RunAll())
            .setOut(new PrintWriter(sw))
            .setErr(new PrintWriter(errSw))
            .execute("summary", "--dir", nonexistent.toString());
        assertEquals(1, exitCode, "Expected exit 1 for missing dir");
        String combined = sw.toString() + errSw.toString();
        assertTrue(combined.contains("No bugs.jsonl found"), "Expected error message in: " + combined);
    }
}
