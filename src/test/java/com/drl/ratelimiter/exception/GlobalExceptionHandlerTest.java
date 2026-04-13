package com.drl.ratelimiter.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldUseStrategyProvidedRetryAfterWithoutChangingBodyShape() {
        ResponseEntity<Map<String, Object>> response = handler.handleRateLimitExceeded(
                new RateLimitExceededException("demo-key", 3, 10_000, 2)
        );

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
