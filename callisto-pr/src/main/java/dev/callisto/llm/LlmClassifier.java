package dev.callisto.llm;

import dev.callisto.model.BugRecord;

/**
 * Classifies a BugRecord synchronously.
 * Called from a daemon thread — blocking is acceptable.
 * NEVER throws — returns UNCERTAIN on any failure (D-20).
 */
@FunctionalInterface
public interface LlmClassifier {

    ClassificationResult classify(BugRecord record);
}
