package dev.callisto.agent;

import dev.callisto.config.CallistoConfig;
import dev.callisto.llm.ClassificationResult;
import dev.callisto.llm.ClaudeCodeClassifier;
import dev.callisto.llm.LlmClassifier;
import dev.callisto.llm.RuleClassifier;
import dev.callisto.model.BugRecord;
import dev.callisto.model.CauseRecord;
import dev.callisto.store.BugStore;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Callisto's {@link Thread.UncaughtExceptionHandler} and caught-exception recorder.
 *
 * Installed via {@link Thread#setDefaultUncaughtExceptionHandler}. The previous handler
 * is saved and chained — called after Callisto records the bug.
 *
 * <p>Flow per exception:
 * <ol>
 *   <li>Compute fingerprint ({@link Fingerprinter#compute})</li>
 *   <li>Derive stable ID ({@link BugIdGenerator#computeId})</li>
 *   <li>Dedup check — skip if already seen within the TTL window</li>
 *   <li>Build and persist {@link dev.callisto.model.BugRecord}</li>
 *   <li>Classify asynchronously via LLM or rule-based classifier</li>
 *   <li>Chain to previous handler</li>
 * </ol>
 *
 * <p>Thread-safety: {@link DedupCache} uses {@link java.util.concurrent.ConcurrentHashMap};
 * {@link dev.callisto.store.BugStore} is internally synchronized.
 */
public class CallistoUncaughtHandler implements Thread.UncaughtExceptionHandler {

    private static final long DEDUP_WINDOW_MS = 60_000L;
    private static final int MAX_CAUSE_DEPTH = 10;

    /**
     * Singleton instance — set during install(), used by ThrowableInterceptor.record().
     * Volatile for safe publication across threads (Byte Buddy advice runs on any thread).
     */
    private static volatile CallistoUncaughtHandler INSTANCE = null;

    /**
     * Entry point for Byte Buddy ThrowableInterceptor — handles caught exceptions that
     * would never reach the UncaughtExceptionHandler.
     *
     * Public static so Byte Buddy inlined advice can call it.
     * No-op if the agent has not yet been installed.
     *
     * @param t the newly-constructed Throwable captured at construction time
     */
    public static void record(Throwable t) {
        CallistoUncaughtHandler instance = INSTANCE;
        if (instance == null || t == null) {
            return; // not yet installed — silently ignore
        }
        try {
            instance.handleException(Thread.currentThread(), t);
        } catch (Throwable ignored) {
            // record() MUST NOT propagate — target app must not be affected
        }
    }

    private final BugStore store;
    private final PackageLabeler labeler;
    private final DedupCache dedupCache;
    private final Thread.UncaughtExceptionHandler previousHandler;
    private final RuleClassifier ruleClassifier;
    private final LlmClassifier classifier;
    private final ExecutorService classifyExecutor;

    private CallistoUncaughtHandler(
        BugStore store,
        PackageLabeler labeler,
        DedupCache dedupCache,
        Thread.UncaughtExceptionHandler previousHandler,
        RuleClassifier ruleClassifier,
        LlmClassifier classifier,
        ExecutorService classifyExecutor
    ) {
        this.store = store;
        this.labeler = labeler;
        this.dedupCache = dedupCache;
        this.previousHandler = previousHandler;
        this.ruleClassifier = ruleClassifier;
        this.classifier = classifier;
        this.classifyExecutor = classifyExecutor;
    }

    private static ExecutorService buildDaemonExecutor() {
        ThreadFactory daemonFactory = r -> {
            Thread t = new Thread(r, "callisto-classifier");
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadExecutor(daemonFactory);
    }

    /**
     * Installs this handler as the default {@link Thread.UncaughtExceptionHandler}.
     * The previous handler is saved and chained. Must be called only after
     * {@link dev.callisto.store.BugStore#init()} has completed.
     *
     * @param store   already-initialized BugStore
     * @param config  loaded CallistoConfig
     */
    public static void install(BugStore store, CallistoConfig config) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        PackageLabeler labeler = new PackageLabeler(config.getProjectPackagePrefix());
        DedupCache cache = new DedupCache(DEDUP_WINDOW_MS);
        RuleClassifier ruleClassifier = new RuleClassifier();
        LlmClassifier classifier = new ClaudeCodeClassifier();
        System.err.println("[Callisto] classification enabled (via Claude Code CLI)");
        ExecutorService executor = buildDaemonExecutor();

        CallistoUncaughtHandler handler = new CallistoUncaughtHandler(
            store, labeler, cache, previous, ruleClassifier, classifier, executor);
        INSTANCE = handler; // publish before setting as default handler (volatile write)
        Thread.setDefaultUncaughtExceptionHandler(handler);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            handleException(thread, throwable);
        } catch (Exception e) {
            // Handler MUST NEVER throw an exception — log and continue
            System.err.println("[Callisto] ERROR in uncaughtException handler: " + e.getMessage());
        } finally {
            // always chain with previous handler
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            }
        }
    }

    private void handleException(Thread thread, Throwable throwable) {
        // 1. Fingerprint and ID
        String fingerprint = Fingerprinter.compute(throwable);
        String id = BugIdGenerator.computeId(fingerprint);

        // 2. Dedup — skip if already seen within the TTL window
        if (dedupCache.shouldSkip(id)) {
            return;
        }

        // 3. Build stackTrace as string array
        List<String> stackTrace = formatStackTrace(throwable.getStackTrace());

        // 4. Build causeChain — iterative, max 10 levels to avoid cycles
        List<CauseRecord> causeChain = buildCauseChain(throwable);

        // 5. Build packageSource — null if prefix not configured (omitted from JSON)
        Map<String, String> packageSource = buildPackageSource(throwable);

        // 6. Assemble BugRecord
        BugRecord record = new BugRecord();
        record.setId(id);
        record.setTimestamp(Instant.now().toString()); // ISO-8601 UTC
        record.setOccurrenceCount(1);
        record.setExceptionType(throwable.getClass().getName());
        record.setExceptionMessage(throwable.getMessage());
        record.setThreadName(thread.getName());
        record.setStackTrace(stackTrace);
        record.setCauseChain(causeChain.isEmpty() ? null : causeChain);
        record.setPackageSource(packageSource); // null -> omitted by @JsonInclude(NON_NULL)

        // 7. Persist
        store.append(record);

        // Rule-based pre-classification — zero cost for known network exceptions
        ClassificationResult ruleResult = ruleClassifier.tryClassify(record);
        if (ruleResult != null) {
            // Synchronous — rule classification is instant, no LLM call
            store.updateClassification(record.getId(), ruleResult.classification, ruleResult.reasoning);
        } else {
            // Async classification — does not block the crashing thread
            final String recordId = record.getId();
            classifyExecutor.submit(() -> {
                try {
                    ClassificationResult result = classifier.classify(record);
                    store.updateClassification(recordId, result.classification, result.reasoning);
                } catch (Exception e) {
                    // classify() should never throw (contract), but defensive
                    System.err.println("[Callisto] ERROR: Classification failed for " + recordId + ": " + e.getMessage());
                }
            });
        }
    }

    /** Formats StackTraceElement[] as List&lt;String&gt; using {@link Fingerprinter#formatFrame}. */
    private List<String> formatStackTrace(StackTraceElement[] elements) {
        List<String> result = new ArrayList<>(elements.length);
        for (StackTraceElement el : elements) {
            result.add(Fingerprinter.formatFrame(el));
        }
        return result;
    }

    /**
     * Builds the cause chain iteratively up to {@code MAX_CAUSE_DEPTH} levels.
     * Uses an {@link java.util.IdentityHashMap} to detect cycles in {@link Throwable#getCause()}.
     */
    private List<CauseRecord> buildCauseChain(Throwable t) {
        List<CauseRecord> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cause = t.getCause();
        int depth = 0;

        while (cause != null && !seen.contains(cause) && depth < MAX_CAUSE_DEPTH) {
            seen.add(cause);
            chain.add(new CauseRecord(
                cause.getClass().getName(),
                cause.getMessage(),
                formatStackTrace(cause.getStackTrace())
            ));
            cause = cause.getCause();
            depth++;
        }
        return chain;
    }

    /**
     * Builds a {@code className → INTERNAL/EXTERNAL} map for all frames in the exception and its causes.
     * Returns {@code null} if {@link PackageLabeler} has no configured prefixes,
     * causing {@code packageSource} to be omitted from the JSONL record.
     */
    private Map<String, String> buildPackageSource(Throwable t) {
        // Check if PackageLabeler has configured prefixes
        String testLabel = labeler.label("__probe__");
        if (testLabel == null) {
            return null; // no prefixes configured — omit packageSource
        }

        Map<String, String> source = new LinkedHashMap<>();

        // Frames from the main exception
        for (StackTraceElement el : t.getStackTrace()) {
            String className = el.getClassName();
            source.computeIfAbsent(className, labeler::label);
        }

        // Frames from causes — iterative traversal with cycle guard
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && !seen.contains(cause) && depth < MAX_CAUSE_DEPTH) {
            seen.add(cause);
            for (StackTraceElement el : cause.getStackTrace()) {
                source.computeIfAbsent(el.getClassName(), labeler::label);
            }
            cause = cause.getCause();
            depth++;
        }

        return source.isEmpty() ? null : source;
    }
}
