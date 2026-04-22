package com.drl.ratelimiter.strategy;

import com.drl.ratelimiter.exception.RateLimitBackendUnavailableException;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.Collections;
import java.util.Objects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.StreamUtils;

/**
 * Base class for Redis-backed strategies that share key construction and Lua execution.
 * It keeps Redis integration concerns out of concrete algorithms so each subclass can focus on its policy logic.
 */
public abstract class AbstractRedisRateLimitStrategy extends AbstractRateLimitStrategy {

    private static final String REDIS_KEY_PREFIX = "rate_limit";

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    /**
     * Creates a Redis-backed strategy with a logical Redis key prefix.
     *
     * @param name stable algorithm name used for registry lookup
     * @param redisTemplate Redis client used to execute Lua scripts
     * @param keyPrefix algorithm-specific key namespace under {@code rate_limit}
     */
    protected AbstractRedisRateLimitStrategy(String name, StringRedisTemplate redisTemplate, String keyPrefix) {
        super(name);
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        this.keyPrefix = keyPrefix.trim();
    }

    /**
     * Builds the physical Redis key for a logical rate-limit key.
     *
     * @param logicalKey application-level key
     * @return Redis key including the shared and algorithm-specific prefixes
     */
    protected final String buildRedisKey(String logicalKey) {
        return REDIS_KEY_PREFIX + ":" + keyPrefix + ":" + logicalKey;
    }

    /**
     * Computes the TTL for Redis state. The TTL is longer than the logical window so idle state
     * survives long enough for refill calculations and then expires automatically.
     *
     * @param windowMs logical policy window in milliseconds
     * @return Redis TTL in milliseconds
     */
    protected final long ttlMs(long windowMs) {
        return Math.max(windowMs * 2, 1_000L);
    }

    /**
     * Executes a Lua script that returns a compact {@code allowed:retryAfterSeconds} decision.
     *
     * @param script Lua script to execute
     * @param logicalKey application-level rate-limit key
     * @param args script arguments
     * @return parsed rate-limit decision
     */
    protected final RateLimitDecision executeDecisionScript(
            DefaultRedisScript<String> script,
            String logicalKey,
            String... args
    ) {
        String redisKey = buildRedisKey(logicalKey);
        try {
            String result = redisTemplate.execute(
                    script,
                    Collections.singletonList(redisKey),
                    (Object[]) args
            );
            if (result == null) {
                throw new RateLimitBackendUnavailableException(
                        getName(),
                        redisKey,
                        new IllegalStateException("Redis script returned null")
                );
            }
            return parseDecision(result, redisKey);
        } catch (RateLimitBackendUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RateLimitBackendUnavailableException(getName(), redisKey, exception);
        }
    }

    /**
     * Loads a Lua script from the classpath into a Spring Redis script wrapper.
     *
     * @param classpathLocation classpath location of the Lua script
     * @param resultType Java type returned by the script
     * @param <T> script result type
     * @return configured Redis script
     */
    protected static <T> DefaultRedisScript<T> loadScript(String classpathLocation, Class<T> resultType) {
        try (InputStream inputStream = new ClassPathResource(classpathLocation).getInputStream()) {
            String scriptText = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
            DefaultRedisScript<T> script = new DefaultRedisScript<>();
            script.setResultType(resultType);
            script.setScriptText(scriptText);
            return script;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Redis Lua script: " + classpathLocation, e);
        }
    }

    private RateLimitDecision parseDecision(String result, String redisKey) {
        String[] parts = result.split(":");
        if (parts.length != 2) {
            throw new RateLimitBackendUnavailableException(
                    getName(),
                    redisKey,
                    new IllegalStateException("Malformed Redis decision '" + result + "'")
            );
        }

        try {
            long allowedFlag = Long.parseLong(parts[0]);
            long retryAfterSeconds = Long.parseLong(parts[1]);
            if (allowedFlag == 1L) {
                return RateLimitDecision.allowed();
            }
            return RateLimitDecision.rejected(retryAfterSeconds);
        } catch (NumberFormatException exception) {
            throw new RateLimitBackendUnavailableException(
                    getName(),
                    redisKey,
                    new IllegalStateException("Malformed Redis decision '" + result + "'", exception)
            );
        }
    }
}
