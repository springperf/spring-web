> [English](en/benchmark-run.md) | 中文

# Spring WebPerf 性能基准测试运行指南

`spring-web-benchmark` 模块用 JMH 压测 WebPerf 框架（perf）与 Spring MVC / WebFlux / Undertow（tomcat / undertow / webflux）的横向对比。运行方式分**两种模式**，选好模式后进入对应手册执行即可。

> **数据报告（只读）**：in-process 数据见 [benchmark.md](benchmark.md)，WSL external 数据见 [benchmark-wsl.md](benchmark-wsl.md)。本文档只讲"怎么跑"。

---

## 1. 两种运行模式

| 维度 | 模式一：in-process（标准环境） | 模式二：WSL external（受限环境） |
|------|-------------------------------|----------------------------------|
| 服务端 | 客户端 JMH JVM 内自起（同 JVM） | WSL2 VM 内独立进程（Linux，4c/2g 受限） |
| 客户端 | 同 JVM | Windows 宿主 OkHttp JMH |
| 资源约束 | 无（Windows 全核） | 服务端 4c/2g，更接近生产 |
| JDK | 17（与报告数据同版本） | 17（WSL 内，JFR 需要） |
| 一键脚本 | [`benchmark-all.sh`](../spring-web-benchmark/benchmark-all.sh) | [`scripts/wsl-run-all.sh`](../spring-web-benchmark/scripts/wsl-run-all.sh) |
| 产物 | 吞吐/延迟 + GC 日志 + 内存快照 | 吞吐/延迟 + 客户端 GC；**无内存快照**（服务端不在本 JVM） |
| 并发矩阵 | `--thread-list` 自动 | `--thread-list` 自动（threads-N 子目录） |
| 单次全量耗时 | 快（分钟级） | 慢（thrpt ≈2.2h，`--sampleTime` ≈4.5h） |
| 典型用途 | 快速全量对比、GC / 内存分配分析 | 更接近生产、并发伸缩宣传数据、SSE 长连接场景 |

## 2. 如何选择

- **日常快速对比 / 分析 GC 与内存分配**：选模式一（in-process）。同 JVM 天然拿到 GC 日志与内存快照，分钟级出报告。**注意档位上限**：in-process 客户端与服务端共享同一 JVM 核数，并发线程建议取 ≤ 核数（如 16 核机用 4/8/16），否则进入超订阅（客户端线程抢 CPU、轻接口吞吐失真、框架差距被抹平）。高并发数据用模式二。
- **对外宣传数据 / 更真实的资源约束 / SSE 长连接**：选模式二（WSL external）。服务端 Linux + 4c/2g 受限，客户端与生产隔离，适合作为宣传数据来源；代价是时长大、需先配好 WSL 环境。
- 两种模式**结果不可横向直接对比**（Windows 无约束 vs Linux 4c2g），分开报告。

## 3. 模式一：in-process（标准环境）

前置：JDK 17+（报告数据即 JDK 17 生成，同版本可复现）、Maven 3.6+，项目已 `mvn install -DskipTests`。完整参数表见 [benchmark.md → 如何运行](benchmark.md#如何运行)。

```bash
# 全量运行（5 profile × 7 API，4 线程）
./spring-web-benchmark/benchmark-all.sh
# 并发伸缩矩阵（自动生成对比报告；档位 ≤ 核数，本文档 16 核用 4,8,16）
./spring-web-benchmark/benchmark-all.sh --thread-list 4,8,16
# 指定 profile + API 子集
./spring-web-benchmark/benchmark-all.sh --profiles perf,tomcat --apis json,sse
# 吞吐 + 延迟百分位
./spring-web-benchmark/benchmark-all.sh --sampleTime
```

单 profile 调试：`cd spring-web-benchmark && mvn jmh:run -Pbenchmark-perf`。

## 4. 模式二：WSL external（受限环境）

前置：WSL2 限 4c/2g（`.wslconfig`）、WSL 内 JDK 17、Windows Maven/curl、离线 `.m2`。**完整环境准备、参数表、产物、容错见 [`spring-web-benchmark/scripts/WSL_SETUP.md`](../spring-web-benchmark/scripts/WSL_SETUP.md)**。

```bash
cd spring-web-benchmark
# 吞吐 + 延迟双模式全量（推荐，睡醒看报告）
./scripts/wsl-run-all.sh --sampleTime
# 并发伸缩矩阵（宣传稿 §1 数据）
./scripts/wsl-run-all.sh --sampleTime --thread-list 16,32,48
# 冒烟验证（短迭代，确认全链路）
./scripts/wsl-run-all.sh --profiles perf -- -w 1 -wi 1 -i 1 -r 1s
```

## 5. 报告产物

两种模式结果都落在同一目录，主报告自动同步到 `latest/`：

```
spring-web-benchmark/benchmark-reports/
├── latest/report.md              ← 最新报告（自动覆盖）
└── {run-id}/                     ← 每次运行快照（时间戳）
    ├── report.md                 ← 主报告（Markdown 对比表）
    ├── run-meta.txt              ← 运行元信息（mode/threads/profiles/apis/jfr）
    ├── jdk-<ver>/jmh-results-<profile>.json   ← JMH 原始 JSON
    ├── threads-N/                ← --thread-list 时，各并发度独立子目录
    └── <profile>-server.log      ← 服务端日志（模式二）
```

> 模式一额外产出 GC 日志（`gc-<profile>.log`）与内存快照（`memory-<profile>.json`）；模式二产出服务端日志与可选 JFR（`--jfr`），但无内存快照。

相关文档：
- [benchmark.md](benchmark.md) — 模式一数据报告（JDK 17 in-process）
- [benchmark-wsl.md](benchmark-wsl.md) — 模式二数据报告（JDK 17 WSL external，宣传稿）
- [`scripts/WSL_SETUP.md`](../spring-web-benchmark/scripts/WSL_SETUP.md) — 模式二详细操作手册
