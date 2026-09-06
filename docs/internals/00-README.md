# Spring WebPerf 内部机制详解（大纲与索引）

> 本系列是面向"想了解框架设计优秀与性能优越、但不打算阅读源码"的读者的内部机制文档。
>
> 与 `docs/` 下既有的用户向文档（`philosophy.md`、`performance-principles.md`、`advanced.md`、`extensions.md`、`overview.md`、`batch.md`）的区别：
>
> - 既有文档讲"怎么用 / 为什么快"（面向使用者，偏结论与对比表）；
> - 本系列讲"内部怎么实现的 / 代码层如何优化"（面向架构理解者，偏 `file:line + 机制`三元组），把既有文档的结论下沉到代码级证据。
>
> 读完本系列，读者应能在不打开 `.java` 文件的前提下，准确说出框架每个热点路径的数据结构与调用链，并理解每一处优化的取舍依据。

---

## 一、写作约定

1. **语言**：正文简体中文；`代码`、`类名`、`API`、`协议字段`、`注解名`保持英文。
2. **证据粒度**：每个机制至少给出一组 `类名` 锚点，并附"做了什么 / 为什么快 / 对比谁"三元组。以当前 `master` 分支源码为准；写作时必须实际打开对应 `.java` 文件核对，禁止凭记忆引用。
3. **不重复既有文档**：性能对比表、用法示例、注解参数表已在 `performance-principles.md` / `advanced.md` / `batch.md` 给全的，本系列只给"机制链路"，数据用 `→ 见 xxx.md` 引用。
4. **图示**：能用 ASCII 流程图说清的不外链 SVG；需要图时统一放 `docs/images/internals/`。
5. **篇幅**：每篇以"讲透一个子系统"为限，不贪大；预计单篇 400–800 行。

---

## 二、系列总览（9 部 20 篇）

| 篇号 | 文件 | 标题 | 所属部 |
|------|------|------|--------|
| 00 | `00-README.md` | 大纲与索引（本文） | 导航 |
| 01 | `01-design-philosophy.md` | 设计哲学与六大取舍原则 | 一·哲学与架构 |
| 02 | `02-architecture-overview.md` | 模块拓扑与启动期全景 | 一·哲学与架构 |
| 03 | `03-component-lifecycle.md` | 三阶段组件生命周期与 Registry 体系 | 二·核心机制 |
| 04 | `04-request-pipeline.md` | 请求处理主链路与线程模型 | 二·核心机制 |
| 05 | `05-server-and-http.md` | Netty 服务器与 HTTP 请求/响应适配 | 三·spring-web 深潜 |
| 06 | `06-routing-engine.md` | 路由引擎与多级 RouterOptimizer 链 | 三·spring-web 深潜 |
| 07 | `07-argument-resolution.md` | 参数解析与启动期预缓存 | 三·spring-web 深潜 |
| 08 | `08-returnvalue-resolution.md` | 返回值解析与响应写出 | 三·spring-web 深潜 |
| 09 | `09-invoker-bytecode.md` | 方法调用器：ASM 字节码生成与 MethodHandle | 三·spring-web 深潜 |
| 10 | `10-cross-cutting.md` | 横切关注点：拦截器 / 异常 / CORS / 数据绑定 / 静态资源 | 三·spring-web 深潜 |
| 11 | `11-async-streaming.md` | 异步与流式：DeferredResult / SSE / 响应式 / 无锁 Drain Loop | 四·异步与流式 |
| 12 | `12-support-bridge.md` | spring-web-servlet 与 spring-web-mvc-support：Servlet API 桥接与 WebMvcConfigurer 翻译中枢 | 五·support 桥接 |
| 13 | `13-batch-module.md` | spring-web-batch：Disruptor 透明请求聚合内幕 | 六·batch 模块 |
| 14 | `14-starter-autoconfig.md` | spring-boot-starter-web：自动装配与零冲突启动 | 七·starter |
| 15 | `15-performance-optimizations.md` | 八大性能优化代码级三元组 | 八·性能与对比 |
| 16 | `16-code-spotlights.md` | 代码聚光灯：十个值得反复读的实现 | 八·性能与对比 |
| 17 | `17-benchmark-data.md` | Benchmark 数据解读与归因 | 八·性能与对比 |
| 18 | `18-spi-extension.md` | 内部 SPI 发现与调用机制 | 九·扩展与决策 |
| 19 | `19-design-decisions.md` | 关键设计决策记录（ADR 风格） | 九·扩展与决策 |

---

## 三、各篇详述

下面为每一篇给出：**定位 / 核心问题 / 覆盖要点 / 源码依据 / 与既有文档关系 / 写作前提**。`源码依据`列出该篇写作时必须实际 Read 的源码文件（行号在写作时核对，大纲阶段只标类名与模块）。

---

### 部一·哲学与架构

#### 01 · 设计哲学与六大取舍原则 `01-design-philosophy.md`

- **定位**：全系列的"为什么"。把 `philosophy.md` 的方法论（启动时做完 / 消除运行时查找 / 不把复杂性推给开发者）落到代码规范级原则。
- **核心问题**：框架在写每一行代码时遵循了哪些"禁止项"与"偏好项"？这些原则如何体现为可验证的代码特征？
- **覆盖要点**：
  1. 六大取舍原则（每条配"禁止 / 偏好 / 代码证据"）：
     - 避免隐式开销（禁止运行时反射、禁止运行时类型推断）
     - 避免隐式对象创建（禁止请求路径 `new ArrayList` / 装箱 / 临时 Map）
     - 避免线程模型僵化（默认 `default` 业务线程池，`@RunInPool(EVENTLOOP)` 或 `pool.default-execute-mode=eventloop` 切零切换）
     - 避免阻塞（IO 非阻塞、`acquire/release` 显式引用计数）
     - 避免反射（启动一次性解析+缓存，运行时零反射）
     - 避免魔法行为（显式 SPI、显式 fail-fast、不靠 SPI 自动发现猜行为）
  2. "启动时确定性"作为总纲：三阶段生命周期如何把"运行时匹配"前移为"启动时计算一次"。
  3. 性能优先级排序的代码学解释（呼应 `performance-principles.md` 的 ★ 表，但讲清"为什么这条比那条收益面大"）。
  4. 与 Spring MVC/WebFlux 的哲学差异：Spring 选"运行时灵活"，本框架选"启动时确定"。
- **源码依据**：`WebContext`、`BaseWebComponent`、`DispatcherHandler`、`AbstractWebServerHttpRequest`（`fastAttributes`）、`MappingCacheKey`。
- **与既有文档关系**：是 `philosophy.md` 的代码层展开；`performance-principles.md` 的"优先级表"在此给出取舍依据。
- **写作前提**：核对 `WebContext` 与 `BaseWebComponent` 的生命周期接口签名。

#### 02 · 模块拓扑与启动期全景 `02-architecture-overview.md`

- **定位**：全系列的"地图"。一张图看懂四个 Maven 模块的依赖、职责、关键包结构，以及启动时它们如何协作。
- **核心问题**：`spring-web` / `spring-web-servlet` / `spring-web-mvc-support` / `spring-web-batch` / `spring-boot-starter-web` 各自边界在哪？一个 HTTP 请求从 Netty `Channel` 到业务方法再到响应字节，跨了哪些模块的哪些类？
- **覆盖要点**：
  1. 四模块职责矩阵（模块 / 依赖谁 / 被谁依赖 / 关键包 / 是否可选）。
  2. `spring-web` 核心包拓扑：`context`（`WebContext`）、`handler`（`DispatcherHandler`）、`registry`（十大 Registry）、`server`（Netty 服务器）、`http`（请求/响应适配）、`route`（路由优化器）、`argument`/`returnvalue`/`invoker`（解析与调用）、`filter`（`WebFilter` SPI）、`async`/`stream`（异步流式）、`cors`/`exception`/`binder`/`resource`（横切）。
  3. 启动期协作时序：`WebServerApplicationContextFactory` → refresh → `SmartLifecycle.start()`（`NettyHttpServer` phase=MAX 最后）→ `WebContext.startLifecycle()` → Phase1/2/3 → `MappingRegistry.optimizeMapping()` → `bind` → 就绪。（校正：`afterPropertiesSet()` 实为 no-op，真正触发者是 `NettyHttpServer.start()` → `startLifecycle()`，见 01/02 篇校正）
  4. 一次请求的调用链（ASCII），标注每一步所属层次（`[web]`/`[netty]`）。
  5. 路径匹配归核心 `RuntimeMappingWebFilter`（`core/filter`），非 support 子包。
- **源码依据**：`module.md`（架构图基线）+ 四模块 `pom.xml` + `spring-web` 根包下的包结构 + `WebContext` / `WebServerApplicationContext` / `NettyHttpServer` 入口类。
- **与既有文档关系**：是 `overview.md` 的内部结构补充；`module.md` 的代码级校正版。
- **写作前提**：`module.md` 内容较多，写作时按需 `Read` 对应片段；实际包结构用 `Glob` 列目录核对。

---

### 部二·核心机制

#### 03 · 三阶段组件生命周期与 Registry 体系 `03-component-lifecycle.md`

- **定位**：理解全框架的钥匙。所有"启动时预缓存"的收益都源自这套生命周期。
- **核心问题**：`initComponentPhase1/2/3` 各做什么？13 个 Registry 如何注册、排序、预匹配？`MappingCacheKey` 的整型索引如何在 Phase3 写入？
- **覆盖要点**：
  1. 组件层级：`WebComponent` → `LifecycleWebComponent` → `BaseWebComponent` → `WebComponentContainer`（`Map<String,WebComponent>`）→ 各 Registry。
  2. 三阶段语义：Phase1（扫描 Spring Bean / 注册 Mapping / 构建策略列表）→ Phase2（构建内部结构 / 路由优化）→ Phase3（warmup / fail-fast 校验 / 分配 `MappingCacheKey` 整型索引）。
  3. `WebContext` 的生命周期触发链：`afterPropertiesSet()` 实为 no-op，真正触发者是 `NettyHttpServer.start()`（`SmartLifecycle`，`phase=Integer.MAX_VALUE` 最后启动）→ `WebContext.startLifecycle()`（CAS 守卫单次执行）→ Phase1/2/3 编排。推迟到 `start()` 的原因：Web 组件初始化应在基础容器初始化完成之后。
  4. 13 个 Registry 一览表（11 容器型 + 2 叶子型：`MappingRegistry` / `InterceptorRegistry` / `ArgumentResolverRegistry` / `ReturnValueResolverRegistry` / `HttpBodyCodecRegistry` / `HttpBodyCodecInterceptorRegistry` / `CorsRegistry` / `ExceptionRegistry` / `AsyncSupportRegistry` / `ResourceHandlerRegistry` / `WebFilterRegistry` + 叶子 `BizPoolRegistry` / `WebDataBinderRegistry`）—— 每个列出：注册时机 / 预匹配产物 / 运行时取用方式。
  5. `MappingCacheKey` 深潜：为什么用 `int index` + `Object[]` 而非 `ConcurrentHashMap`；与 Spring MVC `HandlerMethodArgumentResolverComposite` 的 `synchronized` 缓存对比。
  6. fail-fast 设计：Phase3 如何在启动时拦截"无法解析的参数 / 无法匹配的返回值"，避免运行时才暴问题。
- **源码依据**：`WebContext`、`WebComponent` / `LifecycleWebComponent` / `BaseWebComponent` / `WebComponentContainer`、`MappingRegistry`、`MappingCacheKey`、`ArgumentResolverRegistry`、`ReturnValueResolverRegistry`、`InterceptorRegistry`。
- **与既有文档关系**：`performance-principles.md` §1 的代码层全展开。
- **写作前提**：核对 Phase1/2/3 三个方法的确切签名与调用顺序。

#### 04 · 请求处理主链路与线程模型 `04-request-pipeline.md`

- **定位**：把"一个请求进来之后发生什么"讲到底。
- **核心问题**：`DispatcherHandler` 如何把 Netty `ChannelRead` 事件路由到业务方法？线程如何在 EventLoop 与业务线程池间切换？`acquire/release` 如何保证 ByteBuf 跨线程不泄漏？
- **覆盖要点**：
  1. 入口：Netty `ChannelInboundHandler`（`NettyHttpHandler`）→ `DispatcherHandler`；`RuntimeMappingWebFilter` 是 Filter 链内运行时包装节点（非入口）。
  2. `DispatcherHandler.handleWithMappingResult()` 主链路（`handleWithFullMatch` 为旧名）：拦截器 `preHandle` → 参数解析 → `Invoker.invoke` → 返回值解析 → 拦截器 `postHandle`/`afterCompletion`。
  3. 线程模型三分支：默认 `default` 业务线程池 / `@RunInPool` 自定义池 / `@RunInPool(EVENTLOOP)` 直处理；`pool.default-execute-mode` 全局开关。
  4. `acquire()/release()` 引用计数链：`DispatcherHandler` 的 retain→execute→release 模式，`RejectedExecutionException` 分支也要释放。
  5. 大 body 零拷贝：`>4KB` 走 `retainedDuplicate()`。
  6. `BaseWebServerHttpRequest.fastAttributes[]` 与响应 `setCommitted()` 的 CAS。
- **源码依据**：`DispatcherHandler`（含 `handleWithMappingResult`、acquire/release 段）、`RuntimeMappingWebFilter`、`BaseWebServerHttpRequest`（`fastAttributes`）、`BaseWebServerHttpResponse`（`setCommitted` CAS）、`RunInPool` 注解与 `BizPoolRegistry`。
- **与既有文档关系**：`performance-principles.md` §4/§5/§6 的链路化；`advanced.md` 线程模型章节的内部化。
- **写作前提**：确认 `default-execute-mode` 默认值与 EventLoop 切换的精确判断点。

---

### 部三·spring-web 深潜（六篇）

#### 05 · Netty 服务器与 HTTP 请求/响应适配 `05-server-and-http.md`

- **定位**：I/O 层。讲清 Netty bootstrap 配置、Channel pipeline、`FullHttpRequest` 到 `NettyServerHttpRequest` 的适配、响应 `ByteBuf` 写出。
- **核心问题**：服务器如何启动与配置？HTTP 请求对象如何零拷贝包装 Netty 对象？响应如何把 `ByteBuf`/`DefaultFileRegion` 写回 Channel？
- **覆盖要点**：
  1. `NettyHttpServer` 启动：boss/worker `EventLoopGroup`、`ServerBootstrap`、`ChannelOption`（`TCP_NODELAY`、`WriteBufferWaterMark`、`SO_BACKLOG`）、HTTP/2 ALPN / h2c prior knowledge。
  2. Channel pipeline 装配：`HttpServerCodec` → `HttpObjectAggregator`（或流式不聚合）→ `SslHandler`（可选）→ `HttpTrafficHandler`/业务 handler。
  3. `NettyServerHttpRequest`：包装 `FullHttpRequest`，`acquire/release`，`getInputStream`/`getReader`，大 body `retainedDuplicate`。
  4. `NettyServerHttpResponse`：`getBuf()` 延迟分配，`DefaultHttpContent` 写出，`DefaultFileRegion` sendfile，`setSameSite`/cookie 编码。
  5. 内存管理：Direct `ByteBuf` 引用计数 vs GC `Cleaner` 延迟回收的取舍。
- **源码依据**：`NettyHttpServer`、`NettyServerHttpRequest`、`NettyServerHttpResponse`、SSL/HTTP2 相关 handler、`AbstractNettyWebServer`。
- **与既有文档关系**：`performance-principles.md` §5/§8 的代码层；`advanced.md` SSL/HTTP2 章节的内部化。
- **写作前提**：核对 `ChannelOption` 实际设置项与 HTTP/2 开关条件。

#### 06 · 路由引擎与多级 RouterOptimizer 链 `06-routing-engine.md`

- **定位**：路由层。讲清"启动时分桶 + 多级优化器链"，回答 90%+ 请求为何一次 `HashMap.get` 搞定。
- **核心问题**：路由表如何在启动期从 `@RequestMapping` 构建为多级索引？运行时 `RouterOptimizer` 链如何短路？与 Spring MVC `AntPathMatcher` 的 O(n) 遍历差异在哪？
- **覆盖要点**：
  1. 启动期分桶：`simpleUrlList`（精确）/ `simpleWildcardList`（单级通配）/ `fullWildcardList`（全通配）。
  2. 优化器链：`FullPathRouterOptimizer`（`HashMap.get(path)` O(1)）→ `PrefixPathRouterOptimizer`（前缀 HashMap O(1)）→ `SuffixPathRouterOptimizer`（后缀 HashMap O(1)）→ `LoopPathPatternRouterOptimizer`（`PathPatternRouter[]` 遍历兜底）。
  3. `MappingRegistry.optimizeMapping()` 的编排：分桶 → 统计学前缀选择 → 构链。
  4. `PathPatternRouter` 内部匹配：与 Spring `PathPattern` 的关系与简化。
  5. 路径变量提取：`@PathVariable` 在匹配后从 `Map<String,String>` 取值（启动期已绑定参数索引，见 07）。
  6. 与 Spring MVC `AbstractHandlerMethodMapping.lookupPath()` 全量遍历的复杂度对比。
- **源码依据**：`MappingRegistry`（`optimizeMapping`）、`RouterOptimizer` 接口与四个实现、`PathPatternRouter`、`RuntimeMappingWebFilter`（运行时入口）。
- **与既有文档关系**：`performance-principles.md` §3 的代码层全展开。
- **写作前提**：核对四个 Optimizer 的链式短路条件与顺序。

#### 07 · 参数解析与启动期预缓存 `07-argument-resolution.md`

- **定位**：参数层。讲清每个方法参数如何在启动期绑定到 `StaticArgumentResolver`，运行时 `array[index]` 直取。
- **核心问题**：`ArgumentResolverRegistry` 如何在 Phase3 为每个参数选定解析器？`StaticArgumentResolver` 与 Spring `HandlerMethodArgumentResolver` 的差异？`@PathVariable`/`@RequestParam`/`@RequestBody`/`@RequestHeader` 等各自的解析路径？
- **覆盖要点**：
  1. `ArgumentResolverRegistry`：注册期收集 `ArgumentResolver`，Phase3 遍历每个 `Mapping` 的每个参数，调用 `supportsParameter` 决策，写入 `MappingCacheKey` 索引。
  2. `StaticArgumentResolver`：启动期把"哪个 resolver + 哪个参数元数据"固化，运行时无 `supports` 调用，无遍历。
  3. 与 Spring MVC `HandlerMethodArgumentResolverComposite` 的 `synchronized` 缓存 + 每请求遍历对比。
  4. 各内建解析器：`PathVariableArgumentResolver`、`RequestParamArgumentResolver`、`RequestBodyArgumentResolver`（联动 `HttpBodyConverter`，见 08/12）、`RequestHeaderArgumentResolver` 等。
  5. `@PathVariable` 启动期预解析：把路径变量名→索引映射固化，运行时从 `Map<String,String>` 取值后按缓存索引装箱。
  6. `StaticArgumentResolverProvider` SPI：允许业务注册自定义解析器，启动期一次性匹配。
- **源码依据**：`ArgumentResolverRegistry`、`StaticArgumentResolver`、各内建 `*ArgumentResolver`、`StaticArgumentResolverProvider`、`MappingCacheKey`（参数维度）。
- **与既有文档关系**：`performance-principles.md` §1 参数行的代码层；`extensions.md` `StaticArgumentResolverProvider` 的内部化。
- **写作前提**：核对 Phase3 决策调用的确切方法名与 `MappingCacheKey` 在参数维度的存储结构。

#### 08 · 返回值解析与响应写出 `08-returnvalue-resolution.md`

- **定位**：返回值层。讲清返回值如何在启动期绑定 `ReturnValueResolver`，运行时零遍历命中，并把对象序列化为 `ByteBuf` 写回。
- **核心问题**：`ReturnValueResolverRegistry` 如何为每个方法返回值选定 resolver + MediaType？普通对象 / `ResponseEntity` / `DeferredResult` / `Publisher` / `ResponseBodyEmitter` 各走哪条路径？`HttpBodyConverter` 如何联动？
- **覆盖要点**：
  1. `ReturnValueResolverRegistry`：Phase3 为每个 `Mapping` 返回值选定 `ReturnValueResolver`，写 `MappingCacheKey`。
  2. `ReturnValueResolver` 体系：`ObjectReturnValueResolver`（默认 JSON）、`ResponseEntityReturnValueResolver`、`DeferredResultReturnValueResolver`、`ReactiveReturnValueResolver`（见 11）、`StreamEmitterReturnValueResolver` / `ResponseBodyEmitterReturnValueResolver`（见 11/12）。
  3. `HttpBodyConverter` / `HttpMessageConverter`：启动期收集 converter，运行时按缓存索引直调；与 Spring MVC 运行时遍历 `canWrite` 对比。
  4. 序列化写出：对象 → `JsonConverter`（Jackson/Fastjson 可选）→ `ByteBuf` → `DefaultHttpContent` → `channel.write`。
  5. `@ResponseBody` 与非 `@ResponseBody`（View 渲染，见 12）的分流。
  6. 大文件 / `Resource` 返回：`DefaultFileRegion` sendfile 路径。
- **源码依据**：`ReturnValueResolverRegistry`、各 `*ReturnValueResolver`、`HttpBodyConverter` / `HttpBodyCodecRegistry`、`JsonConverter` 体系、`ResponseBodyEmitterReturnValueResolver`（联动 12）。
- **与既有文档关系**：`performance-principles.md` §1 返回值行的代码层；`extensions.md` `ReturnValueResolver`/`HttpBodyConverter` 的内部化。
- **写作前提**：核对 `HttpBodyConverter` 启动期收集机制（见 12）。

#### 09 · 方法调用器：ASM 字节码生成与 MethodHandle `09-invoker-bytecode.md`

- **定位**：调用层。讲清为什么 `Method.invoke(~200ns)` 被替换为 `INVOKEVIRTUAL(~10ns)` / `invokeExact(~30ns)`，以及生成时机与缓存。
- **核心问题**：`FastInvokerGenerator` 如何用 ASM 在启动期为 `@Optimize` 方法生成专属 `Invoker` 类？未标注方法为何 fallback 到 `MethodHandleInvoker`？生成的字节码长什么样、如何被加载与缓存？
- **覆盖要点**：
  1. `Invoker` 接口与 `invoke(Object[] args)` 契约。
  2. `@Optimize` 注解：标记走 ASM 字节码生成路径。
  3. `FastInvokerGenerator`：Spring ASM `ClassWriter` 生成 `XxxController_method_N implements Invoker`，方法体直接 `INVOKEVIRTUAL` 调目标方法，参数按类型 `checkcast`。
  4. 生成的等价 Java 源码示例 + 反编译关键指令（`INVOKEVIRTUAL`、`CHECKCAST`、`ARETURN`）。
  5. `MethodHandleInvoker`：未标注 `@Optimize` 的 fallback；`MethodHandle.invokeExact(args)`，JIT intrinsic。
  6. 三级调用成本表（`INVOKEVIRTUAL ~10ns` / `invokeExact ~30ns` / `Method.invoke ~200ns`）的归因：访问检查 / 可变参数装箱 / 类型校验。
  7. 生成类的加载与缓存：`ByteClassLoader` / `defineClass`，与 `MappingCacheKey` 的绑定。
  8. 何时选 `@Optimize`：高频端点建议标注，低频端点 MethodHandle 足矣。
- **源码依据**：`Invoker` 接口、`FastInvokerGenerator`、`MethodHandleInvoker`、`@Optimize` 注解、生成的 Invoker 字节码（可手写反编译示意）。
- **与既有文档关系**：`performance-principles.md` §2 的代码层全展开；本篇是全系列"最硬核"的一篇。
- **写作前提**：核对 ASM 生成器的 `visitor` 调用序列与 `defineClass` 路径（注意类加载器隔离）。

#### 10 · 横切关注点：拦截器 / 异常 / CORS / 数据绑定 / 静态资源 `10-cross-cutting.md`

- **定位**：横切层。把不属主链路但每请求都可能触发的子系统集中讲清。
- **核心问题**：拦截器如何在启动期预匹配到每个 Mapping？异常解析链如何工作？CORS 预检与正式请求如何分流？`PerfDataBinder` 砍掉了什么？静态资源如何 sendfile？
- **覆盖要点**：
  1. `InterceptorRegistry`：Phase3 为每个 Mapping 预匹配拦截器数组，运行时直接迭代（无 `MappedInterceptor` 运行时路径匹配）。
  2. `HandlerInterceptor` SPI：`preHandle`/`postHandle`/`afterCompletion`；`HandlerInterceptorWrapper` 见 12。
  3. `ExceptionResolverRegistry`：异常解析链，`@ControllerAdvice` 启动期全量扫描缓存异常处理方法；与 Spring MVC `ExceptionHandlerExceptionResolver` 运行时查找对比。
  4. `CorsRegistry` / `WebCorsProcessor`：CORS 预检（`OPTIONS`）短路、正式请求注入 `Access-Control-*` 头。
  5. `WebDataBinderRegistry` / `PerfDataBinder`：移除 `fieldMarkerPrefix` 等无用功能，最小化绑定路径；`@InitBinder` 启动期缓存。
  6. `ResourceHandlerRegistry`：静态资源映射，`DefaultFileRegion` sendfile；classpath/location 解析（含双斜杠 bug 的防御，见记忆 `resource-handler-double-slash-fix`）。
- **源码依据**：`InterceptorRegistry`、`ExceptionResolverRegistry`、`CorsRegistry`/`WebCorsProcessor`、`WebDataBinderRegistry`/`PerfDataBinder`、`ResourceHandlerRegistry`。
- **与既有文档关系**：`performance-principles.md` §1 拦截器/异常行的代码层；`extensions.md` `HandlerExceptionResolver`/`WebCorsProcessor` 的内部化。
- **写作前提**：核对各 Registry 的 Phase3 预匹配产物结构。

---

### 部四·异步与流式

#### 11 · 异步与流式：DeferredResult / SSE / 响应式 / 无锁 Drain Loop `11-async-streaming.md`

- **定位**：异步层。讲清四类异步返回值的处理路径，重点是 SSE 的无锁 Drain Loop。
- **核心问题**：`DeferredResult`/`Callable` 如何挂起与恢复？SSE 的 `NettyStreamSender` 如何用 `MpscUnboundedArrayQueue` + `AtomicInteger wip` 实现无锁单消费者排空？`@ReactiveSupport` 背压水位如何控制？响应式 `Publisher` 如何在 EventLoop 上直接驱动？
- **覆盖要点**：
  1. `AsyncSupportRegistry`：`DeferredResult`/`Callable`/`ListenableFuture`/`CompletableFuture` 的统一挂起-恢复模型。
  2. `DeferredResultReturnValueResolver`：挂起请求、`setResult`/`setError`/`onTimeout` 恢复。
  3. SSE：`SseEmitter` → `NettyStreamSender`；`MpscUnboundedArrayQueue` 多生产者单消费者；`AtomicInteger wip` drain loop 伪代码与真代码对照。
  4. **wip 计数器边界**（记忆 `sse_fix_channel_write`）：生产者快于 drain 时 wip 残留的处理，drain 循环的 missed 重入；complete 边界事件丢失属既定可接受设计（记忆 `defensive-fixes-confirm-call-model`）。
  5. 背压：`channel.isWritable()` + `BackpressureHandler.INSTANCE` 单例；`WriteBufferWaterMark` 联动。
  6. 响应式：`ReactiveReturnValueResolver` 把 `Publisher` 适配为流式或 `DeferredResult`；`@ReactiveSupport(highWaterMark/lowWaterMark)` 水位。
  7. 线程模型：SSE 写入统一在 EventLoop，线程数 = CPU 核，不随连接增长；对比 Spring MVC 每连接一线程。
- **源码依据**：`AsyncSupportRegistry`、`DeferredResultReturnValueResolver`、`NettyStreamSender`（drain loop）、`BackpressureHandler`、`ReactiveReturnValueResolver`、`@ReactiveSupport`。
- **与既有文档关系**：`performance-principles.md` §7 的代码层；`advanced.md` SSE/响应式章节的内部化。
- **写作前提**：`NettyStreamSender.drain()` 必须实读，wip 边界描述按记忆谨慎措辞，不臆断"完美无缺"。

---

### 部五·support 桥接

#### 12 · spring-web-servlet 与 spring-web-mvc-support：Servlet API 桥接与 WebMvcConfigurer 翻译中枢 `12-support-bridge.md`

- **定位**：兼容层。讲清 support 如何用"同包同名覆盖 + Adapter/Wrapper/Provider"消除 `spring-webmvc` 依赖，同时让 `@ControllerAdvice`/`WebMvcConfigurer`/`Filter`/`HandlerInterceptor`/Session 等 Spring 生态件无缝接入。
- **核心问题**：support 重写了哪些 `org.springframework.web.servlet.*` 类（纯接口/default 方法）？`WebMvcConfigurerBridge` 如何把 21 个 `WebMvcConfigurer` 回调翻译为框架内部 Registry 的注册动作？Servlet 请求/响应如何被 `PerfHttpServletRequest/Response` 适配？Filter 与拦截器如何被 Wrapper 桥接？
- **覆盖要点**：
  1. **同包同名覆盖策略**：19 个 `org.springframework.web.servlet.*` 类的重写分类——纯接口（`HandlerInterceptor`/`AsyncHandlerInterceptor`/`HandlerExceptionResolver`/`LocaleResolver`，default 方法）、`@Deprecated` 存根（`ModelAndView`/`View`）、21 回调空实现（`WebMvcConfigurer`）+ 5 个 shim 收集器（`InterceptorRegistry`/`CorsRegistry`/`ResourceHandlerRegistry`/`AsyncSupportConfigurer`/`ValidatorRegistration`）、no-op stub（`PathMatch`/`ContentNegotiation`/`DefaultServletHandler`/`ViewController`/`ViewResolver`）、`mvc.method.annotation` Advice/Emitter 家族。
  2. **`WebMvcConfigurerBridge` 翻译中枢**：`getOrder=LOWEST_PRECEDENCE-20000`（最后执行收集所有Configurer）；`initComponentPhase1` 收集 `WebMvcConfigurer` Bean；10 个 bridge 方法——`bridgeInterceptors`/`bridgeCorsMappings`/`bridgeResourceHandlers`/`bridgeFormatters`/`bridgeAsyncSupport`/`bridgeArgumentResolvers`/`bridgeMessageConverters`/`bridgeReturnValueHandlers`/`bridgeHandlerExceptionResolvers`/`bridgeConfigureValidator`，每个讲清 shim 数据流向哪个内部 Registry。
  3. **`HandlerInterceptorWrapper`**：`preHandle`/`postHandle`(传 null modelAndView)/`afterCompletion`(Throwable→Exception `NestedServletException` 包裹)/`afterConcurrentHandlingStarted`；双路径 servlet 视图提取（`ServletAttribute` 缓存优先，`RequestContextHolder` fallback）。
  4. **`SupportInterceptorRegistry`**：双类型扫描（`InterceptorRegistration` vs `HandlerInterceptor`）；`MappedInterceptor` vs plain；`AnnotationAwareOrderUtils.findOrder` 排序。
  5. **`SpringHandlerMethodArgumentResolverProvider` / `SpringHandlerMethodReturnValueHandlerAdapter` / `SpringHandlerExceptionResolverAdapter`**：把 Spring 生态的 `HandlerMethodArgumentResolver`/`HandlerMethodReturnValueHandler`/`HandlerExceptionResolver` 适配为本框架 `StaticArgumentResolver`/`ReturnValueResolver`/`HandlerExceptionResolver`；启动期 `supports` 决策消除每请求 dispatch（替代旧 `RuntimeArgumentResolver`）。
  6. **Filter 体系**：`FilterWrapper`（`IdentityHashMap<Filter,String>`+`AtomicLong` 实例级唯一 ID——记忆中 `IdentityHashMap` 解决 identityHashCode 碰撞致安全 Filter 丢失）、`PerfHttpServletFilterChain`、`PerfHttpServletRequest/Response`（rebind、`NettyServletInputStream`、cookie 编解码、session）。
  7. **`AbstractFastFailHttpServletRequest/Response`**：80+ 方法 fail-fast（"not running in a Servlet container"）或默认返回。
  8. **Codec 拦截器桥接**：`RequestBodyAdviceCodecInterceptor`（读侧）/`ResponseBodyAdviceCodecInterceptor`（写侧），`SupportHttpBodyCodecInterceptorRegistry` 启动期收集 `ControllerAdviceBean`。
  9. **`ResponseBodyEmitterReturnValueResolver`**：extends `StreamEmitterReturnValueResolver` implements `LifecycleWebComponent`；`preInitializeEmitter` 注入 `AdapterUtil.setEncodeFunction`；`encodeToStream` 兜底（记忆 `perf-support-sse-converter-missing`：缺 converter 时 String→UTF-8/byte[] fallback）。
  10. **`SupportDispatcherHandler`**：`getOrder=LOWEST_PRECEDENCE-30000`；`initContextHolders` override（`RequestContextHolder` set/reset）；`WriteRespEventListener`/`SessionFlushListener` 用 Netty `ChannelFuture` 驱动 session 持久化。
  11. **Session 体系**：`PerfHttpSession`/`PerfHttpSessionManager`/`HttpSessionData`/`HttpSessionStorage`(SPI)/`InMemoryHttpSessionStorage`(默认 + daemon 过期清理)；session cookie 安全（httpOnly、secure via config/X-Forwarded-Proto、SameSite）。
  12. **`ResponseEntityExceptionHandler`**：`@ControllerAdvice` 15 个标准异常处理。
- **源码依据**：support 模块 47 文件（已有 subagent 报告提供 file:line，**写作时必须用 Read 逐个核对**，因报告生成时安全分类器不可用）。重点核对：`WebMvcConfigurerBridge`（10 bridge 方法行号）、`FilterWrapper`（`IdentityHashMap` 段）、`PerfHttpServletRequest`（session/cookie 段）、`ResponseBodyEmitterReturnValueResolver`（`encodeToStream` 兜底段）、`SupportDispatcherHandler`（`ChannelFuture` session 持久化段）。
- **与既有文档关系**：既有文档无 support 内部专门篇；本篇是 support 的首份内部机制文档。
- **写作前提**：support 报告的 file:line 不可直接照抄，必须实读核对。

---

### 部六·batch 模块

#### 13 · spring-web-batch：Disruptor 透明请求聚合内幕 `13-batch-module.md`

- **定位**：批处理层。把 `batch.md` 的用法下沉到 Disruptor 环形缓冲区、三线程模型、`BatchInvoker` 方法体替换的内幕。
- **核心问题**：`BatchInvoker` 如何在启动期替换原 Controller 方法调用？Disruptor RingBuffer 如何无锁入队？三线程（EventLoop 生产者 / Disruptor 消费者 / bizExecutor 业务池）如何协作与背压？8 个 Micrometer 指标各自埋点在哪？
- **覆盖要点**：
  1. `BatchRegistry`：管理所有 RingBuffer 生命周期，启动期安装 `BatchInvoker`（方法体替换语义——原方法体不执行）。
  2. `BatchInvoker`：根据构造参数位置创建 `BatchRequest` 实例并入队；`BatchRequest<R> extends DeferredResult<R>`。
  3. `DisruptorQueue`：封装 LMAX Disruptor，`ProducerType.MULTI` 多生产者无锁入队，`WaitStrategy`（BLOCKING/YIELDING/SLEEPING/BUSY_SPIN），backpressure（BLOCK/DROP/THROW）。
  4. `BufferingBatchHandler`：Disruptor 消费者，攒批达 `maxBatchSize` 或 `endOfBatch` 提交到 bizExecutor。
  5. bizExecutor 线程池：0 核心 + `SynchronousQueue` + `CallerRunsPolicy`，空闲零线程，满负荷消费者自执行成背压。
  6. 8 Micrometer 指标的埋点位置与 tag 约定（`batch:<ClassName>.<methodName>`）。
  7. 背压传导：RingBuffer 满 → 生产者阻塞 → EventLoop 反压 → TCP 层。
  8. 超时与错误：`DeferredResult` 超时回调（默认 30s）、批量异常遍历 `setError`。
- **源码依据**：`BatchRegistry`、`BatchInvoker`、`DisruptorQueue`、`BufferingBatchHandler`、`BatchRequest`、`@BatchMapping`、8 个 metrics 埋点类（共 16 文件，需逐个 Read）。
- **与既有文档关系**：`batch.md` 的内部化；`philosophy.md` 批处理节的代码层。
- **写作前提**：核对 Disruptor `WaitStrategy` 枚举与 backpressure 实现的确切类名。

---

### 部七·starter

#### 14 · spring-boot-starter-web：自动装配与零冲突启动 `14-starter-autoconfig.md`

- **定位**：装配层。讲清 starter 如何用 `spring.factories` 注册 10 个 `AutoConfiguration`，如何检测冲突并 fail-fast，如何独立部署 Actuator 管理端口。
- **核心问题**：`SpringWebAutoConfiguration` 如何检测并拒绝 `spring-boot-starter-web`(MVC) 同存？`ActuatorEndpointAutoConfiguration` 如何用独立 `ManagementNettyHttpServer` 隔离管理端口？`SpringBootAdminClientAutoConfiguration` 如何发射 `WebServerInitializedEvent` 兼容 SBA/Spring Cloud？`WebServerApplicationContextFactory` 为何强制 `AnnotationConfigApplicationContext`？
- **覆盖要点**：
  1. `spring.factories` 注册的 10 个 `AutoConfiguration` 一览与各自条件。
  2. `SpringWebAutoConfiguration`：核心装配；冲突检测抛 `IllegalStateException`（记忆 `sba-web-server-initialized-event` 兼容背景）。
  3. `SpringWebServletAutoConfiguration` 与 `SpringWebMvcSupportAutoConfiguration`：装配 support 桥接。
  4. `SpringDataWebCompatibilityAutoConfiguration`：Spring Data 兼容（分页/排序）。
  5. `ActuatorEndpointAutoConfiguration`：独立 `ManagementNettyHttpServer` + `ManagementDispatcherHandler`，与主端口隔离。
  6. `SpringWebBatchAutoConfiguration`：条件装配 batch（`spring-web-batch` 在类路径）。
  7. `SpringBootAdminClientAutoConfiguration`：发射 `WebServerInitializedEvent`（兼容 SBA + Spring Cloud 服务注册）。
  8. OpenAPI/SwaggerUi 自动装配。
  9. `WebServerApplicationContextFactory`：强制 `AnnotationConfigApplicationContext` 的原因。
  10. `PerfWebServer`：`WebServer` 抽象的实现，与 Spring Boot `WebServer` 契约对齐。
- **源码依据**：`spring.factories`、10 个 `*AutoConfiguration` 类、`PerfWebServer`、`WebServerApplicationContextFactory`、`ManagementNettyHttpServer`/`ManagementDispatcherHandler`。
- **与既有文档关系**：`advanced.md` Actuator/CORS 章节的内部化；记忆 `sba-web-server-initialized-event` / `wsl-external-migrated-to-master` 的代码层。
- **写作前提**：核对 `spring.factories` 实际注册项与各 `@ConditionalOn*` 条件。

---

### 部八·性能与对比

#### 15 · 八大性能优化代码级三元组 `15-performance-optimizations.md`

- **定位**：把 `performance-principles.md` 的 8 条优化，每条拆为"手段（file:line 机制）/ 为什么快（数据结构归因）/ 对比谁（Spring MVC/WebFlux 同位代码）"三元组。
- **核心问题**：每条优化的代码证据、收益的 CPU/内存归因、与同位 Spring 实现的复杂度/分配量差异。
- **覆盖要点**：8 条逐条三元组（不再列表，与 03–11 互引，避免重复，只补"同位对比"维度）：
  1. 启动预缓存 vs `HandlerMethodArgumentResolverComposite` synchronized；
  2. ASM/MethodHandle vs `Method.invoke`；
  3. 多级 RouterOptimizer vs `AntPathMatcher.match`；
  4. `fastAttributes[]` vs `ConcurrentHashMap`；
  5. `ByteBuf` acquire/release vs 容器自动回收；
  6. default 池为基线，EventLoop 可选 vs 容器线程池强制接管；
  7. 无锁 Drain Loop vs 每连接一线程；
  8. Netty 传输 opts（`TCP_NODELAY`/`DefaultFileRegion`/`WriteBufferWaterMark`）。
- **源码依据**：综合引用 03–11 的源码锚点 + Spring 同位实现（引用 Spring Framework 公开源码位置，标注版本）。
- **与既有文档关系**：`performance-principles.md` 的"同位对比"补完。
- **写作前提**：03–11 已完成（本篇为汇总对比篇，应最后写）。

#### 16 · 代码聚光灯：十个值得反复读的实现 `16-code-spotlights.md`

- **定位**：欣赏篇。挑十个"体现工程美感"的具体实现，逐个用 30–80 行讲透设计意图与巧妙处。
- **核心问题**：哪些实现最能代表"启动时确定性 + 运行时零开销"哲学？它们的巧妙在哪？
- **覆盖要点**（候选，写作时定稿）：
  1. `MappingCacheKey` 整型索引数组（预缓存核心）；
  2. `FastInvokerGenerator` ASM 生成（调用零反射）；
  3. 多级 RouterOptimizer 链短路（路由 O(1)）；
  4. `NettyStreamSender` drain loop（SSE 无锁）；
  5. `fastAttributes[]` Object[]（属性零哈希）；
  6. `DispatcherHandler` acquire/release（跨线程内存安全）；
  7. `WebMvcConfigurerBridge` 翻译中枢（兼容零侵入）；
  8. `FilterWrapper` IdentityHashMap 唯一 ID（安全 Filter 不丢）；
  9. Disruptor `BufferingBatchHandler` 攒批 + CallerRunsPolicy 背压；
  10. `SupportDispatcherHandler` ChannelFuture 驱动 session 持久化。
- **源码依据**：各对应类的关键方法。
- **与既有文档关系**：全系列唯一的"赏析"篇，串联前文。
- **写作前提**：03–13 已完成。

#### 17 · Benchmark 数据解读与归因 `17-benchmark-data.md`

- **定位**：数据篇。用最新报告（`benchmark-reports/20260813-232906/report.md`）的数字，归因到前文讲过的机制。
- **核心问题**：perf 为何在 get/json ~2x tomcat？为何 SSE 7–11x tomcat 且 undertow 4t/64t FAIL？为何每请求分配 perf ~8–11KB vs tomcat ~23–37KB vs webflux ~25–51KB？为何 heap perf ~17MB？
- **覆盖要点**：
  1. 吞吐表解读：get perf 41466/77418/65624 vs tomcat 19129/44031/44900；json perf 40103/77917/110641 vs tomcat 21196/49911/56824；sse perf 14157/15111/14957 vs tomcat 1286/1989/2344 vs undertow FAIL(4t/64t)。
  2. GC/分配归因：perf 每请求 ~8–11KB（预缓存+零临时对象）vs tomcat ~23–37KB（运行时匹配+包装器）vs webflux ~25–51KB（响应式链对象）；sse perf 315KB/请求（无锁队列高频分配）vs tomcat 233KB（低吞吐下的分配）。
  3. 内存归因：perf heap ~17MB（最小化内部结构）vs tomcat 19–33MB（容器内部缓冲）。
  4. 64t 反超：json/valid perf 在 64t 反超 16t（77917→110641），归因基准 eventloop 模式零切换在高并发下的伸缩性。
  5. undertow sse FAIL：归因其 SSE 实现在 4t/64t 的稳定性问题（非本框架优势，客观标注）。
  6. perf vs perf-support：support 桥接的额外开销（get 34504 vs 41466，约 -17%），归因 servlet 适配对象创建。
- **源码依据**：`benchmark-reports/20260813-232906/report.md` + 前文机制锚点。
- **与既有文档关系**：`benchmark.md`/`benchmark-wsl.md` 的归因版。
- **写作前提**：数据以报告原文为准，不臆造。

---

### 部九·扩展与决策

#### 18 · 内部 SPI 发现与调用机制 `18-spi-extension.md`

- **定位**：扩展层（内部视角）。与 `extensions.md`（用户向用法）互补，本篇讲 SPI 在框架内部如何被发现、排序、调用。
- **核心问题**：12 个 SPI 扩展点（`WebFilter`/`HandlerInterceptor`/`HttpBodyCodecInterceptor`/`HandlerExceptionResolver`/`ReturnValueResolver`/`StaticArgumentResolverProvider`/`HttpBodyConverter`/`JsonConverter`/`WebComponent`/`BizPoolRegistry`/`RouterOptimizer`/`WebCorsProcessor`）各自如何被 Registry 发现？`WebComponent` 接口/`@Order`/`AnnotationAwareOrderUtils` 如何排序？运行时调用点在哪？
- **覆盖要点**：12 个 SPI 的"发现时机 / 排序键 / 缓存产物 / 运行时调用点"四元组表 + 每个补一个最小扩展示例骨架。
- **源码依据**：各 Registry 的 `autoRegisterWebComponent`/`getOrder` 段、`WebComponent` 接口/`@Order` 注解、`AnnotationAwareOrderUtils`。
- **与既有文档关系**：`extensions.md` 的内部发现机制版。
- **写作前提**：核对各 SPI 的发现注解与排序机制。

#### 19 · 关键设计决策记录（ADR 风格） `19-design-decisions.md`

- **定位**：决策篇。用 ADR（Architecture Decision Record）风格记录关键取舍的"背景 / 决策 / 后果"。
- **核心问题**：为什么选 ASM 而非全用 MethodHandle？为什么默认业务线程池而非全 EventLoop？为什么 `int index` 而非 `ConcurrentHashMap`？为什么 support 用同包同名覆盖而非新包？为什么 Disruptor 而非自研队列？
- **覆盖要点**（候选 ADR，写作时定稿 10–15 条）：
  1. ASM vs 全 MethodHandle；
  2. 默认业务池 vs 全 EventLoop；
  3. `int index` + `Object[]` vs `ConcurrentHashMap`；
  4. 同包同名覆盖 vs 新包桥接；
  5. Disruptor vs 自研无锁队列；
  6. 有界 `MpscArrayQueue` vs `LinkedBlockingQueue`（SSE）；
  7. `complete` 边界事件丢失可接受（记忆 `defensive-fixes-confirm-call-model`）；
  8. 静态 Map 唯一性是有意设计（记忆同上）；
  9. fail-fast 启动校验 vs 运行时降级；
  10. 显式 SPI vs Spring `@Conditional` 自动发现。
- **源码依据**：对应类/注释 + 记忆中的既定决策。
- **与既有文档关系**：全系列收尾，把"为什么这么设计"沉淀为可追溯记录。
- **写作前提**：07–13 已完成，决策有代码证据。

---

## 四、推荐阅读路径

- **快速理解（30 分钟）**：01 → 02 → 15 → 17。
- **架构师路径（2 小时）**：01 → 02 → 03 → 04 → 15 → 16 → 19。
- **核心源码级（4 小时）**：03 → 04 → 05 → 06 → 07 → 08 → 09 → 11。
- **全模块（8 小时）**：按篇号 01–19 顺序。
- **特定模块速查**：support → 12；batch → 13；starter → 14；SSE → 11；路由 → 06；调用 → 09。

