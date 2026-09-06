# 19 · 关键设计决策记录（ADR）

> [← 返回索引](00-README.md) | 上一篇：[18 · 内部 SPI 发现与调用机制](18-spi-extension.md) | 下一篇：[20 · 总结与路线图](20-summary-roadmap.md)

---

## 引子：把"为什么"沉淀下来

前 18 篇讲了框架"是什么"（架构）、"怎么做到的"（代码）、"快多少"（数据）。本篇是"决策篇"——用 ADR（Architecture Decision Record）风格，把 10 条关键取舍的**背景 / 决策 / 后果**沉淀下来。

写作前提有两条：① 07–13 篇已完成，每条决策都有可追溯到 `file:line` 的代码证据；② 决策依据来自对应类的源码与注释，辅以既定设计记录。本篇不重复论证"怎么做"的细节——那是 [15 篇](15-performance-optimizations.md)（性能优化三元组）、[16 篇](16-code-spotlights.md)（代码聚光灯）、[18 篇](18-spi-extension.md)（SPI 机制）的职责。本篇只回答**"面临什么取舍、选了什么、代价是什么、何时该重新审视"**。

### 阅读约定

每条 ADR 统一四段：

- **背景**：设计时面临的真实张力（通常是性能 vs 兼容、零开销 vs 安全、显式 vs 隐式之间的取舍）。
- **决策**：选了什么方案，附 `file:line` 证据锚点。不展开实现——实现见对应篇章。
- **后果**：收益、代价、边界条件三条。边界条件是最重要的部分——它标明"这条决策在什么前提下成立、何时该推翻"。
- **关联**：指向论证细节的前文章节，避免重复。

ADR 编号 1–10，与 [00-README](00-README.md) 大纲及 [18 篇](18-spi-extension.md) 已引用的 ADR 2 / 9 / 10 一致。

---

## ADR 1：方法调用三路决策——ASM 字节码 + MethodHandle 兜底 + native-image 降级

**状态**：已采纳

### 背景

方法调用是请求路径上最高频的操作。Spring MVC 的 `InvocurableHandlerMethod` 走 `Method.invoke`，每次调用付 access check + 可变参数装箱 + 类型校验，约 200ns，且 `Method.invoke` 是 native 方法边界，JIT 无法跨边界内联整个调用链。

框架可选三条路：① 全用 `MethodHandle.invokeExact`（~30ns，统一且简单，但仍有虚调用分发）；② 对标注 `@Optimize` 的方法用 ASM 运行时生成 `INVOKEVIRTUAL` 字节码（~10ns，最快，但运行时生成字节码有 Metaspace 成本，且 GraalVM native-image 不允许运行时字节码生成）；③ 全用 ASM（最快但 Metaspace 不可控，且阻塞 native-image 兼容）。

### 决策

`InvokableHandlerMethod` 构造器走**三路决策**（`InvokableHandlerMethod.java` `initInvoker()`）：

- `@Optimize` 标注且非 native-image → `createFastInvoker()` → `FastInvokerGenerator` 生成 `INVOKEVIRTUAL` 字节码类；
- 否则 → `createCommonInvoker()` → `MethodHandles.lookup().unreflect(method).bindTo(bean)` + `MethodHandleInvoker`；
- ASM 生成失败 `catch (Throwable)` 降级到 MethodHandle，保证可用性。

`IN_NATIVE_IMAGE` 守卫（ `System.getProperty("org.graalvm.nativeimage.imagecode") != null`）检测 GraalVM 环境，native-image 下强制走 MethodHandle，绕开运行时字节码生成的限制。

### 后果

- **收益**：`@Optimize` 方法 ~10ns、默认 ~30ns，均远低于 `Method.invoke` ~200ns；ASM 生成的是标准 Java 字节码，JIT 可完全内联；MethodHandle 路径也能被 JIT 内联为直接调用。Metaspace 实测 39MB（[17 篇](17-benchmark-data.md) 三节），仍为所有容器最低。
- **代价**：`@Optimize` 需用户显式标注（非默认）；ASM 字节码类增加 Metaspace（但按方法缓存，`invokerClassCache` 共享，同一方法只生成一次）；native-image 下 ASM 优势失效，退回 MethodHandle。
- **边界**：native-image 环境（GraalVM）下 `IN_NATIVE_IMAGE=true`，强制降级；ASM 生成失败的 catch 降级路径保证即使字节码生成异常也不影响启动。重新审视条件：若 native-image 成为主流部署目标，需为 `@Optimize` 方法提供编译期字节码生成替代运行时生成。

**关联**：[15 篇](15-performance-optimizations.md) 优化 2、[16 篇](16-code-spotlights.md) 聚光灯 2、[09 篇](09-invoker-bytecode.md)。

---

## ADR 2：默认 default 业务池为基线 + `@RunInPool(EVENTLOOP)`/`eventloop` 模式可选切回 EventLoop

**状态**：已采纳

### 背景

Netty 的 EventLoop 单线程模型天然适合 I/O 密集型场景，但若业务逻辑阻塞 EventLoop 线程，会拖垮该 EventLoop 上的所有连接。两条路：① 全 EventLoop 模式（WebFlux 路线），要求业务全部非阻塞——纯但苛刻，阻塞型业务（JDBC、同步 RPC）无处安放；② 默认线程池模式（Tomcat 路线），每请求从池取线程——兼容阻塞但每请求一次线程切换（~5–10μs）。

### 决策

以 **default 业务线程池为基线**：无 `@RunInPool` 注解的方法按 `pool.default-execute-mode`（缺省 `"default"`，`PropertiesConstant.java`）解析到 default 池——`determinePool` 返回非 `null`，`executor.execute(...)` 切换到业务线程。仅当显式标注 `@RunInPool(EVENTLOOP)` 或配置 `pool.default-execute-mode=eventloop` 时，`determinePool` 才返回 `null`（`BizPoolRegistry.java`），直接在 EventLoop 调 `handleWithFilter`。`DispatcherHandler.handleWithMappingResult`（ 方法定义）按 `bizPoolRegistry.determinePool(req, mappingResult)` 返回值分派：非 `null` → 切业务池，`null` → EventLoop 同步。`determinePool` 的 `req+mappingResult` 重载（`BizPoolRegistry.java`）委托 `MappingHandlerMethod` 重载，三级解析：① `@RunInPool(EVENTLOOP)` → `null`；② `@RunInPool("name")` → 命名池；③ 无注解 → `defaultExecuteMode`，`null`/`"eventloop"` → `null`，否则 → 该名池（缺省 default 池）。默认池配置 `core=50, max=200, keepAlive=60s`（`BizPoolRegistry.java`，/ 两处 `ThreadPoolExecutor` 构造），`@RunInPool("name")` 可指定命名池。基准测试通过 `application.properties:5` 显式设 `pool.default-execute-mode=eventloop` 跑在 EventLoop 模式。

### 后果

- **收益**：默认 default 池兼容阻塞型业务（JDBC、同步 RPC），不拖垮 EventLoop；需要极致低延迟时可经 `@RunInPool(EVENTLOOP)` 或 `pool.default-execute-mode=eventloop` 切回 EventLoop——基准测试即用 eventloop 模式取得 `json` 64 线程反超 16 线程（[15 篇](15-performance-optimizations.md) 优化 6）。
- **代价**：默认每请求一次线程切换（~5–10μs）+ `executor.execute` 提交开销；切回 EventLoop 模式需用户自行保证业务非阻塞——误把阻塞逻辑放 EventLoop 会拖慢该 EventLoop 上所有连接（与 WebFlux 同样的约束）。
- **边界**：SSE/流式发送方必须在 EventLoop 串行化 `writeAndFlush`（无锁 drain loop 前提），`@RunInPool` 不适用于流式发送方——流式场景的生产者即便在业务线程，最终 drain 仍 `eventLoop.execute(this::drain)` 回到 EventLoop（[16 篇](16-code-spotlights.md) 聚光灯 4）。重新审视条件：若默认池 50/200 的配额在特定负载下成为瓶颈，需调参而非改模型；若全栈非阻塞且追求零切换，可整体切 eventloop 模式。

**关联**：[04 篇](04-request-pipeline.md) 二节、[15 篇](15-performance-optimizations.md) 优化 6、[16 篇](16-code-spotlights.md) 聚光灯 6。[18 篇](18-spi-extension.md) 已引用本条为 ADR 2。

---

## ADR 3：`int index` + `Object[]` 数组索引 vs `ConcurrentHashMap`

**状态**：已采纳

### 背景

运行时需频繁读取每方法、每类、每请求的元数据（参数解析器、返回值解析器、拦截器、异常处理器等）。Spring MVC 用 `ConcurrentHashMap<String, Object>` 或 `AnnotatedElementUtils` 的缓存注解查找，每次访问 `hashCode()` → 桶定位 → `equals()` → `volatile` 读，约 30–50ns。每请求多次访问累积成热点。

### 决策

`MappingCacheKey<T>` 在编译期通过 `AtomicInteger` 全局计数器分配唯一 `int index`（`MappingCacheKey.java` `createMethodCacheKey`），作为静态常量持有。运行时 `handlerMethod.get(cacheKey)` 等价于 `cache[key.index]`——`aload` + `iaload` 两条字节码指令，约 2–3ns。`MappingHandlerMethod` 的 `methodCache`/`classCache` 实例字段指向 static map 里的 `Object[]`（`MappingHandlerMethod.java` static `Map<Method, Object[]>` / `Map<Class<?>, Object[]>`； `getCache` 按 Method/Class 维度 `computeIfAbsent` 取数组，`get`/`set` 走 `cache[key.index]`）。

### 后果

- **收益**：约 10 倍于 `ConcurrentHashMap`；零哈希冲突、零桶遍历（`int` 索引直取 `cache[key.index]`）；泛型 `MappingCacheKey<T>` 保证类型安全免 `cast`。
- **代价**：`int index` 在编译期分配且不可回收（`MappingCacheKey` 是静态常量，生命周期 = JVM 生命周期）；`methodCache`/`classCache` 数组按 `key.index + 1` 懒分配，越界时 `Arrays.copyOf` 扩容（`MappingHandlerMethod.java`，`synchronized` 双检），无固定上限——槽位随已注册键增长，未用槽位仅浪费单个对象引用。
- **边界**：仅适用于"已知有限且编译期确定"的属性集。运行时动态增删的属性仍需 Map/List——框架核心属性全走索引，用户扩展走 SPI 的 List/单字段/Map（见 [18 篇](18-spi-extension.md) 四种发现模式）。重新审视条件：若未来出现高频动态属性，该属性不应进 `methodCache`/`classCache`，而应走请求级 Map。

**关联**：[15 篇](15-performance-optimizations.md) 优化 1/4、[16 篇](16-code-spotlights.md) 聚光灯 1/5、[05 篇](05-request-pipeline.md) 四节。

---

## ADR 4：同包同名覆盖 vs Spring Bean 层 `@Primary`/`@ConditionalOnMissingBean`

**状态**：已采纳

### 背景

`spring-web-servlet` 与 `spring-web-mvc-support` 模块要替换 `spring-web` core 的 `DispatcherHandler`、`InterceptorRegistry`、`WebFilterRegistry`、`HttpBodyCodecInterceptorRegistry` 等 Registry 实现（support 版本增加了 Session flush、Servlet 适配、`WebMvcConfigurer` 翻译等能力）。两条路：① Spring Bean 层覆盖——`@Primary` 或 `@ConditionalOnMissingBean` 让 support 的 Bean 胜出；② 新 Java 包 + `WebComponent` 同名覆盖机制。

Bean 层覆盖有两个硬约束：其一，core 的 Registry 是 `WebComponent`（走 `WebContext.registerWebComponent` 注册时序），不是普通 Bean，Spring `@ConditionalOnMissingBean` 管不到 `WebComponent` 的注册时序；其二，Spring Boot 6.0 默认禁用 bean overriding（`spring.main.allow-bean-definition-overriding=false`），同名 Bean 直接报错。

### 决策

`SupportXxx` 放在 support 子包（**新 Java 包**），`extends` core 的 `XxxRegistry`，重写两点：① `getComponentName()` 返回**父类简单名**（`SupportDispatcherHandler.java` 返回 `DispatcherHandler.class.getSimpleName()` = "DispatcherHandler"，与 core 默认值同名）；② `getOrder()` 返回更高优先级（ `Ordered.LOWEST_PRECEDENCE - 30000`，比 core 默认 `LOWEST_PRECEDENCE - 10000` 更小 = 更高优先级）。

注册流程：core 的 `DispatcherHandler` 由 `SpringWebAutoConfiguration.java` 注册为 Bean，support 的 `SupportDispatcherHandler` 由 `SpringWebServletAutoConfiguration.java` 注册为 Bean。`WebContext` 初始化时 `getBeansOfType(DispatcherHandler.class)` 扫到两个子类型，`getComponentName` 同为 "DispatcherHandler" → `WebComponentContainer.registerWebComponent` 同名冲突 →  `AnnotationAwareOrderComparator.sort` 选 order 更小者为胜（support 胜），败者（core）`destroy`。同样模式覆盖 `SupportInterceptorRegistry`/`SupportWebFilterRegistry`/`SupportHttpBodyCodecInterceptorRegistry`。

### 后果

- **收益**：support 顶替 core 不依赖 Spring Bean overriding（绕开 Spring Boot 6.0 禁用约束），也不依赖 `@ConditionalOnMissingBean`（绕开 `WebComponent` 注册时序问题）；同名覆盖语义清晰，日志与冲突解析可辨。
- **代价**：同包同名机制是隐式的——不读源码难以发现"support 引入后 core 的 DispatcherHandler 被销毁"（[18 篇](18-spi-extension.md) 专章澄清此机制）；support 引入时 core 的 DispatcherHandler 被销毁，若用户误注了一个 core `DispatcherHandler` 的子类 Bean，可能引发二次冲突。
- **边界**：仅 Registry 类（`WebComponent` 子类型）适用此机制，普通 Bean 仍走 Spring `@Conditional`。重新审视条件：若未来 Spring Boot 完全禁用 bean overriding 且 `WebComponent` 机制被废弃，需另寻覆盖路径——但当前机制独立于 Spring Bean 生命周期。

**关联**：[12 篇](12-support-bridge.md)、[18 篇](18-spi-extension.md) 二节（`WebComponentContainer` 冲突解析）。

---

## ADR 5：批处理用 LMAX Disruptor vs 自研无锁队列

**状态**：已采纳

### 背景

`spring-web-batch` 模块需要高吞吐攒批队列：多生产者（多个 EventLoop 线程）→ 单消费者（Disruptor 消费者线程）→ 攒批 → 提交业务线程池。可选：① 自研 `MpscArrayQueue`（SSE 所用，见 ADR 6）；② LMAX Disruptor（成熟的 RingBuffer + SequenceBarrier + 批量发布机制）。

### 决策

batch 用 **LMAX Disruptor**。`BatchRegistry.java` 为每个 `@BatchMapping` 方法创建独立的 `DisruptorQueue`（封装 Disruptor `RingBuffer`），存入 `queues` Map（ `Map<String, DisruptorQueue>`）。`BufferingBatchHandler` 作为 Disruptor 的唯一消费者，`onEvent` 攒批后通过 `SynchronousQueue` + `CallerRunsPolicy` 线程池提交到 `bizExecutor`（[16 篇](16-code-spotlights.md) 聚光灯 9）。

SSE 场景则用自研 `MpscArrayQueue`（ADR 6）——两者场景不同：batch 是**跨连接攒批 + 批量提交**，Disruptor 的 `SequenceBarrier`/批量发布/`endOfBatch` 信号天然适配；SSE 是**单连接多消息流式**，`MpscArrayQueue` 轻量足够，无需 Disruptor 的栅栏机制。

### 后果

- **收益**：Disruptor `RingBuffer` 预分配 `BatchEvent`（零 GC 压力，[15 篇](15-performance-optimizations.md) 优化 5 对象池）；`endOfBatch` 信号降低攒批延迟（无需轮询 buffer size）；`Sequence` 实现无锁批量发布，多 EventLoop 并发入队无竞争。
- **代价**：引入 Disruptor 依赖（`com.lmax` 包）；每个 `@BatchMapping` 方法独占一个 Disruptor（一个消费者线程 + 一个 RingBuffer），批量方法多时线程与内存占用线性增长（`queues` Map 按方法数扩张）。
- **边界**：Disruptor 按 `@BatchMapping` 方法粒度而非全局共享，避免不同批量方法互相干扰（不同方法的批量触发条件、`maxBatchSize` 可能不同）。重新审视条件：若批量方法数极多（数十个），Disruptor 的线程占用需评估是否改为共享队列 + 路由——但当前按方法独占是延迟与隔离的最优解。

**关联**：[13 篇](13-batch-module.md) 五节、[16 篇](16-code-spotlights.md) 聚光灯 9。

---

## ADR 6：SSE 队列用有界 `MpscArrayQueue` + 背压自旋 vs 无界 `LinkedBlockingQueue`

**状态**：已采纳

### 背景

SSE/流式输出需生产者（EventLoop 或业务线程）→ 消费者（EventLoop writer）的通信队列。可选：① 无界队列（`LinkedBlockingQueue`）——永不阻塞生产者，但慢消费者下数据无限堆积导致 OOM；② 有界 `MpscArrayQueue` ——队列满则背压，但生产者需等待。

> **纠偏**：[00-README](00-README.md) 大纲原标题曾写作 `MpscUnboundedArrayQueue`，源码核对后实为**有界** `MpscArrayQueue`（`AbstractNettyStreamSender.java` import、 字段、 构造）。本条以源码为准。

### 决策

`AbstractNettyStreamSender` 用 `MpscArrayQueue` 固定容量 65536（ `MAX_QUEUED_EVENTS = 65536`， `new MpscArrayQueue<>(MAX_QUEUED_EVENTS)`）。队列满时 `enqueueWithBackpressure` 自旋 `Thread.yield()` / `LockSupport.parkNanos(1000)` 背压——不丢数据，生产者短暂挂起等待消费者腾出槽位。

### 后果

- **收益**：有界防 OOM（慢消费者不会让队列无限堆积导致堆溢出）；`MpscArrayQueue` 多生产者单消费者无锁 CAS 入队（[15 篇](15-performance-optimizations.md) 优化 7）；背压沿 `channel.isWritable()` + `WriteBufferWaterMark` 传导到 TCP 层，形成"消费者慢 → 队列满 → 生产者挂 → TCP 反压"的完整背压链。
- **代价**：65536 是经验值，极端高频 SSE（远超正常速率）可能触发背压自旋，`parkNanos(1000)` 约 1μs 级 CPU 自旋开销；队列满时生产者阻塞，若生产者在 EventLoop，会短暂拖慢该 EventLoop 上其他连接的 I/O。
- **边界**：65536 足够覆盖正常 SSE 速率（单连接每秒数千消息 × 消费者 drain 速度远快于生产），背压是"宁可慢不可崩"的取舍。重新审视条件：若出现 SSE 背压日志频繁，应先排查消费者 drain 是否被阻塞（如 `channel.isWritable()=false` 持续），而非直接调大容量——调大只是延缓 OOM，不解决慢消费根因。

**关联**：[11 篇](11-async-streaming.md) 四节、[15 篇](15-performance-optimizations.md) 优化 7、[16 篇](16-code-spotlights.md) 聚光灯 4、[17 篇](17-benchmark-data.md) SSE 场景归因。

---

## ADR 7：`complete` 边界事件的理论竞态可接受（不加锁）

**状态**：已采纳

### 背景

`AbstractNettyStreamSender` 的 `send()` 先 `preSendCheck()` 读 `completed` 字段（ `volatile boolean completed`），再 `enqueueWithBackpressure` + `scheduleDrain`。`complete()` 写 `this.completed = true` 后 `scheduleDrain()`。`wip`（ `AtomicInteger`）与 `completed` 是两个独立的无锁字段——`preSendCheck` 读 `completed` 与 `complete` 写 `completed` 之间无线性化。

理论竞态：`complete()` 置位 `completed` 后、drain 写出 `LastHttpContent` 终止帧（ `onAllDataWritten`）前，若生产者恰好 `send` 一条事件，`preSendCheck` 可能尚未读到 `completed=true`，该事件入队后可能被 drain 写到终止帧之后丢失。

### 决策

**不修**（不在 sender 层加锁）。理由：`complete()` 只来自两类调用方——① 用户代码主动 `complete()`；② 超时回调。用户主动 `complete()` 时，"边界后事件丢失"符合预期（用户已声明流结束，之后再 send 是用户逻辑错误，丢失合理）；超时回调 `complete()` 时，丢失边界事件可接受（超时本身就是异常终止，不保证严格一致）。加锁会在每条 `send`/`complete` 路径引入同步开销，违背无锁 drain loop 的设计前提（[15 篇](15-performance-optimizations.md) 优化 7）。

### 后果

- **收益**：`send`/`complete`/`scheduleDrain` 全无锁（ /  `scheduleDrain` 的 `wip.getAndIncrement()==0` 单线程 drain），这是 SSE 吞吐 7.72x tomcat（[17 篇](17-benchmark-data.md) 1.3 节）的性能前提；无锁字段读写让 drain loop 无等待。
- **代价**：理论竞态客观存在——最后一条事件可能在 `complete` 边界丢失（极低概率，需生产者 `send` 与 `complete` 精确并发且时序卡在 `preSendCheck` 与 `complete` 写 `completed` 之间）。
- **边界**：仅"最后一条边界事件"可能丢失，已入队数据由 drain loop 保证不丢——`afterDrain()` 的 `wip.compareAndSet(0,1)` + `queue.isEmpty()` 双重检查防止 drain 完成瞬间的残留数据丢失。若用户的 `complete` 与 `send` 严格有序（同一线程或存在 happens-before 关系），竞态不触发。重新审视条件：若未来支持多线程并发 `send` + `complete` 且要求严格不丢，需引入线性化（如 `volatile` + CAS 或 sender 层锁），但当前调用模型不触发，加锁是过早优化。

> **决策核心**："修方案前先读完整调用链，回答：① 并发是真的还是理论？② 后果是否违背用户预期？③ 改动是否破坏既有设计意图？三点不过一律不动"。此三点在本条均通过——竞态是理论窗口、丢失符合用户预期、加锁破坏无锁设计。

**关联**：[11 篇](11-async-streaming.md) 四节、[16 篇](16-code-spotlights.md) 聚光灯 4、[17 篇](17-benchmark-data.md) SSE 归因。

---

## ADR 8：缓存静态 Map 唯一性是有意设计（不 per-instance 化）

**状态**：已采纳

### 背景

`MappingHandlerMethod` 的 `methodCache`/`classCache` 用 `static Map<Method, Object[]>` / `static Map<Class<?>, Object[]>`（`MappingHandlerMethod.java`）。替代方案是 per-instance 数组——每个 `MappingHandlerMethod` 实例独占一份缓存数组。当 `@GetMapping` 一个方法被多个 path 共享时（同一方法映射多个路径），一个 `userMethod` 会对应多个 `PathMappingContext`。

### 决策

`static Map` 按 `Method`/`Class` 维度 `computeIfAbsent` 唯一（`MappingHandlerMethod.java` `classCacheInstanceMap.computeIfAbsent(userClass, ...)`、 `methodCacheInstanceMap.computeIfAbsent(userMethod, ...)`）。实例字段 `methodCache`/`classCache`（ `volatile Object[]`）指向 static map 里的同一个 `Object[]`——多 path 共享一份缓存。per-instance 数组会让多 path 各持一份缓存，破坏唯一性，内存浪费。

`HttpBodyCodecRegistry` 同模式：`static MappingCacheKey`（ `TARGET_TYPE_CACHE_KEY`、 `READ_BODY_CONVERTER_CACHE_KEY`、 `WRITE_NEGOTIATION_CACHE_KEY`）作为静态常量；registry 用 `liveNegotiationCaches`（ `Set<Map<String, NegotiationCacheEntry>>`）记录自己创建的 inner map，converter 变更时逐个 `clear`（避免泄漏）。 注释明言："存进 ctx 的 methodCache 数组槽……PathMappingContext 按方法共享实例，首次 computeIfAbsent 后 getCache 即字段读"。

### 后果

- **收益**：多 path 共享缓存省内存（N 个 path 一份而非 N 份）；static map 的 key 是 `userMethod`/`userClass`，天然按方法/类维度唯一，无需实例级维护。
- **代价**：static map 跨实例共享，扩容时 `synchronized(this)` + 双检 + `Arrays.copyOf` 存在"扩容丢失更新"的并发理论窗口（两个线程同时扩容，后写的 `setCache` 覆盖先写的数组，导致先写线程的更新丢失）——该窗口极小且扩容发生在新 `MappingCacheKey` 首次写入时（运行时罕见），已知接受、保留未修。
- **边界**：唯一性是"同一方法被多 path 共享"的有意设计，不应改成 per-instance；扩容并发 bug 是已知接受的低风险。重新审视条件：若扩容并发被证实在生产触发（表现为缓存 miss 后重复 computeIfAbsent，性能下降而非数据错误，因为 computeIfAbsent 保证最终一致），可引入更安全的扩容（如 CAS 替换数组引用），**但不改 per-instance**——per-instance 化会破坏多 path 共享的唯一性设计意图。

> **决策核心**：同 ADR 7——并发是理论窗口、后果不违背用户预期（computeIfAbsent 最终一致）、改动破坏既有设计意图。

**关联**：[16 篇](16-code-spotlights.md) 聚光灯 1、[18 篇](18-spi-extension.md) 四节（`HttpBodyConverter` 缓存）。

---

## ADR 9：fail-fast 启动校验 vs 运行时降级

**状态**：已采纳

### 背景

启动期可校验配置正确性：`@RunInPool` 的池名是否存在、每个参数是否有 `StaticArgumentResolverProvider` 可解析、`@BatchMapping` 的队列配置是否合法。两条路：① fail-fast 启动校验——配置错则启动失败，部署期暴露问题；② 运行时降级——启动通过，运行时遇到错才报（如首请求发现池名不存在才抛异常）。

### 决策

**fail-fast**。三处启动校验：

- `BizPoolRegistry.resolvePool` 池名不存在抛 `IllegalStateException`——`@RunInPool("name")` 的池名必须在 phase3 `getBeansOfType(ExecutorService)` 收的 `pools` Map 中；
- `ArgumentResolverRegistry.validateAllParametersResolvable` phase3 遍历所有 `@RequestMapping` 方法的参数，对每个参数调 provider 的 `supports()`，无 provider 可解析则抛 `IllegalStateException`；
- `@RunInPool` 缺池名、缺 arg resolver when `check-on-startup=true` 等均启动期校验（见 `development.md` fail-fast 条目）。

关键细节：`validateAllParametersResolvable` **只查 `supports()` 不创建实例**—— 注释明言 "does NOT create or cache any resolver, keeping memory footprint zero for endpoints that are never called"。真正的 `resolveArgument` 延迟到首请求 DCL `getMethodArgContexts` → `initStaticArgResolverSupport` 才创建并缓存。这是 fail-fast 与零闲置内存的折中。

### 后果

- **收益**：配置错误在部署期暴露，不会到生产运行时才崩（`@RunInPool` 写错池名、参数漏 resolver 都在启动失败）；启动确定性高，运行时无"配置性异常"。
- **代价**：校验有启动期成本（遍历所有 mapping 方法的参数），但一次性且只查 `supports()` 不创建实例，零闲置内存；启动时间略增（可接受）。
- **边界**：运行时仍有兜底 fail——`resolvePool` 在首请求懒解析 `@RunInPool` 注解时才触发（注解值理论上运行时可变，但池名校验逻辑复用 ，保证启动期与运行时一致）。重新审视条件：若某些扩展点无法在启动期确定（如动态注册的 mapping），fail-fast 退化为"动态注册时校验"（`WebComponentContainer.registerWebComponent` 按 state 追 phase，[18 篇](18-spi-extension.md) 七节），但不放弃校验本身。

**关联**：[18 篇](18-spi-extension.md) 六节（已引用为 ADR 9，含"校验可达≠预创建实例"纠偏）、`development.md`。

---

## ADR 10：显式 SPI 收口 vs Spring `@Conditional` 自动发现

**状态**：已采纳

### 背景

框架有 12 个扩展点（`WebFilter`/`HandlerInterceptor`/`HttpBodyConverter`/`ReturnValueResolver`/`StaticArgumentResolverProvider`/`BizPoolRegistry`/...）。发现与注册可选：① Spring `@Conditional` 自动发现（Spring Boot 惯例，`@ConditionalOnClass`/`@ConditionalOnMissingBean` 驱动）；② 显式 SPI 收口（框架自定义发现 + 排序 + 冲突解析）。

### 决策

**复用 Spring Bean 类型扫描，但收口在 `WebComponentContainer`**。不发明 `@WebComponent` 注解（[18 篇](18-spi-extension.md) 引子已纠偏"框架没有 `@WebComponent` 注解"），SPI 靠 Spring `@Component`/`@Configuration`+`@Bean` 成为 Bean，框架按类型取 Bean（`getBeansOfType` / `autoRegisterWebComponent` / `getWebComponentWithDefault`），排序靠 `Ordered`/`@Order` 经 `AnnotationAwareOrderComparator.sort` 统一处理（`WebComponentContainer.java` / ）。

`WebComponentContainer`（ `autoRegisterWebComponent` 登记、 `registerWebComponent` 冲突解析 + 动态注册按 state 追 phase）把"扫描 + 排序 + 固化"收口在一处。`RouterOptimizer` 是反例边界——`getOptimizerTemplate` 返回固定链（`MappingRegistry.java`），**不扫描 Bean**，扩展靠继承重写 `protected` 方法。这是 12 SPI 中唯一不靠类型发现的扩展点，因为路由优化器是"框架内部路由结构的选择策略"，暴露为 Bean 会破坏 `PathMappingContext` 的内聚。

### 后果

- **收益**：扩展方零学习成本（写普通 Spring Bean 即可被框架发现，无需框架自定义注解）；排序统一（`AnnotationAwareOrderComparator`，识别 `@Order`/`Ordered`/`@Priority`）；动态注册按 state 追 phase，Actuator 端点等后置注册安全；冲突解析收口（同名取 order 更小者，败者 destroy），避免双实例。
- **代价**：`WebComponent` 同名覆盖机制是隐式的（ADR 4），不读源码难发现；`@Conditional` 无法精细控制 `WebComponent` 注册时序——这就是 support 模块用同名覆盖而非 `@ConditionalOnMissingBean` 的原因（ADR 4）。
- **边界**：`RouterOptimizer` 不走类型发现（内部路由结构策略，暴露破坏内聚）；其余 11 个 SPI 全走类型发现。重新审视条件：若未来出现需要"条件化注册 WebComponent"的场景，不应在框架层引入 `@Conditional` 语义，而应在 `WebComponentContainer.registerWebComponent` 内增加条件判断——保持"框架收口"而非"委托 Spring 条件化"。

**关联**：[18 篇](18-spi-extension.md) 全篇（已引用本条为 ADR 10）、[01 篇](01-design-philosophy.md) 设计哲学（ 显式 SPI 原则）。

---

## 小结：十条决策的共同主线

| # | 决策 | 状态 | 代价 | 边界核心 |
|---|------|------|------|---------|
| 1 | ASM + MethodHandle + native-image 降级 | 已采纳 | Metaspace + `@Optimize` 显式标注 | native-image 退回 MethodHandle |
| 2 | 默认 default 池 + `@RunInPool`/eventloop 可选 | 已采纳 | 默认一次线程切换 | 流式发送方不适用 `@RunInPool` |
| 3 | `int index` + `Object[]` vs Map | 已采纳 | 索引不可回收 + 预分配槽位 | 仅编译期确定属性 |
| 4 | 同包同名覆盖 vs Bean 层 `@Primary` | 已采纳 | 机制隐式 | 仅 `WebComponent` 适用 |
| 5 | Disruptor vs 自研队列 | 已采纳 | 依赖 + 按方法独占 | 按方法粒度非全局 |
| 6 | 有界 `MpscArrayQueue` + 背压 | 已采纳 | 极端高频触发自旋 | 背压是"宁可慢不可崩" |
| 7 | `complete` 边界竞态不加锁 | 已采纳 | 最后一条边界事件理论丢失 | 仅调用方并发才触发 |
| 8 | 静态 Map 唯一性（不 per-instance） | 已采纳 | 扩容并发理论窗口 | 不改 per-instance |
| 9 | fail-fast 启动校验 | 已采纳 | 启动期遍历成本 | 动态注册时退化为注册时校验 |
| 10 | 显式 SPI 收口 vs `@Conditional` | 已采纳 | 同名覆盖机制隐式 | `RouterOptimizer` 不走类型发现 |

十条决策背后是三条一以贯之的主线：

1. **启动期确定性 + 运行时零开销**（ADR 1/3/9）：把"确定"从运行时前移到启动期——ASM 字节码生成、`int index` 分配、fail-fast 校验都在启动期完成，运行时只做数组索引与无锁 drain。这与 [16 篇](16-code-spotlights.md) 小结的"启动期做尽计算，运行时只做最必要的事"完全一致。
2. **显式优于隐式，但不发明轮子**（ADR 4/10）：`@RunInPool` 显式声明执行位置（命名业务池或 EventLoop）、`WebComponent` 显式收口 SPI 发现——框架在关键决策点让用户显式表态（而非靠惯例推断）；但又不发明 `@WebComponent` 注解，复用 Spring `Ordered`/`@Order` 与 `AnnotationAwareOrderComparator`，扩展方零学习成本。
3. **接受理论缺陷，拒绝过早防御**（ADR 7/8）：`complete` 边界竞态、静态 Map 扩容并发窗口都是已知接受的理论缺陷——因为它们在当前调用模型下不触发，加锁/per-instance 化会破坏无锁 drain loop 与多 path 共享缓存的既有设计。决策核心是"修方案前先读完整调用链，回答：并发是真的还是理论？后果是否违背用户预期？改动是否破坏既有设计意图？三点不过一律不动"。

这三条主线在 [01 篇](01-design-philosophy.md) 设计哲学中已有原则性表述（零匹配、零反射、显式 SPI），本篇把它们落实为可追溯 `file:line` 的具体取舍记录——**每个"为什么这么设计"都能在源码中找到证据，每个"代价与边界"都标明何时该重新审视**。

---

> **下一篇**：[20 · 总结与路线图](20-summary-roadmap.md)——系列总结与未来工作。
