package dev.callisto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * JSONL record for a bug captured by the agent.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id} — {@code "BUG-xxxxxx"} (6 hex chars, SHA-256 of the fingerprint)</li>
 *   <li>{@code timestamp} — ISO-8601 UTC string</li>
 *   <li>{@code occurrenceCount} — number of times seen within the dedup window</li>
 *   <li>{@code exceptionType} — fully qualified class name</li>
 *   <li>{@code exceptionMessage} — {@link Throwable#getMessage()}, may be {@code null}</li>
 *   <li>{@code threadName} — name of the thread that threw the exception</li>
 *   <li>{@code stackTrace} — frames as {@code "className.method(File.java:line)"} strings</li>
 *   <li>{@code causeChain} — list of {@link CauseRecord}, max 10 levels</li>
 *   <li>{@code packageSource} — {@code className → "INTERNAL"|"EXTERNAL"} map;
 *       omitted from JSON when {@code null} (no package prefix configured)</li>
 *   <li>{@code classification} / {@code reasoning} — set asynchronously after LLM classification</li>
 *   <li>{@code fixStatus} / {@code fixValidation} — set by {@code draft-pr} command</li>
 * </ul>
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

    // null when projectPackagePrefix is not configured — omitted from JSON
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, String> packageSource;

    // null until async classification completes — omitted from JSONL by @JsonInclude(NON_NULL) on the class
    private String classification;

    private String reasoning;

    // null until draft-pr is run — omitted from JSONL by @JsonInclude(NON_NULL)
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
