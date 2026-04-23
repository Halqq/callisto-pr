package dev.callisto.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.callisto.model.BugRecord;
import dev.callisto.model.FixValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BugStoreFixStatusTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void updateFixStatusWritesCorrectLine(@TempDir Path tempDir) throws Exception {
        // REQ-3-03: fix_status=INVALID persists in JSONL
        Path bugsFile = tempDir.resolve("bugs.jsonl");
        BugRecord r1 = new BugRecord();
        r1.setId("BUG-111111");
        r1.setExceptionType("java.lang.NullPointerException");
        BugRecord r2 = new BugRecord();
        r2.setId("BUG-222222");
        r2.setExceptionType("java.lang.RuntimeException");

        // Write two records to the JSONL
        Files.writeString(bugsFile,
            mapper.writeValueAsString(r1) + System.lineSeparator() +
            mapper.writeValueAsString(r2) + System.lineSeparator());

        BugStore store = new BugStore(bugsFile);
        FixValidation fv = new FixValidation(3, "BUILD FAILURE: tests failed", null);
        store.updateFixStatus("BUG-111111", "INVALID", fv);

        List<BugRecord> records = BugStore.readAll(bugsFile);
        assertEquals(2, records.size());
        BugRecord updated = records.stream().filter(r -> "BUG-111111".equals(r.getId())).findFirst().orElseThrow();
        assertEquals("INVALID", updated.getFixStatus());
        assertEquals(3, updated.getFixValidation().getAttempts());
        assertEquals("BUILD FAILURE: tests failed", updated.getFixValidation().getLastTestOutput());

        // The other record's line was not modified
        BugRecord other = records.stream().filter(r -> "BUG-222222".equals(r.getId())).findFirst().orElseThrow();
        assertNull(other.getFixStatus());
    }

    @Test
    void updateFixStatusNonexistentFileNoException(@TempDir Path tempDir) {
        // Non-existent file: must return silently
        Path nonexistent = tempDir.resolve("nofile.jsonl");
        BugStore store = new BugStore(nonexistent);
        assertDoesNotThrow(() -> store.updateFixStatus("BUG-abc", "INVALID", new FixValidation(1, "out", null)));
    }
}
