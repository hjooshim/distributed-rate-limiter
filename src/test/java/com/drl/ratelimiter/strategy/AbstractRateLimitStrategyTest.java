package com.drl.ratelimiter.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ============================================================
 * UNIT TESTS - AbstractRateLimitStrategy shared guardrails
 * ============================================================
 *
 * Verifies the behavior provided by the abstract base class itself:
 *
 *   - shared input validation for key / limit / windowMs
 *   - stable name exposure through getName()
 *   - delegation to doEvaluate() only after validation succeeds
 *
 * The stub strategy keeps the tests focused on the template-method behavior
 * instead of any concrete rate-limiting algorithm.
 */
class AbstractRateLimitStrategyTest {

    // ---------------------------------------------------------
    // Shared input validation
    // ---------------------------------------------------------

    @Test
    @DisplayName("Blank keys should be rejected before concrete evaluation runs")
    void shouldRejectBlankKey() {
        RateLimitStrategy strategy = new StubRateLimitStrategy("STUB");

        assertThatThrownBy(() -> strategy.isAllowed("   ", 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    @DisplayName("Non-positive limits should be rejected before concrete evaluation runs")
    void shouldRejectNonPositiveLimit() {
        RateLimitStrategy strategy = new StubRateLimitStrategy("STUB");

        assertThatThrownBy(() -> strategy.isAllowed("test-key", 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("Non-positive windows should be rejected before concrete evaluation runs")
    void shouldRejectNonPositiveWindow() {
        RateLimitStrategy strategy = new StubRateLimitStrategy("STUB");

        assertThatThrownBy(() -> strategy.isAllowed("test-key", 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowMs");
    }

    // ---------------------------------------------------------
    // Naming behavior
    // ---------------------------------------------------------

    @Test
    @DisplayName("Configured strategy names should be returned unchanged")
    void shouldExposeConfiguredName() {
        RateLimitStrategy strategy = new StubRateLimitStrategy("STUB");

        assertThat(strategy.getName()).isEqualTo("STUB");
    }

    @Test
    @DisplayName("Blank strategy names should be rejected at construction time")
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> new StubRateLimitStrategy("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    // ---------------------------------------------------------
    // Template-method delegation
    // ---------------------------------------------------------

    @Test
    @DisplayName("Valid input should delegate to the concrete implementation")
    void shouldDelegateValidInputToConcreteImplementation() {
        StubRateLimitStrategy strategy = new StubRateLimitStrategy("STUB");

        boolean allowed = strategy.isAllowed("test-key", 1, 1);

        assertThat(allowed).isTrue();
        assertThat(strategy.wasCalled()).isTrue();
    }

    @Test
    @DisplayName("Invalid input should stop before the concrete implementation is called")
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
