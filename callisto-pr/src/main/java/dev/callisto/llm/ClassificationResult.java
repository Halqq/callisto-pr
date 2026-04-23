package dev.callisto.llm;

/**
 * Immutable result of a bug classification.
 *
 * classification: "INTERNAL", "EXTERNAL", or "UNCERTAIN"
 * reasoning: one sentence explaining the decision
 */
public class ClassificationResult {

    public final String classification;
    public final String reasoning;

    public ClassificationResult(String classification, String reasoning) {
        this.classification = classification;
        this.reasoning = reasoning;
    }
}
