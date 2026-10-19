> [English](en/batch.md) | 中文

# Batch 批处理模块

`spring-web-batch` 将高并发的独立请求透明聚合为批量，由批量处理方法一次处理完所有请求，适合 IO 密集型场景（批量 DB 查询、批量 RPC 调用）。

基于 **LMAX Disruptor** 实现无锁环形缓冲区，提供背压策略、等待策略、线程池隔离等生产级特性。

---

## 目录

- [快速开始](#快速开始)
- [@BatchMapping 注解](#batchmapping-注解)
- [架构设计](#架构设计)
  - [攒批机制与批大小](#攒批机制与批大小)
- [可观测性](#可观测性)
- [配置参考](#配置参考)
- [注意事项](#注意事项)

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-batch</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

`spring-boot-starter-web` 中 `spring-web-batch` 声明为 `<scope>provided</scope>`，即使使用了 starter 也需要单独添加。

### 2. 编写 Controller

**第 1 步：定义单请求端点**

将普通 Controller 方法的返回值类型改为 `BatchRequest<R>`（即 `DeferredResult` 的子类），方法体不会实际执行：

```java
@RestController
@RequestMapping("/api")
public class UserController {

    @GetMapping("/users/{id}")
    public BatchRequest<UserResp> getUser(@PathVariable Long id,
                                          @RequestParam(defaultValue = "zh") String lang) {
        // 方法体不会执行，由 BatchInvoker 接管
        return null;
    }
}
```

**第 2 步：定义 BatchRequest 子类**

构造函数参数类型必须与单方法参数类型**按序一致**：

```java
public class GetUserRequest extends BatchRequest<UserResp> {

    private final Long id;
    private final String lang;

    public GetUserRequest(Long id, String lang) {
        this.id = id;
        this.lang = lang;
    }

    public Long getId() { return id; }
    public String getLang() { return lang; }
}
```

**第 3 步：编写批量处理方法**

在同一个 Controller 中编写 `@BatchMapping` 方法：

```java
@RestController
@RequestMapping("/api")
public class UserController {

    // ... 单请求端点

    @BatchMapping(method = "getUser")
    public void batchGetUser(List<GetUserRequest> batch) {
        Map<Long, UserResp> dbResult = userService.batchQuery(batch);
        for (GetUserRequest req : batch) {
            UserResp resp = dbResult.get(req.getId());
            req.setResult(resp != null ? resp : new UserResp());
        }
    }
}
```

- `@BatchMapping(method = "getUser")` 关联到单请求端点 `getUser`
- **唯一参数**必须为 `List<? extends BatchRequest<?>>`，即待处理的批量请求
- 处理完成后调用 `BatchRequest.setResult()` 完成每个请求的 HTTP 响应

---

## @BatchMapping 注解

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BatchMapping {

    int ringBufferSize() default 4096;
    WaitStrategy waitStrategy() default WaitStrategy.BLOCKING;
    Backpressure backpressure() default Backpressure.BLOCK;
    String method() default "";
    int maxBatchSize() default 100;
    int batchWindowMillis() default 0;
    int consumerSize() default -1;

    enum WaitStrategy { YIELDING, BLOCKING, SLEEPING, BUSY_SPIN }
    enum Backpressure { BLOCK, DROP, THROW }
}
```

### 参数说明

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `ringBufferSize` | 4096 | Disruptor 环形缓冲区容量，自动归一化为 2 的 N 次幂 |
| `waitStrategy` | BLOCKING | 消费者等待策略，见下方表格 |
| `backpressure` | BLOCK | 背压策略，见下方表格 |
| `method` | "" | 被关联的单请求方法名，默认与批量处理方法同名 |
| `maxBatchSize` | 100 | 单次批处理最大请求数，达到该值触发批量处理 |
| `batchWindowMillis` | 0 | 攒批时间窗口（毫秒），0 = 关闭。**决定批大小能否达到 `maxBatchSize`，IO 越慢越应开启**，见[攒批机制与批大小](#攒批机制与批大小) |
| `consumerSize` | -1 | 业务线程池（bizExecutor）最大并发处理线程数，默认 CPU 核数；Disruptor 消费者线程独立，**不占该配额**。开启虚拟线程（JDK 21+）后执行线程改为虚拟线程，配额语义不变；此时建议保持 `waitStrategy=BLOCKING`——`BUSY_SPIN`/`YIELDING` 不会卸载虚拟线程，会长期占住载体线程 |

### 背压策略

| 策略 | 行为 | 客户端响应 |
|------|------|-----------|
| `BLOCK` | 生产者线程（业务池或 EventLoop）阻塞直到 RingBuffer 有空间 | 请求挂起，等待处理 |
| `DROP` | 丢弃请求，`BatchRequest` 收到 `BatchOverflowException` | **429 Too Many Requests** |
| `THROW` | 同步抛出 `BatchOverflowException` | **429 Too Many Requests** |

### 等待策略

| 策略 | 适用场景 | CPU 消耗 | 延迟 |
|------|---------|---------|------|
| `BLOCKING` | 空闲时段较多，省 CPU | 低 | 高 |
| `YIELDING` | 低延迟、中等吞吐 | 中 | 中 |
| `SLEEPING` | 省 CPU 但需要一定响应速度 | 低 | 中高 |
| `BUSY_SPIN` | 极致低延迟、高吞吐 | 高（始终占满 CPU） | 低 |

### 线程模型

`@BatchMapping` 为每个方法创建独立的 Disruptor 队列，内部线程模型：

```
调度线程（生产者：默认业务池；开虚拟线程时为 EventLoop）
    ↓ 入队 RingBuffer
Disruptor 消费者线程（1 个）
    ↓ 攒批 → 提交
bizExecutor 线程池（0 ~ consumerSize 个；开虚拟线程时为虚拟线程）
    ↓ 执行
@BatchMapping 方法
```

- **生产者**：由默认线程模型决定——不开启虚拟线程时是 `default` 业务线程池（遵循 `pool.default-execute-mode`）；**开启虚拟线程（JDK 21+）时改为 EventLoop 直投**（`BatchRegistry` 会把 default 池置空，避免多一次线程切换）。两种情况下都只是调用 `BatchInvoker` 入队（微秒级、不阻塞），无需 `@RunInPool`
- **消费者**：Disruptor 单消费者线程不断从 RingBuffer 拉取事件
- **业务线程池**：0 核心线程、`SynchronousQueue`、`CallerRunsPolicy`——空闲时零线程占用，满负荷时消费者线程自行执行形成背压。开启虚拟线程（`spring.threads.virtual.enabled=true`，JDK 21+）时执行线程改为**虚拟线程**（上限与背压语义不变：仍受 `consumerSize` 限制），阻塞只挂起虚拟线程而不占用平台线程

### 攒批机制与批大小

**批处理模块的收益完全取决于批大小**：一次批量 IO 替代 N 次单条 IO，N 越大，消除的 IO 越多。但默认配置下批大小长期停留在 1~3，`maxBatchSize=100` 形同虚设。原因在于攒批的触发方式：

`BufferingBatchHandler` 把每批请求提交到 bizExecutor 时走的是 `executor.execute()`——**异步提交，IO 在业务线程上执行，不阻塞 Disruptor 消费者线程**。而消费者处理一个事件只是把请求加入 buffer（纳秒级），因此它几乎总能追上空队列；Disruptor 的 `endOfBatch` 信号在「没有更多可用事件」时置位，于是消费者每拿到 1 个事件就落盘一批。

> 换句话说：**批大小不是由 `maxBatchSize` 决定的，而是由「消费者处理一个事件期间生产者发布了多少个新事件」决定的。** 在 IO 主导的场景里，这个数通常接近 1。

消费者唯一会被阻塞的时刻，是 bizExecutor 满员、`CallerRunsPolicy` 让消费者线程自己执行 IO。只有那时 RingBuffer 才会积压、批大小才会冲高。这带来一个反直觉的后果：**`consumerSize` 与批大小直接对冲**——并行度调大，消费更快，积压更少，批大小更小。

| 配置（100ms IO，并发 100，实测） | 批大小 | 每请求 IO 成本 | QPS |
|---|---|---|---|
| `consumerSize=8`（合理并行度） | 2.8 | 106.5ms → 38.2ms（2.8x） | 632 |
| `consumerSize=1`（牺牲并行度） | 35.2 | 106.5ms → 3.1ms（34x） | 458 |
| `consumerSize=8` + `batchWindowMillis=5` | 17.8 | 106.5ms → 6.1ms（17x） | 611 |

**解法是开启时间窗口。** `batchWindowMillis > 0` 时，本批第一个事件到达即启动窗口，期间继续累积，直到「攒满 `maxBatchSize`」或「窗口到期」才刷出。这样批大小由配置保证，不再依赖业务池偶然饱和；`consumerSize` 用于复用多核（取核数级别即可，不应超配），两者不再互斥。

窗口的代价是每个批次最多多等一个窗口，即单请求延迟上界约等于窗口值。经验取值：

| IO 延迟 | 建议窗口 | 说明 |
|---------|---------|------|
| < 5ms | 0（关闭） | 批量收益不足以覆盖等待成本 |
| 5 ~ 20ms | IO 延迟的 1/4 | 收益大于等待成本 |
| > 20ms | IO 延迟的 1/2 | IO 越慢，值得等得越久 |

> 实测反例：10ms IO 配 5ms 窗口，吞吐反而低于不开窗口——等待成本占了 IO 时间的一半。窗口应显著小于 IO 延迟。

---

## 架构设计

### 请求流转

<p align="center">
<img src="images/batch-arch.svg" alt="Disruptor 批处理架构——三线程模型"/>
</p>

### 核心组件

| 类 | 职责 |
|------|------|
| `BatchRequest<R>` | 继承 `DeferredResult<R>`，用户扩展的请求基类，通过构造参数位置匹配接收方法参数 |
| `@BatchMapping` | 标注在批量处理方法上，关联对应的单请求方法 |
| `BatchRegistry` | 管理所有 RingBuffer 生命周期（初始化/销毁），安装 `BatchInvoker` |
| `BatchInvoker` | 替换原 Controller 方法调用，直接根据构造参数创建 `BatchRequest` 实例并入队 |
| `DisruptorQueue` | 封装 LMAX Disruptor，提供入队/背压/关闭等操作 |
| `BufferingBatchHandler` | Disruptor 消费者，攒批达到阈值后提交到业务线程池 |

### 为什么用 Disruptor

- **无锁并发**：`ProducerType.MULTI` 支持多个 EventLoop 线程同时入队，无锁竞争
- **预分配事件槽**：`BatchEvent` 在 RingBuffer 中预创建，减少 GC 压力
- **endOfBatch 信号**：Disruptor 在批量发布结束时通知消费者，减少攒批延迟
- **背压自然**：生产者写入 RingBuffer 时，RingBuffer 满 → 生产者线程阻塞（业务池线程，或开虚拟线程时的 EventLoop）→ 请求等待、连接反压

---

## 可观测性

### Metrics 指标

`BatchMetrics` 是指标 SPI，框架默认实现为 `NoOpBatchMetrics`（全 no-op）。当
`io.micrometer.core.instrument.MeterRegistry` 在类路径上时（如通过 `spring-boot-starter-actuator` 引入），
**starter**（`SpringWebBatchAutoConfiguration` 内嵌的 `MicrometerBatchMetricsConfiguration`）会注册
`MicrometerBatchMetrics` 并经 `BatchRegistry#setMetrics(...)` 注入，产出以下指标：

| 指标名 | 类型 | Tag | 说明 |
|--------|------|-----|------|
| `batch.enqueue.total` | Counter | `queue` | 总入队尝试数 |
| `batch.enqueue.rejected` | Counter | `queue` | 入队失败数（`total` 中未成功的部分） |
| `batch.enqueue.dropped` | Counter | `queue` | 背压丢弃数 |
| `batch.enqueue.overflow` | Counter | `queue` | 溢出异常数 |
| `batch.process.duration` | Timer | `queue` + `outcome=success/failure` | 批处理耗时（成败分序列，避免失败批次污染分位数） |
| `batch.process.batch.size` | DistributionSummary | `queue` | 批大小分布 |
| `batch.process.requests` | Counter | `queue` | 请求完成数 |
| `batch.queue.remaining` | Gauge | `queue` | RingBuffer 剩余容量 |
| `batch.queue.capacity` | Gauge | `queue` | RingBuffer 总容量 |

所有指标均带有 `queue` Tag，值为 `batch:<ClassName>.<methodName>`（由 `BatchScanner` 生成）。

### 监控建议

- **`batch.queue.remaining`**：配置告警，低于 bufferSize 的 10% 时通知，表示背压接近极限
- **`batch.process.duration`**：关注 p99 延迟（按 `outcome` 分开看），持续升高说明业务线程池饱和
- **`batch.enqueue.dropped`** / **`batch.enqueue.overflow`**：出现丢弃说明 RingBuffer 容量或消费者速度不足，需调整 `ringBufferSize` 或 `consumerSize`

---

## 配置参考

### 参数调优

| 场景 | 推荐配置 | 说明 |
|------|---------|------|
| 高吞吐、可接受延迟 | `ringBufferSize=16384, maxBatchSize=500, batchWindowMillis=10` | 大 buffer 降低丢包，大 batch 提升吞吐；窗口保证批大小真的能达到 `maxBatchSize` |
| 低延迟敏感 | `ringBufferSize=1024, maxBatchSize=50, waitStrategy=BUSY_SPIN` | 小 buffer 减少排队，BUSY_SPIN 降低延迟。**开启 `spring.threads.virtual.enabled` 时改用 `BLOCKING`**：`BUSY_SPIN`/`YIELDING` 在虚拟线程上不会卸载，会长期占住载体线程 |
| 资源受限（1c1g） | `ringBufferSize=4096, consumerSize=1` | 单消费者线程，省资源。批大小会自然偏大（消费慢→积压），无需再开窗口 |
| 批量 DB 查询 | `maxBatchSize=200, batchWindowMillis=5` | 多数数据库 IN 查询 200 条以内性能最佳；窗口让批大小趋近 200 |
| 批量 RPC 调用 | `maxBatchSize=50, consumerSize=4, batchWindowMillis=10` | 多线程并行 RPC，单批不宜过大；窗口按 RPC 延迟取 1/4~1/2 |
| 慢 IO（>50ms） | `batchWindowMillis=25` | IO 越慢越值得等，此档实测每请求 IO 成本可降 30x 以上 |

### 消费者线程池

`consumerSize` 控制最大并发处理线程数。默认 `-1` 表示 CPU 核数。

线程池使用 `SynchronousQueue` + `CallerRunsPolicy`：

- 空闲时零线程，不占用资源
- 达到上限时，消费者线程自行执行，形成背压
- 无需手动调优核心线程数

**该值用于复用多核，取核数级别即可，不应超配。** 调大它并不会带来更高的批处理吞吐上限之外的收益——反而会减小批大小（消费更快 → 积压更少），削弱批量消除 IO 的效果。需要更大批量时应开 `batchWindowMillis`，而不是调大 `consumerSize`。

> **虚拟线程**：`spring.threads.virtual.enabled=true`（JDK 21+）时，业务池的**线程**改为虚拟线程（线程名 `batch-virtual-*`）——`consumerSize`、`SynchronousQueue`、`CallerRunsPolicy` 语义全部不变，池满时仍由消费者线程自执行形成背压；批量方法内的阻塞 IO 只挂起虚拟线程，不再占用平台线程。

---

## 注意事项

### 方法体替换语义

`@BatchMapping` 安装后，被关联的 `@RequestMapping` 方法体**不会执行**。`BatchInvoker` 直接根据构造函数参数创建 `BatchRequest` 实例并入队。如果单请求方法中有日志、校验等逻辑，它们不会运行——这些应在 `@BatchMapping` 方法中处理。

### 超时处理

`BatchRequest` 默认超时 30 秒，超时后 `DeferredResult` 自动触发超时回调。可通过构造函数传入自定义超时：

```java
public class GetUserRequest extends BatchRequest<UserResp> {
    public GetUserRequest(Long id, String lang) {
        super(10000L); // 10 秒超时
        this.id = id;
        this.lang = lang;
    }
}
```

### 错误处理

| 异常场景 | 处理方式 | 客户端响应 |
|---------|---------|-----------|
| RingBuffer 满，背压 BLOCK | 生产者线程阻塞等待 | 请求挂起 |
| RingBuffer 满，背压 DROP | 请求设置 `BatchOverflowException` | 429 |
| RingBuffer 满，背压 THROW | 同步抛出异常 | 429 |
| 批量处理异常 | 遍历所有未完成的 request 调用 `setError(e)` | 500 |
| 请求超时 | `DeferredResult` 超时回调 | 503 |

### 调试建议

- 启用 `io.springperf.web.batch` 的 DEBUG 日志可观察入队和批处理过程
- 通过 Micrometer 指标监控队列深度和处理延迟，避免 RingBuffer 溢出
