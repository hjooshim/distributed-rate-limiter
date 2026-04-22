package com.drl.ratelimiter.strategy;

/**
 * ============================================================
 * STRATEGY PATTERN — Core Interface
 * ============================================================
 *
 * This interface is the heart of the entire design.
 * It defines ONE contract that all rate-limiting algorithms must fulfill.
 *
 * OOP Principles demonstrated here:
 *  - Abstraction: callers only know about isAllowed(), not HOW it works
 *  - Open-Closed: adding a new algorithm = new class, zero changes here
 *  - Dependency Inversion: everything depends on THIS interface, not concretes
 *
 * Currently: LocalFixedWindowStrategy implements this.
 * In Week 2+: RedisStrategy will implement this with zero caller changes.
 */
public interface RateLimitStrategy {

    /**
     * Decides whether a request should be allowed through.
     *
     * @param key      Unique identifier for the rate limit bucket.
     *                 Usually built as "userId:methodName" or "ip:endpoint".
     * @param limit    Maximum number of requests allowed within the window.
     * @param windowMs Length of the time window in milliseconds.
     * @return full decision metadata for the current request.
     */
    RateLimitDecision evaluate(String key, int limit, long windowMs);

    /**
     * Convenience method that returns only the allow/reject outcome.
     *
     * @param key unique identifier for the rate-limit bucket
     * @param limit maximum number of requests allowed within the window
     * @param windowMs length of the time window in milliseconds
     * @return {@code true} when the request is allowed
     */
    default boolean isAllowed(String key, int limit, long windowMs) {
        return evaluate(key, limit, windowMs).isAllowed();
    }

    /**
     * Returns the stable registry name for this strategy implementation.
     *
     * @return algorithm name such as {@code FIXED_WINDOW}, {@code SLIDING_WINDOW}, or {@code TOKEN_BUCKET}
     */
    default String getName() {
        throw new UnsupportedOperationException("Strategy name must be provided by the implementation");
    }
}
