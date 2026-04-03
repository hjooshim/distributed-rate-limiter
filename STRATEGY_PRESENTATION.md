# Rate Limiter — Strategy Pattern: Presentation Summary

---

## 1. Overview

This project implements a **rate limiting system** using the **Strategy design pattern**,
combined with AOP (Aspect-Oriented Programming) and Spring Boot.

The core idea: different rate-limiting algorithms (Fixed Window, Sliding Window, Token Bucket, etc.)
are interchangeable behind a single interface. The rest of the system never needs to change
when a new algorithm is added.

---

## 2. The Strategy Pattern Structure

```
         ┌─────────────────────────┐
         │   <<interface>>         │
         │   RateLimitStrategy     │
         │─────────────────────────│
         │ + isAllowed(key, limit, │
         │     windowMs): boolean  │
         │ + getName(): String     │
         └────────────┬────────────┘
                      │ implements
          ┌───────────┘
          │
┌─────────▼──────────────┐      (future)
│ LocalFixedWindowStrategy│    ┌──────────────────────┐
│────────────────────────│    │ RedisSlidingWindow... │
│ - counters: ConcurrentHashMap│ │ (not yet implemented)│
│ + isAllowed(...)       │    └──────────────────────┘
│ + getName(): "FIXED_WINDOW"│
│ + reset()              │
└────────────────────────┘
```

### Files

| File | Role |
|------|------|
| `strategy/RateLimitStrategy.java` | Strategy interface — the contract |
| `strategy/LocalFixedWindowStrategy.java` | Concrete strategy — Fixed Window algorithm |
| `strategy/StrategyRegistry.java` | Registry / Factory — maps names to instances |

---

## 3. The Interface: `RateLimitStrategy`

```java
public interface RateLimitStrategy {
    boolean isAllowed(String key, int limit, long windowMs);
    default String getName() { return null; }
}
```

**Design principles demonstrated:**
- **Abstraction**: callers only know `isAllowed()`, not how counting works internally
- **Open-Closed Principle**: add a new algorithm by creating a new class — zero changes to this interface
- **Dependency Inversion**: all callers depend on this interface, not on any concrete class

---

## 4. The Concrete Strategy: `LocalFixedWindowStrategy`

### Algorithm

Time is divided into fixed windows. Each window has its own counter.

```
Window: [00:00 – 01:00]   req1 ✅ (count=1)
                           req2 ✅ (count=2)
                           req3 ✅ (count=3)
                           req4 ❌ (count=3, at limit)
Window: [01:00 – 02:00]   req5 ✅ (new window, count resets to 1)
```

### Key logic (step by step)

```java
// Step 1: Calculate current window ID
long windowId = System.currentTimeMillis() / windowMs;

// Step 2: Compound key scopes the counter to one window
String windowKey = key + ":" + windowId;

// Step 3: Get or create counter (atomic via ConcurrentHashMap)
WindowCounter counter = counters.computeIfAbsent(windowKey, k -> new WindowCounter());

// Step 4: Atomic increment — returns new count
long currentCount = counter.count.incrementAndGet();

// Step 5: Lazy cleanup every 100 requests
if (currentCount % 100 == 0) cleanupOldWindows(windowId);

// Step 6: Allow if within limit
return currentCount <= limit;
```

### Concurrency Design

**Problem:** Two threads both read `count=4`, both see `4 < 5 = true`, both increment
to 5 — result: 6 requests allowed instead of 5 (race condition).

**Solution:** `AtomicLong` with CAS (Compare-And-Swap)

| Approach | Behavior |
|----------|----------|
| `synchronized` block | Thread 2 and 3 block while Thread 1 runs |
| `AtomicLong` (CAS) | All threads try simultaneously; failures spin-retry — **lock-free** |

`ConcurrentHashMap` handles concurrent map reads/writes.
`computeIfAbsent` atomically creates a counter if the key doesn't exist yet.

### Known Limitation

**Boundary burst problem:** a client can send 2× the limit by hitting the end of one
window and the start of the next. This is acceptable for the current phase; Sliding Window
would fix this.

---

## 5. The Registry: `StrategyRegistry`

```java
@Component
public class StrategyRegistry {
    private final Map<String, RateLimitStrategy> strategies;

    public StrategyRegistry(List<RateLimitStrategy> strategyList) {
        // Spring auto-collects ALL RateLimitStrategy beans into the list
        for (RateLimitStrategy s : strategyList) {
            strategies.put(s.getName(), s);
        }
    }

    public RateLimitStrategy get(String name) {
        return strategies.getOrDefault(name, fallback); // fail-open
    }
}
```

**Role:** Factory/Registry — decouples the caller from concrete classes.

**Open-Closed in action:** When a new strategy (e.g., `RedisSlidingWindowStrategy`) is added
as a Spring `@Component`, Spring automatically injects it into the list. Zero changes to
`StrategyRegistry` or `RateLimitAspect`.

**Fail-open fallback:** If an unknown algorithm name is requested (e.g., typo), the registry
returns a pass-through strategy (always allows) rather than crashing the application.

---

## 6. How the Strategy Fits Into the Whole System

```
HTTP Request
     │
     ▼
DemoController.strictEndpoint()
@RateLimit(limit=3, windowMs=10_000, algorithm="FIXED_WINDOW")
     │
     │  Spring AOP intercepts before the method runs
     ▼
RateLimitAspect.enforce()
  1. Extract class+method name → build key "DemoController.strictEndpoint"
  2. Read annotation: limit=3, windowMs=10000, algorithm="FIXED_WINDOW"
  3. Ask StrategyRegistry: registry.get("FIXED_WINDOW")
  4. Call: strategy.isAllowed(key, 3, 10000)
     │
     ▼
LocalFixedWindowStrategy.isAllowed()
  → returns true / false
     │
     ├── true  → joinPoint.proceed() → real method runs → HTTP 200
     └── false → throw RateLimitExceededException → HTTP 429
```

### Component Interaction Table

| Component | Depends On | Used By |
|-----------|-----------|---------|
| `RateLimitStrategy` (interface) | — | `RateLimitAspect`, `StrategyRegistry`, tests |
| `LocalFixedWindowStrategy` | `RateLimitStrategy` | `StrategyRegistry` (via Spring injection) |
| `StrategyRegistry` | `RateLimitStrategy` | `RateLimitAspect` |
| `RateLimitAspect` | `StrategyRegistry`, `@RateLimit` annotation | Spring AOP (auto-applied) |
| `@RateLimit` annotation | — | `DemoController`, `RateLimitAspect` |
| `DemoController` | `@RateLimit` annotation | HTTP client |

---

## 7. OOP Design Principles Applied

| Principle | Where |
|-----------|-------|
| **Abstraction** | `RateLimitStrategy` interface hides algorithm details |
| **Encapsulation** | `WindowCounter` inner class hides `AtomicLong`; cleanup is private |
| **Open-Closed** | Add algorithms without modifying existing code |
| **Dependency Inversion** | `RateLimitAspect` depends on interface, not `LocalFixedWindowStrategy` |
| **Single Responsibility** | Each class has one job: strategy does counting, aspect does enforcement, registry does lookup |
| **Liskov Substitution** | Tests are written against `RateLimitStrategy` interface — any implementation can be swapped in |

---

## 8. Tests: `LocalFixedWindowStrategyTest`

| Test | What it verifies |
|------|-----------------|
| `shouldAllowRequestsWithinLimit` | Basic allow behavior |
| `shouldRejectRequestExceedingLimit` | Reject on 4th request when limit=3 |
| `differentKeysShouldBeIndependent` | Keys do not share counters |
| `limitOfOneShouldAllowExactlyOneRequest` | Edge case: limit=1 |
| `newWindowShouldResetCounter` | Counter resets after window expires (uses 100ms window) |
| `shouldNotExceedLimitUnderConcurrentAccess` | 20 threads → exactly 5 allowed (thread safety) |
| `highIntensityStressTest` (×10) | 10,000 threads → exactly 100 allowed (stress test) |

**Testing philosophy:** The test variable is typed as `RateLimitStrategy` (the interface),
not `LocalFixedWindowStrategy`. This validates LSP — the same tests could run against
any future implementation.

---

## 9. Extensibility: Adding a New Algorithm

To add a `SlidingWindowStrategy`:

1. Create `SlidingWindowStrategy implements RateLimitStrategy`
2. Annotate with `@Component`
3. Return `"SLIDING_WINDOW"` from `getName()`
4. Use it: `@RateLimit(algorithm = "SLIDING_WINDOW", limit = 10, windowMs = 60_000)`

**No other files change.** Spring auto-registers it, `StrategyRegistry` auto-maps it,
`RateLimitAspect` auto-resolves it.