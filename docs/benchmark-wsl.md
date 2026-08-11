> [English](en/benchmark-wsl.md) | 中文

# Spring WebPerf 性能基准测试报告（WSL2 External 实测）

> **定位：** 本文档展示 Spring WebPerf 框架在高并发下的性能优势，数据来自 JDK 17 + WSL2 external 模式的完整基准测试。
> 全部 5 个容器在**同一环境、同一客户端、同一负载**下对比，`perf` 为本框架 Benchmark Profile（原生 Netty 直驱），`perf-support` 为叠加 Servlet 桥接层的配置。

---

## 核心优势

| 维度 | perf | vs Spring MVC (Tomcat) | vs WebFlux |
|------|------|------------------------|------------|
| 高并发吞吐 (json 48 线程) | **43,896 ops/s** | **2.96x** (14,806) | **2.31x** (18,981) |
| 并发伸缩性 (json 16→48 线程) | **+52%** | +17% | +11% |
| SSE 长连接吞吐 (48 线程) | **5,715 ops/s** | **2.63x** (2,170) | **2.08x** (2,743) |
| 字节回显吞吐 (bytes 48 线程) | **45,152 ops/s** | **2.12x** (21,281) | **1.65x** (27,363) |
| 异步处理吞吐 (async 48 线程) | **44,104 ops/s** | **3.06x** (14,407) | **1.84x** (23,996) |
| 高并发延迟 (json 48 线程 p50) | **0.92 ms** | 3.16 ms (**29%**) | 1.95 ms (**47%**) |

**perf 在全部 7 个接口 × 3 个并发度（16/32/48 线程）的对比中保持第一——无一例外。**

最关键的发现：**perf 的优势随并发增长持续扩大**。WebFlux 在 32 线程即见顶回落，Spring MVC 涨幅微弱，而 perf 到 48 线程仍在增长——并发越高，框架优势越大。

---

## 测试环境

| 项 | 配置 |
|----|------|
| CPU | AMD Ryzen 7 4800U（8 物理核 / 16 逻辑核） |
| 内存 | 16 GB（WSL2 限 2 GB） |
| 运行形态 | WSL2（4 vCPU）运行服务端 + Windows 宿主 OkHttp JMH 客户端（external 模式） |
| JDK | 17.0.9 |
| 协议 | HTTP/1.1 keep-alive |
| JMH | 预热/测量 10×10s，fork 1 |
| JFR | 关闭（避免录制开销干扰吞吐） |

> **公平性说明：** 所有容器在完全相同环境下对比——同样的虚拟化、同样的客户端供给、同样的网络税负。perf 与对比框架承受**相同的运行代价**，其相对优势即为真实框架优势。绝对吞吐受运行形态（external 模式）影响，建议以**相对倍数与并发伸缩性**作为横向对比指标。

## 容器说明

| Profile | 端口 | 说明 |
|---------|------|------|
| perf | 9092 | WebPerf 原生 Netty + 5 WebFilter + 3 Interceptor |
| perf-support | 9094 | perf + spring-web-support (Servlet 桥接) + 5 Filter + 3 Interceptor |
| tomcat | 9102 | Spring MVC + Tomcat + 5 Filter + 3 Interceptor |
| undertow | 9112 | Spring MVC + Undertow + 5 Filter + 3 Interceptor |
| webflux | 9122 | Spring WebFlux + Reactor Netty + 8 WebFilter |

## 测试接口

| 方法 | 端点 | 说明 |
|------|------|------|
| json | POST /api/demo/echo | 小 JSON 请求体 (约 50B) + 回显 |
| get | GET /api/demo/hello/{name} | 路径参数 + 5 个查询参数绑定 |
| bytes | GET /api/core/bytes | 原始字节响应 (26B) |
| valid | POST /api/core/validate | @Validated Bean Validation |
| async | GET /api/core/deferred-result | 异步 DeferredResult 返回 |
| bytesLarge | GET /api/core/large-response | 100KB byte[] 响应体 |
| sse | GET /api/core/sse | SSE 流式推送 (100 条消息 × 200 字符) |

---

## 1. 并发伸缩性：perf 的核心优势

### 1.1 json 吞吐及优势倍数随并发度变化 (ops/sec)

| 并发线程 | perf | vs Spring MVC | vs WebFlux |
|----------|------|---------------|------------|
| 16 | 28,877 | **2.29x** (12,610) | **1.69x** (17,064) |
| 32 | 40,967 | **3.00x** (13,646) | **2.03x** (20,166) |
| 48 | **43,896** | **2.96x** (14,806) | **2.31x** (18,981) |

**perf 的优势倍数从 16 线程的 2.29x 一路扩大到 48 线程的 2.96x**——并发越高，领先幅度越大。

### 1.2 并发伸缩比（16 → 48 线程）

衡量框架将并发转化为吞吐的能力。

| 框架 | json | bytes |
|------|------|-------|
| **perf** | **+52%** (28,877→43,896) | **+34%** (33,735→45,152) |
| Spring MVC (Tomcat) | +17% (12,610→14,806) | +8% (19,669→21,281) |
| WebFlux | +11% (17,064→18,981) | +23% (22,336→27,363) |

### 1.3 分析

- **perf 伸缩性最强**：json 并发伸缩比 +52%，是 Spring MVC（+17%）的 **3.1 倍**、WebFlux（+11%）的 4.7 倍。Spring MVC 的线程池随并发增长但涨幅微弱——线程争抢与上下文切换吃掉了大部分新增并发；perf 的 EventLoop 模型无此开销，能持续把并发转化为吞吐。
- **perf 饱和点最高**：perf 到 48 线程仍在增长（32→48 再 +7%），WebFlux 在 32 线程即见顶回落（20,166→18,981）。这直接反映 perf **单请求处理开销极低**——服务端有富余算力，需要更多客户端并发才能打满。

## 2. 高并发全接口对比（48 线程, ops/sec）

| 接口 | perf | perf-support | tomcat | undertow | webflux |
|------|------|-------------|--------|----------|---------|
| async | **44,104** | 38,374 | 14,407 | 15,178 | 23,996 |
| bytes | **45,152** | 42,421 | 21,281 | 24,559 | 27,363 |
| bytesLarge | **7,627** | 7,340 | 6,968 | 6,052 | 6,900 |
| get | **42,066** | 38,312 | 12,576 | 12,916 | 18,797 |
| json | **43,896** | 36,424 | 14,806 | 14,401 | 18,981 |
| sse | **5,715** | 3,160 | 2,170 | 1,985 | 2,743 |
| valid | **45,515** | 38,670 | 14,789 | 15,681 | 19,925 |

**48 线程高并发下，perf 对 Spring MVC 的优势达 2.12x~3.34x**（bytesLarge 大响应体场景 1.09x 除外，其吞吐受 100KB 传输带宽主导），对 WebFlux 达 1.65x~2.31x。

> 注：`get`（多参数绑定）、`async`（异步返回）、`valid`（参数校验）正是 perf 预缓存与零反射优势最大的场景——三者对 Spring MVC 均达 **3x** 级别；`sse`（长连接）对 Spring MVC 达 **2.63x**。

---

## 3. 为什么 perf 更快

perf 的性能优势来自框架设计层面的工程取舍，而非"Netty 比 Tomcat 快"的泛泛说法。核心差异：

| 维度 | WebPerf (perf) | Spring MVC + Tomcat | Spring WebFlux |
|------|-------------------|-------------------|-----------------|
| 底层引擎 | **Netty 原生** | Tomcat Servlet 容器 | Reactor Netty |
| 编程模型 | **同步 + 可选响应式** | 同步阻塞 | 响应式（Mono/Flux） |
| 线程模型 | **EventLoop 直接处理，零切换或按需 `@RunInPool`** | 固定容器线程池，每请求线程切换 | EventLoop 全响应式 |
| 路由匹配 | **O(1) HashMap 多级优化器链** | `AntPathMatcher` O(n) 遍历 | `PathPattern` 近似 O(log n) |
| 方法调用 | **ASM/MethodHandle 零反射 (~10-30ns)** | `Method.invoke()` 反射 (~200ns) | `Method.invoke()` 反射 (~200ns) |
| 参数解析 | **启动时预缓存，运行时直接调用** | 运行时遍历 + `synchronized` 缓存 | 运行时遍历 |
| 返回值处理 | **启动时预缓存，运行时直接命中** | 运行时遍历匹配 | 运行时遍历匹配 |
| 对象分配 | **请求路径零临时对象创建** | 多次创建（参数 Map、验证 Errors 等） | 响应式链 Mono/Flux 对象分配 |
| SSE 实现 | **无锁 Drain Loop + EventLoop 统一写入** | 每连接一线程，同步阻塞写 | Reactor 背压，调度开销随并发增长 |

**一句话总结**：本框架通过**启动时确定性解析消除了运行时所有"查找"和"匹配"开销**——这正是高并发下 perf 优势随线程数持续扩大（而非见顶回落）的根本原因。详细架构决策见 [性能原理](performance-principles.md)。

---

## 4. 关键结论

1. **并发伸缩性碾压**：json 16→48 线程 perf 提升 **+52%**，Spring MVC 仅 +17%；优势倍数从 2.29x 扩大到 **2.96x**——并发越高，perf 优势越大。
2. **高并发绝对吞吐领先**：json 48 线程 **43,896 ops/s**，Spring MVC 的 2.96 倍、WebFlux 的 2.31 倍。
3. **SSE 长连接优势**：48 线程 SSE 吞吐 5,715 ops/s，Spring MVC 的 **2.63 倍**；p50 延迟 7.23ms 仅 MVC（21.46ms）的 **1/3**——EventLoop + 无锁 Drain Loop 在长连接场景优势最大化。
4. **异步与参数绑定场景最强**：async / get / valid 对 Spring MVC 均达 **3.06x / 3.34x / 3.08x** 级别，预缓存 + 零反射的工程收益在高并发下集中兑现。
5. **低延迟同步兑现**：json 48 线程 p50 延迟 **0.92ms**，仅为 Spring MVC（3.16ms）的 **29%**——高吞吐与低延迟同时达成。
6. **perf-support 桥接损耗可控**：普通接口相对 perf 损耗 6%~17%，SSE 长连接场景约 45%（桥接层每连接开销）；但即便叠加桥接层仍对 Spring MVC 保持 **2x~3x** 优势（get 3.05x、async 2.66x、json 2.46x、valid 2.61x、bytes 1.99x）。

---

## 如何复现

```bash
cd spring-web-benchmark
# WSL external 模式一键全量（5 profile × 7 API，16 线程，吞吐+延迟双模式）
./scripts/wsl-run-all.sh --sampleTime --threads 16
# 并发伸缩矩阵（16/32/48 线程，生成本文档 §1 数据）
./scripts/wsl-run-all.sh --sampleTime --thread-list 16,32,48
# 报告输出：benchmark-reports/{run-id}/report.md
```

> 数据基于 JDK 17 + WSL2 external 模式（`jfr=off`），同一环境公平对比，相对倍数即真实框架优势。本文数据来源：`benchmark-reports/20260811-005942`（thrpt,sample | 16/32/48 线程 | 2026-08-11）。标准环境（JDK 8 in-process）展示见 [benchmark.md](benchmark.md)。
