package com.drl.ratelimiter.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts rate-limit rejections into HTTP 429 responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Builds the JSON response for a rejected request.
     *
     * @param exception rejection raised by the rate limiter
     * @return HTTP 429 response body
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(
            RateLimitExceededException exception
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("error", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
        body.put("message", exception.getMessage());
        body.put("key", exception.getKey());
        body.put("limit", exception.getLimit());
        body.put("windowMs", exception.getWindowMs());
        body.put("timestamp", Instant.now().toString());

        long retryAfterSeconds = Math.max(1L, exception.getWindowMs() / 1000L);

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(retryAfterSeconds))
                .body(body);
    }
}
