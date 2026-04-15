package com.drl.ratelimiter.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * ============================================================
 * UNIT TESTS - GlobalExceptionHandler
 * ============================================================
 *
 * Verifies the HTTP-shaping logic that turns rate-limit exceptions into the
 * JSON responses consumed by clients.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("429 responses should preserve Retry-After while keeping the JSON body stable")
    void shouldUseStrategyProvidedRetryAfterWithoutChangingBodyShape() {
        ResponseEntity<Map<String, Object>> response = handler.handleRateLimitExceeded(
                new RateLimitExceededException("demo-key", 3, 10_000, 2)
        );

        // Retry-After must be exposed as an HTTP header, while the JSON body
        // keeps the existing response contract.
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(response.getBody())
                .containsEntry("status", 429)
                .containsEntry("error", "Too Many Requests")
                .containsEntry("key", "demo-key")
                .containsEntry("limit", 3)
                .containsEntry("windowMs", 10_000L)
                .containsKey("message")
                .containsKey("timestamp")
                .doesNotContainKey("retryAfterSeconds");
    }
}
