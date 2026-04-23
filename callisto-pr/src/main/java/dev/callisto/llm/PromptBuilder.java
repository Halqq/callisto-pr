package dev.callisto.llm;

import dev.callisto.model.BugRecord;
import java.util.List;
import java.util.Map;

/**
 * Builds the LLM classification prompt from a BugRecord (D-19).
 *
 * T-03-01-01 (Tampering): sanitizes newlines in exceptionMessage to prevent
 * structure injection into the prompt.
 */
public class PromptBuilder {

    private PromptBuilder() {}

    /**
     * Builds the classification prompt for the LLM.
     *
     * @param record the BugRecord to classify
     * @return String with the full prompt
     */
    public static String buildPrompt(BugRecord record) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are a bug classification assistant. A Java application threw an unhandled exception.\n");
        sb.append("Classify it as INTERNAL (the project's own code caused it), EXTERNAL (caused by a dependency, network, or IO), or UNCERTAIN (cannot determine).\n");
        sb.append("\n");
        sb.append("Exception type: ").append(record.getExceptionType()).append("\n");

        // T-03-01-01: sanitize newlines in exception message
        String message = record.getExceptionMessage();
        if (message != null) {
            message = message.replaceAll("[\r\n]+", " ");
        }
        sb.append("Exception message: ").append(message).append("\n");
        sb.append("Thread: ").append(record.getThreadName()).append("\n");
        sb.append("\n");
        sb.append("Stack trace (with package ownership labels):\n");

        List<String> stackTrace = record.getStackTrace();
        Map<String, String> packageSource = record.getPackageSource();

        if (stackTrace != null) {
            for (String frame : stackTrace) {
                if (packageSource != null) {
                    // Extract class name from frame for lookup
                    String label = resolveLabel(frame, packageSource);
                    sb.append("  ").append(frame).append(label != null ? " [" + label + "]" : "").append("\n");
                } else {
                    sb.append("  ").append(frame).append("\n");
                }
            }
        }

        if (packageSource != null && !packageSource.isEmpty()) {
            sb.append("\n");
            sb.append("Package source map:\n");
            for (Map.Entry<String, String> entry : packageSource.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        sb.append("\n");
        sb.append("Respond ONLY with valid JSON (no markdown, no explanation outside JSON):\n");
        sb.append("{\"classification\": \"INTERNAL|EXTERNAL|UNCERTAIN\", \"reasoning\": \"<one sentence>\"}");

        return sb.toString();
    }

    private static String resolveLabel(String frame, Map<String, String> packageSource) {
        // Frame format: "className.method(File.java:line)"
        // Extract the className portion for exact map lookup — avoids false positives
        // where "com.example.Foo" would incorrectly match "com.example.FooBar".
        int parenIdx = frame.indexOf('(');
        if (parenIdx < 0) return null;
        String methodRef = frame.substring(0, parenIdx); // "className.method"
        int lastDot = methodRef.lastIndexOf('.');
        if (lastDot < 0) return null;
        String className = methodRef.substring(0, lastDot);
        return packageSource.get(className);
    }
}
