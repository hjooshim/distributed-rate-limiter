package com.drl.ratelimiter.exception;

/**
 * Raised when a request is rejected by the rate limiter.
 * It preserves the evaluated policy details so the HTTP layer can build a structured 429 response.
 */
public class RateLimitExceededException extends RuntimeException {

    private final String key;
    private final int limit;
    private final long windowMs;
    private final long retryAfterSeconds;

    /**
     * Creates an exception for a rejected request.
     *
     * @param key unique rate-limit key for the rejected request
     * @param limit configured maximum number of requests in the window
     * @param windowMs configured window length in milliseconds
     */
    public RateLimitExceededException(String key, int limit, long windowMs) {
        this(key, limit, windowMs, Math.max(1L, (windowMs + 999L) / 1_000L));
    }

    /**
     * Creates an exception for a rejected request with an explicit retry delay.
     *
     * @param key unique rate-limit key for the rejected request
     * @param limit configured maximum number of requests in the window
     * @param windowMs configured window length in milliseconds
     * @param retryAfterSeconds retry delay to return to the client
     */
    public RateLimitExceededException(String key, int limit, long windowMs, long retryAfterSeconds) {
        super(String.format(
                "Rate limit exceeded for key '%s': max %d requests per %dms",
                key,
                limit,
                windowMs
        ));
        this.key = key;
        this.limit = limit;
        this.windowMs = windowMs;
        this.retryAfterSeconds = retryAfterSeconds;
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

    /**
     * Returns the suggested retry delay in seconds.
     *
     * @return retry delay that should be exposed to the client
     */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
