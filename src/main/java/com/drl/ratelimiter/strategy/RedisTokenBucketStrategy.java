package com.drl.ratelimiter.strategy;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisTokenBucketStrategy extends AbstractRedisRateLimitStrategy {

    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT =
            loadScript("scripts/token_bucket.lua");

    public RedisTokenBucketStrategy(StringRedisTemplate redisTemplate) {
        super("TOKEN_BUCKET", redisTemplate, "token_bucket");
    }

    @Override
    protected boolean doIsAllowed(String key, int limit, long windowMs) {
        long ttl = ttlMs(windowMs);
        String policyScopedKey = key + ":" + limit + ":" + windowMs;
        return executeAllowDenyScript(
                TOKEN_BUCKET_SCRIPT,
                policyScopedKey,
                String.valueOf(limit),
                String.valueOf(windowMs),
                "1",
                String.valueOf(ttl)
        );
    }
}
