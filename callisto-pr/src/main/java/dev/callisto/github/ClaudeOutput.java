package dev.callisto.github;

/** Output parsed from Claude Code --output-format json result field. */
public class ClaudeOutput {
    private final String filePath;    // may be null if no FILE: marker
    private final String fileContent; // may be null if no CONTENT: marker
    private final String prBody;      // always present
    private final String sessionId;   // may be null/empty if CLI version doesn't support it

    /** Retrocompat constructor — sessionId defaults to null. */
    public ClaudeOutput(String filePath, String fileContent, String prBody) {
        this(filePath, fileContent, prBody, null);
    }

    public ClaudeOutput(String filePath, String fileContent, String prBody, String sessionId) {
        this.filePath = filePath;
        this.fileContent = fileContent;
        this.prBody = prBody;
        this.sessionId = sessionId;
    }

    public String getFilePath() { return filePath; }
    public String getFileContent() { return fileContent; }
    public String getPrBody() { return prBody; }

    /** Session ID captured from claude --output-format json. May be null/empty. */
    public String getSessionId() { return sessionId; }
}
