package dev.callisto.agent;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory deduplication cache with a configurable TTL window.
 *
 * <p>Prevents the same exception fingerprint from being persisted more than once
 * within the TTL window (default: 60 seconds). Occurrence counts are tracked
 * so callers can report how many times a bug was suppressed.
 *
 * <p>Thread-safe: backed by {@link java.util.concurrent.ConcurrentHashMap} with
 * atomic operations. Expired entries are evicted on access to bound memory usage.
 */
public class DedupCache {

    private static final int MAX_SIZE = 10_000;

    private final long windowMs;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private static final class CacheEntry {
        final AtomicInteger count;
        volatile long lastSeenMs;

        CacheEntry(long nowMs) {
            this.count = new AtomicInteger(1);
            this.lastSeenMs = nowMs;
        }
    }

    public DedupCache(long windowMs) {
        this.windowMs = windowMs;
    }

    /**
     * Checks whether this ID should be skipped (already seen within the TTL window).
     * Side effect: records the occurrence (increments count, updates lastSeenMs).
     *
     * @param fingerprintId bug ID (e.g. "BUG-3f9a2c")
     * @return true if it should be skipped (dedup), false if it is a new occurrence
     */
    public boolean shouldSkip(String fingerprintId) {
        long now = System.currentTimeMillis();

        // On-access cleanup of expired entries (prevents unbounded growth)
        evictExpired(now);

        CacheEntry existing = cache.get(fingerprintId);
        if (existing != null && (now - existing.lastSeenMs) < windowMs) {
            // Within the window — increment and skip
            existing.count.incrementAndGet();
            existing.lastSeenMs = now;
            return true;
        }

        // New occurrence or window expired — record
        cache.put(fingerprintId, new CacheEntry(now));
        if (cache.size() > MAX_SIZE) {
            evictOldestOrAny();
        }
        return false;
    }

    private void evictOldestOrAny() {
        long now = System.currentTimeMillis();
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            if ((now - entry.getValue().lastSeenMs) >= windowMs) {
                cache.remove(entry.getKey());
                return; // evicted an expired one
            }
            if (entry.getValue().lastSeenMs < oldestTime) {
                oldestTime = entry.getValue().lastSeenMs;
                oldestKey = entry.getKey();
            }
        }
        // No expired entry found — evict oldest
        if (oldestKey != null) {
            cache.remove(oldestKey);
        }
    }

    /**
     * Returns the occurrence count for the ID (including the first one).
     * Returns 0 if the ID is unknown.
     */
    public int getOccurrenceCount(String fingerprintId) {
        CacheEntry entry = cache.get(fingerprintId);
        return entry != null ? entry.count.get() : 0;
    }

    private void evictExpired(long now) {
        Iterator<Map.Entry<String, CacheEntry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CacheEntry> entry = it.next();
            if ((now - entry.getValue().lastSeenMs) >= windowMs) {
                it.remove();
            }
        }
    }
}
