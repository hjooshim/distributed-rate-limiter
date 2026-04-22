package com.drl.ratelimiter.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.util.ReflectionTestUtils.invokeMethod;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.drl.ratelimiter.strategy.LocalFixedWindowStrategy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ============================================================
 * INTEGRATION TESTS — Full HTTP Stack via MockMvc
 * ============================================================
 *
 * Fires real HTTP requests through the complete Spring stack:
 *
 *   MockMvc → DispatcherServlet → AOP Aspect → Controller
 *                                                    ↓
 *                              GlobalExceptionHandler ← RateLimitExceededException
 *
 * Covers all required cases from design doc Section 3.1 and 3.2:
 *   3.1-1  Allow requests under the limit           → strictEndpoint_shouldAllowRequestsWithinLimit
 *   3.1-2  Reject requests over the limit           → strictEndpoint_shouldReturn429AfterLimitExceeded
 *   3.1-3  Error response validation (header+body)  → strictEndpoint_429ShouldIncludeRetryAfterHeader
 *                                                      strictEndpoint_429ShouldHaveStructuredErrorBody
 *   3.1-4  Endpoint isolation                       → endpointIsolation_exhaustingOneEndpointShouldNotAffectAnother
 *   3.1-5  No @RateLimit = never blocked            → freeEndpoint_shouldNeverBeRateLimited
 *   3.1-6  Recovery after window resets             → recovery_shouldAllowRequestsAfterWindowResets
 *   3.2    Concurrency: full HTTP stack             → concurrent_exactlyLimitRequestsShouldBeAllowed
 *                                                      concurrent_limitRemainsExactUnderRepeatedBursts
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalFixedWindowStrategy strategy;

    /**
     * Reset all in-memory counters before each test.
     * All tests share the same Spring context and the same strategy bean,
     * so without this reset, quota exhausted in one test bleeds into the next.
     */
    @BeforeEach
    void resetCounters() {
        invokeMethod(strategy, "reset");
    }

    // ─────────────────────────────────────────────
    // 3.1-1  Allow requests under the limit
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("Allow: requests within the limit should return HTTP 200")
    void strictEndpoint_shouldAllowRequestsWithinLimit() throws Exception {
        // /api/strict is configured with @RateLimit(limit = 3).
        // Each of the first 3 calls must succeed with HTTP 200.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/strict"))

                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").exists());
        }
    }

    // ─────────────────────────────────────────────
    // 3.1-2  Reject requests over the limit
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("Reject: first request exceeding the limit should return HTTP 429")
    void strictEndpoint_shouldReturn429AfterLimitExceeded() throws Exception {
        // Exhaust the 3-request quota
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/strict")).andExpect(status().isOk());
        }

        // The 4th request must be rejected
        mockMvc.perform(get("/api/strict"))
                .andExpect(status().isTooManyRequests());
    }

    // ─────────────────────────────────────────────
    // 3.1-3  Error response validation
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("Error response: HTTP 429 must include Retry-After header")
    void strictEndpoint_429ShouldIncludeRetryAfterHeader() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/strict")).andExpect(status().isOk());
        }

        // Retry-After is required by RFC 6585 — tells the client when to retry.
        mockMvc.perform(get("/api/strict"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("Error response: HTTP 429 body must contain all required JSON fields")
    void strictEndpoint_429ShouldHaveStructuredErrorBody() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/strict")).andExpect(status().isOk());
        }

        // GlobalExceptionHandler must return a structured JSON body.
        // Clients depend on these fields to display meaningful error messages.
        mockMvc.perform(get("/api/strict"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.key").exists())
                .andExpect(jsonPath("$.limit").exists())
                .andExpect(jsonPath("$.windowMs").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ─────────────────────────────────────────────
    // 3.1-4  Endpoint isolation
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("Endpoint isolation: exhausting one endpoint must not affect another")
    void endpointIsolation_exhaustingOneEndpointShouldNotAffectAnother() throws Exception {
        // Each endpoint's rate limit key is "ClassName.methodName", so
        // /api/strict and /api/normal maintain completely separate counters.
        //
        // Exhaust /api/strict (limit = 3)
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/strict")).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/strict")).andExpect(status().isTooManyRequests());

        // /api/normal must still be unaffected and accept new requests
        mockMvc.perform(get("/api/normal"))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────
    // 3.1-5  Endpoint without @RateLimit is never blocked
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("No rate limit: endpoint without @RateLimit should never return 429")
    void freeEndpoint_shouldNeverBeRateLimited() throws Exception {
        // /api/free has no @RateLimit annotation — the AOP aspect must skip it entirely.
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/api/free"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("Demo endpoint response should match the configured token-bucket policy")
    void tokenBucketDemoEndpoint_shouldExposeTheConfiguredPolicy() throws Exception {
        mockMvc.perform(get("/api/token-bucket/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value("5 per 10 seconds"));
    }

    // ─────────────────────────────────────────────
    // 3.1-6  Recovery after window resets
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("Recovery: requests should be allowed again after the time window resets")
    void recovery_shouldAllowRequestsAfterWindowResets() throws Exception {
        // Exhaust the quota for /api/strict (limit = 3, windowMs = 10_000)
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/strict")).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/strict")).andExpect(status().isTooManyRequests());

        // Wait for the 10-second window to expire.
        // Once the window rolls over, LocalFixedWindowStrategy computes a new
        // windowId and creates a fresh counter — the limit resets to 0.
        Thread.sleep(10_100);

        // The first request in the new window must be allowed again
        mockMvc.perform(get("/api/strict"))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────
    // 3.2  Concurrent stress tests — full HTTP stack
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("Concurrent: exactly 'limit' threads should get 200, the rest 429")
    void concurrent_exactlyLimitRequestsShouldBeAllowed() throws InterruptedException {
        /*
         * Unit tests verify AtomicLong is thread-safe at the strategy layer.
         * This test fires real HTTP requests through the full stack:
         *
         *   Thread-1 ─┐
         *   Thread-2 ─┤─→ MockMvc → DispatcherServlet → AOP Aspect → Strategy
         *   Thread-N ─┘
         *
         * If the AOP aspect had a race condition (e.g., reading annotation
         * parameters was not thread-safe), only this test would catch it.
         *
         * CountDownLatch startLatch: holds all threads at the gate until
         * countDown() fires them simultaneously for maximum contention.
         */
        final int LIMIT        = 3;   // matches @RateLimit(limit = 3) on /api/strict
        final int THREAD_COUNT = 30;  // 10× the limit to guarantee saturation

        AtomicInteger allowed  = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(THREAD_COUNT);
        ExecutorService executor  = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Hold until all threads are ready

                    MvcResult result = mockMvc.perform(get("/api/strict")).andReturn();

                    int status = result.getResponse().getStatus();
                    if (status == 200)      allowed.incrementAndGet();
                    else if (status == 429) rejected.incrementAndGet();

                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Release all threads simultaneously
        doneLatch.await();
        executor.shutdown();

        System.out.printf("[Concurrent] allowed=%d, rejected=%d (limit=%d, threads=%d)%n",
                allowed.get(), rejected.get(), LIMIT, THREAD_COUNT);

        // Across the full AOP + HTTP stack, exactly LIMIT requests must succeed.
        assertThat(allowed.get())
                .as("Exactly %d concurrent requests should receive HTTP 200", LIMIT)
                .isEqualTo(LIMIT);
        assertThat(rejected.get())
                .as("Remaining %d requests should receive HTTP 429", THREAD_COUNT - LIMIT)
                .isEqualTo(THREAD_COUNT - LIMIT);
    }

    @RepeatedTest(5)
    @DisplayName("Concurrent stress: limit holds correctly across repeated bursts")
    void concurrent_limitRemainsExactUnderRepeatedBursts() throws InterruptedException {
        /*
         * Runs the same concurrent burst 5 times (@RepeatedTest).
         * Each repetition resets counters via @BeforeEach, then fires
         * THREAD_COUNT threads simultaneously against /api/normal (limit=10).
         *
         * Verifies there is no warm-up effect or JIT drift that causes
         * the enforced limit to change after the first run.
         */
        final int LIMIT        = 10;  // matches @RateLimit(limit = 10) on /api/normal
        final int THREAD_COUNT = 50;

        AtomicInteger allowed  = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(THREAD_COUNT);
        ExecutorService executor  = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();

                    MvcResult result = mockMvc.perform(get("/api/normal")).andReturn();

                    int status = result.getResponse().getStatus();
                    if (status == 200)      allowed.incrementAndGet();
                    else if (status == 429) rejected.incrementAndGet();

                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        System.out.printf("[Burst Repeat] allowed=%d, rejected=%d%n",
                allowed.get(), rejected.get());

        assertThat(allowed.get()).isEqualTo(LIMIT);
        assertThat(rejected.get()).isEqualTo(THREAD_COUNT - LIMIT);
    }
}
