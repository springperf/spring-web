# WSL external 基准测试模式

> **本文档是模式二（WSL external）详细操作手册**。两种模式总览与选择见 [`docs/benchmark-run.md`](../../docs/benchmark-run.md)（模式一 in-process 见 [`docs/benchmark.md`](../../docs/benchmark.md)）。

把被测服务端放进 WSL2（限 4c / 2g），客户端（OkHttp JMH）跑在 Windows 宿主机，两者资源隔离。相比 in-process 模式（服务端与客户端同 JVM 抢 CPU），external 模式服务端是 Linux + 受限资源，更接近生产。

> **一句话上手**：环境准备就绪后，`./scripts/wsl-run-all.sh --sampleTime` 一条命令跑完全部 5 个 profile × 7 API 的吞吐+延迟压测（约 4.5h），睡一觉醒来直接看 `spring-web-benchmark/benchmark-reports/{run-id}/report.md`。详见 [§3 一键全量压测](#3-一键全量压测wsl-run-allsh)。

## 1. 架构

```
┌────────────── Windows 主机 ──────────────┐      ┌──── WSL2 VM (4c / 2g) ────┐
│  JMH JVM（客户端）                       │      │  java ... PerfApplication  │
│  OkHttp N 线程 + JFR(客户端)             │ ◄──► │  Netty 服务端 + JFR(服务端) │
│  -Dbenchmark.target=<WSL IP>             │  直连 │  -Xms768m G1GC            │
└──────────────────────────────────────────┘      └─────────────────────────────┘
```

## 2. 环境准备

### 2.1 WSL 资源限制（%UserProfile%\.wslconfig）

```
[wsl2]
processors=4
memory=2GB
swap=0
```

生效：`wsl --shutdown` 后重启 WSL。

> 内存口径（实测配置，与 docs/benchmark-wsl.md 数据来源一致）：
> - `memory=2GB` + JVM `-Xmx768m`（堆 + Metaspace + 直接内存留足余量）
>
> 注意：`processors`/`memory` 限制的是**整个 WSL2 VM**（所有发行版共享），WSL 内只跑被测服务端，不要放 Docker Desktop 等。

### 2.2 WSL 内装 JDK 17

```bash
sudo apt update && sudo apt install -y openjdk-17-jdk
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
```

### 2.3 Windows 侧

- Maven（PATH 中），JDK（源码兼容 8，构建用 8/17 均可）
- `curl`（就绪探测用；Windows 10 自带）
- WSL 发行版已启动过至少一次（`wsl --shutdown` 后需先启动过，脚本才能取到 IP）

### 2.4 断网挂机：离线 Maven 依赖

`wsl-run-all.sh` 强制 `mvn -o` 离线运行（避免断网挂机时卡在连阿里云重试）。**首次必须先在线构建一次**把所有依赖拉进本地 `.m2`：

```bash
cd <项目根>
mvn -pl spring-web-benchmark -am install -DskipTests   # 在线，拉全依赖
```

之后断网也能跑；缺依赖会立刻失败（可诊断）而不是挂死。

## 3. 一键全量压测（wsl-run-all.sh）

推荐主路径。自动完成：全量编译 → 逐 profile 构建 → WSL 启动服务端 + 就绪探测 → Windows 跑 JMH → 优雅停止 → 生成 Markdown 报告。单 profile 失败不中断整体（记 FAIL 继续），适合睡前挂机。

```bash
cd spring-web-benchmark
# ① 默认全量（5 profile × 7 API，4 线程，只吞吐，约 2.2h）
./scripts/wsl-run-all.sh
# ② 吞吐+延迟双模式（约 4.5h，推荐，报告含 p50/p90/p99 延迟表）
./scripts/wsl-run-all.sh --sampleTime
```

### 3.1 参数一览

| 参数 | 说明 | 默认值 | 示例 |
|------|------|--------|------|
| `--sampleTime` | 吞吐+延迟双模式（thrpt,sample，报告含延迟百分位表） | 只吞吐（thrpt） | `--sampleTime` |
| `--profiles a,b,c` | 限定 profile 子集 | 全部 5 个 | `--profiles perf,tomcat` |
| `--apis json,sse` | 限定 API 子集（逗号分隔） | 全部 7 个 | `--apis json,bytes` |
| `--threads N` | 单轮客户端并发线程 | @Threads(4) | `--threads 64` |
| `--thread-list 1,4,16` | 多并发度（threads-N 子目录，自动出并发伸缩矩阵报告） | 单轮 | `--thread-list 16,32,48` |
| `--jfr` | 服务端开 JFR 录制（热点分析用） | 关 | `--jfr` |
| `-- ...` | 透传 JMH 参数（如冒烟短迭代） | 无 | `-- -w 1 -wi 1 -i 1 -r 1s` |

内置 profile（端口与 Benchmark 类）：

| Profile | 端口 | 说明 |
|---------|------|------|
| perf | 9092 | WebPerf 原生 Netty + 5 WebFilter + 3 Interceptor |
| perf-support | 9094 | perf + spring-web-support (Servlet 桥接) + 5 Filter + 3 Interceptor |
| tomcat | 9102 | Spring MVC + Tomcat + 5 Filter + 3 Interceptor |
| undertow | 9112 | Spring MVC + Undertow + 5 Filter + 3 Interceptor |
| webflux | 9122 | Spring WebFlux + Reactor Netty + 8 WebFilter |

内置 API：`json`(POST /api/demo/echo)、`get`(GET /api/demo/hello/{name})、`bytes`(GET /api/core/bytes)、`valid`(POST /api/core/validate)、`async`(GET /api/core/deferred-result)、`bytesLarge`(GET /api/core/large-response 100KB)、`sse`(GET /api/core/sse 100×200 字符)。

### 3.2 模式控制与预期时长

- **只吞吐（默认）**：`-m thrpt`，约 **2.2h**。
- **吞吐+延迟（`--sampleTime`）**：`-m thrpt,sample`，约 **4.5h**。报告同时出吞吐表与延迟表（p50/p90/p99/p99.9/p99.99）。

时长估算口径：5 profile × 7 API × JMH 预热 10×10s + 测量 10×10s × fork 1，多并发度（`--thread-list`）按档位叠加。

### 3.3 并发伸缩矩阵（--thread-list）

```bash
# 并发伸缩性对比（16/32/48 线程），自动生成并发矩阵报告
./scripts/wsl-run-all.sh --sampleTime --thread-list 16,32,48
```

每个并发度结果落在 `threads-N/jdk-*/` 子目录，ReportGenerator 检测到该结构自动生成并发伸缩矩阵（benchmark-wsl.md §1 的数据来源）。

### 3.4 冒烟验证（快速确认全链路）

正式长跑前先冒烟，确认构建/服务端/压测/报告全链路通：

```bash
# 单 profile + 短迭代（-- 后参数原样透传给每个 profile 的 JMH）
./scripts/wsl-run-all.sh --profiles perf -- -w 1 -wi 1 -i 1 -r 1s
```

### 3.5 产物结构

```
spring-web-benchmark/benchmark-reports/
├── latest/report.md                        ← 最新报告（自动同步覆盖）
├── {run-id}/                               ← 本次运行快照（时间戳）
│   ├── report.md                           ← 主报告
│   ├── run-meta.txt                        ← 运行元信息（mode/threads/profiles/apis/jfr）
│   ├── threads-N/                          ← --thread-list 时，各并发度独立子目录
│   │   └── jdk-<ver>/jmh-results-<profile>.json
│   ├── jdk-<ver>/jmh-results-<profile>.json   ← 单并发度时的 JMH 原始 JSON
│   ├── <profile>-server.log                ← 各服务端日志
│   └── <profile>-server.jfr                ← 服务端 JFR（仅 --jfr；落盘 Windows 盘，
│                                              防 WSL VM 空闲关闭清空 /tmp）
```

### 3.6 容错行为

- **单 profile FAIL 不中断整体**：编译/classpath/服务端未就绪/JMH 失败均记 FAIL 继续下一个，报告头部列出失败列表。
- **服务端启动容错**：就绪探测（90s）失败会自动清理残留并重试一次，二次失败才跳过。
- **防干扰**：自动启动 WSL keepalive 进程（防 profile 间隙 VM 空闲关闭）+ Windows no-sleep（防断网挂机时系统睡眠中断压测）。
- **JFR 默认关**：实测 JFR profile 模式拖慢服务端 ~38% 吞吐；TPS 数据取自 JMH 客户端结果 JSON 不受影响，但 --jfr 建议仅用于热点分析场景。

### 3.7 常用组合

```bash
# 正式全量，吞吐+延迟（推荐，睡醒看报告）
./scripts/wsl-run-all.sh --sampleTime
# 只测 perf/tomcat 的 json+bytes，64 线程
./scripts/wsl-run-all.sh --profiles perf,tomcat --apis json,bytes --threads 64
# 并发伸缩矩阵（宣传稿 §1 数据）
./scripts/wsl-run-all.sh --sampleTime --thread-list 16,32,48
```

## 4. 手动流程（开发调试 / 单 profile 迭代）

一键脚本适合全量对比；改代码后的单 profile 快速迭代用两步流程更灵活：

```bash
# ① 构建 + WSL 内启动服务端（阻塞前台，Ctrl+C 停止）
./scripts/wsl-server.sh benchmark-perf 9092 768

# ② 另开一个终端，跑 JMH 压测（客户端在 Windows）
./scripts/wsl-benchmark.sh benchmark-perf 9092 -t 64
```

结果与 JFR：
- 服务端 JFR：WSL 内 `/tmp/perf-server.jfr`（启动命令自带 `-XX:StartFlightRecording`）
- 客户端 JFR：`./scripts/wsl-benchmark.sh benchmark-perf 9092 -jvmArgsAppend "-XX:StartFlightRecording=filename=client.jfr,settings=profile"`
- JMH 结果：控制台（或追加 `-rf json -rff target/jmh-results-external.json` 落盘）

## 5. 抢占验证清单（跑压测时同时观测）

目标：确认服务端是瓶颈、客户端没有抢 WSL2 的 vCPU。

| 检查项 | 命令 | 期望 |
|--------|------|------|
| Windows 总 CPU | `typeperf "\Processor(_Total)\% Processor Time" -sc 30` | < 85%（留余量给 WSL2 vCPU） |
| WSL 服务端 CPU | WSL 内 `top` 或 `htop` | 4c 吃满 ~400% = 服务端是瓶颈 |
| 客户端是否强占 | 若 Windows 打满且服务端 < 200% | 降并发 `-t` 或钉亲和性（见下） |

若客户端抢占服务端（Windows 100%、服务端没吃满 4c）：
1. 首选：减并发 `-t 16`（客户端线程少了 CPU 占用直线下降，仍能喂饱 4c 服务端）
2. 次选：钉客户端亲和性，物理空出核给 WSL2：
   ```powershell
   $p = Get-Process java   # JMH 进程
   $p.ProcessorAffinity = 0xFFF0   # 钉到核 4-15，留 0-3 给 WSL2 vCPU
   ```

## 6. 已知环境问题

- 阿里云镜像缺少 `jmh-maven-plugin:1.37`，`mvn jmh:run` 会报 `No plugin found for prefix 'jmh'`。
  脚本已绕开：直接用 `org.openjdk.jmh.Main` 跑（与 in-process 冒烟同一链路）。
  若要治本，可在 `~/.m2/settings.xml` 的 mirror 里补充 Maven Central。
- WSL 首次冷启动较慢：若 profile 间隙 VM 被关闭（keepalive 失效场景），就绪探测可能超时一次，脚本会自动重试。

## 7. 代码开关（已实现）

- `BenchmarkConstants.TARGET_HOST` ← 系统属性 `-Dbenchmark.target=<host>`
- 空 = in-process（默认，现状）；非空 = external（不启动服务端，直接连远端）
- `AbstractServerBenchmark.setup()/teardown()` 按开关分支；external 模式跳过内存快照（本 JVM 仅客户端）
- `BenchClientState.setup(String base)` 支持任意 host

## 8. 注意事项

- **WSL2 IP 每次重启会变**：脚本自动 `wsl hostname -I` 动态获取，不要硬编码。
- **localhost 转发有中继开销**：客户端直连 WSL IP，不走 `localhost`。
- **in-process / external 结果不可横向直接对比**：前者 Windows 无约束、后者 Linux 4c2g，分开报告。
- **两个 WSL2 发行版共享同一个 VM 资源限制**：客户端仍放 Windows，不要放第二个 WSL2。
- **内存快照**：external 模式下 `memory-<profile>.json` 不再生成（服务端不在这台 JVM）。
- **报告入口**：对外宣传稿见 `docs/benchmark-wsl.md`（中）/ `docs/en/benchmark-wsl.md`（英），标准环境（JDK 8 in-process）见 `docs/benchmark.md`。
