# Token Bucket - Feature Documentation

---

## 1. Why Token Bucket?

The existing `LocalFixedWindowStrategy` resets its counter at hard window boundaries. That is simple, but it is not ideal for APIs that should tolerate short bursts and then recover gradually.

A token bucket models that behavior directly: each client owns a bucket with capacity `limit`, every request spends one token, and tokens refill continuously over time.

```
Token Bucket - capacity = 5, window = 10s

t=0s   bucket starts full -> 5 tokens
req1   allowed           -> 4
req2   allowed           -> 3
req3   allowed           -> 2
req4   allowed           -> 1
req5   allowed           -> 0
req6   rejected          -> empty bucket
t=2s   about 1 token refilled
req7   allowed           -> 0
```

This gives two useful properties:

- short bursts are allowed up to the bucket capacity
- the long-term average rate is still enforced

Compared with Sliding Window, Token Bucket is intentionally less strict. Sliding Window records exact request history; Token Bucket stores only the computed bucket state and is better when burst tolerance is part of the policy.

---

## 2. Algorithm

### How it works

Instead of counting requests in a hard slot or storing every request timestamp, each client owns a Redis Hash with two fields:

- `tokens` - how many tokens remain right now
- `lastRefillMs` - when the bucket was last recalculated

The refill rate is:

```text
refillRate = limit / windowMs tokens per millisecond
```

Tokens can be fractional internally. That matters because refill is gradual, not all-at-once.

```
Token Bucket - capacity = 2, window = 1000ms

t=0ms    req1 allowed   tokens = 1
t=0ms    req2 allowed   tokens = 0
t=0ms    req3 rejected  tokens = 0
t=650ms  req4 allowed   about 1.3 tokens refilled -> enough for one request
t=650ms  after req4     about 0.3 tokens remain
t=650ms  req5 rejected  not enough for another full token
```

The refill is lazy: Redis does not run a background job. The bucket is recalculated only when a request arrives.

### Step-by-step per request

```text
1. Get current time from Redis TIME  (avoids JVM clock skew)
2. HGET key tokens and lastRefillMs
3. if state missing:
     tokens = capacity
     lastRefillMs = now
4. else:
     elapsedMs = now - lastRefillMs
     refillTokens = (elapsedMs * capacity) / windowMs
     tokens = min(capacity, tokens + refillTokens)
     lastRefillMs = now
5. if tokens >= requestedTokens:
     tokens = tokens - requestedTokens
     return allowed
   else:
     missingTokens = requestedTokens - tokens
     waitMs = ceil((missingTokens * windowMs) / capacity)
     return rejected, retry-after = ceil(waitMs / 1000)
6. HSET key tokens lastRefillMs
7. PEXPIRE key ttlMs
```

`RedisTokenBucketStrategy` always passes `requestedTokens = 1`, so one HTTP request consumes one token.

---

## 3. Architecture

### Inheritance chain

```text
RateLimitStrategy (interface)
    -> AbstractRateLimitStrategy (validate -> doEvaluate template)
            -> AbstractRedisRateLimitStrategy (key building, Lua execution, error handling)
                    -> RedisTokenBucketStrategy  <- distributed token bucket
```

`RedisTokenBucketStrategy` inherits:

- input validation (`key`, `limit`, `windowMs`) from `AbstractRateLimitStrategy`
- Redis key construction (`rate_limit:token_bucket:<key>`) from `AbstractRedisRateLimitStrategy`
- Lua script execution and result parsing from `AbstractRedisRateLimitStrategy`
- backend-unavailable error wrapping from `AbstractRedisRateLimitStrategy`

The class itself mainly does three things:

- loads `token_bucket.lua`
- computes the TTL
- scopes the logical key by policy before calling Redis

### How it plugs into the system

```text
@RateLimit(algorithm = "TOKEN_BUCKET", limit = 1, windowMs = 60_000)
GET /api/token-bucket/primary
        |
        |  Spring AOP
        v
RateLimitAspect.enforce()
  -> clientId  = ClientIdentityResolver.resolveCurrentClientId()
  -> key       = "DemoController.tokenBucketPrimaryEndpoint:ip:203.0.113.10"
  -> strategy  = StrategyRegistry.get("TOKEN_BUCKET")
  -> decision  = strategy.evaluate(key, 1, 60_000)
        |
        v
RedisTokenBucketStrategy.doEvaluate()
  -> policyScopedKey = key + ":1:60000"
  -> executeDecisionScript(TOKEN_BUCKET_SCRIPT, policyScopedKey, "1", "60000", "1", ttl)
        |
        v
token_bucket.lua (atomic in Redis)
  -> returns "1:0" (allowed) or "0:N" (rejected, retry after N seconds)
        |
        +-- allowed  -> joinPoint.proceed() -> HTTP 200
        `-- rejected -> RateLimitExceededException -> HTTP 429 + Retry-After header
```

The aspect and registry do not need token-bucket-specific branches. They continue to work through the `RateLimitStrategy` interface.

---

## 4. Redis State Model

| Property | Value |
|---|---|
| Data structure | Hash |
| Key format | `rate_limit:token_bucket:<className>.<method>:<clientId>:<limit>:<windowMs>` |
| Field `tokens` | Remaining tokens, possibly fractional |
| Field `lastRefillMs` | Redis timestamp of the last refill calculation |
| TTL | `max(windowMs * 2, 1000)` ms - auto-expires idle buckets |

### Example Redis state

For a client at `ip:203.0.113.10` calling `tokenBucketPrimaryEndpoint` with `limit=1, windowMs=60000`:

```text
Key: rate_limit:token_bucket:DemoController.tokenBucketPrimaryEndpoint:ip:203.0.113.10:1:60000

HGETALL key after the first allowed request:
  "tokens"        -> "0"
  "lastRefillMs"  -> "1744829400000"
```

If the next request arrives 15 seconds later, the script recalculates refill lazily:

```text
15s elapsed, refill = 15/60 = 0.25 tokens

HGETALL key after that rejected request:
  "tokens"        -> "0.25"
  "lastRefillMs"  -> "1744829415000"
```

So the stored state is not request history. It is the current bucket snapshot as of the most recent evaluation.

### Why Hash (not Sorted Set)?

Sliding Window needs a Sorted Set because it stores real request history. Token Bucket does not need the history. It only needs the current computed state, so a small Redis Hash is enough and keeps memory per key at `O(1)`.

---

## 5. Lua Script - `token_bucket.lua`

### Arguments

| ARGV | Value | Purpose |
|------|-------|---------|
| ARGV[1] | `capacity` | Bucket size / maximum tokens |
| ARGV[2] | `windowMs` | Time required to refill `capacity` tokens |
| ARGV[3] | `requestedTokens` | Tokens requested by this operation |
| ARGV[4] | `ttlMs` | Redis key expiry in milliseconds |

Java currently passes `requestedTokens = 1`, but the script is already shaped to support multi-token consumption if the project ever needs weighted requests.

### Why Lua is required

Without atomic execution, two concurrent requests could race:

```text
Thread A: HGET tokens -> 1
Thread B: HGET tokens -> 1
Thread A: consume -> 0, allow
Thread B: consume -> 0, also allow
```

That would overspend the bucket. Redis executes Lua scripts atomically, so refill, consume, retry-after calculation, and persistence happen as one indivisible operation.

### Why Redis TIME is required

If each JVM used its own clock, two app nodes could disagree about how many tokens should have refilled. The script uses Redis `TIME`, so every node shares the same time source.

### Fractional refill math

```lua
local refillTokens = (elapsedMs * capacity) / windowMs
tokens = math.min(capacity, tokens + refillTokens)
```

This is why the bucket refills smoothly instead of in whole-token jumps at fixed boundaries.

### Retry-after calculation

```lua
local missingTokens = requestedTokens - tokens
local waitMs = math.ceil((missingTokens * windowMs) / capacity)
retryAfterSeconds = math.max(1, math.ceil(waitMs / 1000))
```

The client is told how long it should wait before enough tokens are expected to exist for the rejected request.

---

## 6. Comparison: All Three Algorithms

| | Fixed Window | Token Bucket | Sliding Window |
|--|--|--|--|
| Storage | JVM ConcurrentHashMap | Redis Hash | Redis Sorted Set |
| Accuracy | Approximate (boundary burst) | Approximate (refill model) | Exact (records every request) |
| Memory per key | O(1) | O(1) | O(limit) |
| Burst tolerance | No | Yes (up to capacity) | No |
| Boundary burst | Yes (2x limit possible) | No | No |
| Best for | Simple, local, low-stakes | APIs that allow short bursts | Strict per-client quota enforcement |

---

## 7. New Demo Endpoints

Two endpoints use `TOKEN_BUCKET` in `DemoController`:

```java
@RateLimit(limit = 1, windowMs = 60_000, algorithm = "TOKEN_BUCKET")
@GetMapping("/token-bucket/primary")
public Map<String, Object> tokenBucketPrimaryEndpoint()

@RateLimit(limit = 1, windowMs = 60_000, algorithm = "TOKEN_BUCKET")
@GetMapping("/token-bucket/secondary")
public Map<String, Object> tokenBucketSecondaryEndpoint()
```

`limit = 1` is intentional. It makes the integration tests easy to read:

- the first request for a client is allowed
- the second immediate request to the same endpoint is rejected
- a different endpoint or different client still gets its own bucket

---

## 8. Tests

### `RedisTokenBucketStrategyTest` - strategy-level behavior against real Redis

| Test | Scenario | Expected |
|------|----------|----------|
| Allow while tokens remain | Same key, capacity=3 | first requests -> allowed |
| Empty bucket | Same key, capacity=2 | third immediate request -> rejected |
| Full refill | `limit=1, windowMs=500`, sleep 650ms | next request -> allowed |
| Partial refill | `limit=2, windowMs=1000`, sleep 650ms | one request allowed, next one rejected |
| Retry-after | `limit=2, windowMs=4000`, sleep 1200ms after exhaustion | rejected decision reports `retryAfterSeconds = 1` |
| Key isolation | Two logical keys | buckets remain independent |
| Policy isolation | Same logical key, different `limit/windowMs` | separate Redis buckets |
| Concurrency | 20 threads compete for capacity=5 | exactly 5 allowed, 15 rejected |

### `TokenBucketIntegrationTest` - single node (MockMvc)

| Test | Scenario | Expected |
|------|----------|----------|
| Algorithm selection | 1 request to primary | `rate_limit:token_bucket:*` key exists in Redis |
| Client isolation | Client A exhausted, Client B first request | Client B -> 200 |
| Endpoint isolation | Primary exhausted, same client hits secondary | Secondary -> 200 |
| Forwarded header | Requests with `X-Forwarded-For: " 203.0.113.55 , 10.0.0.1 "` | First value trimmed, used as client id |
| Remote address fallback | No `X-Forwarded-For` header | `RemoteAddr` used as client id |

### `DistributedTokenBucketIntegrationTest` - two nodes (TestRestTemplate)

Two independent Spring application contexts point at one shared Redis container, simulating a real multi-instance deployment.
The Redis backend is started as a Docker container through Testcontainers (`GenericContainer<>("redis:7.4-alpine")`), so the test exercises two app nodes against one shared external service rather than in-memory state.

| Test | Scenario | Expected |
|------|----------|----------|
| Shared quota | Node-A and Node-B call primary for same client | first request -> 200, second -> 429 |
| Cross-node endpoint isolation | Node-A hits primary, Node-B hits secondary | both -> 200 |

This is the project's local distributed-environment simulation: separate Spring application contexts behave like separate servers, while the Dockerized Redis container provides the shared state they coordinate through.

### `RateLimitBackendFailureIntegrationTest` - fail-closed behavior

| Test | Scenario | Expected |
|------|----------|----------|
| Redis unavailable | `TOKEN_BUCKET` endpoint called with unreachable Redis | HTTP 503 with structured backend-unavailable payload |
| Local fallback health | `FIXED_WINDOW` endpoint called with unreachable Redis | still HTTP 200 |

---

## 9. Key Files

| File | Purpose |
|------|---------|
| `src/main/resources/scripts/token_bucket.lua` | Atomic refill + consume + retry-after Lua script |
| `src/main/java/com/drl/ratelimiter/strategy/AbstractRedisRateLimitStrategy.java` | Shared Redis key, script, TTL, and error-handling infrastructure |
| `src/main/java/com/drl/ratelimiter/strategy/RedisTokenBucketStrategy.java` | Concrete token-bucket strategy |
| `src/test/java/com/drl/ratelimiter/strategy/RedisTokenBucketStrategyTest.java` | Strategy-level tests against real Redis |
| `src/test/java/com/drl/ratelimiter/controller/TokenBucketIntegrationTest.java` | Single-node HTTP integration tests |
| `src/test/java/com/drl/ratelimiter/controller/DistributedTokenBucketIntegrationTest.java` | Multi-node distributed integration tests |
| `src/test/java/com/drl/ratelimiter/controller/RateLimitBackendFailureIntegrationTest.java` | Fail-closed behavior when Redis is unavailable |
| `src/main/java/com/drl/ratelimiter/controller/DemoController.java` | Demo endpoints for token-bucket coverage |

---

## 10. Design Principles Applied

| Principle | Where |
|-----------|-------|
| **Open-Closed** | `RedisTokenBucketStrategy` plugs into the strategy registry without requiring token-bucket-specific logic in the aspect |
| **Single Responsibility** | Lua script owns token math; `AbstractRedisRateLimitStrategy` owns Redis infrastructure; `RedisTokenBucketStrategy` owns Spring wiring and policy scoping |
| **Template Method** | `AbstractRateLimitStrategy.evaluate()` enforces validate -> doEvaluate order |
| **Dependency Inversion** | `RateLimitAspect` depends on the `RateLimitStrategy` interface, not a concrete bucket implementation |
| **Fail Closed** | Redis failures become `RateLimitBackendUnavailableException`, which surfaces as HTTP 503 instead of silently allowing traffic |
| **Consistent Time Source** | Redis `TIME` prevents cross-node clock skew from corrupting refill decisions |
