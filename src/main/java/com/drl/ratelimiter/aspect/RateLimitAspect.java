package com.drl.ratelimiter.aspect;

import com.drl.ratelimiter.annotation.RateLimit;
import com.drl.ratelimiter.exception.RateLimitExceededException;
import com.drl.ratelimiter.strategy.StrategyRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Intercepts methods annotated with {@link RateLimit}.
 */
@Aspect
@Component
public class RateLimitAspect {

    private final StrategyRegistry strategyRegistry;

    public RateLimitAspect(StrategyRegistry strategyRegistry) {
        this.strategyRegistry = strategyRegistry;
    }

    /**
     * Wraps a {@link RateLimit}-annotated method call.
     *
     * @param joinPoint intercepted method call
     * @param rateLimit annotation instance attached to the method
     * @return original method result
     * @throws Throwable if the intercepted method throws
     */
    @Around("@annotation(rateLimit)")
    public Object enforce(ProceedingJoinPoint joinPoint, RateLimit rateLimit)
            throws Throwable {

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String className = signature.getDeclaringType().getSimpleName();
        String methodName = method.getName();

        String key = className + "." + methodName;
        int limit = rateLimit.limit();
        long windowMs = rateLimit.windowMs();

        boolean allowed = strategyRegistry.get("FIXED_WINDOW").isAllowed(key, limit, windowMs);

        if (!allowed) {
            throw new RateLimitExceededException(key, limit, windowMs);
        }

        return joinPoint.proceed();
    }
}
