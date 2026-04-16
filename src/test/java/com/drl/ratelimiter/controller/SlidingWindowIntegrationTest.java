package com.drl.ratelimiter.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ============================================================
 * INTEGRATION TESTS - Sliding-window HTTP flow
 * ============================================================
 *
 * Fires MockMvc requests through the real Spring stack while Redis is provided
 * by a testcontainer. These tests verify algorithm selection, window enforcement,
 * and key scoping at the HTTP layer.
 *
 * Coverage:
 *   - SLIDING_WINDOW endpoints should populate the sliding-window Redis namespace
 *   - requests beyond the limit inside the window should be rejected
 *   - different clients should not share windows
 *   - one client should still get separate windows per endpoint
 *   - trusted forwarded-header and remote-address identity paths should work
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ratelimit.identity.trust-forwarded-header=true"
)
@AutoConfigureMockMvc
class SlidingWindowIntegrationTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(60));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        // Wire the Spring Boot Redis client to the ephemeral testcontainer.
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void clearRedis() {
        // Clear shared Redis state between tests so each scenario starts with
        // an empty sliding-window namespace.
        try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.flushAll();
        }
    }

    // ---------------------------------------------------------
    // Algorithm selection
    // ---------------------------------------------------------

    @Test
    @DisplayName("SLIDING_WINDOW should be selected when requested")
    void slidingWindowAlgorithmShouldBeSelectedWhenRequested() throws Exception {
        mockMvc.perform(get("/api/sliding-window/primary"))
                .andExpect(status().isOk());

        // A successful request must have written a sorted-set entry under
        // the sliding-window Redis namespace, not the token-bucket namespace.
        Set<String> keys = redisTemplate.keys("rate_limit:sliding_window:*");
        assertThat(keys).isNotNull();
        assertThat(keys).isNotEmpty();
        assertThat(keys).allMatch(key -> key.startsWith("rate_limit:sliding_window:"));
    }

    // ---------------------------------------------------------
    // Window enforcement
    // ---------------------------------------------------------

    @Test
    @DisplayName("Requests beyond the limit inside the window should be rejected with 429")
    void requestsBeyondLimitInsideWindowShouldBeRejected() throws Exception {
        // The endpoint is configured with limit=3. The first three requests
        // from the same client should succeed; the fourth must be rejected.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/sliding-window/primary")
                            .header("X-Forwarded-For", "203.0.113.30"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/sliding-window/primary")
                        .header("X-Forwarded-For", "203.0.113.30"))
                .andExpect(status().isTooManyRequests());
    }

    // ---------------------------------------------------------
    // Window isolation
    // ---------------------------------------------------------

    @Test
    @DisplayName("Different clients should not share the same sliding window")
    void differentClientsShouldNotShareAWindow() throws Exception {
        // Exhaust client A's quota entirely.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/sliding-window/primary")
                            .header("X-Forwarded-For", "203.0.113.31"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/sliding-window/primary")
                        .header("X-Forwarded-For", "203.0.113.31"))
                .andExpect(status().isTooManyRequests());

        // Client B has its own independent window and should still be allowed.
        mockMvc.perform(get("/api/sliding-window/primary")
                        .header("X-Forwarded-For", "203.0.113.32"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("One client should get independent windows per endpoint")
    void oneClientShouldGetIndependentWindowsPerEndpoint() throws Exception {
        // Exhaust client's quota on the primary endpoint.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/sliding-window/primary")
                            .header("X-Forwarded-For", "203.0.113.33"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/sliding-window/primary")
                        .header("X-Forwarded-For", "203.0.113.33"))
                .andExpect(status().isTooManyRequests());

        // The secondary endpoint holds a separate sorted set for the same
        // client, so its quota is untouched.
        mockMvc.perform(get("/api/sliding-window/secondary")
                        .header("X-Forwarded-For", "203.0.113.33"))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------
    // Identity resolution
    // ---------------------------------------------------------

    @Test
    @DisplayName("Trusted forwarded header should use the first trimmed client value")
    void trustedForwardedHeaderShouldUseTheFirstTrimmedClientValue() throws Exception {
        // Exhaust quota using a forwarded header with extra whitespace and a
        // proxy chain — only the first value should be used as the client id.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/sliding-window/primary")
                            .header("X-Forwarded-For", " 203.0.113.40 , 10.0.0.1 "))
                    .andExpect(status().isOk());
        }

        // A second request using the same first value — even with a different
        // proxy suffix — must be counted against the same window.
        mockMvc.perform(get("/api/sliding-window/primary")
                        .header("X-Forwarded-For", "203.0.113.40"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Remote address fallback should work when X-Forwarded-For is missing")
    void remoteAddressFallbackShouldWorkWhenXForwardedForIsMissing() throws Exception {
        // Exhaust quota using the raw remote address as the client id.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/sliding-window/primary")
                            .with(request -> {
                                request.setRemoteAddr("198.51.100.50");
                                return request;
                            }))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/sliding-window/primary")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.50");
                            return request;
                        }))
                .andExpect(status().isTooManyRequests());
    }
}
