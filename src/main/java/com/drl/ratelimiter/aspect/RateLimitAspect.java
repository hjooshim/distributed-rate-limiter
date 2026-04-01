package com.drl.ratelimiter.aspect;

import com.drl.ratelimiter.annotation.RateLimit;
import com.drl.ratelimiter.exception.RateLimitExceededException;
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

        // Placeholder until the strategy layer is wired in.
        boolean allowed = isAllowed(key, limit, windowMs);

        if (!allowed) {
            throw new RateLimitExceededException(key, limit, windowMs);
        }

        return joinPoint.proceed();
    }

    /**
     * Placeholder decision hook until the strategy layer is added.
     *
     * @param key rate-limit bucket key for the current method
     * @param limit maximum number of requests allowed in the window
     * @param windowMs length of the rate-limit window in milliseconds
     * @return {@code true} while the concrete strategy is not wired yet
     */
    private boolean isAllowed(String key, int limit, long windowMs) {
        return true;
    }
}
