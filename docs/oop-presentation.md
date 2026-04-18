

# OOP Design — Distributed Rate Limiter

## 1. OOP Principles

### Abstraction
`RateLimitStrategy` separates *what* rate limiting is from *how* it works.
The AOP Aspect calls one method and never knows whether the algorithm runs
in memory or against Redis:

```java
// RateLimitAspect.java
RateLimitDecision decision = strategyRegistry.get(algorithm)
                                             .evaluate(key, limit, windowMs);
```

---

### Encapsulation
`RateLimitDecision` is an immutable value object. Its internal state is
private and can only be created through factory methods:

```java
// RateLimitDecision.java
private final boolean allowed;
private final long retryAfterSeconds;

// External code can only call:
RateLimitDecision.allowed()
RateLimitDecision.rejected(5)
```

`LocalFixedWindowStrategy` keeps its `ConcurrentHashMap` private — external
code cannot bypass the rate-limiting logic and modify counters directly.

---

### Inheritance
A two-level hierarchy where each layer solves a distinct problem:

```
AbstractRateLimitStrategy            ← handles: input validation
        └── AbstractRedisRateLimitStrategy  ← handles: Redis execution
                    ├── RedisTokenBucketStrategy    ← handles: token-bucket logic
                    └── RedisSlidingWindowStrategy  ← handles: sliding-window logic
```

Each layer is responsible for exactly one concern.

---

### Polymorphism
The same call dispatches to a completely different algorithm at runtime
depending on what the `@RateLimit` annotation says:

```java
// Same line of code, three different behaviors
strategyRegistry.get(algorithm).evaluate(key, limit, windowMs);
// algorithm = "FIXED_WINDOW"   → LocalFixedWindowStrategy
// algorithm = "TOKEN_BUCKET"   → RedisTokenBucketStrategy
// algorithm = "SLIDING_WINDOW" → RedisSlidingWindowStrategy
```

---

## 2. Design Patterns

### Strategy Pattern

**Location**: `RateLimitStrategy` interface + three concrete implementations.

**Problem it solves**: encapsulates each algorithm as a swappable object.
Switching algorithms requires changing one word in an annotation — no
other code changes.

```java
@RateLimit(algorithm = "FIXED_WINDOW")   // → LocalFixedWindowStrategy
@RateLimit(algorithm = "TOKEN_BUCKET")   // → RedisTokenBucketStrategy
@RateLimit(algorithm = "SLIDING_WINDOW") // → RedisSlidingWindowStrategy
```

The caller (`RateLimitAspect`) is completely decoupled from the concrete
implementations. It only depends on the `RateLimitStrategy` interface.

---

### Registry Pattern

**Location**: `StrategyRegistry`.

**Problem it solves**: centralizes strategy lookup so the rest of the
application never references a concrete class by name. At startup, Spring
automatically injects every `RateLimitStrategy` bean into the registry:

```java
// StrategyRegistry.java
public StrategyRegistry(List<RateLimitStrategy> strategyList) {
    for (RateLimitStrategy strategy : strategyList) {
        strategies.put(strategy.getName(), strategy);
    }
}

public RateLimitStrategy get(String name) {
    return strategies.get(name);
}
```

Adding a new algorithm only requires creating a new class. The registry
discovers it automatically — no existing code changes. This is the
**Open-Closed Principle** in practice.

---

## 3. The Request Flow — Full Call Chain

One annotated method triggers five layers of OOP working together:

```java
// ── Step 1: Annotation declares the policy (DemoController.java) ──────────
@RateLimit(limit = 3, windowMs = 10_000, algorithm = "TOKEN_BUCKET")
@GetMapping("/api/token-bucket/primary")
public Map<String, Object> tokenBucketEndpoint() {
    return Map.of("message", "ok");     // zero rate-limiting code here
}


// ── Step 2: AOP intercepts, reads the annotation (RateLimitAspect.java) ───
String key      = className + "." + methodName + ":" + clientId;
int    limit    = rateLimit.limit();
String algorithm = rateLimit.algorithm();

RateLimitDecision decision = strategyRegistry.get(algorithm)
                                             .evaluate(key, limit, windowMs);
if (!decision.isAllowed()) {
    throw new RateLimitExceededException(key, limit, windowMs,
                                         decision.getRetryAfterSeconds());
}
return joinPoint.proceed();  // allowed — continue to the controller


// ── Step 3: Registry resolves the name to an instance (StrategyRegistry) ──
public RateLimitStrategy get(String name) {
    return strategies.get(name);   // "TOKEN_BUCKET" → RedisTokenBucketStrategy
}


// ── Step 4: Abstract class validates, then delegates (AbstractRateLimitStrategy) ──
public final RateLimitDecision evaluate(String key, int limit, long windowMs) {
    validateKey(key);        // shared validation — runs for every strategy
    validateLimit(limit);
    validateWindowMs(windowMs);
    return doEvaluate(key, limit, windowMs);   // polymorphic dispatch
}


// ── Step 5: Concrete strategy executes the algorithm (RedisTokenBucketStrategy) ──
@Override
protected RateLimitDecision doEvaluate(String key, int limit, long windowMs) {
    return executeDecisionScript(TOKEN_BUCKET_SCRIPT,
                                 key,
                                 String.valueOf(limit),
                                 String.valueOf(windowMs), ...);
    // returns RateLimitDecision.allowed()  or  RateLimitDecision.rejected(N)
}
```

---

## 4. Summary

> We applied the **Strategy Pattern** to encapsulate three rate-limiting
> algorithms as interchangeable objects, and the **Registry Pattern** to
> resolve the correct implementation at runtime from a single annotation
> value. The design follows the **Open-Closed Principle**: adding a new
> algorithm requires only one new class — no existing code is modified.
> The four OOP principles — abstraction, encapsulation, inheritance, and
> polymorphism — are each demonstrated at a distinct layer of the call chain.
