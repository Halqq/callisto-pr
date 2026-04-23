package dev.callisto.agent;

/**
 * Computes a stable fingerprint for a {@link Throwable} used for deduplication.
 *
 * <p>Fingerprint format: {@code exceptionClassName|frame0|frame1|frame2|}
 * using the top three stack frames (fewer if the stack is shorter).
 * Lambda and inner-class suffixes are stripped (everything after the first {@code $})
 * to keep fingerprints stable across JVM restarts.
 *
 * <p>{@link StackTraceElement#getFileName()} may be {@code null} — {@code "Unknown Source"}
 * is used as a fallback.
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
     * Formats a {@link StackTraceElement} as {@code "className.method(File.java:line)"}.
     * Normalizes lambda/inner-class names by stripping everything after the first {@code $}.
     * Handles {@code null} file names and negative line numbers safely.
     */
    static String formatFrame(StackTraceElement element) {
        String className = normalizeClassName(element.getClassName());
        String method = element.getMethodName();
        String file = element.getFileName() != null ? element.getFileName() : "Unknown Source";
        int line = Math.max(element.getLineNumber(), 0);
        return className + "." + method + "(" + file + ":" + line + ")";
    }

    /**
     * Strips lambda/inner-class suffixes from a fully qualified class name.
     * Example: {@code "com.app.Foo$$Lambda$42"} → {@code "com.app.Foo"}.
     */
    static String normalizeClassName(String className) {
        int dollarIdx = className.indexOf('$');
        return dollarIdx >= 0 ? className.substring(0, dollarIdx) : className;
    }
}
