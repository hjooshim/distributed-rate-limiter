package com.drl.ratelimiter.strategy;

/**
 * Base implementation for rate-limit strategies that share input validation and naming.
 */
public abstract class AbstractRateLimitStrategy implements RateLimitStrategy {

    private final String name;

    /**
     * Creates a named strategy.
     *
     * @param name stable algorithm name used for registry lookup
     */
    protected AbstractRateLimitStrategy(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name.trim();
    }

    @Override
    public final RateLimitDecision evaluate(String key, int limit, long windowMs) {
        validateKey(key);
        validateLimit(limit);
        validateWindowMs(windowMs);
        return doEvaluate(key, limit, windowMs);
    }

    @Override
    public final String getName() {
        return name;
    }

    /**
     * Evaluates a request after the shared validation layer has accepted the input.
     *
     * @param key logical rate-limit key
     * @param limit request limit or bucket capacity
     * @param windowMs time window or refill window in milliseconds
     * @return decision produced by the concrete strategy
     */
    protected abstract RateLimitDecision doEvaluate(String key, int limit, long windowMs);

    private void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }
    }

    private void validateLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
    }

    private void validateWindowMs(long windowMs) {
        if (windowMs <= 0) {
            throw new IllegalArgumentException("windowMs must be greater than 0");
        }
    }
}
