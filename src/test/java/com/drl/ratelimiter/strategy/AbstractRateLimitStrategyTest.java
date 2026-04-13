package com.drl.ratelimiter.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AbstractRateLimitStrategyTest {

    @Test
    void shouldRejectBlankKey() {
        RateLimitStrategy strategy = new StubRateLimitStrategy("STUB");

        assertThatThrownBy(() -> strategy.isAllowed("   ", 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void shouldRejectNonPositiveLimit() {
        RateLimitStrategy strategy = new StubRateLimitStrategy("STUB");

        assertThatThrownBy(() -> strategy.isAllowed("test-key", 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void shouldRejectNonPositiveWindow() {
        RateLimitStrategy strategy = new StubRateLimitStrategy("STUB");

        assertThatThrownBy(() -> strategy.isAllowed("test-key", 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowMs");
    }

    @Test
    void shouldExposeConfiguredName() {
        RateLimitStrategy strategy = new StubRateLimitStrategy("STUB");

        assertThat(strategy.getName()).isEqualTo("STUB");
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> new StubRateLimitStrategy("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void shouldDelegateValidInputToConcreteImplementation() {
        StubRateLimitStrategy strategy = new StubRateLimitStrategy("STUB");

        boolean allowed = strategy.isAllowed("test-key", 1, 1);

        assertThat(allowed).isTrue();
        assertThat(strategy.wasCalled()).isTrue();
    }

    @Test
    void shouldNotDelegateWhenInputIsInvalid() {
        StubRateLimitStrategy strategy = new StubRateLimitStrategy("STUB");

        assertThatThrownBy(() -> strategy.isAllowed("", 1, 1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(strategy.wasCalled()).isFalse();
    }

    private static final class StubRateLimitStrategy extends AbstractRateLimitStrategy {

        private boolean called;

        private StubRateLimitStrategy(String name) {
            super(name);
        }

        @Override
        protected RateLimitDecision doEvaluate(String key, int limit, long windowMs) {
            called = true;
            return RateLimitDecision.allowed();
        }

        private boolean wasCalled() {
            return called;
        }
    }
}
