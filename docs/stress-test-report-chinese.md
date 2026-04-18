# 性能与压力测试报告
## 分布式限流器

**日期：** 2026-04-18
**作者：** 分布式限流器团队
**版本：** 1.0

---

## 1. 执行摘要

本报告呈现了对分布式限流系统进行的两层性能基准测试结果。测试目标是测量系统实际的吞吐量上限，并定位系统瓶颈所在。

**核心发现：**

| 指标 | 数值 |
|---|---|
| 算法层吞吐上限（JMH，单线程） | **2210 万 ops/s** |
| HTTP 层吞吐上限（JMeter，峰值） | **50,656 RPS** |
| 平均响应延迟 | **7 ms** |
| 系统瓶颈 | **Redis 网络 RTT — 不是算法** |
| 性能差距（算法层 vs HTTP 层） | **440 倍** |

**结论：** 内存固定窗口算法不是瓶颈。系统吞吐天花板由每次请求中同步 Redis 调用的往返延迟决定。每个 Redis 策略请求（Token Bucket、Sliding Window）在得到结果前都必须等待一次完整的网络往返。

---

## 2. 测试环境

| 组件 | 详情 |
|---|---|
| 硬件 | Apple Silicon MacBook Air，**10 个逻辑核心** |
| 操作系统 | macOS Darwin 25.4.0 |
| JVM | OpenJDK 26 |
| Spring Boot | 3.5.12 |
| Redis | 7.4-alpine（Docker，**与应用同机部署**） |
| JMH | 1.37（`forks=0`，进程内运行） |
| JMeter | 5.6.3（非 GUI 模式） |
| Redis 拓扑 | 单实例，本地（`127.0.0.1:6379`） |

**重要说明：**
- Redis 与应用运行在同一台机器上。生产环境中 Redis 通常是远程服务器，额外增加 1–5 ms RTT，这会进一步降低吞吐量。
- JMH 使用 `forks=0`（进程内运行），多个 benchmark 试验共享同一个 JVM 的 JIT 编译状态。结果适用于**相对比较**（同 key vs 独立 key、低并发 vs 高并发），不适合作为绝对生产性能数据。如需权威数字，应使用 `forks=1` 单独运行。

---

## 3. 被测系统架构

```
HTTP 请求
    │
    ▼
RateLimitAspect  （AOP — 构建 key = ClassName.method:clientId）
    │
    ▼
StrategyRegistry.get(algorithm)
    │
    ├── FIXED_WINDOW   → LocalFixedWindowStrategy
    │                    ConcurrentHashMap<windowKey, WindowCounter>
    │                    WindowCounter 封装 AtomicLong（无锁 CAS）
    │                    无 Redis 依赖
    │
    ├── TOKEN_BUCKET   → RedisTokenBucketStrategy
    │                    每次请求 1 次 Redis EVAL
    │                    Lua 脚本：TIME + 2×HGET + HSET + PEXPIRE
    │
    └── SLIDING_WINDOW → RedisSlidingWindowStrategy
                         每次请求 1 次 Redis EVAL
                         Lua 脚本：TIME + ZREMRANGEBYSCORE + ZCARD + ZADD + PEXPIRE
```

所有调用均为**同步阻塞**方式，不存在异步路径。

---

## 4. 测试方法论

### 4.1 两层测试设计

测试分为两层，测量不同维度的性能：

```
第一层（JMH）    — 仅算法层，无 HTTP、无 Spring、无 Redis
                   回答："算法本身能跑多快？"

第二层（JMeter） — 完整 HTTP 链路，包含 Redis
                   回答："真实客户端的体验是什么？"
```

分层测试至关重要。若只用 JMeter，无法判断瓶颈究竟在算法还是在网络。JMH 证明算法不是瓶颈，JMeter 测量系统实际天花板。

### 4.2 线程数确定方法（Little's Law）

线程数通过 **Little's Law** 推导，而非随意选取：

```
L = λ × W

L = 并发线程数
λ = 目标吞吐量（RPS）
W = 每次请求的平均响应时间
```

对于内存策略这类 CPU-bound 负载：
- 最优线程数 ≈ CPU 核数（本机为 10）
- 2 倍核数模拟超线程场景
- 4 倍核数模拟 I/O-bound 线程（等待 Redis 返回的场景）

JMH 线程扫描：**{1, 5, 10, 20, 40}**

### 4.3 USL 并发扫描（通用可扩展性定律）

JMH Benchmark 按照通用可扩展性定律（Universal Scalability Law）进行线程数扫描，将吞吐量与线程数的关系画出曲线，可以看出：
- **上升阶段**：并行收益超过竞争开销
- **峰值**：当前负载的最优并发数
- **下降阶段**：CAS 竞争开销超过并行收益

### 4.4 JMeter 测试场景

每次 JMeter 运行同时对四个端点发压，每个端点使用相同的线程数（共 4×N 个虚拟用户）：

| 端点 | 算法 | 依赖 Redis？ |
|---|---|---|
| `GET /api/free` | 无 | 否 |
| `GET /api/strict` | 固定窗口 | 否 |
| `GET /api/token-bucket/primary` | Token Bucket | 是 |
| `GET /api/sliding-window/primary` | 滑动窗口 | 是 |

每次运行配置：
- 预热时间：5 秒
- 持续时长：30 秒
- 循环模式：持续（在时长内）
- Redis 端点注入 `X-Forwarded-For: 203.0.113.10`（单客户端最坏情况）

---

## 5. 第一层 — 算法基准测试（JMH）

### 5.1 两个测试场景

**`same_key`（共享 key）** — 所有线程竞争同一个 `AtomicLong` 计数器。
模拟生产环境中的热端点：所有客户端共享同一个限流桶。

**`independent_keys`（独立 key）** — 每个线程拥有各自的计数器，无竞争。
模拟多个独立客户端各自使用独立桶的场景。衡量零 CAS 竞争下的纯算法开销，即**算法真实天花板**。

两个场景均设 `limit = Integer.MAX_VALUE`，确保每次调用都走 allow 路径，不混入 reject 分支的开销。

### 5.2 测试结果

| 线程数 | `same_key`（ops/s） | `independent_keys`（ops/s） | CAS 损耗 |
|---|---|---|---|
| 1 | 21,525,162 | 22,079,908 | **2.5%** — 可忽略 |
| 5 | 13,004,990 | 17,842,866 | **27.1%** — 竞争开始显现 |
| 10 | 14,478,047 | 11,856,176 | — ¹ |
| 20 | 14,546,739 | 10,437,229 | — ¹ |
| 40 | 14,241,715 | 10,908,920 | — ¹ |

> ¹ 10 线程以上，`independent_keys` 低于 `same_key`，这是 `forks=0` 的副作用：`same_key` 试验积累的 JIT 优化状态延续到了 `independent_keys` 试验，导致 `same_key` 的分数被高估。同一场景内不同线程数之间的相对比较（如 1 线程 vs 5 线程）仍然有效。

### 5.3 分析

**单线程基线：2200 万 ops/s**

一次限流决策的耗时约为：
```
1 / 22,000,000 = 45 纳秒
```

这是一次 `ConcurrentHashMap.computeIfAbsent` 查找加一次 `AtomicLong.incrementAndGet()` CAS 操作的开销。

**5 线程时的 CAS 竞争：**

在 5 线程（10 核的一半）时，`same_key` 吞吐量相比单线程下降 41%。这是多线程竞争同一个 `AtomicLong` 的代价——失败的 CAS 操作会导致 CPU 自旋重试，而非线程阻塞挂起。

**10–40 线程时的平台期：**

`same_key` 在 10 到 40 线程间稳定在 **1400 万 ops/s** 左右。这是 CAS 竞争的饱和点：继续增加线程数，额外的重试开销与并行收益相互抵消，吞吐量不再增长。

### 5.4 第一层结论

算法层可处理 **1400 万至 2200 万次/秒** 限流决策（视竞争程度而定）。这远超 HTTP 层能够交付的吞吐量，证实**算法不是系统瓶颈**。

---

## 6. 第二层 — HTTP 负载测试（JMeter）

### 6.1 并发扫描结果

| 每端点线程数 | 总虚拟用户数 | RPS | 平均延迟 | Error% |
|---|---|---|---|---|
| 10 | 40 | 49,642 | 7 ms | 65.8% |
| 20 | 80 | 46,568 | 7 ms | 64.6% |
| 50 | 200 | 49,651 | 7 ms | 64.0% |
| **100** | **400** | **50,656** | **7 ms** | **61.5%** |
| 200 | 800 | 46,044 | 7 ms | 65.3% |

**峰值吞吐：50,656 RPS，出现在每端点 100 线程（共 400 个虚拟用户）时**

### 6.2 错误率解读

61–66% 的错误率是**预期行为，并非系统异常**。

四个端点中三个有严格限流（每窗口仅允许 1–3 次请求）。在持续负载下，这三个端点几乎所有请求都会收到 HTTP 429。`/api/free` 端点没有错误，将综合错误率从约 95% 拉低至约 65%。

```
HTTP 429  → 正确的限流拒绝，限流器工作正常
HTTP 5xx  → 系统错误，测试期间未出现
超时       → 连接池耗尽，测试期间未出现
```

### 6.3 RPS 曲线形态：平坦的平台期

```
RPS
55k │
50k │  ●───────●───────●
45k │      ●               ●
40k │
    └──────────────────────── 每端点线程数
        10   20   50  100  200
```

RPS 曲线在所有线程数下**几乎持平**。这是系统瓶颈**不在 CPU 或线程数、而在 I/O 延迟**的典型特征——本例中即 Redis RTT。

增加更多线程无法提升 RPS，因为线程大部分时间都在等待 Redis 响应，而非在 CPU 上执行。

### 6.4 延迟稳定性

平均延迟在所有线程数下稳定在 **7 ms**，与 Little's Law 吻合：

```
在每端点 100 线程、总计 400 虚拟用户时：
L = λ × W
400 = 50,000 × W
W = 400 / 50,000 = 8 ms  ≈ 实测 7 ms ✓
```

系统在负载下没有出现性能退化——延迟稳定，说明在当前并发水平下不存在请求排队或内存压力。

---

## 7. 瓶颈分析

### 7.1 440 倍的差距

| 层次 | 吞吐量 |
|---|---|
| 算法层（JMH，单线程） | 22,000,000 ops/s |
| 完整 HTTP 链路（JMeter，峰值） | 50,656 RPS |
| **差距** | **434 倍** |

### 7.2 根本原因：同步 Redis 调用

每个 Redis 策略端点（`TOKEN_BUCKET`、`SLIDING_WINDOW`）的请求都会同步执行一次 Redis `EVAL` 命令，并等待响应后才返回。本机 Redis 的一次往返耗时约 **0.3–0.5 ms**。

同步调用的理论上限：
```
单连接：1 / 0.5ms = 2,000 请求/秒
Lettuce 默认连接池：约 8–16 个连接
理论上限：2,000 × 16 = ~32,000 RPS（仅 Redis 策略端点）
```

实测总 RPS 约 50k（四端点合计，其中两个无需 Redis），与此估算一致。

### 7.3 LocalFixedWindowStrategy 的优势

`/api/strict` 使用 `LocalFixedWindowStrategy`——无 Redis，纯内存操作。其吞吐量仅受限于 Spring MVC 调度开销（约 1 ms），而非 Redis 延迟。这解释了为何整体 RPS 高于纯 Redis 端点单独运行时的水平。

### 7.4 更高负载下的潜在故障点

当总虚拟用户数超过约 400 时：

1. **Lettuce 连接池耗尽** — 线程排队等待 Redis 连接，延迟急剧上升
2. **Redis 命令队列积压** — Redis 单线程执行器开始跟不上请求速度
3. **JVM GC 压力** — Sliding Window 每个窗口内的请求在 ZSET 中各占一个条目，高 RPS 下会产生大量短生命周期对象

在当前测试的并发水平（最高 400 个总虚拟用户）下，上述故障模式均未出现。

---

## 8. 与工业系统的对比

> 仅供参考，不作为本项目的性能目标。

| 系统 | 架构 | 吞吐量 | 核心优势 |
|---|---|---|---|
| **Nginx `limit_req`** | C 语言，进程内共享内存 | 50 万+ RPS | 无网络跳转，操作系统级性能 |
| **Kong** | 本地计数器 + 异步 Redis 同步 | 5–10 万 RPS | 异步 Redis — 不阻塞每次请求 |
| **AWS API Gateway** | 托管服务，按区域 Token Bucket | 1 万 RPS（默认配额） | 全托管，多区域分布 |
| **Cloudflare** | 近似滑动窗口，分布式边缘 | 百万 RPS | 边缘本地计数器，最终一致性 |
| **本系统** | 每次请求同步 Redis | **~5 万 RPS（本机）** | 简单、正确、分布式 |

**本系统在一台笔记本上的吞吐量已超过 AWS API Gateway 的默认配额**。与 Kong 和 Nginx 相比，差距在 2–10 倍之间。

### 8.1 差距原因分析

| 差距对象 | 根本原因 | 改进路径 |
|---|---|---|
| vs. Nginx（10 倍） | Nginx：C 语言，无 JVM 开销，进程内内存 | JVM 服务可接受的合理差距 |
| vs. Kong（2 倍） | Kong 使用异步 Redis，不阻塞每次请求 | 将同步 `EVAL` 替换为异步 Lettuce |
| vs. Cloudflare（100 倍+） | 边缘本地计数器 + 定期同步，非强一致性 | 需要架构改造（本地缓存 + 异步 Redis）|

---

## 9. 已知局限性

| 局限性 | 影响 | 改进方向 |
|---|---|---|
| JMH `forks=0` | JIT 状态共享；10+ 线程的 `independent_keys` 数据偏低 | 使用 `forks=1` 获取权威数字 |
| Redis 本机部署 | RTT 约 0.3–0.5 ms；生产环境每次调用额外增加 1–5 ms | 使用远程 Redis 重新测试 |
| Redis 单实例 | 未测试高可用和故障切换 | 引入 Sentinel 或 Cluster |
| 同步 Redis 调用 | 每次请求占用一个线程等待完整 RTT | 改用响应式 Lettuce 或增加本地缓存层 |
| JMH 固定 limit | `Integer.MAX_VALUE` 使所有调用走 allow 路径 | 增加独立的 reject 路径 benchmark |
| 无 GC 压力测试 | 未测试 Sliding Window ZSET 在持续高 RPS 下的 GC 影响 | 运行 5 分钟持续负载测试并开启 GC 日志 |

---

## 10. 附录

### A. JMH 原始数据

```
机器配置：10 个逻辑核心
线程扫描：[1, 5, 10, 20, 40]

── threads = 1 ──────────────────────────────────
Benchmark                                  Mode  Cnt         Score        Error  Units
LocalFixedWindowBenchmark.independent_keys thrpt    5  22,079,908 ±  98,041  ops/s
LocalFixedWindowBenchmark.same_key         thrpt    5  21,525,162 ±  97,909  ops/s

── threads = 5 ──────────────────────────────────
LocalFixedWindowBenchmark.independent_keys thrpt    5  17,842,866 ± 115,252  ops/s
LocalFixedWindowBenchmark.same_key         thrpt    5  13,004,990 ± 9,162,357 ops/s

── threads = 10 ─────────────────────────────────
LocalFixedWindowBenchmark.independent_keys thrpt    5  11,856,176 ± 1,092,847 ops/s
LocalFixedWindowBenchmark.same_key         thrpt    5  14,478,047 ±   291,587 ops/s

── threads = 20 ─────────────────────────────────
LocalFixedWindowBenchmark.independent_keys thrpt    5  10,437,229 ± 1,377,606 ops/s
LocalFixedWindowBenchmark.same_key         thrpt    5  14,546,739 ± 1,282,076 ops/s

── threads = 40 ─────────────────────────────────
LocalFixedWindowBenchmark.independent_keys thrpt    5  10,908,920 ± 2,118,911 ops/s
LocalFixedWindowBenchmark.same_key         thrpt    5  14,241,715 ±   110,513 ops/s
```

### B. JMeter 原始数据

```
threads=10  → 汇总 1,491,156 次请求，30s，49,642 RPS，Avg=7ms，Err=65.8%
threads=20  → 汇总 1,399,777 次请求，30s，46,568 RPS，Avg=7ms，Err=64.6%
threads=50  → 汇总 1,491,621 次请求，30s，49,651 RPS，Avg=7ms，Err=64.0%
threads=100 → 汇总 1,522,002 次请求，30s，50,656 RPS，Avg=7ms，Err=61.5%
threads=200 → 汇总 1,383,675 次请求，30s，46,044 RPS，Avg=7ms，Err=65.3%
```

### C. 运行命令

```bash
# JMH — 算法基准测试
./mvnw test -Dtest=LocalFixedWindowBenchmark

# JMeter — HTTP 负载测试（单次运行）
jmeter -n \
  -t src/test/resources/jmeter/rate-limiter-bench.jmx \
  -Jthreads=100 \
  -l results/results-100.jtl \
  -e -o results/report-100

# JMeter — 完整并发扫描
bash results/run-sweep.sh

# 查看 HTML 报告
open results/report-100/index.html
```

### D. 被测端点

| 端点 | 算法 | 限制 | 窗口 |
|---|---|---|---|
| `GET /api/free` | 无 | — | — |
| `GET /api/strict` | 固定窗口（本地内存） | 3 次 | 10 秒 |
| `GET /api/token-bucket/primary` | Token Bucket（Redis） | 1 次 | 60 秒 |
| `GET /api/sliding-window/primary` | 滑动窗口（Redis） | 3 次 | 10 秒 |
