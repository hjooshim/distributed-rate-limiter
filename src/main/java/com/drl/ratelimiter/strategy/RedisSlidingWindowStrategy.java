package com.drl.ratelimiter.strategy;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * ============================================================
 * STRATEGY PATTERN — Concrete Implementation #3
 * Sliding Window Algorithm (Distributed / Redis)
 * ============================================================
 *
 * HOW IT WORKS:
 *   Unlike Fixed Window, the window does not reset on a fixed clock boundary.
 *   Instead, it slides continuously with time. At any moment, the window
 *   covers exactly the last windowMs milliseconds.
 *
 *   Each request is recorded as one entry in a Redis Sorted Set with a
 *   timestamp as its score. The count of entries in the set is the exact
 *   number of requests made within the current window.
 *
 *   Example with limit=3, window=10 seconds:
 *     t=0s   req1 ✅ window=[t-10s, t]  count=1
 *     t=3s   req2 ✅ window=[t-10s, t]  count=2
 *     t=6s   req3 ✅ window=[t-10s, t]  count=3
 *     t=8s   req4 ❌ window=[t-10s, t]  count=3, at limit
 *     t=11s  req5 ✅ window=[1s, 11s]   req1 slides out, count=2
 *
 *   This eliminates the boundary burst problem of Fixed Window:
 *   a client can never exploit a window reset to get 2× the limit.
 *
 * WHY SORTED SET:
 *   Redis Sorted Sets allow O(log N) range deletions by score, making the
 *   trim step efficient even when a bucket has accumulated many entries.
 *   Storing one entry per request also means the count is always exact —
 *   no interpolation or approximation is needed.
 *
 * DISTRIBUTED DESIGN:
 *   All steps — trim, count, record, TTL refresh — execute inside one Lua
 *   script. Redis runs Lua scripts atomically, so two concurrent requests
 *   cannot both read the same count and both decide capacity is available.
 *
 * TIME SOURCE:
 *   The Lua script uses Redis TIME rather than the JVM clock from an
 *   application node. This avoids clock-skew bugs where different instances
 *   disagree about which entries fall inside the current window.
 *
 * STATE LIFECYCLE:
 *   Redis keys get a TTL longer than the logical window. Sorted Sets for
 *   idle clients then expire automatically, preventing abandoned request
 *   history from accumulating in Redis indefinitely.
 */
@Component
public class RedisSlidingWindowStrategy extends AbstractRedisRateLimitStrategy {

    // Lua script that performs trim + count + record as one atomic Redis operation.
    private static final DefaultRedisScript<String> SLIDING_WINDOW_SCRIPT =
            loadScript("scripts/sliding_window.lua", String.class);

    /**
     * Creates the sliding-window strategy.
     *
     * @param redisTemplate Redis client used to store per-client request history
     */
    public RedisSlidingWindowStrategy(StringRedisTemplate redisTemplate) {
        super("SLIDING_WINDOW", redisTemplate, "sliding_window");
    }

    @Override
    protected RateLimitDecision doEvaluate(String key, int limit, long windowMs) {
        // Step 1: Compute how long Redis should retain the sorted set.
        // The TTL is intentionally longer than the logical window so entries
        // added near the window boundary are still visible to the next request,
        // and the key expires automatically once the client goes idle.
        long ttl = ttlMs(windowMs);

        // Step 2: Scope the logical key by the configured policy.
        // This prevents the same caller from accidentally sharing one sorted set
        // across different limits or window sizes configured on different endpoints.
        //
        // Example:
        //   user-123 + limit=5  + window=10000
        //   user-123 + limit=10 + window=10000
        //
        // Those must be different Redis keys because they represent
        // different rate-limit policies.
        String policyScopedKey = key + ":" + limit + ":" + windowMs;

        // Step 3: Execute the Lua script.
        // Arguments:
        //   1. limit     -> maximum number of requests allowed inside the window
        //   2. windowMs  -> length of the sliding window in milliseconds
        //   3. ttl       -> Redis key expiry in milliseconds
        //
        // The script does all of this atomically:
        //   - trim entries that have slid out of the window
        //   - count remaining entries
        //   - allow/reject this request
        //   - record the request if allowed
        //   - return "allowed:retryAfterSeconds"
        return executeDecisionScript(
                SLIDING_WINDOW_SCRIPT,
                policyScopedKey,
                String.valueOf(limit),
                String.valueOf(windowMs),
                String.valueOf(ttl)
        );
    }
}
