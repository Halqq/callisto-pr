package dev.callisto.agent;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice intercepting Throwable constructor exits.
 *
 * Intercepts ALL Throwable subclass constructions — catches exceptions that target code
 * catches internally, which the UncaughtExceptionHandler would never see.
 *
 * Re-entrancy guard (ThreadLocal): prevents recursive interception when Callisto's own
 * code inside handleException() creates Throwable instances (e.g., StringBuilder, etc.).
 *
 * Safety rules — Advice must NEVER affect target app behavior:
 *   - Entire body wrapped in try/catch(Throwable) that swallows silently.
 *   - Filter out dev.callisto.* classes (recursion prevention).
 *   - Filter out StackOverflowError, OutOfMemoryError, ThreadDeath (JVM-fatal, not actionable).
 *   - No-op if CallistoUncaughtHandler not yet installed (record() handles null instance).
 */
public class ThrowableInterceptor {

    /** Re-entrancy guard — prevents recursive Callisto interception. */
    private static final ThreadLocal<Boolean> IN_CALLISTO = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Called by Byte Buddy after every Throwable constructor completes.
     *
     * @param self the newly-constructed Throwable instance
     */
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onConstructorExit(@Advice.This Throwable self) {
        // Re-entrancy check — must be first, before any allocations
        if (IN_CALLISTO.get()) {
            return;
        }

        try {
            IN_CALLISTO.set(Boolean.TRUE);

            // Filter JVM-fatal errors that are not actionable
            if (self instanceof StackOverflowError
                    || self instanceof OutOfMemoryError
                    || self instanceof ThreadDeath) {
                return;
            }

            // Filter Callisto's own classes to prevent recursion
            String className = self.getClass().getName();
            if (className.startsWith("dev.callisto.")) {
                return;
            }

            // Delegate to handler — no-op if not yet installed
            CallistoUncaughtHandler.record(self);

        } catch (Throwable ignored) {
            // Interceptor MUST NOT affect target app — swallow all errors silently
        } finally {
            IN_CALLISTO.set(Boolean.FALSE);
        }
    }
}
