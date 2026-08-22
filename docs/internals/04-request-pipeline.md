# 04 · 请求处理主链路与线程模型

> [← 返回索引](00-README.md) | 上一篇：[03 · 组件生命周期与 Registry 体系](03-component-lifecycle.md) | 下一篇：[05 · Netty 服务器与 HTTP 请求/响应适配](05-server-and-http.md)

---

## 引子：把启动期算好的缓存，放进一次真实请求

[03 篇](03-component-lifecycle.md) 回答了"三阶段生命周期产出了哪些缓存"——拦截器数组、参数解析器槽位、返回值处理器槽位、线程池引用、Filter 链、CORS Provider。本篇回答下一个问题：**这些缓存在一次真实请求里，到底被谁、在什么时机、以什么方式取出来用？**

换句话说，03 篇讲的是"启动时算一次"，本篇讲的是"运行时只做查表"的后半句。读完本篇你应能精确复现：一个 HTTP 请求从 Netty `channelRead` 到响应 `flush`，中间经过哪些方法、每个方法取用了哪个 Registry 在哪个阶段写入的哪份缓存、线程如何在 EventLoop 与业务线程池之间切换、`ByteBuf` 的引用计数如何在跨线程时保持不泄漏。

一个贯穿全篇的判断：**这套链路的每一段都拒绝"运行时计算"，只做"数组直取 / 单例直调"。** 这不是巧合，而是 [01 篇](01-design-philosophy.md) "启动时确定性"总纲在请求路径上的逐段兑现。

---

## 一、全链路总览

先给一张完整的请求时序图，后续各节按图分段展开。图中的行号是 `DispatcherHandler` / `NettyHttpHandler` 的真实方法锚点：

```
Netty EventLoop 线程
  │
  ▼  channelRead(ctx, FullHttpRequest)
NettyHttpHandler.channelRead              NettyHttpHandler.java
  │  (finally ReferenceCountUtil.release 原始消息)
  ▼
NettyHttpHandler.handleRequest           NettyHttpHandler.java
  │  · shuttingDown? → 503 ()
  │  · 构造 NettyServerHttpResponse(keepAlive) 
  │  · URI 解析 + contextPath 校验 → 404 ()
  │  · msg.retain()  ← ByteBuf 生命周期第一站 
  │  · new NettyServerHttpRequest 
  │  · resp.setTimeout() 
  ▼
DispatcherHandler.httpHandle              DispatcherHandler.java
  ▼
DispatcherHandler.handle                  DispatcherHandler.java
  │  mappingRegistry.mapping(req) → MappingResult   ← 用 Phase2 构建的优化器链
  ▼
DispatcherHandler.handleWithMappingResult DispatcherHandler.java
  │  bizPoolRegistry.determinePool(req, mr)  ← 取 methodCache 槽位的线程池引用
  │
  ├── executor != null（default 池 / 自定义池）:
  │     │  req.acquire()              ← retain，跨线程前加固引用 
  │     ▼  executor.execute(() -> { handleWithFilter; finally req.release() })  ()
  │     │  catch RejectedExecutionException:
  │     │     req.release() 
  │     │     !isShutdown() → 503 + RETRY_AFTER ()
  │     │     isShutdown()  → EventLoop 兜底 handleWithFilter ()
  │     ▼  【业务线程池】
  │
  └── executor == null（EVENTLOOP 零切换）: handleWithFilter ()
        ▼  【仍在 EventLoop】
        │
        ▼  （两种线程汇合于此）
DispatcherHandler.handleWithFilter        DispatcherHandler.java
  │  webFilterRegistry.doFilter(req, resp)   ← 取 PathMappingContext.cachedFilterChain
  │    │  DefaultFilterChain.doFilter          DefaultFilterChain.java
  │    │    requestContext.getFilterIndexAndIncrement()  ← 复用 request.filterIndex
  │    │    index < filters.size()? filters.get(index).doFilter(req,resp,this)
  │    │    否则 → 链尾 dispatcherHandler.handleAfterFilter(...)  
  │    │      │  （RuntimeMappingWebFilter 是链内节点，运行时 include/exclude 匹配）
  │    │      ▼
  │    ▼  Filter 链全部执行完
  │  catch Throwable → handleException + invokeWithRealResult ()
  ▼
DispatcherHandler.handleAfterFilter       DispatcherHandler.java
  │  initContextHolders(LocaleContextHolder) 
  │  mappingResult.isMatched()?
  │    是 → doHandle(req, resp, matchedContext) 
  │    否 → handleWithNoFullMatch → CORS 预检 / 404·405 
  │  finally removeContextHolders ()
  ▼
DispatcherHandler.doHandle                DispatcherHandler.java
  │  corsRegistry.corsHandle(req,resp)         ← 取 corsConfigurationProvider 
  │  interceptorRegistry.preHandle(req,resp)   ← 取 cachedInterceptors 
  │  argumentResolverRegistry.resolveArguments ← 取 methodCache 参数解析器槽 
  │  mappingContext.invoke(args,req,resp)      ← invoker.invoke（MethodHandle/Fast）
  │  returnValueResolverRegistry.resolveReturnValue ← 取 methodCache 返回值处理器槽 
  │  catch → handleException → exceptionRegistry.handle ()
  │  finally:
  │    isAsyncRequest? → afterConcurrentHandlingStarted + 存 METRICS_START_ATTR ()
  │    否 → (preHandlePassed? invokeWithRealResult : skip) + metrics.recordRequest ()
  ▼
DispatcherHandler.invokeWithRealResult   DispatcherHandler.java
  │  interceptorRegistry.postHandle 
  │  interceptorRegistry.afterCompletion 
  │  finally flushResponse 
  ▼
响应 flush → Netty channel 写出 → 请求结束
```

这张图就是本篇全部内容的索引。下面逐段展开，每段都标注"用了 03 篇哪个 Registry 在哪个阶段写入的哪份缓存"。

---

## 二、入口层：NettyHttpHandler

### 2.1 它才是真正的入口，不是 RuntimeMappingWebFilter

00-README 大纲把入口写成"Netty `ChannelInboundHandler` → `RuntimeMappingWebFilter` → `DispatcherHandler`"。核对源码后修正：**`RuntimeMappingWebFilter` 不是入口，它是 Filter 链内的一个运行时包装节点**（见第五节）。真正的 Netty→框架入口是 `NettyHttpHandler`：

```java
// NettyHttpHandler.java
@Slf4j
@ChannelHandler.Sharable                         // 多 pipeline 共享单例
public class NettyHttpHandler extends ChannelInboundHandlerAdapter {
    private final WebContext webContext;
    private final String contextPath;
    private final HttpHandler handler;           // 即 DispatcherHandler
    private volatile boolean shuttingDown;
    // 构造：NettyHttpServer.java new NettyHttpHandler(webContext, contextPath, dispatcher)
```

`NettyHttpHandler` 是一个 `@Sharable` 的 `ChannelInboundHandlerAdapter`——单例由 `NettyHttpServer.java` 构造、 作为构造参数传入 `Http2ChannelInitializer`，再由后者 `addLast` 装配到所有 worker channel 的 pipeline 末尾。它实现 `HttpHandler` 接口的注入对象就是 `DispatcherHandler`。所以"Netty 事件 → 框架"的衔接点是唯一的：

```java
// NettyHttpHandler.java
public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (!(msg instanceof FullHttpRequest)) {
        ctx.fireChannelRead(msg);                // 非 HTTP 消息透传
        return;
    }
    FullHttpRequest request = (FullHttpRequest) msg;
    try {
        handleRequest(ctx, request);
    } finally {
        ReferenceCountUtil.release(request);     // Netty 入站消息原始引用在此释放
    }
}
```

`NettyHttpHandler` 的职责被刻意压到最小（类注释列了四条）：解析 URI、校验 contextPath、构造 request/response、委托 `httpHandle`、异常兜底。**它不做任何业务决策**——路由、线程、参数、返回值全部下放给 `DispatcherHandler`。这种"入口薄、调度厚"的分层，是 [01 篇](01-design-philosophy.md) 原则 6（避免魔法行为）在 I/O 边界的体现：I/O 层只做 I/O 适配，不掺和业务语义。

### 2.2 ByteBuf 生命周期第一站：msg.retain()

```java
// NettyHttpHandler.java
msg.retain();                                                   //
NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctxNetty, msg, resolvedPath);
resp.setTimeout();
try {
    handler.httpHandle(req, resp);                              //
} finally {
    req.release();                                              //
}
```

这一行 `msg.retain()` 是整套引用计数链的第一站，值得单独说清。Netty 的 `channelRead` 默认在返回时释放入站消息（ 的 `ReferenceCountUtil.release`），但框架要让 `FullHttpRequest` 的 `ByteBuf` 跨入业务逻辑、甚至跨线程使用，所以必须在构造 `req` 前 `retain` 一次，让 `req` 持有一份独立引用。`req.release()` 在 `httpHandle` 返回后释放这次 retain——与 `channelRead` 的 finally release 合起来保证：**Netty 原始消息的引用计数在两条路径上都对称收尾，不泄漏、不提前释放**。

跨业务线程时还会再叠一层 `acquire/release`（第四节），形成"Netty retain → req 持有 → 跨线程 acquire → 业务线程 release → req.release → Netty release"的完整链。每一步都显式、可追踪——这是 [01 篇](01-design-philosophy.md) 原则 4（避免阻塞 / 显式引用计数）的硬要求。

### 2.3 关闭期与异常兜底

```java
// NettyHttpHandler.java
if (shuttingDown) {
    NettyServerHttpResponse resp = new NettyServerHttpResponse(webContext, ctxNetty, false);
    resp.sendError(HttpStatus.SERVICE_UNAVAILABLE, "Server is shutting down");
    return;
}
```

优雅关闭时（`setShuttingDown()` 由 `NettyHttpServer.java` 在停止流程中调用），新到请求直接 503，不进 `DispatcherHandler`——避免关闭过程中半处理的请求悬空。外层的 `catch Throwable` 是 I/O 层最后一道兜底：`DispatcherHandler` 漏网的异常在此转成 500，不让异常跑到 Netty 框架层导致连接异常关闭。

---

## 三、路由匹配：DispatcherHandler.handle

```java
// DispatcherHandler.java
public void handle(WebServerHttpRequest req, WebServerHttpResponse resp) {
    // 路由匹配（EventLoop 中执行，路径查找 O(1)~O(n) 足够快）
    MappingResult result = mappingRegistry.mapping(req);
    handleWithMappingResult(req, resp, result);
}
```

路由匹配发生在 `handle`，**仍在 EventLoop 线程**（注释  明示）。`mappingRegistry.mapping(req)` 返回 `MappingResult`，内部走的是 [03 篇](03-component-lifecycle.md) Phase2 由 `MappingRegistry.optimizeMapping()` 构建的多级优化器链（精确路径 `HashMap.get` O(1) → 前缀 → 后缀 → 兜底遍历，详见 [06 篇](06-routing-engine.md)）。

本篇不展开优化器链的内部（那是 06 篇的范畴），只标注一件事：**路由匹配这一步产出的 `MappingResult` 被写回 request，成为后续所有环节取用缓存的"钥匙"**。`DefaultFilterChain` 链尾取 `MappingResult.get(request)`（`DefaultFilterChain.java`）、`PathMappingContext.get(request)` 也是从 `MappingResult` 反查（`PathMappingContext.java`）。一次匹配的结果在整条链路上被反复复用，**绝不重复匹配**——这又是"运行时只做查表"的兑现。

---

## 四、线程调度三分支：handleWithMappingResult

这是请求路径上最关键的一段，也是 [01 篇](01-design-philosophy.md) 原则 3（避免线程模型僵化）的落地代码。

### 4.1 主结构与 determinePool 的缓存取用

```java
// DispatcherHandler.java
protected void handleWithMappingResult(WebServerHttpRequest req, WebServerHttpResponse resp,
                                      MappingResult mappingResult) {
    // 通过 BizPoolRegistry 使用 Phase3 预缓存的线程池，无映射或未标注 @RunInPool 时为 null → EventLoop 同步处理
    ExecutorService executor = bizPoolRegistry.determinePool(req, mappingResult);
    if (executor != null) {
        req.acquire();
        try {
            executor.execute(() -> {
                try {
                    handleWithFilter(req, resp, mappingResult);
                } finally {
                    req.release();
                }
            });
        } catch (RejectedExecutionException e) {
            req.release();
            if (!executor.isShutdown()) {
                resp.getHeaders().set(HttpHeaders.RETRY_AFTER, "5");
                sendError(resp, HttpStatus.SERVICE_UNAVAILABLE, "Too many requests");
            } else {
                handleWithFilter(req, resp, mappingResult);
            }
        }
    } else {
        handleWithFilter(req, resp, mappingResult);
    }
}
```

`bizPoolRegistry.determinePool(req, mappingResult)` 取用的是 [03 篇](03-component-lifecycle.md) 深潜过的 `BizPoolRegistry` `methodCache` 槽位：`BizPoolRegistry` 持 `BIZ_POOL_KEY = MappingCacheKey.createMethodCacheKey(Object.class)`（`BizPoolRegistry.java`），`determinePool` 内部 `mappingContext.get(BIZ_POOL_KEY)` → 命中 `NO_POOL` 哨兵返回 `null`，否则按 `defaultExecuteMode` 策略调 `resolvePool`，从 `pools` 取出启动期由 `initDefaultPoolFromConfig()` 创建的 `default` `ThreadPoolExecutor`（/，`resolvePool` 经 `pools.get` 取出 ），并 `set` 回写缓存（default 池 ；EventLoop 哨兵 /）。**首次请求懒缓存，后续请求一次数组直取**。

### 4.2 三分支语义

| 分支 | 触发条件 | `determinePool` 返回 | 行为 |
|------|----------|---------------------|------|
| default 池（默认） | 无 `@RunInPool`，`pool.default-execute-mode` 缺省=`"default"` | `default` `ThreadPoolExecutor` | `acquire` → 切业务线程 → `finally release` |
| 自定义池 | `@RunInPool("ioPool")` | 命名池 | 同上，切到命名池 |
| EVENTLOOP 零切换 | `@RunInPool(RunInPool.EVENTLOOP)` 或 `pool.default-execute-mode=eventloop` | `NO_POOL` → `null` | `else` 分支，EventLoop 直处理 |

### 4.3 acquire/release 的对称性

`req.acquire()` 在跨线程前 retain `ByteBuf`，`finally req.release()` 在业务线程处理完释放。这是 [01 篇](01-design-philosophy.md) 原则 4 的硬要求：跨线程持引用必须显式加固，否则 EventLoop 回调结束会 release，业务线程拿到的 `ByteBuf` 已被释放——堆外内存踩踏。

### 4.4 RejectedExecutionException 的两条出路

线程池满（队列满 + 线程数达上限）抛 `RejectedExecutionException` 时， 先 `release` 掉刚 `acquire` 的引用（否则泄漏），再按 `executor.isShutdown()` 分两路：

- **`!isShutdown()`（池还在，只是满）→ 503 + `RETRY_AFTER: 5`**。注释明确"不在 EventLoop 重试，避免阻塞 I/O 线程拖垮服务器"——满载时若退回 EventLoop 跑业务，会把 I/O 线程拖死，所以宁可拒绝也不要拖垮。
- **`isShutdown()`（优雅关闭中，池已关）→ EventLoop 兜底 `handleWithFilter`**。关闭期池已不可用，退回 EventLoop 跑完，保证关闭中已接入的请求有始有终。

这两路区分是工程化的细节：满载和关闭是两种本质不同的状态，处理策略相反（一个拒绝、一个兜底），不能用一个 503 笼统处理。

---

## 五、Filter 链：游标式链与链尾衔接

无论走 default 池还是 EventLoop，两条线程路径在 `handleWithFilter` 汇合：

```java
// DispatcherHandler.java
private void handleWithFilter(WebServerHttpRequest req, WebServerHttpResponse resp,
                              MappingResult mappingResult) {
    try {
        webFilterRegistry.doFilter(req, resp);              //
    } catch (Throwable ex) {
        handleException(ex, req, resp);                     // 异常走 exceptionRegistry
        invokeWithRealResult(req, resp, null, ex);         // 仍补 postHandle/afterCompletion
    }
}
```

### 5.1 三段式 resolveFilterChain 与 cachedFilterChain

`webFilterRegistry.doFilter` 内部：

```java
// WebFilterRegistry.java
public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
    DefaultFilterChain chain = resolveFilterChain(request);   //
    chain.doFilter(request, response);                       //
}

protected DefaultFilterChain resolveFilterChain(WebServerHttpRequest request) {
    MappingResult mappingResult = MappingResult.get(request);
    if (!mappingResult.isMatched()) {
        return unmatchedChain;                               // 无匹配用全量链
    }
    PathMappingContext mappingContext = mappingResult.getMatchedContext();
    DefaultFilterChain cached = mappingContext.getCachedFilterChain();   // 实例字段
    if (cached != null) {
        return cached;                                      // 命中直接返回
    }
    synchronized (mappingContext) {                          // DCL
        cached = mappingContext.getCachedFilterChain();
        if (cached == null) {
            List<WebFilter> filters = initCachedFilters(mappingContext);
            cached = new DefaultFilterChain(dispatcherHandler, filters);
            mappingContext.setCachedFilterChain(cached);    // 写回实例字段
        }
    }
    return cached;
}
```

这里取用的正是 [03 篇](03-component-lifecycle.md) 点名的 `PathMappingContext.cachedFilterChain` 实例字段（`PathMappingContext.java`、``）。三类不走 `MappingCacheKey` 整型索引的 Registry 之一（另两类是 `InterceptorRegistry.cachedInterceptors`、`CorsRegistry.corsConfigurationProvider`），因为存的是 `DefaultFilterChain` 这个有类型的领域对象，不需要整型寻址。get-null-check + DCL 模式与 `MappingCacheKey` 消费者完全一致，只是存储位置在实例字段而非 `Object[]` 槽位。

`initCachedFilters`（`WebFilterRegistry.java`）的三态编译期推断是 Phase3 预匹配的延伸：`matchPathRuleToCached` 返回 `ALWAYS`（直接加入）/ `RUNTIME`（包 `RuntimeMappingWebFilter`）/ `NEVER`（跳过）。无路径规则的 filter 永远匹配，有路径规则的交给 `RuntimeMappingWebFilter` 在运行时按请求路径匹配——**编译期能确定的绝不留到运行时，必须运行时匹配的才包装**。

### 5.2 RuntimeMappingWebFilter：链内节点，不是入口

```java
// RuntimeMappingWebFilter.java, 80-87
public class RuntimeMappingWebFilter implements WebFilter {
    private final WebFilter delegate;
    @Nullable private final String[] includePatterns;
    @Nullable private final String[] excludePatterns;
    private final int order;

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) throws Exception {
        if (matches(request.getPath())) {
            delegate.doFilter(request, response, chain);    // 命中才执行被包装 filter
        } else {
            chain.doFilter(request, response);              // 不命中跳过，继续链
        }
    }
}
```

`RuntimeMappingWebFilter` 是 `WebFilter` 链中的一个节点，用 Servlet 规范路径匹配（`ServletFilterPatternUtils.matches`）决定是否执行被包装的 delegate。它和 `DefaultFilterChain` 的关系是组合：链里每个节点都是 `WebFilter`，`RuntimeMappingWebFilter` 是其中"带路径条件"的那一类。**它不接 Netty、不接 Dispatcher，只是链内一个条件分支**——大纲把它列为入口是误读。

### 5.3 DefaultFilterChain：游标式链，零对象创建

```java
// DefaultFilterChain.java
public class DefaultFilterChain implements FilterChain {
    private final DispatcherHandler dispatcherHandler;
    private final List<WebFilter> filters;

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        BaseWebServerHttpRequest requestContext = (BaseWebServerHttpRequest) request.getRequestContext();
        int index = requestContext.getFilterIndexAndIncrement();   //
        if (index < filters.size()) {
            WebFilter next = filters.get(index);
            next.doFilter(request, response, this);                // 递归调下一 filter
        } else {
            dispatcherHandler.handleAfterFilter(request, response, MappingResult.get(request));  // 链尾
        }
    }
}
```

这是整条链路里最精巧的一段。`DefaultFilterChain` **不是**一个迭代器对象，它复用 `request` 自身的 `filterIndex` 字段（`BaseWebServerHttpRequest.java`、 `getFilterIndexAndIncrement`）作为游标。每次 `doFilter` 取游标、自增、判断是否到尾：

- 未到尾 → `filters.get(index).doFilter(req, resp, this)`，被调 filter 处理完调 `chain.doFilter` 推进游标。
- 到尾 → `dispatcherHandler.handleAfterFilter(...)`，**控制权交回 DispatcherHandler**。

两个零开销特征：
1. **零对象创建**：没有 `new Iterator`、没有新建链节点，游标是 request 上一个 `int` 字段。每请求从头开始（`filterIndex=0`），结束时游标归位由 request 生命周期回收。
2. **零状态同步**：游标在 request 上，请求级隔离，天然无并发——即便 filter 链里某个 filter 又触发异步，游标状态也只在当前请求线程可见。

链尾 `handleAfterFilter` 是 Filter 链与主处理的**唯一衔接点**：Filter 链负责横切（包装、校验、改写 request/response），主处理负责业务语义，两者通过这个回调点清晰分界。

### 5.4 路由前置：与 Tomcat 线程模型的根本差异

把前面三节（路由、线程调度、Filter 链）的顺序拼起来，能看到一条与 Tomcat/Spring MVC 截然相反的执行链：

```
本框架：  路由匹配(EventLoop) → determinePool 选池 → 切业务线程 → Filter 链 → doHandler
            DispatcherHandler                                
Tomcat：  分配容器线程 → Filter 链 → 路由匹配(DispatcherServlet.doDispatch) → 主处理
```

本框架在 EventLoop 上先做完路由（`handle`），拿到 `MappingResult` 后才用它选线程池（`handleWithMappingResult`）、切业务线程、跑 Filter 链。Tomcat 的顺序正好倒过来：请求一进 Connector 就分配一个容器线程（固定 HTTP 线程池），Filter 链先跑，路由匹配推迟到 Filter 链尾的 `DispatcherServlet.doDispatch` 内才发生。这个顺序差异不是细节，是两套线程模型的根分歧。

**优点：方法注解定义线程池。** 路由先于线程切换，`determinePool`（`BizPoolRegistry.java`）才能从路由结果取出目标方法（`mappingContext.getMethod()`）读 `@RunInPool` 注解，按方法粒度选池：`@RunInPool(EVENTLOOP)` 留 EventLoop 零切换，`@RunInPool("ioPool")` 切命名池，无注解走全局 `defaultExecuteMode`。IO 密集方法走 ioPool、CPU 密集方法走 cpuPool、要零切换的留 EventLoop——声明式、方法级、框架内置。Tomcat 的线程先于路由，容器线程早已分配，业务方法即便有注解也无法在进 Filter 链前按注解切池，只能在方法体内手工 `CompletableFuture.supplyAsync(task, executor)`——声明式的方法级池选择在容器线程模型下做不到。

**优点：Filter 链方法级预计算。** 路由结果在 Filter 链之前就确定，`PathMappingContext.cachedFilterChain`（ 实例字段，DCL）在首请求时按路由结果的路径规则预编译整条 Filter 链（`initCachedFilters` 的 ALWAYS/RUNTIME/NEVER 三态，§5.1），CORS Provider、拦截器数组（`cachedInterceptors`）同样基于已确定路由预匹配。后续请求一次直取，零运行时匹配。Tomcat 的 Filter 链是全局固定的（`FilterRegistrationBean` 注册时的全局链），带路径条件的 Filter（如 Spring Security 的 `FilterSecurityInterceptor`）要在 Filter 内运行时各自重新 parse 路径、重新 match——每个带条件的 Filter 都重复一遍路由信息计算，无法由容器统一预编译到方法粒度。

**优点：EventLoop 线程隔离。** 路由在 EventLoop 上做（轻量查表 `O(1)~O(n)`，注释明示），重活切业务线程，I/O 线程不被业务阻塞。Tomcat 的容器线程"一包到底"——I/O 读写与业务处理同线程，业务慢直接占住容器线程，靠线程数（如 200）兜底，线程数即并发上限。

**代价：Filter 无法用 Servlet `forward` 改路由目标。** 路由在 Filter 链之前已固化，`MappingResult` 写回 request（`DefaultFilterChain.java` 链尾取 `MappingResult.get(request)`、`PathMappingContext` 反查），Filter 链尾 `handleAfterFilter` 直接消费这个已固化的结果。Servlet 规范的 `RequestDispatcher.forward(path)` 依赖容器重新派发、重新路由到新目标——这套机制在本框架不成立：Filter 链内改请求路径不会触发重新匹配，路由早定了。带路径条件的 `RuntimeMappingWebFilter` 的 include/exclude 只决定 Filter 是否执行，不改路由目标。Servlet `forward`/`redirect` 语义的适配见 [12 篇](12-support-bridge.md)。

一句话：路由前置换来方法级线程池选择 + Filter 链方法级预计算，代价是 Servlet `forward` 语义需重新定义——这正是 [01 篇 原则 3](01-design-philosophy.md#原则-3--避免线程模型僵化业务方掌控何时切换) 的倒置代价：把"何时切换线程"交给业务方，前提是框架先替业务方把路由算好。

---

## 六、上下文初始化与分流：handleAfterFilter

```java
// DispatcherHandler.java
public void handleAfterFilter(WebServerHttpRequest req, WebServerHttpResponse resp, MappingResult mappingResult) {
    boolean initContext = false;
    try {
        initContext = initContextHolders(req, resp);                //
        if (mappingResult.isMatched()) {
            doHandle(req, resp, mappingResult.getMatchedContext()); // 命中走主处理
        } else {
            handleWithNoFullMatch(req, resp, mappingResult);        // 未命中
        }
    } finally {
        if (initContext) {
            removeContextHolders(req, resp);                       //
        }
    }
}
```

`initContextHolders` 初始化 `LocaleContextHolder`（Spring 的 `ThreadLocal` locale 上下文），`threadContextInheritable` 控制是否可被子线程继承。这一步必须在 Filter 链之后、`doHandle` 之前——因为 Filter 可能包装 request（某些 Filter 会替换 request 对象），包装后的 request 要在 `doHandle` 取 locale 时被正确识别，所以上下文初始化锚定在此（注释）。

分流两路：
- **命中** → `doHandle`（第七节，主处理）。
- **未命中** → `handleWithNoFullMatch`：CORS 预检（`CorsUtils.isPreFlightRequest`）走 `handleCorsPreflight`，否则 `handleOnNoMatchMappingContext` 抛 404/405 走 `exceptionRegistry.handle` + `afterCompletion`。404/405 用预构的 `static final` 异常单例，不 `fillInStackTrace`——[01 篇](01-design-philosophy.md) 原则 2 在高频错误路径上的兑现。

`finally` 的 `removeContextHolders` 重置 `ThreadLocal`，防止线程复用导致 locale 串味。`initContext` 标志位避免无谓的 reset（`buildLocaleContext` 返回 null 时不初始化也不 reset）。

---

## 七、主处理 doHandle：逐段标注用了哪份缓存

这是请求路径的核心。每一段都标注取用了 [03 篇](03-component-lifecycle.md) 哪个 Registry 的哪份缓存：

```java
// DispatcherHandler.java
protected void doHandle(WebServerHttpRequest req, WebServerHttpResponse resp,
                         PathMappingContext mappingContext) {
    Object result = null;
    Throwable exception = null;
    long start = metrics.getNanoTime();
    boolean preHandlePassed = false;
    try {
        // cors
        if (corsRegistry.corsHandle(req, resp)) {          // ① 取 corsConfigurationProvider（实例字段）
            resp.flush();
            return;
        }

        // --- preHandle ---
        preHandlePassed = interceptorRegistry.preHandle(req, resp);   // ② 取 cachedInterceptors（实例字段）
        if (!preHandlePassed) {
            resp.flush();
            return;
        }

        // resolve args
        Object[] args = argumentResolverRegistry.resolveArguments(mappingContext, req, resp);  // ③ 取 methodCache 参数解析器槽

        // invoke
        result = mappingContext.invoke(args, req, resp);   // ④ invoker.invoke（MethodHandle/Fast）

        // return value
        returnValueResolverRegistry.resolveReturnValue(result, mappingContext, req, resp);    // ⑤ 取 methodCache 返回值处理器槽
    } catch (Throwable ex) {
        exception = ex;
        handleException(ex, req, resp);                    // ⑥ exceptionRegistry.handle
    } finally {
        if (AsyncSupportUtils.isAsyncRequest(req)) {
            interceptorRegistry.afterConcurrentHandlingStarted(req, resp);   // ⑦ 异步分流
            req.getRequestContext().setAttribute(METRICS_START_ATTR, start); // 存计时戳到 fastAttributes
        } else {
            if (preHandlePassed) {
                invokeWithRealResult(req, resp, result, exception);          // ⑧ postHandle/afterCompletion
            }
            metrics.recordRequest(req.getMethodValue(), mappingContext.getPathRule(),
                    resp.getStatus().value(), metrics.getNanoTime() - start);
        }
    }
}
```

逐段标注：

| 段 | 代码 | 取用的缓存 | 缓存来源（03 篇） |
|----|------|-----------|------------------|
| ① CORS | `corsRegistry.corsHandle` () | `PathMappingContext.corsConfigurationProvider` 实例字段 | `CorsRegistry` Phase2/3，`CorsRegistry.java` get-null-check |
| ② preHandle | `interceptorRegistry.preHandle` () | `PathMappingContext.cachedInterceptors` 实例字段（DCL） | `InterceptorRegistry.java`，`PathMappingContext.java` |
| ③ 参数解析 | `argumentResolverRegistry.resolveArguments` () | `MappingHandlerMethod.methodCache[index]` 懒缓存 | `ArgumentResolverRegistry.java` get-null-check |
| ④ 方法调用 | `mappingContext.invoke` () | `InvokableHandlerMethod.invoker` 字段（启动期初始化） | `InvokableHandlerMethod.java` initInvoker |
| ⑤ 返回值处理 | `returnValueResolverRegistry.resolveReturnValue` () | `MappingHandlerMethod.methodCache[index]` 懒缓存 | `ReturnValueResolverRegistry.java` get/set |
| ⑥ 异常 | `exceptionRegistry.handle` () | 异常处理器映射 | `ExceptionRegistry.java` Phase2 |
| ⑦ 异步分流 | `afterConcurrentHandlingStarted` () | 拦截器数组 | 同 ② |
| ⑧ 后处理 | `invokeWithRealResult` () | 同 ② | 同 ② |

这张表是本篇串联 03 篇的核心价值：**doHandle 的 8 段，每一段都是一次缓存直取，没有一段做运行时匹配**。03 篇讲的"启动时算一次"，在这里兑现为"doHandle 跑一次只做查表"。

### 7.1 preHandle 返回 false 的语义修正

注释记录了一个已修复的语义点：Spring `HandlerExecutionChain.applyPreHandle` 规定 `preHandle` 返回 false 时，`afterCompletion` 只对已通过的拦截器执行，不执行 `postHandle`，也不再全量回调 `afterCompletion`。修复前 `finally` 里的 `invokeWithRealResult` 无条件再回调一次，导致已通过者双调、未进入者误收回调。修复后用 `preHandlePassed` 标志位把关，false 时跳过 `invokeWithRealResult`——`preHandle` 返回 false 直接 `resp.flush(); return`，干净短路。

### 7.2 METRICS_START_ATTR 与 fastAttributes

`METRICS_START_ATTR = RequestAttribute.createAttribute(Long.class)` 是计时戳槽位。异步请求把 `start` 存进 `req.getRequestContext().setAttribute(METRICS_START_ATTR, start)`——这走的是 `BaseWebServerHttpRequest.setAttribute(RequestAttribute, T)`，存进 `fastAttributes[idx]` 数组而非 `ConcurrentHashMap`。异步分发恢复时（第十节）再 `getAttribute(METRICS_START_ATTR)` 取回，一次数组直取。计时戳跨异步边界传递，零哈希、零装箱——[01 篇](01-design-philosophy.md) 原则 2 在请求属性维度的兑现。

---

## 八、方法调用：Invoker 三选与 invokeExact

`doHandle` 的 `mappingContext.invoke(args, req, resp)` 实际调的是继承自 `InvokableHandlerMethod` 的 `invoke`：

```java
// InvokableHandlerMethod.java
public Object invoke(Object[] args, WebServerHttpRequest request, WebServerHttpResponse response) throws Throwable {
    Object value = invoker.invoke(args);        // 运行时只做这一件事
    setResponseStatus(request, response);      // 处理 @ResponseStatus
    return value;
}
```

运行时只做 `invoker.invoke(args)`——`invoker` 是启动期一次性初始化好的（ 字段、`` initInvoker），运行时不再选、不再反射。`invoker` 的三选逻辑全在构造期：

```java
// InvokableHandlerMethod.java, 65-74
public InvokableHandlerMethod(Object bean, Method method) {
    super(bean, method);
    if (bean instanceof Invoker) {              // 路径 A：bean 自身是 Invoker
        this.invoker = (Invoker) bean;
        this.optimized = false;
    } else {
        this.optimized = hasOptimizeAnnotation();   // @Optimize 标在方法或类
        this.invoker = initInvoker();
    }
    // ...
}

private Invoker initInvoker() {
    if (optimized && !IN_NATIVE_IMAGE) {         // 路径 B：@Optimize + 非 GraalVM
        try {
            return createFastInvoker();         // ASM 字节码生成
        } catch (Throwable e) {
            logger.error("create fast invoker error", e);   // 失败回退
        }
    }
    return createCommonInvoker();               // 路径 C：默认 MethodHandle
}
```

三条路径：

| 路径 | 触发 | invoker 实现 | 调用开销 |
|------|------|--------------|---------|
| A | `bean instanceof Invoker`（自定义 Invoker bean） | bean 自身 | 由 bean 决定 |
| B | `@Optimize` + 非 native-image | `FastInvoker`（ASM 生成 `INVOKEVIRTUAL`） | ~10ns |
| C | 默认 / FastInvoker 失败回退 / GraalVM | `MethodHandleInvoker` | ~30ns |

路径 B 的失败回退是健壮性设计：ASM 字节码生成可能在某些 JVM 或受限环境失败，`catch Throwable` 后回退到路径 C 的 `MethodHandleInvoker`，保证功能不丢、只是退到次优调用。GraalVM native-image 下（`` `IN_NATIVE_IMAGE` 检测）直接跳过路径 B——封闭世界不支持运行时字节码生成，强行生成会异常。

### 8.1 MethodHandleInvoker：invokeExact 与签名适配

```java
// MethodHandleInvoker.java
public MethodHandleInvoker(MethodHandle methodHandle) {
    int paramCount = methodHandle.type().parameterCount();
    // 统一适配为 (Object[]) -> Object：
    if (paramCount > 0) {
        MethodHandle spread = methodHandle.asSpreader(Object[].class, paramCount);
        this.methodHandle = spread.asType(MethodType.methodType(Object.class, Object[].class));
    } else {
        MethodHandle noArg = methodHandle.asType(MethodType.methodType(Object.class));
        this.methodHandle = MethodHandles.dropArguments(noArg, 0, Object[].class);
    }
}

@Override
public Object invoke(Object[] args) throws Throwable {
    return methodHandle.invokeExact(args);      // JVM intrinsic
}
```

构造期把任意签名的 `MethodHandle` 统一适配成 `(Object[]) -> Object`：有参用 `asSpreader` 把 `Object[]` 展开为独立参数；无参用 `dropArguments` 忽略掉 `Object[]` 入参。运行时 `invokeExact(args)` 是 JVM intrinsic，JIT 可内化为直接调用——相比 `Method.invoke`（~200ns，access check + 装箱 + 类型校验），省一个数量级。`createCommonInvoker`（`InvokableHandlerMethod.java`）用 `MethodHandles.lookup().unreflect(bridgedMethod).bindTo(bean)` 一次性绑定目标 bean，运行时无需再传 receiver。

完整调用器机制（ASM 字节码生成、`@Optimize` 选型、与 Spring `InvocableHandlerMethod.doInvoke` 对比）留给 [09 篇](09-invoker-bytecode.md)，本篇只点出在请求路径上的落点：**`doHandle` 这一行，是"运行时零反射"原则在主链路上的兑现点**。

---

## 九、后处理与异常兜底

### 9.1 invokeWithRealResult

```java
// DispatcherHandler.java
protected void invokeWithRealResult(WebServerHttpRequest req, WebServerHttpResponse resp, Object result, Throwable exception) {
    try {
        interceptorRegistry.postHandle(req, resp, result);     // 取 cachedInterceptors
        interceptorRegistry.afterCompletion(req, resp, exception);  //
    } catch (Exception e) {
        log.error(e.getMessage(), e);
    } finally {
        flushResponse(resp);                                   //
    }
}
```

`postHandle` + `afterCompletion` 都取用 `cachedInterceptors`（同 preHandle）。`afterCompletion` 传 `exception`——即使业务抛了异常，`afterCompletion` 仍对已通过 preHandle 的拦截器执行清理，这是 Spring 的标准语义。`flushResponse` 只在 `resp.isHandled()` 时 flush，避免重复写。

### 9.2 handleException：catch-safe

```java
// DispatcherHandler.java
protected void handleException(Throwable ex, WebServerHttpRequest req, WebServerHttpResponse resp) {
    log.error("Unhandled exception from request processing: {}", ex.getMessage(), ex);
    try {
        exceptionRegistry.handle(ex, req, resp);              //
    } catch (Throwable handleEx) {
        log.error("Exception handler failed", handleEx);     // 异常处理器自己也炸了
    }
}
```

双层 catch：`exceptionRegistry.handle` 若自己抛异常（业务 `@ExceptionHandler` 写错）， 再 catch 住只记日志，不让异常继续上抛把连接打断。这是 [01 篇](01-design-philosophy.md) 原则 6（避免魔法行为）在异常路径的延伸——**异常处理本身不能成为新的异常源**。`flushResponse` 同样 catch-safe（ catch IOException 只记日志）。

### 9.3 404/405 与未匹配

`handleOnNoMatchMappingContext`：`result.isMethodMismatch()` ? 405 : 404，用 `static final` 异常单例，不 `fillInStackTrace`，走 `exceptionRegistry.handle` + `afterCompletion`——与 `doHandle` 中异常路径行为一致，404/405 不走特殊通道，复用异常处理链。

---

## 十、异步分发：asyncDispatch

```java
// DispatcherHandler.java
public void asyncDispatch(WebServerHttpRequest req, WebServerHttpResponse resp, Object concurrentResult) {
    if (resp instanceof BaseWebServerHttpResponse) {
        ((BaseWebServerHttpResponse) resp).resetHandled();    // 重置 handled 标志
    }
    Object result = null;
    Throwable exception = null;
    try {
        if (concurrentResult instanceof Throwable) {
            exception = (Throwable) concurrentResult;
            exceptionRegistry.handle(exception, req, resp);   // 异步异常
        } else {
            result = concurrentResult;
            PathMappingContext ctx = PathMappingContext.get(req);
            if (ctx != null) {
                returnValueResolverRegistry.resolveReturnValue(concurrentResult, ctx, req, resp);  //
            }
        }
    } catch (Throwable e) {
        log.error(e.getMessage(), e);
    } finally {
        invokeWithRealResult(req, resp, result, exception);   // 补 postHandle/afterCompletion
        Long start = req.getRequestContext().getAttribute(METRICS_START_ATTR);  // 取回计时戳
        if (start != null) {
            PathMappingContext ctx = PathMappingContext.get(req);
            metrics.recordRequest(req.getMethodValue(),
                    ctx != null ? ctx.getPathRule() : null,
                    resp.getStatus().value(), metrics.getNanoTime() - start);
        }
    }
}
```

异步请求（`DeferredResult`/`Callable`/`ResponseEntity` + `@ReactiveSupport` 等）在 `doHandle` 的 finally 走分支：`afterConcurrentHandlingStarted` 通知拦截器"异步开始"，把计时戳存进 `fastAttributes`，**释放当前线程**（请求线程回到 default 池或 EventLoop）。等异步结果就绪，框架在某个线程上调 `asyncDispatch` 恢复：

-  `resetHandled()`——首次 `doHandle` 时返回值处理器把 `handled` 置 true（标记响应"已处理"，但 body 还没写），异步恢复时 reset，让后续 `resolveReturnValue` 能真正写 body。
- `` `concurrentResult` 是 `Throwable` ? 走异常处理 : 走返回值处理（取用 `methodCache` 槽位，同 `doHandle`）。
-  补 `postHandle`/`afterCompletion`——异步请求的拦截器后处理推迟到恢复时才做。
- 取回 `METRICS_START_ATTR`（一次 `fastAttributes` 数组直取），算完整耗时 `recordRequest`。

这条异步路径把 [03 篇](03-component-lifecycle.md) 的 `AsyncSupportRegistry`、`ReturnValueResolverRegistry` 的缓存、`fastAttributes` 数组、`handled` CAS 串在一起：**异步不是新机制，而是主链路在 finally 分流、稍后用同一套缓存恢复**。完整异步流式机制（`NettyStreamSender` 无锁 drain loop、背压）留给 [11 篇](11-async-streaming.md)。

---

## 十一、HTTP 适配层零开销特征

请求路径上反复出现的几个"零开销"特征，集中在 HTTP 适配基类，这里集中说明：

### 11.1 fastAttributes：数组替代 Map

```java
// BaseWebServerHttpRequest.java
protected final Map<String, Object> attributes = new ConcurrentHashMap<>();
protected final Object[] fastAttributes = new Object[RequestAttribute.getMaxSize()];

// BaseWebServerHttpRequest.java
public <T> T getAttribute(RequestAttribute<T> key) {
    int idx = key.getIndex();
    if (idx < fastAttributes.length) {
        return (T) fastAttributes[idx];                    // 数组直取
    }
    return (T) attributes.get(FAST_ATTR_PREFIX + idx);      // 超界回退 Map
}
```

`RequestAttribute.getMaxSize()` 在启动期确定（`RequestAttribute` 用 `AtomicInteger` 分配索引， FAST_ATTR_PREFIX 是回退 Map 的 key 前缀）。热点属性（`METRICS_START_ATTR`、`REQUEST_ATTRIBUTE_ARRAY`、`PathMappingContext` 数组）走 `fastAttributes[idx]`——**整条 get/set 路径不创建任何对象**：不分配 `Entry`、不计算 hashCode、不装箱。只有属性数超过预分配上限才回退 `ConcurrentHashMap`，这是有界可观测的降级而非静默分配（[01 篇](01-design-philosophy.md) 原则 2）。

### 11.2 handled/committed 双 CAS

```java
// BaseWebServerHttpResponse.java
protected AtomicBoolean handled = new AtomicBoolean(false);
protected AtomicBoolean committed = new AtomicBoolean(false);

// BaseWebServerHttpResponse.java
public boolean setHandled() {
    return handled.compareAndSet(false, true);              // 整个请求只成功一次
}
protected boolean setCommitted() {
    setHandled();
    boolean result = committed.compareAndSet(false, true);
    if (result) setTimeout(null, -1);                       // 提交即取消超时
    return result;
}
```

`handled` 标记"响应已被某段代码接管"（写 body、sendError 等），CAS 保证全请求只成功一次——重复写（如 `doHandle` 正常返回后又 `handleException`）会被  的 `setHandled()` 返回 false 拦住，只 `log.warn` 不重复写。`committed` 标记"响应已刷到底层 channel"，提交即 `setTimeout(null,-1)` 取消超时——避免已完成的请求还触发超时 503。两个 CAS 把"响应状态机"做到无锁、单次、确定。

### 11.3 getBody 延迟分配与 filterIndex 游标

```java
// BaseWebServerHttpResponse.java
@Override
public OutputStream getBody() {
    if (body == null) body = new ByteArrayOutputStream();   // 首次写才分配
    return body;
}
```

响应 body 缓冲延迟到首次 `getBody()` 才分配——无 body 的请求（204、304、CORS 预检）零分配。Netty 子类 `NettyServerHttpResponse` 进一步用 `ByteBuf` 替代 `ByteArrayOutputStream`，大 body（>4KB）走 `retainedDuplicate()` 零拷贝（[05 篇](05-server-and-http.md) 展开）。

```java
// BaseWebServerHttpRequest.java, 85
protected int filterIndex = 0;
public int getFilterIndexAndIncrement() { return filterIndex++; }
```

`filterIndex` 是 Filter 链游标（第五节 `DefaultFilterChain` 复用），请求级 `int` 字段，零对象创建。这些细节单独看微不足道，累积起来就是 [01 篇](01-design-philosophy.md) 原则 2 所说的"每请求减少 1-2 个短期对象分配，在 28K ops/s 下每分钟减少百万级分配"。

---

## 十二、全链路缓存标注总表

把前面各节的取用关系汇总成一张表，这是本篇串联 [03 篇](03-component-lifecycle.md) 的最终交付——一张表看清"03 篇产出的缓存在 04 篇的哪里被取用"：

| 请求环节 | 代码位置 | 取用的缓存 | 缓存类型 | 写入者（03 篇） | 写入时机 |
|---------|---------|-----------|---------|----------------|---------|
| 路由匹配 | `DispatcherHandler` | 优化器链（simpleUrlList 等） | `MappingRegistry` 内部结构 | `MappingRegistry.optimizeMapping` | Phase2 |
| 线程调度 | `DispatcherHandler` | `BizPoolRegistry.BIZ_POOL_KEY` 槽 | `methodCache[index]` | `BizPoolRegistry.determinePool` 首次懒缓存 | 首请求 |
| Filter 链 | `WebFilterRegistry` | `PathMappingContext.cachedFilterChain` | 实例字段（DCL） | `WebFilterRegistry.initCachedFilters` | 首请求 |
| Filter 节点匹配 | `RuntimeMappingWebFilter` | include/exclude patterns | 构造期固定 | `WebFilterRegistry.initAllFilters` | Phase3 |
| CORS | `DispatcherHandler` | `PathMappingContext.corsConfigurationProvider` | 实例字段（get-null-check） | `CorsRegistry` | 首请求 |
| 拦截器 preHandle | `DispatcherHandler` | `PathMappingContext.cachedInterceptors` | 实例字段（DCL） | `InterceptorRegistry.initCachedInterceptors` | 首请求 |
| 参数解析 | `DispatcherHandler` | `MappingHandlerMethod.methodCache[index]` | `Object[]` 槽 | `ArgumentResolverRegistry` 首次懒缓存 | 首请求 |
| 方法调用 | `DispatcherHandler` | `InvokableHandlerMethod.invoker` | 实例字段 | `InvokableHandlerMethod.initInvoker` | 构造期 |
| 返回值处理 | `DispatcherHandler` | `MappingHandlerMethod.methodCache[index]` | `Object[]` 槽 | `ReturnValueResolverRegistry` 首次懒缓存 | 首请求 |
| 异常处理 | `DispatcherHandler` | 异常处理器映射 | `ExceptionRegistry` 内部 | `ExceptionRegistry` | Phase2 |
| 拦截器 postHandle/afterCompletion | `DispatcherHandler/244` | `cachedInterceptors` | 同 preHandle | 同上 | 同上 |
| 异步恢复 | `DispatcherHandler` | `methodCache` 返回值槽 | 同返回值处理 | 同上 | 同上 |
| 计时戳传递 | `DispatcherHandler/309` | `fastAttributes[METRICS_START_ATTR.idx]` | `Object[]` 数组 | `RequestAttribute` 启动期分配索引 | 启动期 |

这张表回答了本篇引子的问题：**03 篇产出的每一份缓存，都能在这张表里找到它在请求路径上的取用点；而请求路径上的每一段业务逻辑，背后都挂着一份启动期算好的缓存。** 这就是"启动时确定性 → 运行时只做查表"在请求管线全链路上的完整兑现。

---

## 小结

本篇把 [03 篇](03-component-lifecycle.md) 的缓存机制放进了真实请求路径。核心结论三条：

1. **链路是分层的，每层只做一件事**：`NettyHttpHandler` 只做 I/O 适配（入口薄），`DispatcherHandler.handle` 只做路由，`handleWithMappingResult` 只做线程调度，`DefaultFilterChain` 只做横切编排，`doHandle` 只做业务语义。层与层之间通过明确的回调点衔接（`httpHandle` → `handle` → `doFilter` → `handleAfterFilter` → `doHandle` → `invokeWithRealResult`），没有隐式跳转。

2. **每一段都是缓存直取，没有运行时匹配**：从路由的优化器链到 `BizPoolRegistry` 的线程池槽、`PathMappingContext` 的三个实例字段、`MappingHandlerMethod` 的 `methodCache`/`classCache`、`InvokableHandlerMethod` 的 `invoker`——主链路上没有一处做 `supports` 遍历、反射调用、运行时类型推断。03 篇的"启动时算一次"在这里兑现为"doHandle 跑一次只做查表"。

3. **线程与内存是显式契约，不是隐式托管**：`acquire/release` 对称跨线程管理 `ByteBuf`（原则 4），`RejectedExecutionException` 分满载与关闭两路（原则 3），`handled`/`committed` CAS 保证响应状态机无锁单次（原则 2），`fastAttributes` 数组替代 Map（原则 2）。这些显式契约是性能的来源，也是框架"克制的源码"在请求路径上的集中体现。

下一篇转入 I/O 层：[05 · Netty 服务器与 HTTP 请求/响应适配](05-server-and-http.md)——把本篇里反复出现的 `NettyHttpHandler`、`NettyServerHttpRequest`、`NettyServerHttpResponse`、`msg.retain()`、`retainedDuplicate()` 放回 Netty 的真实环境，讲清服务器如何启动、Channel pipeline 如何装配、`ByteBuf` 如何零拷贝包装与写回。
