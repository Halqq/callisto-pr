package dev.callisto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * JSONL record of a bug captured by the agent.
 *
 * Schema (D-14, D-15, D-16):
 * - id: "BUG-xxxxxx" (6 hex chars, SHA-256 of the fingerprint)
 * - timestamp: ISO-8601 UTC with milliseconds (Instant.now().toString())
 * - occurrenceCount: int, incremented in the in-memory cache
 * - exceptionType: fully qualified class name (e.g. java.lang.NullPointerException)
 * - exceptionMessage: getMessage() — may be null
 * - threadName: Thread.currentThread().getName()
 * - stackTrace: array of strings "className.method(File.java:line)"
 * - causeChain: array of CauseRecord (non-recursive, max 10 levels)
 * - packageSource: Map<String, String> className → "INTERNAL"|"EXTERNAL"
 *   OMITTED from JSON if null (D-13: prefix not configured)
 *
 * Java 11: POJO with getters/setters (no record).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BugRecord {
    private String id;
    private String timestamp;
    private int occurrenceCount;
    private String exceptionType;
    private String exceptionMessage;
    private String threadName;
    private List<String> stackTrace;
    private List<CauseRecord> causeChain;

    // D-13: null when projectPackagePrefix is not configured — omitted from JSON
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, String> packageSource;

    // D-17: null until async classification completes — omitted from JSONL by @JsonInclude(NON_NULL) on the class
    private String classification;

    private String reasoning;

    // D-07: null while draft-pr has not been run — omitted from JSONL by @JsonInclude(NON_NULL)
    private String fixStatus;   // "PENDING" | "VALID" | "INVALID"
    private FixValidation fixValidation;

    public BugRecord() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public int getOccurrenceCount() { return occurrenceCount; }
    public void setOccurrenceCount(int occurrenceCount) { this.occurrenceCount = occurrenceCount; }

    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }

    public String getExceptionMessage() { return exceptionMessage; }
    public void setExceptionMessage(String exceptionMessage) { this.exceptionMessage = exceptionMessage; }

    public String getThreadName() { return threadName; }
    public void setThreadName(String threadName) { this.threadName = threadName; }

    public List<String> getStackTrace() { return stackTrace; }
    public void setStackTrace(List<String> stackTrace) { this.stackTrace = stackTrace; }

    public List<CauseRecord> getCauseChain() { return causeChain; }
    public void setCauseChain(List<CauseRecord> causeChain) { this.causeChain = causeChain; }

    public Map<String, String> getPackageSource() { return packageSource; }
    public void setPackageSource(Map<String, String> packageSource) { this.packageSource = packageSource; }

    public String getClassification() { return classification; }
    public void setClassification(String classification) { this.classification = classification; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public String getFixStatus() { return fixStatus; }
    public void setFixStatus(String fixStatus) { this.fixStatus = fixStatus; }

    public FixValidation getFixValidation() { return fixValidation; }
    public void setFixValidation(FixValidation fixValidation) { this.fixValidation = fixValidation; }
}
