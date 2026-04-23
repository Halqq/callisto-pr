package dev.callisto.github;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

class ClaudeCodePatchGeneratorTest {

    private final ClaudeCodePatchGenerator gen = new ClaudeCodePatchGenerator();

    @Test
    void testJsonParsing_happyPath_returnsClaudeOutput() throws Exception {
        String json = "{\n" +
            "  \"type\": \"result\",\n" +
            "  \"subtype\": \"success\",\n" +
            "  \"is_error\": false,\n" +
            "  \"result\": \"FILE: src/main/java/com/example/Foo.java\\n===CONTENT===\\npublic class Foo {}\\n===END===\\nPR_BODY:\\n## Fix\\nFixed the bug.\\n===END_PR_BODY===\",\n" +
            "  \"stop_reason\": \"end_turn\",\n" +
            "  \"total_cost_usd\": 0.001\n" +
            "}";
        ClaudeOutput out = gen.parseStdout(json);
        assertEquals("src/main/java/com/example/Foo.java", out.getFilePath());
        assertEquals("public class Foo {}", out.getFileContent());
        assertEquals("## Fix\nFixed the bug.", out.getPrBody());
    }

    @Test
    void testJsonParsing_isErrorTrue_throwsClaudeCodeException() {
        String json = "{\n" +
            "  \"type\": \"result\",\n" +
            "  \"is_error\": true,\n" +
            "  \"result\": \"Error: tool call failed\"\n" +
            "}";
        IOException ex = assertThrows(IOException.class, () -> gen.parseStdout(json));
        assertTrue(ex.getMessage().contains("Claude Code reported error"),
            "Expected 'Claude Code reported error' in: " + ex.getMessage());
    }

    @Test
    void testPrBodyExtraction_noFileMarkers_prBodyIsFallback() throws Exception {
        String json = "{\"is_error\": false, \"result\": \"Some plain text response\"}";
        ClaudeOutput out = gen.parseStdout(json);
        assertNull(out.getFilePath());
        assertNull(out.getFileContent());
        assertEquals("Some plain text response", out.getPrBody());
    }
}
