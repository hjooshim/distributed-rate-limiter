package com.drl.ratelimiter.strategy;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
public class LocalFixedWindowStrategy extends AbstractRateLimitStrategy {

    // Key = "userId:method:windowMs:windowId"
    // Value = WindowCounter (holds count for that configured window)
    private final ConcurrentHashMap<String, WindowCounter> counters
            = new ConcurrentHashMap<>();
    private final AtomicLong totalRequests = new AtomicLong(0);

    /**
     * Creates the in-memory fixed-window strategy.
     */
    public LocalFixedWindowStrategy() {
        super("FIXED_WINDOW");
    }

    @Override
    protected RateLimitDecision doEvaluate(String key, int limit, long windowMs) {
        long nowMs = System.currentTimeMillis();

        // Step 1: Calculate which window we're currently in.
        // windowId changes every `windowMs` milliseconds.
        // e.g., with windowMs=60000: windowId=0 for first minute,
        //        windowId=1 for second minute, etc.
        long windowId = nowMs / windowMs;

        // Step 2: Build a compound key = original key + configured window size
        // + current window ID. This keeps different policies isolated even
        // if two window sizes would otherwise produce the same quotient.
        String windowKey = key + ":" + windowMs + ":" + windowId;

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
        if (totalRequests.incrementAndGet() % 100 == 0) {
            cleanupOldWindows(nowMs);
        }

        if (currentCount <= limit) {
            return RateLimitDecision.allowed();
        }

        long elapsedInWindowMs = nowMs % windowMs;
        long remainingWindowMs = Math.max(1L, windowMs - elapsedInWindowMs);
        return RateLimitDecision.rejected(toRetryAfterSeconds(remainingWindowMs));
    }

    /**
     * Removes counters from windows that have already expired.
     * A window is "old" if its windowId is less than the current windowId.
     *
     * This is a simplified cleanup — production systems would use
     * a scheduled task or TTL-based expiry instead.
     */
    private void cleanupOldWindows(long currentTimeMs) {
        counters.entrySet().removeIf(entry -> {
            // Extract the window configuration from the compound key.
            // Key format: "originalKey:windowMs:windowId"
            String entryKey = entry.getKey();
            int lastColon = entryKey.lastIndexOf(':');
            if (lastColon < 0) return false;
            int secondLastColon = entryKey.lastIndexOf(':', lastColon - 1);
            if (secondLastColon < 0) return false;
            try {
                long entryWindowMs = Long.parseLong(
                        entryKey.substring(secondLastColon + 1, lastColon)
                );
                long entryWindowId = Long.parseLong(
                        entryKey.substring(lastColon + 1)
                );
                long currentWindowId = currentTimeMs / entryWindowMs;
                long oldestRetainedWindowId = Math.max(0, currentWindowId - 1);
                // Keep the immediately previous window so delayed requests
                // around a rollover cannot recreate an already-evicted bucket.
                return entryWindowId < oldestRetainedWindowId;
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
    void reset() {
        counters.clear();
        totalRequests.set(0);
    }

    private long toRetryAfterSeconds(long remainingWindowMs) {
        return Math.max(1L, (remainingWindowMs + 999L) / 1_000L);
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
