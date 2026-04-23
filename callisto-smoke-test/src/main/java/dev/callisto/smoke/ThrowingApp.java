package dev.callisto.smoke;

/**
 * Target app that throws an unhandled NullPointerException.
 * Used by ExceptionCaptureIT to verify that the agent captures the exception.
 * Exit code will be != 0 (JVM terminates with unhandled exception).
 */
public class ThrowingApp {
    public static void main(String[] args) {
        throw new NullPointerException("smoke test intentional NPE");
    }
}
