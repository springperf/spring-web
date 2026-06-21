# 性能原理

本项目的性能不是来自"Netty 比 Tomcat 快"这种泛泛的说法。Netty 只是一个事件驱动 I/O 框架，真正的高性能来自框架设计层面的工程取舍。

以下按照**收益面从大到小**排列——排在前面的技术每请求都受益，排在后面的技术只在特定场景生效。

---

## 优先级排序

| 优先级 | 技术 | 收益面 |
|--------|------|--------|
| ★★★★★ | 启动时预缓存，运行时零反射零匹配 | 100% 请求 |
| ★★★★★ | 字节码生成 / MethodHandle 替代反射调用 | 100% 请求 |
| ★★★★★ | O(1) 路由——多级优化器链 | 100% 请求 |
| ★★★★☆ | 避免隐式对象创建——GC 友好 | 100% 请求 |
| ★★★★☆ | ByteBuf 引用计数——精确内存管理 | 100% 请求 |
| ★★★★☆ | EventLoop 直接处理，支持响应式 / 虚拟线程 | 可选——由业务方控制编程模型 |
| ★★☆☆☆ | 无锁 Drain Loop——SSE/流式高吞吐 | 仅流式场景 |
| ★★☆☆☆ | Netty 层面的传输优化 | 边际收益 |

---

## 1. 启动时预缓存，运行时零反射零匹配 ← 核心

### 手段

框架利用三阶段生命周期（`initComponentPhase1/2/3`），在启动时一次性完成所有元数据的解析和缓存。每个 Registry 在 Phase3 遍历所有 Mapping，计算出对应策略后写入 `MappingCacheKey`。

`MappingCacheKey` 使用**整型数组索引**（而非 `ConcurrentHashMap`）：

```java
// MappingCacheKey 内部
final int index;  // 启动时分配的固定索引

// 存取是直接数组访问
mappingContext.get(MAPPING_CACHE_KEY)
    → handlerMethod.methodCache[cacheKey.index]  // O(1)，无锁，无哈希
```

以下所有组件均使用此机制在启动时完成预匹配：

| 组件 | 运行时行为 | 对比 Spring MVC |
|------|-----------|----------------|
| `ArgumentResolverRegistry` | 直接调用已缓存的解析器 | 每次请求遍历解析器列表 + `synchronized` 缓存 |
| `ReturnValueResolverRegistry` | 直接调用已缓存的解析器 | 每次请求遍历返回值处理器列表 |
| `BizPoolRegistry` | 数组索引取线程池引用 | 每次请求反射查找 `@RunInPool` |
| `InterceptorRegistry` | 直接返回预匹配的拦截器数组 | 每次请求路径匹配拦截器 |
| `WebDataBinderRegistry` | 直接返回预缓存的 Validator | 每次请求扫描 `@InitBinder` |
| `HttpBodyCodecInterceptorRegistry` | 直接返回预缓存的 Advice 链 | 每次请求匹配 `RequestBodyAdvice` |

### 为什么这是最核心的手段

- **不依赖请求类型**：无论请求是 CPU 密集型还是 IO 密集型，无论是否使用 `@RunInPool`，预缓存机制在每次请求中都生效
- **消除同步开销**：Spring MVC 的 `HandlerMethodArgumentResolverComposite` 使用 `synchronized` 块来保护解析器缓存，高并发下存在锁竞争
- **数组存取比 Map 快 3-5 倍**：`MappingCacheKey` 的 `index` 直接索引 `Object[]`，省掉 `ConcurrentHashMap.get()` 的 4 次 volatile 读和 hashCode 计算
- **编译期确定 vs 运行时匹配**：启动时完成匹配 = 一次开销；运行时匹配 = 每次请求重复开销

---

## 2. 字节码生成替代反射调用

### 手段

两个级别的调用优化：

**一级（`@Optimize` 注解）→ ASM 字节码生成**

`FastInvokerGenerator` 使用 Spring ASM（`ClassWriter`）在启动时为每个标记 `@Optimize` 的控制器方法生成专属的 Java 类，生成的字节码直接使用 `INVOKEVIRTUAL` 指令：

```java
// 生成代码等价于：
public class HelloController_sayHello_0 implements Invoker {
    private final HelloController target;

    public Object invoke(Object[] args) {
        return target.sayHello((String) args[0]);  // INVOKEVIRTUAL
    }
}
```

`INVOKEVIRTUAL` 是 JVM 最基础的调用指令，JIT 可以直接内联——和手写代码等价。

**二级（默认）→ MethodHandle**

```java
// MethodHandleInvoker.java 第 27 行
return methodHandle.invokeExact(args);
```

`MethodHandle.invokeExact()` 是 JVM intrinsic，JIT 可以内化为直接调用，省掉 `Method.invoke()` 的访问检查和参数装箱。

### 为什么快

| 调用方式 | 耗时 (近似) | 开销来源 |
|---------|------------|---------|
| `INVOKEVIRTUAL` 直接调用 | ~10ns | 无 |
| `MethodHandle.invokeExact()` | ~30ns | 无访问检查 |
| `Method.invoke()` | ~200ns | access check + 可变参数装箱 + 类型校验 |

> 注意：即使方法被 `@RunInPool` 调度到业务线程执行，调用方式仍然是 `INVOKEVIRTUAL`/`MethodHandle`，收益不丢失。

---

## 3. O(1) 路由——多级优化器链

### 手段

`MappingRegistry.optimizeMapping()` 在启动时对路由表执行分桶 + 多级优化：

**第一步：分桶**

```
无通配符路径 (/api/user/list)    → simpleUrlList    (HashMap.get  O(1))
单级通配符路径 (/api/user/{id})   → simpleWildcardList (前缀/后缀索引)
全通配路径 (/**)                 → fullWildcardList  (遍历)
```

**第二步：构建优化器链**

```
FullPathRouterOptimizer  (精确路径 → HashMap.get(path)  O(1))
  ↓ 未命中
PrefixPathRouterOptimizer (前缀匹配 → HashMap.get(prefix) O(1))
  ↓ 未命中
SuffixPathRouterOptimizer (后缀匹配 → HashMap.get(suffix) O(1))
  ↓ 未命中
LoopPathPatternRouterOptimizer (遍历 PathPatternRouter[]  O(n))
```

### 为什么快

- Spring MVC 不论路径是否含通配符都需要 `AntPathMatcher.match()` 做字符串模式匹配
- 本框架 90%+ 的路径是精确路径，一次 `HashMap.get()` 搞定
- 统计学前缀优化器自动选择最佳前缀深度，将对通配符路径的遍历范围压缩到最小

此收益同样与是否使用 `@RunInPool` 无关，每请求都受益。

---

## 4. 避免隐式对象创建——GC 友好

### 手段

设计原则明确写入代码规范：**请求路径上不 `new ArrayList`、不装箱、不创建临时对象。**

具体落地：
- `BaseWebServerHttpRequest.fastAttributes[]`：`Object[]` 替代 `ConcurrentHashMap`，零 String 对象创建
- `NettyServerHttpResponse.getBuf()`：`ByteBuf` 延迟到首次写操作才分配
- `BaseWebServerHttpResponse.setCommitted()`：`AtomicBoolean` CAS，整个请求生命周期只执行一次
- 所有 Registry 启动时完成列表构建，运行时读操作不创建临时集合

### 为什么快

GC 压力直接转化为 STW 暂停。每次请求减少 1-2 个短期对象的分配，在 28K ops/s 下就是每分钟减少 170 万次分配。对于使用了 `@RunInPool` 的业务线程，GC 友好同样生效——业务线程的 GC 暂停同样影响吞吐。

---

## 5. ByteBuf 引用计数——精确内存管理

### 手段

`NettyServerHttpRequest` 实现了 `acquire()` / `release()` 接口，用于 `@RunInPool` 切换到业务线程时的 ByteBuf 生命周期管理：

```java
// DispatcherHandler 第 86-98 行
req.acquire();         // retain FullHttpRequest
try {
    executor.execute(() -> {
        try { processRequest(...); }
        finally { req.release(); }  // 业务线程处理完释放
    });
} catch (RejectedExecutionException e) {
    req.release();      // 线程池满也要释放
    throw e;
}
```

大 body（>4KB）使用 `retainedDuplicate()` 零拷贝，避免额外的堆内存分配。

### 为什么快

- 直接操作 `DirectByteBuf`：省掉 heap → direct 的拷贝
- 引用计数精确控制 direct memory 释放，不依赖 GC（`Cleaner` 回收有延迟）
- `Unpooled.wrappedBuffer(data)` 零拷贝包装 byte[]

---

## 6. EventLoop 直接处理，支持响应式编程与虚拟线程

### 手段

`DispatcherHandler.handleWithPathMappingContext()`（第 84-101 行）中，如果控制器**没有**标注 `@RunInPool`，则直接在 Netty EventLoop 线程中执行整个请求管线。

### 关键理解：这不是"替你做优化"，而是"给你选择权"

传统 Servlet 容器（Tomcat）强制将所有请求交给容器线程池，业务代码无法选择在哪里执行。本框架的 EventLoop 处理提供的真正价值是**编程模型的选择自由**：

**选择一：同步阻塞 + 业务线程池（当前主流）**

```java
@RunInPool("db")
public Result<Data> query() {
    return Result.ok(repository.findById(1L)); // 业务线程，阻塞 safe
}
```

保持 Spring MVC 开发者最熟悉的模型，`@RunInPool` 明确声明"这里需要线程池"。

**选择二：响应式编程（无 `@RunInPool`）**

```java
@GetMapping("/reactive")
@ReactiveSupport(highWaterMark = 256, lowWaterMark = 64)
public Publisher<Data> reactiveEndpoint() {
    return Flux.range(1, 1000)
            .map(i -> new Data(i, "item-" + i));
}
```

业务方自行使用响应式驱动（R2DBC、WebClient、Reactive Redis），所有 IO 是非阻塞的，**直接在 EventLoop 上执行，不占用业务线程**。框架通过 `ReactiveReturnValueResolver` 将 `Publisher` 适配为流式输出或 `DeferredResult`，配合 `@ReactiveSupport` 的背压参数（`highWaterMark`/`lowWaterMark`）控制内存水位。

这种方式对高并发、IO 密集且延迟分散的场景（如网关、聚合服务）特别有效——EventLoop 本身的高效调度 + reactive 库的异步驱动，不需要业务线程池介入。

**选择三：JDK 21 虚拟线程（未来）**

```java
// 不需要 @RunInPool，虚拟线程自动处理阻塞
@GetMapping("/future-ready")
public Result<Data> query() {
    return Result.ok(repository.findById(1L)); // 在 EventLoop 上执行
}
```

此框架的同步编程模型与虚拟线程天然兼容。当 JDK 21+ 虚拟线程普及后，业务代码可以直接在 EventLoop 上执行阻塞操作——虚拟线程的 `park`/`unpark` 机制让阻塞不再阻塞操作系统线程，`@RunInPool` 不再需要业务线程池。框架架构无需改动即可无缝过渡。

### 当前收益 vs 架构意义

在当下，直接 EventLoop 处理对纯 CPU 端点节省了线程切换（~1-3μs），但这只是附带好处。**架构上更重要的是**：框架没有绑定死一个模型——业务方可以根据自己的场景选择同步 + 线程池、响应式、或者未来的虚拟线程，而不需要更换 Web 框架。

这同时也是为什么 `@RunInPool` 是**显式注解**而非隐式行为——框架默认信任业务方的选择，只在明确需要时才切线程。

---

## 7. 无锁 Drain Loop——SSE/流式高吞吐

### 手段

`NettyStreamSender` 使用 `MpscUnboundedArrayQueue` + `AtomicInteger wip` 构成无锁 Drain Loop：

```java
private final MpscUnboundedArrayQueue<ByteBuf> queue;  // 多生产者单消费者无锁队列

void drain() {
    int missed = 1;
    for (;;) {
        while (channel.isWritable()) {
            ByteBuf buf = queue.poll();
            channel.write(new DefaultHttpContent(buf));
        }
        channel.flush();
        missed = wip.addAndGet(-missed);
        if (missed == 0) break;
    }
}
```

### 适用范围

仅 SSE 和流式响应场景。Benchmark 中 perf 的 SSE 吞吐达到 Tomcat 的 3.85x。

---

## 8. Netty 层面的传输优化

- `TCP_NODELAY=true`：禁用 Nagle 算法，对小包延迟友好
- `WriteBufferWaterMark`：精确背压阈值
- `DefaultFileRegion`：sendfile 零拷贝文件传输
- HTTP/2 多路复用（可选）

这些是所有 Netty 框架都能获得的通用收益，非本项目独有。

---

## 总结

| 优先级 | 技术 | 收益面 | 量产化估计 |
|--------|------|--------|-----------|
| ★★★★★ | 启动时预缓存，运行时零匹配 | 100% | 省掉每次请求的参数/返回值解析器遍历 |
| ★★★★★ | ASM/MethodHandle 替代反射 | 100% | 方法调用从 ~200ns 降到 ~10-30ns |
| ★★★★★ | O(1) HashMap 路由 | 100% | 路径匹配从 O(n) 降到 O(1) |
| ★★★★☆ | GC 友好无临时分配 | 100% | 每分钟减少百万级对象分配 |
| ★★★★☆ | ByteBuf 零拷贝 | 100% | 消除 1-2 次数据拷贝 |
| ★★★★☆ | EventLoop 直接处理 | 可选——三种模型 | 响应式编程 / 虚拟线程的基础 |
| ★★☆☆☆ | 无锁 Drain Loop | 仅 SSE | SSE 场景 3.85x |
| ★★☆☆☆ | Netty 传输优化 | 边际 | 所有 Netty 框架共享 |

**核心结论**：本框架通过**启动时确定性解析**消除了运行时所有"查找"和"匹配"开销，配合**字节码生成**消除反射——这两项是 100% 请求受益的核心手段。"EventLoop 直接处理"的真正价值不在省线程切换，而是为响应式编程和未来虚拟线程提供了基础设施——业务方自主选择编程模型，框架不强制。