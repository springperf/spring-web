# 16 · 代码聚光灯：十个值得反复读的实现

> [← 返回索引](00-README.md) | 上一篇：[15 · 性能优化全览](15-performance-optimizations.md) | 下一篇：[17 · Benchmark 数据解读](17-benchmark-data.md)

---

## 引子：工程美学的"十张快照"

前 15 篇从架构到性能、从桥接到自动配置，完整描述了框架的每个模块。本篇是"欣赏篇"——挑十个"体现工程美感"的具体实现，每个用 30–80 行讲透设计意图与巧妙处。

这些实现共同体现一个哲学：**启动期做尽计算，运行时只做最必要的事。**

---

## 聚光灯 1：`MappingCacheKey`——整型索引数组，预缓存核心

### 问题

框架在运行时需要频繁读取每方法、每类、每请求的元数据。Spring MVC 使用 `ConcurrentHashMap<String, Object>` 或 `AnnotatedElementUtils` 的缓存注解查找——每次读取需要 `hashCode()` → 桶定位 → `equals()` 比较 → `volatile` 读。

### 方案

`MappingCacheKey<T>` 是一个启动期分配整型索引的泛型容器（[05 篇](05-request-pipeline.md) 四节）：

```java
// MappingCacheKey.java
public final class MappingCacheKey<T> {
    private static final AtomicInteger METHOD_CACHE_SEQ = new AtomicInteger();   // 方法级计数器
    private static final AtomicInteger CLASS_CACHE_SEQ  = new AtomicInteger();  // 类级计数器
    final int index;                     // 启动期确定的 int 索引
    final Class<T> type;
    final boolean classCache;

    public static <T> MappingCacheKey<T> createMethodCacheKey(Class<T> type) {
        return new MappingCacheKey<>(METHOD_CACHE_SEQ.getAndIncrement(), type, false);
    }
    public static <T> MappingCacheKey<T> createClassCacheKey(Class<T> type) {
        return new MappingCacheKey<>(CLASS_CACHE_SEQ.getAndIncrement(), type, true);
    }
}

// 使用方：启动期静态常量
private static final MappingCacheKey<BatchRequestMetaData> BATCH_META_CACHE_KEY =
        MappingCacheKey.createMethodCacheKey(BatchRequestMetaData.class);

// 运行时：O(1) 数组直取
BatchRequestMetaData meta = mappingContext.get(BATCH_META_CACHE_KEY);  // methodCache[BATCH_META_CACHE_KEY.index]
```

### 巧妙之处

- **双 `AtomicInteger` 计数器**：方法级（`METHOD_CACHE_SEQ`）与类级（`CLASS_CACHE_SEQ`）各一个独立计数器，避免跨粒度索引冲突。每个 `MappingCacheKey` 启动期分配唯一 `int` 索引，映射到 `methodCache`/`classCache` 的 `Object[]` 槽位。
- **零运行时计算**：`mappingContext.get(cacheKey)` 等价于 `methodCache[key.index]`，两条字节码指令（`aload` + `iaload`）。
- **类型安全**：`MappingCacheKey<T>` 的泛型保证了 `get` 返回类型正确，无需 `cast`。
- **方法级 vs 类级**：`createMethodCacheKey` 和 `createClassCacheKey` 分别对应方法级别和类级别的缓存粒度，避免跨方法冲突。

---

## 聚光灯 2：`FastInvokerGenerator`——ASM 生成 `INVOKEVIRTUAL`

### 问题

方法调用是请求路径上的最高频操作之一。`Method.invoke` 每次调用付 access check + 可变参数装箱 + 类型校验，约 200ns。`MethodHandle.invokeExact` 降到 ~30ns，但仍有虚调用分发。

### 方案

`FastInvokerGenerator` 在启动期使用 ASM 为每个 `@Optimize` 方法生成一个 `Invoker` 实现类（[09 篇](09-invoker-bytecode.md) 三节），生成的 `invoke(Object[])` 方法体是直接的 `CHECKCAST` + `INVOKEVIRTUAL`：

```java
// 生成的类等价于：
public class Invoker$UserController$getUser$0 implements Invoker {
    private final UserController target;

    public Object invoke(Object[] args) {
        return this.target.getUser(        // INVOKEVIRTUAL 直接调用
            (String) args[0],              // CHECKCAST
            (Integer) args[1]              // CHECKCAST + unbox
        );
    }
}
```

### 巧妙之处

- **`ClassWriter.COMPUTE_FRAMES`**：ASM 自动计算栈帧和局部变量表大小，开发人员只需输出指令序列，无需手动管理帧。
- **`invokerClassCache` 按方法缓存**：`ConcurrentHashMap<Method, Class<?>>`，同一个方法的所有 `InvokableHandlerMethod` 实例共享字节码，只生成一次。
- **8 种基本类型拆箱/装箱**：`unbox` 方法覆盖 `int`/`boolean`/`long`/`double`/`float`/`short`/`byte`/`char`，每个对应 `CHECKCAST` + `XXXValue` 的拆箱序列，或 `valueOf` 的装箱序列。
- **`IN_NATIVE_IMAGE` 守卫**：GraalVM native-image 不允许运行时生成字节码，框架自动降级为 `MethodHandle`。

---

## 聚光灯 3：多级 `RouterOptimizer` 链短路

### 问题

路由匹配是每个请求的入口。Spring MVC 的 `AntPathMatcher.match` 每次请求逐段字符串匹配，包含通配符展开和正则匹配。

### 方案

`MappingRegistry` 在 `initComponentPhase3`（`MappingRegistry.java`）构建优化器链（[06 篇](06-routing-engine.md) 三节）。链序由 `optimizeMapping` 决定：先条件加入 `FullPathRouterOptimizer`（仅当无通配符路径非空），再对每组通配符路径调用 `getOptimizerTemplate()`（固定返回 `[Prefix, Suffix, Loop]`）按各自 `support()` 过滤后加入。请求按"最精确 → 最模糊"顺序短路：

```java
// MappingRegistry.java（doMapping 核心循环，简化）
protected void doMapping(WebServerHttpRequest req) {
    for (RouterOptimizer optimizer : optimizers) {     // FullPath → Prefix → Suffix → Loop（通配符组可能多组）
        Router router = optimizer.optimizeRoute(req);  // 优化器自判路径是否归属本层，否则返回 null
        if (router != null) {
            PathMappingContext ctx = router.route(req); // 命中则 O(1) HashMap 或 O(n) 遍历
            if (ctx != null) { handleMatch(req, ctx); return; }
        }
    }
}
```

### 巧妙之处

- **`PathPattern` 启动期分段**：`PathMappingContext` 构造时只存储 `pathRule` 字符串，真正的分段（`split("/")`）在优化器的 `support()` 方法中完成（`PrefixPathRouterOptimizer.support`），启动期据此为每条路径选定最优优化器。`/api/users/{id}` 进入全路径优化器，`/api/**` 进入前缀优化器，`*.json` 进入后缀优化器。
- **精确路径 O(1)**：`FullPathRouterOptimizer` 用 `HashMap<String, Router>`，全路径匹配直接命中，零额外计算。
- **前缀 HashMap**：`PrefixPathRouterOptimizer` 按选定前缀段拼接的字符串作 `HashMap` key，`/api/users/{id}` 与 `/api/orders/{id}` 共享 `/api` 前缀命中同一 `Router`，O(1) 查找。
- **短路收益**：benchmark 中绝大多数请求在第一层（全路径精确匹配）命中，剩余在前缀/后缀层命中，遍历兜底极少触发。

---

## 聚光灯 4：`AbstractNettyStreamSender` drain loop——SSE 无锁之美

### 问题

SSE 和流式输出需要生产者线程（EventLoop/业务线程）和消费者线程（Netty EventLoop writer）高效通信，避免锁竞争和线程挂起。

### 方案

`MpscArrayQueue`（固定容量 65536）+ `AtomicInteger wip` 的 drain loop 模式（[11 篇](11-async-streaming.md) 四节）：

```java
// DefaultNettyStreamSender.java（drain loop 核心结构，简化展示；入口在 scheduleDrain）
@Override
protected void drain() {
    if (!channel.isActive()) {                  // :29 失活分支：清队列、复位 wip
        queue.clear();
        wip.set(0);                              // 复位，否则后续 complete() 的 scheduleDrain 被吞
        if (completed && !lastHttpContentWritten) onAllDataWritten();
        return;
    }
    int missed = 1;
    for (;;) {                                   // :44 missed 重检循环
        while (channel.isWritable()) {           // :47 背压：不可写则停止消费
            Object data = queue.poll();
            if (data == null) break;
            // emitter.encode(data, batchOut) 批量编码到 batchBuf；达 maxFlushBytes 调 flushContent(batchBuf)
        }
        missed = wip.addAndGet(-missed);         // :77 drain 期间又有新入队？
        if (missed == 0) break;
    }
    afterDrain();                                 队列残留再调度 / completed 收尾写 LastHttpContent
}
```

### 巧妙之处

- **`scheduleDrain()` 的 wip 入口**：`wip.getAndIncrement() == 0` 时才真正调用/调度 drain，后续重入只递增 wip，由当前 drain 末尾的 missed 循环消化排空新数据。无 `synchronized`/`ReentrantLock`，无线程挂起——这是"无锁、无等待"的核心。
- **`missed = wip.addAndGet(-missed)` 重检**：drain 循环结束时检查是否有"错过"的并发入队。如果 `missed != 0`，说明在 drain 过程中又有新数据入队，继续循环。
- **`scheduleDrain()` 的 EventLoop 执行**：`wip.getAndIncrement() == 0` 时，若已在 EventLoop 则直接 drain，否则 `eventLoop.execute(this::drain)`（`eventLoop` 为构造期缓存的 `ctx.executor()`）确保所有 write/flush 在 EventLoop 上串行化。
- **`afterDrain()` 再检**：`wip.compareAndSet(0, 1)` + `queue.isEmpty()` 的双重检查防止 drain 完成瞬间的残留数据丢失。

---

## 聚光灯 5：`fastAttributes[]`——属性零哈希

### 问题

请求属性（异常对象、拦截器列表、异步结果）需要跨阶段传递。`ConcurrentHashMap<String, Object>` 提供灵活的键值存储，但每次访问需要 `hashCode()` → `equals()` → `volatile` 读。

### 方案

`BaseWebServerHttpRequest` 用 `Object[]` 数组 + 启动期分配的 `RequestAttribute` 索引（[05 篇](05-request-pipeline.md) 四节）：

```java
// RequestAttribute.java
public class RequestAttribute<T> {
    private static final AtomicInteger SEQ = new AtomicInteger();
    final int index;
    final Class<T> type;

    public static <T> RequestAttribute<T> createAttribute(Class<T> type) {
        return new RequestAttribute<>(SEQ.getAndIncrement(), type);   // 启动期分配唯一索引
    }
    public static int getMaxSize() { return SEQ.get() + 1; }          // 已注册属性数 → 数组长度
    public int getIndex() { return index; }
}

// BaseWebServerHttpRequest.java（实现 RequestContext 接口）
public abstract class BaseWebServerHttpRequest implements ..., RequestContext {
    protected final Object[] fastAttributes = new Object[RequestAttribute.getMaxSize()];

    public <T> T getAttribute(RequestAttribute<T> key) {
        int idx = key.getIndex();
        return idx < fastAttributes.length ? (T) fastAttributes[idx]            // 命中数组
                                          : (T) attributes.get(FAST_ATTR_PREFIX + idx);  // 溢出兜底
    }
}
```

### 巧妙之处

- **`int` 索引替代 `String` 键**：`RequestAttribute` 的 `AtomicInteger` 启动期分配唯一索引，无需人工维护。
- **数组长度启动期确定**：`fastAttributes` 长度 = `RequestAttribute.getMaxSize()`（启动期已注册属性数），溢出的属性键自动回落到 `attributes` Map（`FAST_ATTR_PREFIX + idx`），无固定上限、无扩容开销。
- **零并发控制**：`BaseWebServerHttpRequest` 绑定到单请求，单线程（EventLoop）访问，不需要 `volatile`、`synchronized` 或 `ConcurrentHashMap` 的分段锁。
- **`RequestAttribute<T>` 泛型**：`getAttribute` 返回类型安全，无需外部 `cast`。

---

## 聚光灯 6：`DispatcherHandler` acquire/release——跨线程内存安全

### 问题

`ByteBuf` 使用引用计数管理堆外内存。当请求切到业务线程池（default 池缺省即切，或 `@RunInPool("name")` 命名池）时，必须确保 EventLoop 线程和业务线程之间的内存安全：EventLoop 线程释放 `ByteBuf` 时，业务线程不应还在写入。

### 方案

`DispatcherHandler.handleWithMappingResult` 在切业务线程时 `acquire()`，业务线程的 finally 中 `release()`（[04 篇](04-request-pipeline.md) 二节）：

```java
// DispatcherHandler.java
protected void handleWithMappingResult(WebServerHttpRequest req, WebServerHttpResponse resp, MappingResult mappingResult) {
    ExecutorService executor = bizPoolRegistry.determinePool(req, mappingResult);
    if (executor != null) {                  // default 池（缺省）或命名池 → 切业务线程
        req.acquire();                       // EventLoop 线程：增加引用计数
        try {
            executor.execute(() -> {         // 提交到业务线程
                try { handleWithFilter(req, resp, mappingResult); }
                finally { req.release(); }   // 业务线程释放，与 EventLoop 的 acquire 跨线程配对
            });
        } catch (RejectedExecutionException e) {
            req.release();                   // 未提交成功，EventLoop 自行释放
            // ...返回 503...
        }
    } else {                                 // 无映射 / @RunInPool(EVENTLOOP) / =eventloop
        handleWithFilter(req, resp, mappingResult);  // EventLoop 同线程，不 acquire/release
    }
}
```

### 巧妙之处

- **`acquire`/`release` 跨线程配对**：切业务线程时 EventLoop 在 `executor != null` 分支 `acquire()`，业务线程的 finally `release()`；拒绝提交时 EventLoop 自行 `release()`。三条路径都成对，无悬挂 acquire。
- **EventLoop 同线程不 acquire**：`executor == null`（EventLoop 模式）时同线程处理，ByteBuf 引用计数由 Netty pipeline 管理，无需 acquire/release——避免无谓的计数器抖动。
- **引用计数安全**：`acquire()`（底层 `ByteBuf.retain()`）在 `executor.execute` 前执行，此时 `ByteBuf` 至少有一个引用（来自 Netty pipeline），不会出现 `retain()` 时已释放的竞态。
- **finally 确保释放**：业务线程的 `release()` 在 finally 中执行，不论异常还是正常返回，防止 Direct Memory 泄漏。

---

## 聚光灯 7：`WebMvcConfigurerBridge`——翻译中枢，兼容零侵入

### 问题

Support 模块需要桥接 Spring 的 `WebMvcConfigurer` 接口——该接口有 10+ 个方法，每个方法操作 Spring MVC 的注册器（`InterceptorRegistry`、`CorsRegistry`、`ResourceHandlerRegistry` 等）。框架需要在启动期读取 `WebMvcConfigurer` 实现并翻译到框架的 Registry。

### 方案

`WebMvcConfigurerBridge` 在 `initComponentPhase1` 内完成"收集 + 桥接翻译"（[12 篇](12-support-bridge.md) 二节）：

```java
// WebMvcConfigurerBridge.java
public class WebMvcConfigurerBridge extends BaseWebComponent {

    @Override
    public void initComponentPhase1() {                    // :74
        Map<String, WebMvcConfigurer> configurers =
                webContext.getCtx().getBeansOfType(WebMvcConfigurer.class);
        if (configurers.isEmpty()) return;

        // Spring 原生 Registry 作 Shim 收集器（局部变量）
        InterceptorRegistry shimInterceptorRegistry = new InterceptorRegistry();
        CorsRegistry shimCorsRegistry = new CorsRegistry();
        ResourceHandlerRegistry shimResourceRegistry = new ResourceHandlerRegistry();

        // 收集：遍历所有 WebMvcConfigurer Bean 写入 shim
        for (WebMvcConfigurer configurer : configurers.values()) {
            configurer.addInterceptors(shimInterceptorRegistry);
            configurer.addCorsMappings(shimCorsRegistry);
            configurer.addResourceHandlers(shimResourceRegistry);
        }
        // 翻译：shim 收集的内容桥接到框架 Registry
        bridgeInterceptors(shimInterceptorRegistry);
        bridgeCorsMappings(shimCorsRegistry);
        bridgeResourceHandlers(shimResourceRegistry);
        bridgeFormatters(configurers);            // 余下 7 类直接遍历 configurer 桥接
        bridgeAsyncSupport(configurers);
        bridgeArgumentResolvers(configurers);
        bridgeMessageConverters(configurers);
        bridgeReturnValueHandlers(configurers);
        bridgeHandlerExceptionResolvers(configurers);
        bridgeConfigureValidator(configurers);
    }
}
```

### 巧妙之处

- **Spring 原生 Registry 作 Shim**：`shimInterceptorRegistry`/`shimCorsRegistry`/`shimResourceRegistry` 直接 `new` Spring MVC 的原生 Registry，`WebMvcConfigurer` 写入时不知道自己在被"收集"——它们就是 Spring 标准 API。收集后由 `bridgeInterceptors`/`bridgeCorsMappings`/`bridgeResourceHandlers` 读取 shim 的注册列表翻译到框架 Registry。
- **单阶段收集+翻译**：收集与翻译都在 `initComponentPhase1` 内完成，循环写完 shim 立即 bridge，无跨阶段时序依赖。跑在 Phase 1 是为确保框架 Registry 自身初始化前注入用户配置。
- **全量兼容**：10 个桥接方法覆盖 `addInterceptors`/`addCorsMappings`/`addResourceHandlers`/`addFormatters`/`configureAsyncSupport`/`addArgumentResolvers`/`extendMessageConverters`/`addReturnValueHandlers`/`extendHandlerExceptionResolvers`/`configureValidator`。
- **零侵入**：用户代码只操作 Spring 标准接口，框架通过 Shim 收集器透明拦截。

---

## 聚光灯 8：`FilterWrapper` IdentityHashMap——安全 Filter 不丢

### 问题

`FilterWrapper` 包装 `jakarta.servlet.Filter` 为框架的 `WebFilter`。`WebComponentContainer` 按组件名去重——同名只保留 order 更小者。Filter 没有天然的"方法名"标识，先前用 `System.identityHashCode` 可能哈希碰撞，导致两个不同的安全 Filter 实例被判为同一实例，低 order 者被静默销毁（安全回归）。

### 方案

`FilterWrapper` 使用 `IdentityHashMap<jakarta.servlet.Filter, String>` 为每个 Filter 实例生成唯一组件名（[12 篇](12-support-bridge.md) 三节）：

```java
// FilterWrapper.java
public class FilterWrapper implements WebFilter {

    // C4：实例级唯一标识。同类不同实例（如 Spring Security 同 filter 类多实例）
    // 不得被 WebComponentContainer 按类名去重误杀；同实例重复包装返回同一名字。
    private static final Map<jakarta.servlet.Filter, String> COMPONENT_NAMES =
            Collections.synchronizedMap(new IdentityHashMap<>());          // :69 线程安全
    private static final AtomicLong NEXT_INSTANCE_ID = new AtomicLong(1); // :72

    protected final jakarta.servlet.Filter filter;
    protected int order;

    @Override
    public String getComponentName() {                                     // :75 幂等
        return COMPONENT_NAMES.computeIfAbsent(filter, f ->
                f.getClass().getName() + "@" + NEXT_INSTANCE_ID.getAndIncrement());
    }
}
```

### 巧妙之处

- **`IdentityHashMap` 实例级唯一性**：`IdentityHashMap` 用 `==` 而非 `equals()` 比较键，同一 Filter 实例多次包装返回相同名称，不同实例"同一类"返回不同名称。
- **`Collections.synchronizedMap` 包装**：`COMPONENT_NAMES` 是跨线程共享的 static Map（启动期注册 + 动态注册路径都访问），`synchronizedMap` 保证线程安全。
- **`AtomicLong` 自增 ID**：`NEXT_INSTANCE_ID.getAndIncrement()` 保证全局唯一，即使同一 Filter 类有多个实例——如 `SecurityFilter` 的多个实例分别命名为 `...SecurityFilter@1`、`...SecurityFilter@2`（全限定名 + `@` + 递增 ID）。
- **`getComponentName()` 幂等**：每次调用 `computeIfAbsent`，同实例首次分配后复用缓存值，不同实例绝不碰撞，供 `WebComponentContainer` 按名去重使用（避免安全 Filter 被误杀）。

---

## 聚光灯 9：`BufferingBatchHandler`——攒批 + CallerRunsPolicy 背压

### 问题

Batch 模块需要收集独立请求为批量，提交到业务线程池执行。攒批的大小和时机需要平衡延迟和吞吐，同时线程池满时不能丢失请求。

### 方案

`BufferingBatchHandler` 作为 Disruptor 的唯一消费者，攒批后通过 `SynchronousQueue` + `CallerRunsPolicy` 的线程池提交（[13 篇](13-batch-module.md) 五节）：

```java
// BufferingBatchHandler.java
public void onEvent(BatchEvent event, long sequence, boolean endOfBatch) {
    buffer.add(event.request());

    boolean shouldFlush = endOfBatch
            || (maxBatchSize > 0 && buffer.size() >= maxBatchSize);  // maxBatchSize<=0 时仅靠 endOfBatch
    if (shouldFlush) {
        flush();
    }
}

private void flush() {
    if (buffer.isEmpty()) return;                   // 空批跳过
    List<BatchRequest<?>> batch = buffer;
    buffer = new ArrayList<>();                     // 原子替换：新事件进新 buffer
    executor.execute(() -> processBatch(batch));    // 提交到 bizExecutor
}
```

```java
// DisruptorQueue.java —— bizExecutor 构造（0 core + SynchronousQueue + CallerRunsPolicy）
this.bizExecutor = new ThreadPoolExecutor(
        0, consumerSize,
        60L, TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        r -> { Thread t = new Thread(r, "batch-worker-" + queueName); t.setDaemon(false); return t; },
        (r, executor) -> { if (!executor.isShutdown()) r.run(); }   // CallerRunsPolicy
);
```

### 巧妙之处

- **`endOfBatch` + `maxBatchSize` 双触发**：Disruptor 在批量发布结束时提供 `endOfBatch` 信号，减少攒批延迟。`maxBatchSize > 0` 时作为兜底防止单批过大；`maxBatchSize <= 0` 时仅靠 `endOfBatch`，纯延迟触发。
- **原子 buffer 替换**：`flush()` 先 `if (buffer.isEmpty()) return` 跳过空批，再将当前 buffer 整体替换为新 ArrayList 后提交线程池。提交后新事件进入新 buffer，与处理中的 batch 互不干扰。
- **`SynchronousQueue` + `CallerRunsPolicy`**：零核心线程池空闲时零占用，满负荷时消费者线程自行执行 → 消费者不再从 RingBuffer 拉取 → RingBuffer 填满 → EventLoop 生产者阻塞 → TCP 反压。一条完整的"自然背压链"。`bizExecutor` 在 `DisruptorQueue` 构造期创建并注入 `BufferingBatchHandler`。
- **`handleBatchError` 逐请求 `setError`**：`@BatchMapping` 方法执行失败时，遍历所有未完成的 request 调用 `setError(cause)`，不会出现"一个请求失败，整批请求无响应挂起"。

---

## 聚光灯 10：`SupportDispatcherHandler`——ChannelFuture 驱动 session 持久化

### 问题

Session 需要在响应写入完成后（`ChannelFuture` 完成时）持久化。如果持久化时机不对——在响应写入前持久化，可能写入失败但 session 已保存；在响应写入后不持久化，session 状态丢失。

### 方案

`SupportDispatcherHandler` 扩展 `DispatcherHandler`，在 `initContextHolders`（请求开始）向 response 注册 `WriteRespEventListener`，该监听器接入 Netty 的 `ChannelFuture` 回调，在响应写入完成/失败时持久化 session（[12 篇](12-support-bridge.md) 四节）：

```java
// SupportDispatcherHandler.java
public class SupportDispatcherHandler extends DispatcherHandler {

    @Override
    protected boolean initContextHolders(WebServerHttpRequest req, WebServerHttpResponse resp) {  // :22
        boolean init = super.initContextHolders(req, resp);
        // ...RequestContextHolder 设置...
        resp.addWriteRespEventListener(new SessionFlushListener(req));   // :28 注册写入完成监听器
        return init || requestAttributes != null;
    }

    // 接入 Netty ChannelFuture 回调，同步/异步/流式场景均在正确生命周期点执行
    private static class SessionFlushListener implements WriteRespEventListener {   // :53

        @Override public void completeSuccessCallback() { flushSession(); }          // :62
        @Override public void completeErrorCallback(Throwable t) { flushSession(); } // :67 失败也存

        private void flushSession() {                                                // :72
            PerfHttpSession session = request.getRequestContext()
                    .getAttribute(PerfHttpSessionManager.SESSION_ATTR_KEY);
            if (session == null || session.isInvalid()) return;
            PerfHttpSessionManager manager = request.getWebContext()
                    .getWebComponent(PerfHttpSessionManager.class);
            if (manager != null) { session.markAccessed(); manager.saveSession(session); }  // 
        }
    }
}
```

### 巧妙之处

- **`WriteRespEventListener` 接入 `ChannelFuture`**：监听器在 `initContextHolders`（请求开始）注册到 response，Netty 写入完成的 `ChannelFuture` 回调时触发 `completeSuccessCallback`，确保"响应先写、session 再存"，且同步/异步/流式场景均在正确生命周期点执行。
- **`completeErrorCallback` 也持久化**：响应写入失败仍调 `flushSession()` 更新 `lastAccessedTime`，防止 session 因写入失败而过早过期。
- **`markAccessed` + `saveSession`**：先标记访问时间再保存，`saveSession` 经 `PerfHttpSessionManager` 落到 storage，与 Servlet 容器的 session 持久化时机对齐。
- **无效 session 跳过**：`session == null || session.isInvalid()` 直接返回，避免持久化已失效 session。

---

## 小结：十张快照背后的设计哲学

十个聚光灯看似独立，但有一条共同主线——**"启动期做尽计算，运行时只做最必要的事"**：

| # | 聚光灯 | 启动期做的工作 | 运行时做的事 |
|---|--------|--------------|-------------|
| 1 | `MappingCacheKey` | 双 `AtomicInteger` 分配 int 索引 | `methodCache[key.index]` |
| 2 | `FastInvokerGenerator` | ASM 生成 `INVOKEVIRTUAL` 字节码 | `invoke(Object[])` 直接调用 |
| 3 | `RouterOptimizer` 链 | 启动期构建优化器链（HashMap） | 从第一层开始短路命中 |
| 4 | drain loop | 创建 `MpscArrayQueue`（65536 固定容量） | `wip.getAndIncrement()` + `queue.poll()` |
| 5 | `fastAttributes[]` | 启动期分配索引，构造时分配数组 | `Object[index]` 直读直写 |
| 6 | `acquire`/`release` | 无（运行时引用计数） | `retain()` + `release()` 配对 |
| 7 | `WebMvcConfigurerBridge` | Phase 1 收集 + 桥接翻译 | 无（运行时直接使用翻译后的 Registry） |
| 8 | `FilterWrapper IdentityHashMap` | 构造时计算唯一名称 | `getComponentName()` 返回缓存名称 |
| 9 | `BufferingBatchHandler` | 创建 Disruptor + bizExecutor | `onEvent()` 攒批 + `flush()` 提交 |
| 10 | `SupportDispatcherHandler` | 扩展 `DispatcherHandler` | `ChannelFuture` 回调驱动 session 持久化 |

8 个在启动期（或首次请求前）完成计算和缓存，2 个（`acquire`/`release` 和 `ChannelFuture` 回调）在运行时以最小的代价完成关键时序控制。这不是巧合，而是框架设计原则的必然结果——**把"确定"从运行时前移到启动期，运行时只保留"不确定"的部分**，这正是 [01 篇](01-design-philosophy.md) 原则 1（零匹配）和原则 5（零反射）在代码层面的完美呈现。

---

> **下一篇**：[17 · Benchmark 数据解读与归因](17-benchmark-data.md)——用最新报告的数字，归因到前文讲过的每一个机制。