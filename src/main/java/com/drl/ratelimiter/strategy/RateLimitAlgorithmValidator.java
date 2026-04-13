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
 * Validates that every {@link RateLimit} annotation references a registered strategy.
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
        List<String> invalidAlgorithms = new ArrayList<>();

        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> beanType = beanFactory.getType(beanName);
            if (beanType == null) {
                continue;
            }

            Class<?> userClass = ClassUtils.getUserClass(beanType);
            var rateLimitedMethods = MethodIntrospector.selectMethods(
                    userClass,
                    (MethodIntrospector.MetadataLookup<RateLimit>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, RateLimit.class)
            );

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

        if (!invalidAlgorithms.isEmpty()) {
            throw new IllegalStateException(
                    "Unknown rate limit algorithms configured: " + String.join(", ", invalidAlgorithms)
            );
        }

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
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }
}
