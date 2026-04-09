package com.drl.ratelimiter.strategy;

public abstract class AbstractRateLimitStrategy implements RateLimitStrategy {

    private final String name;

    protected AbstractRateLimitStrategy(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name.trim();
    }

    @Override
    public final boolean isAllowed(String key, int limit, long windowMs) {
        validateKey(key);
        validateLimit(limit);
        validateWindowMs(windowMs);
        return doIsAllowed(key, limit, windowMs);
    }

    @Override
    public final String getName() {
        return name;
    }

    protected abstract boolean doIsAllowed(String key, int limit, long windowMs);

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
