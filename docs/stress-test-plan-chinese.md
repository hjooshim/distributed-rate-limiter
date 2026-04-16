# 压力测试计划 — 分布式限流器

## 概述

本文档涵盖两种超出现有功能集成测试范围的测试方式：

- **方案 A — 正确性压力测试**（JUnit）：验证在极高并发（200–500 线程）下，
  限流器仍能精确执行限制。以标准 JUnit 测试编写，通过 Maven 运行。

- **方案 B — 吞吐量基准测试**（JMH + JMeter）：测量实际 RPS 和延迟数据。
  需要外部工具，不属于 Maven 测试套件的一部分。

第 2 节中的性能上限数字是**基于代码结构推算的理论估值，未经实测验证**。
第 5 节说明如何测出真实数据。

---

## 1. 系统架构

```
HTTP 请求
    │
    ▼
RateLimitAspect  (AOP 拦截 — 构建 key = 类名.方法名:clientId)
    │
    ▼
StrategyRegistry.get(algorithm)
    │
    ├── FIXED_WINDOW   → LocalFixedWindowStrategy
    │                    ConcurrentHashMap<windowKey, WindowCounter>
    │                    WindowCounter 包装 AtomicLong（无锁 CAS）
    │
    ├── TOKEN_BUCKET   → RedisTokenBucketStrategy
    │                    Redis Hash（tokens, lastRefillMs）
    │                    Lua 脚本：refill + consume，1 次原子 EVAL
    │
    └── SLIDING_WINDOW → RedisSlidingWindowStrategy
                         Redis Sorted Set（score = 请求时间戳 ms）
                         Lua 脚本：trim + count + ZADD，1 次原子 EVAL
```

每个请求在 strategy 之外的额外开销：AOP 反射（MethodSignature 查找）+
身份解析 + StrategyRegistry 一次 map 查找。所有调用均为**同步阻塞**，
不存在异步或 reactive 路径。

---

## 2. 性能上限分析（理论估值）

> **注意**：以下数字来自代码结构分析和底层数据结构的已知特性，
> **未经基准测试验证**。如需真实数据，参见第 5 节。

### 2.1 LocalFixedWindowStrategy

| 因素 | 说明 |
|---|---|
| 数据结构 | `ConcurrentHashMap<String, WindowCounter>` |
| 热路径 | `computeIfAbsent` → `AtomicLong.incrementAndGet()` |
| 是否无锁 | 是 — `incrementAndGet` 使用硬件 CAS；仅首次创建时有 segment 锁 |
| 清理开销 | 每 100 次请求触发一次 O(N) map 全扫描 |
| 内存增长 | 每个（endpoint × client × windowId）组合一个 `WindowCounter` |

**压力下的瓶颈：**

1. **CAS 重试风暴** — 200+ 线程争同一个 key 时，`AtomicLong` CAS 在每次冲突
   时重试。表现为 CPU 自旋，不是阻塞——吞吐下降但线程不会挂起。

2. **延迟清理争用** — `cleanupOldWindows` 在每 100 次请求时进行全 map 扫描，
   高 RPS 下多个线程可能同时到达这个边界，`ConcurrentHashMap.removeIf`
   获取 segment 写锁时可能短暂暂停。

3. **`computeIfAbsent` 首次创建分配峰值** — 每个新 window slot 的第一个请求
   分配一个 `WindowCounter`。极端并发下多个线程可能竞相创建同一个 key，
   虽然只有一个能成功，但会短暂产生分配压力。

**估算上限（未验证）：**
- 单 key（一个 endpoint、一个 client）：**~50,000–100,000 RPS**（GC 压力是瓶颈）
- 多 key（多个 client）：受活跃 `WindowCounter` 数量的内存限制

---

### 2.2 RedisTokenBucketStrategy

| 因素 | 说明 |
|---|---|
| 数据结构 | Redis Hash — 2 个字段：`tokens`、`lastRefillMs` |
| 每次请求的操作 | 1 次 `EVAL`：`TIME` + 2× `HGET` + `HSET` + `PEXPIRE` |
| 原子性 | Lua 脚本在 Redis 内原子执行 |
| 时间来源 | `Redis TIME` — 不受 JVM 时钟偏移影响 |
| 状态大小 | 每个（endpoint × client × policy）固定 2 字段 — O(1) |

**压力下的瓶颈：**

1. **网络 RTT 是主要瓶颈** — RTT 1ms 时，单线程最多约 1,000 次决策/秒。
   20 个连接的 pool 约 20,000 RPS。是延迟而非 Redis 计算能力限制了上限。

2. **Redis 单线程执行** — 所有 Lua 脚本在 Redis 内串行执行。极高 RPS 时，
   Redis 命令队列积压会变得可测量。

3. **连接池耗尽** — Lettuce 默认 pool 大小固定。100+ 并发线程时，
   如果 pool 小于线程数，线程会排队等待连接。

**估算上限（未验证）：**
- 本机 Redis：**~5,000–20,000 RPS**（取决于连接池配置）
- 远程 Redis（1–5 ms RTT）：每个 app 节点 **~1,000–5,000 RPS**

---

### 2.3 RedisSlidingWindowStrategy

| 因素 | 说明 |
|---|---|
| 数据结构 | Redis Sorted Set — 每个窗口内的请求一个成员 |
| 每次请求的操作 | 1 次 `EVAL`：`TIME` + `ZREMRANGEBYSCORE` + `ZCARD` + `ZADD` + `PEXPIRE` |
| 原子性 | Lua 脚本原子执行 |
| 状态大小 | 每个被放行的请求写一条 ZSET 记录 — O(limit) |

**压力下的瓶颈：**

1. **`ZREMRANGEBYSCORE` 复杂度为 O(log N + M)** — M 是每次调用需要清理的
   过期条目数。持续高 RPS 下 M 会增大，清理开销明显高于 Token Bucket 的
   O(1) hash 操作。

2. **ZSET 成员增长** — `limit=1000`、`windowMs=60s` 的窗口每个 key 最多保留
   1,000 条 ZSET 成员。内存和清理开销随配置的 limit 线性增长。

3. **时间戳唯一性** — 成员使用 Redis TIME 的 `"nowMs:microseconds"` 格式
   保证唯一性。微秒级碰撞会导致 ZADD 覆盖已有条目，窗口计数偏低。
   实际中 Redis TIME 微秒推进速度足以避免此问题。

**估算上限（未验证）：**
- 本机 Redis：**~3,000–10,000 RPS** — 因 ZSET 操作开销，明显慢于 Token Bucket
- 远程 Redis：每个 app 节点 **~500–3,000 RPS**

---

### 2.4 已知正确性风险

| 风险 | 策略 | 说明 |
|---|---|---|
| 边界突发 | FIXED_WINDOW | 客户端可在两个 window 边界各发半份请求，实际获得 2× limit。已知设计限制。 |
| 清理竞争 | FIXED_WINDOW | `cleanupOldWindows` 可能在请求进行时提前删除 counter。概率低，但高并发下可能发生。 |
| 连接池耗尽 | TOKEN_BUCKET / SLIDING_WINDOW | 100+ 线程时 Lettuce pool 可能排队，超时行为未测试。 |
| Redis 故障切换时钟跳变 | 所有 Redis 策略 | Redis 发生故障切换时 `Redis TIME` 可能跳变，无对应保护。 |
| ZADD 成员碰撞 | SLIDING_WINDOW | 同一 Redis 微秒内两个请求碰撞；一个条目被覆盖，窗口计数偏低。 |

---

## 3. 方案 A — 正确性压力测试（JUnit）

**目标**：验证在高并发下限制仍被精确执行。
使用 JUnit 5 + MockMvc / TestRestTemplate，通过 Maven 运行。
**不测量吞吐量**。

与现有集成测试的区别：
- 线程数高 5–66 倍（30 → 200+）
- 多轮持续负载
- 并发的跨 endpoint 和跨 client 场景

---

### 3.1 本地 Fixed-Window 压力测试 — `StressTest.java`

无外部依赖，使用 MockMvc + 内存策略。

#### ST-1 — 200 并发线程冲击单一 Endpoint

| | |
|---|---|
| Endpoint | `GET /api/strict`（`@RateLimit(limit=3, windowMs=10_000)`） |
| 线程数 | 200，通过 `CountDownLatch` 同时释放 |
| 通过标准 | allowed = **3**，rejected = **197**，无非预期状态码 |
| 验证内容 | `AtomicLong` CAS 在现有测试并发度 66 倍下的正确性。任何非原子的自增都会导致 allowed > 3。 |

#### ST-2 — 两个 Endpoint 同时承受负载

| | |
|---|---|
| Endpoints | `/api/strict`（limit=3）和 `/api/normal`（limit=10） |
| 线程数 | 共 200 — 每个 endpoint 100 个，交错提交到同一个 executor |
| 通过标准 | strict: allowed=**3**；normal: allowed=**10** |
| 验证内容 | 并发跨 endpoint 负载下的 key 隔离。`ConcurrentHashMap` 中的 key 碰撞会导致某个 endpoint 超过其限制。 |

#### ST-3 — 500 线程冲击无限速 Endpoint

| | |
|---|---|
| Endpoint | `GET /api/free`（无 `@RateLimit`） |
| 线程数 | 500 |
| 通过标准 | 全部 500 个响应 = **HTTP 200** |
| 验证内容 | AOP 注解跳过路径的线程安全性。注解查找中的 TOCTOU 竞争会产生意外的 429。 |

#### ST-4 — 5 轮 × 50 线程突发

| | |
|---|---|
| Endpoint | `GET /api/strict`（limit=3） |
| 准备 | 每轮：`strategy.reset()`，然后释放 50 个线程 |
| 通过标准 | 每轮：allowed = **3**，rejected = **47** |
| 验证内容 | `reset()` 不留残留状态。模拟 `cleanupOldWindows` 在 window 滚动时的行为。 |

**运行：**
```bash
./mvnw test -Dtest=StressTest
```

---

### 3.2 分布式策略压力测试 — `DistributedStressTest.java`

使用 TestContainers Redis，在 `@AfterEach` 中清空 Redis。

#### DST-1 — Token Bucket：100 并发线程

| | |
|---|---|
| Endpoint | `GET /api/token-bucket/primary`（limit=1） |
| 线程数 | 100，相同 client IP（`X-Forwarded-For: 203.0.113.10`） |
| 通过标准 | allowed = **1**，rejected = **99** |
| 验证内容 | Lua 原子性。若 `EVAL` 非原子，两个同时读到 `tokens=1` 的线程会都放行自己。 |

#### DST-2 — Sliding Window：100 并发线程

| | |
|---|---|
| Endpoint | `GET /api/sliding-window/primary`（limit=3） |
| 线程数 | 100，相同 client IP |
| 通过标准 | allowed = **3**，rejected = **97** |
| 验证内容 | `ZREMRANGEBYSCORE + ZCARD + ZADD` 原子性。非原子执行会导致 allowed > 3。 |

#### DST-3 — 多节点：2 节点 × 50 线程，共享配额

| | |
|---|---|
| Endpoint | `GET /api/sliding-window/primary`（limit=3） |
| 准备 | 2 个独立 Spring 上下文（node-A、node-B）指向同一 Redis；每节点 50 线程，相同 client IP |
| 通过标准 | 两节点合计 allowed = **3**，rejected = **97** |
| 验证内容 | 分布式配额共享。若每个节点用本地计数器，合计 allowed 可能高达 6。 |

#### DST-4 — 多 Client 隔离：5 个 Client × 10 线程

| | |
|---|---|
| Endpoint | `GET /api/token-bucket/primary`（每个 client limit=1） |
| 准备 | 5 个 client IP（`203.0.113.101`–`.105`），每个 10 线程，共 50 线程同时释放 |
| 通过标准 | 合计 allowed = **5**（每个 client 1 个），rejected = **45** |
| 验证内容 | per-client key 隔离。验证 `policyScopedKey = key + ":" + limit + ":" + windowMs` 确保各 client 的 bucket 独立。 |

**运行：**
```bash
./mvnw test -Dtest=DistributedStressTest
```

**所有测试打印一行摘要，例如：**
```
[ST-1]  allowed=3,  rejected=197  (limit=3, threads=200)
[DST-3] allowed=3,  rejected=97   across 2 nodes (limit=3, total=100)
```

---

## 4. 方案 A 的覆盖盲区

| 盲区 | 说明 |
|---|---|
| 实际吞吐量（RPS） | MockMvc 有调度开销，不适合吞吐量测量 |
| Redis 连接池耗尽 | 需要调整 Lettuce pool 大小并观察超时行为 |
| 延迟分布（p99、p999） | 需要负载工具，JUnit 无法测量 |
| 持续数分钟的负载 | JUnit 超时机制使此类测试不实际 |
| GC 暂停对窗口精度的影响 | 需要长时间负载 + GC 日志，超出测试范围 |

---

## 5. 方案 B — 吞吐量基准测试（JMH + JMeter）

**目标**：产出真实的 RPS 和延迟数字，替换第 2 节中的理论估值。
分两层测量，分别定位瓶颈所在。

```
第一层：Strategy 层（无 HTTP、无 Spring）  → JMH
        测量算法上限，排除 HTTP 开销干扰。

第二层：完整 HTTP 路径                      → JMeter
        测量客户端视角下的端到端 RPS、延迟百分位数和错误率，
        并自动生成 HTML 报告。
```

---

### 5.1 第一层 — JMH（Strategy 算法上限）

#### 准备

在 `pom.xml` 中添加：
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

#### Benchmark 类

创建 `src/test/java/com/drl/ratelimiter/benchmark/LocalFixedWindowBenchmark.java`：

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)      // 3 秒 JIT 热身
@Measurement(iterations = 5, time = 2) // 5 轮 × 2 秒正式测量
@Fork(1)
public class LocalFixedWindowBenchmark {

    private LocalFixedWindowStrategy strategy;

    @Setup
    public void setup() {
        strategy = new LocalFixedWindowStrategy();
    }

    // 所有线程争同一个 counter key
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

    // 每个线程有独立 key — 测量无竞争时的纯算法开销
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

> **为什么 limit = 1,000,000？** 防止产生 reject，只测放行路径的开销，
> 不混入拒绝逻辑。

#### 运行

```bash
./mvnw test -Dtest=LocalFixedWindowBenchmark
```

#### 预期输出形态

```
Benchmark                                    Threads   Score (ops/s)
single_thread                                      1     ~4,000,000
ten_threads_same_key                              10     ~8,000,000   ← CAS 竞争开始显现
fifty_threads_same_key                            50     ~6,000,000   ← 竞争加剧，吞吐下降
fifty_threads_independent_keys                    50    ~20,000,000   ← 无竞争，真实算法上限
```

`same_key` 和 `independent_keys` 在 50 线程时的差距，就是生产环境中多个 client
共享同一 endpoint key 时的 CAS 竞争代价。

---

### 5.2 第二层 — JMeter（完整 HTTP 吞吐量）

JMeter 是 Java 生态的标准压测工具，能自动生成包含响应时间曲线、吞吐趋势
和延迟百分位数的 HTML 报告，测试结果可以直接放进项目文档。

#### 准备

```bash
# macOS 安装
brew install jmeter

# 启动 Redis
docker run -d --name redis-bench -p 6379:6379 redis:7.4-alpine

# 启动 Spring Boot 应用
./mvnw spring-boot:run
```

#### 测试计划结构

每个测试计划包含以下 JMeter 元素：

```
Test Plan（测试计划）
└── Thread Group（线程组）         ← 并发数和持续时间配置
    ├── HTTP Request（HTTP 请求）   ← 被测 endpoint
    ├── HTTP Header Manager        ← 注入 X-Forwarded-For（模拟 client IP）
    ├── Summary Report             ← GUI 模式下实时查看 RPS 和错误率
    └── Aggregate Report           ← p50 / p90 / p99 延迟分布
```

#### 场景一 — 单 Endpoint 基线测试（GUI 配置）

打开 JMeter GUI，按如下配置：

| 元素 | 配置值 |
|---|---|
| Thread Group → Number of Threads | `100` |
| Thread Group → Ramp-Up Period | `5`（秒，逐步加压） |
| Thread Group → Loop Count | `Forever` |
| Thread Group → Duration | `30`（秒） |
| HTTP Request → Server Name | `localhost` |
| HTTP Request → Port | `8080` |
| HTTP Request → Path | `/api/strict` |

将 Path 依次换成 `/api/free`、`/api/token-bucket/primary`、
`/api/sliding-window/primary`，对比四个 endpoint 的表现。

#### 场景二 — 多 Client 隔离测试（CSV 驱动）

创建 `src/test/resources/jmeter/client-ips.csv`：

```
203.0.113.101
203.0.113.102
203.0.113.103
203.0.113.104
203.0.113.105
```

在线程组中添加：

| 元素 | 配置值 |
|---|---|
| CSV Data Set Config → Filename | `client-ips.csv` |
| CSV Data Set Config → Variable Names | `clientIp` |
| CSV Data Set Config → Sharing Mode | `All threads` |
| HTTP Header Manager → Header Name | `X-Forwarded-For` |
| HTTP Header Manager → Header Value | `${clientIp}` |
| HTTP Request → Path | `/api/token-bucket/primary` |

每个虚拟用户按轮询顺序从 CSV 中取一个 IP，模拟 5 个独立 client 各自消耗
自己的配额。对应 JUnit 测试中的 DST-4 场景。

#### 场景三 — 并发度扫描（CLI，非 GUI 模式）

> **正式压测必须使用非 GUI 模式。** JMeter GUI 本身会占用大量 CPU 和内存，
> 会严重干扰吞吐量测量结果。

将测试计划保存为 `src/test/resources/jmeter/rate-limiter-bench.jmx`，
然后通过 CLI 扫描不同并发度：

```bash
for threads in 10 20 50 100 150 200 300; do
  jmeter -n \
    -t src/test/resources/jmeter/rate-limiter-bench.jmx \
    -Jthreads=$threads \
    -l results-${threads}.jtl \
    -e -o ./jmeter-report-${threads}
  echo "=== threads=$threads 完成，报告：./jmeter-report-${threads}/index.html ==="
done
```

> `-n` = 非 GUI 模式
> `-Jthreads=$threads` = 覆盖测试计划中的 `${threads}` 变量
> `-l results.jtl` = 原始结果日志
> `-e -o ./report` = 在指定目录生成 HTML 报告

RPS 随并发度增加先上升，到达峰值后下降。峰值点就是该 endpoint 的并发上限；
下降说明连接池或 Redis pipeline 已饱和。

#### HTML 报告内容

每次运行后用浏览器打开 `./jmeter-report-{threads}/index.html`，
重点关注以下图表：

| 图表 | 关注点 |
|---|---|
| **Throughput over Time** | RPS 是否稳定，还是测试过程中持续下降 |
| **Response Time Percentiles** | p99 急剧上升 = 尾延迟问题 |
| **Active Threads over Time** | 确认线程数确实按配置加压 |
| **Error rate** | 429 是正常拒绝；5xx 或超时才是系统问题 |

JMeter 生成的汇总表示例：

```
Label               # Samples   Average   p90    p99    Throughput   Error%
/api/strict         300000      2 ms      5 ms   18 ms  9,823/sec    93.3%
/api/sliding-window 180000      8 ms      15 ms  42 ms  5,991/sec    97.0%
/api/free           300000      1 ms      2 ms   6 ms   10,204/sec   0.0%
```

> Error% 对限速 endpoint 很高是正常的——那些都是 HTTP 429 正确拒绝，
> 不是系统故障。

---

### 5.3 需要记录的关键指标

| 指标 | 工具 | 说明 |
|---|---|---|
| Strategy ops/sec（无竞争） | JMH `independent_keys` | 算法真实上限，排除并发争用 |
| Strategy ops/sec（单 key） | JMH `same_key` | 热点 endpoint 的实际上限 |
| CAS 竞争下降幅度 | JMH 对比 `same_key` vs `independent_keys` | 并发访问的代价 |
| HTTP 峰值 RPS | JMeter Throughput 图 | 客户端视角的端到端吞吐 |
| HTTP 延迟 p50 / p99 | JMeter Percentiles 图 | 典型延迟和尾延迟 |
| 并发度上限 | JMeter 扫描 | 增加线程开始拖慢 RPS 的点 |
| 错误率分布 | JMeter Summary Report | 区分 429（正常拒绝）和 5xx（程序错误） |

---

### 5.4 基准测试完成后更新第 2 节

将估算值替换为实测值：

```
LocalFixedWindowStrategy
  算法层上限（JMH，50 线程，无竞争）:        XX,XXX,XXX ops/s
  算法层上限（JMH，50 线程，同一 key）:      XX,XXX,XXX ops/s
  完整 HTTP 路径（JMeter，100 线程，30 秒）: XX,XXX RPS  p99=XX ms

RedisTokenBucketStrategy
  完整 HTTP 路径（JMeter，100 线程，30 秒）: XX,XXX RPS  p99=XX ms

RedisSlidingWindowStrategy
  完整 HTTP 路径（JMeter，100 线程，30 秒）: XX,XXX RPS  p99=XX ms
```

---

## 6. 与工业界的对比

> 仅作参考，不是本项目的目标。

| 系统 | 方案 | 大致上限 |
|---|---|---|
| Nginx `limit_req` | 进程内共享内存，C 语言 | 500k+ RPS |
| Kong | 本地 counter + 异步 Redis 同步 | 50k–100k RPS |
| AWS API Gateway | 托管，per-Region token bucket | 默认 10k RPS |
| Cloudflare | 近似滑动窗口，本地 + 异步汇聚 | 数百万 RPS |
| **我们的实现** | 每次请求同步访问 Redis | ~5k–20k RPS（估算） |

**与生产系统的关键差距：**
1. 同步 Redis 调用（工业界用异步/reactive）
2. 没有本地缓存层（每个请求都打 Redis）
3. Sliding Window 每个请求写一条 ZSET 记录（工业界用 2 个 counter 近似）
4. 单 Redis 实例（没有 Cluster 或 Sentinel）
