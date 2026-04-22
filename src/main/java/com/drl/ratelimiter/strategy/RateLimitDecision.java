package com.drl.ratelimiter.strategy;

/**
 * Immutable result of evaluating a rate-limit request.
 * This value object carries both the allow/reject outcome and the retry delay for rejected requests.
 */
public final class RateLimitDecision {

    private static final RateLimitDecision ALLOWED = new RateLimitDecision(true, 0);

    private final boolean allowed;
    private final long retryAfterSeconds;

    private RateLimitDecision(boolean allowed, long retryAfterSeconds) {
        this.allowed = allowed;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Returns a reusable decision that allows the request immediately.
     *
     * @return allowed decision
     */
    public static RateLimitDecision allowed() {
        return ALLOWED;
    }

    /**
     * Creates a rejected decision with the number of seconds a client should wait before retrying.
     *
     * @param retryAfterSeconds retry delay in seconds
     * @return rejected decision
     */
    public static RateLimitDecision rejected(long retryAfterSeconds) {
        if (retryAfterSeconds <= 0) {
            throw new IllegalArgumentException("retryAfterSeconds must be greater than 0");
        }
        return new RateLimitDecision(false, retryAfterSeconds);
    }

    /**
     * Indicates whether the request is allowed.
     *
     * @return {@code true} when the request may proceed
     */
    public boolean isAllowed() {
        return allowed;
    }

    /**
     * Returns the retry delay for rejected requests.
     *
     * @return retry delay in seconds, or {@code 0} when the request is allowed
     */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
