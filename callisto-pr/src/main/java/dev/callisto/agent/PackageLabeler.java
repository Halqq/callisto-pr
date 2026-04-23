package dev.callisto.agent;

import java.util.List;

/**
 * Classifies classes as INTERNAL or EXTERNAL based on configured prefixes.
 *
 * D-11: projectPackagePrefix as List<String>
 * D-12: matching via startsWith(prefix) for each prefix
 * D-13: no prefixes configured (null or empty) -> returns null (packageSource omitted from JSONL)
 */
public class PackageLabeler {

    private final List<String> prefixes;

    public PackageLabeler(List<String> prefixes) {
        this.prefixes = prefixes;
    }

    /**
     * Classifies a class as INTERNAL, EXTERNAL, or null.
     *
     * @param className fully qualified class name (e.g. "com.myapp.Service")
     * @return "INTERNAL" if any configured prefix is a prefix of className,
     *         "EXTERNAL" if prefixes are configured but none match,
     *         null if prefixes is null or empty (D-13: packageSource omitted)
     */
    public String label(String className) {
        if (prefixes == null || prefixes.isEmpty()) {
            return null; // D-13: omit packageSource
        }
        for (String prefix : prefixes) {
            if (className.startsWith(prefix)) {
                return "INTERNAL";
            }
        }
        return "EXTERNAL";
    }
}
