package com.drl.ratelimiter.strategy;

/**
 * Immutable result of evaluating a rate-limit request.
 */
public final class RateLimitDecision {

    private static final RateLimitDecision ALLOWED = new RateLimitDecision(true, 0);

    private final boolean allowed;
    private final long retryAfterSeconds;

    private RateLimitDecision(boolean allowed, long retryAfterSeconds) {
        this.allowed = allowed;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static RateLimitDecision allowed() {
        return ALLOWED;
    }

    public static RateLimitDecision rejected(long retryAfterSeconds) {
        if (retryAfterSeconds <= 0) {
            throw new IllegalArgumentException("retryAfterSeconds must be greater than 0");
        }
        return new RateLimitDecision(false, retryAfterSeconds);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
