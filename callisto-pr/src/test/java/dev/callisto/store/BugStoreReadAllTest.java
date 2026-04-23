package dev.callisto.store;

import dev.callisto.model.BugRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BugStore.readAll(Path) — D-27 contract.
 */
class BugStoreReadAllTest {

    private static final String LINE_1 = "{\"id\":\"BUG-aabbcc\",\"exceptionType\":\"java.lang.NullPointerException\",\"occurrenceCount\":1}";
    private static final String LINE_2 = "{\"id\":\"BUG-ddeeff\",\"exceptionType\":\"java.io.IOException\",\"occurrenceCount\":2}";
    private static final String MALFORMED = "{not valid json";

    @TempDir
    Path tempDir;

    @Test
    void readAll_returnsEmptyList_whenFileAbsent() {
        Path bugsFile = tempDir.resolve("missing").resolve("bugs.jsonl");
        List<BugRecord> result = BugStore.readAll(bugsFile);
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void readAll_returnsEmptyList_whenFileIsEmpty() throws Exception {
        Path bugsFile = tempDir.resolve("bugs.jsonl");
        Files.createFile(bugsFile);
        List<BugRecord> result = BugStore.readAll(bugsFile);
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void readAll_parsesValidRecords() throws Exception {
        Path bugsFile = tempDir.resolve("bugs.jsonl");
        Files.write(bugsFile, (LINE_1 + "\n" + LINE_2 + "\n").getBytes(StandardCharsets.UTF_8));
        List<BugRecord> result = BugStore.readAll(bugsFile);
        assertEquals(2, result.size());
        assertEquals("BUG-aabbcc", result.get(0).getId());
    }

    @Test
    void readAll_skipsMalformedLine_andContinues() throws Exception {
        Path bugsFile = tempDir.resolve("bugs.jsonl");
        Files.write(bugsFile, (LINE_1 + "\n" + MALFORMED + "\n" + LINE_2 + "\n").getBytes(StandardCharsets.UTF_8));
        List<BugRecord> result = BugStore.readAll(bugsFile);
        assertEquals(2, result.size());
    }
}
