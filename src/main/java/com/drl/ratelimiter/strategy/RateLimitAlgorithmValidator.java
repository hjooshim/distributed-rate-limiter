package com.drl.ratelimiter.strategy;

import com.drl.ratelimiter.annotation.RateLimit;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * ============================================================
 * STARTUP VALIDATION - Fail Fast on Unknown Algorithms
 * ============================================================
 *
 * PURPOSE:
 *   Verifies at application startup that every {@link RateLimit} annotation
 *   references an algorithm name that actually exists in the strategy
 *   registry.
 *
 * WHY THIS EXISTS:
 *   Without this check, a typo like {@code TOKEN_BUKET} would compile and the
 *   application would start, but the failure would only appear later when that
 *   endpoint receives traffic. That is a poor failure mode.
 *
 *   This validator moves the failure to startup time instead:
 *     - faster feedback for developers
 *     - no latent production surprise
 *     - one clear error listing every invalid algorithm it found
 *
 * HOW IT WORKS:
 *   As part of the Spring lifecycle, this component:
 *     1. Iterates over bean definitions in the application context
 *     2. Resolves each bean's user class (not proxy wrapper)
 *     3. Finds methods annotated with {@link RateLimit}
 *     4. Checks whether each annotation's algorithm exists in the registry
 *     5. Fails startup if any unknown names are discovered
 */
@Component
public class RateLimitAlgorithmValidator implements SmartLifecycle {

    private final ListableBeanFactory beanFactory;
    private final StrategyRegistry strategyRegistry;
    private volatile boolean running;

    /**
     * Creates the startup validator for configured rate-limit algorithms.
     *
     * @param beanFactory bean factory used to inspect application beans
     * @param strategyRegistry registry used to verify configured algorithm names
     */
    public RateLimitAlgorithmValidator(
            ListableBeanFactory beanFactory,
            StrategyRegistry strategyRegistry
    ) {
        this.beanFactory = beanFactory;
        this.strategyRegistry = strategyRegistry;
    }

    @Override
    public void start() {
        // Collect every invalid configuration first so startup fails with one
        // aggregated error message instead of one method at a time.
        List<String> invalidAlgorithms = new ArrayList<>();

        // Step 1: Inspect every bean known to the application context.
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> beanType = beanFactory.getType(beanName);
            // Some bean definitions may not expose a resolvable runtime type.
            // Those cannot contribute annotated methods, so we skip them.
            if (beanType == null) {
                continue;
            }

            // Step 2: Unwrap Spring proxies back to the underlying user class.
            // This ensures method introspection sees the real application
            // methods rather than proxy-generated wrapper types.
            Class<?> userClass = ClassUtils.getUserClass(beanType);

            // Step 3: Find every method carrying @RateLimit.
            // findMergedAnnotation handles composed/meta-annotations as well,
            // so this validator still works if @RateLimit is wrapped later in
            // a custom annotation.
            var rateLimitedMethods = MethodIntrospector.selectMethods(
                    userClass,
                    (MethodIntrospector.MetadataLookup<RateLimit>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, RateLimit.class)
            );

            // Step 4: Verify each discovered algorithm name against the
            // registry built from the available strategy beans.
            for (Method method : rateLimitedMethods.keySet()) {
                RateLimit rateLimit = rateLimitedMethods.get(method);
                if (!strategyRegistry.contains(rateLimit.algorithm())) {
                    invalidAlgorithms.add(
                            userClass.getSimpleName()
                                    + "#"
                                    + method.getName()
                                    + " -> "
                                    + rateLimit.algorithm()
                    );
                }
            }
        }

        // Step 5: Fail fast if any method references an unknown strategy name.
        if (!invalidAlgorithms.isEmpty()) {
            throw new IllegalStateException(
                    "Unknown rate limit algorithms configured: " + String.join(", ", invalidAlgorithms)
            );
        }

        // Mark the lifecycle component as started only after validation passes.
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Run as early as possible so invalid configuration stops the
        // application before later lifecycle components do more work.
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        // SmartLifecycle requires the asynchronous-style callback variant.
        // This implementation has no background work, so stop immediately
        // and then notify Spring that shutdown can continue.
        stop();
        callback.run();
    }
}
