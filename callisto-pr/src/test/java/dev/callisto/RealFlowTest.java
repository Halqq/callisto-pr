package dev.callisto;

import dev.callisto.agent.CallistoUncaughtHandler;
import dev.callisto.config.CallistoConfig;
import dev.callisto.model.BugRecord;
import dev.callisto.store.BugStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real integration flow tests — no mocks, no stubs.
 * Each test exercises the full stack from config → handler → store → CLI.
 */
class RealFlowTest {

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

    /** outputDir = absolute tempDir path — BugStore writes directly to tempDir/bugs.jsonl */
    private CallistoConfig configForDir(Path dir) {
        CallistoConfig config = new CallistoConfig();
        config.setOutputDir(dir.toAbsolutePath().toString());
        return config;
    }

    private BugStore installAndGetStore(CallistoConfig config) {
        BugStore store = new BugStore(config);
        store.init();
        CallistoUncaughtHandler.install(store, config);
        return store;
    }

    private Thread.UncaughtExceptionHandler handler() {
        return Thread.getDefaultUncaughtExceptionHandler();
    }

    // ─────────────────────────────────────────────────────────────
    // Flow 1: SocketTimeoutException → rule classifier → EXTERNAL
    //         stored without any LLM call (synchronous, D-22)
    // ─────────────────────────────────────────────────────────────
    @Test
    void flow_socketTimeout_ruleClassifiesExternal_synchronously() throws Exception {
        CallistoConfig config = configForDir(tempDir);
        installAndGetStore(config);

        // SocketTimeoutException is in EXTERNAL_SIMPLE_NAMES in RuleClassifier
        SocketTimeoutException ex = new SocketTimeoutException("connection timed out after 30s");
        handler().uncaughtException(Thread.currentThread(), ex);

        // Rule classification is synchronous — no sleep needed
        List<BugRecord> records = BugStore.readAll(tempDir.resolve("bugs.jsonl"));
        assertEquals(1, records.size(), "Exactly one record must be stored");

        BugRecord record = records.get(0);
        assertEquals("java.net.SocketTimeoutException", record.getExceptionType());
        assertEquals("EXTERNAL", record.getClassification(),
            "RuleClassifier must pre-classify SocketTimeoutException as EXTERNAL synchronously");
        assertNotNull(record.getReasoning());
        assertTrue(record.getReasoning().contains("SocketTimeoutException"),
            "Reasoning must mention the exception simple name");
    }

    // ─────────────────────────────────────────────────────────────
    // Flow 2: Exception with cause → causeChain persisted in JSONL
    // ─────────────────────────────────────────────────────────────
    @Test
    void flow_exceptionWithCause_causeChainPersistedInRecord() throws Exception {
        CallistoConfig config = configForDir(tempDir);
        installAndGetStore(config);

        // Wrap: RuntimeException caused by IOException
        Exception rootCause = new java.io.IOException("disk read failed");
        RuntimeException outer = new RuntimeException("processing failed", rootCause);

        handler().uncaughtException(Thread.currentThread(), outer);

        List<BugRecord> records = BugStore.readAll(tempDir.resolve("bugs.jsonl"));
        assertEquals(1, records.size());

        BugRecord record = records.get(0);
        assertNotNull(record.getCauseChain(), "causeChain must be set for wrapped exceptions");
        assertEquals(1, record.getCauseChain().size(), "One cause in chain");
        assertEquals("java.io.IOException", record.getCauseChain().get(0).getExceptionType());
        assertEquals("disk read failed", record.getCauseChain().get(0).getExceptionMessage());
    }

    // ─────────────────────────────────────────────────────────────
    // Flow 3: Config file → Handler → Store → CLI summary
    //         Full end-to-end: write config, capture bugs, read via CLI
    // ─────────────────────────────────────────────────────────────
    @Test
    void flow_configToHandlerToStore_toCliSummary() throws Exception {
        // Write callisto.json pointing to tempDir/.callisto
        Path callistoDir = tempDir.resolve(".callisto");
        Files.createDirectories(callistoDir);

        String configJson = "{\"outputDir\": \"" + callistoDir.toAbsolutePath().toString().replace("\\", "/") + "\"}";
        Files.writeString(tempDir.resolve("callisto.json"), configJson);

        // Use the config to install handler
        CallistoConfig config = configForDir(callistoDir);
        installAndGetStore(config);

        // Capture 3 different exceptions
        handler().uncaughtException(Thread.currentThread(), new RuntimeException("bug 1"));
        handler().uncaughtException(Thread.currentThread(), new IllegalStateException("bug 2"));
        handler().uncaughtException(Thread.currentThread(), new SocketTimeoutException("bug 3"));

        // Verify raw file first
        Path bugsFile = callistoDir.resolve("bugs.jsonl");
        assertTrue(Files.exists(bugsFile), "bugs.jsonl must exist after captures");
        List<BugRecord> rawRecords = BugStore.readAll(bugsFile);
        assertEquals(3, rawRecords.size(), "All 3 exceptions must be stored");

        // Run CLI summary pointing to tempDir (CLI resolves .callisto/ internally)
        StringWriter sw = new StringWriter();
        int exitCode = new CommandLine(new Cli())
            .setExecutionStrategy(new CommandLine.RunAll())
            .setOut(new PrintWriter(sw))
            .execute("summary", "--dir", tempDir.toString());
        sw.flush();

        String output = sw.toString();
        assertEquals(0, exitCode, "CLI must exit 0. Output:\n" + output);
        assertTrue(output.contains("3") || output.contains("Total"),
            "Summary must show total count. Output:\n" + output);
        // SocketTimeoutException was rule-classified as EXTERNAL
        assertTrue(output.contains("EXTERNAL"),
            "Summary must show EXTERNAL classification. Output:\n" + output);
    }

    // ─────────────────────────────────────────────────────────────
    // Flow 4: projectPackagePrefix → packageSource labels in record
    //         Internal frames labeled INTERNAL, external EXTERNAL
    // ─────────────────────────────────────────────────────────────
    @Test
    void flow_packagePrefix_producesPackageSourceInRecord() throws Exception {
        CallistoConfig config = configForDir(tempDir);
        // Label dev.callisto.* frames as INTERNAL — test frames will include these
        config.setProjectPackagePrefix(List.of("dev.callisto"));
        installAndGetStore(config);

        // This exception's stack will include dev.callisto.RealFlowTest frames
        RuntimeException ex = new RuntimeException("packageSource test");
        handler().uncaughtException(Thread.currentThread(), ex);

        List<BugRecord> records = BugStore.readAll(tempDir.resolve("bugs.jsonl"));
        assertEquals(1, records.size());

        BugRecord record = records.get(0);
        assertNotNull(record.getPackageSource(),
            "packageSource must be set when projectPackagePrefix is configured (D-13)");
        assertFalse(record.getPackageSource().isEmpty(),
            "packageSource must have at least one entry");

        // This test class is dev.callisto.RealFlowTest → must be labeled INTERNAL
        String label = record.getPackageSource().get("dev.callisto.RealFlowTest");
        assertEquals("INTERNAL", label,
            "dev.callisto.RealFlowTest must be labeled INTERNAL — it matches the prefix");
    }

    // ─────────────────────────────────────────────────────────────
    // Flow 5: Store persistence across restarts (Pitfall 5)
    //         New BugStore on same file reconstructs knownIds on init()
    // ─────────────────────────────────────────────────────────────
    @Test
    void flow_storeRestart_reconstructsKnownIds_fromDisk() throws Exception {
        CallistoConfig config = configForDir(tempDir);
        installAndGetStore(config);

        // Capture exception in "first JVM session"
        handler().uncaughtException(Thread.currentThread(),
            new RuntimeException("persisted across restart"));

        // Read the stored ID from disk
        List<BugRecord> firstSession = BugStore.readAll(tempDir.resolve("bugs.jsonl"));
        assertEquals(1, firstSession.size());
        String persistedId = firstSession.get(0).getId();
        assertNotNull(persistedId);

        // Simulate restart: new BugStore instance on the same file
        BugStore restarted = new BugStore(config);
        restarted.init(); // must reconstruct knownIds from bugs.jsonl

        // hasId must return true — dedup will prevent re-storing the same bug
        assertTrue(restarted.hasId(persistedId),
            "After restart, hasId must return true for IDs read from disk (Pitfall 5 protection)");
    }
}
