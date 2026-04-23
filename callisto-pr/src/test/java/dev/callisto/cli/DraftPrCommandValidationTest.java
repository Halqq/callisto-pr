package dev.callisto.cli;

import dev.callisto.config.TestConfig;
import dev.callisto.model.BugRecord;
import dev.callisto.model.FixValidation;
import dev.callisto.store.BugStore;
import dev.callisto.validation.TestRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the retry loop validation logic in isolation.
 * The full flow (real subprocess) is covered by manual smoke tests.
 */
public class DraftPrCommandValidationTest {

    @Test
    void fixValidationAttemptsRecorded(@TempDir Path tempDir) throws Exception {
        // REQ-3-03: fix_validation.attempts records number of attempts
        Path bugsFile = tempDir.resolve("bugs.jsonl");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        BugRecord r = new BugRecord();
        r.setId("BUG-aabbcc");
        r.setExceptionType("NPE");
        Files.writeString(bugsFile, mapper.writeValueAsString(r));

        BugStore store = new BugStore(bugsFile);
        FixValidation fv = new FixValidation(3, "test failed output", null);
        store.updateFixStatus("BUG-aabbcc", "INVALID", fv);

        List<BugRecord> records = BugStore.readAll(bugsFile);
        BugRecord updated = records.get(0);
        assertEquals("INVALID", updated.getFixStatus());
        assertEquals(3, updated.getFixValidation().getAttempts()); // REQ-3-05: maxAttempts=3
        assertNull(updated.getFixValidation().getPrUrl()); // no PR when INVALID
    }

    @Test
    void testConfigDefaultMaxAttempts() {
        // REQ-3-05: default maxAttempts = 3
        TestConfig cfg = new TestConfig();
        assertEquals(3, cfg.getMaxAttempts());
    }

    @Test
    void testRunnerDetectsMavenInProject(@TempDir Path projectDir) throws Exception {
        // REQ-3-01: TestRunner integration detects pom.xml
        Files.createFile(projectDir.resolve("pom.xml"));
        TestRunner runner = new TestRunner();
        assertEquals("mvn test", runner.detectTestCommand(projectDir, new TestConfig()));
    }

    @Test
    void fixStatusValidAndPrUrlWrittenWhenTestsPass(@TempDir Path tempDir) throws Exception {
        // REQ-3-04: when tests pass, fix_status=VALID and pr_url are written to BugStore
        Path bugsFile = tempDir.resolve("bugs.jsonl");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        BugRecord r = new BugRecord();
        r.setId("BUG-valid1");
        r.setExceptionType("NPE");
        Files.writeString(bugsFile, mapper.writeValueAsString(r));

        BugStore store = new BugStore(bugsFile);
        // Simulates what DraftPrCommand does after tests pass: updateFixStatus with VALID + pr_url
        FixValidation fv = new FixValidation(1, null, "https://github.com/owner/repo/pull/42");
        store.updateFixStatus("BUG-valid1", "VALID", fv);

        List<BugRecord> records = BugStore.readAll(bugsFile);
        BugRecord updated = records.get(0);
        // REQ-3-04: fix_status=VALID written
        assertEquals("VALID", updated.getFixStatus());
        // REQ-3-04: pr_url written to fix_validation
        assertEquals("https://github.com/owner/repo/pull/42", updated.getFixValidation().getPrUrl());
        // D-07: lastTestOutput omitted when VALID (implementer criterion per D-07)
        assertNull(updated.getFixValidation().getLastTestOutput());
    }
}
