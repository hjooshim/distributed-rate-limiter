# OOP Presentation — Distributed Rate Limiter
## Slide Script (5 minutes, 9 slides)

---

## Slide 1 — Title

**[VISUAL]**
```
Distributed Rate Limiter
OOP Design & Patterns
```

**[SPEAKER NOTES]**
Hey everyone. I'm going to walk through how we applied OOP principles and
design patterns in our distributed rate limiter. I'll cover the four principles,
two patterns, show you the actual call chain in the code, and share what was
honestly the hardest thing to get right.

---

## Slide 2 — Abstraction

**[VISUAL]**
```
Abstraction
"Hide the HOW behind the WHAT"

           «interface»
        RateLimitStrategy
        + evaluate(key, limit, windowMs)
                 ▲
    ┌────────────┼────────────┐
    ▼            ▼            ▼
 LocalFixed   TokenBucket  SlidingWindow
 (memory)      (Redis)       (Redis)

// Caller never references a concrete class:
strategyRegistry.get(algorithm)
                .evaluate(key, limit, windowMs);
```

**[SPEAKER NOTES]**
So the first principle is abstraction. We have one interface — RateLimitStrategy —
with one method: evaluate. The AOP Aspect that intercepts every HTTP request
calls this method and has absolutely no idea whether the algorithm is running
in memory or making a network call to Redis. That's the whole point.
What's nice about this is when we added Sliding Window later in the project,
the Aspect code didn't change at all. We just added a new class.

---

## Slide 3 — Encapsulation

**[VISUAL]**
```
Encapsulation
"Protect your data — expose only what's needed"

public final class RateLimitDecision {

    // ① Singleton for the "allowed" case — created once, reused forever
    private static final RateLimitDecision ALLOWED = new RateLimitDecision(true, 0);

    // ② Fields are private + final — NOBODY outside can read or write them directly
    private final boolean allowed;
    private final long retryAfterSeconds;

    // ③ Constructor is private — you CANNOT call new RateLimitDecision(...)
    private RateLimitDecision(boolean allowed, long retryAfterSeconds) { ... }

    // ④ The ONLY two ways to get a decision (factory methods):
    public static RateLimitDecision allowed()                   // returns the singleton
    public static RateLimitDecision rejected(long retryAfterSeconds)  // validates > 0, then creates

    // ⑤ Read-only access — getters, no setters
    public boolean isAllowed()            { return allowed; }
    public long getRetryAfterSeconds()    { return retryAfterSeconds; }
}

// How the Aspect uses it:
RateLimitDecision decision = strategy.evaluate(key, limit, windowMs);
if (!decision.isAllowed()) {
    throw new RateLimitExceededException(decision.getRetryAfterSeconds());
}
```

**[SPEAKER NOTES]**
Encapsulation — let me walk through this class line by line because it's a
really clean example of every encapsulation technique working together.

**① The singleton constant at the top.**
`ALLOWED` is a `private static final` field, created once when the class loads
and reused every time a request is allowed. Because it's private, nothing
outside the class can see it — you only get it through the `allowed()` factory
method. This also means zero object allocation on the happy path, which matters
at high request rates.

**② Private final fields.**
Both `allowed` and `retryAfterSeconds` are `private` and `final`. Private means
no outside code can read or write them directly — there's no
`decision.allowed = false` or `decision.retryAfterSeconds = 99` possible.
Final means once the object is constructed, those values never change.
This makes every `RateLimitDecision` **immutable** — you can safely share it
across threads with no synchronization.

**③ Private constructor.**
This is the key move. The constructor is `private`, so the only code that can
call `new RateLimitDecision(...)` is code inside this class itself. Callers
in the rest of the codebase literally cannot construct one directly. This forces
everyone through the factory methods, which is exactly where we control what
states are valid.

**④ Factory methods as the only entry points.**
There are exactly two: `allowed()` and `rejected(retryAfterSeconds)`.
`allowed()` just returns the pre-built singleton — fast, no allocation.
`rejected()` validates that `retryAfterSeconds > 0` before creating anything.
This is where encapsulation prevents bugs: it is **impossible** to create a
"rejected" decision with a zero or negative retry time. The class enforces that
rule at the one place decisions are born. Without this pattern you'd need to
scatter validation checks all over the codebase, and one day someone would forget.

**⑤ Getters, no setters.**
`isAllowed()` and `getRetryAfterSeconds()` let callers read the state they need.
There are no setters — once a decision is made, it cannot be changed. The Aspect
calls `isAllowed()`, and if it's false, grabs `getRetryAfterSeconds()` to build
the 429 response. That's the entire public API. Clean, minimal, safe.

---

## Slide 4 — Inheritance

**[VISUAL]**
```
Inheritance
"Each layer owns exactly one responsibility"

AbstractRateLimitStrategy
  └─ validates input: key, limit, windowMs
        │
        ├── LocalFixedWindowStrategy
        │     └─ ConcurrentHashMap + AtomicLong  (no Redis)
        │
        └── AbstractRedisRateLimitStrategy
              └─ executes Lua script on Redis
              └─ handles connection failures
                    │
                    ├── RedisTokenBucketStrategy
                    │     └─ token-bucket logic only
                    │
                    └── RedisSlidingWindowStrategy
                          └─ sorted-set logic only
```

**[SPEAKER NOTES]**
For inheritance, we have a two-level hierarchy. The top abstract class handles
input validation — null checks, limit must be positive, that kind of thing —
same rules for every algorithm so they live in one place.
The middle layer, AbstractRedisRateLimitStrategy, handles all the Redis stuff:
running the Lua script, managing the connection. Both Token Bucket and Sliding
Window inherit this, so if we ever change how we talk to Redis, we fix it once.
The concrete classes at the bottom? They only contain the actual algorithm logic.
Nothing else.

---

## Slide 5 — Polymorphism

**[VISUAL]**
```
Polymorphism
"One call, different behavior at runtime"

strategy.evaluate(key, limit, windowMs)

  algorithm = "FIXED_WINDOW"
  → AtomicLong.incrementAndGet()  (in memory, ~45 ns)

  algorithm = "TOKEN_BUCKET"
  → Redis EVAL: HGET + HSET Lua script  (~1 ms RTT)

  algorithm = "SLIDING_WINDOW"
  → Redis EVAL: ZADD + ZCARD Lua script  (~1 ms RTT)

// Change behavior by changing one word:
@RateLimit(algorithm = "TOKEN_BUCKET")
@RateLimit(algorithm = "SLIDING_WINDOW")
```

**[SPEAKER NOTES]**
Polymorphism is probably the one you see the most in this project.
One line of code — strategy.evaluate() — but at runtime it does completely
different things depending on which object comes back from the registry.
Fixed Window hits an AtomicLong in memory, takes about 45 nanoseconds.
Token Bucket fires a Lua script at Redis, takes around a millisecond.
Same interface, totally different behavior. And from a developer's perspective,
switching an endpoint from one algorithm to another is literally just changing
one word in the annotation.

---

## Slide 6 — Strategy Pattern

**[VISUAL]**
```
Strategy Pattern
"Encapsulate each algorithm as a swappable object"

         «interface»
       RateLimitStrategy
       + evaluate(...)
             ▲
    ┌────────┼────────┐
    │        │        │
 Fixed    Token   Sliding
 Window   Bucket  Window

Before:  if (algorithm.equals("FIXED"))  { ... }
         else if (algorithm.equals("TOKEN")) { ... }  ← not this

After:   strategyRegistry.get(algorithm).evaluate(...)  ← this
```

**[SPEAKER NOTES]**
The Strategy Pattern is basically the backbone of the whole system.
Instead of writing a giant if-else chain to pick an algorithm,
each algorithm is its own class implementing the same interface.
The Aspect just calls evaluate — it doesn't care which one it gets.
The real win: adding a new algorithm means writing one new class.
You don't touch the Aspect, you don't touch the Registry, you don't
touch the exception handler. Nothing breaks.

---

## Slide 7 — Registry Pattern

**[VISUAL]**
```
Registry Pattern
"One place to look up anything by name"

// StrategyRegistry.java
public StrategyRegistry(List<RateLimitStrategy> strategies) {
    for (RateLimitStrategy s : strategies) {
        registry.put(s.getName(), s);   // "FIXED_WINDOW" → LocalFixed...
    }                                   // "TOKEN_BUCKET"  → RedisToken...
}                                       // "SLIDING_WINDOW"→ RedisSlidingWindow...

public RateLimitStrategy get(String name) {
    return registry.get(name);
}

// Spring auto-injects ALL RateLimitStrategy beans at startup
// → new algorithm = new class, zero changes elsewhere
```

**[SPEAKER NOTES]**
The Registry Pattern works hand-in-hand with Strategy. At startup, Spring
automatically finds every class that implements RateLimitStrategy and injects
them all into the constructor as a list. The Registry just loops through and
maps each one by name. So "TOKEN_BUCKET" maps to the token bucket class,
and so on. What's cool about this is the Registry never needs to know what
algorithms exist. If we add a fourth algorithm tomorrow, Spring picks it up
automatically — the Registry code doesn't change at all.

---

## Slide 8 — The Call Chain

**[VISUAL]**
```
One annotated method → 5 layers of OOP

Step 1  DemoController       @RateLimit(limit=3, algorithm="TOKEN_BUCKET")
           ↓                 zero rate-limiting code in the controller
Step 2  RateLimitAspect      reads annotation → builds key → calls registry
           ↓
Step 3  StrategyRegistry     "TOKEN_BUCKET" → returns RedisTokenBucketStrategy
           ↓
Step 4  AbstractRateLimitStrategy   validate(key, limit, windowMs)
           ↓                        then calls doEvaluate()
Step 5  RedisTokenBucketStrategy    runs Lua script on Redis
           ↓
        RateLimitDecision.allowed()  →  continue
        RateLimitDecision.rejected() →  throw 429
```

**[SPEAKER NOTES]**
This slide ties everything together. When a request hits the controller,
the developer has written zero rate-limiting code there — just the annotation.
The AOP Aspect intercepts the call, reads the annotation, builds a key from
the class name and client IP, and asks the Registry for the right strategy.
The Registry returns the concrete object. The abstract class validates the
inputs, then hands off to doEvaluate. The concrete strategy runs the Lua script.
Finally it comes back as either allowed — the request goes through — or
rejected — we throw a 429. Every OOP concept we talked about is working
in this one flow.

---

## Appendix — Class Relationship Diagram (`strategy` package)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            «interface»                                      │
│                         RateLimitStrategy                                   │
│  + evaluate(key, limit, windowMs): RateLimitDecision                        │
│  + isAllowed(key, limit, windowMs): boolean          [default]              │
│  + getName(): String                                 [default → null]       │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │ implements
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           «abstract class»                                  │
│                       AbstractRateLimitStrategy                             │
│  ─ name: String                                                             │
│  + evaluate(key, limit, windowMs)  [final]  ← validates → calls doEvaluate │
│  + getName()                       [final]                                  │
│  # doEvaluate(key, limit, windowMs)[abstract]                               │
│  ─ validateKey / validateLimit / validateWindowMs  [private]                │
└────────────┬─────────────────────────────────────────────┬──────────────────┘
             │ extends                                      │ extends
             ▼                                             ▼
┌───────────────────────────────┐          ┌──────────────────────────────────┐
│        «abstract class»       │          │     «concrete class» @Component  │
│  AbstractRedisRateLimitStrategy│         │      LocalFixedWindowStrategy    │
│                               │          │                                  │
│  ─ redisTemplate: StringRedis │          │  ─ counters: ConcurrentHashMap   │
│  ─ keyPrefix: String          │          │       <String, WindowCounter>    │
│                               │          │  ─ totalRequests: AtomicLong     │
│  # buildRedisKey()   [final]  │          │                                  │
│  # ttlMs()           [final]  │          │  # doEvaluate()                  │
│  # executeDecisionScript()    │          │  + reset()  [test helper]        │
│                      [final]  │          │                                  │
│  # loadScript()      [static] │          │  ┌────────────────────────────┐  │
│  ─ parseDecision()  [private] │          │  │  «static inner class»      │  │
└───────┬──────────────┬────────┘          │  │      WindowCounter         │  │
        │ extends      │ extends           │  │  + count: AtomicLong       │  │
        ▼              ▼                   │  └────────────────────────────┘  │
┌──────────────┐  ┌──────────────────┐    └──────────────────────────────────┘
│  «concrete»  │  │    «concrete»    │
│  @Component  │  │    @Component    │
│ RedisToken   │  │  RedisSlidingWin │
│ BucketStrate │  │  dowStrategy    │
│              │  │                 │
│ ─ TOKEN_     │  │ ─ SLIDING_      │
│   BUCKET_    │  │   WINDOW_       │
│   SCRIPT     │  │   SCRIPT        │
│   [static]   │  │   [static]      │
│              │  │                 │
│ # doEvaluate │  │ # doEvaluate()  │
└──────────────┘  └─────────────────┘

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

┌─────────────────────────────────────────────────┐
│               «final class»                     │
│             RateLimitDecision                   │   ← returned by every evaluate()
│                                                 │
│  ─ ALLOWED: RateLimitDecision  [static final]   │
│  ─ allowed: boolean            [private final]  │
│  ─ retryAfterSeconds: long     [private final]  │
│                                                 │
│  ─ RateLimitDecision(...)      [private]        │
│  + allowed()                   [static factory] │
│  + rejected(retryAfterSeconds) [static factory] │
│  + isAllowed(): boolean                         │
│  + getRetryAfterSeconds(): long                 │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│            «component» @Component               │
│              StrategyRegistry                   │   ← holds all strategies
│                                                 │
│  ─ strategies: Map<String, RateLimitStrategy>   │
│                                                 │
│  + StrategyRegistry(List<RateLimitStrategy>)    │
│      ↑ Spring injects ALL strategy beans here   │
│  + get(name): RateLimitStrategy                 │
│  + contains(name): boolean                      │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│  «component» @Component  implements SmartLifecycle│
│         RateLimitAlgorithmValidator             │   ← startup validation
│                                                 │
│  ─ beanFactory: ListableBeanFactory             │
│  ─ strategyRegistry: StrategyRegistry           │
│                                                 │
│  + start()  ← scans ALL @RateLimit annotations, │
│               fails startup if algorithm name   │
│               not found in StrategyRegistry     │
│  + getPhase() → Integer.MIN_VALUE               │
│      (runs first among all lifecycle beans)     │
└─────────────────────────────────────────────────┘
```

### Relationship Summary

| 关系 | From → To | 类型 |
|------|-----------|------|
| `AbstractRateLimitStrategy` → `RateLimitStrategy` | 实现接口 | `implements` |
| `LocalFixedWindowStrategy` → `AbstractRateLimitStrategy` | 单层继承 | `extends` |
| `AbstractRedisRateLimitStrategy` → `AbstractRateLimitStrategy` | 单层继承 | `extends` |
| `RedisTokenBucketStrategy` → `AbstractRedisRateLimitStrategy` | 单层继承 | `extends` |
| `RedisSlidingWindowStrategy` → `AbstractRedisRateLimitStrategy` | 单层继承 | `extends` |
| `WindowCounter` → `LocalFixedWindowStrategy` | 静态内部类 | `inner class` |
| 所有策略 → `RateLimitDecision` | 返回值依赖 | `uses (return type)` |
| `StrategyRegistry` → `RateLimitStrategy` | 聚合接口集合 | `aggregates` |
| `RateLimitAlgorithmValidator` → `StrategyRegistry` | 启动校验依赖 | `depends on` |

### 层级职责划分

```
Layer 0  RateLimitStrategy          接口契约 — 定义"做什么"
Layer 1  AbstractRateLimitStrategy  共享验证 — key/limit/windowMs 合法性
Layer 2  AbstractRedisRateLimitStrategy  共享 Redis 基础设施 — key 构造、Lua 执行、解析
Layer 3  Concrete Strategies        纯算法逻辑 — Fixed Window / Token Bucket / Sliding Window
─────────────────────────────────────────────────────────────
Support  RateLimitDecision          不可变值对象 — 封装 allowed + retryAfterSeconds
Support  StrategyRegistry           名称→实例映射 — Strategy Pattern 的查找入口
Support  RateLimitAlgorithmValidator 启动校验 — Fail Fast，防止注解拼写错误上线
```

---

## Slide 9 — What I Learned

**[VISUAL]**
```
Hardest Part: Atomic Operations

// WRONG — race condition under concurrency
if (counter < limit) {
    counter++;          ← two threads both read 2, both increment to 3
    return ALLOWED;     ← allowed 4 requests when limit = 3
}

// RIGHT — atomic CAS (Compare-And-Swap)
long count = atomicLong.incrementAndGet();  ← hardware-level atomic
if (count <= limit) return ALLOWED;

JMH Result:
  1 thread  → 22,000,000 ops/s
  5 threads → 13,000,000 ops/s  ← CAS contention costs 40%
  The price of correctness under concurrency
```

**[SPEAKER NOTES]**
The hardest thing in this project wasn't picking a design pattern —
it was understanding why atomic operations matter.
Early on I thought "just increment a counter, how hard can it be?"
But under real concurrency, two threads can both read the same value,
both increment, and you end up allowing more requests than the limit.
That's a silent bug — no exception, just wrong behavior.
AtomicLong fixes this with a hardware-level Compare-And-Swap instruction.
But it's not free — our JMH benchmark showed that at 5 threads competing
on the same counter, throughput drops 40% from CAS retries.
That's the real lesson: correctness under concurrency always has a cost,
and measuring it is the only way to know how much you're paying.
Thanks everyone.
```
