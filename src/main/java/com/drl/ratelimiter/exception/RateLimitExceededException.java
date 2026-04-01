package com.drl.ratelimiter.exception;

/**
 * Raised when a request is rejected by the rate limiter.
 */
public class RateLimitExceededException extends RuntimeException {

    private final String key;
    private final int limit;
    private final long windowMs;

    /**
     * Creates an exception for a rejected request.
     *
     * @param key unique rate-limit key for the rejected request
     * @param limit configured maximum number of requests in the window
     * @param windowMs configured window length in milliseconds
     */
    public RateLimitExceededException(String key, int limit, long windowMs) {
        super(String.format(
                "Rate limit exceeded for key '%s': max %d requests per %dms",
                key,
                limit,
                windowMs
        ));
        this.key = key;
        this.limit = limit;
        this.windowMs = windowMs;
    }

    /**
     * Returns the rejected rate-limit key.
     *
     * @return key used by the rate limiter
     */
    public String getKey() {
        return key;
    }

    /**
     * Returns the configured request limit.
     *
     * @return maximum allowed requests in the current window
     */
    public int getLimit() {
        return limit;
    }

    /**
     * Returns the configured window size in milliseconds.
     *
     * @return window length in milliseconds
     */
    public long getWindowMs() {
        return windowMs;
    }
}
