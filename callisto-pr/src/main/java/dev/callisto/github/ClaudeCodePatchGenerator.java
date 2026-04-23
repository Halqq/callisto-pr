package dev.callisto.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.callisto.model.BugRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Invokes Claude Code CLI as a subprocess to generate a code patch and PR body.
 *
 * D-30: `claude --print "<prompt>" --output-format json` subprocess.
 * D-32 Pitfall 2: pb.start() throws IOException if claude not in PATH — catch separately.
 * T-5-02: ProcessBuilder uses List<String> args — no shell injection via bug ID or exception message.
 */
public class ClaudeCodePatchGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Generates a ClaudeOutput by invoking Claude Code CLI in the given project directory.
     *
     * @param bug        Bug record to generate fix for
     * @param projectDir CWD for the claude subprocess (D-33)
     * @return ClaudeOutput with filePath + fileContent (if FILE: marker present) and prBody
     * @throws IOException          if claude is not in PATH or subprocess I/O fails
     * @throws InterruptedException if subprocess is interrupted
     */
    public ClaudeOutput generate(BugRecord bug, Path projectDir) throws IOException, InterruptedException {
        String prompt = buildPrompt(bug);

        // T-5-02: List<String> args — never concatenate into a shell string
        ProcessBuilder pb = new ProcessBuilder(
            "claude", "--print", prompt, "--output-format", "json"
        );
        pb.directory(projectDir.toFile());
        // CR-01: redirectErrorStream(true) merges stderr into stdout before readAllBytes(),
        // preventing deadlock when Claude output exceeds the OS pipe buffer (64KB on Linux).
        pb.redirectErrorStream(true);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            // Pitfall 2: binary not in PATH — distinct from non-zero exit
            throw new IOException(
                "claude not found in PATH — install Claude Code: https://docs.anthropic.com/claude-code", e
            );
        }

        String stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = proc.waitFor();

        if (exit != 0) {
            throw new IOException("claude exited " + exit + ": " + stdout.trim());
        }

        return parseStdout(stdout);
    }

    /** Package-private for unit testing without subprocess. */
    ClaudeOutput parseStdout(String jsonStdout) throws IOException {
        JsonNode root = MAPPER.readTree(jsonStdout);
        if (root.path("is_error").asBoolean(false)) {
            throw new IOException(
                "Claude Code reported error: " + root.path("result").asText("(no detail)")
            );
        }
        String resultText = root.path("result").asText("");
        String sessionId = root.path("session_id").asText(null);
        if (sessionId != null && sessionId.isEmpty()) sessionId = null;
        ClaudeOutput parsed = parseResult(resultText);
        return new ClaudeOutput(parsed.getFilePath(), parsed.getFileContent(), parsed.getPrBody(), sessionId);
    }

    /**
     * Parses the structured result text from Claude Code.
     *
     * Expected format (when file patch present):
     *   FILE: relative/path/to/File.java
     *   ===CONTENT===
     *   &lt;full new file content&gt;
     *   ===END===
     *   PR_BODY:
     *   &lt;markdown PR body&gt;
     *   ===END_PR_BODY===
     *
     * If FILE:/===CONTENT=== markers absent → filePath=null, fileContent=null, prBody=full resultText.
     */
    ClaudeOutput parseResult(String resultText) {
        String filePath = null;
        String fileContent = null;
        String prBody = resultText; // default: use full text as PR body

        int fileMarker = resultText.indexOf("FILE:");
        int contentStart = resultText.indexOf("===CONTENT===");
        int contentEnd = resultText.indexOf("===END===");
        int prBodyStart = resultText.indexOf("PR_BODY:");
        int prBodyEnd = resultText.indexOf("===END_PR_BODY===");

        if (fileMarker >= 0 && contentStart > fileMarker && contentEnd > contentStart) {
            // Extract file path: text between "FILE:" and "===CONTENT==="
            filePath = resultText.substring(fileMarker + "FILE:".length(), contentStart).trim();

            // Extract file content: text between "===CONTENT===" and "===END==="
            fileContent = resultText.substring(contentStart + "===CONTENT===".length(), contentEnd).trim();
        }

        if (prBodyStart >= 0) {
            int bodyTextStart = prBodyStart + "PR_BODY:".length();
            if (prBodyEnd > bodyTextStart) {
                prBody = resultText.substring(bodyTextStart, prBodyEnd).trim();
            } else {
                prBody = resultText.substring(bodyTextStart).trim();
            }
        }

        return new ClaudeOutput(filePath, fileContent, prBody);
    }

    /**
     * Retry via --resume: reuses context from the previous Claude session.
     * D-04: sends only truncated test output — Claude already has the project context loaded.
     * T-5-02: ProcessBuilder(List<String>) — no shell string concatenation.
     * Pitfall 4: session_id absent → IllegalArgumentException — caller decides fallback.
     *
     * @param sessionId  session_id captured from the previous call to generate()
     * @param testOutput test output truncated to ~4KB
     * @return new ClaudeOutput with corrected patch
     */
    public ClaudeOutput resume(String sessionId, String testOutput)
            throws IOException, InterruptedException {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException(
                "[Callisto] session_id unavailable — cannot retry via --resume. " +
                "Update Claude Code CLI or check --output-format json support."
            );
        }
        // T-5-02: List<String> — no shell string concatenation
        ProcessBuilder pb = new ProcessBuilder(
            "claude", "--resume", sessionId, "-p", testOutput
        );
        // CR-01: redirectErrorStream(true) merges stderr into stdout before readAllBytes(),
        // preventing deadlock when output exceeds the OS pipe buffer.
        pb.redirectErrorStream(true);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException(
                "claude not found in PATH — install Claude Code: https://docs.anthropic.com/claude-code", e
            );
        }

        String stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = proc.waitFor();

        if (exit != 0) {
            throw new IOException("claude --resume exited " + exit + ": " + stdout.trim());
        }

        return parseStdout(stdout);
    }

    private String buildPrompt(BugRecord bug) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are analyzing a bug captured by the Callisto Java agent.\n\n");
        sb.append("Bug ID: ").append(bug.getId()).append("\n");
        sb.append("Exception: ").append(bug.getExceptionType());
        if (bug.getExceptionMessage() != null && !bug.getExceptionMessage().isEmpty()) {
            sb.append(": ").append(bug.getExceptionMessage());
        }
        sb.append("\nClassification: ").append(bug.getClassification()).append("\n");
        if (bug.getReasoning() != null && !bug.getReasoning().isEmpty()) {
            sb.append("Reasoning: ").append(bug.getReasoning()).append("\n");
        }
        sb.append("Stack Trace:\n");
        List<String> frames = bug.getStackTrace();
        if (frames != null) {
            for (String frame : frames) {
                sb.append("  ").append(frame).append("\n");
            }
        }
        sb.append("\nExplore this project, find the root cause of this exception in the source code, ");
        sb.append("and produce a fix. Do NOT output a diff. Output the full new file content.\n\n");
        sb.append("Your response MUST be structured as:\n\n");
        sb.append("FILE: <relative/path/to/File.java>\n");
        sb.append("===CONTENT===\n");
        sb.append("<full new content of the file — NOT a diff>\n");
        sb.append("===END===\n\n");
        sb.append("PR_BODY:\n");
        sb.append("<markdown PR body with: bug description, classification result (")
          .append(bug.getClassification())
          .append("), reproduction context, and fix explanation>\n");
        sb.append("===END_PR_BODY===\n");
        return sb.toString();
    }
}
