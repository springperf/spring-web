# 13 · spring-web-batch：Disruptor 透明请求聚合内幕

> [← 返回索引](00-README.md) | 上一篇：[12 · support 桥接层](12-support-bridge.md) | 下一篇：[14 · starter 自动配置](14-starter-autoconfig.md)

---

## 引子：当单请求变成批量

大多数 Web 框架的请求处理模型是"一个请求 → 一次方法调用"。当业务逻辑包含 IO 操作（DB 查询、RPC 调用）时，批量合并是提升吞吐的有效手段——把 N 个独立请求聚合成一个批量查询，一次 IO 返回 N 个结果。

Spring 生态中，批处理是业务代码的责任：你需要自己收集请求、设计批处理队列、管理线程和超时。`spring-web-batch` 做的事情是**把"批量"变成框架的透明能力**——Controller 方法照常写，框架在启动期替换方法体，把每次调用重定向到 Disruptor 环形缓冲区，由批量处理方法一次消费。

**核心问题**：`BatchInvoker` 如何在启动期替换原 Controller 方法调用？Disruptor RingBuffer 如何无锁入队？三线程（EventLoop 生产者 / Disruptor 消费者 / bizExecutor 业务池）如何协作与背压？8 个 Micrometer 指标各自埋点在哪？

---

## 一、总体架构：三线程模型

`@BatchMapping` 为每个方法创建独立的 Disruptor 队列，请求流转经过三个线程层级：

```
EventLoop 线程（生产者）
    │  BatchInvoker.invoke(args) → 反射创建 BatchRequest 实例
    │  queue.enqueue(batchReq)   → RingBuffer.tryPublishEvent / publishEvent
    ▼
┌──────────────────────────────────────────┐
│  Disruptor RingBuffer（预分配 BatchEvent） │
│  ProducerType.MULTI · 固定容量 2^N       │
└──────────────────────────────────────────┘
    │  Disruptor 消费者线程（1 个）
    │  BufferingBatchHandler.onEvent() → buffer.add()
    │  buffer.size() >= maxBatchSize || endOfBatch → flush()
    ▼
┌──────────────────────────────────────────┐
│  bizExecutor（0 核心 + SynchronousQueue  │
│   + CallerRunsPolicy）                   │
└──────────────────────────────────────────┘
    │  worker 线程
    │  meta.batchMethod().invoke(bean, batch)
    ▼
@BatchMapping 方法执行
    │  req.setResult(resp) → DeferredResult 完成
    ▼
HTTP 响应
```

三层之间通过以下机制解耦与背压：

| 层 | 线程 | 职责 | 背压路径 |
|----|------|------|---------|
| **生产者** | EventLoop | `BatchInvoker` 创建 `BatchRequest` 入队 | RingBuffer 满 → EventLoop 阻塞 → TCP 反压 |
| **消费者** | Disruptor 单线程 | 从 RingBuffer 拉取，攒批，提交到业务池 | `CallerRunsPolicy` → 消费者线程执行业务 → 消费阻塞 → RingBuffer 满 |
| **业务执行** | bizExecutor 池 | 调用 `@BatchMapping` 方法处理整批请求 | `SynchronousQueue` 满 → `CallerRunsPolicy` 触发 |

---

## 二、注册与扫描：`BatchRegistry` + `BatchScanner`

### 2.1 启动期安装

`BatchRegistry` 继承 `BaseWebComponent`，在 `initComponentPhase2` 中执行扫描和安装：

```java
// BatchRegistry.java
public void initComponentPhase2() throws Exception {
    ApplicationContext ctx = webContext.getCtx();
    MappingRegistry mappingRegistry = webContext.getWebComponent(MappingRegistry.class);

    List<BatchHandlerRegistration> registrations = new BatchScanner()
            .scan(ctx, mappingRegistry.getMappingContextList());

    for (BatchHandlerRegistration reg : registrations) {
        install(reg);
    }
    this.registrations.addAll(registrations);
}
```

`install` 方法做五件事：

1. **缓存 `BatchRequestMetaData`**：将元数据通过 `BATCH_META_CACHE_KEY` 缓存到 `singleCtx`（单请求 `PathMappingContext`）上，供后续 `BatchInvoker` 使用。
2. **创建 `DisruptorQueue`**：按 `queueName`（`batch:<ClassName>.<methodName>`）创建队列，`putIfAbsent` 防重复安装，冲突时 `shutdown` 新队列再抛异常（，修复前直接抛导致线程泄漏）。
3. **替换 Invoker**：`singleCtx.setInvoker(new BatchInvoker(meta, queue))`——这是"方法体替换"语义的实现点。
4. **矫正返回值类型**：`setEffectiveReturnType`让 `ReturnValueResolverRegistry` 能正确解析 `BatchRequest<X>` 中的泛型内联类型 `X`。
5. **取消 `@RunInPool`**：`setDefaultPool(ctx, null)`确保入队操作在 EventLoop 上完成，不额外线程切换。

### 2.2 扫描过程

`BatchScanner.scan`分三步：

1. **`findBatchMethods`**：扫描所有 `@Controller` Bean，查找带 `@BatchMapping` 的方法，收集为 `BatchMethodCandidate` 列表。
2. **`findSingleMethod`**：对每个 `@BatchMapping` 方法，按方法名从同一 Bean 的 `PathMappingContext` 列表中查找关联的单请求方法。如果找到多个同名方法，检查方法签名是否一致——不一致时抛异常（，避免歧义匹配）。
3. **`resolveRequestType`**：从 `@BatchMapping` 方法的第一参数 `List<X>` 中提取泛型参数 `X`，确保 `X extends BatchRequest<?>`。这是连接"单请求方法参数类型"与"批量请求构造函数"的关键桥梁。

### 2.3 `BatchRequestMetaData`：元数据容器

`BatchRequestMetaData`（10 个字段）是扫描阶段的产物，包含了运行期所需的一切信息：

```java
// BatchRequestMetaData.java
public Method batchMethod()      { return batchMethod; }      // @BatchMapping 方法
public Class<?> beanType()       { return beanType; }         // Controller 类
public Class<? extends BatchRequest<?>> requestType()         // BatchRequest 子类
public String queueName()        { return queueName; }        // "batch:UserController.batchGetUser"
public int ringBufferSize()      { return ringBufferSize; }   // 归一化后的容量
public BatchMapping.WaitStrategy waitStrategy()               // 等待策略
public BatchMapping.Backpressure backpressure()               // 背压策略
public Constructor<?> singleMethodCtor()                      // 单方法参数匹配的构造函数
public int maxBatchSize()        { return maxBatchSize; }     // 攒批阈值
public int consumerSize()        { return consumerSize; }     // 业务池最大线程数
```

---

## 三、方法体替换：`BatchInvoker`

### 3.1 `CustomInvoker` 接口

`BatchInvoker` 实现 `CustomInvoker` 接口（[09 篇](09-invoker-bytecode.md) 第七条），是框架中唯一一个在启动期被注入到 `PathMappingContext` 的自定义调用器。

### 3.2 `invoke` 方法

`BatchInvoker.invoke`是"方法体替换语义"的运行时入口：

```java
// BatchInvoker.java
public Object invoke(Object[] args) throws Exception {
    BatchRequest<?> batchReq = (BatchRequest<?>) meta.singleMethodCtor().newInstance(args);
    queue.enqueue(batchReq);
    return batchReq;
}
```

两步：**反射创建 `BatchRequest` 实例** → **入队**。这个过程在 EventLoop 线程上执行，不涉及 `@RunInPool` 的线程切换。

关键语义：**原 Controller 方法体完全不执行。** `args` 是参数解析器产出的 `Object[]` 数组（与单请求方法参数类型按序一致），`singleMethodCtor` 在 `BatchScanner.resolveSingleMethodCtor` 中通过 `PathMappingContext` 的 `MethodParameter[]` 匹配构造函数参数类型。

```java
// BatchScanner.java
private Constructor<?> resolveSingleMethodCtor(Class<?> requestType, PathMappingContext singleCtx) {
    MethodParameter[] params = singleCtx.getMethodParameters();
    Class<?>[] paramTypes = params == null || params.length == 0
            ? new Class<?>[0]
            : Arrays.stream(params).map(MethodParameter::getParameterType).toArray(Class<?>[]::new);
    try {
        Constructor<?> ctor = requestType.getDeclaredConstructor(paramTypes);
        ctor.setAccessible(true);
        return ctor;
    } catch (NoSuchMethodException e) {
        throw new IllegalStateException(
                "BatchRequest [" + requestType.getSimpleName()
                        + "] must have a constructor matching method ["
                        + singleCtx.getMethod().getName() + "] parameter types: "
                        + Arrays.toString(paramTypes));
    }
}
```

### 3.3 `BatchRequest`：`DeferredResult` 子类

`BatchRequest<R>` 继承 `DeferredResult<R>`，是单请求的"响应容器"：

```java
// BatchRequest.java
public abstract class BatchRequest<R> extends DeferredResult<R> {
    private static final long DEFAULT_TIMEOUT = 30000L;
    private volatile boolean completed;

    @Override
    public final boolean setResult(R result) {
        if (completed) return false;
        completed = true;
        return super.setResult(result);
    }

    @Override
    public boolean setErrorResult(Object result) {
        if (completed) return false;
        completed = true;
        return super.setErrorResult(result);
    }
}
```

`completed` 守卫确保 `setResult`/`setErrorResult` 只生效一次——批量处理中可能多个消费者竞争完成同一个请求，**"先到先得"语义**防止重复回调。`BatchRequest` 作为 `invoke` 的返回值，经由 [11 篇](11-async-streaming.md) 的 `DeferredResult` 处理路径完成异步响应。

---

## 四、入队与背压：`DisruptorQueue`

### 4.1 构造

`DisruptorQueue` 构造器初始化 Disruptor 的三个核心组件：

```java
// DisruptorQueue.java
this.disruptor = new Disruptor<>(
        BatchEvent::new,                          // EventFactory
        size,                                     // RingBuffer 容量（2^N）
        r -> new Thread(r, "batch-disruptor-" + queueName),
        ProducerType.MULTI,                        // 多生产者（多个 EventLoop 并发入队）
        WaitStrategyFactory.create(meta.waitStrategy())
);
```

| 组件 | 选择 | 理由 |
|------|------|------|
| `ProducerType.MULTI` | 多生产者模式 | 多个 EventLoop 线程可能同时入队，`MULTI` 使用 CAS 而非 `next()` 单线程 claim |
| `EventFactory` | `BatchEvent::new` | RingBuffer 预分配所有 `BatchEvent` 槽位，运行时复用，零分配 |
| 线程工厂 | 命名线程（非 daemon） | 确保 shutdown 前线程不会因 JVM 退出而中断 |

### 4.2 `bizExecutor`：SynchronousQueue + CallerRunsPolicy

业务线程池使用零核心线程 + `SynchronousQueue` + `CallerRunsPolicy`：

```java
// DisruptorQueue.java
this.bizExecutor = new ThreadPoolExecutor(
        0, consumerSize,
        60L, TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        r -> new Thread(r, "batch-worker-" + queueName),
        (r, executor) -> {
            if (!executor.isShutdown()) {
                r.run();  // CallerRunsPolicy — 消费者线程直接执行
            }
        }
);
```

这个组合的设计意图：

- **空闲零线程**：`corePoolSize=0` + `SynchronousQueue`，无请求时线程池零占用。
- **自然背压**：`SynchronousQueue` 没有缓冲容量，提交任务时必须有一个工作线程在 `take()`。如果没有空闲线程，新建线程直到 `maximumPoolSize`。达到上限后触发 `CallerRunsPolicy`——Disruptor 消费者线程自行执行 `processBatch`，此时消费者不再从 RingBuffer 拉取新事件，RingBuffer 逐渐填满，EventLoop 生产者因 RingBuffer 满而阻塞，最终反压到 TCP 层。
- **无需预热**：无核心线程，无需手动调优。

### 4.3 `normalizeRingBufferSize`：C8/C12 保护

`normalizeRingBufferSize`将配置值归一化为 2 的 N 次幂，附带两个保护：

```java
// DisruptorQueue.java
static int normalizeRingBufferSize(int size) {
    if (size <= 0) return 4096;
    // C8: 禁止退化尺寸。ringBufferSize=1 意味着单槽队列，并发批处理下灾难性
    // 背压/丢请求；<64 一律提升到最小可用尺寸 64。
    if (size < 64) return 64;
    int result = Integer.highestOneBit(size);
    if (result < size) result <<= 1;
    // C12: result 溢出为负（size > 2^30）或恰好等于 2^30 时都不能用——Disruptor
    // RingBuffer 构造时按 capacity 预分配全部 BatchEvent 槽位，2^30 槽位 × ~24B
    // ≈ 25GB+ 堆，任何 -Xmx 都必然 OOM（2^30 只是 int 里最大 2 的幂，不是内存安全
    // 容量）。回退到构造器 warn 声明的最大合理容量 2^18（262144，预分配 ~6MB）。
    return result > 0 && result < (1 << 30) ? result : 1 << 18;
}
```

| 保护 | 条件 | 行为 |
|------|------|------|
| **C8** | `size < 64` | 回退到 64，防止退化尺寸（如 `ringBufferSize=1` 导致灾难性背压） |
| **C12** | `result >= 1<<30` | 回退到 262144（~6MB），防止 `2^30` 预分配 25GB+ 堆导致 OOM |

### 4.4 `enqueue`：三种背压策略

`enqueue`根据 `BatchMapping.Backpressure` 枚举选择入队方式：

```java
// DisruptorQueue.java
public void enqueue(BatchRequest<?> request) {
    // 已知边界（Tier 1 评审确认，保持零锁热路径）：
    // halted 检查与 publish 之间存在纳秒级竞态窗口——enqueue 读到 halted=false 后，
    // shutdown 恰好完成 disruptor.shutdown()（drain 全部已发布事件 + 停消费线程），
    // 随后本线程才发布 → 事件滞留环形缓冲无人消费，该请求由 DeferredResult 30s 超时兜底。
    if (halted.get()) {
        metrics.recordEnqueue(queueName, false);
        batchHandler.processDirectly(Collections.singletonList(request));
        return;
    }
    switch (backpressure) {
        case DROP:
            if (!ringBuffer.tryPublishEvent(translator, request)) {
                metrics.recordDrop(queueName);
                if (request.isCompleted()) {
                    log.warn("Queue [{}] drop failed — request already completed", queueName);
                } else {
                    request.setError(new BatchOverflowException(queueName));
                }
            }
            break;
        case THROW:
            if (!ringBuffer.tryPublishEvent(translator, request)) {
                metrics.recordOverflow(queueName);
                throw new BatchOverflowException(queueName);
            }
            break;
        case BLOCK:
        default:
            ringBuffer.publishEvent(translator, request);  // 阻塞等待
            break;
    }
    metrics.recordEnqueue(queueName, true);
}
```

| 策略 | Disruptor 方法 | EventLoop 行为 | 客户端响应 |
|------|---------------|---------------|-----------|
| **BLOCK**（默认） | `publishEvent` | 阻塞等待 RingBuffer 有空间 → TCP 反压 | 连接挂起 |
| **DROP** | `tryPublishEvent` | 失败时 `BatchRequest.setError(BatchOverflowException)` | 429 Too Many Requests |
| **THROW** | `tryPublishEvent` | 失败时同步抛异常 → 404/500 处理路径 | 429 Too Many Requests |

`DROP` 与 `THROW` 的区别：`DROP` 不阻塞 EventLoop，请求通过 `BatchRequest.setError` 异步完成（走 `DeferredResult` 处理路径）；`THROW` 同步抛出，由框架的异常处理路径响应。

### 4.5 `shutdown`：三步优雅停机

`shutdown`使用三步策略确保安全关闭：

```java
// DisruptorQueue.java
public void shutdown() {
    if (!halted.compareAndSet(false, true)) return;

    // Step 1: 优雅 drain Disruptor RingBuffer——消费所有已发布事件
    disruptor.shutdown();  // 或 fallback 到 disruptor.halt()

    // Step 2: 冲刷 BufferingBatchHandler 中残余的 buffer
    batchHandler.flushRemaining();

    // Step 3: 等待正在执行的批处理完成
    bizExecutor.shutdown();
    if (!bizExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
        bizExecutor.shutdownNow();
    }
}
```

三步顺序是必要的：先停 Disruptor 消费者（不再拉取新事件）→ 冲刷残余 buffer（`flushRemaining` 处理 `shutdown` 前已拉取但未提交的 `BatchRequest`）→ 等待业务线程池完成正在执行的批处理。`awaitTermination(30s)` 超时后 `shutdownNow`，防止线程泄漏。

---

## 五、攒批消费：`BufferingBatchHandler`

### 5.1 `onEvent`：攒批逻辑

`BufferingBatchHandler` 实现 `EventHandler<BatchEvent>`，是 Disruptor 的唯一消费者：

```java
// BufferingBatchHandler.java
public void onEvent(BatchEvent event, long sequence, boolean endOfBatch) {
    buffer.add(event.request());

    boolean shouldFlush = endOfBatch
            || (maxBatchSize > 0 && buffer.size() >= maxBatchSize);
    if (shouldFlush) {
        flush();
    }
}
```

攒批触发条件：`endOfBatch`（Disruptor 在批量发布结束时给消费者的信号）或 `buffer.size() >= maxBatchSize`（默认 100）。

`flush()`将当前 buffer 原子替换为新 ArrayList，提交到 `bizExecutor`：

```java
// BufferingBatchHandler.java
private void flush() {
    if (buffer.isEmpty()) return;
    List<BatchRequest<?>> batch = buffer;
    buffer = new ArrayList<>();
    executor.execute(() -> processBatch(batch));
}
```

### 5.2 `processBatch`：批量执行

`processBatch`反射调用 `@BatchMapping` 方法：

```java
// BufferingBatchHandler.java
private void processBatch(List<BatchRequest<?>> batch) {
    long start = System.nanoTime();
    int batchSize = batch.size();
    boolean success = false;
    try {
        Object[] args = new Object[]{batch};
        meta.batchMethod().invoke(bean, args);
        success = true;
        metrics.recordRequestCompleted(meta.queueName(), batchSize);
    } catch (InvocationTargetException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        handleBatchError(batch, cause);
    } catch (Throwable e) {
        handleBatchError(batch, e);
    } finally {
        metrics.recordBatchProcessed(meta.queueName(), batchSize,
                System.nanoTime() - start, success);
    }
}
```

`@BatchMapping` 方法执行失败时，`handleBatchError`遍历所有未完成的 request，调用 `setError(cause)` 完成每个请求的异常响应——**不会出现"一个请求失败，整批请求无响应挂起"**。

---

## 六、可观测性：8 个 Micrometer 指标

`BatchMetrics` SPI定义了 6 个记录方法，对应 8 个 Micrometer 指标：

| 指标名 | 记录方法 | 埋点位置 | 类型 | Tag |
|--------|---------|---------|------|-----|
| `batch.enqueue.total` | `recordEnqueue` | `DisruptorQueue.enqueue` 成功/失败时 | Counter | `queue` |
| `batch.enqueue.dropped` | `recordDrop` | `DisruptorQueue.enqueue` DROP 分支 | Counter | `queue` |
| `batch.enqueue.overflow` | `recordOverflow` | `DisruptorQueue.enqueue` THROW 分支 | Counter | `queue` |
| `batch.process.duration` | `recordBatchProcessed` | `BufferingBatchHandler.processBatch` finally | Timer | `queue` |
| `batch.process.batch.size` | `recordBatchProcessed` | 同上（DistributionSummary 维度） | DistributionSummary | `queue` |
| `batch.process.requests` | `recordRequestCompleted` | `BufferingBatchHandler.processBatch` 成功时 | Counter | `queue` |
| `batch.queue.remaining` | `reportQueueCapacity` | `DisruptorQueue.remainingCapacity()` | Gauge | `queue` |
| `batch.queue.capacity` | `reportQueueCapacity` | `DisruptorQueue.bufferSize()` | Gauge | `queue` |

所有指标的 `queue` Tag 值为 `batch:<ClassName>.<methodName>`（如 `batch:UserController.batchGetUser`）。

`reportQueueCapacity` 在 `BatchRegistry` 中通过调度定期上报——`DisruptorQueue` 暴露出 `remainingCapacity()` 和 `bufferSize()` 方法，供外部采集器轮询。

`NoOpBatchMetrics`是默认实现，所有方法空操作——当 Micrometer 不在类路径时，零开销。

---

## 七、边界与安全网

### 7.1 停机竞态窗口

`DisruptorQueue.enqueue` 的 `halted` 检查与 `shutdown` 之间存在纳秒级竞态窗口：

```java
// DisruptorQueue.java 注释
// 已知边界（Tier 1 评审确认，保持零锁热路径）：halted 检查与 publish 之间存在纳秒级
// 竞态窗口——enqueue 读到 halted=false 后，shutdown 恰好完成 disruptor.shutdown()
// （drain 全部已发布事件 + 停消费线程），随后本线程才发布 → 事件滞留环形缓冲无人
// 消费，该请求由 DeferredResult 30s 超时兜底。仅停机瞬间概率性发生，可接受。
// 如需彻底关闭窗口：用 enqueueLock 互斥「检查 halted → publish」与 shutdown 的
// halted 设置（synchronized 包裹），代价是每次 enqueue 一次 monitor 进入/退出。
```

这是设计决策——**零锁热路径优先于停机时的完全有序**。竞态窗口的后果是停机瞬间的零星请求可能滞留 RingBuffer，由 `DeferredResult` 30s 超时兜底。

### 7.2 `BatchRequest` 超时

`BatchRequest` 默认 30s 超时，通过 `DeferredResult` 的超时机制触发。超时后，`DeferredResult` 自动调用超时回调，框架返回 503。`completed` 守卫确保 `setResult`/`setErrorResult` 只生效一次：如果超时先触发，`setErrorResult` 标记 `completed=true`，后续 `BufferingBatchHandler` 的 `setResult` 被 `if (completed) return false` 拦截。

### 7.3 `BatchOverflowException`：429 映射

`BatchOverflowException` 继承 `ResponseStatusException`，使用 `HttpStatus.TOO_MANY_REQUESTS`（429）：

```java
// BatchOverflowException.java
public class BatchOverflowException extends ResponseStatusException {
    public BatchOverflowException(String queueName) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "Batch queue [" + queueName + "] is full, please retry later");
    }
}
```

由于 `ResponseStatusException` 在 [10 篇](10-cross-cutting.md) 的异常处理链中，由 `ResponseStatusExceptionResolver` 处理，用户无需额外配置。

### 7.4 `BatchExceptionHandler`：Disruptor 异常兜底

`BatchExceptionHandler`实现 `ExceptionHandler<BatchEvent>`，处理 Disruptor 消费者的三个异常场景：

| 方法 | 场景 | 行为 |
|------|------|------|
| `handleEventException` | 消费者处理事件时抛异常 | 日志 error + 如果未完成则 `request.setError(ex)` |
| `handleOnStartException` | 消费者线程启动失败 | 日志 error |
| `handleOnShutdownException` | 消费者线程关闭异常 | 日志 warn |

---

## 八、对比 Spring 生态批处理

| 维度 | 本框架（`spring-web-batch`） | Spring 生态 |
|------|---------------------------|-------------|
| 批处理触发 | 框架自动攒批（`endOfBatch` + `maxBatchSize`） | 业务代码自行实现（`List` 积累 + `ScheduledExecutor` 定时刷） |
| 队列实现 | LMAX Disruptor RingBuffer（无锁、预分配） | 通常 `BlockingQueue` / `ConcurrentLinkedQueue` |
| 背压 | 三级背压链（RingBuffer → EventLoop → TCP） | 队列无界（`ConcurrentLinkedQueue`）或 `BlockingQueue` 满抛异常 |
| 背压策略 | BLOCK / DROP / THROW 三种可选 | 无（通常固定一种） |
| 线程模型 | 三线程隔离（生产者 / 消费者 / 业务池） | 生产者和消费者共享线程池或固定线程 |
| 方法体替换 | `BatchInvoker` 启动期替换，原方法体不执行 | 无等效机制，需业务代码手动封装 |
| 等待策略 | 4 种（BLOCKING / YIELDING / SLEEPING / BUSY_SPIN） | 无（通常 `BlockingQueue.take()` 或 `poll(timeout)`） |
| 可观测性 | 8 个指标 SPI，Micrometer 集成 | 无标准指标，需自行埋点 |
| 超时兜底 | `BatchRequest` 30s 默认超时 + `completed` 守卫 | 无标准超时机制 |
| 停机安全 | 3 步优雅停机（drain → flush → awaitTermination） | 无标准停机流程 |
| 与异步框架集成 | 原生集成 `DeferredResult` 异步处理路径 | 通常与 Spring MVC 异步独立 |
| 配置侵入性 | `@BatchMapping` 注解 + `BatchRequest` 子类 | 无标准化配置 |

**核心差异**：Spring 生态没有标准化的"请求透明聚合"方案。通常的做法是：

1. 业务代码中自行维护一个 `BlockingQueue<Request>`，用 `ScheduledExecutor` 定时刷或手动触发。
2. 使用 Spring Integration 或类似框架的聚合器（Aggregator），但需要额外引入消息中间件。
3. 批处理逻辑与业务代码耦合，队列满、超时、错误处理都需要手动管理。

`spring-web-batch` 把这一切下沉到框架层：**注解声明批量方法 → 框架自动攒批 → Disruptor 无锁入队 → 三级背压链 → 8 个指标可观测**。用户在业务代码中只需要关心"拿到一批请求，逐个 `setResult`"。

---

## 九、小结：批量模块的克制在哪里

回到引子的问题：`BatchInvoker` 如何在启动期替换原方法调用？Disruptor 如何无锁入队？三线程如何协作与背压？

1. **`BatchRegistry` + `BatchScanner` 启动期扫描** → 扫描 `@BatchMapping`，关联单请求方法，解析 `BatchRequest` 泛型类型，缓存 `BatchRequestMetaData` 到 `PathMappingContext`。
2. **`BatchInvoker` 方法体替换** → `PathMappingContext.setInvoker(batchInvoker)`，运行时反射创建 `BatchRequest` 实例并入队，原方法体完全不执行。
3. **`DisruptorQueue` 无锁入队** → `ProducerType.MULTI` CAS claim，`BatchEvent` 预分配零 GC，`normalizeRingBufferSize` C8/C12 保护，三种背压策略（BLOCK/DROP/THROW）。
4. **`BufferingBatchHandler` 攒批消费** → `endOfBatch` + `maxBatchSize` 双触发，`bizExecutor` 零核心线程 + `SynchronousQueue` + `CallerRunsPolicy` 自然背压，`handleBatchError` 逐个 `setError` 防挂起。
5. **三级背压链** → RingBuffer 满 → EventLoop 阻塞 → TCP 反压（BLOCK 模式）；或 `tryPublishEvent` 失败 + `BatchOverflowException` 429（DROP/THROW 模式）。
6. **8 个指标埋点** → `BatchMetrics` SPI 定义 6 个记录方法，`queue` Tag 值 `batch:<ClassName>.<methodName>`，`NoOpBatchMetrics` 默认零开销。
7. **安全网** → 停机竞态窗口由 `DeferredResult` 30s 超时兜底；`BatchRequest.completed` 守卫防止重复回调；`BatchExceptionHandler` 捕获消费者异常；`BatchOverflowException` 映射 429。

这一层的克制体现在：**不把批处理留给业务代码，也不为批处理引入复杂的消息中间件。** LMAX Disruptor 提供无锁的环形缓冲区，`ProducerType.MULTI` 支持多 EventLoop 并发入队，`SynchronousQueue` + `CallerRunsPolicy` 在零额外线程的基础上实现了自然背压——**没有调度线程、没有定时器、没有额外的队列线程**，三个线程层次（EventLoop / Disruptor 消费者 / bizExecutor 工作者）形成了一条干净的反压链。`@BatchMapping` 方法体替换语义让用户无需修改单请求方法——框架层面透明的"请求聚合"，是 [01 篇](01-design-philosophy.md) 原则 2（零侵入）在批处理场景的落地。

---

> **下一篇**：[14 · starter 自动配置](14-starter-autoconfig.md)——`spring-boot-starter-web`：条件装配、`@Configuration` 链、自动配置覆盖与扩展点。