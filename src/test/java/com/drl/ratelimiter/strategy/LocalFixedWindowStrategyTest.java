package com.drl.ratelimiter.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * ======================= =====================================
 * UNIT TESTS — LocalFixedWindowStrategy
 * ============================================================
 *
 * These tests cover:
 *   1. Basic allow/reject behavior
 *   2. Window reset (new window = fresh counter)
 *   3. Thread safety under concurrent access
 *
 * TESTING PHILOSOPHY:
 *   We test the INTERFACE (RateLimitStrategy), not the concrete class.
 *   This means the same tests could run against any implementation —
 *   LSP (Liskov Substitution Principle) in action.
 */
class LocalFixedWindowStrategyTest {

    // Test against the interface, not the concrete type
    private RateLimitStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new LocalFixedWindowStrategy();
    }

    // ─────────────────────────────────────────────
    // BASIC BEHAVIOR TESTS
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("Should allow requests within the limit")
    void shouldAllowRequestsWithinLimit() {
        // GIVEN: limit is 3
        // WHEN: we make 3 requests
        // THEN: all should be allowed

        assertThat(strategy.isAllowed("test-key", 3, 60_000)).isTrue();
        assertThat(strategy.isAllowed("test-key", 3, 60_000)).isTrue();
        assertThat(strategy.isAllowed("test-key", 3, 60_000)).isTrue();
    }

    @Test
    @DisplayName("Should reject the request that exceeds the limit")
    void shouldRejectRequestExceedingLimit() {
        // GIVEN: limit is 3
        // WHEN: we make 4 requests
        // THEN: first 3 allowed, 4th rejected

        strategy.isAllowed("test-key", 3, 60_000); // 1 ✅
        strategy.isAllowed("test-key", 3, 60_000); // 2 ✅
        strategy.isAllowed("test-key", 3, 60_000); // 3 ✅

        boolean fourthRequest = strategy.isAllowed("test-key", 3, 60_000); // 4 ❌

        assertThat(fourthRequest).isFalse();
    }

    @Test
    @DisplayName("Different keys should have independent counters")
    void differentKeysShouldBeIndependent() {
        // GIVEN: limit is 2 for both keys
        // Using up key-A should not affect key-B

        strategy.isAllowed("key-A", 2, 60_000); // key-A: 1
        strategy.isAllowed("key-A", 2, 60_000); // key-A: 2 (at limit)

        // key-B should still be fresh
        boolean result = strategy.isAllowed("key-B", 2, 60_000);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Different window configurations should not share counters")
    void differentWindowConfigurationsShouldNotShareCounters() {
        long now = System.currentTimeMillis();
        long largerWindow = now + 10_000;
        long largestWindow = now + 20_000;

        assertThat(strategy.isAllowed("shared-config-key", 1, largerWindow)).isTrue();
        assertThat(strategy.isAllowed("shared-config-key", 2, largestWindow)).isTrue();
        assertThat(strategy.isAllowed("shared-config-key", 2, largestWindow)).isTrue();
    }

    @Test
    @DisplayName("Limit of 1 should allow exactly 1 request")
    void limitOfOneShouldAllowExactlyOneRequest() {
        assertThat(strategy.isAllowed("single-key", 1, 60_000)).isTrue();
        assertThat(strategy.isAllowed("single-key", 1, 60_000)).isFalse();
    }

    @Test
    @DisplayName("New time window should reset the counter")
    void newWindowShouldResetCounter() throws InterruptedException {
        // GIVEN: a very short window of 100ms, limit 2
        strategy.isAllowed("window-key", 2, 100); // 1
        strategy.isAllowed("window-key", 2, 100); // 2 (at limit)

        boolean overLimit = strategy.isAllowed("window-key", 2, 100);
        assertThat(overLimit).isFalse(); // Rejected ❌

        // WHEN: wait for the window to expire
        Thread.sleep(150); // wait > 100ms

        // THEN: a new window starts, counter resets
        boolean afterReset = strategy.isAllowed("window-key", 2, 100);
        assertThat(afterReset).isTrue(); // Allowed again ✅
    }

    @Test
    @DisplayName("Rejected decisions should report the remaining time in the active window")
    void rejectedDecisionsShouldReportRemainingTimeInActiveWindow() throws InterruptedException {
        LocalFixedWindowStrategy concreteStrategy = new LocalFixedWindowStrategy();
        long windowMs = System.currentTimeMillis() + 2_000;

        assertThat(concreteStrategy.evaluate("retry-after-key", 1, windowMs).isAllowed()).isTrue();

        Thread.sleep(1_200);

        RateLimitDecision decision = concreteStrategy.evaluate("retry-after-key", 1, windowMs);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getRetryAfterSeconds()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Cleanup should remove expired counters even under high-cardinality low-volume traffic")
    void cleanupShouldHandleHighCardinalityLowVolumeTraffic() throws Exception {
        LocalFixedWindowStrategy concreteStrategy = new LocalFixedWindowStrategy();

        concreteStrategy.isAllowed("expired-key", 1, 50);
        // Cleanup retains the immediately previous window to avoid rollover
        // races, so this sleep must push the old entry at least two windows
        // behind the current time to make removal deterministic.
        Thread.sleep(130);

        for (int i = 0; i < 99; i++) {
            concreteStrategy.isAllowed("fresh-key-" + i, 1, 60_000);
        }

        assertThat(counterKeys(concreteStrategy))
                .noneMatch(key -> key.startsWith("expired-key:"));
    }

    @Test
    @DisplayName("Cleanup should keep the immediately previous window to avoid rollover races")
    void cleanupShouldKeepPreviousWindowToAvoidRolloverRaces() throws Exception {
        LocalFixedWindowStrategy concreteStrategy = new LocalFixedWindowStrategy();

        putCounter(concreteStrategy, "late-request:100:1");
        putCounter(concreteStrategy, "expired-request:100:0");

        invokeCleanup(concreteStrategy, 250);

        assertThat(counterKeys(concreteStrategy))
                .contains("late-request:100:1")
                .doesNotContain("expired-request:100:0");
    }

    // ─────────────────────────────────────────────
    // CONCURRENCY TEST
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("Should not exceed limit under concurrent access")
    void shouldNotExceedLimitUnderConcurrentAccess() throws InterruptedException {
        /*
         * WHY THIS TEST MATTERS:
         * Without AtomicLong, two threads could both read count=4,
         * both check 4 < 5 = true, both increment to 5, and we'd
         * allow 6 requests instead of 5. This is the race condition.
         *
         * This test simulates 20 threads all hammering the same
         * key at the same time, and verifies exactly 5 get through.
         *
         * CountDownLatch trick:
         *   - startLatch: makes all threads wait until they're ALL ready,
         *     then releases them simultaneously for maximum contention.
         *   - doneLatch: main thread waits until all threads finish.
         */
        int limit = 5;
        int threadCount = 20;

        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        // This latch holds all threads until we call countDown()
        // so they all start at the exact same moment
        CountDownLatch startLatch = new CountDownLatch(1);

        // This latch counts down once per thread; main thread waits on it
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for the start signal
                    boolean allowed = strategy.isAllowed(
                            "concurrent-key", limit, 60_000
                    );
                    if (allowed) allowedCount.incrementAndGet();
                    else         rejectedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown(); // Signal this thread is done
                }
            });
        }

        startLatch.countDown(); // 🚀 Release all threads simultaneously
        doneLatch.await();      // Wait for all threads to finish
        executor.shutdown();

        System.out.println("Allowed: " + allowedCount.get()
                + ", Rejected: " + rejectedCount.get());

        // CRITICAL ASSERTION:
        // Exactly `limit` requests should have been allowed — not more.
        assertThat(allowedCount.get())
                .as("Exactly %d requests should be allowed", limit)
                .isEqualTo(limit);

        assertThat(rejectedCount.get())
                .as("The rest should be rejected")
                .isEqualTo(threadCount - limit);
    }

    @Test
    @DisplayName("getName() should return FIXED_WINDOW")
    void getNameShouldReturnCorrectIdentifier() {
        assertThat(strategy.getName()).isEqualTo("FIXED_WINDOW");
    }

    @Test
    @DisplayName("Reset should clear cleanup cadence state")
    void resetShouldClearCleanupCadenceState() throws Exception {
        LocalFixedWindowStrategy concreteStrategy = new LocalFixedWindowStrategy();

        concreteStrategy.isAllowed("reset-key", 10, 60_000);
        concreteStrategy.reset();

        assertThat(totalRequests(concreteStrategy)).isZero();
    }

    @RepeatedTest(10)
    @DisplayName("High Intensity Stress Test: 500 Threads Contention")
    void highIntensityStressTest() throws InterruptedException {
        // 参数配置：500个并发线程冲击100个配额
        int threadCount = 10000;
        int limit = 100;
        int windowMs = 60_000;
        String testKey = "stress-test-key-" + System.nanoTime(); // 保证每次重复测试 key 独立

        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // 使用固定线程池模拟真实并发
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 阻塞所有线程，等待"发令枪"
                    if (strategy.isAllowed(testKey, limit, windowMs)) {
                        allowedCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long startTime = System.nanoTime();
        startLatch.countDown(); // 🚀 模拟瞬间爆发流量

        // 设置最大等待时间，防止死锁
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        long endTime = System.nanoTime();

        executor.shutdown();

        // 性能数据计算
        long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
        double avgLatencyMs = (double) durationMs / threadCount;

        // 核心断言：在高并发下，通过的数量必须精确等于 limit
        assertThat(completed).as("Test should complete within timeout").isTrue();
        assertThat(allowedCount.get())
            .as("Allowed count should precisely match the limit under high contention")
            .isEqualTo(limit);

        System.out.printf("[Stress Test] Throughput: %d requests | Duration: %d ms | Avg Latency: %.4f ms\n",
            threadCount, durationMs, avgLatencyMs);
    }

    @SuppressWarnings("unchecked")
    private Iterable<String> counterKeys(LocalFixedWindowStrategy strategy)
            throws NoSuchFieldException, IllegalAccessException {
        Field countersField = LocalFixedWindowStrategy.class.getDeclaredField("counters");
        countersField.setAccessible(true);
        return ((Map<String, ?>) countersField.get(strategy)).keySet();
    }

    @SuppressWarnings("unchecked")
    private void putCounter(LocalFixedWindowStrategy strategy, String key)
            throws NoSuchFieldException, IllegalAccessException {
        Field countersField = LocalFixedWindowStrategy.class.getDeclaredField("counters");
        countersField.setAccessible(true);
        Map<String, Object> counters = (Map<String, Object>) countersField.get(strategy);
        counters.put(key, new LocalFixedWindowStrategy.WindowCounter());
    }

    private void invokeCleanup(LocalFixedWindowStrategy strategy, long currentTimeMs)
            throws Exception {
        Method cleanupMethod = LocalFixedWindowStrategy.class
                .getDeclaredMethod("cleanupOldWindows", long.class);
        cleanupMethod.setAccessible(true);
        cleanupMethod.invoke(strategy, currentTimeMs);
    }

    private long totalRequests(LocalFixedWindowStrategy strategy)
            throws NoSuchFieldException, IllegalAccessException {
        Field totalRequestsField = LocalFixedWindowStrategy.class
                .getDeclaredField("totalRequests");
        totalRequestsField.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicLong) totalRequestsField.get(strategy)).get();
    }
}
