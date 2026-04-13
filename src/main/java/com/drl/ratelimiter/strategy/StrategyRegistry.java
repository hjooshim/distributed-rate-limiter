package com.drl.ratelimiter.strategy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

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

    private final Map<String, RateLimitStrategy> strategies;

    /**
     * Constructor injection — Spring passes in all RateLimitStrategy beans.
     *
     * @param strategyList All beans implementing RateLimitStrategy,
     *                     collected automatically by Spring.
     */
    public StrategyRegistry(List<RateLimitStrategy> strategyList) {
        Map<String, RateLimitStrategy> resolvedStrategies = new HashMap<>();
        for (RateLimitStrategy strategy : strategyList) {
            String name = strategy.getName();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("Rate limit strategy names must not be blank");
            }
            RateLimitStrategy previous = resolvedStrategies.putIfAbsent(name, strategy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate rate limit strategy name '" + name + "'");
            }
        }
        this.strategies = Map.copyOf(resolvedStrategies);
    }

    /**
     * Look up a strategy by name.
     *
     * @param name Strategy name, e.g. "FIXED_WINDOW", "TOKEN_BUCKET"
     * @return The matching strategy.
     */
    public RateLimitStrategy get(String name) {
        RateLimitStrategy strategy = strategies.get(name);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown rate limit strategy '" + name + "'");
        }
        return strategy;
    }

    public boolean contains(String name) {
        return strategies.containsKey(name);
    }

    public Set<String> names() {
        return strategies.keySet();
    }
}
