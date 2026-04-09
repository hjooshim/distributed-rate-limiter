package com.drl.ratelimiter.strategy;

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

    protected final boolean executeAllowDenyScript(
            DefaultRedisScript<Long> script,
            String logicalKey,
            String... args
    ) {
        Long result = redisTemplate.execute(
                script,
                Collections.singletonList(buildRedisKey(logicalKey)),
                args
        );
        return Long.valueOf(1L).equals(result);
    }

    protected static DefaultRedisScript<Long> loadScript(String classpathLocation) {
        try (InputStream inputStream = new ClassPathResource(classpathLocation).getInputStream()) {
            String scriptText = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setResultType(Long.class);
            script.setScriptText(scriptText);
            return script;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Redis Lua script: " + classpathLocation, e);
        }
    }
}
