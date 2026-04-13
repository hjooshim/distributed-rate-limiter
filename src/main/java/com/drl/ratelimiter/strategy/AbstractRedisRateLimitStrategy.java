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

public abstract class AbstractRedisRateLimitStrategy extends AbstractRateLimitStrategy {

    private static final String REDIS_KEY_PREFIX = "rate_limit";

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    protected AbstractRedisRateLimitStrategy(String name, StringRedisTemplate redisTemplate, String keyPrefix) {
        super(name);
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        this.keyPrefix = keyPrefix.trim();
    }

    protected final String buildRedisKey(String logicalKey) {
        return REDIS_KEY_PREFIX + ":" + keyPrefix + ":" + logicalKey;
    }

    protected final long ttlMs(long windowMs) {
        return Math.max(windowMs * 2, 1_000L);
    }

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
