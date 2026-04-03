# Rate Limiter — Test Suite Presentation

---

## 1. Test Architecture Overview

The project has **two distinct layers of tests**, each verifying a different scope:

```
┌─────────────────────────────────────────────────────────────┐
│  LAYER 2 — Integration Tests (RateLimitIntegrationTest)     │
│  Full HTTP Stack: MockMvc → AOP → Strategy → Response       │
├─────────────────────────────────────────────────────────────┤
│  LAYER 1 — Unit Tests (LocalFixedWindowStrategyTest)        │
│  Strategy in isolation: isAllowed() logic only              │
└─────────────────────────────────────────────────────────────┘
```

| | Unit Tests | Integration Tests |
|---|---|---|
| Spring context | No | Yes (full boot) |
| HTTP layer | No | Yes (MockMvc) |
| AOP Aspect | Not involved | Fully exercised |
| Speed | Fast (< 1s each) | Slower (10s window test) |
| Test count | 8 | 9 (incl. repeats) |

---

## 2. Unit Tests — `LocalFixedWindowStrategyTest`

### 2.1 Test Design Philosophy

```java
// Test is typed against the INTERFACE, not the concrete class
private RateLimitStrategy strategy;   // ← not LocalFixedWindowStrategy

@BeforeEach
void setUp() {
    strategy = new LocalFixedWindowStrategy();
}
```

This demonstrates **Liskov Substitution Principle (LSP)**: the same test suite
can run against any future `RateLimitStrategy` implementation with zero changes.

---

### 2.2 Functional Tests

#### Test 1 — Allow within limit

```
Input:  3 requests, limit = 3, window = 60s
Result: ✅ ✅ ✅  (all 3 allowed)
```

#### Test 2 — Reject at limit boundary

```
Input:  4 requests, limit = 3, window = 60s
Result: ✅ ✅ ✅ ❌  (4th rejected)
```

The boundary is **inclusive**: request #3 is allowed (count = 3 ≤ 3), request #4 is rejected (count = 4 > 3).

#### Test 3 — Key isolation

```
key-A: ✅ ✅  (count=2, at limit)
key-B: ✅     (independent counter, unaffected)
```

Each unique key maintains its own `WindowCounter` in `ConcurrentHashMap`.

#### Test 4 — Edge case: limit = 1

```
Request 1: ✅  (count=1 ≤ 1)
Request 2: ❌  (count=2 > 1)
```

#### Test 5 — Window reset (time-based)

```
Window [t=0 … t=100ms], limit = 2:
  Request 1: ✅ (count=1)
  Request 2: ✅ (count=2)
  Request 3: ❌ (count=3, over limit)

Thread.sleep(150ms) → window rolls over

New window [t=150ms … t=250ms]:
  Request 4: ✅ (fresh counter, count=1)
```

The window ID is `System.currentTimeMillis() / windowMs`.
After 150ms with `windowMs=100`, the ID increments — a new `WindowCounter` is created.

---

### 2.3 Concurrency Tests

#### Test 6 — Thread safety: 20 threads vs limit = 5

**Setup:**
```
Threads:  20
Limit:     5
Window:   60s
Key:      "concurrent-key"

CountDownLatch startLatch = new CountDownLatch(1);
// All 20 threads block at startLatch.await()
// startLatch.countDown() releases all simultaneously → maximum contention
```

**Expected result:**
```
Allowed:   5   (exactly)
Rejected: 15   (exactly)
```

**Why AtomicLong prevents over-counting:**

| Scenario | Without AtomicLong (plain long) | With AtomicLong (CAS) |
|---|---|---|
| Thread 1 reads count | 4 | CAS: read + write in one atomic op |
| Thread 2 reads count | 4 (stale) | CAS: detects conflict, retries |
| Both threads write | count = 5 (but 2 requests passed!) | count = 5 (only 1 request passed) |
| Result | Over-limit allowed | Exactly on-limit |

---

#### Test 7 — High-intensity stress test: 10,000 threads vs limit = 100 (`@RepeatedTest(10)`)

**Setup:**
```
Threads:    10,000
Limit:         100
Window:       60s
Repeats:       10
```

**What this proves:**

| Run | Allowed | Rejected | Pass? |
|-----|---------|----------|-------|
| 1   | 100     | 9,900    | ✅ |
| 2   | 100     | 9,900    | ✅ |
| 3   | 100     | 9,900    | ✅ |
| … (×10) | 100  | 9,900   | ✅ |

**Sample console output:**
```
[Stress Test] Throughput: 10000 requests | Duration: 312 ms | Avg Latency: 0.0312 ms
[Stress Test] Throughput: 10000 requests | Duration: 287 ms | Avg Latency: 0.0287 ms
```

At 10,000 concurrent threads, the system enforces the limit **exactly** — not 99, not 101.
This demonstrates that `AtomicLong.incrementAndGet()` is correct under extreme contention.

---

## 3. Integration Tests — `RateLimitIntegrationTest`

### 3.1 What Integration Tests Cover That Unit Tests Cannot

```
Unit test scope:
  strategy.isAllowed("key", 3, 10000)  →  true/false

Integration test scope:
  HTTP GET /api/strict  →  HTTP 200 / HTTP 429
                           + response body JSON
                           + Retry-After header
                           + AOP aspect wiring
                           + Spring context
```

The integration tests verify that **the entire chain is wired correctly**,
not just the algorithm in isolation.

---

### 3.2 Test-by-Test Breakdown

#### Test 1 — Allow within limit (HTTP 200)

```
Endpoint:  GET /api/strict   (@RateLimit limit=3, windowMs=10_000)
Requests:  3
Expected:  HTTP 200 × 3
JSON check: $.message exists
```

#### Test 2 — Reject over limit (HTTP 429)

```
Requests 1-3: HTTP 200 ✅
Request 4:    HTTP 429 ❌
```

Verifies the AOP aspect correctly intercepts the 4th call and throws
`RateLimitExceededException`, which `GlobalExceptionHandler` converts to HTTP 429.

#### Test 3 — Retry-After header validation

```
Response headers on HTTP 429:
  Retry-After: <value>   ← must exist (RFC 6585 compliance)
```

Tells the client when it can retry. Without this, clients would have to guess.

#### Test 4 — JSON error body structure

```json
{
  "status":    429,
  "error":     "Too Many Requests",
  "message":   "...",
  "key":       "DemoController.strictEndpoint",
  "limit":     3,
  "windowMs":  10000,
  "timestamp": "..."
}
```

All 7 fields validated. The `key` field shows exactly which method was rate-limited,
which is critical for debugging in production.

#### Test 5 — Endpoint isolation

```
/api/strict  → exhaust limit (3 req) → HTTP 429 ❌
/api/normal  → first request          → HTTP 200 ✅
```

Rate limit keys are scoped by method name (`"DemoController.strictEndpoint"` vs
`"DemoController.normalEndpoint"`), so each endpoint has its own independent quota.

#### Test 6 — No `@RateLimit` = never blocked

```
/api/free: 20 consecutive requests
Expected: HTTP 200 × 20  (AOP aspect ignores methods without @RateLimit)
```

Demonstrates that rate limiting is **opt-in**, not applied globally.

#### Test 7 — Recovery after window reset

```
Timeline:
  t=0s:      req1-3 → HTTP 200 ✅ ✅ ✅
  t=0s:      req4   → HTTP 429 ❌
  t=0–10s:   window still active (windowMs=10_000)
  Thread.sleep(10_100ms)
  t=10.1s:   req5   → HTTP 200 ✅  (new window, counter reset)
```

This is the **only test that involves real time** (`Thread.sleep`).
It validates the window rollover mechanism end-to-end through the HTTP stack.

---

### 3.3 Concurrency Tests (Full HTTP Stack)

#### Test 8 — 30 threads vs limit = 3

```
Endpoint:   GET /api/strict  (limit=3)
Threads:    30
All threads released simultaneously via CountDownLatch
```

**Results:**
```
[Concurrent] allowed=3, rejected=27 (limit=3, threads=30)
```

This catches race conditions **at the AOP layer** — not just the strategy layer.
If `RateLimitAspect.enforce()` had any thread-safety issue, this test would
see `allowed > 3`.

#### Test 9 — Repeated bursts: 50 threads vs limit = 10 (`@RepeatedTest(5)`)

```
Endpoint:   GET /api/normal  (limit=10)
Threads:    50
Repeats:    5   (@RepeatedTest — @BeforeEach resets counters each run)
```

**Results across all 5 runs:**

| Run | Allowed | Rejected | Status |
|-----|---------|----------|--------|
| 1   | 10      | 40       | ✅ PASS |
| 2   | 10      | 40       | ✅ PASS |
| 3   | 10      | 40       | ✅ PASS |
| 4   | 10      | 40       | ✅ PASS |
| 5   | 10      | 40       | ✅ PASS |

**Why `@RepeatedTest` matters:** verifies there is no JIT warm-up drift or
shared-state leakage between runs. The limit holds at exactly 10 every single time.

---

## 4. Complete Test Coverage Summary

### Unit Tests (8 total)

| # | Test | Scenario | Threads | Limit | Result |
|---|------|----------|---------|-------|--------|
| 1 | Allow within limit | 3 req, limit=3 | 1 | 3 | all ✅ |
| 2 | Reject over limit | 4 req, limit=3 | 1 | 3 | 3✅ 1❌ |
| 3 | Key isolation | 2 keys | 1 | 2 | independent |
| 4 | Edge case limit=1 | 2 req | 1 | 1 | 1✅ 1❌ |
| 5 | Window reset | sleep 150ms | 1 | 2 | resets ✅ |
| 6 | Thread safety | simultaneous | 20 | 5 | exact 5✅ 15❌ |
| 7 | getName() | — | 1 | — | "FIXED_WINDOW" |
| 8 | Stress test ×10 | simultaneous | 10,000 | 100 | exact 100✅ |

### Integration Tests (9 total)

| # | Test | Scope | Threads | Limit | Result |
|---|------|-------|---------|-------|--------|
| 1 | HTTP 200 within limit | full stack | 1 | 3 | 200×3 |
| 2 | HTTP 429 over limit | full stack | 1 | 3 | 429 on req 4 |
| 3 | Retry-After header | response | 1 | 3 | header present |
| 4 | JSON error body | response | 1 | 3 | 7 fields valid |
| 5 | Endpoint isolation | two endpoints | 1 | 3/10 | independent |
| 6 | No annotation = free | AOP skipping | 1 | none | 200×20 |
| 7 | Window recovery | time-based | 1 | 3 | 200 after 10.1s |
| 8 | HTTP concurrent | full stack | 30 | 3 | exact 3✅ 27❌ |
| 9 | Repeated bursts ×5 | full stack | 50 | 10 | exact 10✅ ×5 |

---

## 5. Key Testing Decisions

### Why two separate test layers?

Unit tests are fast and precise — they isolate the algorithm itself.
Integration tests verify that all components are wired together correctly.
A bug in `RateLimitAspect` would pass unit tests but fail integration tests.

### Why `@BeforeEach strategy.reset()`?

All integration tests share one Spring context (one `LocalFixedWindowStrategy` bean).
Without reset, a test exhausting the quota bleeds into the next test.
`reset()` calls `counters.clear()` — it exists only for test isolation.

### Why `CountDownLatch` in concurrency tests?

Without it, threads start at staggered times (thread-pool scheduling delay).
`startLatch.await()` ensures all threads compete at the **exact same instant**,
maximizing contention and making the race condition reliably reproducible.

### Why `@RepeatedTest`?

A concurrency bug might only manifest 1 in 10 runs due to thread scheduling.
Running the same test 5–10 times catches flaky failures that a single run would miss.