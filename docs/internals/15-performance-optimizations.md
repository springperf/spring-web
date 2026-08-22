# 15 · 八大性能优化代码级三元组

> [← 返回索引](00-README.md) | 上一篇：[14 · starter 自动配置](14-starter-autoconfig.md) | 下一篇：[16 · 代码聚光灯](16-code-spotlights.md)

---

## 引子：性能不是"优化"出来的，是"设计"出来的

前 11 篇（03–13）逐层拆解了框架的每个组件。本篇把 03–11 篇中涉及的性能机制，提取为 8 条独立优化，每条拆成"**手段（代码机制）/ 为什么快（数据结构归因）/ 对比谁（Spring MVC/WebFlux 同位实现）**"三元组。

与 `performance-principles.md` 的关系：该文档是性能**原则**（为什么），本篇是性能**代码实现**（怎么做），同位对比维度补完。

---

## 一、启动预缓存：零运行时匹配

### 手段

框架在启动期（`initComponentPhase1`/`Phase2`）完成所有元数据的预计算和缓存，运行时从 `Object[]` 数组或 `int[]` 索引直取。核心缓存机制：

| 缓存结构 | 存储位置 | 启动期计算 | 运行时访问 |
|----------|---------|-----------|-----------|
| `RequestAttribute` | `fastAttributes[]` 索引槽 | `PathMappingContext` 启动期声明属性键分配 index | `requestContext.getAttribute(key)` → `fastAttributes[idx]` |
| `cachedInterceptors` | `List<HandlerInterceptor>` | 首次请求 DCL 缓存 | 遍历列表，零匹配 |
| `cachedFilterChain` | `DefaultFilterChain` | 首次请求 DCL 缓存 | `filterIndex.getAndIncrement()` 直取 |
| `StaticArgumentResolver` | `StaticArgumentResolver[]` | 启动期预创建 | 按参数位置索引直取 |
| `MethodArgContext` | `MethodArgContext` | 启动期预计算 | 参数解析器读取缓存上下文 |

详见 [07 篇](07-argument-resolution.md) 的 `StaticArgumentResolverProvider` 机制、[10 篇](10-cross-cutting.md) 的 `cachedInterceptors`/`cachedFilterChain` 三段式缓存。

### 为什么快

- **零运行时匹配**：Spring MVC 的 `HandlerMethodArgumentResolverComposite` 在每次请求的 `resolveArgument` 中遍历所有解析器，调用 `supportsParameter` 逐一匹配，`synchronized` 保护内部缓存。框架的 `StaticArgumentResolver[]` 按参数位置索引，O(1) 直接命中。
- **零注解查找**：Spring MVC 在运行时通过 `getParameterAnnotation` 查找注解（`AnnotatedElementUtils` 的缓存虽有效，但仍有方法调用链开销）。框架在启动期一次性解析并缓存为 `StaticArgumentResolver`，运行时零注解查找。
- **零排序开销**：`@Order` 排序在启动期完成，运行时直接遍历有序列表。

### 对比 Spring MVC

| 维度 | 本框架 | Spring MVC |
|------|--------|-----------|
| 参数解析器匹配 | `StaticArgumentResolver[]` 按位置索引，O(1) | `HandlerMethodArgumentResolverComposite` 遍历 `supportsParameter`，O(n) + `synchronized` |
| 拦截器匹配 | `cachedInterceptors` 三段式预计算，运行时遍历 | `MappedInterceptor.getInterceptor` 每次请求 `matches` 路径匹配 |
| Filter 匹配 | `cachedFilterChain` 预计算，`unmatchedChain` 兜底 | `FilterChain.doFilter` 无缓存，每次 `Filter` 自行判断 |
| CORS 匹配 | `CorsConfigurationProvider` 缓存到 `PathMappingContext` | `AbstractHandlerMapping.getCorsConfiguration` 每次请求遍历 |
| 异常处理器匹配 | `ExceptionHandlerAdvice[]` 缓存，`MappingCacheKey` 直取 | `HandlerExceptionResolverComposite` 遍历 `resolveException` |

---

## 二、方法调用：ASM/MethodHandle vs Method.invoke

### 手段

`InvokableHandlerMethod` 在构造器中使用三路决策（[09 篇](09-invoker-bytecode.md) 二节）：

```java
// InvokableHandlerMethod.java
private Invoker initInvoker() {
    if (optimized && !IN_NATIVE_IMAGE) {
        return createFastInvoker();    // ① ASM 字节码 → INVOKEVIRTUAL
    }
    return createCommonInvoker();      // ② MethodHandle.invokeExact
}
```

| 路径 | 开销 | 实现 |
|------|------|------|
| ASM 字节码（`@Optimize`） | ~10ns | `FastInvokerGenerator` 生成 `INVOKEVIRTUAL` 字节码，`CHECKCAST` + `unbox` + `box` |
| `MethodHandle.invokeExact`（默认） | ~30ns | `MethodHandles.lookup().unreflect(method)` → `asSpreader(Object[].class, paramCount)` |
| `Method.invoke`（Spring MVC） | ~200ns | 每次 access check + 可变参数装箱 + JIT 无法内联的 native 边界 |

### 为什么快

- **`Method.invoke` 的 native 方法边界**阻止了 JIT 的跨方法内联调用链。`MethodHandle.invokeExact` 虽然也是虚调用，但 JIT 在热路径上可以将其内联为直接调用（`inline` + `intrinsic`）。
- **ASM 生成的 `INVOKEVIRTUAL`** 是标准的 Java 字节码指令，JIT 可以完全内联和优化，调用链与手写代码无异。
- **`MethodHandle.asSpreader`** 将 `Object[]` 展开为独立参数，`invokeExact` 不装箱、不类型检查、不 access check。

### 对比 Spring MVC

| 维度 | 本框架 | Spring MVC |
|------|--------|-----------|
| 默认调用方式 | `MethodHandle.invokeExact`（~30ns） | `Method.invoke`（~200ns） |
| 优化路径 | `@Optimize` → ASM `INVOKEVIRTUAL`（~10ns） | 无 |
| 调用器缓存 | 按方法缓存字节码类（`ConcurrentHashMap<Method, Class<?>>`） | 无缓存，每次 `Method.invoke` |
| 返回值处理 | `invoke` 后 inline `setResponseStatus` | `invoke` 后由 `ModelAndViewContainer` 处理 |

---

## 三、路由匹配：多级 RouterOptimizer vs AntPathMatcher

### 手段

`MappingRegistry` 在 `initComponentPhase3`（`MappingRegistry.java`）构建四类路由优化器链（[06 篇](06-routing-engine.md) 三节）：

```
RouterOptimizer 链:
  FullPathRouterOptimizer  → 全路径精确匹配（O(1) HashMap）
  PrefixRouterOptimizer    → 前缀匹配（HashMap）
  SuffixRouterOptimizer    → 后缀匹配（HashMap）
  LoopRouterOptimizer      → 遍历兜底（O(n)）
```

每个 `PathMappingContext` 启动期存 `pathRule` 字符串，优化器在 `support()` 中分段并分派到对应的路由层级。

### 为什么快

- **全路径精确匹配 O(1)**：`@GetMapping("/api/users/{id}")` 编译为全路径模式，`FullPathRouterOptimizer` 使用 `HashMap` 直接命中。
- **前缀匹配 HashMap**：`/api/**` 等前缀模式按选定前缀段拼接的字符串作 `HashMap` key（`PrefixPathRouterOptimizer`），`/api/users/{id}` 与 `/api/orders/{id}` 共享 `/api` 前缀命中同一 `Router`，O(1) 查找。
- **Spring MVC 的 `AntPathMatcher.match`** 每次请求逐段字符串匹配，包含通配符展开、正则匹配，时间复杂度 O(path segments × pattern length)。
- **`PathPattern`（Spring 5.3+）** 虽比 `AntPathMatcher` 快，但仍然是每次请求的匹配，无多级路由缓存。

### 对比 Spring MVC

| 维度 | 本框架 | Spring MVC |
|------|--------|-----------|
| 精确路径 | `HashMap.get()`，O(1) | `AntPathMatcher.match`，O(n) 或 `PathPattern.matches`，O(n) |
| 前缀路径 | 前缀 HashMap 命中 | `AntPathMatcher.match` 逐段匹配 |
| 通配符路径 | `LoopRouterOptimizer` 遍历 | `AntPathMatcher.match` 或 `PathPattern.matches` |
| 路由缓存 | 启动期构建优化器链 | 无路由缓存，每次请求重新匹配 |
| `PathPattern` 兼容 | 支持 `PathPatternRouteMatcher` 和 `AntPathMatcher` | 支持 `PathPattern`（Spring 5.3+）和 `AntPathMatcher` |

---

## 四、属性访问：`fastAttributes[]` vs `ConcurrentHashMap`

### 手段

`BaseWebServerHttpRequest` 用 `fastAttributes[]`（`Object[]` 数组）替代 `ConcurrentHashMap<String, Object>` 存储请求属性（[05 篇](05-request-pipeline.md) 四节）：

```java
// RequestAttribute.java
public class RequestAttribute<T> {
    private static final AtomicInteger SEQ = new AtomicInteger();
    public static <T> RequestAttribute<T> createAttribute(Class<T> type) {
        return new RequestAttribute<>(SEQ.getAndIncrement(), type);  // 启动期分配唯一 int 索引
    }
    public static int getMaxSize() { return SEQ.get() + 1; }          // 已注册属性数 → 数组长度
    public int getIndex() { return index; }
}

// BaseWebServerHttpRequest.java
public abstract class BaseWebServerHttpRequest implements ..., RequestContext {
    protected final Object[] fastAttributes = new Object[RequestAttribute.getMaxSize()];

    public <T> T getAttribute(RequestAttribute<T> key) {
        int idx = key.getIndex();
        return idx < fastAttributes.length ? (T) fastAttributes[idx]            // 命中数组
                                          : (T) attributes.get(FAST_ATTR_PREFIX + idx);  // 溢出兜底
    }
}
```

每个属性对应一个启动期分配的 `int` 索引（`RequestAttribute` 的 `getIndex()`），运行时通过索引直接读写 `Object[]` 数组。

### 为什么快

- **`ConcurrentHashMap.get()`** 包含：`hashCode()` 计算 → 桶定位 → 链表/红黑树遍历 → `equals()` 比较 → `volatile` 读。每次约 30–50ns。
- **`fastAttributes[index]`** 是 `aload` + `iaload` 两条字节码指令，约 2–3ns。10 倍差距。
- 零哈希冲突、零扩容、零并发控制（`RequestContext` 绑定到单请求，单线程访问）。

### 对比 Spring MVC

| 维度 | 本框架 | Spring MVC |
|------|--------|-----------|
| 属性存储 | `Object[]` 数组 + `int` 索引 | `ConcurrentHashMap<String, Object>` 或 `HashMap<String, Object>` |
| 访问方式 | `fastAttributes[index]`，O(1) 直接索引 | `map.get(key)`，hash + equals + volatile |
| 属性数量 | 启动期由已注册属性数决定（`RequestAttribute.getMaxSize()`） | 运行时动态增长 |
| 并发控制 | 无（单线程访问） | `ConcurrentHashMap` 分段锁 |
| 缓存键 | `RequestAttribute` 启动期分配 `int index` | `String` 键，运行时 `hashCode()` |

---

## 五、内存管理：`ByteBuf` acquire/release vs 容器自动回收

### 手段

框架的请求/响应对象（`NettyServerHttpRequest`/`NettyServerHttpResponse`）实现引用计数接口，通过 `acquire()`/`release()` 手动管理 Direct Memory 生命周期（[05 篇](05-request-pipeline.md) 三节）：

```java
// NettyServerHttpRequest.java
public void acquire() { content.retain(); }
public void release() { content.release(); }
```

`DispatcherHandler.handle()` 入口处 `acquire()`，`DispatcherHandler.handleAfterFilter()` 的 finally 中 `release()`。跨线程传递时（`@RunInPool`），EventLoop 线程 `acquire()`，业务线程 `release()`。

### 为什么快

- **Direct Memory 避免 GC 管理**：`ByteBuf` 使用堆外内存（Direct Buffer），不占用 JVM 堆，减少 GC 压力。手动引用计数避免了 GC 的不可预测性。
- **零拷贝**：`FileRegion` 和 `ChunkedWriteHandler` 实现零拷贝文件传输，数据从磁盘直接发送到网卡，不经过 JVM 堆。
- **Spring MVC 的容器自动回收**（Tomcat/Undertow）由容器管理请求/响应生命周期，但包含额外的包装器对象创建（`RequestFacade`/`ResponseFacade`），每请求约 2–3KB 额外分配。

### 对比 Spring MVC

| 维度 | 本框架 | Spring MVC（Tomcat） |
|------|--------|---------------------|
| 内存管理 | 手动 `acquire()`/`release()` 引用计数 | 容器自动回收 |
| 堆外内存 | 直接使用 `ByteBuf`（Direct/Heap 混合） | `NIO Buffer` + `ServletInputStream` 包装 |
| 零拷贝 | `FileRegion` + `DefaultFileRegion` | `sendfile`（Tomcat NIO） |
| 每请求分配 | ~8–11KB（预缓存 + 零临时对象） | ~23–37KB（运行时匹配 + Facade 包装） |
| 对象池 | `BatchEvent` 预分配（Disruptor RingBuffer） | 无（每次请求创建新对象） |

---

## 六、线程模型：default 业务池为基线，EventLoop 可选

### 手段

框架默认在 default 业务线程池处理请求；显式 `@RunInPool(EVENTLOOP)` 或配置 `pool.default-execute-mode=eventloop` 时才切回 EventLoop 直处理（[04 篇](04-request-pipeline.md) 二节，`DispatcherHandler.java`）：

```java
// DispatcherHandler.java
ExecutorService executor = bizPoolRegistry.determinePool(req, mappingResult);
if (executor != null) {                       // default 池（缺省）或 @RunInPool("name") 命名池
    req.acquire();
    executor.execute(() -> {                  // 切业务线程
        try { handleWithFilter(req, resp, mappingResult); }
        finally { req.release(); }
    });
} else {                                       // 无映射 / @RunInPool(EVENTLOOP) / =eventloop
    handleWithFilter(req, resp, mappingResult);  // EventLoop 直处理
}
```

`BizPoolRegistry` 的默认池配置为 `core=50, max=200, keepAlive=60s`，`@RunInPool` 可指定池名称。

### 为什么快

- **default 池兼容阻塞**：默认在业务线程池处理，阻塞型业务（JDBC、同步 RPC）不拖垮 EventLoop；池化复用线程，避免 Tomcat 式每请求新建/销毁线程。
- **eventloop 模式零切换（可选）**：切到 `eventloop` 模式后，单 EventLoop 线程处理多个连接，无线程切换开销，CPU 缓存亲和性高——这是基准测试所选模式。
- **Spring MVC（Tomcat）** 默认 `min-spare-threads=10`，`max-threads=200`（Spring Boot 默认），每请求从线程池获取一个线程，包含上下文切换（~5–10μs）和缓存污染；本框架 default 池同样有一次切换，但池配置 `core=50, max=200, keepAlive=60s` 针对高频短任务调优。
- **低并发优势更明显（eventloop 模式）**：基准 eventloop 模式下，perf 在低并发下零切换优势最显著——`json` 场景 4 线程 perf 37508 ops/s 是 tomcat 19900 的 1.88 倍，16 线程 73286 vs 45481 降至 1.61 倍（tomcat 线程池满负荷后差距收窄）。绝对吞吐随并发增长，但倍数在 16 线程档收窄。

### 对比 Spring MVC

| 维度 | 本框架 | Spring MVC（Tomcat） |
|------|--------|---------------------|
| 默认线程 | default 业务线程池（`core=50, max=200`） | 容器线程池（Tomcat `min-spare-threads`） |
| 线程切换 | 默认一次，`eventloop` 模式/`@RunInPool(EVENTLOOP)` 时零 | 每请求至少一次（线程池分配） |
| 异步支持 | `DeferredResult`/`Callable` 在 EventLoop 直接回调 | `Callable` 走 `TaskExecutor`，`DeferredResult` 仍阻塞容器线程 |
| 高并发伸缩 | 低并发倍数更高（4t 1.88x），16t 收窄至 1.61x | 线程池满负荷后差距收窄 |
| 线程池配置 | `core=50, max=200, keepAlive=60s`（默认池） | `min-spare-threads=10, max-threads=200`（Spring Boot 默认） |

---

## 七、无锁 Drain Loop vs 每连接一线程

### 手段

SSE 和流式输出使用 `MpscArrayQueue` + `AtomicInteger wip` 的 drain loop 模式（[11 篇](11-async-streaming.md) 四节）：

```java
// DefaultNettyStreamSender.drain()（入口在 scheduleDrain：wip.getAndIncrement()==0 才调用）
protected void drain() {
    if (!channel.isActive()) { queue.clear(); wip.set(0); ...; return; }  // 失活分支
    int missed = 1;
    for (;;) {
        while (channel.isWritable()) {                 // 背压：不可写则停止消费
            Object msg = queue.poll();
            if (msg == null) break;
            // emitter.encode(msg, batchOut) → flushContent(batchBuf) 批量编码写入
        }
        missed = wip.addAndGet(-missed);              // drain 期间新入队则继续
        if (missed == 0) break;
    }
    afterDrain();                                       // 残留再调度 / completed 收尾
}
```

`scheduleDrain()` 的 `wip.getAndIncrement() == 0` 入口保证同一时刻只有一个线程在执行 drain 循环；`afterDrain()` 的 `wip.compareAndSet(0, 1)` 在队列残留时抢占重新调度。生产者线程（EventLoop）和消费者线程（业务线程）通过 `MpscArrayQueue`（多生产者单消费者无锁队列）通信。

### 为什么快

- **无锁**：`MpscArrayQueue` 使用 CAS 入队，`wip` 使用 `AtomicInteger` 的 `getAndIncrement`/`addAndGet`，无 `synchronized`/`ReentrantLock`。
- **无等待**：`wip.getAndIncrement() == 0` 检查使 drain 循环在第一次调用时开始，最后一次调用时结束。无线程挂起/唤醒。
- **批量消费**：一次 drain 循环可以消费多个消息，减少 `writeAndFlush` 调用次数。
- **Netty `Channel` 的 `writeAndFlush`** 是异步操作，EventLoop 串行化执行，无锁竞争。

### 对比 Spring MVC

| 维度 | 本框架 | Spring MVC（SSE） |
|------|--------|------------------|
| 队列模式 | `MpscArrayQueue` 无锁 + `AtomicInteger wip` | `BlockingQueue` 或 `SynchronizedQueue` |
| 消费模式 | 主动 drain 循环 | `SseEmitter.send()` 阻塞调用 |
| 每连接线程 | 零（EventLoop 处理） | 一个容器线程 |
| 背压 | `channel.isWritable()` + `channelWritabilityChanged` | `SseEmitter` 超时/异常 |
| 高吞吐 | 7–11x Tomcat SSE（benchmark 数据） | 低吞吐（连接数增加时线性下降） |

---

## 八、Netty 传输优化：`TCP_NODELAY`/`DefaultFileRegion`/`WriteBufferWaterMark`

### 手段

`NettyHttpServer` 在启动时配置 Netty 的传输层参数：

```java
// NettyHttpServer.java
.option(ChannelOption.SO_BACKLOG,
        webContext.getProps().getInt(PropertiesConstant.SERVER_NETTY_SO_BACKLOG))  // 默认 128
.childOption(ChannelOption.TCP_NODELAY, true)              // 禁用 Nagle 算法
.childOption(ChannelOption.SO_KEEPALIVE, false)            // TCP keepalive（默认关闭）
.childOption(ChannelOption.SO_REUSEADDR, true)             // 地址重用
.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)  // pooled 分配器
.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
        new WriteBufferWaterMark(8192, 32768))             // low=8KB, high=32KB

// 零拷贝文件传输
pipeline.addLast(new ChunkedWriteHandler());              // 支持 FileRegion
```

### 为什么快

- **`TCP_NODELAY`**：禁用 Nagle 算法，小数据包（如 SSE 的 `data:...\n\n`）立即发送，不等待 ACK 累积。
- **`WriteBufferWaterMark`**：低水位 8KB、高水位 32KB（可配置）。Netty 的 `Channel.isWritable()` 在写缓冲超过高水位时返回 `false`，低于低水位时返回 `true`。与 `BackpressureHandler` 配合，实现 TCP 层的背压控制。
- **`ChunkedWriteHandler`**：支持 `FileRegion` 零拷贝文件传输，数据从磁盘到网卡绕过 JVM 堆。
- **`SO_BACKLOG`**：连接队列长度（默认 128，可配置），高并发下可调大以减少连接拒绝。
- **`PooledByteBufAllocator`**：复用 ByteBuf 实例，减少 GC 压力。

### 对比 Spring MVC

| 维度 | 本框架 | Spring MVC（Tomcat） |
|------|--------|---------------------|
| Nagle 算法 | `TCP_NODELAY=true`（可配置，默认 true） | `socket.tcpNoDelay=true`（Spring Boot 默认） |
| 写缓冲 | `WriteBufferWaterMark(8KB, 32KB)`（可配置） | Tomcat 内部缓冲（`max-connections=8192`） |
| 零拷贝文件 | `FileRegion` + `ChunkedWriteHandler` | `sendfile`（NIO connector） |
| 连接队列 | `SO_BACKLOG=128`（可配置） | `accept-count=100`（Spring Boot 默认） |
| ByteBuf 分配器 | `PooledByteBufAllocator`（可配置） | Tomcat 内部缓冲池 |
| 连接数 | 无上限（受 Netty 配置和系统限制） | `max-connections=8192`（Spring Boot 默认） |
| 背压控制 | `BackpressureHandler` + `WriteBufferWaterMark` | Tomcat 连接器内部抛异常 |

---

## 九、优化收益汇总

| 优化 | 启动期 | 运行时 | 每请求加速 | 分配减少 |
|------|--------|--------|-----------|---------|
| 启动预缓存 | 计算 + 存储 | 数组索引直取 | ~200ns | 0 |
| ASM/MethodHandle | 字节码生成/MethodHandle 创建 | `INVOKEVIRTUAL`/`invokeExact` | ~170ns（vs `Method.invoke`） | 0 |
| 多级 RouterOptimizer | 构建优化器链 | `HashMap.get()` O(1) | ~500ns–1μs | 0 |
| `fastAttributes[]` | 启动期分配索引 | `Object[index]` | ~30ns/次（vs `ConcurrentHashMap`） | 0 |
| `ByteBuf` 引用计数 | 0 | `acquire()`/`release()` | 0 | ~15–26KB/请求（vs Tomcat Facade） |
| 线程模型（基准 eventloop 模式） | 0 | 零线程切换 | ~5–10μs（vs 线程池切换） | 0 |
| 无锁 Drain Loop | 0 | CAS + `wip` | 无锁竞争 | 0 |
| Netty 传输 | 配置 ChannelOption | 零拷贝 + TCP 优化 | 视场景 | 0 |

**Benchmark 数据汇总**（详见 [17 篇](17-benchmark-data.md)）：

| 场景 | 本框架（perf） | Spring MVC（Tomcat） | 倍数 |
|------|---------------|---------------------|------|
| GET（16t） | 72636 ops/s | 39398 ops/s | ~1.84x |
| JSON（16t） | 73286 ops/s | 45481 ops/s | ~1.61x |
| SSE（16t） | 14920 ops/s | 1933 ops/s | ~7.72x |
| 每请求分配 | ~8–11KB | ~23–37KB | ~1/3 |

---

## 十、小结：性能设计的"三元组"思维

8 条优化的三元组结构：

| # | 优化 | 手段 | 为什么快 | 对比谁 |
|---|------|------|---------|--------|
| 1 | 启动预缓存 | `StaticArgumentResolver[]` + `MappingCacheKey` | 零运行时匹配，O(1) 索引 | `HandlerMethodArgumentResolverComposite` + `synchronized` |
| 2 | 方法调用 | ASM/`MethodHandle` | JIT 内联，零 native 边界 | `Method.invoke` |
| 3 | 路由匹配 | 四类 `RouterOptimizer` 链 | 全路径 O(1) HashMap，前缀 HashMap | `AntPathMatcher.match`/`PathPattern.matches` |
| 4 | 属性访问 | `fastAttributes[]` + `int` 索引 | `aload` + `iaload` 两条指令 | `ConcurrentHashMap.get()` |
| 5 | 内存管理 | `ByteBuf` acquire/release | Direct Memory 减少 GC，零拷贝 | Servlet Facade 包装器 |
| 6 | 线程模型 | default 池为基线，eventloop 模式可选 | 业务池兼容阻塞；eventloop 零切换 | Tomcat 线程池 |
| 7 | 流式输出 | `MpscArrayQueue` + `AtomicInteger wip` | 无锁 drain loop，批量消费 | `BlockingQueue` + 每连接一线程 |
| 8 | 传输层 | `TCP_NODELAY`/`FileRegion`/`WaterMark` | 减少延迟，零拷贝，背压控制 | Tomcat 连接器配置 |

这一层的克制体现在：**不把性能优化当作"事后的打磨"，而是"启动期的确定性 + 运行时的零开销"——每条优化在启动期一次性完成计算和缓存，运行时从数组/索引直取。** 8 条优化覆盖了从路由匹配到线程模型、从内存管理到传输层配置的完整请求路径，每条都对应一个明确的 Spring MVC 同位实现，差异可测量、可归因。

---

> **下一篇**：[16 · 代码聚光灯](16-code-spotlights.md)——十个"体现工程美感"的具体实现，逐个用 30–80 行讲透设计意图与巧妙处。