> [English](en/performance-principles.md) | 中文

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
| `BizPoolRegistry` | 数组索引取线程池引用 | — |
| `InterceptorRegistry` | 直接返回预匹配的拦截器数组 | 每次请求路径匹配拦截器 |
| `WebDataBinderRegistry` | 直接返回预缓存的 Validator | 每次请求扫描 `@InitBinder` |
| `HttpBodyCodecInterceptorRegistry` | 直接返回预缓存的 Advice 链 | 每次请求匹配 `RequestBodyAdvice` |

### 为什么这是最核心的手段

- **不依赖请求类型**：无论请求是 CPU 密集型还是 IO 密集型，预缓存机制在每次请求中都生效
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

> 注意：此收益与调用发生的线程无关，即使调度到业务线程执行，调用方式仍然是 `INVOKEVIRTUAL`/`MethodHandle`。

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

此收益每请求都受益。

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

GC 压力直接转化为 STW 暂停。每次请求减少 1-2 个短期对象的分配，在 28K ops/s 下就是每分钟减少 170 万次分配。

---

## 5. ByteBuf 引用计数——精确内存管理

### 手段

`NettyServerHttpRequest` 实现了 `acquire()` / `release()` 接口，用于跨线程切换（EventLoop → 业务线程池）时的 ByteBuf 生命周期管理：

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

`DispatcherHandler.handleWithFullMatch()` 中，如果控制器**没有**标注 `@RunInPool`，默认在 `default` 业务线程池中执行（通过 `pool.*` 配置）。轻量端点可通过 `@RunInPool(RunInPool.EVENTLOOP)` 显式切换到 EventLoop，或通过 `pool.default-execute-mode=eventloop` 全局配置。

### 关键理解：这不是"替你做优化"，而是"给你选择权"

传统 Servlet 容器（Tomcat）强制将所有请求交给容器线程池，业务代码无法选择在哪里执行。本框架允许请求直接在 EventLoop 上执行，业务方自主决定是否切到线程池——提供的真正价值是**编程模型的选择自由**：

**选择一：同步阻塞（默认）**

无 `@RunInPool` 时方法默认在 `default` 业务线程池执行（通过 `pool.*` 配置）。使用 `@RunInPool("custom")` 可调度到自定义线程池。

**选择二：响应式编程（`@RunInPool(RunInPool.EVENTLOOP)`）**

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
@GetMapping("/future-ready")
@RunInPool(RunInPool.EVENTLOOP) // 虚拟线程 + EventLoop 无阻塞切换
public Result<Data> query() {
    return Result.ok(repository.findById(1L));
}
```

此框架的同步编程模型与虚拟线程天然兼容。当 JDK 21+ 虚拟线程普及后，`@RunInPool` + `EventLoop` 组合允许虚拟线程直接在 EventLoop 上执行阻塞操作——虚拟线程的 `park`/`unpark` 机制让阻塞不再阻塞操作系统线程，无需额外业务线程池。

### 当前收益 vs 架构意义

在当下，直接 EventLoop 处理对纯 CPU 端点节省了线程切换（~1-3μs），但这只是附带好处。**架构上更重要的是**：框架没有绑定死一个模型——业务方可以根据自己的场景选择同步阻塞、响应式、或者未来的虚拟线程，而不需要更换 Web 框架。

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

仅 SSE 和流式响应场景。Benchmark 中 perf 的 SSE 吞吐达到 Spring MVC 的 3.89x（4 线程），高并发下扩展至 6.64x。

---

## 8. Netty 层面的传输优化

- `TCP_NODELAY=true`：禁用 Nagle 算法，对小包延迟友好
- `WriteBufferWaterMark`：精确背压阈值
- `DefaultFileRegion`：sendfile 零拷贝文件传输
- HTTP/2 多路复用（可选）

这些是所有 Netty 框架都能获得的通用收益，非本项目独有。

---

## 附：全维度对比

### 说明

- **Spring Web** = 本项目（spring-boot-starter-web）
- **MVC+Tomcat** = Spring MVC + Tomcat（spring-boot-starter-web 默认）
- **WebFlux** = Spring WebFlux + Reactor Netty

同一格内两个描述用 `→` 分隔时，左侧为手段，右侧为效果或原因。

---

### 核心架构

| 维度 | Spring Web | MVC+Tomcat | WebFlux |
|------|-----------|------------|---------|
| 底层引擎 | **Netty 4.1**（原生） | Tomcat 9/10（Servlet 容器） | Reactor Netty |
| 编程模型 | **同步 + 可选响应式** | 同步阻塞 | 响应式（Mono/Flux） |
| I/O 模型 | **Netty 非阻塞传输** | Servlet 阻塞 I/O | Reactor 非阻塞 |
| 线程模型 | **EventLoop 直接处理** → 零切换或 `@RunInPool` 按需切换 | 固定容器线程池 → 每次请求线程切换 | EventLoop 全响应式 → 所有操作必须非阻塞 |
| 启动阶段 | **三阶段生命周期 (Phase1/2/3)** → 组件自初始化、预缓存、fail-fast 校验 | Spring 容器 + DispatcherServlet 初始化 | Spring 容器 + 初始化 |
| HTTP/2 | **支持**（h2 ALPN + h2c prior knowledge） | 支持（需配置） | 原生支持 |

### 请求生命周期

| 维度 | Spring Web | MVC+Tomcat | WebFlux |
|------|-----------|------------|---------|
| 请求对象 | **`NettyServerHttpRequest`** → 包装 `FullHttpRequest`，引用计数管理 ByteBuf | `HttpServletRequest` → Tomcat 内部对象 | `ServerHttpRequest` → 包装 Netty `HttpRequest` |
| 请求体读取 | **`ByteBuf` 零拷贝** → ≤4KB heap 拷贝，>4KB `retainedDuplicate()` | `InputStream.read()` → heap → direct 拷贝 | `DataBuffer` → `ByteBuf` 包装 |
| 请求体管理 | **`acquire()/release()`** → 显式引用计数，跨线程时 retain | 容器管理 → 请求结束自动回收 | 引用计数 → `DataBufferUtils.release()` |
| 请求属性 | **`fastAttributes[]`** → `Object[]` 数组，O(1) 无哈希 | `HashMap<String,Object>` → 哈希 + 锁 | `Map<String,Object>` → 哈希 |

### 请求处理管线

| 维度 | Spring Web | MVC+Tomcat | WebFlux |
|------|-----------|------------|---------|
| 过滤器 | **`WebFilter`** SPI → 启动时构建有序链 | `javax.servlet.Filter` → 容器管理的 Filter Chain | `WebFilter` → 响应式链 |
| 路径映射 | **多级 RouterOptimizer 链** → 精确路径 HashMap O(1)，前缀/后缀索引，兜底遍历 | **`AntPathMatcher`** → 遍历匹配，O(n) | **`PathPatternParser`** → 编译后匹配，近似 O(log n) |
| 路径匹配机制 | **组合优化器**：FullPathRouterOptimizer → Prefix → Suffix → Loop | `AbstractHandlerMethodMapping.lookupPath()` → 遍历所有注册 | `AbstractHandlerMethodMapping` + `PathPattern` |
| Controller 调度 | **`DispatcherHandler`** → 直接路由到 `InvokableHandlerMethod` | `DispatcherServlet` → `HandlerExecutionChain` | `DispatcherHandler` → 响应式 HandlerAdapter |
| 方法调用 | **ASM/MethodHandle** → 零反射 (~10-30ns) | `Method.invoke()` → 反射 (~200ns) | `Method.invoke()` → 反射 (~200ns) |

### 参数与返回值

| 维度 | Spring Web | MVC+Tomcat | WebFlux |
|------|-----------|------------|---------|
| 参数解析 | **启动时预缓存 `StaticArgumentResolver`** → 运行时直接调用，数组索引，零遍历 | **运行时匹配** → `HandlerMethodArgumentResolverComposite` + `synchronized` 缓存 | **运行时匹配** → 遍历 `HandlerMethodArgumentResolver` |
| `@PathVariable` | 启动时预解析 → 运行时从 `Map<String,String>` 取值 | 每次请求从 `HandlerMethod` 取路径变量 | 每次请求从 `PathPattern` 取路径变量 |
| `@RequestBody` | 启动时预匹配 `HttpBodyConverter` → 运行时直接调用 | 运行时遍历 `HttpMessageConverter` 列表 | 运行时通过 `BodyExtractor` 解码 |
| 数据绑定 | **`PerfDataBinder`** → 移除 `fieldMarkerPrefix` 等无用功能，最小化绑定路径 | `WebDataBinder` → 标准 JavaBeans 属性绑定 | `WebExchangeDataBinder` → 响应式绑定 |
| 返回值处理 | **启动时预缓存 `ReturnValueResolver`** → 运行时直接命中 | **运行时匹配** → 遍历 `HandlerMethodReturnValueHandlerComposite` | **运行时匹配** → 遍历 `HandlerMethodReturnValueHandler` |
| 响应写入 | **ByteBuf → `DefaultHttpContent` → `channel.write()`** → 零拷贝 direct 写 | `OutputStream.write(byte[])` → heap→direct 拷贝 | `DataBufferFactory.wrap()` → 零拷贝 |
| 大文件输出 | **`DefaultFileRegion`** → sendfile 零拷贝 | `FileCopyUtils` → heap 中转 | `ZeroCopyFileRegion` → sendfile 零拷贝 |

### 拦截器与异常

| 维度 | Spring Web | MVC+Tomcat | WebFlux |
|------|-----------|------------|---------|
| 拦截器 | **`HandlerInterceptor`** → 启动时预匹配到 Mapping，运行时直接迭代 | `HandlerInterceptor` → `MappedInterceptor` 运行时路径匹配 | **无拦截器概念** → 全部用 `WebFilter` 替代 |
| 拦截器缓存 | **`InterceptorRegistry` 预匹配** → 每个 Mapping 的拦截器列表在 Phase3 确定 | `MappedInterceptor` → 每次请求遍历 include/exclude 模式 | — |
| `@ControllerAdvice` | **启动时全量扫描** → 预缓存异常处理方法、`@InitBinder`、Advice 拦截器 | **运行时查找** → `ExceptionHandlerExceptionResolver` 启动时缓存 | **启动时扫描** → `ControllerAdviceBean` 缓存 |
| 异常处理 | **`HandlerExceptionResolver` 链** → 启动时注册 | `HandlerExceptionResolverComposite` → 运行时遍历 | `WebExceptionHandler` → 响应式异常处理 |

### SSE / 流式

| 维度 | Spring Web | MVC+Tomcat | WebFlux |
|------|-----------|------------|---------|
| SSE 实现 | **`NettyStreamSender`** → `MpscUnboundedArrayQueue` + 无锁 Drain Loop | `SseEmitter` → 每个连接一个线程，同步阻塞写 | `Flux<ServerSentEvent>` → Reactor 背压 |
| 背压机制 | **`channel.isWritable()` + `BackpressureHandler`** → 水位线精确控制 | **无** → 生产者过快直接阻塞线程 | Reactor `request(n)` → operator 链传播 |
| 线程占用 | **EventLoop 统一写入** → 线程数 = CPU 核，不随连接增长 | **每连接一线程** → 线程数 = 连接数 | EventLoop 统一写入 |
| 流式吞吐 | **Spring MVC 的 3.89x(4t) / 6.64x(64t)** | 基准 | 接近，但无锁队列优势在小消息场景更显著 |

### 对象分配与内存

| 维度 | Spring Web | MVC+Tomcat | WebFlux |
|------|-----------|------------|---------|
| 属性容器 | **`Object[]` 数组** → `fastAttributes[i]`，零创建零哈希 | `ConcurrentHashMap` → Entry + String 对象分配 | `HashMap` → 哈希分配 |
| 响应体缓冲 | **延迟分配** → 首次 `getBuf()` 才创建 ByteBuf | 内置缓冲区 → Tomcat 内部 `OutputStream` | `DataBufferFactory` → 可配置 |
| 中间对象 | **零临时集合** → 请求路径不 `new ArrayList` | 多次创建 → 参数 Map、验证 Errors 等 | 响应式链 → Mono/Flux 对象分配 |
| 对象复用 | **全局单例** → `BackpressureHandler.INSTANCE`、`SslExceptionHandler.INSTANCE` | Filter 单例 | 组件单例 |

### 生态兼容

| 维度 | Spring Web | MVC+Tomcat | WebFlux |
|------|-----------|------------|---------|
| Servlet API | **桥接兼容**（`spring-web-support` 模块） | 原生支持 | 不支持 |
| Actuator | **原生支持** + 独立管理端口 | 原生支持 | 原生支持 |
| `@RequestMapping` | **全兼容** | 原生 | 原生 |
| `javax.validation` | 支持 | 支持 | 支持 |
| Spring Data | 桥接兼容 | 原生 | 原生 |
| 最小堆占用 | **~20MB** | ~23MB | ~23MB |
| P50 延迟 (小包) | **0.12-0.15ms** | 0.17-0.26ms | 0.18-0.24ms |

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
| ★★☆☆☆ | 无锁 Drain Loop | 仅 SSE | SSE 场景 3.89x(4t) / 6.64x(64t) |
| ★★☆☆☆ | Netty 传输优化 | 边际 | 所有 Netty 框架共享 |

**核心结论**：本框架通过**启动时确定性解析**消除了运行时所有"查找"和"匹配"开销——这是性能提升的根本原因。字节码生成消除反射在此基础上进一步优化了方法调用（~200ns → ~30ns）。默认走 `default` 业务线程池让大多数 IO handler 免于注解，轻量端点可通过 `@RunInPool(RunInPool.EVENTLOOP)` 显式切换到 EventLoop。这是"业务方自主选择编程模型"设计理念的落地：框架提供灵活控制，不强制单一模型。