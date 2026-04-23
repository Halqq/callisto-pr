package dev.callisto.llm;

import dev.callisto.model.BugRecord;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PromptBuilder.buildPrompt() — D-19, T-03-01-01.
 */
class PromptBuilderTest {

    private static BugRecord makeRecord(String exType, String exMsg, List<String> stack, Map<String, String> pkgSource) {
        BugRecord r = new BugRecord();
        r.setId("BUG-aabbcc");
        r.setExceptionType(exType);
        r.setExceptionMessage(exMsg);
        r.setThreadName("main");
        r.setStackTrace(stack);
        r.setPackageSource(pkgSource);
        return r;
    }

    @Test
    void buildPrompt_containsExceptionType() {
        BugRecord record = makeRecord("java.lang.NullPointerException", "null ref", List.of(), null);
        String prompt = PromptBuilder.buildPrompt(record);

        assertTrue(prompt.contains("java.lang.NullPointerException"),
            "Prompt must include exception type");
    }

    @Test
    void buildPrompt_containsClassificationOptions() {
        BugRecord record = makeRecord("java.io.IOException", "disk full", List.of(), null);
        String prompt = PromptBuilder.buildPrompt(record);

        assertTrue(prompt.contains("INTERNAL"), "Prompt must mention INTERNAL");
        assertTrue(prompt.contains("EXTERNAL"), "Prompt must mention EXTERNAL");
        assertTrue(prompt.contains("UNCERTAIN"), "Prompt must mention UNCERTAIN");
    }

    @Test
    void buildPrompt_sanitizesNewlineInMessage() {
        // T-03-01-01: newlines in exceptionMessage must not break prompt structure
        BugRecord record = makeRecord("java.lang.RuntimeException",
            "line1\nline2\r\nline3", List.of(), null);
        String prompt = PromptBuilder.buildPrompt(record);

        // After sanitization, the message line must not contain bare newlines mid-value
        String msgLine = prompt.lines()
            .filter(l -> l.startsWith("Exception message:"))
            .findFirst()
            .orElse("");
        assertFalse(msgLine.isEmpty(), "Exception message line must exist");
        // The sanitized message appears on a single line
        assertTrue(msgLine.contains("line1") && msgLine.contains("line2") && msgLine.contains("line3"),
            "Sanitized message must preserve content on one line");
    }

    @Test
    void buildPrompt_carriageReturnSanitized() {
        BugRecord record = makeRecord("java.lang.Exception", "msg\r\nevil", List.of(), null);
        String prompt = PromptBuilder.buildPrompt(record);

        // Count "Exception message:" occurrences — should be exactly 1 (no injected lines)
        long count = prompt.lines().filter(l -> l.startsWith("Exception message:")).count();
        assertEquals(1, count, "Newline injection must not split message into multiple lines");
    }

    @Test
    void buildPrompt_nullMessage_doesNotCrash() {
        BugRecord record = makeRecord("java.lang.Error", null, List.of(), null);
        // Must not throw
        String prompt = assertDoesNotThrow(() -> PromptBuilder.buildPrompt(record));
        assertTrue(prompt.contains("java.lang.Error"));
    }

    @Test
    void buildPrompt_includesStackFrames() {
        List<String> frames = Arrays.asList(
            "com.myapp.Service.doWork(Service.java:42)",
            "java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)"
        );
        BugRecord record = makeRecord("java.lang.RuntimeException", "oops", frames, null);
        String prompt = PromptBuilder.buildPrompt(record);

        assertTrue(prompt.contains("com.myapp.Service.doWork(Service.java:42)"),
            "Internal frame must appear in prompt");
        assertTrue(prompt.contains("ThreadPoolExecutor"),
            "External frame must appear in prompt");
    }

    @Test
    void buildPrompt_includesPackageSourceLabels() {
        List<String> frames = List.of("com.myapp.Foo.bar(Foo.java:10)");
        Map<String, String> pkgSource = new LinkedHashMap<>();
        pkgSource.put("com.myapp.Foo", "INTERNAL");
        BugRecord record = makeRecord("java.lang.Exception", "err", frames, pkgSource);
        String prompt = PromptBuilder.buildPrompt(record);

        // The frame line should be annotated with [INTERNAL]
        assertTrue(prompt.contains("[INTERNAL]"),
            "Package label must appear next to frame");
    }

    @Test
    void buildPrompt_packageSourceMap_appearsInPrompt() {
        Map<String, String> pkgSource = new LinkedHashMap<>();
        pkgSource.put("com.myapp.Foo", "INTERNAL");
        pkgSource.put("com.vendor.Bar", "EXTERNAL");
        BugRecord record = makeRecord("java.lang.Exception", "err",
            List.of("com.myapp.Foo.m(Foo.java:1)", "com.vendor.Bar.m(Bar.java:2)"), pkgSource);
        String prompt = PromptBuilder.buildPrompt(record);

        assertTrue(prompt.contains("Package source map"), "Package source map section must exist");
        assertTrue(prompt.contains("com.myapp.Foo: INTERNAL"));
        assertTrue(prompt.contains("com.vendor.Bar: EXTERNAL"));
    }

    @Test
    void buildPrompt_endsWithJsonTemplate() {
        BugRecord record = makeRecord("java.lang.Exception", "e", List.of(), null);
        String prompt = PromptBuilder.buildPrompt(record);

        assertTrue(prompt.trim().endsWith("}"),
            "Prompt must end with JSON template closing brace");
        assertTrue(prompt.contains("\"classification\""),
            "JSON template must include classification field");
        assertTrue(prompt.contains("\"reasoning\""),
            "JSON template must include reasoning field");
    }

    @Test
    void buildPrompt_noPackageSource_noMapSection() {
        BugRecord record = makeRecord("java.lang.Exception", "e", List.of(), null);
        String prompt = PromptBuilder.buildPrompt(record);

        assertFalse(prompt.contains("Package source map"),
            "No package source map section when packageSource is null");
    }
}
