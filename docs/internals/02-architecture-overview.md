# 02 · 模块拓扑与启动期全景

> [← 返回索引](00-README.md) | 上一篇：[01 · 设计哲学与六大取舍原则](01-design-philosophy.md) | 下一篇：[03 · 三阶段组件生命周期与 Registry 体系](03-component-lifecycle.md)

---

## 引子：从哲学到地图

[01 篇](01-design-philosophy.md) 把"为什么快"落到了六条代码规范级原则。但原则需要载体——这些原则写在哪些 Maven 模块里、启动时按什么顺序拼装、一次 HTTP 请求跨了多少个类？本篇是全系列的**地图**：用一张依赖矩阵 + 一张包拓扑 + 一条启动时序 + 一条请求调用链，把"四大模块"的真实结构交代清楚。

读完本篇，你应能回答：**为什么这套框架要拆成四个 jar，而不是塞进一个？support 和 batch 为什么是 `provided` 依赖？一个请求从 Netty 字节到业务方法，到底经过了哪些模块的哪些类？**

本篇另有一项隐性职责：**校正 `../../.agent/context/module.md` 中两处与当前源码不符的描述**。module.md 是架构概览基线，仍有效，但其中"生命周期由 `afterPropertiesSet()` 触发"和"support 的 `servlet/filter/match/` 子包"两点与 `master` 当前源码矛盾——本篇以源码行号为准，给出校正。

---

## 一、四模块职责矩阵

顶层 `pom.xml` 聚合了 9 个模块（[`pom.xml`](../../pom.xml)），本系列聚焦其中与 Web 框架直接相关的四个：

| 模块 | 职责 | 依赖谁 | 被谁依赖 | 关键包 | 可选性 |
|------|------|--------|----------|--------|--------|
| **spring-web** | 核心框架：Netty 服务器 + 路由 + 参数/返回值解析 + 调用器 + 横切 | Spring（context/core/beans/aop/web）+ Netty + Jackson；fastjson2/reactive-streams 为 `provided` | 其余三者 | `context` `core` `http` `server` `json` `annotation` `util` | **基石**，不可选 |
| **spring-web-support** | Servlet API 与 SpringMVC 桥接层 | spring-web + `jakarta.servlet-api`(provided) | starter（provided） | `support.*` + 重写的 `org.springframework.web.servlet.*` | 可选 |
| **spring-web-batch** | LMAX Disruptor 透明请求聚合 | spring-web + `disruptor` | starter（provided） | `batch.*` | 可选 |
| **spring-boot-starter-web** | Spring Boot 自动装配 + 零冲突启动 | spring-web + support(provided) + batch(provided) + spring-boot-starter + starter-json + actuator(provided) | 用户业务应用 | `autoconfigure.*` | 入口，必选 |

### 1.1 依赖矩阵的代码事实

四份 `pom.xml` 印证了上表（以下只列 compile 范围的"对外"依赖，test 依赖略）：

**spring-web**（[`spring-web/pom.xml`](../../spring-web/pom.xml)）只引 Spring 五件套 + Netty + Jackson，外加三个 `provided`：

```xml
<!-- spring-web/pom.xml -->
<dependency>
    <groupId>org.reactivestreams</groupId>
    <artifactId>reactive-streams</artifactId>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>com.google.code.findbugs</groupId>
    <artifactId>jsr305</artifactId>
    <version>3.0.2</version>
    <scope>provided</scope>
</dependency>
```

`reactive-streams` 是 `provided`——意味着核心的响应式支持（`ReactiveReturnValueResolver` 等）在 classpath 有 reactive 库时才真正生效，没有也不报错。这是"可选能力"的依赖表达。

**spring-web-support**（[`spring-web-support/pom.xml`](../../spring-web-support/pom.xml)）只依赖 spring-web + `jakarta.servlet-api(provided)`：

```xml
<!-- spring-web-support/pom.xml -->
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web</artifactId>
</dependency>
<dependency>
    <groupId>jakarta.servlet</groupId>
    <artifactId>jakarta.servlet-api</artifactId>
    <scope>provided</scope>
</dependency>
```

**spring-web-batch**（[`spring-web-batch/pom.xml`](../../spring-web-batch/pom.xml)）只依赖 spring-web + `disruptor`。

**spring-boot-starter-web**（[`spring-boot-starter-web/pom.xml`](../../spring-boot-starter-web/pom.xml)）的关键设计在两处 `provided`：

```xml
<!-- spring-boot-starter-web/pom.xml -->
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-support</artifactId>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-batch</artifactId>
    <scope>provided</scope>
</dependency>
```

### 1.2 `provided` = "类路径即开关"

support 与 batch 对 starter 是 `provided`，这是一个有意的设计决策，不是疏忽：

- **`provided` 的 Maven 语义**：编译期可见（starter 的 autoconfig 能引用 support 的类），但不传递到最终应用 classpath。
- **实际效果**：用户应用的 `pom.xml` 若显式引入 `spring-web-support`，桥接层进 classpath，starter 的 `SpringWebSupportAutoConfiguration`（`@ConditionalOnClass` 命中）自动激活；若不引入，桥接层不存在，核心仍能独立运行。
- **哲学对应**：这恰好是 [01 篇 原则 6](01-design-philosophy.md#) "避免魔法行为——显式 SPI、显式 fail-fast"的反面印证——这里不是"靠条件猜测"，而是"依赖是否在场本身就是显式声明"。条件装配的触发条件（classpath 上有某个类）是用户用 Maven 坐标写明的，可追溯、可排查。

### 1.3 starter 排除 Tomcat 的"反向防线"

starter 还做了一件容易被忽略的事：引入 SBA 客户端时，**显式排除**其传递的 `spring-boot-starter-web`（即 Spring 官方的 Tomcat starter）：

```xml
<!-- spring-boot-starter-web/pom.xml -->
<dependency>
    <groupId>de.codecentric</groupId>
    <artifactId>spring-boot-admin-client</artifactId>
    <scope>provided</scope>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

Tomcat 的 `spring-boot-starter-web` 一旦混入，会和本框架在自动装配层争抢 `WebServer`——这是 [14 篇](14-starter-autoconfig.md) 要详述的"零冲突启动"防线之一：在依赖源头就把可能的冲突掐掉，而不是等运行时检测。

### 1.4 版本基线

顶层 `pom.xml` 锁定全局版本（[`pom.xml`](../../pom.xml)）：

| 组件 | 版本 | 来源 |
|------|------|------|
| Spring Boot | 3.5.16（默认，Profile 可切 2.4.x~4.1.x） | `pom.xml` + profiles `` |
| Netty | 4.1.137.Final | `pom.xml` |
| Jackson | 2.17.2（SB4 时对齐 2.21.4） | `pom.xml`  |
| Fastjson2 | 2.0.60（provided） | `pom.xml` |
| Disruptor | 3.4.4 | `pom.xml` |
| Java | 17 | `pom.xml` |

> 多版本兼容（parent→BOM+profiles 改造）的来龙去脉见 memory `sb-3x-compatibility-verification`，本篇不展开。

---

## 二、spring-web 核心包拓扑

四模块中，`spring-web` 是真正的核心。它的源码根包 `io.springperf.web` 下的包结构如下（以实际 `Glob` 结果为准，非凭记忆）：

```
io.springperf.web
│
├── context/                     组件容器 + 三阶段生命周期
│   ├── WebComponent               组件根接口（extends Ordered）
│   ├── LifecycleWebComponent      三阶段生命周期接口（Phase1/2/3 + destroyComponent，全 default）
│   ├── BaseWebComponent           抽象基类，持有 WebContext
│   ├── WebComponentContainer      组件注册表基类（State 状态机 + 冲突解析 + 生命周期重放）
│   ├── WebContext                 应用上下文（extends WebComponentContainer, implements InitializingBean）
│   ├── ApplicationProperties      类型安全配置访问
│   └── PropertiesConstant         配置键常量
│
├── server/                       Netty 服务器
│   ├── HttpHandler                顶层 HTTP 处理策略接口
│   ├── NettyHttpHandler           Netty ChannelHandler，适配 Netty → 框架请求/响应（@Sharable）
│   ├── NettyHttpServer            implements SmartLifecycle, LifecycleWebComponent（启动/停止 Netty）
│   ├── NettyHttpHandler.SslExceptionHandler   SSL 异常兜底（静态单例）
│   ├── Http2ChannelInitializer    HTTP/2 + HTTP/1 pipeline 装配
│   ├── PipelineCustomizer         pipeline 扩展点（如 WebSocket 握手 handler）
│   └── NettyMetricsHandler        连接计数 + 指标（静态单例）
│
├── http/                         HTTP 请求/响应抽象
│   ├── WebServerHttpRequest / WebServerHttpResponse   统一接口
│   ├── BaseWebServerHttpRequest / BaseWebServerHttpResponse   抽象基类（fastAttributes / setCommitted）
│   ├── NettyServerHttpRequest / NettyServerHttpResponse        Netty 实现
│   ├── RequestAttribute / RequestContext / ConnectionContext
│   ├── BackpressureHandler        背压处理器
│   └── support/                   HTTP 支持类（multipart、BodyHttpInputMessage 等）
│
├── core/                         核心请求处理（11 个子包）
│   ├── DispatcherHandler          中央分发器（extends BaseWebComponent, implements HttpHandler）
│   ├── filter/                    WebFilter 过滤器链
│   │   ├── WebFilter                SPI 接口（extends WebComponent）
│   │   ├── FilterChain / DefaultFilterChain
│   │   ├── WebFilterRegistry        管理过滤器（extends WebComponentContainer）
│   │   ├── WebFilterRegistration
│   │   ├── RuntimeMappingWebFilter  运行时路径匹配的 Filter 包装 ← 核心路由匹配落点
│   │   └── AccessLogWebFilter       访问日志
│   ├── mapping/                   请求映射 + 路由引擎（match/ + route/）
│   ├── arg/                       参数解析（provider/ + resolver/ + databinder/）
│   ├── retval/                    返回值解析（resolver/ + async/）
│   ├── invoker/                   方法调用（Invoker / InvokableHandlerMethod / FastInvokerGenerator）
│   ├── interceptor/              处理器拦截器
│   ├── codec/                    HTTP 消息编解码（HttpBodyConverter + interceptor/）
│   ├── cors/                     跨域
│   ├── exception/                异常处理
│   ├── async/                    异步支持（reactive/ + stream/）
│   ├── resource/                 静态资源
│   ├── pool/                     业务线程池（BizPoolRegistry）
│   └── metrics/                  指标（WebMetrics / NoOpWebMetrics）
│
├── json/                         JSON 抽象层
│   ├── JsonConverter               SPI（toJson/fromJson 三重重载）
│   ├── JacksonConverter           默认实现
│   └── FastjsonConverter          Fastjson2 实现（provided）
│
├── annotation/                   自定义注解
│   ├── @Optimize                   标记 ASM 调用优化
│   ├── @ReactiveSupport            背压参数
│   └── @RunInPool                  业务线程池指定
│
└── util/                         工具类（含 support/ 子包）
    └── PathPatternUtils / WebUtils / MetaUtils / DefaultLoggerUtil / Pair
```

### 2.1 拓扑的两个观察点

**观察一：`core` 是"请求处理主链路"的集中地。** 11 个子包对应请求处理的 11 个横切关注点——从 `filter`（前置过滤）到 `mapping`（路由）到 `arg`/`retval`/`invoker`（参数/返回值/调用）到 `interceptor`/`cors`/`exception`（横切）到 `async`/`resource`/`pool`/`metrics`。`DispatcherHandler` 持有其中九个 Registry 的引用（[`DispatcherHandler.java`](../../spring-web/src/main/java/io/springperf/web/core/DispatcherHandler.java)），是这条链的编排者。

**观察二：核心 `filter` 包内含 `RuntimeMappingWebFilter`，这是"路径匹配的 Filter 包装"的真正落点。** 这一事实将在 [§五 校正二](#五与-modulemd-的差异校正) 展开——它纠正了 module.md 把路径匹配工具归到 support 的描述。

### 2.2 support 与 batch 的包拓扑（简表）

support 的源码在 `io.springperf.web.support.*`（[`Glob` 结果](../../spring-web-support/src/main/java/io/springperf/web/support)），按桥接职责分包：

| support 子包 | 职责 | 代表类 |
|--------------|------|--------|
| `support` | 扩展分发器 | `SupportDispatcherHandler` |
| `support.mvc.config` | WebMvcConfigurer 翻译中枢 | `WebMvcConfigurerBridge` |
| `support.mvc.arg` / `support.mvc.retval` / `support.mvc.exception` / `support.mvc.interceptor` | Spring MVC 四类 SPI 适配 | `SpringHandlerMethodArgumentResolverProvider` 等 |
| `support.arg.provider` | Servlet 请求/响应参数解析 | `HttpServletRequestProvider` 等 |
| `support.codec.interceptor` | RequestBodyAdvice/ResponseBodyAdvice 适配 | `SupportHttpBodyCodecInterceptorRegistry` |
| `support.async.stream` | `ResponseBodyEmitter` 适配 | `ResponseBodyEmitterReturnValueResolver` |
| `support.servlet` / `support.servlet.session` / `support.servlet.context` | Servlet API 桥接（HttpServletRequest 等） | `PerfHttpServletRequest` / `PerfHttpSession` |
| `support.servlet.filter` | **Servlet Filter 桥接**（注意：与核心 `core/filter` 是两回事） | `FilterWrapper` / `SupportWebFilterRegistry` / `PerfHttpServletFilterChain` |
| `org.springframework.web.servlet.*`（重写） | 同包同名覆盖 Spring 类 | `PathMatchConfigurer` 等 |

batch 的源码在 `io.springperf.web.batch.*`，核心是 Disruptor 透明聚合——具体类留到 [13 篇](13-batch-module.md) 展开，本篇只点明它的**切入点**：它在请求链路上"拦截"了返回值写出阶段，把多个并发请求的结果聚合后批量返回。

> **一个必须澄清的命名陷阱**：support 的 `support.servlet.filter` 包里也有 "Filter"，但它桥接的是 `jakarta.servlet.Filter`（Servlet API 的 Filter），通过 `FilterWrapper` 适配进核心 `WebFilter` SPI。这与核心 `core/filter.RuntimeMappingWebFilter`（框架自有路径匹配 Filter）是**两个不同概念**，不要混淆。

---

## 三、启动期协作时序

### 3.1 完整启动链（校正版）

module.md 的启动时序图（`module.md`）把生命周期触发点写成 `WebContext.afterPropertiesSet()`——**这与当前源码不符**。校正后的完整链路如下：

```
Spring Boot 启动
  │
  ├─ ApplicationContextFactory = WebServerApplicationContextFactory
  │     （@Order(-10000)，强制返回 AnnotationConfigApplicationContext）
  │     → WebServerApplicationContextFactory.java
  │
  ├─ ApplicationContext.refresh()
  │     ├─ 实例化并装配所有 Bean：
  │     │   · WebContext（holds DispatcherHandler + 各 Registry）
  │     │   · NettyHttpServer（SmartLifecycle, phase=Integer.MAX_VALUE）
  │     │   · 各 Registry Bean（MappingRegistry / ArgumentResolverRegistry / ...）
  │     │   · 若 support 在 classpath：SupportDispatcherHandler / WebMvcConfigurerBridge / ...
  │     │   · 若 batch 在 classpath：Batch 相关组件
  │     │   （注意：WebContext.afterPropertiesSet() 此刻是 no-op，什么都不做）
  │     │     → WebContext.java
  │     │
  │     └─ refresh 末尾，LifecycleProcessor 按 phase 升序调用所有 SmartLifecycle.start()
  │         · NettyHttpServer.getPhase() = Integer.MAX_VALUE  → 最后启动
  │           → NettyHttpServer.java
  │
  ├─ NettyHttpServer.start()                                   → NettyHttpServer.java
  │     │
  │     ├─ webContext.startLifecycle()                          →
  │     │     │  （CAS 守卫 lifecycleStarted，保证只跑一次）
  │     │     ├─ initWithWebContext(this)                       → WebContext.java  注入上下文
  │     │     ├─ initComponentPhase1()  扫描/收集元数据          →
  │     │     ├─ initComponentPhase2()  跨组件连接/路由优化       →
  │     │     └─ initComponentPhase3()  预缓存/fail-fast          →
  │     │        （MappingRegistry.optimizeMapping() 在此阶段构建 RouterOptimizer 链）
  │     │
  │     ├─ 取 DispatcherHandler（启动期单线程，运行时纯读）       → 
  │     ├─ new NettyHttpHandler(webContext, contextPath, dispatcher)  →
  │     ├─ 配置 boss/worker EventLoopGroup + ServerBootstrap     → 
  │     └─ bootstrap.bind(port).sync()                           →
  │        ├─ actualPort 写入 local.server.port 系统属性         → 
  │        └─ log.info("Netty Server started on port {}")       →
  │
  └─ 就绪，开始接受请求
```

### 3.2 为什么生命周期锚定在 `start()` 而非 `afterPropertiesSet()`

`WebContext` 虽实现了 `InitializingBean`，却把 `afterPropertiesSet()` 留空：

```java
// WebContext.java
@Override
public void afterPropertiesSet() {
    // No-op: lifecycle is now deferred to startLifecycle(),
    // triggered by NettyHttpServer#start().
}
```

真正编排三阶段的是 `startLifecycle()`（[`WebContext.java`](../../spring-web/src/main/java/io/springperf/web/context/WebContext.java)），由 `NettyHttpServer.start()` 显式触发（[`NettyHttpServer.java`](../../spring-web/src/main/java/io/springperf/web/server/NettyHttpServer.java)）。

**推迟的理由**：support 的 `WebMvcConfigurerBridge` 必须在 Spring 容器加载完**所有**用户 `WebMvcConfigurer` Bean 之后，才能收集到完整的 shim 数据（拦截器注册、参数解析器、跨域配置等）。若在 `afterPropertiesSet()`（Bean 初始化期）就跑 Phase1，彼时部分 `WebMvcConfigurer` 可能尚未实例化，收集到的输入不完整——违背 [01 篇总纲](01-design-philosophy.md#一总纲启动时确定性) "确定性首先要求输入完整"。

锚定在 `NettyHttpServer.start()` 的精妙之处：`NettyHttpServer` 的 `getPhase() = Integer.MAX_VALUE`（[`NettyHttpServer.java`](../../spring-web/src/main/java/io/springperf/web/server/NettyHttpServer.java)），是 Spring `SmartLifecycle` 中**最后一个启动**的组件。此刻所有 Bean（含 support 桥接、用户 `WebMvcConfigurer`）均已就位——`startLifecycle()` 拿到的输入是完整的。

### 3.3 `SmartLifecycle` 与"端口绑定在生命周期之后"

`NettyHttpServer` 同时实现了 `SmartLifecycle` 与 `LifecycleWebComponent`，二者职责分离：

- **`SmartLifecycle.start()`** 负责"触发 WebContext 生命周期 + 绑定端口"——对 Spring 容器的契约。
- **`LifecycleWebComponent.destroyComponent()`** 负责"关闭 EventLoopGroup"——对框架组件体系的契约。

注意一个细节：`stop()` 里**不**关闭 EventLoopGroup，只 `setShuttingDown()` 拒绝新请求 + 关 `serverChannel`（[`NettyHttpServer.java`](../../spring-web/src/main/java/io/springperf/web/server/NettyHttpServer.java)）。EventLoop 的真正关闭推迟到 `destroyComponent()`，**在 BatchRegistry / BizPoolRegistry 等组件排空之后**——这保证优雅关闭期间，在途请求还能用 EventLoop 完成。`stop()` 注释写得很直白：

```java
// NettyHttpServer.java
// EventLoop 关闭已移至 destroyComponent()，在 BatchRegistry / BizPoolRegistry 等组件排空后执行
```

### 3.4 优雅关闭的 503 兜底

关闭期间到达的新请求，`NettyHttpHandler` 直接返回 503：

```java
// NettyHttpHandler.java
if (shuttingDown) {
    NettyServerHttpResponse resp = new NettyServerHttpResponse(webContext, ctxNetty, false);
    try {
        resp.sendError(HttpStatus.SERVICE_UNAVAILABLE, "Server is shutting down");
    } catch (Exception ignored) {
        log.debug("sendError 503 failed", ignored);
    }
    return;
}
```

这是"优雅关闭"的硬契约：新请求不进链路、不耗资源，直接 503；旧请求在 EventLoop 上跑完。

---

## 四、一次请求的跨模块调用链

下面是一次完整请求的 ASCII 调用链，**每一步标注其所属模块**（`[web]` = spring-web 核心，`[support]` = spring-web-support，`[batch]` = spring-web-batch，`[netty]` = Netty 库本身）：

```
[netty]  TCP 连接到达 worker EventLoop
  │
  │  HttpServerCodec 解码 → FullHttpRequest
  ▼
[netty]  BackpressureHandler（背压）
  ▼
[netty]  SupportMultipartAggregator（可选，multipart 聚合）
  ▼
[web]    NettyHttpHandler.channelRead()                          NettyHttpHandler.java
  │       ├─ 校验 contextPath
  │       ├─ msg.retain()← 跨线程前引用计数 +1 的前置准备
  │       └─ new NettyServerHttpRequest / NettyServerHttpResponse
  ▼
[web]    DispatcherHandler.httpHandle() → handle()               DispatcherHandler.java
  │       └─ MappingRegistry.mapping(req) → MappingResult
  │            （RouterOptimizer 链：FullPath → Prefix → Suffix → Loop）
  ▼
[web]    DispatcherHandler.handleWithMappingResult()             
  │       └─ BizPoolRegistry.determinePool(req, mappingResult)
  │           ├─ != null → req.acquire() + executor.execute（默认走
  │           │             "default" 业务线程池，发生一次切换）       
  │           │   └─ RejectedExecutionException → req.release() + 503 
  │           └─ == null → EventLoop 直处理（@RunInPool(EVENTLOOP)
  │                         或 pool.default-execute-mode=eventloop）
  ▼
[web]    WebFilterRegistry.doFilter()  ← handleWithFilter()      
  │       └─ DefaultFilterChain 逐 Filter 执行
  │           ├─ RuntimeMappingWebFilter（路径匹配的 Filter 包装）   core/filter
  │           ├─ AccessLogWebFilter（访问日志）
  │           └─ [support] FilterWrapper 包装的 Servlet Filter（若引入 support）
  │       └─ 链尾回调 → DispatcherHandler.handleAfterFilter()
  ▼
[web]    DispatcherHandler.handleAfterFilter()                   
  │       ├─ initContextHolders（LocaleContextHolder 等）           
  │       └─ mappingResult.isMatched() ?
  │           ├─ YES → doHandle()
  │           └─ NO  → handleWithNoFullMatch()（CORS 预检 / 404 / 405） 
  ▼
[web]    doHandle()                                              
  │       ├─ CorsRegistry.corsHandle()                              
  │       ├─ InterceptorRegistry.preHandle()                        
  │       ├─ ArgumentResolverRegistry.resolveArguments()
  │       │     └─ [support] SpringHandlerMethodArgumentResolverProvider（若引入 support）
  │       ├─ mappingContext.invoke(args, req, resp)
  │       │     └─ Invoker：ASM INVOKEVIRTUAL（@Optimize）或 MethodHandle.invokeExact
  │       ├─ ReturnValueResolverRegistry.resolveReturnValue()
  │       │     ├─ [web]     JsonBodyReturnValueResolver → HttpBodyCodecRegistry.writeBody()
  │       │     ├─ [support] ResponseBodyEmitterReturnValueResolver（若引入 support）
  │       │     ├─ [web]     StreamEmitterReturnValueResolver → NettyStreamSender（SSE/流式）
  │       │     └─ [batch]   BatchReturnValueResolver（若引入 batch，透明聚合）
  │       └─ finally: invokeWithRealResult() / metrics.recordRequest() 
  ▼
[web]    invokeWithRealResult()                                  
  │       ├─ InterceptorRegistry.postHandle()
  │       ├─ InterceptorRegistry.afterCompletion()
  │       └─ flushResponse()
  ▼
[web]    NettyServerHttpResponse.flush() → channel.writeAndFlush(ByteBuf)
  │
[netty]  写回 TCP
```

### 4.1 调用链的三个读法

**读法一：模块切入点在哪。** support 在链路上有四个切入点——参数解析（`SpringHandlerMethodArgumentResolverProvider`）、返回值（`ResponseBodyEmitterReturnValueResolver`）、编解码拦截器（`RequestBodyAdvice`/`ResponseBodyAdvice` 适配）、Filter（`FilterWrapper` 包装 Servlet Filter）。batch 只有一个切入点——返回值写出阶段（透明聚合）。它们都通过"同包同名覆盖 + @Order 优先"接入，不修改核心代码。

**读法二：默认线程模型。** 默认（无 `@RunInPool`）时 `determinePool` 返回 `default` 业务线程池（非 `null`）——`BizPoolRegistry` 把全局策略 `defaultExecuteMode` 读为 `pool.default-execute-mode` 的值，缺省 `"default"`，于是 `resolvePool("default")` 返回启动期创建的 `ThreadPoolExecutor`。请求在  `acquire` 后 `executor.execute` 切到 `default` 池跑完整链路（Filter → 参数解析 → 调用 → 返回值），`finally release`。要零切换须显式 `@RunInPool(EVENTLOOP)` 或 `pool.default-execute-mode=eventloop`，此时 `determinePool` 写 `NO_POOL` 哨兵并返回 `null`， 直接在 EventLoop 内 `handleWithFilter`。这与 [01 篇 原则 3](01-design-philosophy.md#原则-3--避免线程模型僵化业务方掌控何时切换) 一致，以 `BizPoolRegistry` 实现 + `PropertiesConstant` 默认值 + `performance-principles.md` §6 为准（`DispatcherHandler.java` 注释系早期残留，与实现矛盾，见 01 篇原则 3 说明）。

**读法三：`acquire/release` 的对称性。** 默认 `default` 池路径触发 `acquire` → 业务线程 `finally release` → 拒绝时也 `release`；只有 EventLoop 直处理路径（`determinePool` 返回 `null`）不触发 `acquire`，因为整条链路同线程，ByteBuf 引用无需跨线程转移。这是 [01 篇 原则 4](01-design-philosophy.md#原则-4--避免阻塞非阻塞-io--显式引用计数) 的代码落地。

---

## 五、与 module.md 的差异校正

module.md（[`../../.agent/context/module.md`](../../.agent/context/module.md)）是架构概览基线，整体仍有效。但写作本篇时核对源码发现两处与 `master` 当前源码不符，逐一校正如下。

### 校正一：生命周期触发点

**module.md 表述**（`module.md`、、）：

> `WebContext implements InitializingBean → 驱动整个组件生命周期`
> `WebContext.afterPropertiesSet() → initWithWebContext() → initComponentPhase1/2/3`

**源码事实**：

```java
// WebContext.java
@Override
public void afterPropertiesSet() {
    // No-op: lifecycle is now deferred to startLifecycle(),
    // triggered by NettyHttpServer#start().
}

// WebContext.java
public void startLifecycle() {
    if (!lifecycleStarted.compareAndSet(false, true)) { return; }
    try {
        this.initWithWebContext(this);
        this.initComponentPhase1();
        this.initComponentPhase2();
        this.initComponentPhase3();
    } catch (Exception e) {
        throw new RuntimeException("Failed to start WebContext lifecycle", e);
    }
}

// NettyHttpServer.java
public void start() {
    // 在 Netty 启动前触发 WebContext 生命周期，确保所有 WebComponent 已完成初始化
    webContext.startLifecycle();
    ...
}
```

**校正结论**：`afterPropertiesSet()` 是 no-op；真正触发三阶段的是 `NettyHttpServer.start()`（由 Spring `SmartLifecycle` 机制在 refresh 末尾按 phase 顺序调用）→ `WebContext.startLifecycle()`。理由见 [§3.2](#32-为什么生命周期锚定在-start-而非-afterpropertiesset)。

> 这与 [01 篇总纲](01-design-philosophy.md#一总纲启动时确定性) 已埋的修正一致，本篇给出完整时序链。后续 [03 篇](03-component-lifecycle.md) 将展开 `startLifecycle` 内部三阶段的具体机制。

### 校正二：support 的 `servlet/filter/match/` 子包

**module.md 表述**（`module.md`）：

> `servlet/filter/` 下含 `match/` 子包，路径匹配工具 `Exact/Prefix/Suffix/PathMatch`

**源码事实**（`Glob spring-web-support/src/main/java/io/springperf/web/**/*.java` 全量结果）：

support 的 `support.servlet.filter` 包下实际只有三个类，**无 `match/` 子包**：

| 实际存在的类 | 职责 |
|--------------|------|
| `FilterWrapper` | 包装 `jakarta.servlet.Filter` → 框架 `WebFilter` |
| `SupportWebFilterRegistry` | 扩展 `WebFilterRegistry`，自动注册 Servlet `Filter` Bean |
| `PerfHttpServletFilterChain` | 适配框架 `FilterChain` → `javax.servlet.FilterChain` |

而真正承担"运行时路径匹配的 Filter 包装"职责的类，在**核心**而非 support：

```java
// spring-web/core/filter/RuntimeMappingWebFilter.java
public class RuntimeMappingWebFilter implements WebFilter {
    private final WebFilter delegate;
    ...
}
```

**校正结论**：路径匹配的核心 Filter 包装是 `spring-web` 的 `core/filter/RuntimeMappingWebFilter`，不在 support。support 的 `support.servlet.filter` 是 Servlet `jakarta.servlet.Filter` 的桥接层，与核心路径匹配无关。module.md 描述的 support `match/` 子包在当前源码中不存在（疑为历史重构后未同步的过时描述）。

> 这一校正对阅读源码很重要：若按 module.md 旧述去 support 找路径匹配，会扑空。本系列后续涉及 Filter/路径匹配时，一律以 `core/filter` 为准；support 的 Servlet Filter 桥接留到 [12 篇](12-support-bridge.md) 详述。

---

## 六、模块边界的设计哲学

回到开篇的问题：**为什么拆四个 jar，而不是塞进一个？**

### 6.1 核心的"零业务侵入 + 可独立"

`spring-web` 只引 Spring 五件套（context/core/beans/aop/web）+ Netty + Jackson，**不引 `spring-boot`、不引 `spring-webmvc`、不引 Servlet API**。这意味着核心可以脱离 Spring Boot 独立使用——理论上能嵌入任何 Netty 应用。`reactive-streams`/`jsr305`/`fastjson2` 全 `provided`，是"有则增强、无则不报错"的可选能力。

这一边界对应 [01 篇 原则 6](01-design-philosophy.md#原则-6--避免魔法行为显式-sp显式-fail-fast不靠隐式猜测)：核心不靠"类路径上有 Spring Boot"来决定行为，它只声明自己能用的最小集合。

### 6.2 support/batch 的"可选激活"

support 与 batch 是 `provided` 依赖，用户按需引入。这带来两个收益：

1. **轻量场景的启动更快**：纯 API 网关不引 support，省掉 Servlet 桥接的初始化开销与依赖体积。
2. **能力的组合自由**：要 Servlet 兼容引 support，要批量聚合引 batch，两者可独立选择。

这对应 [01 篇 原则 3](01-design-philosophy.md#原则-3--避免线程切换默认-eventloop-直处理显式才切换) 的精神延伸——"框架把选择权交给业务方"：是否需要 Servlet 兼容、是否需要批量聚合，是业务方用 Maven 坐标显式声明的，不是框架替业务方做死。

### 6.3 starter 的"零配置 + 零冲突"

starter 聚合自动装配（10 个 AutoConfiguration，详见 [14 篇](14-starter-autoconfig.md)），用户只需 `spring-boot-starter-web` 一行依赖即可启动。同时它在依赖源头排除 Tomcat 的 `spring-boot-starter-web`（[§1.3](#13-starter-排除-tomcat-的反向防线)），并在 `SpringWebAutoConfiguration` 启动期检测 SpringMVC 冲突抛 `IllegalStateException`——"零冲突"是显式防线，不是运气。

### 6.4 同包覆盖：support 桥接的根基

support 模块在 `src/main/java/org/springframework/web/servlet/` 等路径下**重写** Spring 的类（如 `PathMatchConfigurer`，见 [§2.2](#22-support-与-batch-的包拓扑简表) 表末）。这是"同包同名覆盖"策略——Java 类加载时，同名同包的类先入 classpath 者胜。support 用此策略让用户的 `import org.springframework.web.servlet.config.annotation.PathMatchConfigurer` 实际拿到的是本框架的覆盖版本，从而在不改业务代码的前提下注入桥接行为。

这是 [01 篇 原则 6](01-design-philosophy.md#原则-6--避免魔法行为显式-sp显式-fail-fast不靠隐式猜测) 的一个**特例与张力点**：同包覆盖本质是一种"隐式"行为，但框架用 `@Order` 优先级 + `log.warn` 显式告警（见 `WebComponentContainer.registerWebComponent` 的冲突处理）把它变得可观测、可排查。机制细节留到 [12 篇](12-support-bridge.md)。

---

## 七、小结：地图的用途

本篇给的四张图——职责矩阵、包拓扑、启动时序、请求调用链——是后续 17 篇的导航坐标。后续每讲一个机制，都会落回这四张图的某个坐标：

- [03 篇](03-component-lifecycle.md) 展开 `startLifecycle` 内部三阶段与 Registry 体系（[§3.1](#31-完整启动链校正版) 的中段）；
- [04 篇](04-request-pipeline.md) 展开 `handleWithMappingResult` → `doHandle` 的请求管线（[§4](#四一次请求的跨模块调用链) 的 `web` 段）；
- [05-10 篇](05-server-and-http.md) 逐个深潜 `server`/`http`/`mapping`/`arg`/`retval`/`invoker`/横切包；
- [11 篇](11-async-streaming.md) 展开 `async/stream` 与 `NettyStreamSender`；
- [12 篇](12-support-bridge.md) 展开 support 的同包覆盖与 `WebMvcConfigurerBridge`；
- [13 篇](13-batch-module.md) 展开 batch 的 Disruptor 聚合切入点；
- [14 篇](14-starter-autoconfig.md) 展开 starter 的 10 个 AutoConfiguration 与零冲突防线。

记住两个校正（生命周期由 `start()` 触发、路径匹配 Filter 在 `core/filter`），后续阅读源码就不会在错误的位置扑空。

---

> **下一篇**：[03 · 三阶段组件生命周期与 Registry 体系](03-component-lifecycle.md)——钻进 `startLifecycle()` 内部，看 Phase1/2/3 各做什么、十大 Registry 如何预匹配、`MappingCacheKey` 的整型索引如何写入。
