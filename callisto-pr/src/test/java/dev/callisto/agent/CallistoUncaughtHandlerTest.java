package dev.callisto.agent;

import dev.callisto.config.CallistoConfig;
import dev.callisto.model.BugRecord;
import dev.callisto.store.BugStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CallistoUncaughtHandler — D-10 (handler chain), dedup, store append.
 */
class CallistoUncaughtHandlerTest {

    @TempDir
    Path tempDir;

    private Thread.UncaughtExceptionHandler savedHandler;

    @BeforeEach
    void saveHandler() {
        savedHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @AfterEach
    void restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(savedHandler);
    }

    /**
     * Sets outputDir to an absolute path. Path.resolve(absolute) returns the absolute
     * path itself, so BugStore writes to tempDir regardless of user.dir.
     */
    private CallistoConfig makeConfig() {
        CallistoConfig config = new CallistoConfig();
        config.setOutputDir(tempDir.toAbsolutePath().toString());
        return config;
    }

    private BugStore makeStore() {
        BugStore store = new BugStore(makeConfig());
        store.init();
        return store;
    }

    private Path bugsFile() {
        return tempDir.resolve("bugs.jsonl");
    }

    @Test
    void uncaughtException_storesRecord() {
        BugStore store = makeStore();
        CallistoUncaughtHandler.install(store, makeConfig());
        Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();

        handler.uncaughtException(Thread.currentThread(),
            new RuntimeException("test exception for callisto handler"));

        List<BugRecord> records = BugStore.readAll(bugsFile());
        assertEquals(1, records.size(), "One record must be stored");

        BugRecord stored = records.get(0);
        assertEquals("java.lang.RuntimeException", stored.getExceptionType());
        assertEquals("test exception for callisto handler", stored.getExceptionMessage());
        assertNotNull(stored.getId());
        assertTrue(stored.getId().startsWith("BUG-"), "ID must match BUG-xxxxxx format");
    }

    @Test
    void uncaughtException_chainsWithPreviousHandler() {
        AtomicInteger callCount = new AtomicInteger(0);
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> callCount.incrementAndGet());

        CallistoUncaughtHandler.install(makeStore(), makeConfig());
        Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();

        handler.uncaughtException(Thread.currentThread(), new RuntimeException("chain test"));

        // D-10: previous handler must be called exactly once
        assertEquals(1, callCount.get(), "Previous handler must be called");
    }

    @Test
    void uncaughtException_dedup_skipsDuplicateFingerprint() {
        CallistoUncaughtHandler.install(makeStore(), makeConfig());
        Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();

        // Same exception instance → same fingerprint → dedup
        RuntimeException ex = new RuntimeException("dedup test");
        handler.uncaughtException(Thread.currentThread(), ex);
        handler.uncaughtException(Thread.currentThread(), ex);

        List<BugRecord> records = BugStore.readAll(bugsFile());
        assertEquals(1, records.size(),
            "Dedup must suppress second occurrence of same fingerprint within window");
    }

    @Test
    void uncaughtException_differentExceptions_bothStored() {
        CallistoUncaughtHandler.install(makeStore(), makeConfig());
        Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();

        handler.uncaughtException(Thread.currentThread(), new RuntimeException("first"));
        handler.uncaughtException(Thread.currentThread(), new IllegalStateException("second"));

        List<BugRecord> records = BugStore.readAll(bugsFile());
        assertEquals(2, records.size(),
            "Different exception types must produce distinct records");
    }

    @Test
    void uncaughtException_setsTimestamp() {
        CallistoUncaughtHandler.install(makeStore(), makeConfig());
        Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();

        handler.uncaughtException(Thread.currentThread(), new RuntimeException("ts test"));

        List<BugRecord> records = BugStore.readAll(bugsFile());
        assertFalse(records.isEmpty());
        String ts = records.get(0).getTimestamp();
        assertNotNull(ts, "Timestamp must be set");
        // D-16: ISO-8601 UTC contains 'T' separator
        assertTrue(ts.contains("T"), "Timestamp must be ISO-8601 format");
    }

    @Test
    void record_staticMethod_noopWhenNotInstalled() {
        // Before install, record() must not crash even if INSTANCE is null
        // Reset to null to simulate "not yet installed" scenario
        Thread.setDefaultUncaughtExceptionHandler(null);

        assertDoesNotThrow(() ->
            CallistoUncaughtHandler.record(null),
            "record(null) must not throw");
    }
}
