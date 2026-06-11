# SpringPerf JMH 基准测试

## 多维度性能对比

从 v1.1 起，benchmark 模块支持 **TPS、延迟百分位、GC 行为、内存占用** 四维度自动采集和报告生成。

## 快速开始

### 一键全量运行（推荐）

```bash
# Linux / macOS / WSL
./spring-web-benchmark/benchmark-all.sh

# Windows
spring-web-benchmark\benchmark-all.bat
```

执行流程：
1. 全量编译（`mvn clean install -DskipTests`）
2. 依次运行 10 个 profile（互不干扰，失败不影响后续）
3. 自动生成 Markdown 报告

> 如果脚本报找不到 ReportGenerator main class 的错误，在运行后手动执行报告生成即可。

### 手动生成报告

```bash
cd spring-web-benchmark
mvn compile -q
mvn exec:java -pl . \
  -Dexec.mainClass="io.springperf.benchmark.report.generator.ReportGenerator" \
  -Dexec.args="benchmark-reports"
```

### 单 profile 运行（开发调优）

```bash
mvn jmh:run -Pbenchmark-perf \
  -Dbenchmark.port=9091 \
  -Dbenchmark.profile.name=perf
```

## 采集指标

| 维度 | 来源 | 指标 |
|------|------|------|
| TPS | JMH `Mode.Throughput` | ops/sec |
| 延迟 | JMH `Mode.SampleTime` | p50 / p90 / p99 / p99.9 / p99.99 (ms) |
| GC | JVM GC 日志 | Young GC 次数 / 平均暂停 / 最大暂停 / Full GC 次数 |
| 内存 | `MemoryMXBean` @TearDown | Heap Used / Metaspace / Code Cache |

## 输出结构

```
spring-web-benchmark/benchmark-reports/
├── 20250610-120000/              ← 每次运行独立目录（永不覆盖）
│   ├── jdk-11.0.20/
│   │   ├── jmh-results-perf.json     ← TPS + 延迟百分位
│   │   ├── gc-perf.log               ← GC 暂停日志
│   │   ├── memory-perf.json          ← 内存快照
│   │   ├── jmh-results-tomcat.json
│   │   ├── gc-tomcat.log
│   │   ├── memory-tomcat.json
│   │   ├── ... (10 profile 重复)
│   │   └── run-summary.json          ← 执行状态记录
│   └── report.md                     ← 本期汇总报告
├── 20250609-080000/              ← 历史报告
│   └── ...
└── latest/report.md              ← 最新报告入口
```

## Profile 列表与端口分配

| Profile | 端口 | 说明 |
|---------|------|------|
| perf | 9091 | 自定义 Perf Netty 框架 |
| perf-filter | 9092 | Perf + 5 WebFilter + 3 Interceptor |
| perf-support | 9093 | Perf + spring-web-support 桥接 |
| perf-support-filter | 9094 | Perf + Support + Servlet Filter |
| tomcat | 9101 | Spring MVC + Tomcat |
| tomcat-filter | 9102 | Tomcat + 5 Filter + 3 Interceptor |
| undertow | 9111 | Spring MVC + Undertow |
| undertow-filter | 9112 | Undertow + 5 Filter + 3 Interceptor |
| webflux | 9121 | Spring WebFlux + Reactor Netty |
| webflux-filter | 9122 | WebFlux + 8 WebFilter |

## 基准测试场景

| 方法 | 端点 | 说明 |
|------|------|------|
| jsonEcho | POST /api/demo/echo | 小 JSON POST + 回显 201 |
| helloGet | GET /api/demo/hello/{name}/aaaxxx | 路径变量 + 5 查询参数 |
| asyncDeferredResult | GET /api/core/deferred-result | 异步 DeferredResult |
| bytes | GET /api/core/bytes | 原始字节响应 |
| validatePost | POST /api/core/validate | @Validated Bean Validation |
| jsonEchoLarge | POST /api/demo/echo | ~100KB 大 JSON 请求体 |
| largeResponse | GET /api/core/large-response | 100KB 响应体 |

## 配置

| 参数 | 默认值 |
|------|--------|
| 预热 | 5 次 × 10 秒 |
| 测量 | 10 次 × 10 秒 |
| Fork | 1（隔离 JVM） |
| 线程 | 4 |
| 堆 | 1GB G1GC + AlwaysPreTouch |
| 协议 | HTTP/1.1 |

## JDK 兼容

- **JDK 8**: GC 日志通过 `-XX:+PrintGCDetails` 输出，报告阶段由 `Jdk8GcLogParser` 解析
- **JDK 11+**: GC 日志通过 `-Xlog:gc*` 输出，报告阶段由 `Jdk11GcLogParser` 解析
- 内存快照：使用 `MemoryMXBean` API，JDK 8~21 一致
- 自动检测：解析器按日志首行格式特征自动选择

## 多 JDK 对比（高级用法）

```bash
# 安装多 JDK 后，指定路径列表
./benchmark-all.sh --jdk-list /usr/lib/jvm/jdk8,/usr/lib/jvm/jdk11
```

报告将按 JDK 分组展示，支持跨版本对比。
