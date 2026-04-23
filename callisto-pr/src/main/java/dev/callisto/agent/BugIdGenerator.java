package dev.callisto.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Generates deterministic, stable bug IDs from a fingerprint string.
 *
 * <p>ID format: {@code "BUG-"} followed by 6 lowercase hex characters,
 * derived from the first 6 bytes of the SHA-256 hash of the fingerprint.
 *
 * <p>{@link java.security.MessageDigest} is not thread-safe — a new instance
 * is created per call.
 */
public class BugIdGenerator {

    private BugIdGenerator() {}

    /**
     * Computes the bug ID from the fingerprint.
     *
     * @param fingerprint fingerprint string in {@code "type|frame0|frame1|frame2|"} format
     * @return ID in the format {@code "BUG-xxxxxx"} (6 lowercase hex chars)
     */
    public static String computeId(String fingerprint) {
        try {
            // CRITICAL: new instance per call — do not share between threads
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return "BUG-" + hex.substring(0, 6);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by all compatible JDKs — should never occur
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
