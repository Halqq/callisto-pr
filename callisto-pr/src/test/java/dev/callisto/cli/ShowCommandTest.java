package dev.callisto.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.callisto.model.BugRecord;
import dev.callisto.model.FixValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ShowCommandTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String runShow(String bugId, Path projectDir) {
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new ShowCommand());
        cmd.setOut(new PrintWriter(sw));
        cmd.setErr(new PrintWriter(new StringWriter()));
        cmd.execute(bugId, "--dir", projectDir.toString());
        return sw.toString();
    }

    @Test
    void showInvalidBugDisplaysTestOutput(@TempDir Path tempDir) throws Exception {
        // REQ-3-06: callisto show <id> displays fix_validation.last_test_output if INVALID
        Path callisto = tempDir.resolve(".callisto");
        Files.createDirectories(callisto);
        BugRecord r = new BugRecord();
        r.setId("BUG-aabbcc");
        r.setExceptionType("java.lang.NullPointerException");
        r.setFixStatus("INVALID");
        FixValidation fv = new FixValidation(3, "BUILD FAILURE: 2 tests failed", null);
        r.setFixValidation(fv);
        Files.writeString(callisto.resolve("bugs.jsonl"), mapper.writeValueAsString(r));

        String output = runShow("BUG-aabbcc", tempDir);
        assertTrue(output.contains("INVALID"), "Should show INVALID status");
        assertTrue(output.contains("BUILD FAILURE"), "Should show test output");
    }

    @Test
    void showValidBugDisplaysPrUrl(@TempDir Path tempDir) throws Exception {
        // REQ-3-06: if VALID -> display pr_url
        Path callisto = tempDir.resolve(".callisto");
        Files.createDirectories(callisto);
        BugRecord r = new BugRecord();
        r.setId("BUG-aabbcc");
        r.setExceptionType("java.lang.NullPointerException");
        r.setFixStatus("VALID");
        FixValidation fv = new FixValidation(1, null, "https://github.com/x/y/pull/42");
        r.setFixValidation(fv);
        Files.writeString(callisto.resolve("bugs.jsonl"), mapper.writeValueAsString(r));

        String output = runShow("BUG-aabbcc", tempDir);
        assertTrue(output.contains("VALID"), "Should show VALID status");
        assertTrue(output.contains("https://github.com/x/y/pull/42"), "Should show PR URL");
    }

    @Test
    void showPendingBugNoPrUrl(@TempDir Path tempDir) throws Exception {
        // D-10: fix_status null -> PENDING (no fix_validation)
        Path callisto = tempDir.resolve(".callisto");
        Files.createDirectories(callisto);
        BugRecord r = new BugRecord();
        r.setId("BUG-aabbcc");
        r.setExceptionType("java.lang.NullPointerException");
        Files.writeString(callisto.resolve("bugs.jsonl"), mapper.writeValueAsString(r));

        String output = runShow("BUG-aabbcc", tempDir);
        assertTrue(output.contains("PENDING") || output.contains("BUG-aabbcc"),
            "Should show bug info");
    }
}
