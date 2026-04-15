package com.drl.ratelimiter.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.drl.ratelimiter.strategy.LocalFixedWindowStrategy;
import java.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ============================================================
 * INTEGRATION TESTS - Client identity in the HTTP rate-limit path
 * ============================================================
 *
 * Sends real MockMvc requests through the full stack to verify that identity
 * resolution changes which callers share or do not share quota.
 *
 * Coverage:
 *   - forwarded headers are ignored by default
 *   - authenticated principals override remote addresses
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ClientIdentityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalFixedWindowStrategy strategy;

    /**
     * Reset all fixed-window counters before each test.
     * These integration tests share one Spring context and one in-memory
     * strategy bean, so quota usage must be cleared between scenarios.
     */
    @BeforeEach
    void resetCounters() {
        strategy.reset();
    }

    // ---------------------------------------------------------
    // Forwarded-header trust disabled by default
    // ---------------------------------------------------------

    @Test
    @DisplayName("Forwarded header should be ignored by default when remote address is available")
    void forwardedHeaderShouldBeIgnoredByDefaultWhenRemoteAddressIsAvailable() throws Exception {
        // Different forwarded values, same remote address.
        // Because trust is disabled by default, all four requests must map to
        // the same client identity and exhaust one shared quota.
        mockMvc.perform(get("/api/strict")
                        .header("X-Forwarded-For", "203.0.113.10")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.30");
                            return request;
                        }))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/strict")
                        .header("X-Forwarded-For", "203.0.113.11")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.30");
                            return request;
                        }))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/strict")
                        .header("X-Forwarded-For", "203.0.113.12")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.30");
                            return request;
                        }))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/strict")
                        .header("X-Forwarded-For", "203.0.113.13")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.30");
                            return request;
                        }))
                .andExpect(status().isTooManyRequests());
    }

    // ---------------------------------------------------------
    // Principal identity takes precedence
    // ---------------------------------------------------------

    @Test
    @DisplayName("Principal should be preferred over remote address")
    void principalShouldBePreferredOverRemoteAddress() throws Exception {
        Principal principal = () -> "alice";

        // Same principal, different remote addresses.
        // The resolver should key all requests by principal:alice, so they
        // still consume one shared quota and the 4th call must be rejected.
        mockMvc.perform(get("/api/strict")
                        .principal(principal)
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.41");
                            return request;
                        }))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/strict")
                        .principal(principal)
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.42");
                            return request;
                        }))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/strict")
                        .principal(principal)
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.43");
                            return request;
                        }))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/strict")
                        .principal(principal)
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.44");
                            return request;
                        }))
                .andExpect(status().isTooManyRequests());
    }
}
