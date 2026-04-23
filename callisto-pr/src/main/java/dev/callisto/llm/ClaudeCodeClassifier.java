package dev.callisto.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.callisto.model.BugRecord;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Classifica bugs via Claude Code CLI subprocess (`claude --print`).
 *
 * Mirrors ClaudeCodePatchGenerator pattern: ProcessBuilder List-form args (T-qmmd-01 — no shell injection).
 * Stderr discarded (T-qmmd-02). Never throws — returns UNCERTAIN on any failure (D-20).
 *
 * Usage: new ClaudeCodeClassifier().classify(record)
 * No API key, no HTTP, no provider config needed.
 */
public class ClaudeCodeClassifier implements LlmClassifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ClassificationResult classify(BugRecord record) {
        try {
            return buildAndInvoke(record);
        } catch (IOException e) {
            return new ClassificationResult("UNCERTAIN", "claude not found in PATH or I/O error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ClassificationResult("UNCERTAIN", "Classification interrupted");
        } catch (Exception e) {
            return new ClassificationResult("UNCERTAIN", "Classification failed: " + e.getMessage());
        }
    }

    /**
     * Builds the prompt, spawns the claude subprocess, and parses the result.
     * Package-private to allow test subclasses to override without subprocess.
     *
     * @throws IOException          if claude is not in PATH or subprocess I/O fails
     * @throws InterruptedException if subprocess wait is interrupted
     */
    ClassificationResult buildAndInvoke(BugRecord record) throws IOException, InterruptedException {
        String prompt = PromptBuilder.buildPrompt(record);

        // T-qmmd-01: List<String> args — never concatenate into a shell string
        // T-quick-02: prompt passed via stdin, not CLI arg — eliminates injection vector
        ProcessBuilder pb = new ProcessBuilder("claude", "--print", "--output-format", "json");
        pb.redirectErrorStream(false);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("claude not found in PATH — install Claude Code: https://docs.anthropic.com/claude-code", e);
        }

        // Write prompt to stdin before reading stdout — must happen before waitFor()
        try (OutputStream stdin = proc.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }

        // T-qmmd-02: discard stderr — no secrets, already sanitized by PromptBuilder
        proc.getErrorStream().transferTo(OutputStream.nullOutputStream());

        // Wait up to 30s before reading stdout — avoids readAllBytes() blocking indefinitely
        // when the process produces no output but has not terminated.
        boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            return new ClassificationResult("UNCERTAIN", "claude process timed out after 30s");
        }
        int exit = proc.exitValue();
        String stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        if (exit != 0) {
            return new ClassificationResult("UNCERTAIN", "claude exited " + exit);
        }

        return parseStdout(stdout);
    }

    /**
     * Parses the JSON envelope output from `claude --output-format json`.
     * Package-private for unit testing without subprocess.
     *
     * Expected: {"is_error": false, "result": "<classification JSON text>"}
     */
    ClassificationResult parseStdout(String jsonStdout) throws IOException {
        JsonNode root = MAPPER.readTree(jsonStdout);
        if (root.path("is_error").asBoolean(false)) {
            String errorDetail = root.path("result").asText("(no detail)");
            return new ClassificationResult("UNCERTAIN", "Claude Code reported error: " + errorDetail);
        }
        String resultText = root.path("result").asText("");
        return parseClassificationJson(resultText);
    }

    /**
     * Parses the classification JSON from Claude's text output.
     * Strips markdown fences, validates enum values.
     * Static package-private — testable directly.
     *
     * Expected: {"classification": "INTERNAL|EXTERNAL|UNCERTAIN", "reasoning": "<one sentence>"}
     * On any parse failure: returns UNCERTAIN.
     */
    static ClassificationResult parseClassificationJson(String text) {
        try {
            // Strip markdown fences (same pattern as AnthropicClassifier)
            String cleaned = text
                    .replaceAll("(?s)^```[a-z]*\\s*", "")
                    .replaceAll("```\\s*$", "")
                    .trim();

            JsonNode node = MAPPER.readTree(cleaned);
            String classification = node.path("classification").asText("UNCERTAIN");
            String reasoning = node.path("reasoning").asText("");

            // Validate against known enum values — coerce unknown to UNCERTAIN
            if (!classification.equals("INTERNAL")
                    && !classification.equals("EXTERNAL")
                    && !classification.equals("UNCERTAIN")) {
                classification = "UNCERTAIN";
            }

            return new ClassificationResult(classification, reasoning);
        } catch (Exception e) {
            return new ClassificationResult("UNCERTAIN", "Failed to parse LLM response");
        }
    }
}
