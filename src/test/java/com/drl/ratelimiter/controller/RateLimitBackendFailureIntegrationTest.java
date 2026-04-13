package com.drl.ratelimiter.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=1",
                "spring.data.redis.timeout=250ms",
                "spring.data.redis.connect-timeout=250ms",
                "ratelimit.identity.trust-forwarded-header=true"
        }
)
@AutoConfigureMockMvc
class RateLimitBackendFailureIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("TOKEN_BUCKET should fail closed with HTTP 503 when Redis is unavailable")
    void tokenBucketShouldReturn503WhenRedisIsUnavailable() throws Exception {
        mockMvc.perform(get("/api/token-bucket/primary")
                        .header("X-Forwarded-For", "203.0.113.30"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value(containsString("Rate limit backend unavailable")))
                .andExpect(jsonPath("$.strategy").value("TOKEN_BUCKET"))
                .andExpect(jsonPath("$.key").value(
                        "rate_limit:token_bucket:DemoController.tokenBucketPrimaryEndpoint:ip:203.0.113.30:1:60000"
                ))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("FIXED_WINDOW should still work when Redis is unavailable")
    void fixedWindowShouldStillWorkWhenRedisIsUnavailable() throws Exception {
        mockMvc.perform(get("/api/strict"))
                .andExpect(status().isOk());
    }
}
