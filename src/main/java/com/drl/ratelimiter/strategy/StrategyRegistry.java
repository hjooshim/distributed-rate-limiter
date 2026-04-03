package com.drl.ratelimiter.strategy;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ============================================================
 * FACTORY / REGISTRY PATTERN
 * ============================================================
 *
 * PURPOSE:
 *   Centralizes the lookup of RateLimitStrategy implementations.
 *   The AOP Aspect asks this registry: "give me the strategy
 *   named TOKEN_BUCKET" — it doesn't know or care about the
 *   concrete class behind that name.
 *
 * HOW IT WORKS (Spring magic):
 *   Spring automatically collects ALL beans that implement
 *   RateLimitStrategy and injects them as a List<RateLimitStrategy>.
 *   We then build a name → instance map from that list.
 *
 *   Right now there's only LocalFixedWindowStrategy.
 *   In Week 2, when we add RedisSlidingWindowStrategy, Spring
 *   will automatically include it in the injected list —
 *   zero changes needed here. This is the Open-Closed Principle.
 *
 * DEPENDENCY INVERSION:
 *   This class depends on the RateLimitStrategy INTERFACE,
 *   not on any concrete class. The concrete implementations
 *   are wired by Spring at startup time.
 */
@Component
public class StrategyRegistry {

    private final Map<String, RateLimitStrategy> strategies = new HashMap<>();

    // Fallback used when the requested strategy name doesn't exist.
    // Fail-open: returns true (allow) so the app doesn't crash if
    // someone misspells an algorithm name.
    private final RateLimitStrategy fallback;

    /**
     * Constructor injection — Spring passes in all RateLimitStrategy beans.
     *
     * @param strategyList All beans implementing RateLimitStrategy,
     *                     collected automatically by Spring.
     */
    public StrategyRegistry(List<RateLimitStrategy> strategyList) {
        for (RateLimitStrategy strategy : strategyList) {
            strategies.put(strategy.getName(), strategy);
        }
        // Default fallback: allow everything (fail-open behavior)
        this.fallback = (key, limit, windowMs) -> true;
    }

    /**
     * Look up a strategy by name.
     *
     * @param name Strategy name, e.g. "FIXED_WINDOW", "TOKEN_BUCKET"
     * @return The matching strategy, or the fail-open fallback if not found.
     */
    public RateLimitStrategy get(String name) {
        return strategies.getOrDefault(name, fallback);
    }
}
