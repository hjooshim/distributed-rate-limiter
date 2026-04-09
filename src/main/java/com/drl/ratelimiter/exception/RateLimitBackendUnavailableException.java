package com.drl.ratelimiter.exception;

/**
 * Raised when the distributed rate-limit backend cannot be reached safely.
 */
public class RateLimitBackendUnavailableException extends RuntimeException {

    private final String strategy;
    private final String key;

    /**
     * Creates an exception for a backend failure during rate-limit evaluation.
     *
     * @param strategy strategy that attempted the backend call
     * @param key backend key involved in the failed check
     * @param cause underlying Redis or script execution failure
     */
    public RateLimitBackendUnavailableException(String strategy, String key, Throwable cause) {
        super(String.format(
                "Rate limit backend unavailable for strategy '%s' and key '%s'",
                strategy,
                key
        ), cause);
        this.strategy = strategy;
        this.key = key;
    }

    /**
     * Returns the strategy that failed.
     *
     * @return strategy name
     */
    public String getStrategy() {
        return strategy;
    }

    /**
     * Returns the backend key involved in the failure.
     *
     * @return backend key
     */
    public String getKey() {
        return key;
    }
}
