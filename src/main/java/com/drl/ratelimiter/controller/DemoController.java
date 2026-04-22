package com.drl.ratelimiter.controller;

import com.drl.ratelimiter.annotation.RateLimit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Demo endpoints used to show how {@link RateLimit} is applied across multiple policies.
 * The responses intentionally echo the configured limits so demos and tests can verify policy isolation.
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    private static final String STRICT_LIMIT_LABEL = "3 per 10 seconds";
    private static final String NORMAL_LIMIT_LABEL = "10 per minute";
    private static final String TOKEN_BUCKET_PRIMARY_LIMIT_LABEL = "1 per minute";
    private static final String TOKEN_BUCKET_DEMO_LIMIT_LABEL = "5 per 10 seconds";
    private static final String SLIDING_WINDOW_LIMIT_LABEL = "3 per 10 seconds";

    /**
     * Endpoint with a strict policy.
     *
     * @return response body for the strict endpoint
     */
    @RateLimit(limit = 3, windowMs = 10_000)
    @GetMapping("/strict")
    public Map<String, Object> strictEndpoint() {
        return Map.of(
                "message", "Strict endpoint",
                "limit", STRICT_LIMIT_LABEL,
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * Endpoint with a more relaxed policy.
     *
     * @return response body for the normal endpoint
     */
    @RateLimit(limit = 10, windowMs = 60_000)
    @GetMapping("/normal")
    public Map<String, Object> normalEndpoint() {
        return Map.of(
                "message", "Normal endpoint",
                "limit", NORMAL_LIMIT_LABEL,
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * Endpoint backed by the token-bucket strategy for client-aware coverage.
     *
     * @return response body for the token-bucket demo endpoint
     */
    @RateLimit(limit = 1, windowMs = 60_000, algorithm = "TOKEN_BUCKET")
    @GetMapping("/token-bucket/primary")
    public Map<String, Object> tokenBucketPrimaryEndpoint() {
        return Map.of(
                "message", "Token bucket primary endpoint",
                "limit", TOKEN_BUCKET_PRIMARY_LIMIT_LABEL,
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * Second token-bucket endpoint used to prove per-endpoint isolation.
     *
     * @return response body for the second token-bucket demo endpoint
     */
    @RateLimit(limit = 5, windowMs = 10_000, algorithm = "TOKEN_BUCKET")
    @GetMapping("/token-bucket/demo")
    public Map<String, Object> tokenBucketDemoEndpoint() {
        return Map.of(
                "message", "Token bucket secondary endpoint",
                "limit", TOKEN_BUCKET_DEMO_LIMIT_LABEL,
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * Endpoint backed by the sliding-window strategy for client-aware coverage.
     *
     * @return response body for the sliding-window primary demo endpoint
     */
    @RateLimit(limit = 3, windowMs = 10_000, algorithm = "SLIDING_WINDOW")
    @GetMapping("/sliding-window/primary")
    public Map<String, Object> slidingWindowPrimaryEndpoint() {
        return Map.of(
                "message", "Sliding window primary endpoint",
                "limit", SLIDING_WINDOW_LIMIT_LABEL,
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * Second sliding-window endpoint used to prove per-endpoint isolation.
     *
     * @return response body for the sliding-window secondary demo endpoint
     */
    @RateLimit(limit = 3, windowMs = 10_000, algorithm = "SLIDING_WINDOW")
    @GetMapping("/sliding-window/secondary")
    public Map<String, Object> slidingWindowSecondaryEndpoint() {
        return Map.of(
                "message", "Sliding window secondary endpoint",
                "limit", SLIDING_WINDOW_LIMIT_LABEL,
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * Endpoint without any rate-limit annotation.
     *
     * @return response body for the free endpoint
     */
    @GetMapping("/free")
    public Map<String, Object> freeEndpoint() {
        return Map.of(
                "message", "No rate limit on this endpoint",
                "timestamp", Instant.now().toString()
        );
    }
}
