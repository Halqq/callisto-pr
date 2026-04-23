package dev.callisto.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * fix_validation object persisted in BugRecord after draft-pr execution.
 *
 * D-07: @JsonInclude(NON_NULL) — null fields omitted from JSONL for backward compatibility.
 * - attempts: number of attempts made (1..maxAttempts)
 * - lastTestOutput: truncated output (~4KB) of test stdout/stderr
 * - prUrl: URL of the created PR — populated only if fix_status = VALID
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FixValidation {
    private int attempts;
    private String lastTestOutput;
    private String prUrl;

    public FixValidation() {}

    public FixValidation(int attempts, String lastTestOutput, String prUrl) {
        this.attempts = attempts;
        this.lastTestOutput = lastTestOutput;
        this.prUrl = prUrl;
    }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public String getLastTestOutput() { return lastTestOutput; }
    public void setLastTestOutput(String lastTestOutput) { this.lastTestOutput = lastTestOutput; }

    public String getPrUrl() { return prUrl; }
    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }
}
