# 01 · 设计哲学与六大取舍原则

> [← 返回索引](00-README.md) | 下一篇：[02 · 模块拓扑与启动期全景](02-architecture-overview.md)

---

## 引子：哲学不是口号，是代码规范

很多高性能框架的文档会把"我们很快"归因于 Netty。本框架在 [`philosophy.md`](../philosophy.md) 已经反驳过这个说法——Netty 只是事件驱动 I/O 工具，真正的快来自设计层面的工程取舍。本篇把这个"取舍"从方法论下沉到**代码规范级原则**：框架在写每一行代码时遵循了哪些"禁止项"与"偏好项"，这些原则又如何体现为可逐行核对的可验证代码特征。

读完本篇，你应能回答：**为什么这套框架的源码读起来有一种克制的"不便利感"——很多 Java 框架习以为常的写法在这里被刻意拒绝了？** 这种拒绝不是固执，而是性能的来源。

---

## 一、总纲：启动时确定性

六大原则之上还有一个总纲，一句话：

> **启动时把所有能确定的事都确定好，运行时只做查表。**

这条总纲是其余六条的公分母。它把 Spring MVC 的"运行时灵活"设计整个翻转过来：

| 信息 | Spring MVC 的做法 | 本框架的做法 |
|------|-------------------|-------------|
| URL → 哪个方法 | 运行时 `lookupPath()` 遍历匹配 | 启动时分桶 + 多级优化器链，运行时 `HashMap.get` |
| 方法参数 → 哪个解析器 | 运行时 `supportsParameter` 遍历 + `synchronized` 缓存 | 启动期 Phase3 决策，写整型索引，运行时 `array[index]` |
| 返回值 → 哪个处理器 + MediaType | 运行时遍历 `canWrite` | 启动期预匹配，运行时直调 |
| 方法调用 | 运行时 `Method.invoke` 反射 | 启动期 ASM 生成 `INVOKEVIRTUAL` 或 `MethodHandle` 固化 |

这套"启动时确定"的落地载体，是三阶段组件生命周期。它的入口不在 `InitializingBean` 的常规位置——值得专门指出一个容易误读的细节：

```java
// WebContext.java
@Override
public void afterPropertiesSet() {
}
```

`WebContext` 虽实现了 `InitializingBean`，却把 `afterPropertiesSet()` 留空。真正的三阶段编排推迟到 `startLifecycle()`，由 `NettyHttpServer.start()` 在 Spring 容器完全就绪、所有 Bean 注册完毕后才触发：

```java
// WebContext.java
public void startLifecycle() {
    if (!lifecycleStarted.compareAndSet(false, true)) {
        return;
    }
    try {
        this.initWithWebContext(this);   // 注入上下文
        this.initComponentPhase1();      // Phase1：元数据收集
        this.initComponentPhase2();      // Phase2：跨组件连接
        this.initComponentPhase3();      // Phase3：优化与缓存
    } catch (Exception e) {
        throw new RuntimeException("Failed to start WebContext lifecycle", e);
    }
}
```

**为什么推迟？** Web 组件初始化应在 Spring 基础容器初始化完成之后。`afterPropertiesSet()` 的触发时点取决于 bean 在依赖图中的位置，无法保证此时容器已就绪；而 `SmartLifecycle.start()` 在 context refresh 完全结束后由 `LifecycleProcessor` 调用，此时所有 bean 必然就绪。把生命周期锚定在此，是为了让"启动时确定"拿到的输入是完整的——确定性首先要求输入完整。

三阶段的精确语义在 [`LifecycleWebComponent`](../../spring-web/src/main/java/io/springperf/web/context/LifecycleWebComponent.java) 的接口注释里写得很清楚（Phase1 扫描收集 / Phase2 跨组件连接建索引 / Phase3 优化缓存预计算），机制细节留给 [03 篇](03-component-lifecycle.md)，本篇只取其哲学含义：**每一阶段都在把"运行时要算的事"前移为"启动时算一次的事"。**

---

## 二、六大取舍原则

每条原则给三个锚点：**禁止/偏好**（代码规范）、**代码证据**（`file:line`）、**对比对象**（同位的 Spring 实现）。

### 原则 1 · 避免隐式开销——运行时零反射、零匹配、零类型推断

> 禁止：在请求处理路径上做反射调用、运行时类型推断、遍历匹配。
> 偏好：启动期一次性解析并缓存为整型索引，运行时数组直取。

**代码证据**

```java
// MappingCacheKey.java
public final class MappingCacheKey<T> {
    private static final AtomicInteger METHOD_CACHE_SEQ = new AtomicInteger();
    private static final AtomicInteger CLASS_CACHE_SEQ   = new AtomicInteger();

    final int index;          // 启动期分配的固定整型索引
    final Class<T> type;
    final boolean classCache;

    public static <T> MappingCacheKey<T> createMethodCacheKey(Class<T> type) {
        return new MappingCacheKey<>(METHOD_CACHE_SEQ.getAndIncrement(), type, false);
    }
    // ...
}
```

`MappingCacheKey` 是整套预缓存机制的"地址"。启动期每创建一个缓存槽位，就用 `AtomicInteger.getAndIncrement()` 分配一个**全局唯一且永不复用的整型 index**。运行时取用是 `handlerMethod.methodCache[cacheKey.index]`——一次数组访问，无哈希、无锁、无比较。

**为什么不用 `ConcurrentHashMap`？** 因为 Map 的 `get()` 即使在最佳情况下也是 4 次 volatile 读 + hashCode 计算 + 可能的桶遍历；数组访问是一条 `aaload` 指令。在每请求都触发的热路径上，这个差距被并发放大。

**对比对象**：Spring MVC 的 `HandlerMethodArgumentResolverComposite` 用 `synchronized` 块保护解析器缓存（`getArgumentResolvers` → `createCache` 锁内重建），高并发下存在锁竞争；且即便缓存命中，每请求仍要遍历 `argumentResolvers` 列表做 `supportsParameter` 匹配。本框架把"匹配"这件事整体前移到 Phase3 一次性做完，运行时连 `supports` 都不调。

> 本原则的完整机制链路见 [03 篇](03-component-lifecycle.md)（Registry 体系）与 [07 篇](07-argument-resolution.md)（参数解析）。

---

### 原则 2 · 避免隐式对象创建——请求路径零 `new`、零装箱、零临时集合

> 禁止：在请求处理路径上 `new ArrayList`、装箱基本类型、创建临时 Map/包装器。
> 偏好：预分配定长数组、复用单例、延迟到首次写操作才分配缓冲。

**代码证据一：`fastAttributes` 数组替代 Map**

```java
// BaseWebServerHttpRequest.java
protected final Map<String, Object> attributes = new ConcurrentHashMap<>();
protected final Object[] fastAttributes = new Object[RequestAttribute.getMaxSize()];

// BaseWebServerHttpRequest.java
public <T> T getAttribute(RequestAttribute<T> key) {
    int idx = key.getIndex();
    if (idx < fastAttributes.length) {
        return (T) fastAttributes[idx];        // 数组直取，零对象创建
    }
    return (T) attributes.get(FAST_ATTR_PREFIX + idx);  // 超界才回退 Map
}
```

`RequestAttribute.getMaxSize()` 在启动期确定——又是"启动时确定"的体现。热点请求属性（如 DispatcherHandler 的 `METRICS_START_ATTR` 计时戳）走 `fastAttributes[idx]`，**整条 get/set 路径不创建任何对象**：不分配 `Entry`、不计算 hashCode、不装箱。只有当 `RequestAttribute` 数量超过启动期预分配上限时才回退到 `ConcurrentHashMap`——这是一个有界的、可观测的降级，而非静默的隐式分配。

**代码证据二：异常对象复用为静态单例**

```java
// DispatcherHandler.java
private static final StacklessResponseStatusException NOT_FOUND_EXCEPTION =
        new StacklessResponseStatusException(HttpStatus.NOT_FOUND);
private static final StacklessResponseStatusException METHOD_NOT_ALLOWED_EXCEPTION =
        new StacklessResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED);
```

404/405 是高频路径。Spring MVC 每次 `sendError` 都 `new` 一个异常对象（带堆栈填充）；本框架把这两个异常预构为 `static final` 单例，且 `StacklessResponseStatusException` 顾名思义**不填充堆栈**——省掉 `Throwable.fillInStackTrace()` 这一每次约几微秒、且本身分配栈帧数组的开销。

**对比对象**：Spring MVC 每请求创建 `EmptyBodyCheckingHttpInputMessage` 包装器（检查 body 是否为空用）、参数绑定用的临时 Map、`ModelAndView` 容器等。Benchmark 实测每请求堆分配本框架 ~8–11KB vs Tomcat ~23–37KB vs WebFlux ~25–51KB（[见 17 篇](17-benchmark-data.md)）——这个 2–5 倍差距的根因之一就是这条原则。

> 完整对象分配归因见 [15 篇](15-performance-optimizations.md) §4。

---

### 原则 3 · 避免线程模型僵化——业务方掌控何时切换

> 禁止：容器强制接管所有请求到固定线程池、剥夺业务方的线程选择权。
> 偏好：默认走可配置的 `default` 业务线程池（core/max/queue/虚拟线程皆可调，发生一次切换）；轻量端点用 `@RunInPool(EVENTLOOP)` 或全局 `pool.default-execute-mode=eventloop` 切到 EventLoop 零切换直处理；IO 密集端点用 `@RunInPool("ioPool")` 切专用池。

**代码证据**

```java
// DispatcherHandler.java
protected void handleWithMappingResult(WebServerHttpRequest req, WebServerHttpResponse resp,
                                        MappingResult mappingResult) {
    // 通过 BizPoolRegistry 使用 Phase3 预缓存的线程池，
    // 无映射或未标注 @RunInPool 时为 null → EventLoop 同步处理
    ExecutorService executor = bizPoolRegistry.determinePool(req, mappingResult);
    if (executor != null) {
        req.acquire();                       // 跨线程前 retain
        try {
            executor.execute(() -> {
                try {
                    handleWithFilter(req, resp, mappingResult);
                } finally {
                    req.release();          // 业务线程处理完释放
                }
            });
        } catch (RejectedExecutionException e) {
            req.release();                  // 线程池满也要释放
            // ... 503 + RETRY_AFTER
        }
    } else {
        handleWithFilter(req, resp, mappingResult);   // EventLoop 直处理，零切换
    }
}
```

`bizPoolRegistry.determinePool(req, mappingResult)` 的返回值是"业务方掌控切换"的代码开关。决策在 `BizPoolRegistry.determinePool` 内（详见 [03 篇](03-component-lifecycle.md) + [04 篇](04-request-pipeline.md)）：

- **默认（无 `@RunInPool`）→ `default` 业务线程池**：`BizPoolRegistry.initWithWebContext` 把全局策略 `defaultExecuteMode` 读为 `pool.default-execute-mode` 的值，**缺省 `"default"`**（[`PropertiesConstant.java`](../../spring-web/src/main/java/io/springperf/web/context/PropertiesConstant.java)）。`"default"` 既非 `null` 也非 `"eventloop"`，故 `determinePool` 走 `resolvePool("default")` 返回启动期 `initDefaultPoolFromConfig()` 创建的 `ThreadPoolExecutor` → 命中 `if (executor != null)` → `acquire` + `executor.execute` → **发生一次到 `default` 池的线程切换**。
- **EventLoop 零切换（显式）**：`@RunInPool(RunInPool.EVENTLOOP)` 或全局 `pool.default-execute-mode=eventloop` → `determinePool` 写 `NO_POOL` 哨兵并返回 `null` → 命中 `else` → `handleWithFilter` 在 EventLoop 内跑完整链路，零 `Executor.execute`、零切换。
- **自定义池**：`@RunInPool("ioPool")` → `resolvePool("ioPool")` 返回命名池。

**对比对象**：Tomcat 强制把每个请求从 Acceptor/Poller 移交到固定工作线程池（`ProtocolHandler` → `Processor` → 工作线程），业务无法选择"不切换"，也无法换池。本框架的 `default` 池默认也切一次线程，但池参数（core/max/keepalive/queue/虚拟线程）完全由 `pool.*` 配置掌控；更关键的是业务方随时能用一个注解或一行配置切到 EventLoop 零切换——Tomcat 给不了这个选项。

**这条原则的真正价值不是"省那一次 ~1–3μs 切换"**，而是哲学层面的：**框架把编程模型的选择权交给业务方，而不是替业务方做死决定。** 轻量端点 `@RunInPool(RunInPool.EVENTLOOP)` 切零切换；IO 密集 `@RunInPool("ioPool")` 切专用池；虚拟线程来了，同样的 `@RunInPool` 组合无缝承接。框架不绑定单一模型——这是 [`philosophy.md`](../philosophy.md) "框架该干的活框架自己干"的代码学注脚。

> 完整线程模型见 [04 篇](04-request-pipeline.md)。

---

### 原则 4 · 避免阻塞——非阻塞 I/O + 显式引用计数

> 禁止：在 EventLoop 上阻塞、依赖 GC 延迟回收堆外内存。
> 偏好：非阻塞传输、`acquire/release` 显式引用计数跨线程管理 ByteBuf 生命周期。

**代码证据**

`DispatcherHandler.java` 的 `acquire() → executor.execute → release()` 模式（见原则 3 引用）是这条原则的核心示范。三个要点：

1. **跨线程前 `acquire()`**：Netty 的 `ByteBuf` 是引用计数的。EventLoop 线程把请求移交给业务线程池前，必须 `retain`（`acquire` 的语义），否则 EventLoop 处理完当前回调会 `release`，业务线程拿到的 `ByteBuf` 已被释放——堆外内存踩踏。
2. **业务线程 `finally release()`**：处理完显式释放，归还堆外内存，不依赖 GC 的 `Cleaner`（`Cleaner` 回收有延迟，高吞吐下会堆积 direct memory）。
3. **`RejectedExecutionException` 分支也要 `release()`**：线程池满拒绝任务时，已经 `acquire` 过的引用必须在这条分支释放，否则泄漏。源码 `DispatcherHandler.java` 精确处理了这个边界。

这个 `acquire/release` 的对称性是"避免阻塞/避免泄漏"原则的代码硬要求——它不是便利 API，是内存安全的契约。

**对比对象**：Servlet 容器由容器统一管理请求体生命周期，请求结束自动回收。便利的代价是：业务代码无法跨线程持有请求体（一持有就泄漏或被回收），也无法做零拷贝透传。本框架用显式引用计数换来了 `retainedDuplicate()` 零拷贝、跨线程传递 `ByteBuf`、直接操作 `DirectByteBuf` 省掉 heap→direct 拷贝等能力。

> 完整内存管理机制见 [05 篇](05-server-and-http.md)。

---

### 原则 5 · 避免反射——启动一次性解析，运行时零反射

> 禁止：在请求路径上用 `Method.invoke`、`Class.forName`、运行时注解扫描。
> 偏好：启动期一次性解析注解/泛型/方法签名，生成直接调用代码或 `MethodHandle`。

**代码证据**

启动期：Phase1 扫描 `@RequestMapping`、解析方法参数泛型、收集 `@ControllerAdvice`；Phase3 为每个方法参数选定 `StaticArgumentResolver`、为每个返回值选定 `ReturnValueResolver`、为标注 `@Optimize` 的方法用 ASM 生成 `Invoker` 字节码。

运行时：方法调用走的是 ASM 生成的 `INVOKEVIRTUAL`（~10ns）或 `MethodHandle.invokeExact`（~30ns），而非 `Method.invoke`（~200ns）。这个 20 倍差距的归因见 [09 篇](09-invoker-bytecode.md)，本篇只点出原则：**反射是启动期的合法工具，是运行期的禁止项。**

> 这里有一个容易混淆的点：`@RequestMapping`、`@PathVariable` 等注解本身是反射读取的——但**只读一次**（启动期），读完后把结果固化进 `MappingCacheKey` 索引和 `StaticArgumentResolver`。运行时不再碰注解对象。这区别于 Spring MVC 在每次请求的 `HandlerMethod` 处理中仍可能触发注解元数据查找。

**对比对象**：Spring MVC 的 `InvocableHandlerMethod.doInvoke()` 走 `Method.invoke()`，每次调用都付 access check + 可变参数装箱 + 类型校验。WebFlux 同样如此。本框架在 `@Optimize` 路径上把这个开销直接消除。

> 完整调用器机制见 [09 篇](09-invoker-bytecode.md)。

---

### 原则 6 · 避免魔法行为——显式 SPI、显式 fail-fast、不靠隐式猜测

> 禁止：静默覆盖、隐式条件装配、运行时才暴露配置错误。
> 偏好：组件冲突可见（log.warn）、自动注册显式（`Function` 映射）、启动期 fail-fast。

**代码证据一：组件冲突显式可见，非静默覆盖**

```java
// WebComponentContainer.java
public void registerWebComponent(WebComponent webComponent) {
    if (webComponents.containsKey(webComponent.getComponentName())) {
        WebComponent oldComponent = webComponents.get(webComponent.getComponentName());
        List<WebComponent> list = Arrays.asList(webComponent, oldComponent);
        AnnotationAwareOrderComparator.sort(list);          // 按 @Order 排序
        WebComponent newComponent = list.get(0);             // 取优先级高的
        webComponents.put(webComponent.getComponentName(), newComponent);
        log.warn("{} components have conflicts. Use {} and deprecate {}",   // 显式告警
                webComponent.getComponentName(), newComponent, list.get(1));
        if (newComponent == webComponent) {
            destroyComponent(oldComponent);                 // destroy 掉低的
        } else {
            return;
        }
    } else {
        webComponents.put(webComponent.getComponentName(), webComponent);
    }
    // ... 后续按 state 补跑生命周期
}
```

当新注册组件与已有组件同名时，会和核心组件产生同名冲突。这里的处理哲学是：**冲突不静默，按 `@Order` 明确取舍，并 `log.warn` 告知"用了谁、废弃了谁"**。业务方能在启动日志里看到覆盖关系，而不是在某个请求出了诡异行为后才发现某组件被悄悄换掉。

**代码证据二：自动注册是显式 `Function` 映射，非 SPI 猜测**

```java
// WebComponentContainer.java
protected Map<Class, Function<?, ? extends WebComponent>> autoRegisterComponentMap = new HashMap<>();

public <B> void autoRegisterWebComponent(Class<B> clazz, Function<B, ? extends WebComponent> getComponentFunc) {
    autoRegisterComponentMap.put(clazz, getComponentFunc);
}
```

"把某个 Spring Bean 类型适配为 WebComponent"这件事，必须由某个 Registry 显式调用 `autoRegisterWebComponent(BeanClass.class, bean -> new XxxWrapper(bean))` 注册一个 `Function`。没有"扫描类路径发现所有候选"这种魔法——适配关系是代码里写明的、可追溯的。这是各类 Adapter/Wrapper/Provider 模式能保持行为确定性的根基。

**代码证据三：启动期 fail-fast**

Phase3 的职责注释明确写着"optimization, caching, final preparation for request handling"（`LifecycleWebComponent.java`）。这意味着：参数解析器匹配不上、返回值无对应处理器、路由冲突等"运行时才会暴问题"的情况，在 Phase3 就会 fail-fast，启动直接失败。**宁可启动失败，不要运行时降级**——这是"避免魔法"在可靠性维度的延伸。

**对比对象**：Spring Boot 的 `@ConditionalOn*` 系列是强大的隐式条件装配——便利，但当某个 Bean 没生效时，排查"为什么它没被装配"往往要逆着条件链推导。本框架的取舍是：核心组件关系不靠条件猜测，靠显式注册与显式冲突告警。代价是写框架代码时要多写几行 `autoRegisterWebComponent`，收益是行为可预测、可排查。

---

## 三、六大原则的相互关系

这六条不是孤立的清单，而是一个自洽的体系，以"启动时确定性"为根：

```
                    启动时确定性（总纲）
                           │
        ┌──────┬───────────┼───────────┬──────┐
        │      │           │           │      │
     原则1   原则2       原则5       原则6   原则3/4
   零匹配   零分配      零反射      显式SPI  线程/内存
        │      │           │           │      │
        └──────┴───── 决定性要求输入完整 ──┴──────┘
                         （生命周期推迟到 startLifecycle）
```

- **原则 1（零匹配）和原则 5（零反射）** 是"启动时确定"在运行时的两面：前者消除"查找"开销，后者消除"调用"开销。
- **原则 2（零分配）** 是前两条的伴生要求——如果运行时还 `new` 包装器、装箱，那省下的匹配开销又被 GC 吞掉。
- **原则 6（显式 SPI + fail-fast）** 是前几条的前提保障：只有组件关系显式、启动期校验，"启动时确定"才真正确定，而不是"看起来确定实际靠运气"。
- **原则 3（避免线程切换）和原则 4（避免阻塞/显式引用计数）** 是"运行时只做查表"在线程与内存维度的约束——查表再快，如果每次请求都要切线程、泄漏 ByteBuf，性能也上不去。

---

## 四、与 Spring MVC / WebFlux 的哲学差异

把六条原则对照两个主流框架，能看清本框架的位置：

| 维度 | 本框架 | Spring MVC | Spring WebFlux |
|------|--------|-----------|----------------|
| 匹配时机 | 启动期一次性 | 运行时每次 | 运行时每次 |
| 调用方式 | ASM/MethodHandle | `Method.invoke` | `Method.invoke` |
| 线程模型 | 默认 `default` 业务池（可配/可切虚拟线程），`@RunInPool(EVENTLOOP)` 零切换 | 容器线程池强制接管 | EventLoop 全响应式 |
| 编程模型 | 同步 + 可选响应式 | 同步阻塞 | 强制响应式 |
| 兼容性手段 | 同包覆盖 + 显式 Wrapper（见 12） | 原生 | 不兼容 Servlet API |
| 配置错误暴露 | 启动期 fail-fast | 部分运行时 | 部分运行时 |

这张表的核心信息是 [`philosophy.md`](../philosophy.md) 那句"框架该干的活框架自己干"的代码学展开：Spring MVC 选了"运行时灵活"（便利但每请求付开销），WebFlux 选了"强制响应式"（高性能但强迫业务换范式），本框架选了"启动时确定 + 运行时零开销 + 保持 Spring 编程体验"——把性能优化的复杂性封进框架内部，对业务代码透明。

---

## 五、性能优先级排序的代码学解释

[`performance-principles.md`](../performance-principles.md) 给了 8 条优化按收益面排序的 ★ 表。从六大原则的角度，这个排序的依据是：

- **★★★★★ 三条（预缓存 / ASM-MethodHandle / O(1) 路由）** 都是原则 1+5 的落地，收益面 100%，因为它们消除的是"每请求必然发生"的查找与调用开销。
- **★★★★ 三条（GC 友好 / ByteBuf 引用计数 / EventLoop 直处理）** 是原则 2+3+4 的落地，收益面 100% 或"由业务方控制"，消除的是分配与切换开销。
- **★★ 两条（无锁 Drain Loop / Netty 传输优化）** 只在特定场景（SSE/流式）或边际生效，排序靠后。

这个排序的逻辑是：**先消除"每请求必付"的开销，再优化"特定场景才付"的开销。** 它和六条原则的优先级完全对应——原则 1、5（运行时零查找零反射）是根，原则 2、3、4 是干，原则 6 是保障。

---

## 六、小结：克制的源码即性能的来源

回到开篇的问题：为什么这套框架的源码读起来有一种克制的"不便利感"？

因为六大原则每一条都在拒绝某种便利：

- 拒绝"运行时灵活匹配"的便利（原则 1）→ 多写 Phase3 预缓存代码
- 拒绝"`new` 一个包装器"的便利（原则 2）→ 多写 `fastAttributes` 数组与单例
- 拒绝"容器统一管线程"的便利（原则 3）→ 把线程选择权显式交给业务
- 拒绝"容器统一管内存"的便利（原则 4）→ 多写 `acquire/release` 对称
- 拒绝"`Method.invoke` 通用"的便利（原则 5）→ 多写 ASM 生成器
- 拒绝"隐式条件装配"的便利（原则 6）→ 多写显式 `Function` 注册

每一次拒绝，都把一份"运行时每请求重复付的开销"转化成了"启动时一次性的工程投入"。这就是本框架性能优越的根因——**不是某个单点技巧，而是一整套相互自洽的克制规范的累积效应**。后续各篇会把每一条克制规范展开到 `file:line + 机制`的粒度，让你在不读源码的前提下也能精确复现这套设计。

---

> **下一篇**：[02 · 模块拓扑与启动期全景](02-architecture-overview.md)——把这套哲学放进四个 Maven 模块的真实结构里，画出一次请求跨模块的完整调用链。
