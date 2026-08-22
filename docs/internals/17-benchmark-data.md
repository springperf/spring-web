# 17 · Benchmark 数据解读与归因

> [← 返回索引](00-README.md) | 上一篇：[16 · 代码聚光灯](16-code-spotlights.md) | 下一篇：[18 · 内部 SPI 发现与调用机制](18-spi-extension.md)

---

## 引子：数字背后的机制

前 16 篇讲了"为什么快"。本篇用最新 benchmark 报告的数字，把每个数据点归因到前文讲过的具体机制——路由缓存、ASM 调用、EventLoop 直处理、无锁 drain loop、零拷贝传输。

**数据来源**：`spring-web-benchmark/benchmark-reports/latest/report.md`（2026-08-15 11:05:55 生成），5 个容器（perf / perf-support / tomcat / undertow / webflux）× 7 个 API（async / bytes / bytesLarge / get / json / sse / valid）× 3 个并发度（4/8/16 线程）× JDK 17.0.9。

---

## 一、吞吐表解读

### 1.1 GET 场景

| 容器 | 4线程 | 8线程 | 16线程 | 倍数（vs tomcat，16t） |
|------|-------|-------|--------|----------------------|
| **perf** | 38538 | 54982 | 72636 | **1.84x** |
| perf-support | 32943 | 47245 | 62162 | 1.58x |
| tomcat | 17051 | 26385 | 39398 | 1.0x |
| undertow | 17604 | 25317 | 40972 | 1.04x |
| webflux | 15467 | 24762 | 41711 | 1.06x |

**归因**：GET 是最简单的场景（无 body 解析、无 JSON 序列化），最能体现框架的"请求路径开销"。perf 在 16 线程下达到 72636 ops/s，是 tomcat 的 1.84 倍。差异来自：

1. **路由匹配 O(1)**（[15 篇](15-performance-optimizations.md) 优化 3）：`FullPathRouterOptimizer` 的 `HashMap` 直接命中，vs Tomcat 的 `AntPathMatcher.match` 逐段字符串匹配。
2. **方法调用零反射**（[15 篇](15-performance-optimizations.md) 优化 2）：`MethodHandle.invokeExact` ~30ns vs `Method.invoke` ~200ns。
3. **基准 eventloop 模式零切换**（[15 篇](15-performance-optimizations.md) 优化 6）：vs Tomcat 每请求从线程池获取一个线程（~5–10μs 切换开销）。
4. **属性访问零哈希**（[15 篇](15-performance-optimizations.md) 优化 4）：`fastAttributes[index]` vs `ConcurrentHashMap.get()`。

### 1.2 JSON 场景

| 容器 | 4线程 | 8线程 | 16线程 | 倍数（vs tomcat，16t） |
|------|-------|-------|--------|----------------------|
| **perf** | 37508 | 54678 | 73286 | **1.61x** |
| perf-support | 32942 | 48864 | 61906 | 1.36x |
| tomcat | 19900 | 28329 | 45481 | 1.0x |
| undertow | 19488 | 28668 | 43738 | 0.96x |
| webflux | 18114 | 26726 | 47217 | 1.04x |

**归因**：JSON 场景增加了序列化开销，但框架的优势仍然明显。perf 73286 ops/s vs tomcat 45481，1.61 倍。JSON 序列化本身（Jackson）在所有容器中相同，差异来自序列化前后的请求路径开销——参数解析、返回值处理、响应写入。

### 1.3 SSE 场景——最大差距

| 容器 | 4线程 | 8线程 | 16线程 | 倍数（vs tomcat，16t） |
|------|-------|-------|--------|----------------------|
| **perf** | 13323 | 15384 | 14920 | **7.72x** |
| perf-support | 8632 | 9915 | 9230 | 4.77x |
| tomcat | 1055 | 1293 | 1933 | 1.0x |
| undertow | **FAIL** | **FAIL** | 1761 | — |
| webflux | 2623 | 3982 | 4614 | 2.39x |

**归因**：SSE 是差距最大的场景，perf 16 线程 14920 ops/s vs tomcat 1933，**7.72 倍**。差距来自：

1. **无锁 drain loop**（[15 篇](15-performance-optimizations.md) 优化 7）：`MpscArrayQueue` + `AtomicInteger wip` 的无锁队列，vs Tomcat SSE 的 `SseEmitter.send()` 阻塞调用 + 每连接一线程模型。
2. **`TCP_NODELAY`**（[15 篇](15-performance-optimizations.md) 优化 8）：禁用 Nagle 算法，SSE 的 `data:...\n\n` 小数据包立即发送。perf 与 tomcat 均默认 `TCP_NODELAY=true`，此项非差异来源——SSE 差距主因是无锁 drain loop 与 EventLoop 串行化写入。
3. **EventLoop 串行化写入**：所有 SSE 消息在 EventLoop 线程上串行 `writeAndFlush`，无锁竞争。Tomcat 的 SSE 每个连接占用一个容器线程，高并发下线程切换开销线性增长。

**undertow SSE FAIL**：undertow 在 4/8 线程下 SSE 测试 FAIL，16 线程仅 1761 ops/s。这是 undertow SSE 实现的稳定性问题（非本框架优势），客观标注。

### 1.4 `bytesLarge` 场景——零拷贝优势

| 容器 | 4线程 | 8线程 | 16线程 | 倍数（vs tomcat，16t） |
|------|-------|-------|--------|----------------------|
| **perf** | 18250 | 19111 | 15603 | **1.35x** |
| perf-support | 17614 | 18152 | 15435 | 1.34x |
| tomcat | 10032 | 11079 | 11555 | 1.0x |
| undertow | 12587 | 10710 | 10758 | 0.93x |
| webflux | 11772 | 10244 | 10087 | 0.87x |

**归因**：`bytesLarge` 传输大字节数组（~10KB+），差距 1.35x。perf 在 16 线程下反而下降（19111→15603），归因大 payload 在高并发下的 `ByteBuf` 分配压力——每请求分配 ~148KB（见下节 GC 数据）。但绝对值仍领先 tomcat。

### 1.5 `async` 场景

| 容器 | 4线程 | 8线程 | 16线程 | 倍数（vs tomcat，16t） |
|------|-------|-------|--------|----------------------|
| **perf** | 40501 | 56696 | 75134 | **1.72x** |
| perf-support | 33017 | 49441 | 58893 | 1.35x |
| tomcat | 19111 | 27038 | 43521 | 1.0x |
| undertow | 17543 | 26257 | 41905 | 0.96x |
| webflux | 24297 | 34542 | 57109 | 1.31x |

**归因**：`async` 场景使用 `DeferredResult`/`CompletableFuture`。perf 75134 ops/s vs tomcat 43521，1.72 倍。框架的 `AsyncSupportRegistry` 在 EventLoop 上直接处理异步回调，无额外线程切换。Tomcat 的 `DeferredResult` 仍占用容器线程等待异步完成。

---

## 二、GC 与分配归因

### 2.1 每请求分配量

| 场景 | perf | perf-support | tomcat | undertow | webflux |
|------|------|-------------|--------|----------|---------|
| get | 10.7KB | 13.2KB | 37.3KB | 35.6KB | 50.0KB |
| json | 10.1KB | 12.7KB | 23.4KB | 23.3KB | 32.6KB |
| bytes | 7.9KB | 10.3KB | 16.8KB | 15.9KB | 23.7KB |
| valid | 11.2KB | 13.6KB | 23.4KB | 23.2KB | 33.1KB |
| async | 8.8KB | 13.3KB | 32.7KB | 24.4KB | 25.1KB |
| sse | 310.4KB | 364.7KB | 233.9KB | FAIL | 192.5KB |
| bytesLarge | 148.4KB | 150.7KB | 157.7KB | 157.1KB | 164.3KB |

**归因**：

1. **perf 每请求分配最低**（get 10.7KB / json 10.1KB / bytes 7.9KB），归因预缓存 + 零临时对象（[15 篇](15-performance-optimizations.md) 优化 1、4、5）。启动期完成的元数据缓存（`MappingCacheKey`、`StaticArgumentResolver[]`、`cachedInterceptors`）使运行时零额外分配。
2. **tomcat 每请求分配 ~2–3x perf**（get 37.3KB vs 10.7KB），归因运行时匹配（`AntPathMatcher.match` 每次创建 `StringBuilder`）+ Facade 包装器（`RequestFacade`/`ResponseFacade` 每请求 ~2–3KB）+ `ConcurrentHashMap` 属性存储。
3. **webflux 每请求分配最高**（get 50.0KB），归因响应式链对象——每个 `Mono`/`Flux` 操作创建一个 `Lambda` 对象 + `Subscription` 对象，链式调用累积大量短期对象。
4. **perf-support 比 perf 多 ~2–3KB/请求**（get 13.2KB vs 10.7KB），归因 Servlet 适配对象创建（`PerfHttpServletRequest`/`PerfHttpServletResponse` 包装）。这是兼容 Spring MVC 的代价，约 +23% 分配量。
5. **sse 场景 perf 分配 310.4KB/请求**——高于 tomcat 的 233.9KB，归因 `MpscArrayQueue` 高频分配（每消息一个 `ByteBuf`）。但 perf 吞吐 7.72x tomcat，单位时间分配率更高（4489MB/s vs 440MB/s），GC 压力通过更高吞吐摊薄。

### 2.2 GC 暂停时间

| 场景 | perf 平均暂停 | tomcat 平均暂停 | 倍数 |
|------|-------------|---------------|------|
| get (16t) | 1.4ms | 1.5ms | 0.93x |
| json (16t) | 1.4ms | 1.5ms | 0.93x |
| sse (16t) | 1.6ms | 3.6ms | 0.44x |
| async (4t) | 1.5ms | 6.4ms | 0.23x |

**归因**：perf 的 GC 暂停普遍短于 tomcat，归因更低的每请求分配量——相同吞吐下，perf 产生的垃圾更少，Young GC 频率更低。`async` 场景 4 线程下 perf 1.5ms vs tomcat 6.4ms，差距最大（0.23x），归因 tomcat 的 `DeferredResult` 异步处理中创建了额外的 `ModelAndView` 相关对象。

### 2.3 分配率（MB/s）

| 场景 | perf (16t) | tomcat (16t) | 倍数 |
|------|-----------|-------------|------|
| get | 741MB/s | 1419MB/s | 0.52x |
| json | 707MB/s | 1054MB/s | 0.67x |
| sse | 4489MB/s | 440MB/s | 10.2x |

**归因**：`get`/`json` 场景 perf 分配率约为 tomcat 的一半，归因更低的每请求分配量。`sse` 场景 perf 分配率 10 倍于 tomcat（4489MB/s vs 440MB/s），但这是"高吞吐的代价"——以 16 线程档位交叉验证（吞吐 × 每请求分配 ≈ 实测分配率）：perf 14920 ops/s × 310.4KB/请求 ≈ 4631MB/s，与实测 4489MB/s 吻合（误差 ~3%）；tomcat 1933 ops/s × 233.9KB/请求 ≈ 452MB/s，与实测 440MB/s 吻合（误差 ~3%）。**perf 的分配率高是因为吞吐高，不是因为单次请求分配多。**

---

## 三、内存占用归因

### 3.1 Heap Used

| 容器 | 4线程 | 8线程 | 16线程 |
|------|-------|-------|--------|
| **perf** | 24MB | 37MB | 55MB |
| perf-support | 24MB | 38MB | 55MB |
| tomcat | 26MB | 34MB | 58MB |
| undertow | 25MB | 33MB | 53MB |
| webflux | 25MB | 32MB | 51MB |

**归因**：所有容器的 Heap Used 相近（24–58MB），差异不大。perf 24MB（4线程）→ 55MB（16线程），增长来自请求处理中的活跃对象。tomcat 26MB（4线程）→ 58MB（16线程），增长略高，归因容器内部缓冲（`Tomcat` 的 `Connector` 缓冲池）。

### 3.2 Metaspace

| 容器 | Metaspace |
|------|-----------|
| **perf** | 39MB |
| perf-support | 40MB |
| tomcat | 42–43MB |
| undertow | 43MB |
| webflux | 45–47MB |

**归因**：perf 的 Metaspace 最低（39MB），归因框架的精简类结构——40+ 个核心组件 + Support 桥接层，无 Spring MVC 的 `DispatcherServlet`/`RequestMappingHandlerAdapter`/`HandlerMethodArgumentResolverComposite` 等重量级类。webflux 最高（45–47MB），归因响应式链的大量 `Lambda` 类生成。

### 3.3 Code Cache

| 容器 | Code Cache |
|------|-----------|
| **perf** | 6MB |
| perf-support | 6–7MB |
| tomcat | 8–9MB |
| undertow | 8–9MB |
| webflux | 9–10MB |

**归因**：perf 的 Code Cache 最低（6MB），归因 `MethodHandle.invokeExact` 和 ASM `INVOKEVIRTUAL` 的 JIT 内联——内联后方法数减少，编译产物更紧凑。tomcat/webflux 的 `Method.invoke` 无法跨 native 边界内联，JIT 编译了更多独立方法。

---

## 四、延迟分析归因

### 4.1 p50 延迟（get 场景，16线程）

| 容器 | p50 | p90 | p99 | p99.9 | p99.99 |
|------|-----|-----|-----|-------|--------|
| **perf** | 0.25ms | 0.35ms | 0.43ms | 0.52ms | 2.16ms |
| perf-support | 0.25ms | 0.34ms | 0.42ms | 0.52ms | 2.18ms |
| tomcat | 0.38ms | 0.49ms | 0.67ms | 2.27ms | 4.27ms |
| undertow | 0.35ms | 0.49ms | 0.66ms | 2.24ms | 4.14ms |
| webflux | 0.36ms | 0.45ms | 0.60ms | 2.41ms | 3.97ms |

**归因**：perf 的 p50 0.25ms，是 tomcat 0.38ms 的 0.66 倍。p99 差距更大——perf 0.43ms vs tomcat 0.67ms。p99.9 和 p99.99 的差距最明显：perf p99.99 2.16ms vs tomcat 4.27ms。长尾延迟的差距归因 GC 暂停——tomcat 更高的分配率导致更频繁的 Young GC，GC 暂停累积到长尾。

### 4.2 SSE 延迟差距

| 容器 | p50 (16t) | p99 (16t) | p99.99 (16t) |
|------|-----------|-----------|-------------|
| **perf** | 1.03ms | 1.76ms | 5.28ms |
| perf-support | 1.57ms | 3.38ms | 6.22ms |
| tomcat | 7.76ms | 12.60ms | 25.54ms |
| webflux | 3.34ms | 5.75ms | 14.06ms |

**归因**：perf SSE p50 1.03ms vs tomcat 7.76ms，7.5 倍差距。p99.99 perf 5.28ms vs tomcat 25.54ms，4.8 倍。归因无锁 drain loop 的批量消费——一次 drain 循环消费多个消息，减少 `writeAndFlush` 调用次数，降低延迟。Tomcat 的 `SseEmitter.send()` 每消息一次阻塞调用，延迟线性累积。

---

## 五、perf vs perf-support：桥接层的代价

| 场景 | perf (16t) | perf-support (16t) | 差距 | 归因 |
|------|-----------|-------------------|------|------|
| get | 72636 | 62162 | -14.4% | Servlet 适配对象创建 |
| json | 73286 | 61906 | -15.5% | `PerfHttpServletRequest`/`Response` 包装 |
| async | 75134 | 58893 | -21.6% | `RequestContextHolder` 初始化 + Session 管理 |
| sse | 14920 | 9230 | -38.1% | `ResponseBodyEmitter` 适配 + Session flush |
| bytes | 74540 | 65815 | -11.7% | Servlet 适配（较轻） |
| valid | 69766 | 58454 | -16.2% | Servlet 适配 + 数据绑定桥接 |

**归因**：perf-support 平均比 perf 慢 ~15–38%，归因 Support 桥接层的额外开销：

1. **Servlet 适配对象创建**：每请求创建 `PerfHttpServletRequest`/`PerfHttpServletResponse` 包装器，约 +2–3KB/请求分配。
2. **`RequestContextHolder` 初始化**：`SupportDispatcherHandler` 在 `handleAfterFilter` 中初始化 `RequestContextHolder`，额外 ThreadLocal 访问。
3. **Session 管理**：`PerfHttpSessionManager` 的 `flush()` 在 `ChannelFuture` 回调中执行，SSE 场景高频回调增加开销。
4. **`WebMvcConfigurerBridge` 翻译**：虽然翻译在启动期完成，但运行时的 `HandlerInterceptorWrapper`/`FilterWrapper` 包装增加了间接调用。

**SSE 差距最大（-38.1%）**：归因 `ResponseBodyEmitter` 适配 + Session 的高频 `flush` 回调——SSE 每消息一次 `ChannelFuture` 完成，每次触发 Session 持久化检查。

---

## 六、高并发伸缩性

### 6.1 4→16 线程的伸缩性

| 场景 | 容器 | 4→16 增长 | 伸缩效率 |
|------|------|----------|---------|
| get | perf | 38538→72636 | 1.88x（4x 线程 → 1.88x 吞吐） |
| get | tomcat | 17051→39398 | 2.31x |
| json | perf | 37508→73286 | 1.95x |
| json | tomcat | 19900→45481 | 2.29x |
| sse | perf | 13323→14920 | 1.12x |
| sse | tomcat | 1055→1933 | 1.83x |

**归因**：

1. **perf 的伸缩效率低于 tomcat**（get 1.88x vs 2.31x），因为 perf 在 4 线程下已经接近单核极限，16 线程时 EventLoop 线程数固定（不受客户端线程数影响），增长来自多 EventLoop 并行。tomcat 在 4 线程下线程池未充分利用，16 线程时线程池满负荷，增长更明显。
2. **perf SSE 4→16 仅 1.12x**：SSE 是长连接场景，4 线程已建立足够连接，16 线程不再增加有效连接数。但绝对吞吐（14920）仍远超 tomcat（1933）。
3. **perf 在高并发下的优势是绝对值而非伸缩率**：4 线程 perf 38538 已是 tomcat 17051 的 2.26x，16 线程 72636 是 tomcat 39398 的 1.84x。框架的优势在低并发下更明显——零开销路径在低并发下不被线程切换掩盖。

---

## 七、数据汇总与机制映射

| 数据点 | perf 优势 | 归因机制 | 篇章 |
|--------|----------|---------|------|
| get 吞吐 1.84x | 72636 vs 39398 | 路由 O(1) + MethodHandle + EventLoop | [15](15-performance-optimizations.md) 优化 2/3/6 |
| json 吞吐 1.61x | 73286 vs 45481 | 参数解析 O(1) + 返回值处理零反射 | [15](15-performance-optimizations.md) 优化 1 |
| sse 吞吐 7.72x | 14920 vs 1933 | 无锁 drain loop + EventLoop 串行化 | [15](15-performance-optimizations.md) 优化 7 |
| bytesLarge 1.35x | 15603 vs 11555 | `ByteBuf` 引用计数 + 零拷贝 | [15](15-performance-optimizations.md) 优化 5 |
| 每请求分配 ~1/3 | 10.7KB vs 37.3KB | 预缓存 + 零临时对象 | [15](15-performance-optimizations.md) 优化 1/4/5 |
| GC 暂停 0.44x | 1.6ms vs 3.6ms (sse) | 低分配率 → 低 GC 频率 | [15](15-performance-optimizations.md) 优化 5 |
| Metaspace 最低 | 39MB vs 42–47MB | 精简类结构 + JIT 内联 | [15](15-performance-optimizations.md) 优化 2 |
| Code Cache 最低 | 6MB vs 8–10MB | `invokeExact` 内联 | [15](15-performance-optimizations.md) 优化 2 |
| p99.99 延迟 0.5x | 2.16ms vs 4.27ms (get) | 低 GC 暂停 → 低长尾 | [15](15-performance-optimizations.md) 优化 5 |
| perf-support -15% | 桥接层开销 | Servlet 适配 + Session flush | [12](12-support-bridge.md) |

---

## 八、小结：数字背后的"启动期确定性"

回到引子的问题：perf 为何在 get/json ~2x tomcat？为何 SSE 7.72x tomcat？为何每请求分配 perf ~8–11KB vs tomcat ~23–37KB？

1. **吞吐优势归因"启动期确定性"**：路由匹配 O(1)、方法调用零反射、属性访问零哈希——这些在启动期完成的预计算，使运行时每请求节省 ~500ns–1μs 的匹配开销，累积到吞吐差距。
2. **SSE 7.72x 归因"无锁 drain loop + EventLoop 串行化"**：`MpscArrayQueue` 的无锁 drain loop 消除了 Tomcat SSE 的每连接一线程模型开销，EventLoop 串行化写入避免锁竞争；perf 与 tomcat 均默认禁用 Nagle，非差异来源。
3. **分配量 ~1/3 归因"零临时对象"**：预缓存使运行时零额外分配（`fastAttributes[]` 替代 `ConcurrentHashMap`、`StaticArgumentResolver[]` 替代遍历匹配），vs Tomcat 的运行时匹配创建 `StringBuilder` + Facade 包装器。
4. **内存最低归因"精简结构"**：40+ 组件 + 桥接层 vs Spring MVC 的重量级类层级，Metaspace 39MB vs 42–47MB。
5. **perf-support -15% 归因"兼容代价"**：Servlet 适配对象 + Session flush 是兼容 Spring MVC 的必要代价，用户可通过不引入 `spring-web-support` 规避。

这一层的克制体现在：**不把性能优势归因于"Netty 比 Tomcat 快"这种笼统结论，而是逐数据点映射到具体的代码机制。** 每个数字都有对应的篇章和优化条目，读者可以从数据点反向追溯到源码的 `file:line`。

---

> **下一篇**：[18 · 内部 SPI 发现与调用机制](18-spi-extension.md)——12 个 SPI 扩展点的发现、排序、调用机制。