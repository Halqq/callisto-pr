package dev.callisto.store;

import dev.callisto.model.BugRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BugStore.append(), init(), and hasId() — STORE-01, D-03, thread-safety.
 */
class BugStoreAppendTest {

    @TempDir
    Path tempDir;

    private static BugRecord makeRecord(String id, String exType) {
        BugRecord r = new BugRecord();
        r.setId(id);
        r.setExceptionType(exType);
        r.setExceptionMessage("message for " + id);
        r.setThreadName("test-thread");
        r.setOccurrenceCount(1);
        return r;
    }

    @Test
    void append_createsFile_ifNotExists() throws Exception {
        Path bugsFile = tempDir.resolve("bugs.jsonl");
        BugStore store = new BugStore(bugsFile);
        store.init();

        assertFalse(Files.exists(bugsFile), "File must not exist before first append");

        store.append(makeRecord("BUG-000001", "java.lang.RuntimeException"));

        assertTrue(Files.exists(bugsFile), "File must be created after first append");
    }

    @Test
    void append_writesValidJsonl_oneRecordPerLine() throws Exception {
        Path bugsFile = tempDir.resolve("bugs.jsonl");
        BugStore store = new BugStore(bugsFile);
        store.init();

        store.append(makeRecord("BUG-000001", "java.lang.NullPointerException"));
        store.append(makeRecord("BUG-000002", "java.io.IOException"));

        List<String> lines = Files.readAllLines(bugsFile);
        // Each non-empty line is valid JSON containing its ID
        List<String> nonEmpty = lines.stream().filter(l -> !l.isBlank()).toList();
        assertEquals(2, nonEmpty.size(), "Must have 2 JSONL lines");
        assertTrue(nonEmpty.get(0).contains("BUG-000001"));
        assertTrue(nonEmpty.get(1).contains("BUG-000002"));
    }

    @Test
    void append_addsIdToKnownIds() throws Exception {
        Path bugsFile = tempDir.resolve("bugs.jsonl");
        BugStore store = new BugStore(bugsFile);
        store.init();

        assertFalse(store.hasId("BUG-abcdef"));
        store.append(makeRecord("BUG-abcdef", "java.lang.Exception"));
        assertTrue(store.hasId("BUG-abcdef"), "ID must be known after append");
    }

    @Test
    void init_reconstructsKnownIds_fromExistingFile() throws Exception {
        Path bugsFile = tempDir.resolve("bugs.jsonl");
        // Write a record directly to simulate prior session
        BugStore firstStore = new BugStore(bugsFile);
        firstStore.init();
        firstStore.append(makeRecord("BUG-persist", "java.lang.Exception"));

        // New store instance reads the same file
        BugStore secondStore = new BugStore(bugsFile);
        secondStore.init();

        // Must reconstruct knownIds from file
        assertTrue(secondStore.hasId("BUG-persist"),
            "init() must reconstruct knownIds from existing bugs.jsonl (Pitfall 5)");
    }

    @Test
    void init_emptyDir_createsDirectory() throws Exception {
        Path subDir = tempDir.resolve("nested").resolve(".callisto");
        Path bugsFile = subDir.resolve("bugs.jsonl");
        BugStore store = new BugStore(bugsFile);

        assertFalse(Files.exists(subDir));
        store.init();
        assertTrue(Files.isDirectory(subDir), "D-03: init() must create output directory");
    }

    @Test
    void append_concurrent_allRecordsPersisted() throws Exception {
        Path bugsFile = tempDir.resolve("bugs.jsonl");
        BugStore store = new BugStore(bugsFile);
        store.init();

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        List<Throwable> errors = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String id = String.format("BUG-%06x", i);
            executor.submit(() -> {
                try {
                    startLatch.await(); // all threads start simultaneously
                    store.append(makeRecord(id, "java.lang.Exception"));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all threads
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads must complete");
        executor.shutdown();

        assertTrue(errors.isEmpty(), "No errors during concurrent appends: " + errors);

        List<BugRecord> records = BugStore.readAll(bugsFile);
        assertEquals(threadCount, records.size(),
            "All " + threadCount + " concurrent records must be persisted (thread-safety check)");
    }

    @Test
    void readAll_afterMultipleAppends_returnsAllRecords() throws Exception {
        Path bugsFile = tempDir.resolve("bugs.jsonl");
        BugStore store = new BugStore(bugsFile);
        store.init();

        for (int i = 0; i < 5; i++) {
            store.append(makeRecord("BUG-00000" + i, "java.lang.Exception"));
        }

        List<BugRecord> records = BugStore.readAll(bugsFile);
        assertEquals(5, records.size());
    }
}
