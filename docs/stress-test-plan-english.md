# Stress Test Plan — Distributed Rate Limiter

## Overview

This document covers two distinct types of testing that go beyond the existing
functional integration suite:

- **Plan A — Correctness Stress Tests** (JUnit): verify that the rate limiter
  enforces limits accurately under extreme concurrency (200–500 threads). These
  are written as standard JUnit tests and run with Maven.

- **Plan B — Throughput Benchmarking** (JMH + JMeter): measure actual RPS and
  latency numbers. These require external tools and are not part of the Maven
  test suite.

The performance ceiling numbers in Section 2 are **theoretical estimates
derived from code analysis**, not measured values. Section 5 describes how
to produce real measured numbers.

---

## 1. System Architecture

```
HTTP Request
    │
    ▼
RateLimitAspect  (AOP — builds key = ClassName.method:clientId)
    │
    ▼
StrategyRegistry.get(algorithm)
    │
    ├── FIXED_WINDOW   → LocalFixedWindowStrategy
    │                    ConcurrentHashMap<windowKey, WindowCounter>
    │                    WindowCounter wraps AtomicLong (lock-free CAS)
    │
    ├── TOKEN_BUCKET   → RedisTokenBucketStrategy
    │                    Redis Hash (tokens, lastRefillMs)
    │                    Lua script: refill + consume, 1 atomic EVAL
    │
    └── SLIDING_WINDOW → RedisSlidingWindowStrategy
                         Redis Sorted Set (score = request timestamp ms)
                         Lua script: trim + count + ZADD, 1 atomic EVAL
```

Per-request overhead outside the strategy: AOP reflection (MethodSignature
lookup) + identity resolution + one StrategyRegistry map lookup. All calls
are **synchronous and blocking** — no async or reactive path exists.

---

## 2. Performance Ceiling Analysis (Theoretical Estimates)

> **Note**: the numbers below are derived from code structure and known
> characteristics of the underlying data structures. They have **not** been
> verified by benchmarks. See Section 5 for how to measure them.

### 2.1 LocalFixedWindowStrategy

| Factor | Detail |
|---|---|
| Data structure | `ConcurrentHashMap<String, WindowCounter>` |
| Hot path | `computeIfAbsent` → `AtomicLong.incrementAndGet()` |
| Lock-free? | Yes — hardware CAS at `incrementAndGet`; segment lock only at first-create |
| Cleanup cost | O(N) map scan triggered every 100 requests |
| Memory growth | One `WindowCounter` per (endpoint × client × windowId) |

**Bottlenecks under stress:**

1. **CAS retry storm** — At 200+ threads per key, `AtomicLong` CAS retries on
   every collision. This shows up as CPU spin, not blocking — throughput drops
   but threads are never parked.

2. **Lazy cleanup contention** — `cleanupOldWindows` runs a full map scan every
   100 requests. At high RPS, multiple threads can collide at this boundary,
   causing brief pauses as `ConcurrentHashMap.removeIf` acquires segment locks.

3. **`computeIfAbsent` allocation spike** — The first request of each new window
   slot allocates a `WindowCounter`. Under extreme concurrency, multiple threads
   may race to create the same key; only one survives but allocation pressure
   spikes briefly.

**Estimated ceiling (unverified):**
- Single key: **~50,000–100,000 RPS** before GC pressure dominates
- Multi-key: memory-bound by live `WindowCounter` count

---

### 2.2 RedisTokenBucketStrategy

| Factor | Detail |
|---|---|
| Data structure | Redis Hash — 2 fields: `tokens`, `lastRefillMs` |
| Per-request ops | 1 `EVAL`: `TIME` + 2× `HGET` + `HSET` + `PEXPIRE` |
| Atomicity | Lua script runs atomically in Redis |
| Time source | `Redis TIME` — immune to JVM clock skew |
| State size | Fixed 2 fields per (endpoint × client × policy) — O(1) |

**Bottlenecks under stress:**

1. **Network RTT dominates** — At 1 ms RTT, one thread handles at most ~1,000
   decisions/sec. A 20-connection pool gives ~20,000 RPS. Latency, not Redis
   compute, is the ceiling.

2. **Redis single-threaded execution** — All Lua scripts serialize in Redis.
   At very high RPS, command queuing in Redis's pipeline becomes measurable.

3. **Connection pool saturation** — Lettuce's default pool has a fixed size.
   Under 100+ concurrent threads, threads queue for a connection if the pool is
   smaller than the thread count.

**Estimated ceiling (unverified):**
- Localhost Redis: **~5,000–20,000 RPS** depending on pool configuration
- Remote Redis (1–5 ms RTT): **~1,000–5,000 RPS** per app node

---

### 2.3 RedisSlidingWindowStrategy

| Factor | Detail |
|---|---|
| Data structure | Redis Sorted Set — one member per in-window request |
| Per-request ops | 1 `EVAL`: `TIME` + `ZREMRANGEBYSCORE` + `ZCARD` + `ZADD` + `PEXPIRE` |
| Atomicity | Lua script runs atomically |
| State size | One ZSET entry per allowed request — O(limit) per key |

**Bottlenecks under stress:**

1. **`ZREMRANGEBYSCORE` is O(log N + M)** — M is the number of expired entries
   trimmed per call. Under sustained high-RPS load, M grows and trim cost
   increases noticeably compared to the O(1) hash ops of Token Bucket.

2. **ZSET member growth** — A window of `limit=1000` over 60 s retains up to
   1,000 ZSET members per key. Memory and trim cost scale linearly with the
   configured limit.

3. **Timestamp uniqueness** — Members use `"nowMs:microseconds"` from Redis TIME.
   A microsecond collision would cause ZADD to silently overwrite an existing
   entry, undercounting the window. In practice, Redis TIME advances fast enough
   to make this extremely rare.

**Estimated ceiling (unverified):**
- Localhost Redis: **~3,000–10,000 RPS** — slower than Token Bucket at high
  concurrency due to ZSET operation cost
- Remote Redis: **~500–3,000 RPS** per app node

---

### 2.4 Known Correctness Risks

| Risk | Strategy | Description |
|---|---|---|
| Boundary burst | FIXED_WINDOW | Client can send 2× limit across a window boundary. Known design limitation. |
| Cleanup race | FIXED_WINDOW | `cleanupOldWindows` could evict a counter mid-flight at high concurrency. Low probability. |
| Pool exhaustion | TOKEN_BUCKET / SLIDING_WINDOW | Under >100 threads, Lettuce pool may queue requests; timeout behavior is untested. |
| Redis failover clock jump | All Redis | If Redis is replaced mid-flight, `Redis TIME` may jump; not mitigated. |
| ZADD member collision | SLIDING_WINDOW | Two requests in the same Redis microsecond collide; one entry overwritten, window undercounted. |

---

## 3. Plan A — Correctness Stress Tests (JUnit)

**Goal**: verify that limits are enforced exactly under high concurrency.
These tests use JUnit 5 + MockMvc / TestRestTemplate and run with Maven.
They do **not** measure throughput.

**What distinguishes these from the existing integration tests:**
- Thread counts are 5–66× higher (30 → 200+)
- Multiple rounds of sustained load
- Concurrent cross-endpoint and cross-client scenarios

---

### 3.1 Local Fixed-Window Tests — `StressTest.java`

No external dependencies. Uses MockMvc against the in-memory strategy.

#### ST-1 — 200 Concurrent Threads on One Endpoint

| | |
|---|---|
| Endpoint | `GET /api/strict` (`@RateLimit(limit=3, windowMs=10_000)`) |
| Threads | 200, released simultaneously via `CountDownLatch` |
| Pass criteria | allowed = **3**, rejected = **197**, no unexpected status codes |
| What it validates | `AtomicLong` CAS correctness at 66× current test concurrency. Any non-atomic increment allows > 3. |

#### ST-2 — Two Endpoints Under Simultaneous Load

| | |
|---|---|
| Endpoints | `/api/strict` (limit=3) and `/api/normal` (limit=10) |
| Threads | 200 total — 100 per endpoint, interleaved in the same executor |
| Pass criteria | strict: allowed=**3**; normal: allowed=**10** |
| What it validates | Key isolation under cross-endpoint concurrent load. A key collision in `ConcurrentHashMap` would cause one endpoint to exceed its limit. |

#### ST-3 — 500 Threads on the Unrestricted Endpoint

| | |
|---|---|
| Endpoint | `GET /api/free` (no `@RateLimit`) |
| Threads | 500 |
| Pass criteria | All 500 responses = **HTTP 200** |
| What it validates | AOP annotation-bypass path is thread-safe. A TOCTOU race in annotation lookup would produce unexpected 429s. |

#### ST-4 — 5 Burst Rounds × 50 Threads

| | |
|---|---|
| Endpoint | `GET /api/strict` (limit=3) |
| Setup | Each round: `strategy.reset()`, then release 50 threads |
| Pass criteria | Every round: allowed = **3**, rejected = **47** |
| What it validates | `reset()` leaves no residual state across rounds. Mirrors what `cleanupOldWindows` does on window rollover. |

**Run:**
```bash
./mvnw test -Dtest=StressTest
```

---

### 3.2 Distributed Strategy Tests — `DistributedStressTest.java`

Uses TestContainers Redis. Flushes Redis in `@AfterEach`.

#### DST-1 — Token Bucket: 100 Concurrent Threads

| | |
|---|---|
| Endpoint | `GET /api/token-bucket/primary` (limit=1) |
| Threads | 100, same client IP (`X-Forwarded-For: 203.0.113.10`) |
| Pass criteria | allowed = **1**, rejected = **99** |
| What it validates | Lua atomicity. Without atomic `EVAL`, two threads both reading `tokens=1` would each allow themselves. |

#### DST-2 — Sliding Window: 100 Concurrent Threads

| | |
|---|---|
| Endpoint | `GET /api/sliding-window/primary` (limit=3) |
| Threads | 100, same client IP |
| Pass criteria | allowed = **3**, rejected = **97** |
| What it validates | `ZREMRANGEBYSCORE + ZCARD + ZADD` atomicity. Non-atomic execution would allow > 3. |

#### DST-3 — Multi-Node: 2 Nodes × 50 Threads, Shared Quota

| | |
|---|---|
| Endpoint | `GET /api/sliding-window/primary` (limit=3) |
| Setup | 2 Spring contexts (node-A, node-B) → same Redis; 50 threads per node, same client IP |
| Pass criteria | Total allowed across both nodes = **3**, rejected = **97** |
| What it validates | Distributed quota sharing. If each node used local counters, total allowed would be up to 6. |

#### DST-4 — Multi-Client Isolation: 5 Clients × 10 Threads

| | |
|---|---|
| Endpoint | `GET /api/token-bucket/primary` (limit=1 per client) |
| Setup | 5 client IPs (`203.0.113.101`–`.105`), 10 threads each, all 50 released simultaneously |
| Pass criteria | Total allowed = **5** (1 per client), rejected = **45** |
| What it validates | Per-client key isolation. Verifies `policyScopedKey = key + ":" + limit + ":" + windowMs` keeps buckets independent. |

**Run:**
```bash
./mvnw test -Dtest=DistributedStressTest
```

**All tests print a summary line:**
```
[ST-1]  allowed=3,   rejected=197  (limit=3, threads=200)
[DST-3] allowed=3,   rejected=97   across 2 nodes (limit=3, total=100)
```

---

## 4. Plan A — What These Tests Do NOT Cover

| Gap | Note |
|---|---|
| Actual throughput (RPS) | MockMvc adds scheduling overhead; not a throughput harness |
| Redis connection pool exhaustion | Would require tuning Lettuce pool size and observing timeouts |
| Latency distribution (p99, p999) | Needs a load tool, not JUnit |
| Sustained load over minutes | JUnit timeouts make this impractical |
| GC pause impact on window accuracy | Requires prolonged load + GC logging |

---

## 5. Plan B — Throughput Benchmarking (JMH + JMeter)

**Goal**: produce real RPS and latency numbers to replace the theoretical
estimates in Section 2. Two layers are benchmarked separately to isolate
where the bottleneck actually is.

```
Layer 1: Strategy layer (no HTTP, no Spring)  → JMH
         Measures algorithm ceiling, free of HTTP overhead.

Layer 2: Full HTTP path                        → JMeter
         Measures end-to-end RPS, latency percentiles, and error rate
         as a client would see it. Generates an HTML report.
```

---

### 5.1 Layer 1 — JMH (Strategy Ceiling)

#### Setup

Add to `pom.xml`:
```xml
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-core</artifactId>
    <version>1.37</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-generator-annprocess</artifactId>
    <version>1.37</version>
    <scope>test</scope>
</dependency>
```

#### Benchmark Class

Create `src/test/java/com/drl/ratelimiter/benchmark/LocalFixedWindowBenchmark.java`:

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)      // 3 s JIT warm-up
@Measurement(iterations = 5, time = 2) // 5 × 2 s measurement rounds
@Fork(1)
public class LocalFixedWindowBenchmark {

    private LocalFixedWindowStrategy strategy;

    @Setup
    public void setup() {
        strategy = new LocalFixedWindowStrategy();
    }

    // All threads compete on the same counter key
    @Benchmark @Threads(1)
    public RateLimitDecision single_thread() {
        return strategy.evaluate("bench:key", 1_000_000, 60_000);
    }

    @Benchmark @Threads(10)
    public RateLimitDecision ten_threads_same_key() {
        return strategy.evaluate("bench:key", 1_000_000, 60_000);
    }

    @Benchmark @Threads(50)
    public RateLimitDecision fifty_threads_same_key() {
        return strategy.evaluate("bench:key", 1_000_000, 60_000);
    }

    // Each thread has its own key — measures pure algorithm cost with no CAS contention
    @State(Scope.Thread)
    public static class ThreadKey {
        String key = "bench:" + Thread.currentThread().getId();
    }

    @Benchmark @Threads(50)
    public RateLimitDecision fifty_threads_independent_keys(ThreadKey tk) {
        return strategy.evaluate(tk.key, 1_000_000, 60_000);
    }
}
```

> **Why limit = 1,000,000?** This prevents rejections, so the benchmark measures
> the full allow-path cost without mixing in the reject branch.

#### Run

```bash
./mvnw test -Dtest=LocalFixedWindowBenchmark
```

#### Expected Output Shape

```
Benchmark                                    Threads   Score (ops/s)
single_thread                                      1     ~4,000,000
ten_threads_same_key                              10     ~8,000,000   ← CAS contention visible
fifty_threads_same_key                            50     ~6,000,000   ← contention increases
fifty_threads_independent_keys                    50    ~20,000,000   ← no contention, true ceiling
```

The gap between `same_key` and `independent_keys` at 50 threads shows the cost
of CAS contention in production (where many clients share the same endpoint key).

---

### 5.2 Layer 2 — JMeter (Full HTTP Throughput)

JMeter is the standard load testing tool for Java applications. It produces an
HTML report with response-time graphs, throughput trends, and latency
percentiles — results that can be embedded directly in project documentation.

#### Setup

```bash
# macOS
brew install jmeter

# Start Redis
docker run -d --name redis-bench -p 6379:6379 redis:7.4-alpine

# Start the application
./mvnw spring-boot:run
```

#### Test Plan Structure

Each test plan uses the following JMeter elements:

```
Test Plan
└── Thread Group          ← concurrency and duration config
    ├── HTTP Request       ← endpoint under test
    ├── HTTP Header Manager← inject X-Forwarded-For for client identity
    ├── Summary Report     ← live RPS + error rate in GUI
    └── Aggregate Report   ← p50 / p90 / p99 latency breakdown
```

#### Scenario 1 — Single Endpoint Baseline (GUI)

Open JMeter GUI and configure:

| Element | Setting |
|---|---|
| Thread Group → Number of Threads | `100` |
| Thread Group → Ramp-Up Period | `5` (seconds) |
| Thread Group → Loop Count | `Forever` |
| Thread Group → Duration | `30` (seconds) |
| HTTP Request → Server | `localhost` |
| HTTP Request → Port | `8080` |
| HTTP Request → Path | `/api/strict` |

Repeat with paths `/api/free`, `/api/token-bucket/primary`,
`/api/sliding-window/primary` to compare all four endpoints.

#### Scenario 2 — Multi-Client Isolation (CSV-driven IPs)

Create `src/test/resources/jmeter/client-ips.csv`:

```
203.0.113.101
203.0.113.102
203.0.113.103
203.0.113.104
203.0.113.105
```

Add to the Thread Group:

| Element | Setting |
|---|---|
| CSV Data Set Config → Filename | `client-ips.csv` |
| CSV Data Set Config → Variable Names | `clientIp` |
| CSV Data Set Config → Sharing Mode | `All threads` |
| HTTP Header Manager → Header Name | `X-Forwarded-For` |
| HTTP Header Manager → Header Value | `${clientIp}` |
| HTTP Request → Path | `/api/token-bucket/primary` |

Each virtual user picks a client IP from the CSV in round-robin order,
simulating 5 independent clients each consuming their own quota.

#### Scenario 3 — Concurrency Sweep (CLI, no GUI)

> **Always run actual benchmarks in non-GUI mode.** JMeter's GUI consumes
> significant memory and CPU, which distorts throughput measurements.

Save the test plan as `src/test/resources/jmeter/rate-limiter-bench.jmx`,
then run a sweep across concurrency levels:

```bash
for threads in 10 20 50 100 150 200 300; do
  jmeter -n \
    -t src/test/resources/jmeter/rate-limiter-bench.jmx \
    -Jthreads=$threads \
    -l results-${threads}.jtl \
    -e -o ./jmeter-report-${threads}
  echo "=== threads=$threads done, report: ./jmeter-report-${threads}/index.html ==="
done
```

> `-n` = non-GUI mode
> `-Jthreads=$threads` = overrides the `${threads}` variable in the test plan
> `-l results.jtl` = raw result log
> `-e -o ./report` = generate HTML report in the given directory

RPS will rise with thread count, peak, then decline. The peak is the
concurrency ceiling. The decline indicates connection pool or Redis saturation.

#### HTML Report Output

After each run, open `./jmeter-report-{threads}/index.html` in a browser.
Key charts to look at:

| Chart | What to look for |
|---|---|
| **Throughput over time** | Is RPS stable, or does it degrade mid-test? |
| **Response Time Percentiles** | p99 rising sharply = tail latency problem |
| **Active Threads over Time** | Confirms the thread count actually ramped |
| **Error rate** | 429s are expected; 5xx or timeouts are not |

Sample summary table JMeter produces:

```
Label               # Samples   Average   p90    p99    Throughput   Error%
/api/strict         300000      2 ms      5 ms   18 ms  9,823/sec    93.3%
/api/sliding-window 180000      8 ms      15 ms  42 ms  5,991/sec    97.0%
/api/free           300000      1 ms      2 ms   6 ms   10,204/sec   0.0%
```

> The high Error% for rate-limited endpoints is expected — those are HTTP 429
> responses. A 429 is a correct rejection, not a system failure.

---

### 5.3 Metrics to Record

| Metric | Tool | What it tells you |
|---|---|---|
| Strategy ops/sec (no contention) | JMH `independent_keys` | True algorithm ceiling, no concurrency cost |
| Strategy ops/sec (single key) | JMH `same_key` | Real-world ceiling for a hot endpoint |
| CAS contention drop | JMH `same_key` vs `independent_keys` | How much concurrent access costs |
| HTTP RPS (peak) | JMeter Throughput chart | End-to-end throughput a client sees |
| HTTP latency p50 / p99 | JMeter Percentiles chart | Typical and tail latency |
| Concurrency ceiling | JMeter sweep | Thread count where adding more hurts RPS |
| Error rate breakdown | JMeter Summary Report | Distinguish 429 (correct) from 5xx (bug) |

---

### 5.4 Updating Section 2 After Benchmarking

Replace the estimated ceilings with measured values:

```
LocalFixedWindow
  Algorithm ceiling (JMH, 50 threads, no contention): XX,XXX,XXX ops/s
  Algorithm ceiling (JMH, 50 threads, same key):       XX,XXX,XXX ops/s
  Full HTTP path (JMeter, 100 threads, 30 s):          XX,XXX RPS  p99=XX ms

RedisTokenBucketStrategy
  Full HTTP path (JMeter, 100 threads, 30 s):          XX,XXX RPS  p99=XX ms

RedisSlidingWindowStrategy
  Full HTTP path (JMeter, 100 threads, 30 s):          XX,XXX RPS  p99=XX ms
```

---

## 6. Comparison with Industry Systems

> For context only — not a target for this project.

| System | Approach | Approximate ceiling |
|---|---|---|
| Nginx `limit_req` | In-process shared memory, C | 500k+ RPS |
| Kong | Local counter + async Redis sync | 50k–100k RPS |
| AWS API Gateway | Managed, per-region token bucket | 10k RPS default |
| Cloudflare | Approximate sliding window, local + async | Millions RPS |
| **Our implementation** | Synchronous Redis per request | ~5k–20k RPS (estimated) |

**Key gaps vs production systems:**
1. Synchronous Redis calls (vs async/reactive)
2. No local caching layer (every request hits Redis)
3. `SlidingWindow` stores one ZSET entry per request (vs approximate 2-counter approach)
4. Single Redis instance (no Cluster or Sentinel)
