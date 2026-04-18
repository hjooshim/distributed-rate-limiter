package com.drl.ratelimiter.benchmark;

import com.drl.ratelimiter.strategy.LocalFixedWindowStrategy;
import com.drl.ratelimiter.strategy.RateLimitDecision;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================
 * JMH BENCHMARK — LocalFixedWindowStrategy Algorithm Ceiling
 * ============================================================
 *
 * WHY THIS DESIGN
 * ───────────────
 * Thread count is derived from Little's Law and USL (Universal Scalability
 * Law), not chosen arbitrarily:
 *
 *   Little's Law:  L = λ × W
 *     L = concurrent threads
 *     λ = target RPS
 *     W = average latency per call
 *
 *   USL sweep: test at 1 → cores/2 → cores → cores×2 → cores×4
 *   and plot throughput vs. thread count. Throughput rises, peaks,
 *   then declines. The peak is the real ceiling; the decline marks
 *   where CAS contention cost exceeds parallelism benefit.
 *
 * TWO SCENARIOS
 * ─────────────
 * same_key          — all threads compete on one AtomicLong counter.
 *                     Represents a hot endpoint where every client
 *                     shares the same rate-limit bucket.
 *
 * independent_keys  — each thread has its own counter (no contention).
 *                     Represents many independent clients, each with
 *                     their own bucket. This is the true algorithm
 *                     ceiling, free of CAS retry overhead.
 *
 * The gap between the two at the same thread count shows the exact
 * cost of CAS contention in a real deployment.
 *
 * limit = Integer.MAX_VALUE keeps every call on the allow-path so the
 * benchmark measures allow-path cost only (no reject-branch mixing).
 *
 * METRICS
 * ───────
 * ops/s (Throughput): how many rate-limit decisions per second.
 *   → use to compare scenarios and find the throughput ceiling.
 *
 * HOW TO RUN
 * ──────────
 * ./mvnw test -Dtest=LocalFixedWindowBenchmark
 *
 * The runner sweeps thread counts automatically based on this machine's
 * logical core count and prints a summary table at the end of each sweep.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class LocalFixedWindowBenchmark {

    private LocalFixedWindowStrategy strategy;

    /**
     * Fresh strategy instance before each benchmark trial.
     * Level.Trial = once per (benchmark method × thread-count run),
     * so every scenario starts with a clean counter map.
     */
    @Setup(Level.Trial)
    public void setup() {
        strategy = new LocalFixedWindowStrategy();
    }

    // ─────────────────────────────────────────────────────────
    // Scenario A — Hot endpoint: all threads share one counter
    //
    // Models a production endpoint where the rate-limit key is
    // scoped to the endpoint only (no per-client isolation).
    // Every thread races to increment the same AtomicLong, causing
    // CAS retry storms at high concurrency.
    // ─────────────────────────────────────────────────────────

    @Benchmark
    public RateLimitDecision same_key() {
        return strategy.evaluate("bench:endpoint", Integer.MAX_VALUE, 60_000);
    }

    // ─────────────────────────────────────────────────────────
    // Scenario B — Isolated clients: each thread owns its counter
    //
    // Models many independent clients each with their own bucket.
    // No two threads share a key so there is zero CAS contention.
    // This measures the pure algorithm cost (ConcurrentHashMap lookup
    // + AtomicLong increment) with no concurrency overhead.
    // ─────────────────────────────────────────────────────────

    /**
     * Thread-scoped state gives each JMH worker thread a unique key,
     * ensuring no two threads ever touch the same AtomicLong counter.
     */
    @State(Scope.Thread)
    public static class ThreadKey {
        String key;

        @Setup(Level.Trial)
        public void setup() {
            key = "bench:client:" + Thread.currentThread().getId();
        }
    }

    @Benchmark
    public RateLimitDecision independent_keys(ThreadKey tk) {
        return strategy.evaluate(tk.key, Integer.MAX_VALUE, 60_000);
    }

    // ─────────────────────────────────────────────────────────
    // JUnit entry point — USL thread sweep
    // ─────────────────────────────────────────────────────────

    /**
     * Runs both scenarios at each thread count in the USL sweep:
     *
     *   1 thread    — single-threaded baseline
     *   cores / 2   — half load
     *   cores       — CPU-bound optimum (Little's Law target for this workload)
     *   cores × 2   — light oversubscription / hyperthreading
     *   cores × 4   — simulates I/O-bound threads (e.g., waiting for Redis)
     *
     * forks(0): runs in-process so Maven Surefire can invoke it directly.
     * Trade-off: JIT state is shared across trials. Numbers are suitable
     * for relative comparison (same_key vs independent_keys, low vs high
     * thread count) but not for absolute production benchmarking.
     * For authoritative numbers remove forks(0) and run the class standalone.
     *
     * Expected shape (USL curve):
     *   threads=1   → highest ops/s per thread, no contention
     *   threads=cores → peak total throughput
     *   threads=cores×4 → throughput plateaus or declines (CAS saturation)
     *
     * same_key will always trail independent_keys; the gap widens as
     * thread count grows because more threads cause more CAS retries.
     */
    @Test
    void runBenchmarks() throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        int[] threadCounts = {
                1,
                Math.max(1, cores / 2),
                cores,
                cores * 2,
                cores * 4
        };

        System.out.printf("%n╔══════════════════════════════════════════════╗%n");
        System.out.printf("║  LocalFixedWindowBenchmark — USL thread sweep ║%n");
        System.out.printf("╠══════════════════════════════════════════════╣%n");
        System.out.printf("║  Machine logical cores : %-4d                 ║%n", cores);
        System.out.printf("║  Thread counts tested  : %-28s ║%n", Arrays.toString(threadCounts));
        System.out.printf("╚══════════════════════════════════════════════╝%n%n");

        for (int threads : threadCounts) {
            System.out.printf("════ threads = %-3d ════════════════════════════%n", threads);
            Options opt = new OptionsBuilder()
                    .include(getClass().getSimpleName())
                    .threads(threads)
                    .forks(0)
                    .build();
            new Runner(opt).run();
        }
    }
}
