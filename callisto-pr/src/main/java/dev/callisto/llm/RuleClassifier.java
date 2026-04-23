package dev.callisto.llm;

import dev.callisto.model.BugRecord;
import java.util.Set;

/**
 * Rule-based classifier that pre-classifies well-known network exceptions
 * as {@code EXTERNAL} without invoking the LLM, reducing latency and cost.
 */
public class RuleClassifier {

    // Known network exceptions classified as EXTERNAL without LLM
    private static final Set<String> EXTERNAL_SIMPLE_NAMES = Set.of(
        "SocketTimeoutException",
        "SSLHandshakeException",
        "UnknownHostException",
        "ConnectException"
    );

    /**
     * Attempts to classify the BugRecord based on rules.
     *
     * @param record the BugRecord to classify
     * @return ClassificationResult if a rule applies, null otherwise (delegate to LLM)
     */
    public ClassificationResult tryClassify(BugRecord record) {
        if (record.getExceptionType() == null) {
            return null;
        }
        String simpleName = simpleNameOf(record.getExceptionType());
        if (EXTERNAL_SIMPLE_NAMES.contains(simpleName)) {
            return new ClassificationResult("EXTERNAL", "Known network exception: " + simpleName);
        }
        return null;
    }

    private String simpleNameOf(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }
}
