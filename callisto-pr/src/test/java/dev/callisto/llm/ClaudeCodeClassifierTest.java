package dev.callisto.llm;

import dev.callisto.model.BugRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClaudeCodeClassifier — targets parsing methods directly (no subprocess needed).
 * For classify() end-to-end, uses a subclass that overrides buildAndInvoke() to inject fake results.
 */
class ClaudeCodeClassifierTest {

    private final ClaudeCodeClassifier classifier = new ClaudeCodeClassifier();

    // --- parseStdout tests ---

    @Test
    void parseStdout_validJson_returnsClassification() throws IOException {
        String stdout = "{\"is_error\":false,\"result\":\"{\\\"classification\\\":\\\"INTERNAL\\\",\\\"reasoning\\\":\\\"Project code threw the exception\\\"}\"}";
        ClassificationResult result = classifier.parseStdout(stdout);
        assertEquals("INTERNAL", result.classification);
        assertEquals("Project code threw the exception", result.reasoning);
    }

    @Test
    void parseStdout_isError_returnsUncertain() throws IOException {
        String stdout = "{\"is_error\":true,\"result\":\"Something went wrong\"}";
        ClassificationResult result = classifier.parseStdout(stdout);
        assertEquals("UNCERTAIN", result.classification);
        assertTrue(result.reasoning.contains("Something went wrong"));
    }

    @Test
    void parseStdout_invalidClassification_coercesToUncertain() throws IOException {
        String stdout = "{\"is_error\":false,\"result\":\"{\\\"classification\\\":\\\"BOGUS\\\",\\\"reasoning\\\":\\\"unknown\\\"}\"}";
        ClassificationResult result = classifier.parseStdout(stdout);
        assertEquals("UNCERTAIN", result.classification);
    }

    @Test
    void parseStdout_markdownFenced_stripsAndParses() throws IOException {
        String inner = "```json\\n{\\\"classification\\\":\\\"EXTERNAL\\\",\\\"reasoning\\\":\\\"Network timeout\\\"}\\n```";
        String stdout = "{\"is_error\":false,\"result\":\"" + inner + "\"}";
        ClassificationResult result = classifier.parseStdout(stdout);
        assertEquals("EXTERNAL", result.classification);
        assertEquals("Network timeout", result.reasoning);
    }

    // --- parseClassificationJson tests ---

    @Test
    void parseClassificationJson_internal_returnsInternal() {
        String text = "{\"classification\":\"INTERNAL\",\"reasoning\":\"Bug in app code\"}";
        ClassificationResult result = ClaudeCodeClassifier.parseClassificationJson(text);
        assertEquals("INTERNAL", result.classification);
        assertEquals("Bug in app code", result.reasoning);
    }

    @Test
    void parseClassificationJson_external_returnsExternal() {
        String text = "{\"classification\":\"EXTERNAL\",\"reasoning\":\"DB connection failed\"}";
        ClassificationResult result = ClaudeCodeClassifier.parseClassificationJson(text);
        assertEquals("EXTERNAL", result.classification);
    }

    @Test
    void parseClassificationJson_malformedJson_returnsUncertain() {
        ClassificationResult result = ClaudeCodeClassifier.parseClassificationJson("not json at all");
        assertEquals("UNCERTAIN", result.classification);
        assertEquals("Failed to parse LLM response", result.reasoning);
    }

    @Test
    void parseClassificationJson_markdownFenced_stripsAndParses() {
        String text = "```json\n{\"classification\":\"INTERNAL\",\"reasoning\":\"App bug\"}\n```";
        ClassificationResult result = ClaudeCodeClassifier.parseClassificationJson(text);
        assertEquals("INTERNAL", result.classification);
    }

    @Test
    void parseClassificationJson_unknownValue_coercesToUncertain() {
        String text = "{\"classification\":\"MAYBE\",\"reasoning\":\"Not sure\"}";
        ClassificationResult result = ClaudeCodeClassifier.parseClassificationJson(text);
        assertEquals("UNCERTAIN", result.classification);
    }

    // --- classify() end-to-end with injected subprocess result ---

    @Test
    void classify_withMockedProcess_returnsResult() {
        String fakeStdout = "{\"is_error\":false,\"result\":\"{\\\"classification\\\":\\\"EXTERNAL\\\",\\\"reasoning\\\":\\\"Network issue\\\"}\"}";

        ClaudeCodeClassifier testClassifier = new ClaudeCodeClassifier() {
            @Override
            ClassificationResult buildAndInvoke(BugRecord record) throws IOException {
                return parseStdout(fakeStdout);
            }
        };

        BugRecord record = new BugRecord();
        record.setId("test-id");
        record.setExceptionType("java.net.SocketTimeoutException");
        record.setExceptionMessage("Connection timed out");
        record.setThreadName("main");
        record.setStackTrace(List.of("com.example.App.main(App.java:10)"));

        ClassificationResult result = testClassifier.classify(record);
        assertEquals("EXTERNAL", result.classification);
        assertEquals("Network issue", result.reasoning);
    }

    @Test
    void classify_processThrowsIOException_returnsUncertain() {
        ClaudeCodeClassifier testClassifier = new ClaudeCodeClassifier() {
            @Override
            ClassificationResult buildAndInvoke(BugRecord record) throws IOException {
                throw new IOException("claude not found in PATH");
            }
        };

        BugRecord record = new BugRecord();
        record.setId("test-id");
        record.setExceptionType("java.lang.NullPointerException");
        record.setExceptionMessage(null);
        record.setThreadName("main");
        record.setStackTrace(List.of());

        ClassificationResult result = testClassifier.classify(record);
        assertEquals("UNCERTAIN", result.classification);
        assertTrue(result.reasoning.contains("claude not found"));
    }
}
