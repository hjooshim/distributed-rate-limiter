package com.drl.ratelimiter.strategy;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * ============================================================
 * STRATEGY PATTERN — Concrete Implementation #2
 * Token Bucket Algorithm (Distributed / Redis)
 * ============================================================
 *
 * HOW IT WORKS:
 *   Think of each client as owning a bucket that can hold up to `limit`
 *   tokens. Every request spends one token.
 *
 *   Tokens refill gradually over time instead of resetting all at once.
 *   Refill rate = limit / windowMs.
 *
 *   Example with limit=5, window=10 seconds:
 *     t=0s   bucket starts full       -> 5 tokens
 *     req1   allowed                  -> 4 tokens
 *     req2   allowed                  -> 3 tokens
 *     req3   allowed                  -> 2 tokens
 *     req4   allowed                  -> 1 token
 *     req5   allowed                  -> 0 tokens
 *     req6   rejected                 -> bucket empty
 *     t=2s   about 1 token refilled   -> 1 token
 *     req7   allowed                  -> 0 tokens
 *
 * WHY TOKEN BUCKET IS USEFUL:
 *   Unlike Fixed Window, this algorithm smooths traffic over time and allows
 *   short bursts up to the bucket capacity while still enforcing the average
 *   rate. That makes it better for APIs where occasional bursts are okay but
 *   sustained abuse should still be throttled.
 *
 * DISTRIBUTED DESIGN:
 *   Bucket state is stored in Redis so every application instance sees the
 *   same token count.
 *
 *   Each bucket stores:
 *     - tokens: how many tokens remain right now
 *     - lastRefillMs: when we last recalculated refill
 *
 *   The refill calculation and token deduction happen inside one Lua script.
 *   That is critical for correctness: Redis executes a Lua script atomically,
 *   so two servers cannot both read the same token and both allow a request.
 *
 * TIME SOURCE:
 *   The Lua script uses Redis TIME rather than the JVM clock from an
 *   application node. This avoids clock-skew bugs where different instances
 *   disagree about how many tokens should have refilled.
 *
 * STATE LIFECYCLE:
 *   Redis keys get a TTL longer than the logical window. Idle buckets then
 *   expire automatically, which prevents unused clients from leaving stale
 *   token state in Redis forever.
 */
@Component
public class RedisTokenBucketStrategy extends AbstractRedisRateLimitStrategy {

    // Lua script that performs refill + consume as one atomic Redis operation.
    private static final DefaultRedisScript<String> TOKEN_BUCKET_SCRIPT =
            loadScript("scripts/token_bucket.lua", String.class);

    /**
     * Creates the token-bucket strategy.
     *
     * @param redisTemplate Redis client used to store bucket state
     */
    public RedisTokenBucketStrategy(StringRedisTemplate redisTemplate) {
        super("TOKEN_BUCKET", redisTemplate, "token_bucket");
    }

    @Override
    protected RateLimitDecision doEvaluate(String key, int limit, long windowMs) {
        // Step 1: Compute how long Redis should retain the bucket state.
        // The TTL is intentionally longer than the logical window so partially
        // refilled buckets can survive brief idle periods, but still expire
        // eventually if the client stops sending requests.
        long ttl = ttlMs(windowMs);

        // Step 2: Scope the logical key by the configured policy.
        // This prevents the same caller from accidentally sharing one bucket
        // across different limits or window sizes.
        //
        // Example:
        //   user-123 + limit=10 + window=60000
        //   user-123 + limit=100 + window=60000
        //
        // Those must be different Redis buckets because they represent
        // different rate-limit policies.
        String policyScopedKey = key + ":" + limit + ":" + windowMs;

        // Step 3: Execute the Lua script.
        // Arguments:
        //   1. limit      -> bucket capacity
        //   2. windowMs   -> refill period used to derive refill rate
        //   3. "1"        -> tokens requested for this single API call
        //   4. ttl        -> Redis key expiry in milliseconds
        //
        // The script does all of this atomically:
        //   - load current bucket state
        //   - calculate how many tokens should be refilled
        //   - allow/reject this request
        //   - persist updated token state
        //   - return "allowed:retryAfterSeconds"
        return executeDecisionScript(
                TOKEN_BUCKET_SCRIPT,
                policyScopedKey,
                String.valueOf(limit),
                String.valueOf(windowMs),
                "1",
                String.valueOf(ttl)
        );
    }
}
