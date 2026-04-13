package com.drl.ratelimiter.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import com.drl.ratelimiter.strategy.LocalFixedWindowStrategy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ClientIdentityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalFixedWindowStrategy strategy;

    @BeforeEach
    void resetCounters() {
        strategy.reset();
    }

    @Test
    @DisplayName("Forwarded header should be ignored by default when remote address is available")
    void forwardedHeaderShouldBeIgnoredByDefaultWhenRemoteAddressIsAvailable() throws Exception {
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

    @Test
    @DisplayName("Principal should be preferred over remote address")
    void principalShouldBePreferredOverRemoteAddress() throws Exception {
        Principal principal = () -> "alice";

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
