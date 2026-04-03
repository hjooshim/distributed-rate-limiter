package com.drl.ratelimiter.strategy;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================
 * STRATEGY PATTERN — Concrete Implementation #1
 * Fixed Window Algorithm (Local / In-Memory)
 * ============================================================
 *
 * HOW IT WORKS:
 *   Time is divided into fixed windows (e.g., each 60-second slot).
 *   Each window has a counter. When a request arrives:
 *     1. Identify which window it falls in (based on current time)
 *     2. If counter < limit → allow, increment counter
 *     3. If counter >= limit → reject
 *
 *   Example with limit=3, window=60s:
 *     [00:00–01:00]  req1 ✅ (count=1)
 *     [00:00–01:00]  req2 ✅ (count=2)
 *     [00:00–01:00]  req3 ✅ (count=3)
 *     [00:00–01:00]  req4 ❌ (count=3, at limit)
 *     [01:00–02:00]  req5 ✅ (new window, count resets to 1)
 *
 * CONCURRENCY DESIGN:
 *   Problem: Multiple threads hitting the same counter simultaneously
 *   could cause a race condition (two threads both read count=2,
 *   both increment to 3, so we allow 4 requests instead of 3).
 *
 *   Solution: WindowCounter wraps a single AtomicLong counter.
 *   AtomicLong.incrementAndGet() is a hardware-level CAS (Compare-And-Swap)
 *   operation — it's atomic without needing a synchronized block.
 *   ### long + synchronized block: thread 1: im progress, thread 2: waiting, thread 3: waiting, will block
 *   ### CAS: thread1: try - success
 *            thread2: try - retry
 *            thread3: try - retry    cause rating
 *   it is a lock-free concurrency control technique. Multiple threads attempt to update a shared variable simultaneously, and threads that fail will keep retrying through spinning until they succeed.
 *
 *   ConcurrentHashMap: thread-safe map for concurrent reads/writes.
 *   computeIfAbsent: atomically creates a new counter if key doesn't exist.
 *
 * KNOWN LIMITATION (Fixed Window):
 *   Boundary burst problem — a client can send 2× the limit by making
 *   requests at the end of one window and the start of the next.
 *   This is acceptable for Week 1. Week 2 fixes this with Sliding Window.
 */
@Component
public class LocalFixedWindowStrategy implements RateLimitStrategy {

    // Key = "userId:method:windowId"
    // Value = WindowCounter (holds count + window start time)
    private final ConcurrentHashMap<String, WindowCounter> counters
            = new ConcurrentHashMap<>();

    @Override
    public boolean isAllowed(String key, int limit, long windowMs) {
        // Step 1: Calculate which window we're currently in.
        // windowId changes every `windowMs` milliseconds.
        // e.g., with windowMs=60000: windowId=0 for first minute,
        //        windowId=1 for second minute, etc.
        long windowId = System.currentTimeMillis() / windowMs;

        // Step 2: Build a compound key = original key + current window ID
        // This means each time window gets its own separate counter.
        String windowKey = key + ":" + windowId;

        // Step 3: Get or create a counter for this window.
        // computeIfAbsent is atomic — safe under concurrent access.
        WindowCounter counter = counters.computeIfAbsent(
                windowKey, k -> new WindowCounter()
        );

        // Step 4: Atomically increment and check.
        // incrementAndGet() returns the NEW value after incrementing.
        // If the new count is <= limit, this request is allowed.
        long currentCount = counter.count.incrementAndGet();
        // Step 5: Lazy cleanup — remove counters from old windows
        // to prevent unbounded memory growth.
        // We only clean up occasionally (every 100 requests) to avoid
        // performance overhead on every single request.
        if (currentCount % 100 == 0) {
            cleanupOldWindows(windowId);
        }

        return currentCount <= limit;
    }

    @Override
    public String getName() {
        return "FIXED_WINDOW";
    }

    /**
     * Removes counters from windows that have already expired.
     * A window is "old" if its windowId is less than the current windowId.
     *
     * This is a simplified cleanup — production systems would use
     * a scheduled task or TTL-based expiry instead.
     */
    private void cleanupOldWindows(long currentWindowId) {
        counters.entrySet().removeIf(entry -> {
            // Extract the windowId from the compound key
            // Key format: "originalKey:windowId"
            String entryKey = entry.getKey();
            int lastColon = entryKey.lastIndexOf(':');
            if (lastColon < 0) return false;
            try {
                long entryWindowId = Long.parseLong(
                        entryKey.substring(lastColon + 1)
                );
                // Remove if this counter belongs to a previous window
                return entryWindowId < currentWindowId;
            } catch (NumberFormatException e) {
                return false;
            }
        });
    }

    /**
     * Clears all counters. Intended for use in tests only, to reset
     * shared in-memory state between test cases without restarting
     * the Spring context.
     */
    public void reset() {
        counters.clear();
    }

    /**
     * Inner class representing a single window's counter.
     *
     * Why a class instead of just AtomicLong?
     * Encapsulation — if we later want to add metadata
     * (e.g., first request timestamp, request IDs for logging),
     * we only change this class, not the outer logic.
     */
    static class WindowCounter {
        // AtomicLong: thread-safe long value using CPU-level CAS instructions.
        // No synchronized keyword needed — faster and avoids lock contention.
        final java.util.concurrent.atomic.AtomicLong count
                = new java.util.concurrent.atomic.AtomicLong(0);
    }
}
