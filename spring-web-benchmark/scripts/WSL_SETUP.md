# WSL external 基准测试模式

把被测服务端放进 WSL2（限 2c / 1g），客户端（OkHttp JMH）跑在 Windows 宿主机，两者资源隔离。相比 in-process 模式（服务端与客户端同 JVM 抢 CPU），external 模式服务端是 Linux + 受限资源，更接近生产（1c1g Linux）。

## 架构

```
┌────────────── Windows 主机 ──────────────┐      ┌──── WSL2 VM (2c / 1g) ────┐
│  JMH JVM（客户端）                       │      │  java ... PerfApplication  │
│  OkHttp N 线程 + JFR(客户端)             │ ◄──► │  Netty 服务端 + JFR(服务端) │
│  -Dbenchmark.target=<WSL IP>             │  直连 │  -Xms768m G1GC            │
└──────────────────────────────────────────┘      └─────────────────────────────┘
```

## 1. 环境准备

### 1.1 WSL 资源限制（%UserProfile%\.wslconfig）

```
[wsl2]
processors=2
memory=1GB
swap=1GB
```

生效：`wsl --shutdown` 后重启 WSL。

> 内存口径：
> - 严格 1g 环境（推荐）：`memory=1GB` + JVM `-Xmx768m`（堆 + Metaspace + 直接内存塞进 1G VM）
> - 宽松档：`memory=1536MB` + JVM `-Xmx1g`（heap 足 1g，但 VM 稍大）
>
> 注意：`processors`/`memory` 限制的是**整个 WSL2 VM**（所有发行版共享），WSL 内只跑被测服务端，不要放 Docker Desktop 等。

### 1.2 WSL 内装 JDK 17

```bash
sudo apt update && sudo apt install -y openjdk-17-jdk
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
```

### 1.3 Windows 侧

- Maven（PATH 中），JDK（源码兼容 8，构建用 8/17 均可）

## 2. 使用流程

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

## 3. 抢占验证清单（跑压测时同时观测）

目标：确认服务端是瓶颈、客户端没有抢 WSL2 的 vCPU。

| 检查项 | 命令 | 期望 |
|--------|------|------|
| Windows 总 CPU | `typeperf "\Processor(_Total)\% Processor Time" -sc 30` | < 85%（留余量给 WSL2 vCPU） |
| WSL 服务端 CPU | WSL 内 `top` 或 `htop` | 2c 吃满 ~200% = 服务端是瓶颈 |
| 客户端是否强占 | 若 Windows 打满且服务端 < 200% | 降并发 `-t` 或钉亲和性（见下） |

若客户端抢占服务端（Windows 100%、服务端没吃满 2c）：
1. 首选：减并发 `-t 16`（客户端线程少了 CPU 占用直线下降，仍能喂饱 2c 服务端）
2. 次选：钉客户端亲和性，物理空出核给 WSL2：
   ```powershell
   $p = Get-Process java   # JMH 进程
   $p.ProcessorAffinity = 0x03FF   # 钉到核 2-9，留 0-1 给 WSL2 vCPU
   ```

## 4. 已知环境问题

- 阿里云镜像缺少 `jmh-maven-plugin:1.37`，`mvn jmh:run` 会报 `No plugin found for prefix 'jmh'`。
  脚本已绕开：直接用 `org.openjdk.jmh.Main` 跑（与 in-process 冒烟同一链路）。
  若要治本，可在 `~/.m2/settings.xml` 的 mirror 里补充 Maven Central。

## 5. 代码开关（已实现）

- `BenchmarkConstants.TARGET_HOST` ← 系统属性 `-Dbenchmark.target=<host>`
- 空 = in-process（默认，现状）；非空 = external（不启动服务端，直接连远端）
- `AbstractServerBenchmark.setup()/teardown()` 按开关分支；external 模式跳过内存快照（本 JVM 仅客户端）
- `BenchClientState.setup(String base)` 支持任意 host

## 6. 注意事项

- **WSL2 IP 每次重启会变**：脚本自动 `wsl hostname -I` 动态获取，不要硬编码。
- **localhost 转发有中继开销**：客户端直连 WSL IP，不走 `localhost`。
- **in-process / external 结果不可横向直接对比**：前者 Windows 无约束、后者 Linux 2c1g，分开报告。
- **两个 WSL2 发行版共享同一个 VM 资源限制**：客户端仍放 Windows，不要放第二个 WSL2。
- **内存快照**：external 模式下 `memory-<profile>.json` 不再生成（服务端不在这台 JVM）。
