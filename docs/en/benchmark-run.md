> English | [中文](../benchmark-run.md)

# Spring WebPerf Benchmark — Run Guide

The `spring-web-benchmark` module benchmarks the WebPerf framework (`perf`) head-to-head against Spring MVC / WebFlux / Undertow (`tomcat` / `undertow` / `webflux`) with JMH. There are **two run modes** — pick one, then follow the corresponding manual.

> **Reports (read-only)**: in-process data lives in [benchmark.md](benchmark.md), WSL external data in [benchmark-wsl.md](benchmark-wsl.md). This document covers only "how to run".

---

## 1. The Two Run Modes

| Dimension | Mode 1: in-process (standard env) | Mode 2: WSL external (constrained env) |
|-----------|-----------------------------------|----------------------------------------|
| Server | Started inside the client's JMH JVM (same JVM) | Standalone process in the WSL2 VM (Linux, 4c/2g) |
| Client | Same JVM | OkHttp JMH on the Windows host |
| Resource limits | None (full Windows cores) | Server constrained to 4c/2g — closer to production |
| JDK | 17 (same version as report data) | 17 (inside WSL, required for JFR) |
| One-command script | [`benchmark-all.sh`](../../spring-web-benchmark/benchmark-all.sh) | [`scripts/wsl-run-all.sh`](../../spring-web-benchmark/scripts/wsl-run-all.sh) |
| Output | Throughput/latency + GC logs + memory snapshots | Throughput/latency + client GC; **no memory snapshot** (server is not in this JVM) |
| Concurrency matrix | Auto via `--thread-list` | Auto via `--thread-list` (threads-N subdirs) |
| Full-run duration | Fast (minutes) | Slow (thrpt ≈2.2h, `--sampleTime` ≈4.5h) |
| Typical use | Quick full comparisons, GC / allocation analysis | Production-like runs, marketing scaling data, SSE long-connection scenarios |

## 2. How to Choose

- **Daily quick comparisons / GC & memory analysis**: Mode 1 (in-process). Same-JVM naturally yields GC logs and memory snapshots; full report in minutes. **Watch the thread ceiling**: in-process client and server share the same JVM cores, so concurrency should stay at ≤ core count (e.g. 4/8/16 on a 16-core box); going beyond enters oversubscription (client threads steal CPU, light-API throughput distorts, framework gaps flatten). Use Mode 2 for high-concurrency data.
- **Marketing data / realistic resource limits / SSE long connections**: Mode 2 (WSL external). Linux + 4c/2g constrained server, client isolated from production — a suitable source for promotional data; costs longer runtime and requires WSL setup first.
- The two modes are **not directly comparable** (unconstrained Windows vs Linux 4c2g) — report them separately.

## 3. Mode 1: in-process (standard env)

Prereqs: JDK 17+ (report data is generated on JDK 17 — use the same version to reproduce it), Maven 3.6+, and `mvn install -DskipTests` already done. Full parameter table in [benchmark.md → How to Run](benchmark.md#how-to-run).

```bash
# Full run (5 profiles × 7 APIs, 4 threads)
./spring-web-benchmark/benchmark-all.sh
# Concurrency scaling matrix (auto-generates comparison report; levels ≤ cores, 4,8,16 on this 16-core box)
./spring-web-benchmark/benchmark-all.sh --thread-list 4,8,16
# Subset of profiles + APIs
./spring-web-benchmark/benchmark-all.sh --profiles perf,tomcat --apis json,sse
# Throughput + latency percentiles
./spring-web-benchmark/benchmark-all.sh --sampleTime
```

Single-profile debugging: `cd spring-web-benchmark && mvn jmh:run -Pbenchmark-perf`.

## 4. Mode 2: WSL external (constrained env)

Prereqs: WSL2 limited to 4c/2g (`.wslconfig`), JDK 17 inside WSL, Maven/curl on Windows, offline `.m2`. **Full environment setup, parameter table, artifacts, and failure tolerance live in [`spring-web-benchmark/scripts/WSL_SETUP.md`](../../spring-web-benchmark/scripts/WSL_SETUP.md)**.

```bash
cd spring-web-benchmark
# Full throughput + latency run (recommended — wake up to a report)
./scripts/wsl-run-all.sh --sampleTime
# Concurrency scaling matrix (§1 data of the WSL report)
./scripts/wsl-run-all.sh --sampleTime --thread-list 16,32,48
# Smoke test (short iterations, confirms the whole pipeline)
./scripts/wsl-run-all.sh --profiles perf -- -w 1 -wi 1 -i 1 -r 1s
```

## 5. Report Artifacts

Both modes write to the same directory; the main report is auto-mirrored to `latest/`:

```
spring-web-benchmark/benchmark-reports/
├── latest/report.md              ← newest report (auto-overwritten)
└── {run-id}/                     ← per-run snapshot (timestamp)
    ├── report.md                 ← main report (Markdown comparison tables)
    ├── run-meta.txt              ← run metadata (mode/threads/profiles/apis/jfr)
    ├── jdk-<ver>/jmh-results-<profile>.json   ← raw JMH JSON
    ├── threads-N/                ← per-thread-count subdirs when --thread-list
    └── <profile>-server.log      ← server log (Mode 2)
```

> Mode 1 additionally produces GC logs (`gc-<profile>.log`) and memory snapshots (`memory-<profile>.json`); Mode 2 produces server logs and optional JFR (`--jfr`), but no memory snapshot.

Related docs:
- [benchmark.md](benchmark.md) — Mode 1 data report (JDK 17 in-process)
- [benchmark-wsl.md](benchmark-wsl.md) — Mode 2 data report (JDK 17 WSL external, promotional)
- [`scripts/WSL_SETUP.md`](../../spring-web-benchmark/scripts/WSL_SETUP.md) — Mode 2 detailed run manual
