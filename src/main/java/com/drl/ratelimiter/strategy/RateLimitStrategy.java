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

    default boolean isAllowed(String key, int limit, long windowMs) {
        return evaluate(key, limit, windowMs).isAllowed();
    }

  /**
   * Returns the name of this strategy. Used by StrategyRegistry to look up the correct
   * implementation. Example: "FIXED_WINDOW", "SLIDING_WINDOW", "TOKEN_BUCKET"
   */
  default String getName() {
    return null;
  }
}
