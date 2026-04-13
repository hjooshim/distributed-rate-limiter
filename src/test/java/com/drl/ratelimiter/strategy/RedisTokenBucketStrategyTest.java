package com.drl.ratelimiter.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisTokenBucketStrategyTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(60));

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisTokenBucketStrategy strategy;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                redis.getHost(),
                redis.getMappedPort(6379)
        );
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        strategy = new RedisTokenBucketStrategy(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        if (redisTemplate != null) {
            try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
                connection.flushAll();
            }
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("Should allow requests while tokens remain")
    void shouldAllowRequestsWhileTokensRemain() {
        String key = uniqueKey("allow");

        assertThat(strategy.isAllowed(key, 3, 1_000)).isTrue();
        assertThat(strategy.isAllowed(key, 3, 1_000)).isTrue();
    }

    @Test
    @DisplayName("Should reject requests when bucket is empty")
    void shouldRejectRequestsWhenBucketIsEmpty() {
        String key = uniqueKey("empty");

        assertThat(strategy.isAllowed(key, 2, 1_000)).isTrue();
        assertThat(strategy.isAllowed(key, 2, 1_000)).isTrue();
        assertThat(strategy.isAllowed(key, 2, 1_000)).isFalse();
    }

    @Test
    @DisplayName("Should refill tokens after enough time passes")
    void shouldRefillTokensAfterEnoughTimePasses() throws InterruptedException {
        String key = uniqueKey("refill");

        assertThat(strategy.isAllowed(key, 1, 500)).isTrue();
        assertThat(strategy.isAllowed(key, 1, 500)).isFalse();

        Thread.sleep(650);

        assertThat(strategy.isAllowed(key, 1, 500)).isTrue();
    }

    @Test
    @DisplayName("Partial refill should restore only the tokens earned over time")
    void partialRefillShouldRestoreOnlyEarnedTokens() throws InterruptedException {
        String key = uniqueKey("partial-refill");

        assertThat(strategy.isAllowed(key, 2, 1_000)).isTrue();
        assertThat(strategy.isAllowed(key, 2, 1_000)).isTrue();
        assertThat(strategy.isAllowed(key, 2, 1_000)).isFalse();

        Thread.sleep(650);

        assertThat(strategy.isAllowed(key, 2, 1_000)).isTrue();
        assertThat(strategy.isAllowed(key, 2, 1_000)).isFalse();
    }

    @Test
    @DisplayName("Rejected decisions should report time until the next token is available")
    void rejectedDecisionsShouldReportTimeUntilNextTokenIsAvailable() throws InterruptedException {
        String key = uniqueKey("retry-after");

        assertThat(strategy.evaluate(key, 2, 4_000).isAllowed()).isTrue();
        assertThat(strategy.evaluate(key, 2, 4_000).isAllowed()).isTrue();

        Thread.sleep(1_200);

        RateLimitDecision decision = strategy.evaluate(key, 2, 4_000);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getRetryAfterSeconds()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Different keys should be independent")
    void differentKeysShouldBeIndependent() {
        String keyA = uniqueKey("key-a");
        String keyB = uniqueKey("key-b");

        assertThat(strategy.isAllowed(keyA, 1, 1_000)).isTrue();
        assertThat(strategy.isAllowed(keyA, 1, 1_000)).isFalse();

        assertThat(strategy.isAllowed(keyB, 1, 1_000)).isTrue();
    }

    @Test
    @DisplayName("Different configurations should not share the same bucket state")
    void differentConfigurationsShouldNotShareTheSameBucketState() {
        String key = uniqueKey("config-scope");

        assertThat(strategy.isAllowed(key, 1, 1_000)).isTrue();
        assertThat(strategy.isAllowed(key, 1, 1_000)).isFalse();

        assertThat(strategy.isAllowed(key, 2, 2_000)).isTrue();
        assertThat(strategy.isAllowed(key, 2, 2_000)).isTrue();
    }

    @Test
    @DisplayName("getName should return TOKEN_BUCKET")
    void shouldExposeStrategyName() {
        assertThat(strategy.getName()).isEqualTo("TOKEN_BUCKET");
    }

    @Test
    @DisplayName("Concurrent requests should not exceed capacity")
    void concurrentRequestsShouldNotExceedCapacity() throws InterruptedException {
        String key = uniqueKey("concurrent");
        int capacity = 5;
        long windowMs = 60_000;
        int threads = 20;

        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    if (strategy.isAllowed(key, capacity, windowMs)) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(allowed.get()).isEqualTo(capacity);
        assertThat(rejected.get()).isEqualTo(threads - capacity);
    }

    private static String uniqueKey(String suffix) {
        return suffix + ":" + UUID.randomUUID();
    }
}
