package dev.callisto.model;

import java.util.List;

/**
 * An element of the causeChain of a BugRecord.
 * Non-recursive — depth limited to 10 in the handler (see Fingerprinter).
 * Java 11: use POJO (not record — requires Java 16+).
 */
public class CauseRecord {
    private String exceptionType;
    private String exceptionMessage;
    private List<String> stackTrace;

    public CauseRecord() {}

    public CauseRecord(String exceptionType, String exceptionMessage, List<String> stackTrace) {
        this.exceptionType = exceptionType;
        this.exceptionMessage = exceptionMessage;
        this.stackTrace = stackTrace;
    }

    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }

    public String getExceptionMessage() { return exceptionMessage; }
    public void setExceptionMessage(String exceptionMessage) { this.exceptionMessage = exceptionMessage; }

    public List<String> getStackTrace() { return stackTrace; }
    public void setStackTrace(List<String> stackTrace) { this.stackTrace = stackTrace; }
}
