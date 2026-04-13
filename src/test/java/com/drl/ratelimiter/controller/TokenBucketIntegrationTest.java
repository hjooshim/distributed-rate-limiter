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

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ratelimit.identity.trust-forwarded-header=true"
)
@AutoConfigureMockMvc
class TokenBucketIntegrationTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(60));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void clearRedis() {
        try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.flushAll();
        }
    }

    @Test
    @DisplayName("TOKEN_BUCKET should be selected when requested")
    void tokenBucketAlgorithmShouldBeSelectedWhenRequested() throws Exception {
        mockMvc.perform(get("/api/token-bucket/primary"))
                .andExpect(status().isOk());

        Set<String> keys = redisTemplate.keys("rate_limit:token_bucket:*");
        assertThat(keys).isNotNull();
        assertThat(keys).isNotEmpty();
        assertThat(keys).allMatch(key -> key.startsWith("rate_limit:token_bucket:"));
    }

    @Test
    @DisplayName("Different clients should not share the same bucket")
    void differentClientsShouldNotShareABucket() throws Exception {
        mockMvc.perform(get("/api/token-bucket/primary")
                        .header("X-Forwarded-For", "203.0.113.10, 10.0.0.1"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/token-bucket/primary")
                        .header("X-Forwarded-For", "203.0.113.10, 10.0.0.1"))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/api/token-bucket/primary")
                        .header("X-Forwarded-For", "203.0.113.11"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("One client should get independent buckets per endpoint")
    void oneClientShouldGetIndependentBucketsPerEndpoint() throws Exception {
        mockMvc.perform(get("/api/token-bucket/primary")
                        .header("X-Forwarded-For", "203.0.113.20"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/token-bucket/primary")
                        .header("X-Forwarded-For", "203.0.113.20"))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/api/token-bucket/secondary")
                        .header("X-Forwarded-For", "203.0.113.20"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Trusted forwarded header should use the first trimmed client value")
    void trustedForwardedHeaderShouldUseTheFirstTrimmedClientValue() throws Exception {
        mockMvc.perform(get("/api/token-bucket/primary")
                        .header("X-Forwarded-For", " 203.0.113.55 , 10.0.0.1 ")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.99");
                            return request;
                        }))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/token-bucket/primary")
                        .header("X-Forwarded-For", "203.0.113.55")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.98");
                            return request;
                        }))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Remote address fallback should work when X-Forwarded-For is missing")
    void remoteAddressFallbackShouldWorkWhenXForwardedForIsMissing() throws Exception {
        mockMvc.perform(get("/api/token-bucket/primary")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.25");
                            return request;
                        }))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/token-bucket/primary")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.25");
                            return request;
                        }))
                .andExpect(status().isTooManyRequests());
    }
}
