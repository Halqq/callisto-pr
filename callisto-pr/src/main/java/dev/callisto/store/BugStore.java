package dev.callisto.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.callisto.config.CallistoConfig;
import dev.callisto.model.BugRecord;
import dev.callisto.model.FixValidation;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Append-only JSONL persistence for bug records.
 *
 * STORE-01: bugs persist across restarts in JSONL format
 * D-03: .callisto/ created via Files.createDirectories if it does not exist
 *
 * Thread-safety: synchronized on fileLock to serialize writes.
 * Init: reads existing bugs.jsonl at initialization to reconstruct the set of IDs.
 *
 * Avoided anti-pattern (Pitfall 4): always bw.newLine() after writeValue.
 * Avoided anti-pattern (Pitfall 5): reconstructs knownIds from file on restart.
 */
public class BugStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .disable(SerializationFeature.INDENT_OUTPUT); // JSONL: one record per line

    private final Path bugsFile;
    private final Object fileLock = new Object();

    // Known IDs — reconstructed in init()
    private final Set<String> knownIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public BugStore(CallistoConfig config) {
        this(Paths.get(System.getProperty("user.dir"))
            .resolve(config.getOutputDir())
            .resolve("bugs.jsonl"));
    }

    /** Public constructor for tests with TempDir */
    public BugStore(Path bugsFile) {
        this.bugsFile = bugsFile;
    }

    /**
     * Initializes the store: creates the .callisto/ directory and reads existing records.
     * D-03: Files.createDirectories — does not fail if already exists.
     * Pitfall 5: reconstructs knownIds from file to avoid duplicate IDs on restart.
     */
    public void init() {
        try {
            Files.createDirectories(bugsFile.getParent());
        } catch (IOException e) {
            System.err.println("[Callisto] WARN: Failed to create output directory: " + e.getMessage());
        }

        if (!Files.exists(bugsFile)) {
            return; // first use — file does not exist yet
        }

        // Read existing records to reconstruct knownIds
        try (BufferedReader reader = Files.newBufferedReader(bugsFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    BugRecord record = MAPPER.readValue(line, BugRecord.class);
                    if (record.getId() != null) {
                        knownIds.add(record.getId());
                    }
                } catch (Exception e) {
                    // Invalid line (corrupted JSON) — skip with warning
                    System.err.println("[Callisto] WARN: Skipping malformed line in bugs.jsonl: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[Callisto] WARN: Failed to read existing bugs.jsonl: " + e.getMessage());
        }
    }

    /**
     * Persists a new bug record to bugs.jsonl.
     * Thread-safe: synchronized on fileLock.
     * Pitfall 4: newLine() required after writeValue for valid JSONL.
     *
     * @param record record to persist
     */
    public void append(BugRecord record) {
        synchronized (fileLock) {
            try (FileChannel channel = FileChannel.open(bugsFile,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                String json = MAPPER.writeValueAsString(record) + System.lineSeparator();
                channel.write(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
                channel.force(true); // fsync for durability
                // Add to knownIds only after successful write — prevents silent data loss
                // if writeValue throws, the ID must not be marked as persisted
                knownIds.add(record.getId());
            } catch (IOException e) {
                System.err.println("[Callisto] ERROR: Failed to write bug record: " + e.getMessage());
            }
        }
    }

    /**
     * Updates classification + reasoning of an existing record in the JSONL.
     * D-18: reads all lines, updates the match by ID, rewrites the file.
     * USES SAME fileLock as append() — CRITICAL to avoid race conditions.
     *
     * @param id             bug ID (e.g. "BUG-aabbcc")
     * @param classification "INTERNAL", "EXTERNAL", or "UNCERTAIN"
     * @param reasoning      one-sentence explanation
     */
    public void updateClassification(String id, String classification, String reasoning) {
        synchronized (fileLock) {
            if (!Files.exists(bugsFile)) return;
            // WR-04: use FileChannel + FileLock consistent with append() to protect against
            // concurrent writes from the agent JVM running in a separate process.
            try (FileChannel channel = FileChannel.open(bugsFile,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                byte[] raw = new byte[(int) channel.size()];
                channel.read(ByteBuffer.wrap(raw));
                String content = new String(raw, StandardCharsets.UTF_8);
                // split without -1 drops trailing empty strings (avoids adding extra \n)
                String[] lines = content.split("\n");
                StringBuilder sb = new StringBuilder();
                for (String line : lines) {
                    if (line.trim().isEmpty()) {
                        sb.append(line).append("\n");
                        continue;
                    }
                    try {
                        BugRecord r = MAPPER.readValue(line, BugRecord.class);
                        if (id.equals(r.getId())) {
                            r.setClassification(classification);
                            r.setReasoning(reasoning);
                            sb.append(MAPPER.writeValueAsString(r)).append("\n");
                        } else {
                            sb.append(line).append("\n");
                        }
                    } catch (Exception e) {
                        sb.append(line).append("\n"); // preserve malformed lines
                    }
                }
                // Remove trailing newline added above if original didn't end with one
                String result = sb.toString();
                if (!content.endsWith("\n") && result.endsWith("\n")) {
                    result = result.substring(0, result.length() - 1);
                }
                byte[] out = result.getBytes(StandardCharsets.UTF_8);
                channel.truncate(0);
                channel.position(0);
                channel.write(ByteBuffer.wrap(out));
                channel.force(true);
            } catch (IOException e) {
                System.err.println("[Callisto] ERROR: Failed to update classification: " + e.getMessage());
            }
        }
    }

    /**
     * Updates fix_status + fix_validation of an existing record in the JSONL.
     * D-09: rewrites JSONL by ID — same pattern as updateClassification().
     * USES SAME fileLock as append() and updateClassification() — CRITICAL.
     *
     * @param id            bug ID (e.g. "BUG-aabbcc")
     * @param fixStatus     "PENDING", "VALID", or "INVALID"
     * @param fixValidation object with attempts, lastTestOutput, prUrl
     */
    public void updateFixStatus(String id, String fixStatus, FixValidation fixValidation) {
        synchronized (fileLock) {
            if (!Files.exists(bugsFile)) return;
            // WR-04: use FileChannel + FileLock consistent with append() to protect against
            // concurrent writes from the agent JVM running in a separate process.
            try (FileChannel channel = FileChannel.open(bugsFile,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                byte[] raw = new byte[(int) channel.size()];
                channel.read(ByteBuffer.wrap(raw));
                String content = new String(raw, StandardCharsets.UTF_8);
                // split without -1 drops trailing empty strings (avoids adding extra \n)
                String[] lines = content.split("\n");
                StringBuilder sb = new StringBuilder();
                for (String line : lines) {
                    if (line.trim().isEmpty()) {
                        sb.append(line).append("\n");
                        continue;
                    }
                    try {
                        BugRecord r = MAPPER.readValue(line, BugRecord.class);
                        if (id.equals(r.getId())) {
                            r.setFixStatus(fixStatus);
                            r.setFixValidation(fixValidation);
                            sb.append(MAPPER.writeValueAsString(r)).append("\n");
                        } else {
                            sb.append(line).append("\n");
                        }
                    } catch (Exception e) {
                        sb.append(line).append("\n"); // preserve malformed lines
                    }
                }
                // Remove trailing newline added above if original didn't end with one
                String result = sb.toString();
                if (!content.endsWith("\n") && result.endsWith("\n")) {
                    result = result.substring(0, result.length() - 1);
                }
                byte[] out = result.getBytes(StandardCharsets.UTF_8);
                channel.truncate(0);
                channel.position(0);
                channel.write(ByteBuffer.wrap(out));
                channel.force(true);
            } catch (IOException e) {
                System.err.println("[Callisto] ERROR: Failed to update fix status: " + e.getMessage());
            }
        }
    }

    /**
     * Checks whether a bug ID is already known (from file or this session).
     *
     * @param id ID in the format "BUG-xxxxxx"
     * @return true if it already exists in the store
     */
    public boolean hasId(String id) {
        return knownIds.contains(id);
    }

    /**
     * Reads all BugRecords from a JSONL file.
     * D-27: returns empty list if file does not exist (no exception thrown).
     * Malformed lines: skip + warn to stderr.
     * Reuses the existing MAPPER.
     *
     * <p><b>CLI-only safety:</b> This method is NOT thread-safe with concurrent
     * {@link #append} or {@link #updateClassification} calls in the same JVM.
     * It is safe to call from the CLI process because the CLI runs in a separate
     * JVM from the agent — no concurrent writes are possible in that scenario.
     *
     * @param bugsFile absolute path to bugs.jsonl
     * @return list of BugRecords (may be empty)
     */
    public static List<BugRecord> readAll(Path bugsFile) {
        if (!Files.exists(bugsFile)) {
            return Collections.emptyList();
        }
        List<BugRecord> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(bugsFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    result.add(MAPPER.readValue(line, BugRecord.class));
                } catch (Exception e) {
                    System.err.println("[Callisto] WARN: Skipping malformed line in bugs.jsonl: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[Callisto] WARN: Failed to read bugs.jsonl: " + e.getMessage());
        }
        return result;
    }
}
