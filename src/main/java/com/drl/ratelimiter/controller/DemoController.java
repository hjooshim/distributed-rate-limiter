package com.drl.ratelimiter.controller;

import com.drl.ratelimiter.annotation.RateLimit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Demo endpoints used to show how {@link RateLimit} is applied.
 */
@RestController
@RequestMapping("/api")
public class DemoController {

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
                "limit", "3 per 10 seconds",
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
                "limit", "10 per minute",
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
                "limit", "1 per minute",
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * Second token-bucket endpoint used to prove per-endpoint isolation.
     *
     * @return response body for the second token-bucket demo endpoint
     */
    @RateLimit(limit = 1, windowMs = 60_000, algorithm = "TOKEN_BUCKET")
    @GetMapping("/token-bucket/secondary")
    public Map<String, Object> tokenBucketSecondaryEndpoint() {
        return Map.of(
                "message", "Token bucket secondary endpoint",
                "limit", "1 per minute",
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
                "limit", "3 per 10 seconds",
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
                "limit", "3 per 10 seconds",
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
