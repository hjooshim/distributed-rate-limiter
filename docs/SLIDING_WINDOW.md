# Sliding Window — Feature Documentation

**Branch:** `kitty-feature`  
**Algorithm name:** `SLIDING_WINDOW`

---

## 1. Why Sliding Window?

The existing `LocalFixedWindowStrategy` divides time into fixed slots and resets the counter at each boundary. This creates a **boundary burst problem**: a client can send 2× the configured limit by firing requests at the very end of one window and the very start of the next.

```
Fixed Window — limit = 3, window = 10s
                                      ↓ window boundary
[0s ─────────── 10s][10s ─────────── 20s]
  req1 ✅ req2 ✅ req3 ✅ | req4 ✅ req5 ✅ req6 ✅
                           ← 6 requests in ≈ 1 second, limit never triggered
```

`RedisSlidingWindowStrategy` eliminates this by tracking the **exact timestamps of past requests**. The window rolls with time, so the quota always reflects what happened in the last N milliseconds — not since an arbitrary clock tick.

---

## 2. Algorithm

### How it works

Instead of a single counter, each client owns a **Sorted Set** in Redis where every entry records one real request timestamp. The "window" is simply the set of entries whose timestamp is within `[now - windowMs, now]`.

```
Sliding Window — limit = 3, window = 10s

t= 0s   req1 ✅   window = [req1]         count = 1
t= 3s   req2 ✅   window = [req1, req2]   count = 2
t= 6s   req3 ✅   window = [req1,req2,req3] count = 3
t= 8s   req4 ❌   window still has 3 entries → rejected
t=11s   req5 ✅   req1 slides out (11-0 > 10s) → count = 2 → allowed
```

The boundary burst problem is gone. At `t=9.9s`, the window still includes `req1` (added at `t=0s`); only after `t=10s` does `req1` slide out.

### Step-by-step per request

```
1. Get current time from Redis TIME  (avoids JVM clock skew)
2. ZREMRANGEBYSCORE key 0 (now - windowMs)  → trim expired entries
3. ZCARD key  → count requests inside window
4. if count < limit:
     ZADD key score=nowMs member="nowMs:microseconds"  → record request
     return allowed
   else:
     oldest = ZRANGE key 0 0 WITHSCORES  → find earliest entry
     waitMs = (oldest.score + windowMs) - now
     return rejected, retry-after = ceil(waitMs / 1000)
5. PEXPIRE key ttlMs  → refresh TTL
```

---

## 3. Architecture

### Inheritance chain

```
RateLimitStrategy (interface)
    └── AbstractRateLimitStrategy (validate → doEvaluate template)
            └── AbstractRedisRateLimitStrategy (key building, Lua execution, error handling)
                    └── RedisSlidingWindowStrategy  ← new
```

`RedisSlidingWindowStrategy` inherits:
- Input validation (`key`, `limit`, `windowMs`) from `AbstractRateLimitStrategy`
- Redis key construction (`rate_limit:sliding_window:<key>`) from `AbstractRedisRateLimitStrategy`
- Lua script execution and result parsing from `AbstractRedisRateLimitStrategy`
- Backend-unavailable error wrapping from `AbstractRedisRateLimitStrategy`

The class itself only contains three things: the script reference, the constructor, and `doEvaluate()`.

### How it plugs into the system

```
@RateLimit(algorithm = "SLIDING_WINDOW", limit = 3, windowMs = 10_000)
GET /api/sliding-window/primary
        │
        │  Spring AOP
        ▼
RateLimitAspect.enforce()
  → clientId  = ClientIdentityResolver.resolveCurrentClientId()
  → key       = "DemoController.slidingWindowPrimaryEndpoint:ip:203.0.113.10"
  → strategy  = StrategyRegistry.get("SLIDING_WINDOW")
  → decision  = strategy.evaluate(key, 3, 10_000)
        │
        ▼
RedisSlidingWindowStrategy.doEvaluate()
  → policyScopedKey = key + ":3:10000"
  → executeDecisionScript(SLIDING_WINDOW_SCRIPT, policyScopedKey, "3", "10000", ttl)
        │
        ▼
sliding_window.lua (atomic in Redis)
  → returns "1:0" (allowed) or "0:N" (rejected, retry after N seconds)
        │
        ├── allowed  → joinPoint.proceed() → HTTP 200
        └── rejected → RateLimitExceededException → HTTP 429 + Retry-After header
```

No changes were made to `RateLimitAspect`, `StrategyRegistry`, `GlobalExceptionHandler`,
or any other existing class. Spring auto-discovers the new `@Component` and registers it.

---

## 4. Redis State Model

| Property | Value |
|---|---|
| Data structure | Sorted Set (ZSET) |
| Key format | `rate_limit:sliding_window:<className>.<method>:<clientId>:<limit>:<windowMs>` |
| Score | Request timestamp in milliseconds |
| Member | `"<timestampMs>:<microseconds>"` — unique per request |
| TTL | `max(windowMs × 2, 1000)` ms — auto-expires idle keys |

### Example Redis state

For a client at `ip:203.0.113.10` calling `slidingWindowPrimaryEndpoint` with `limit=3, windowMs=10000`:

```
Key: rate_limit:sliding_window:DemoController.slidingWindowPrimaryEndpoint:ip:203.0.113.10:3:10000

ZRANGE key 0 -1 WITHSCORES:
  "1744829400000:123456"  →  1744829400000
  "1744829403000:234567"  →  1744829403000
  "1744829406000:345678"  →  1744829406000
```

Three entries in the sorted set = three requests inside the window.

### Why Sorted Set (not Hash)?

Token Bucket uses a Hash (`tokens`, `lastRefillMs`) because it maintains a computed state that changes continuously. Sliding Window needs to store the **actual history** of requests — one entry per request — so a Sorted Set with timestamp scores is the natural fit. `ZREMRANGEBYSCORE` trims it in O(log N + M) time.

---

## 5. Lua Script — `sliding_window.lua`

### Arguments

| ARGV | Value | Purpose |
|------|-------|---------|
| ARGV[1] | `limit` | Maximum requests allowed in window |
| ARGV[2] | `windowMs` | Window length in milliseconds |
| ARGV[3] | `ttlMs` | Redis key expiry in milliseconds |

Token Bucket passes 4 arguments (including `requestedTokens`). Sliding Window always consumes exactly one slot per request, so only 3 arguments are needed.

### Why Lua is required

Without atomic execution, two concurrent requests could race:

```
Thread A: ZCARD → count = 2   (under limit)
Thread B: ZCARD → count = 2   (under limit)
Thread A: ZADD  → count = 3   (allowed)
Thread B: ZADD  → count = 4   (also allowed — over limit!)
```

Redis runs Lua scripts atomically. No other client can interleave reads or writes
while the script executes, so the trim → count → record sequence is always atomic.

### Member uniqueness

```lua
local member = tostring(nowMs) .. ':' .. tostring(redisTime[2])
```

`redisTime[2]` is the microsecond component from Redis `TIME`. Appending it ensures
that two requests arriving within the same millisecond produce different members and
are both recorded as separate entries, rather than one overwriting the other.

### Retry-after calculation

```lua
local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
local waitMs = (oldest.score + windowMs) - nowMs
retryAfterSeconds = math.max(1, math.ceil(waitMs / 1000))
```

After `ZREMRANGEBYSCORE`, every remaining entry has `score > now - windowMs`.
Therefore `oldest.score + windowMs > now`, guaranteeing `waitMs > 0`.
The client is told exactly when the oldest request will slide out.

---

## 6. Comparison: All Three Algorithms

| | Fixed Window | Token Bucket | Sliding Window |
|--|--|--|--|
| Storage | JVM ConcurrentHashMap | Redis Hash | Redis Sorted Set |
| Accuracy | Approximate (boundary burst) | Approximate (time-based refill) | Exact (records every request) |
| Memory per key | O(1) | O(1) | O(limit) |
| Burst tolerance | No | Yes (up to capacity) | No |
| Boundary burst | Yes (2× limit possible) | No | No |
| Best for | Simple, local, low-stakes | APIs that allow short bursts | Strict per-client quota enforcement |

---

## 7. New Demo Endpoints

Two endpoints were added to `DemoController`:

```java
@RateLimit(limit = 3, windowMs = 10_000, algorithm = "SLIDING_WINDOW")
@GetMapping("/sliding-window/primary")
public Map<String, Object> slidingWindowPrimaryEndpoint()

@RateLimit(limit = 3, windowMs = 10_000, algorithm = "SLIDING_WINDOW")
@GetMapping("/sliding-window/secondary")
public Map<String, Object> slidingWindowSecondaryEndpoint()
```

`limit = 3` (vs Token Bucket's `limit = 1`) is intentional: the test suite needs to
demonstrate that the count increases correctly over multiple requests before rejection,
which requires more than one allowed request per window.

---

## 8. Tests

### `SlidingWindowIntegrationTest` — single node (MockMvc)

| Test | Scenario | Expected |
|------|----------|----------|
| Algorithm selection | 1 request to primary | `rate_limit:sliding_window:*` key exists in Redis |
| Window enforcement | 4 requests, same client, limit=3 | req 1–3 → 200, req 4 → 429 |
| Client isolation | Client A exhausted, Client B first request | Client B → 200 |
| Endpoint isolation | Primary exhausted, same client hits secondary | Secondary → 200 |
| Forwarded header | Requests with `X-Forwarded-For: " 203.0.113.40 , 10.0.0.1 "` | First value trimmed, used as client id |
| Remote address fallback | No `X-Forwarded-For` header | `RemoteAddr` used as client id |

### `DistributedSlidingWindowIntegrationTest` — two nodes (TestRestTemplate)

Two independent Spring application contexts point at one shared Redis container, simulating a real multi-instance deployment.

| Test | Scenario | Expected |
|------|----------|----------|
| Shared quota | Node-A and Node-B alternate requests for same client, limit=3 | 4th request (on Node-B) → 429 |
| Cross-node endpoint isolation | Node-A exhausts primary, Node-B hits secondary | Secondary → 200 |

The distributed test deliberately alternates between nodes to prove that the Sorted Set state is read and written consistently across instances, not cached locally.

---

## 9. Files Added or Changed

### New files

| File | Purpose |
|------|---------|
| `src/main/resources/scripts/sliding_window.lua` | Atomic trim + count + record Lua script |
| `src/main/java/.../strategy/RedisSlidingWindowStrategy.java` | Concrete strategy implementation |
| `src/test/java/.../controller/SlidingWindowIntegrationTest.java` | Single-node integration tests |
| `src/test/java/.../controller/DistributedSlidingWindowIntegrationTest.java` | Multi-node distributed tests |

### Modified files

| File | Change |
|------|--------|
| `DemoController.java` | Added `/sliding-window/primary` and `/sliding-window/secondary` endpoints |
| `StrategyRegistryValidationTest.java` | Added `SLIDING_WINDOW` bean to `KnownAlgorithmsConfig` so the startup validator test passes with the new endpoints |

---

## 10. Design Principles Applied

| Principle | Where |
|-----------|-------|
| **Open-Closed** | `RedisSlidingWindowStrategy` added with zero changes to `StrategyRegistry`, `RateLimitAspect`, or any existing strategy |
| **Single Responsibility** | Lua script owns the algorithm; Java class owns Spring wiring and argument construction |
| **Template Method** | `AbstractRateLimitStrategy.evaluate()` enforces validate → doEvaluate order; `RedisSlidingWindowStrategy` only implements `doEvaluate()` |
| **Dependency Inversion** | `RateLimitAspect` depends on `RateLimitStrategy` interface — it picked up the new strategy without modification |
| **Fail Fast** | `RateLimitAlgorithmValidator` catches unknown algorithm names at startup, not at runtime |
