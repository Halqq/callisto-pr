package dev.callisto.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DedupCache — covers AGENT-03 and D-09.
 * WAVE 0: class DedupCache does not exist yet — fails at compile time.
 */
class DedupCacheTest {

    @Test
    void firstOccurrence_notSkipped() {
        DedupCache cache = new DedupCache(60_000L);
        assertFalse(cache.shouldSkip("BUG-aabbcc"), "First occurrence is never skipped");
    }

    @Test
    void sameIdWithin60s_isSkipped() {
        DedupCache cache = new DedupCache(60_000L);
        cache.shouldSkip("BUG-aabbcc"); // registers first occurrence
        assertTrue(cache.shouldSkip("BUG-aabbcc"), "Second occurrence within window must be skipped");
    }

    @Test
    void differentIds_notSkipped() {
        DedupCache cache = new DedupCache(60_000L);
        cache.shouldSkip("BUG-aabbcc");
        assertFalse(cache.shouldSkip("BUG-112233"), "Different ID is never skipped on first occurrence");
    }

    @Test
    void expiredWindow_notSkipped() throws InterruptedException {
        // 50ms window for fast test
        DedupCache cache = new DedupCache(50L);
        cache.shouldSkip("BUG-aabbcc");
        Thread.sleep(100); // wait for window to expire
        assertFalse(cache.shouldSkip("BUG-aabbcc"), "After window expires, must not be skipped");
    }

    @Test
    void getOccurrenceCount_incrementsOnSkip() {
        DedupCache cache = new DedupCache(60_000L);
        cache.shouldSkip("BUG-aabbcc"); // count=1
        cache.shouldSkip("BUG-aabbcc"); // count=2 (skipped)
        cache.shouldSkip("BUG-aabbcc"); // count=3 (skipped)
        assertEquals(3, cache.getOccurrenceCount("BUG-aabbcc"),
            "occurrenceCount must be 3 after 3 calls");
    }
}
