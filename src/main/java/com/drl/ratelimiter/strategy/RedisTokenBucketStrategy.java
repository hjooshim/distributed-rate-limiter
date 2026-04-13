package com.drl.ratelimiter.strategy;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Distributed token-bucket strategy backed by Redis state and a Lua decision script.
 */
@Component
public class RedisTokenBucketStrategy extends AbstractRedisRateLimitStrategy {

    private static final DefaultRedisScript<String> TOKEN_BUCKET_SCRIPT =
            loadScript("scripts/token_bucket.lua", String.class);

    /**
     * Creates the token-bucket strategy.
     *
     * @param redisTemplate Redis client used to store bucket state
     */
    public RedisTokenBucketStrategy(StringRedisTemplate redisTemplate) {
        super("TOKEN_BUCKET", redisTemplate, "token_bucket");
    }

    @Override
    protected RateLimitDecision doEvaluate(String key, int limit, long windowMs) {
        long ttl = ttlMs(windowMs);
        String policyScopedKey = key + ":" + limit + ":" + windowMs;
        return executeDecisionScript(
                TOKEN_BUCKET_SCRIPT,
                policyScopedKey,
                String.valueOf(limit),
                String.valueOf(windowMs),
                "1",
                String.valueOf(ttl)
        );
    }
}
