package dev.callisto.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BugIdGenerator — covers D-06 and D-07.
 * WAVE 0: class BugIdGenerator does not exist yet — this test fails at compile time.
 */
class BugIdGeneratorTest {

    @Test
    void sameFingerprint_producesSameId() {
        String fp = "java.lang.NullPointerException|com.app.Foo.bar(Foo.java:10)|com.app.Baz.run(Baz.java:5)|";
        String id1 = BugIdGenerator.computeId(fp);
        String id2 = BugIdGenerator.computeId(fp);
        assertEquals(id1, id2, "ID must be deterministic for the same fingerprint");
    }

    @Test
    void id_matchesBugPrefix() {
        String fp = "java.lang.RuntimeException|com.app.A.b(A.java:1)||";
        String id = BugIdGenerator.computeId(fp);
        assertTrue(id.startsWith("BUG-"), "ID must start with 'BUG-'");
    }

    @Test
    void id_hasSixHexCharsAfterPrefix() {
        String fp = "java.io.IOException|com.app.X.y(X.java:2)||";
        String id = BugIdGenerator.computeId(fp);
        // Format: BUG-xxxxxx (6 lowercase hex chars)
        String hexPart = id.substring(4); // remove "BUG-"
        assertEquals(6, hexPart.length(), "Must have 6 hex chars after 'BUG-'");
        assertTrue(hexPart.matches("[0-9a-f]{6}"), "Must be lowercase hex: " + hexPart);
    }

    @Test
    void differentFingerprints_produceDifferentIds() {
        String fp1 = "java.lang.NullPointerException|com.app.Foo.bar(Foo.java:10)||";
        String fp2 = "java.lang.IllegalStateException|com.app.Foo.bar(Foo.java:10)||";
        assertNotEquals(BugIdGenerator.computeId(fp1), BugIdGenerator.computeId(fp2));
    }
}
