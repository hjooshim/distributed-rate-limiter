package com.drl.ratelimiter.aspect;

import com.drl.ratelimiter.annotation.RateLimit;
import com.drl.ratelimiter.exception.RateLimitExceededException;
import com.drl.ratelimiter.identity.ClientIdentityResolver;
import com.drl.ratelimiter.strategy.RateLimitDecision;
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
    private final ClientIdentityResolver clientIdentityResolver;

    public RateLimitAspect(
            StrategyRegistry strategyRegistry,
            ClientIdentityResolver clientIdentityResolver
    ) {
        this.strategyRegistry = strategyRegistry;
        this.clientIdentityResolver = clientIdentityResolver;
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
        String clientId = clientIdentityResolver.resolveCurrentClientId();

        String key = className + "." + methodName + ":" + clientId;
        int limit = rateLimit.limit();
        long windowMs = rateLimit.windowMs();
        String algorithm = rateLimit.algorithm();

        RateLimitDecision decision = strategyRegistry.get(algorithm).evaluate(key, limit, windowMs);

        if (!decision.isAllowed()) {
            throw new RateLimitExceededException(
                    key,
                    limit,
                    windowMs,
                    decision.getRetryAfterSeconds()
            );
        }

        return joinPoint.proceed();
    }
}
