package com.drl.ratelimiter.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.drl.ratelimiter.annotation.RateLimit;
import com.drl.ratelimiter.controller.DemoController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

/**
 * ============================================================
 * UNIT TESTS - StrategyRegistry startup validation
 * ============================================================
 *
 * Uses ApplicationContextRunner to verify that strategy wiring fails fast
 * when the application is misconfigured.
 *
 * Coverage:
 *   - duplicate strategy names should stop startup
 *   - unknown algorithm names in @RateLimit should stop startup
 *   - valid FIXED_WINDOW / TOKEN_BUCKET combinations should start cleanly
 */
class StrategyRegistryValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    // ---------------------------------------------------------
    // Duplicate strategy-name validation
    // ---------------------------------------------------------

    @Test
    @DisplayName("Startup should fail when two strategies register the same name")
    void shouldFailStartupWhenStrategyNamesAreDuplicated() {
        contextRunner
                .withUserConfiguration(StrategyRegistry.class, DuplicateStrategiesConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Duplicate rate limit strategy name 'DUPLICATE'");
                });
    }

    // ---------------------------------------------------------
    // Unknown algorithm-name validation
    // ---------------------------------------------------------

    @Test
    @DisplayName("Startup should fail when @RateLimit references an unknown algorithm")
    void shouldFailStartupWhenRateLimitAnnotationReferencesUnknownAlgorithm() {
        contextRunner
                .withUserConfiguration(
                        StrategyRegistry.class,
                        RateLimitAlgorithmValidator.class,
                        UnknownAlgorithmConfig.class
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCauseOf(context.getStartupFailure()).getMessage())
                            .contains("MISSING_STRATEGY")
                            .contains("unknownAlgorithmEndpoint");
                });
    }

    // ---------------------------------------------------------
    // Happy-path validation
    // ---------------------------------------------------------

    @Test
    @DisplayName("Startup should succeed when every referenced algorithm exists")
    void shouldAcceptExistingFixedWindowAndTokenBucketAlgorithms() {
        contextRunner
                .withUserConfiguration(
                        StrategyRegistry.class,
                        RateLimitAlgorithmValidator.class,
                        KnownAlgorithmsConfig.class
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DuplicateStrategiesConfig {

        @Bean
        RateLimitStrategy duplicateStrategyOne() {
            return new NamedStrategy("DUPLICATE");
        }

        @Bean
        RateLimitStrategy duplicateStrategyTwo() {
            return new NamedStrategy("DUPLICATE");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class UnknownAlgorithmConfig {

        @Bean
        RateLimitStrategy fixedWindowStrategy() {
            return new NamedStrategy("FIXED_WINDOW");
        }

        @Bean
        UnknownAlgorithmEndpoint unknownAlgorithmEndpoint() {
            return new UnknownAlgorithmEndpoint();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class KnownAlgorithmsConfig {

        @Bean
        RateLimitStrategy fixedWindowStrategy() {
            return new NamedStrategy("FIXED_WINDOW");
        }

        @Bean
        RateLimitStrategy tokenBucketStrategy() {
            return new NamedStrategy("TOKEN_BUCKET");
        }

        @Bean
        RateLimitStrategy slidingWindowStrategy() {
            return new NamedStrategy("SLIDING_WINDOW");
        }

        @Bean
        DemoController demoController() {
            return new DemoController();
        }
    }

    static class UnknownAlgorithmEndpoint {

        @RateLimit(limit = 1, windowMs = 1_000, algorithm = "MISSING_STRATEGY")
        void unknownAlgorithmEndpoint() {
        }
    }

    static class NamedStrategy implements RateLimitStrategy {

        private final String name;

        NamedStrategy(String name) {
            this.name = name;
        }

        @Override
        public RateLimitDecision evaluate(String key, int limit, long windowMs) {
            return RateLimitDecision.allowed();
        }

        @Override
        public String getName() {
            return name;
        }
    }

    private static Throwable rootCauseOf(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
