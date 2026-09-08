> English | [中文](../benchmark-wsl.md)

# Spring WebPerf Performance Benchmark Report (WSL2 External)

> **Purpose:** This document showcases Spring WebPerf's performance advantage under high concurrency, based on a full benchmark run with JDK 17 in WSL2 external mode.
> All 5 containers are compared in the **same environment, with the same client and the same load**. `perf` is this framework's Benchmark Profile (native Netty, no Servlet bridge), and `perf-support` adds a Servlet bridge layer on top.

---

## Core Advantages

| Metric | perf | vs Spring MVC (Tomcat) | vs WebFlux |
|--------|------|------------------------|------------|
| High-concurrency throughput (json, 48 threads) | **43,896 ops/s** | **2.96x** (14,806) | **2.31x** (18,981) |
| Concurrency scaling (json, 16→48 threads) | **+52%** | +17% | +11% |
| SSE long-connection throughput (48 threads) | **5,715 ops/s** | **2.63x** (2,170) | **2.08x** (2,743) |
| Byte-echo throughput (bytes, 48 threads) | **45,152 ops/s** | **2.12x** (21,281) | **1.65x** (27,363) |
| Async throughput (async, 48 threads) | **44,104 ops/s** | **3.06x** (14,407) | **1.84x** (23,996) |
| High-concurrency latency (json, 48 threads, p50) | **0.92 ms** | 3.16 ms (**29%**) | 1.95 ms (**47%**) |

**perf ranks first in every comparison across all 7 APIs × 3 concurrency levels (16/32/48 threads) — no exceptions.**

The most striking finding: **perf's advantage keeps growing as concurrency increases.** WebFlux peaks and falls back at 32 threads, Spring MVC barely scales, while perf is still growing at 48 threads — the higher the concurrency, the greater the framework advantage.

---

## Test Environment

| Item | Configuration |
|------|---------------|
| CPU | AMD Ryzen 7 4800U (8 physical / 16 logical cores) |
| Memory | 16 GB (WSL2 limited to 2 GB) |
| Topology | WSL2 (4 vCPUs) runs the servers + Windows host runs the OkHttp JMH client (external mode) |
| JDK | 17.0.9 |
| Protocol | HTTP/1.1 keep-alive |
| JMH | 10×10s warmup/measurement, fork 1 |
| JFR | Disabled (recording overhead would distort throughput) |

> **Fairness note:** All containers are compared under identical conditions — the same virtualization, the same client load, the same network tax. perf and the compared frameworks bear **the same runtime cost**, so its relative advantage is the real framework advantage. Absolute throughput is shaped by the runtime topology (external mode); prefer **relative multiples and concurrency scaling** when comparing horizontally.

## Container Profiles

| Profile | Port | Description |
|---------|------|-------------|
| perf | 9092 | WebPerf native Netty + 5 WebFilter + 3 Interceptor |
| perf-support | 9094 | perf + spring-web-servlet (Servlet bridge) + 5 Filter + 3 Interceptor |
| tomcat | 9102 | Spring MVC + Tomcat + 5 Filter + 3 Interceptor |
| undertow | 9112 | Spring MVC + Undertow + 5 Filter + 3 Interceptor |
| webflux | 9122 | Spring WebFlux + Reactor Netty + 8 WebFilter |

## Framework Versions

Same binaries as [benchmark.md](benchmark.md#framework-versions) (actual artifacts resolved from the project's `spring-boot-dependencies` **3.2.12** BOM):

| Framework | Version |
|-----------|---------|
| Spring MVC | **6.1.15** (Spring Framework) |
| Spring WebFlux | **6.1.15** (Spring Framework) |
| Tomcat | **10.1.33** (jakarta.servlet 6.0.0) |
| Undertow | **2.3.17.Final** |
| Reactor Netty | **1.1.24** (reactor-bom 2023.0.12 / reactor-core 3.6.12) |
| Netty | **4.1.115.Final** |
| Jackson | **2.17.2** |
| OkHttp (JMH client) | **4.12.0** |
| JMH | **1.37** |

## Test Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| json | POST /api/demo/echo | Small JSON body (~50B) + echo |
| get | GET /api/demo/hello/{name} | Path parameter + 5 query parameter bindings |
| bytes | GET /api/core/bytes | Raw byte response (26B) |
| valid | POST /api/core/validate | @Validated Bean Validation |
| async | GET /api/core/deferred-result | Async DeferredResult |
| bytesLarge | GET /api/core/large-response | 100KB byte[] response body |
| sse | GET /api/core/sse | SSE streaming push (100 messages × 200 chars) |

---

## 1. Concurrency Scaling: perf's Core Advantage

### 1.1 json Throughput and Advantage Multiple vs Concurrency (ops/sec)

| Threads | perf | vs Spring MVC | vs WebFlux |
|---------|------|---------------|------------|
| 16 | 28,877 | **2.29x** (12,610) | **1.69x** (17,064) |
| 32 | 40,967 | **3.00x** (13,646) | **2.03x** (20,166) |
| 48 | **43,896** | **2.96x** (14,806) | **2.31x** (18,981) |

**perf's advantage multiple grows from 2.29x at 16 threads to 2.96x at 48 threads** — the higher the concurrency, the wider the lead.

### 1.2 Concurrency Scaling Ratio (16 → 48 threads)

Measures a framework's ability to turn concurrency into throughput.

| Framework | json | bytes |
|-----------|------|-------|
| **perf** | **+52%** (28,877→43,896) | **+34%** (33,735→45,152) |
| Spring MVC (Tomcat) | +17% (12,610→14,806) | +8% (19,669→21,281) |
| WebFlux | +11% (17,064→18,981) | +23% (22,336→27,363) |

### 1.3 Analysis

- **perf scales the best**: json concurrency scaling is +52%, **3.1x** that of Spring MVC (+17%) and 4.7x that of WebFlux (+11%). Spring MVC's thread pool does grow with concurrency, but only marginally — thread contention and context switching eat most of the added concurrency; perf's EventLoop model has no such overhead and keeps converting concurrency into throughput.
- **perf saturates last**: perf is still growing at 48 threads (another +7% from 32→48), while WebFlux peaks and falls back at 32 (20,166→18,981). This directly reflects perf's **extremely low per-request processing cost** — the server has spare compute that only higher client concurrency can fully load.

## 2. Full-API Comparison at High Concurrency (48 threads, ops/sec)

| Endpoint | perf | perf-support | tomcat | undertow | webflux |
|----------|------|--------------|--------|----------|---------|
| async | **44,104** | 38,374 | 14,407 | 15,178 | 23,996 |
| bytes | **45,152** | 42,421 | 21,281 | 24,559 | 27,363 |
| bytesLarge | **7,627** | 7,340 | 6,968 | 6,052 | 6,900 |
| get | **42,066** | 38,312 | 12,576 | 12,916 | 18,797 |
| json | **43,896** | 36,424 | 14,806 | 14,401 | 18,981 |
| sse | **5,715** | 3,160 | 2,170 | 1,985 | 2,743 |
| valid | **45,515** | 38,670 | 14,789 | 15,681 | 19,925 |

**At 48 threads, perf beats Spring MVC by 2.12x–3.34x** (excluding `bytesLarge`, where throughput is dominated by 100KB transfer bandwidth at 1.09x) and WebFlux by 1.65x–2.31x.

> Note: `get` (multi-parameter binding), `async` (async return), and `valid` (bean validation) are exactly the scenarios where perf's pre-caching and zero-reflection model pay off the most — all three reach the **3x** level against Spring MVC; `sse` (long connection) reaches **2.63x**.

---

## 3. Why perf Is Faster

perf's advantage comes from deliberate engineering trade-offs at the framework-design level, not a vague "Netty is faster than Tomcat." The core differences:

| Dimension | WebPerf (perf) | Spring MVC + Tomcat | Spring WebFlux |
|-----------|-------------------|---------------------|-----------------|
| Engine | **Native Netty** | Tomcat Servlet container | Reactor Netty |
| Programming model | **Synchronous + optional reactive** | Synchronous blocking | Reactive (Mono/Flux) |
| Threading model | **EventLoop handles directly, zero switching or opt-in `@RunInPool`** | Fixed container thread pool, thread switch per request | Fully reactive EventLoop |
| Route matching | **O(1) HashMap multi-level optimizer chain** | `AntPathMatcher` O(n) traversal | `PathPattern`, approx. O(log n) |
| Method invocation | **ASM/MethodHandle, zero reflection (~10-30ns)** | `Method.invoke()` reflection (~200ns) | `Method.invoke()` reflection (~200ns) |
| Argument resolution | **Pre-cached at startup, direct invocation at runtime** | Runtime traversal + `synchronized` cache | Runtime traversal |
| Return value handling | **Pre-cached at startup, direct hit at runtime** | Runtime traversal + matching | Runtime traversal + matching |
| Allocation | **Zero temporary objects on the request path** | Multiple allocations (argument Maps, validation Errors, etc.) | Reactive chain Mono/Flux allocations |
| SSE implementation | **Lock-free Drain Loop + unified EventLoop writes** | One thread per connection, synchronized blocking writes | Reactor backpressure, scheduling overhead grows with concurrency |

**In one sentence**: this framework **eliminates all runtime "lookup" and "matching" overhead through deterministic resolution at startup** — which is exactly why perf's advantage keeps widening with thread count (rather than peaking and falling back) under high concurrency. For detailed architecture decisions see [Performance Principles](performance-principles.md).

---

## 4. Key Findings

1. **Concurrency scaling crushes the competition**: json 16→48 threads, perf gains **+52%** vs Spring MVC's +17%; the advantage multiple grows from 2.29x to **2.96x** — the higher the concurrency, the bigger perf's lead.
2. **Absolute high-concurrency throughput lead**: json at 48 threads hits **43,896 ops/s** — 2.96x Spring MVC, 2.31x WebFlux.
3. **SSE long-connection advantage**: 5,715 ops/s at 48 threads, **2.63x** Spring MVC; p50 latency 7.23ms, only **1/3** of MVC's 21.46ms — EventLoop + lock-free Drain Loop pay off most in long-connection scenarios.
4. **Async, parameter binding, and validation are the strongest scenarios**: async / get / valid reach **3.06x / 3.34x / 3.08x** against Spring MVC — the engineering payoff of pre-caching + zero reflection concentrates under high concurrency.
5. **Low latency delivered alongside high throughput**: json p50 at 48 threads is **0.92ms**, just **29%** of Spring MVC's 3.16ms — high throughput and low latency achieved at the same time.
6. **perf-support bridge overhead is manageable**: ~6–17% on ordinary endpoints, ~45% in the SSE long-connection scenario (per-connection bridge cost); yet even with the bridge layer it keeps a **2x–3x** advantage over Spring MVC (get 3.05x, async 2.66x, json 2.46x, valid 2.61x, bytes 1.99x).

---

## How to Reproduce

> Benchmarking supports two run modes (in-process / WSL external); full one-command usage is documented in the [Benchmark Run Guide](benchmark-run.md).

```bash
cd spring-web-benchmark
# One-command full WSL external run (5 profiles × 7 APIs, 16 threads, throughput + latency)
./scripts/wsl-run-all.sh --sampleTime --threads 16
# Concurrency scaling matrix (16/32/48 threads; produces §1 data)
./scripts/wsl-run-all.sh --sampleTime --thread-list 16,32,48
# Report output: benchmark-reports/{run-id}/report.md
```

> Data based on JDK 17 + WSL2 external mode (`jfr=off`), compared fairly in the same environment — relative multiples reflect the real framework advantage. This document's data source: `benchmark-reports/20260811-005942` (thrpt,sample | 16/32/48 threads | 2026-08-11). The standard environment (JDK 17 in-process) benchmark is documented in [benchmark.md](benchmark.md).
