package dev.callisto.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.callisto.model.BugRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BugStoreClassificationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BugRecord makeRecord(String id) {
        BugRecord r = new BugRecord();
        r.setId(id);
        r.setExceptionType("java.lang.NullPointerException");
        r.setExceptionMessage("test");
        r.setThreadName("main");
        r.setOccurrenceCount(1);
        r.setTimestamp("2026-01-01T00:00:00.000Z");
        return r;
    }

    @Test
    void updateClassification_updatesMatchingRecord(@TempDir Path tempDir) throws Exception {
        Path bugFile = tempDir.resolve("bugs.jsonl");
        BugStore store = new BugStore(bugFile);
        store.init();

        BugRecord record = makeRecord("BUG-aabb01");
        store.append(record);

        store.updateClassification("BUG-aabb01", "INTERNAL", "test reason");

        List<String> lines = Files.readAllLines(bugFile);
        assertEquals(1, lines.size());
        JsonNode node = MAPPER.readTree(lines.get(0));
        assertEquals("INTERNAL", node.path("classification").asText());
        assertEquals("test reason", node.path("reasoning").asText());
    }

    @Test
    void updateClassification_preservesOtherRecords(@TempDir Path tempDir) throws Exception {
        Path bugFile = tempDir.resolve("bugs.jsonl");
        BugStore store = new BugStore(bugFile);
        store.init();

        store.append(makeRecord("BUG-001"));
        store.append(makeRecord("BUG-002"));
        store.append(makeRecord("BUG-003"));

        store.updateClassification("BUG-002", "EXTERNAL", "network error");

        List<String> lines = Files.readAllLines(bugFile);
        assertEquals(3, lines.size());

        JsonNode node0 = MAPPER.readTree(lines.get(0));
        JsonNode node1 = MAPPER.readTree(lines.get(1));
        JsonNode node2 = MAPPER.readTree(lines.get(2));

        assertEquals("BUG-001", node0.path("id").asText());
        assertFalse(node0.has("classification"));

        assertEquals("BUG-002", node1.path("id").asText());
        assertEquals("EXTERNAL", node1.path("classification").asText());
        assertEquals("network error", node1.path("reasoning").asText());

        assertEquals("BUG-003", node2.path("id").asText());
        assertFalse(node2.has("classification"));
    }

    @Test
    void updateClassification_nonexistentId_noChange(@TempDir Path tempDir) throws Exception {
        Path bugFile = tempDir.resolve("bugs.jsonl");
        BugStore store = new BugStore(bugFile);
        store.init();

        store.append(makeRecord("BUG-exists"));

        String before = Files.readString(bugFile);
        store.updateClassification("BUG-nonexistent", "INTERNAL", "reason");
        String after = Files.readString(bugFile);

        assertEquals(before, after);
    }

    @Test
    void updateClassification_fileNotExists_noError(@TempDir Path tempDir) {
        Path bugFile = tempDir.resolve("nonexistent-bugs.jsonl");
        BugStore store = new BugStore(bugFile);
        // Do NOT call init() — file does not exist

        assertDoesNotThrow(() -> store.updateClassification("BUG-xxx", "INTERNAL", "reason"));
        assertFalse(Files.exists(bugFile));
    }
}
