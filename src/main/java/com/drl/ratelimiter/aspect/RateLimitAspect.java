package com.drl.ratelimiter.aspect;

import com.drl.ratelimiter.annotation.RateLimit;
import com.drl.ratelimiter.exception.RateLimitExceededException;
import com.drl.ratelimiter.strategy.StrategyRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Arrays;

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
        String clientId = resolveClientId();

        String key = className + "." + methodName + ":" + clientId;
        int limit = rateLimit.limit();
        long windowMs = rateLimit.windowMs();
        String algorithm = rateLimit.algorithm();

        boolean allowed = strategyRegistry.get(algorithm).isAllowed(key, limit, windowMs);

        if (!allowed) {
            throw new RateLimitExceededException(key, limit, windowMs);
        }

        return joinPoint.proceed();
    }

    private String resolveClientId() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (!(requestAttributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            return "unknown-client";
        }

        String forwardedFor = servletRequestAttributes.getRequest().getHeader("X-Forwarded-For");
        String forwardedClient = firstForwardedValue(forwardedFor);
        if (forwardedClient != null) {
            return forwardedClient;
        }

        String remoteAddress = servletRequestAttributes.getRequest().getRemoteAddr();
        if (remoteAddress != null && !remoteAddress.isBlank()) {
            return remoteAddress.trim();
        }

        return "unknown-client";
    }

    private String firstForwardedValue(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return null;
        }

        return Arrays.stream(forwardedFor.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse(null);
    }
}
