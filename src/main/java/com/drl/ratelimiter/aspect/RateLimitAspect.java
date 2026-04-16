package com.drl.ratelimiter.aspect;

import java.lang.reflect.Method;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import com.drl.ratelimiter.annotation.RateLimit;
import com.drl.ratelimiter.exception.RateLimitExceededException;
import com.drl.ratelimiter.identity.ClientIdentityResolver;
import com.drl.ratelimiter.strategy.RateLimitDecision;
import com.drl.ratelimiter.strategy.StrategyRegistry;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * ============================================================
 * ASPECT-ORIENTED PROGRAMMING (AOP) - Cross-Cutting Rate Limiting
 * ============================================================
 *
 * PURPOSE:
 *   Enforces {@link RateLimit} without requiring controller or service methods
 *   to call the rate-limiter manually.
 *
 * HOW IT WORKS:
 *   When Spring AOP sees a method annotated with {@link RateLimit}, it routes
 *   the method call through this aspect first.
 *
 *   This aspect then:
 *     1. Reads the intercepted method and its annotation values
 *     2. Resolves who the caller is (principal or IP-based identity)
 *     3. Builds a stable rate-limit key for "method + caller"
 *     4. Looks up the configured algorithm in the strategy registry
 *     5. Asks that strategy whether this request is allowed
 *     6. Throws HTTP 429 metadata if rejected, or proceeds if allowed
 *
 * WHY AOP IS A GOOD FIT:
 *   Rate limiting is cross-cutting behavior. Many endpoints may need it, but
 *   none of them should contain repeated boilerplate like:
 *     - resolve caller identity
 *     - build a rate-limit key
 *     - call the strategy
 *     - throw on rejection
 *
 *   Putting that flow in an aspect keeps endpoint code focused on business
 *   logic while centralizing the enforcement policy in one place.
 */
@Aspect
@Component
public class RateLimitAspect {

    private final StrategyRegistry strategyRegistry;
    private final ClientIdentityResolver clientIdentityResolver;
    private final MeterRegistry meterRegistry;

    public RateLimitAspect(
            StrategyRegistry strategyRegistry,
            ClientIdentityResolver clientIdentityResolver,
            MeterRegistry meterRegistry
    ) {
        this.strategyRegistry = strategyRegistry;
        this.clientIdentityResolver = clientIdentityResolver;
        this.meterRegistry = meterRegistry;
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

        // Step 1: Extract method metadata from the intercepted call.
        // ProceedingJoinPoint is the generic AOP wrapper, but we need the
        // MethodSignature to read the Java method and its declaring class.
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String className = signature.getDeclaringType().getSimpleName();
        String methodName = method.getName();

        // Step 2: Resolve the current caller identity.
        // The resolver decides whether this request is tracked by principal
        // name, forwarded header, remote IP, or a fallback value.
        String clientId = clientIdentityResolver.resolveCurrentClientId();

        // Step 3: Build a stable logical key for this endpoint + caller pair.
        // Example:
        //   DemoController.hello:principal:alice
        //   DemoController.hello:ip:203.0.113.10
        //
        // This ensures each caller gets an independent quota for each
        // annotated method instead of sharing one global counter.
        String key = className + "." + methodName + ":" + clientId;

        // Step 4: Read the policy directly from the annotation instance that
        // triggered this advice.
        int limit = rateLimit.limit();
        long windowMs = rateLimit.windowMs();
        String algorithm = rateLimit.algorithm();

        // Step 5: Resolve the configured strategy and evaluate the request.
        // The aspect does not know whether the algorithm is local, Redis-
        // backed, fixed-window, token-bucket, etc. That choice is delegated
        // to the strategy registry.
        RateLimitDecision decision = strategyRegistry.get(algorithm).evaluate(key, limit, windowMs);

        // Step 6: Record the decision as a labeled counter.
        // Tags are intentionally low-cardinality: endpoint and algorithm are
        // bounded by the number of annotated methods in the codebase, and
        // result has exactly two values (allowed / rejected).
        meterRegistry.counter("rate_limit_decisions_total",
                "endpoint",  className + "." + methodName,
                "algorithm", algorithm,
                "result",    decision.isAllowed() ? "allowed" : "rejected"
        ).increment();

        // Step 7: Reject before the business method runs.
        // Throwing here prevents the controller/service method from executing
        // at all when the caller has exhausted its quota.
        if (!decision.isAllowed()) {
            throw new RateLimitExceededException(
                    key,
                    limit,
                    windowMs,
                    decision.getRetryAfterSeconds()
            );
        }

        // Step 8: Continue to the original application logic only when the
        // rate-limit check has passed.
        return joinPoint.proceed();
    }
}