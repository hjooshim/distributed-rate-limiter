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
