package dev.callisto.agent;

/**
 * Computes the fingerprint of a Throwable for deduplication.
 *
 * D-08: fingerprint = exceptionClassName + "|" + frame[0] + "|" + frame[1] + "|" + frame[2]
 * - Missing frames (stack < 3 frames): use available ones
 * - Lambda/inner class normalized: remove everything after the first '$'
 *
 * Pitfall 6 (RESEARCH.md): StackTraceElement.getFileName() can be null —
 * use "Unknown Source" as fallback.
 */
public class Fingerprinter {

    private Fingerprinter() {}

    /**
     * Computes the exception fingerprint for use in dedup and ID generation.
     *
     * @param t captured throwable
     * @return fingerprint string in the format "type|frame0|frame1|frame2|"
     */
    public static String compute(Throwable t) {
        String exceptionType = t.getClass().getName();
        StackTraceElement[] frames = t.getStackTrace();

        StringBuilder fp = new StringBuilder();
        fp.append(exceptionType).append("|");

        // Top-3 frames (or fewer if stack trace is short)
        int limit = Math.min(3, frames.length);
        for (int i = 0; i < limit; i++) {
            fp.append(formatFrame(frames[i])).append("|");
        }
        // Pad with "|" if fewer than 3 frames
        for (int i = limit; i < 3; i++) {
            fp.append("|");
        }

        return fp.toString();
    }

    /**
     * Formats a StackTraceElement as a string.
     * Normalizes lambda/inner class: removes everything after the first '$'.
     * Pitfall 6: getFileName() can be null, getLineNumber() can be -1.
     */
    static String formatFrame(StackTraceElement element) {
        String className = normalizeClassName(element.getClassName());
        String method = element.getMethodName();
        String file = element.getFileName() != null ? element.getFileName() : "Unknown Source";
        int line = Math.max(element.getLineNumber(), 0);
        return className + "." + method + "(" + file + ":" + line + ")";
    }

    /**
     * Removes lambda/inner class suffix from className.
     * D-08: "com.app.Foo$$Lambda$42" -> "com.app.Foo"
     */
    static String normalizeClassName(String className) {
        int dollarIdx = className.indexOf('$');
        return dollarIdx >= 0 ? className.substring(0, dollarIdx) : className;
    }
}
