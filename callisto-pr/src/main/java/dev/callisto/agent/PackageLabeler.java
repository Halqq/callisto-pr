package dev.callisto.agent;

import java.util.List;

/**
 * Classifies class names as {@code INTERNAL} or {@code EXTERNAL} based on
 * configured package prefixes from {@code callisto.json#projectPackagePrefix}.
 *
 * <p>A class is {@code INTERNAL} if any configured prefix is a prefix of its
 * fully qualified name. If no prefixes are configured, {@link #label} returns
 * {@code null} and the {@code packageSource} field is omitted from the JSONL record.
 */
public class PackageLabeler {

    private final List<String> prefixes;

    public PackageLabeler(List<String> prefixes) {
        this.prefixes = prefixes;
    }

    /**
     * Classifies a fully qualified class name.
     *
     * @param className fully qualified class name (e.g. {@code "com.myapp.Service"})
     * @return {@code "INTERNAL"} if any prefix matches, {@code "EXTERNAL"} if prefixes are
     *         configured but none match, or {@code null} if no prefixes are configured
     */
    public String label(String className) {
        if (prefixes == null || prefixes.isEmpty()) {
            return null; // no prefixes — omit packageSource from record
        }
        for (String prefix : prefixes) {
            if (className.startsWith(prefix)) {
                return "INTERNAL";
            }
        }
        return "EXTERNAL";
    }
}
