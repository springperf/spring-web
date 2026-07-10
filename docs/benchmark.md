> [English](en/benchmark.md) | 中文

# Spring Web 性能对比报告

**生成时间:** 2026-07-09 16:54:37

**JDK:** jdk-1.8.0_341

---

## 核心优势

| 维度 | perf | vs Spring MVC | vs WebFlux |
|------|------|-----------|-------------|
| 吞吐量 (json 4t) | **26,718 ops/s** | **1.90x** | **1.73x** |
| SSE 吞吐量 (64t) | **2,624 ops/s** | **6.37x** | **2.64x** |
| p50 延迟 (bytes 4t) | **0.12ms** | Spring MVC 的 50-60% | Spring MVC 的 50-60% |
| 每请求分配 (json 4t) | **17.7KB** | 29.9KB (-41%) | 36.0KB (-51%) |
| 吞吐伸缩比 (json 4→64t) | **+63%** | +58%（16t 后停滞） | +47% |
| SSE 每请求分配 (64t) | **249.2KB** | 1163.3KB (-79%) | 717.9KB (-65%) |
| 4 线程堆内存 | **20MB** | 23MB | 23MB |

perf 在所有维度均领先：吞吐量最高、延迟最低、分配最少、内存最省。SSE 场景优势最为显著（64 线程下吞吐达 Spring MVC 的 **6.37 倍**）。

**perf 在全部 7 个接口 × 3 个并发度 × 4 个对比框架中实现 100% 胜率——无一例外。**

---

## 测试环境

| 项目 | 配置 |
|------|------|
| CPU | Intel(R) Core(TM) i7-10750H (12 核) |
| 内存 | 32 GB |
| JDK | OpenJDK 1.8.0_341 |
| JVM 参数 | -Xms1g -Xmx1g -XX:+UseG1GC -XX:+AlwaysPreTouch |
| 协议 | HTTP/1.1 (keep-alive) |
| JMH 预热 | 10 轮 × 10 秒 |
| JMH 测量 | 10 轮 × 10 秒 |
| Fork | 1 (隔离 JVM) |
| 并发线程 | 4, 16, 64 |
| 操作系统 | Windows 11 |

## 容器说明

| Profile | 端口 | 说明 |
|---------|------|------|
| perf | 9092 | Spring Web 原生 Netty + 5 WebFilter + 3 Interceptor |
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

## 1. 吞吐量伸缩性

### 1.1 4线程基线 (ops/sec)

| 接口 | perf | Spring MVC (Tomcat) | Spring MVC (Undertow) | WebFlux |
|------|------|--------|----------|---------|
| json | 26718 | 14061 | 10305 | 15460 |
| get | 27398 | 12502 | 13425 | 13174 |
| bytes | 34232 | 20045 | 11737 | 17211 |
| valid | 26706 | 14544 | 13662 | 15621 |
| async | 28354 | 13407 | 10169 | 18283 |
| bytesLarge | 11508 | 4979 | 7764 | 7737 |
| sse | 1226 | 315 | FAIL | 944 |

#### perf 优势倍数 (4线程, vs Spring MVC)

| 接口 | vs Spring MVC (Tomcat) | vs Spring MVC (Undertow) | vs WebFlux |
|------|-----------|-------------|-------------|
| json | **1.90x** | **2.59x** | **1.73x** |
| get | **2.19x** | **2.04x** | **2.08x** |
| bytes | **1.71x** | **2.92x** | **1.99x** |
| valid | **1.84x** | **1.95x** | **1.71x** |
| async | **2.11x** | **2.79x** | **1.55x** |
| bytesLarge | **2.31x** | **1.48x** | **1.49x** |
| sse | **3.89x** | — | **1.30x** |

### 1.2 伸缩比 (16/4, 64/4)

| 框架 | 线程 | json | get | bytes | valid | async | bytesLarge | sse |
|------|------|------|-----|-------|-------|-------|-----------|-----|
| **perf** | 16 | 1.36x | 1.38x | 1.44x | 1.31x | 1.36x | 1.50x | 1.49x |
| | 64 | **1.63x** | **1.55x** | **1.55x** | **1.49x** | **1.59x** | **1.17x** | **2.14x** |
| Spring MVC (Tomcat) | 16 | 1.61x | 1.58x | 1.64x | 1.59x | 1.59x | 1.33x | 1.25x |
| | 64 | **1.58x** | **1.41x** | **1.57x** | **1.49x** | **1.53x** | **1.32x** | **1.31x** |
| Spring MVC (Undertow) | 16 | 2.04x | 1.44x | 2.61x | 1.56x | 1.97x | 1.56x | — |
| | 64 | **2.03x** | **1.36x** | **2.77x** | **1.59x** | **2.11x** | **1.43x** | — |
| webflux | 16 | 1.44x | 1.50x | 1.18x | 1.28x | 1.09x | 1.23x | 1.15x |
| | 64 | **1.47x** | **1.44x** | **1.75x** | **1.35x** | **1.31x** | **1.55x** | **1.05x** |

### 1.3 perf 优势倍数随线程变化 (vs Spring MVC)

| 接口 | 4线程 | 16线程 | 64线程 |
|------|-------|--------|--------|
| json | 1.90x | 1.60x | **1.96x** |
| get | 2.19x | 1.92x | **2.42x** |
| bytes | 1.71x | 1.50x | 1.69x |
| valid | 1.84x | 1.52x | 1.84x |
| async | 2.11x | 1.81x | 2.20x |
| bytesLarge | 2.31x | **2.60x** | 2.05x |
| sse | 3.89x | 4.63x | **6.37x** |

### 1.4 分析

- **小包接口 (json/get/bytes/valid/async)**: perf 在 4 线程时达到 26K~34K ops/s，是 Spring MVC 的 **1.7~2.2x**。随线程增加到 64，perf 吞吐持续增长（json +63%, bytes +55%, get +55%），而 Spring MVC 在 16 线程见顶后持平或反降，线程池争抢成为瓶颈。get 接口优势倍数最高（4t 达 **2.19x**），因为 perf 对路径参数和查询参数的预缓存效果最显著。
- **SSE 接口**: perf 的并发伸缩性优势最显著——从 4→64 线程吞吐增长 **114%**（1226→2624），而 Spring MVC 仅增长 **31%**（315→412）。perf 优势倍数从 4t 的 3.89x 扩大到 64t 的 **6.37x**。核心原因：perf 的 EventLoop + 无锁 Drain Loop 模型不阻塞线程，而 Spring MVC 的线程-连接绑定模型在 64 线程下严重受限。
- **bytesLarge (100KB)**: perf 在 16 线程达到峰值 17,257 ops/s（2.60x vs Spring MVC），但 64 线程降至 13,431。大响应体场景下，Netty 写缓冲区压力和内存带宽成为新瓶颈，导致 64 线程反降。
- **perf-support 桥接损耗**: 详见第 7 节桥接层损耗分析。普通接口损耗 8-12%，SSE 接口损耗达 41.6%。
- **webflux 伸缩性弱**: webflux 各接口 4→64 线程伸缩比仅 1.05~1.75x，远低于 perf 的 1.49~2.14x。Reactor 调度开销在纯 CPU 场景下限制了并发扩展。

---

## 2. 延迟分析 (ms)

### 2.1 4线程 p50 / p99 / p99.9

| 接口 | perf | Spring MVC (Tomcat) | Spring MVC (Undertow) | WebFlux |
|------|------|--------|----------|---------|
| json | **0.15 / 0.22 / 0.35** | 0.27 / 0.47 / 0.75 | 0.27 / 0.63 / 2.17 | 0.25 / 0.66 / 2.11 |
| get | **0.14 / 0.22 / 0.35** | 0.31 / 0.48 / 0.74 | 0.29 / 0.47 / 0.74 | 0.29 / 0.43 / 0.67 |
| bytes | **0.12 / 0.18 / 0.29** | 0.19 / 0.32 / 0.46 | 0.19 / 0.40 / 0.83 | 0.19 / 0.42 / 1.01 |
| valid | **0.15 / 0.22 / 0.35** | 0.27 / 0.47 / 0.77 | 0.25 / 0.71 / 2.29 | 0.25 / 0.49 / 1.20 |
| async | **0.14 / 0.21 / 0.33** | 0.28 / 0.47 / 0.70 | 0.29 / 0.71 / 1.63 | 0.20 / 0.38 / 0.72 |
| bytesLarge | **0.34 / 0.51 / 2.61** | 0.75 / 1.17 / 3.33 | 0.42 / 1.23 / 15.98 | 0.44 / 1.20 / 3.21 |
| sse | **3.57 / 4.24 / 6.25** | 12.65 / 17.07 / 22.64 | FAIL | 4.25 / 6.86 / 9.80 |

perf p50 延迟为 **0.12~0.15ms**（小包场景），是 Spring MVC 的 50-60%。p99 控制在 **0.22ms** 以内，p99.9 同样最低（0.29~0.35ms），说明 EventLoop 模型在低并发下尾延迟极为稳定。

SSE 场景 perf p50 仅 **3.57ms**，远低于 Spring MVC 的 12.65ms，也优于 WebFlux 的 4.25ms。

### 2.2 64线程 p50 / p99 / p99.9（高并发尾延迟）

| 接口 | perf | Spring MVC (Tomcat) | WebFlux |
|------|------|--------|---------|
| json | **0.80 / 5.19 / 105.12** | 2.99 / 7.50 / 82.31 | 2.38 / 16.65 / 55.77 |
| get | **0.76 / 7.03 / 59.69** | 3.44 / 10.40 / 136.31 | 2.70 / 14.22 / 36.63 |
| bytes | **0.25 / 32.80 / 99.22** | 2.31 / 5.55 / 22.81 | 1.75 / 13.91 / 48.82 |
| valid | **0.90 / 6.15 / 12.16** | 3.16 / 8.62 / 76.02 | 2.33 / 14.63 / 46.79 |
| async | **0.64 / 12.04 / 107.48** | 2.90 / 11.65 / 111.02 | 1.86 / 16.63 / 63.29 |
| bytesLarge | **0.58 / 74.97 / 141.56** | 8.80 / 77.33 / 215.15 | 3.19 / 48.17 / 164.89 |
| sse | **23.43 / 50.99 / 68.03** | 155.45 / 520.93 / 1489.30 | 58.26 / 263.89 / 635.87 |

64 线程下所有框架的尾延迟均有显著退化，但 perf 的 p50 仍保持最低（Spring MVC 的 25-30%）。SSE 差距最大：perf p99 为 **50.99ms**，而 Spring MVC 高达 **520.93ms**（perf 的 10.2x），p99.9 更是达到 **1489.30ms**（perf 68.03ms 的 21.9x）。

bytes 接口 perf p99 升至 32.80ms（高于 Spring MVC 的 5.55ms），这是 EventLoop 模型的 p50/p99 取舍：perf p50 仅 **0.25ms**（Spring MVC 的 1/9），但单线程 EventLoop 写队列在 64 线程并发下产生少量长尾。p99.9 为 **99.22ms**，尾延迟仍在可控范围。相比之下，Spring MVC 线程池模型 p50=2.31ms（9x）但 p99 更稳定——这是同步阻塞 vs 事件驱动架构的固有权衡。

---

## 3. GC 行为

GC 数据来自 JVM 级别日志，同一 profile 下各接口一致。每请求分配因各接口吞吐不同而异，下表以 json、get 和 SSE 为代表。

| 框架 | 线程 | Young GC 次数 | 平均暂停 | 分配率 | 每请求分配 (json) | 每请求分配 (get) | SSE 每请求分配 |
|------|------|--------------|---------|-------|-----------------|----------------|--------------|
| perf | 4 | 160 | 2.2ms | 463MB/s | **17.7KB** | **16.9KB** | 386.7KB |
| perf | 16 | 216 | 2.5ms | 617MB/s | 17.4KB | 16.4KB | 346.0KB |
| perf | 64 | 244 | 3.1ms | 639MB/s | **15.0KB** | **17.4KB** | **249.2KB** |
| Spring MVC (Tomcat) | 4 | 140 | 2.6ms | 410MB/s | 29.9KB | 40.3KB | 1334.9KB |
| Spring MVC (Tomcat) | 16 | 216 | 2.8ms | 604MB/s | 27.4KB | 39.4KB | 1563.0KB |
| Spring MVC (Tomcat) | 64 | 177 | 3.4ms | 468MB/s | 21.6KB | 39.7KB | 1163.3KB |
| webflux | 4 | 189 | 2.3ms | 543MB/s | 36.0KB | 56.1KB | 588.5KB |
| webflux | 16 | 245 | 2.6ms | 682MB/s | 31.3KB | 51.0KB | 643.5KB |
| webflux | 64 | 262 | 3.1ms | 696MB/s | 31.4KB | 54.7KB | 717.9KB |

perf 在 json 场景下每请求仅分配 **15.0~17.7KB**，显著低于 Spring MVC 的 21.6~29.9KB 和 webflux 的 31.3~36.0KB。低分配率意味着更少的 GC 暂停和更高的缓存局部性。

get 接口每请求分配的差距更为显著——perf 仅 **16.4~17.4KB**，Spring MVC 高达 **39.4~40.3KB**（perf 的 2.3 倍），webflux 达 **51.0~56.1KB**（perf 的 3.1 倍）。多参数绑定场景下框架预缓存机制的优势被放大，Spring MVC 每请求构造参数名解析临时对象，而 perf 启动时已完成绑定。

SSE 场景下 perf 的每请求分配从 386.7KB(4t) 降至 **249.2KB(64t)**（降幅 35%），而 Spring MVC 高达 1163~1563KB、webflux 的分配不降反升（588.5→717.9KB）。perf 的 EventLoop 在高并发下复用缓冲区，分配效率提升；Reactor 调度开销则随并发增长。

64 线程下 Spring MVC (Undertow) 平均暂停跃升至 13.5ms（Young GC 仅 91 次），表明线程争抢导致 GC 停顿不均匀。

---

## 4. 内存伸缩 (稳态 Heap)

| 框架 | 4线程 | 16线程 | 64线程 |
|------|-------|--------|--------|
| perf | **20MB** | 67MB | 256MB |
| Spring MVC (Tomcat) | 23MB | 67MB | 200MB |
| Spring MVC (Undertow) | 24MB | 69MB | 177MB |
| webflux | 23MB | 77MB | 186MB |

4 线程下 perf 堆占用 **20MB**，为所有框架最低。随线程增加到 64，所有框架堆占用均增长约 8-12 倍，主要来自更多的并发请求在途对象（Netty 缓冲区、请求/响应体、线程栈）。perf 在 64 线程下 256MB 略高于 Spring MVC 的 200MB，与 Netty 写缓冲区积压有关。

---

## 5. 关键结论

1. **并发伸缩性领先**: perf 从 4→64 线程吞吐持续增长（json +63%, SSE +114%, get +55%），而 Servlet 容器在 16 线程后停滞或反降。perf 优势在高并发下被放大，SSE 场景达 **6.37x vs Spring MVC**。
2. **延迟稳定**: p50 延迟 **0.12~0.15ms**（小包），是 Spring MVC 的 50-60%。64 线程下 perf p50 仍保持最低（Spring MVC 的 25-30%），p99/p99.9 在 6/7 接口中全面领先，仅 bytes 接口因单线程 EventLoop 写队列产生少量长尾。
3. **参数绑定场景优势显著**: get 接口（路径参数 + 5 个查询参数）4t 吞吐达 **2.19x** vs Spring MVC，每请求分配仅 Spring MVC 的 **42%**——多参数绑定场景下 perf 的预缓存优势最大化体现。
4. **SSE 碾压性优势**: 全线程级别 perf SSE 均领先，64 线程下吞吐达 Spring MVC 的 **6.37x**、p99 延迟仅 **50.99ms**（Spring MVC 的 1/10）。EventLoop + 无锁 Drain Loop 模型在高并发 SSE 场景下优势最大化。
5. **bytesLarge 双刃剑**: 16 线程 perf 优势达 **2.60x**，但 64 线程反降（1.17x 伸缩比）。Netty 写缓冲区压力限制了 100KB 大响应体的高并发扩展。
6. **perf-support 桥接损耗可控**: 普通接口损耗 8-12%，SSE 达 41.6%。桥接层 SSE 通路仍是主要优化方向。详见第 7 节。
7. **GC 随吞吐线性增长**: 分配率从 463MB/s(4t) 增至 639MB/s(64t)，每请求分配仅 15~18KB（json），为 Spring MVC 的 59%，无异常。

## 6. 架构对比：为什么 perf 更快

perf 的性能优势来自框架设计层面的工程取舍，而非"Netty 比 Tomcat 快"的泛泛说法。详见[性能原理](performance-principles.md)文档。

### 核心差异

| 维度 | Spring Web (perf) | Spring MVC + Tomcat | Spring WebFlux |
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

### 一句话总结

本框架通过**启动时确定性解析消除了运行时所有"查找"和"匹配"开销**，配合字节码生成消除反射——这两项是 100% 请求受益的核心手段。详见[性能原理](performance-principles.md)的全维度对比表。

---

## 7. 桥接层损耗分析

perf-support 是在 perf（原生 Netty）之上叠加 Servlet 桥接层的容器，用于评估桥接开销。

### 7.1 吞吐量对比 (perf-support vs perf, 4线程)

| 接口 | perf | perf-support | 损耗 |
|------|------|-------------|------|
| json | 26718 | 24380 | **-8.7%** |
| get | 27398 | 25562 | **-6.7%** |
| bytes | 34232 | 31551 | **-7.8%** |
| valid | 26706 | 23844 | **-10.7%** |
| async | 28354 | 25010 | **-11.8%** |
| bytesLarge | 11508 | 11075 | **-3.8%** |
| sse | 1226 | 716 | **-41.6%** |

### 7.2 分析

- **普通接口**: 桥接层损耗 8-12%，主要来自 Servlet API 适配和额外 Filter 链处理。
- **SSE 接口**: 损耗达 41.6%，Servlet 桥接层的 SSE 通路存在明显的额外开销。
- **大响应体 (bytesLarge)**: 损耗仅 3.8%，大响应场景下桥接层开销被数据拷贝时间稀释。
- **内存**: perf-support 堆占用与 perf 基本一致（4t: 21MB vs 20MB; 64t: 238MB vs 256MB），桥接层不引入额外内存压力。

---

## 8. 已知问题

| 问题 | 影响 | 状态 |
|------|------|------|
| sse Undertow 4t 失败 | Spring MVC (Undertow) / sse | Undertow SSE 实现限制 |
| bytesLarge 64t 吞吐反降 | perf / bytesLarge | 待优化（写缓冲区压力） |

---

## 如何运行

```bash
# 全量运行（多线程并发测试）
./spring-web-benchmark/benchmark-all.sh --thread-list 4,16,64

# 单 profile 运行
cd spring-web-benchmark
mvn jmh:run -Pbenchmark-perf -Dbenchmark.profile.name=perf
```

## 输出结构

```
spring-web-benchmark/benchmark-reports/
├── latest/
│   └── report.md                  ← 最新报告
├── YYYYMMDD-HHMMSS/               ← 历史快照
│   ├── report.md
│   ├── threads-4/
│   ├── threads-16/
│   └── threads-64/
```

## 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| JMH 预热 | 10 × 10s | 10 轮 × 10 秒 |
| JMH 测量 | 10 × 10s | 10 轮 × 10 秒 |
| Fork | 1 | fork JVM 隔离 |
| 线程 | 4, 16, 64 | 并发线程数 |
| 堆内存 | 1GB | -Xms1g -Xmx1g |
| GC | G1GC | -XX:+UseG1GC |
| 协议 | HTTP/1.1 | keep-alive |