# 13 · spring-web-batch：Disruptor 透明请求聚合内幕

> [← 返回索引](00-README.md) | 上一篇：[12 · support 桥接层](12-support-bridge.md) | 下一篇：[14 · starter 自动配置](14-starter-autoconfig.md)

---

## 引子：当单请求变成批量

大多数 Web 框架的请求处理模型是"一个请求 → 一次方法调用"。当业务逻辑包含 IO 操作（DB 查询、RPC 调用）时，批量合并是提升吞吐的有效手段——把 N 个独立请求聚合成一个批量查询，一次 IO 返回 N 个结果。

Spring 生态中，批处理是业务代码的责任：你需要自己收集请求、设计批处理队列、管理线程和超时。`spring-web-batch` 做的事情是**把"批量"变成框架的透明能力**——Controller 方法照常写，框架在启动期替换方法体，把每次调用重定向到 Disruptor 环形缓冲区，由批量处理方法一次消费。

**核心问题**：`BatchInvoker` 如何在启动期替换原 Controller 方法调用？Disruptor RingBuffer 如何无锁入队？三个线程层级（**生产者 = handler 执行线程** / Disruptor 消费者 / bizExecutor 业务池）如何协作与背压？9 个 Micrometer 指标各自埋点在哪？

---

## 一、总体架构：三线程模型

`@BatchMapping` 为每个方法创建独立的 Disruptor 队列，请求流转经过三个线程层级：

```
调用线程（生产者）——即该 @RequestMapping handler 的执行线程：
    默认 `default` 业务池（pool.default-execute-mode 默认值）；
    虚拟线程模式（spring.threads.virtual.enabled + JDK 21+）或显式
    pool.default-execute-mode=eventloop 时为 EventLoop（零切换）
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
| **生产者** | 调用线程（默认 `default` 业务池；虚拟线程 / `eventloop` 模式为 EventLoop） | `BatchInvoker` 创建 `BatchRequest` 入队 | RingBuffer 满 → 生产者线程阻塞 → TCP 反压 |
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
2. **创建 `DisruptorQueue`**：按 `queueName`（`batch:<ClassName>.<methodName>`）创建队列，`putIfAbsent` 防重复安装，冲突时先 `shutdown` 新队列再抛异常（直接抛出会留下未停止的消费线程）。
3. **替换 Invoker**：`singleCtx.setInvoker(new BatchInvoker(meta, queue))`——这是"方法体替换"语义的实现点。
4. **矫正返回值类型**：`setEffectiveReturnType`让 `ReturnValueResolverRegistry` 能正确解析 `BatchRequest<X>` 中的泛型内联类型 `X`。
5. **虚拟线程模式下取消 `@RunInPool`**：`if (virtualThreads) poolRegistry.setDefaultPool(reg.singleCtx(), null)`——虚拟线程的 `default` 池以虚拟线程执行任务，而 single 路径只做入队（微秒级、不阻塞），走它反而多一次线程切换，故置空默认池让入队留在 EventLoop。**未开虚拟线程时不设置**，遵循全局 `pool.default-execute-mode`（默认 `default` 业务池）。两种情况下用户显式 `@RunInPool` 都优先（`setDefaultPool` 仅在无注解时生效）。

### 2.2 扫描过程

`BatchScanner.scan`分三步：

1. **`findBatchMethods`**：扫描所有 `@Controller` Bean，查找带 `@BatchMapping` 的方法，收集为 `BatchMethodCandidate` 列表。
2. **`findSingleMethod`**：对每个 `@BatchMapping` 方法，按方法名从同一 Bean 的 `PathMappingContext` 列表中查找关联的单请求方法。如果找到多个同名方法，检查方法签名是否一致——不一致时抛异常（避免歧义匹配）。
3. **`resolveRequestType`**：从 `@BatchMapping` 方法的第一参数 `List<X>` 中提取泛型参数 `X`，确保 `X extends BatchRequest<?>`。这是连接"单请求方法参数类型"与"批量请求构造函数"的关键桥梁。

### 2.3 `BatchRequestMetaData`：元数据容器

`BatchRequestMetaData`（11 个字段）是扫描阶段的产物，包含了运行期所需的一切信息：

```java
// BatchRequestMetaData.java
public Method batchMethod()      { return batchMethod; }      // @BatchMapping 方法
public Class<?> beanType()       { return beanType; }         // Controller 类
public Class<? extends BatchRequest<?>> requestType()         // BatchRequest 子类
public String queueName()        { return queueName; }        // "batch:BatchUserController.batchGetUser"
public int ringBufferSize()      { return ringBufferSize; }   // 注解配置的原始值（归一化发生在 DisruptorQueue 构造时）
public BatchMapping.WaitStrategy waitStrategy()               // 等待策略
public BatchMapping.Backpressure backpressure()               // 背压策略
public Constructor<?> singleMethodCtor()                      // 单方法参数匹配的构造函数
public int maxBatchSize()        { return maxBatchSize; }     // 攒批阈值
public int batchWindowMillis()   { return batchWindowMillis; } // 攒批时间窗口，0=关闭（见 §5.1）
public int consumerSize()        { return consumerSize; }     // 业务池最大线程数
```

---

## 三、方法体替换：`BatchInvoker`

### 3.1 `CustomInvoker` 接口

`BatchInvoker` 实现 `CustomInvoker` 接口（[09 篇](09-invoker-bytecode.md) 第七条），是**批处理场景**在启动期被注入到 `PathMappingContext` 的自定义调用器。同族实现还有 `ServletInvoker`、`ViewControllerInvoker`、`ResourceRequestHandler`、`OperationHandlerInvoker`、`LinksOperationInvoker`——它们分属 servlet / 视图控制器 / 静态资源 / Actuator 等场景，`CustomInvoker` 是这些「非标准控制器处理器」的统一契约。

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

两步：**反射创建 `BatchRequest` 实例** → **入队**。这个过程运行在调用方线程（即该 handler 的执行线程：默认 `default` 业务池；虚拟线程 / `eventloop` 模式为 EventLoop）上，不涉及 `@RunInPool` 的额外线程切换。

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
        ProducerType.MULTI,                        // 多生产者（多个业务线程并发入队）
        WaitStrategyFactory.create(meta.waitStrategy())
);
```

| 组件 | 选择 | 理由 |
|------|------|------|
| `ProducerType.MULTI` | 多生产者模式 | 多个业务线程（默认 `default` 池的 worker；`eventloop` 模式下为多个 EventLoop）可能同时入队，`MULTI` 使用 CAS 而非 `next()` 单线程 claim |
| `EventFactory` | `BatchEvent::new` | RingBuffer 预分配所有 `BatchEvent` 槽位，运行时复用，零分配 |
| 线程工厂 | 命名线程（非 daemon） | 确保 shutdown 前线程不会因 JVM 退出而中断 |

### 4.2 `bizExecutor`：SynchronousQueue + CallerRunsPolicy

业务线程池使用零核心线程 + `SynchronousQueue` + `CallerRunsPolicy`：

```java
// DisruptorQueue.java
ThreadFactory bizThreadFactory = useVirtualThreads
        ? VirtualThreadSupport.newThreadFactory("batch-virtual-" + queueName + "-")
        : platformThreadFactory(queueName);          // 非 daemon 平台线程
this.bizExecutor = new ThreadPoolExecutor(0, consumerSize, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
        bizThreadFactory, (r, executor) -> {
            if (!executor.isShutdown()) {
                r.run(); // CallerRunsPolicy — 消费者线程直接执行
            }
        });
```

这个组合的设计意图：

- **空闲零线程**：`corePoolSize=0` + `SynchronousQueue`，无请求时线程池零占用。
- **自然背压**：`SynchronousQueue` 没有缓冲容量，提交任务时必须有一个工作线程在 `take()`。如果没有空闲线程，新建线程直到 `maximumPoolSize`。达到上限后触发 `CallerRunsPolicy`——Disruptor 消费者线程自行执行 `processBatch`，此时消费者不再从 RingBuffer 拉取新事件，RingBuffer 逐渐填满，生产者线程（默认 `default` 业务池 worker）因 RingBuffer 满而阻塞，最终反压到 TCP 层。
- **无需预热**：无核心线程，无需手动调优。

### 4.3 `normalizeRingBufferSize`：上下限保护

`normalizeRingBufferSize`将配置值归一化为 2 的 N 次幂，附带两个保护：

```java
// DisruptorQueue.java
static int normalizeRingBufferSize(int size) {
    if (size <= 0) return 4096;
    // 禁止退化尺寸。ringBufferSize=1 意味着单槽队列，并发批处理下灾难性
    // 背压/丢请求；<64 一律提升到最小可用尺寸 64。
    if (size < 64) return 64;
    int result = Integer.highestOneBit(size);
    if (result < size) result <<= 1;
    // 上限即 2^18=262144。Disruptor RingBuffer 按 capacity 预分配全部 BatchEvent
    // 槽位，2^18 × ~24B ≈ 6MB 堆；任何大于 262144 的请求都回退到该上限（而非可用到
    // 2^30——那需要 25GB+ 堆，必然 OOM）。
    return result > 0 && result <= (1 << 18) ? result : 1 << 18;
}
```

| 保护 | 条件 | 行为 |
|------|------|------|
| **下限保护** | `size < 64` | 回退到 64，防止退化尺寸（如 `ringBufferSize=1` 导致灾难性背压） |
| **上限保护** | `result > 1<<18`（含 `<=0` 的溢出为负） | 回退到 262144（~6MB）；**任何大于 262144 的配置都被压到该上限** |

### 4.4 `enqueue`：三种背压策略

`enqueue`根据 `BatchMapping.Backpressure` 枚举选择入队方式：

```java
// DisruptorQueue.java
public void enqueue(BatchRequest<?> request) {
    // 已知边界（保持零锁热路径）：
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
                reportCapacity();   // 队列满正是容量指标最需要被看到的时刻
                if (request.isCompleted()) {
                    log.warn("Queue [{}] drop failed — request already completed (likely timed out), "
                            + "client will not receive overflow signal", queueName);
                } else {
                    request.setError(new BatchOverflowException(queueName));
                }
            } else {
                metrics.recordEnqueue(queueName, true);   // 仅成功路径记录
            }
            break;
        case THROW:
            if (!ringBuffer.tryPublishEvent(translator, request)) {
                metrics.recordOverflow(queueName);
                reportCapacity();
                throw new BatchOverflowException(queueName);
            }
            metrics.recordEnqueue(queueName, true);
            break;
        case BLOCK:
        default:
            ringBuffer.publishEvent(translator, request);  // 阻塞等待
            metrics.recordEnqueue(queueName, true);
            break;
    }
}
```

| 策略 | Disruptor 方法 | 生产者行为 | 客户端响应 |
|------|---------------|-----------|-----------|
| **BLOCK**（默认） | `publishEvent` | 阻塞等待 RingBuffer 有空间 → TCP 反压 | 连接挂起 |
| **DROP** | `tryPublishEvent` | 失败时 `BatchRequest.setError(BatchOverflowException)`，异步完成 | 429 Too Many Requests |
| **THROW** | `tryPublishEvent` | 失败时同步抛 `BatchOverflowException` → 框架异常处理路径 | **429** Too Many Requests（`BatchOverflowException extends ResponseStatusException`，构造器传 `HttpStatus.TOO_MANY_REQUESTS`；不是 404/500，也没有 `@ResponseStatus` 注解） |

`DROP` 与 `THROW` 的区别：`DROP` 不阻塞 EventLoop，请求通过 `BatchRequest.setError` 异步完成（走 `DeferredResult` 处理路径）；`THROW` 同步抛出，由框架的异常处理路径响应。

### 4.5 `shutdown`：三步优雅停机

`shutdown`使用三步策略确保安全关闭：

```java
// DisruptorQueue.java
public void shutdown() {
    if (!halted.compareAndSet(false, true)) return;

    // Step 1: 带硬上界的 drain（无参 shutdown() 等价于 shutdown(-1, MILLISECONDS)，即无限等待）
    try {
        disruptor.shutdown(5, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
        disruptor.halt();   // 未消费请求由 DeferredResult 超时兜底
    }

    // Step 2: 冲刷 BufferingBatchHandler 中残余的 buffer
    batchHandler.flushRemaining();

    // Step 3: 等待正在执行的批处理完成
    bizExecutor.shutdown();
    if (!bizExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
        bizExecutor.shutdownNow();
    }
}
```

三步顺序是必要的：先停 Disruptor 消费者（不再拉取新事件）→ 冲刷残余 buffer（`flushRemaining` 处理 `shutdown` 前已拉取但未提交的 `BatchRequest`）→ 等待业务线程池完成正在执行的批处理。

**Step 1 必须用带超时的重载**：Disruptor 3.4.x 的无参 `shutdown()` 内部是 `shutdown(-1, MILLISECONDS)`——`timeout < 0` 时循环永不超时，等于无限等待 backlog 清空。而 bizExecutor 满员时消费者被 `CallerRunsPolicy` 拖入业务执行，backlog 清空速度就等于业务处理速度，容器关闭会没有上界。改成 5 秒上界后，超时即 `halt()`，未消费的请求由 `DeferredResult` 超时兜底——宁可快速完成关机。Step 3 的 `awaitTermination(30s)` 只覆盖业务池，不覆盖 Step 1。

另外，业务池的拒绝策略在池已 shutdown 时**抛出 `RejectedExecutionException`**（而不是静默丢弃），由 `BufferingBatchHandler#submit` 逐条 `setError` 完成批次——批次不会消失，也不会让请求一直挂到超时。

---

## 五、攒批消费：`BufferingBatchHandler`

### 5.1 攒批的两个阶段：阈值 flush 与窗口 flush

`BufferingBatchHandler` 实现 `EventHandler<BatchEvent>`，是 Disruptor 的唯一消费者。**攒批存在两条互斥的触发路径，由 `batchWindowMillis` 决定走哪条**：

```java
// BufferingBatchHandler.onEvent（窗口关闭，默认）
buffer.add(request);
if (maxBatchSize > 0 && buffer.size() >= maxBatchSize) {
    flush();
} else if (endOfBatch) {
    flush();
}

// BufferingBatchHandler.onEvent（窗口开启）
buffer.add(request);
if (maxBatchSize > 0 && buffer.size() >= maxBatchSize) {
    flush();
} else if (buffer.size() == 1) {
    startWindowLocked();   // 只排一次任务，不响应 endOfBatch
}
```

**阈值 flush**（两条路径都有）：攒满 `maxBatchSize` 立即刷，不等窗口。

**窗口 flush**（仅窗口开启）：本批第一个事件到达时向共享调度线程排一个到期任务，期间继续累积，到期把整批一次性提交。到期判断在 `flushOnWindow()` 中，与 `onEvent` 通过 `bufferLock` 互斥。

### 5.2 为什么默认（无窗口）攒不起批

这是本模块最容易踩的坑，值得单独说清楚。`flush()` 的提交方式是：

```java
private void flush() {
    if (buffer.isEmpty()) return;
    List<BatchRequest<?>> batch = buffer;
    buffer = new ArrayList<>();
    executor.execute(() -> processBatch(batch));   // 异步提交
}
```

`executor.execute()` 是**异步**的——IO 在 bizExecutor 的 worker 上执行，**Disruptor 消费者线程不被阻塞**。而消费者处理一个事件只是 `buffer.add()`（纳秒级），于是它几乎总能追上空队列；Disruptor 的 `endOfBatch` 在「没有更多可用事件」时置位，消费者每拿到 1 个事件就落盘一批。

> 结论：**无窗口时，批大小 = 「消费者处理一个事件期间生产者发布了多少个新事件」，通常接近 1**，`maxBatchSize` 形同虚设。

消费者唯一被阻塞的时刻是 bizExecutor 满员、`CallerRunsPolicy` 让消费者线程自己执行 IO。只有那时 RingBuffer 才积压、批大小才冲高。因此 **`consumerSize` 与批大小直接对冲**：并行度调大 → 消费更快 → 积压更少 → 批更小。

实测（100ms IO、并发 100、JDK 17）：

| 配置 | 批大小 | 每请求 IO 成本 | QPS |
|---|---|---|---|
| `consumerSize=8`（合理并行度） | 2.8 | 106.5ms → 38.2ms（2.8x） | 632 |
| `consumerSize=1` | 35.2 | 106.5ms → 3.1ms（34x） | 458 |
| `consumerSize=8` + `batchWindowMillis=5` | 17.8 | 106.5ms → 6.1ms（17x） | 611 |

**窗口是唯一能同时拿到「并行度」与「大批」的手段**：它把刷出时机从「消费者追上空队列」改成「窗口到期」，批大小由配置保证。代价是每批最多多等一个窗口（单请求延迟上界 ≈ 窗口值），故窗口应取 IO 延迟的 1/4 ~ 1/2——实测 10ms IO 配 5ms 窗口，吞吐反而低于不开窗口。

### 5.3 窗口的并发设计

| 关注点 | 处理 |
|---|---|
| 消费者线程 vs 调度线程 | `bufferLock` 保护「add」与「取走并替换 list」；持锁时间仅数十纳秒，远小于窗口周期 |
| 调度线程数 | 全进程共享单个 `batch-window` daemon 线程；每个 handler 只登记一次并记录「登记在哪个实例上」，登记/注销/关停在同一把静态锁内完成——无窗口队列停机时不会误减其他队列的份额 |
| 任务被拒 | 调度器正在关闭时 `schedule()` 可能抛 `RejectedExecutionException`：捕获后立即刷出本批，不让请求挂到超时 |
| 停机顺序 | `DisruptorQueue.shutdown()` 中 `flushRemaining()` → `closeWindow()`，防止迟到任务重复提交 |
| 向后兼容 | `batchWindowMillis` 默认 0，此时 `endOfBatch` 分支与历史行为完全一致，`bufferLock` 无争用 |

`flush()` 将当前 buffer 原子替换为新 ArrayList，提交到 `bizExecutor`：

```java
private void flush() {
    if (buffer.isEmpty()) return;
    List<BatchRequest<?>> batch = buffer;
    buffer = new ArrayList<>();
    executor.execute(() -> processBatch(batch));
}
```

### 5.4 `processBatch`：批量执行

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
        failUncompleted(batch);   // 漏调 setResult 的请求必须有结论
        reportCapacity();         // 容量 Gauge 的常规采样点
    }
}
```

`@BatchMapping` 方法执行失败时，`handleBatchError` 遍历所有未完成的 request，调用 `setError(cause)` 完成每个请求的异常响应——**不会出现"一个请求失败，整批请求无响应挂起"**。

`failUncompleted` 补上另一半：批量方法**正常返回**但漏调 `setResult`（按 id 查 map 后漏 key 是最常见的形态）时，未完成的请求会以 `IllegalStateException` 完成并打一条 WARN（含完成数 / 总数）。没有这道兜底，这些请求只能静默挂满 30 秒超时，既没有正确响应也没有排查线索。

> 这条兜底同时明确了契约：批量方法必须在返回前完成批内所有请求。把 `setResult` 丢给其他线程异步执行后立即返回的用法不在支持范围内。

---

## 六、可观测性：9 个 Micrometer 指标

`BatchMetrics` SPI定义了 6 个记录方法，由 starter 的 `MicrometerBatchMetrics` 实现为 9 个 Micrometer 指标：

| 指标名 | 记录方法 | 埋点位置 | 类型 | Tag |
|--------|---------|---------|------|-----|
| `batch.enqueue.total` | `recordEnqueue` | `DisruptorQueue.enqueue`：停机直处理分支（false）+ 三种背压的**成功**路径；DROP 失败不记 enqueue（只记 drop） | Counter | `queue` |
| `batch.enqueue.rejected` | `recordEnqueue(success=false)` | 入队失败（`total` 中未成功的部分） | Counter | `queue` |
| `batch.enqueue.dropped` | `recordDrop` | `DisruptorQueue.enqueue` DROP 失败分支 | Counter | `queue` |
| `batch.enqueue.overflow` | `recordOverflow` | `DisruptorQueue.enqueue` THROW 失败分支 | Counter | `queue` |
| `batch.process.duration` | `recordBatchProcessed` | `BufferingBatchHandler.processBatch` finally | Timer | `queue` + `outcome=success/failure`（成败分序列，避免失败批次污染分位数） |
| `batch.process.batch.size` | `recordBatchProcessed` | 同上（DistributionSummary 维度） | DistributionSummary | `queue` |
| `batch.process.requests` | `recordRequestCompleted` | `BufferingBatchHandler.processBatch` 成功时 | Counter | `queue` |
| `batch.queue.remaining` | `reportQueueCapacity` | `DisruptorQueue` 构造预热一次 + `processBatch` finally（每批）+ DROP / THROW 失败补报 | Gauge | `queue` |
| `batch.queue.capacity` | `reportQueueCapacity` | 同上 | Gauge | `queue` |

所有指标的 `queue` Tag 值为 `batch:<ClassName>.<methodName>`（如 `batch:BatchUserController.batchGetUser`，由 `BatchScanner.resolveQueueName` 生成）。

两个 Gauge 采用「外部 map 占位 + 锁外注册」的写法（刻意不用 `ConcurrentHashMap.computeIfAbsent`——那会在持有 CHM bin 锁时调用 `MeterRegistry.register`，与 MeterRegistry 内部锁形成潜在死锁面）。

**采样策略**：容量是「水位」类指标，用批次频率采样即可，因此埋点放在 `processBatch` 结束处，而不是 `enqueue` 的生产者热路径上。`DisruptorQueue` 构造末尾会预热上报一次，把 Gauge 的首次注册（会进 `MeterRegistry`）留在启动阶段，而不是留给背压瞬间的生产者线程（可能是 EventLoop）。DROP / THROW 入队失败时会额外补报一次——队列满、批次可能不再产生，这正是容量指标最需要被看到的时刻。

代价是它不是实时读数：批次停止产生且没有入队失败时，Gauge 停在最后一次采样值。

`NoOpBatchMetrics`是默认实现，所有方法空操作——当 Micrometer 不在类路径时，零开销（实现类 `MicrometerBatchMetrics` 位于 `spring-boot-starter-web`，由 `SpringWebBatchAutoConfiguration` 在 `MeterRegistry` 存在时注入）。

---

## 七、边界与安全网

### 7.1 停机竞态窗口

`DisruptorQueue.enqueue` 的 `halted` 检查与 `shutdown` 之间存在纳秒级竞态窗口：

```java
// DisruptorQueue.java 注释
// 已知边界（保持零锁热路径）：halted 检查与 publish 之间存在纳秒级
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
| 可观测性 | 9 个指标（`BatchMetrics` SPI + starter 的 Micrometer 实现） | 无标准指标，需自行埋点 |
| 超时兜底 | `BatchRequest` 30s 默认超时 + `completed` 守卫 | 无标准超时机制 |
| 停机安全 | 3 步优雅停机（drain → flush → awaitTermination） | 无标准停机流程 |
| 与异步框架集成 | 原生集成 `DeferredResult` 异步处理路径 | 通常与 Spring MVC 异步独立 |
| 配置侵入性 | `@BatchMapping` 注解 + `BatchRequest` 子类 | 无标准化配置 |

**核心差异**：Spring 生态没有标准化的"请求透明聚合"方案。通常的做法是：

1. 业务代码中自行维护一个 `BlockingQueue<Request>`，用 `ScheduledExecutor` 定时刷或手动触发。
2. 使用 Spring Integration 或类似框架的聚合器（Aggregator），但需要额外引入消息中间件。
3. 批处理逻辑与业务代码耦合，队列满、超时、错误处理都需要手动管理。

`spring-web-batch` 把这一切下沉到框架层：**注解声明批量方法 → 框架自动攒批 → Disruptor 无锁入队 → 三级背压链 → 9 个指标可观测**。用户在业务代码中只需要关心"拿到一批请求，逐个 `setResult`"。

---

## 九、小结：批量模块的克制在哪里

回到引子的问题：`BatchInvoker` 如何在启动期替换原方法调用？Disruptor 如何无锁入队？三线程如何协作与背压？

1. **`BatchRegistry` + `BatchScanner` 启动期扫描** → 扫描 `@BatchMapping`，关联单请求方法，解析 `BatchRequest` 泛型类型，缓存 `BatchRequestMetaData` 到 `PathMappingContext`。
2. **`BatchInvoker` 方法体替换** → `InvokableHandlerMethod.setInvoker(batchInvoker)`（签名 `public void setInvoker(Invoker invoker)`），运行时反射创建 `BatchRequest` 实例并入队，原方法体完全不执行。
3. **`DisruptorQueue` 无锁入队** → `ProducerType.MULTI` CAS claim，`BatchEvent` 预分配零 GC，`normalizeRingBufferSize` 上下限保护，三种背压策略（BLOCK/DROP/THROW）。
4. **`BufferingBatchHandler` 攒批消费** → `endOfBatch` + `maxBatchSize` 双触发（`batchWindowMillis > 0` 时改为「窗口到期 + 攒满」），`bizExecutor` 零核心线程 + `SynchronousQueue` + `CallerRunsPolicy` 自然背压，`handleBatchError` / `failUncompleted` 逐个 `setError` 防挂起。
5. **三级背压链** → RingBuffer 满 → 生产者线程（默认 `default` 业务池 worker）阻塞 → TCP 反压（BLOCK 模式）；或 `tryPublishEvent` 失败 + `BatchOverflowException` 429（DROP/THROW 模式）。
6. **9 个指标埋点** → `BatchMetrics` SPI 定义 6 个记录方法（由 starter 的 `MicrometerBatchMetrics` 实现为 9 个指标，容量 Gauge 在启动预热 + 每批 + 背压失败时采样），`queue` Tag 值 `batch:<ClassName>.<methodName>`，`NoOpBatchMetrics` 默认零开销。
7. **安全网** → Step 1 的 drain 有 5s 硬上界、业务池拒绝时逐条 `setError`；批量方法漏完成请求由 `failUncompleted` 收尾；停机竞态窗口由 `DeferredResult` 30s 超时兜底；`BatchRequest.completed` 守卫防止重复回调；`BatchExceptionHandler` 捕获消费者异常；`BatchOverflowException` 映射 429。

这一层的克制体现在：**不把批处理留给业务代码，也不为批处理引入复杂的消息中间件。** LMAX Disruptor 提供无锁的环形缓冲区，`ProducerType.MULTI` 支持多生产者并发入队，`SynchronousQueue` + `CallerRunsPolicy` 在零额外线程的基础上实现了自然背压——**默认（`batchWindowMillis=0`）下没有调度线程、没有定时器、没有额外的队列线程**；只有显式开启窗口才引入一个全进程共享的 `batch-window` daemon 线程（按队列登记、无使用者时关停）。三个线程层次（handler 执行线程 / Disruptor 消费者 / bizExecutor 工作者）形成了一条干净的反压链。`@BatchMapping` 方法体替换语义让用户无需修改单请求方法——框架层面透明的"请求聚合"，是 [01 篇](01-design-philosophy.md) 原则 2（单请求方法保持不变）在批处理场景的落地。

---

> **下一篇**：[14 · starter 自动配置](14-starter-autoconfig.md)——`spring-boot-starter-web`：条件装配、`@Configuration` 链、自动配置覆盖与扩展点。
