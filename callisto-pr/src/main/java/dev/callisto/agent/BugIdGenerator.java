package dev.callisto.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Generates deterministic IDs for bugs from the fingerprint.
 *
 * D-06: Format "BUG-" + 6 lowercase hex chars
 * D-07: ID = first 6 chars of SHA-256 of the fingerprint
 * CRITICAL: MessageDigest is NOT thread-safe — create a new instance per call.
 */
public class BugIdGenerator {

    private BugIdGenerator() {}

    /**
     * Computes the bug ID from the fingerprint.
     *
     * @param fingerprint fingerprint string (D-08: type|frame0|frame1|frame2)
     * @return ID in the format "BUG-xxxxxx" (6 lowercase hex chars)
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
