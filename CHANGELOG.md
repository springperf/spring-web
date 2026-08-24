# 变更日志

> [English Version](docs/en/changelog.md)

本项目遵循 [语义化版本控制](https://semver.org/lang/zh-CN/)。

## [3.2.5] - 20260824

### 新增

- **Servlet 规范桥（servlet spec bridge）**：`spring-web-support` 新增完整 Servlet 适配层，提供 `PerfServletContext`、`PerfAsyncContext`、`PerfRequestDispatcher`、`PerfWebConnection`、`PerfHttpPrincipal`、`ServletPartAdapter`、`PerfFilterConfig`、`ForwardWebServerHttpRequest`、`NettyHttpServletRequest` 等实现，支持在 Netty 上运行 Servlet 规范 Filter 链、`AsyncContext`、`RequestDispatcher` 转发、`HttpSession`、`Part` 等能力；新增 `SupportDispatcherHandler` 桥接分发器
- **`@RunInEventloop` 元注解**：`@Optimize` / `@ReactiveSupport` 标注 `@RunInEventloop`，业务方法可直接在 EventLoop 上执行，减少线程切换
- **Netty / Body / AccessLog 配置暴露**：`PropertiesConstant` 新增对应配置项，`NettyHttpServer` / `AccessLogWebFilter` 支持外部化配置
- **WSL 一键基准测试**：新增 `wsl-run-all.sh` / `wsl-benchmark.sh` / `wsl-server.sh` / `wsl-convert-cp.sh` 脚本、`WSL_SETUP.md` 与 `no-sleep.ps1`，支持可选 JFR 采集
- **报告生成器增强**：`ReportGenerator` 跳过损坏的结果 JSON，缺失容器在报告中渲染 FAIL 列并附带提示
- **内部实现文档**：新增 `docs/internals/00~19` 全量架构、请求链路与设计决策文档

### 优化

- **解析热路径分配削减**：请求参数解析、`InMemoryHttpSessionStorage`、`MicrometerWebMetrics` 路径减少中间对象分配
- **批处理 SSE 发送**：`AbstractNettyStreamSender` 新增 `sendAll()` 批量发送，避免逐条 flush，并修复 drain 重入
- **大请求体释放优化**：大体积请求体通过 `duplicate` 引用替代 refcount 加锁，消除锁开销

### 修复

- **Multipart**：`destroy()` 释放 decoder 以归还池化内存；修复 destroy 顺序、`undecodedChunk`、refcount 泄漏；`RequestPart` 缺失时返回 400 而非 500
- **WebSocket**：升级被拒绝时释放请求；修复 h2c fragment buffer 泄漏与 WS upgrade 泄漏
- **Async**：异步超时改为懒调度并修正 metrics 计时；`SSE emitter select`、`ReactiveSupport` 首个请求 NPE、冷流发布者背压 refill 修复
- **路由与参数绑定**：`SuffixPathRouterOptimizer` off-by-one 修复；映射与参数解析启动期 fail-fast 校验；`AbstractNamedValueResolver` 支持泛型集合元素类型转换；`getReader` 回落 UTF-8 编码
- **异常处理**：`ResponseStatusException` 头通过跨版本适配器正确拷贝
- **文件下载**：使用 `Content-Length` 定长帧，避免 chunked 传输
- **大请求体**：异步读取与释放同步，避免已释放 `ByteBuf` 被访问
- **管理端口隔离**：management dispatcher 不再参与主端口路由查找
- **Interceptor**：`afterCompletion` 语义修正
- **Netty 层**：`@Sharable` 处理器生命周期与 h2c buffer leak guard；`NettyHttpHandler` 请求路径 NPE 修复
- **Batch 队列**：`DisruptorQueue` RingBuffer 大小 clamp、溢出保护；`BatchScanner` 泛型类型解析修复
- **SSL PEM 解析**：`SslContextFactory` 私钥与证书解析修复
- **FilterWrapper**：`id` 稳定性修正
- **访问日志**：`AccessLogWebFilter` 错误状态记录与格式修正

### 文档

- **AI 上下文迁移**：`CLAUDE.md` / `.claude` 迁移至 `AGENTS.md` / `.agent`
- **benchmark 数据刷新**：WSL / 多实例趋势图数据更新
- **内部实现文档**：servlet bridge / async 流式 / batch 模块文档补齐

## [3.2.4] - 20260805

### 新增

- **`JsonConverter.toJsonBytes()` API**：`JsonConverter` 接口新增直接输出 `byte[]` 的方法，Jackson / Fastjson 转换器实现，跳过中间 `String` 拷贝，降低响应序列化开销
- **Spring Boot 4.x 事件适配桥**：新增 `Boot4WebServerInitializedEventBridge` 及 `Boot4WebServerInitializedEventAutoConfiguration`，SB4 环境下正确发射 `WebServerInitializedEvent`
- **GraalVM native-image 运行时提示**：新增 `SpringWebRuntimeHints`，补充 SB4 / 原生镜像场景的反射注册配置
- **SSE 测试体系扩充**：新增 `DefaultNettyStreamSenderTest`、`EarlyEncodeNettyStreamSenderTest`、`HttpBodyCodecRegistryWriteNegotiationTest`、`NettyHttpHeadersAdapterTest` 等
- **一键 JFR flame graph 脚本**：`spring-web-benchmark/jfr-hotspot.sh` / `analyze-jfr2.sh`，批量生成 CPU 热点火焰图；`analyze_jfr.py` CPU 热点分析脚本纳入版本控制

### 重构

- **SSE 流式发送重构**：`NettyStreamSender` 拆分为 `DefaultNettyStreamSender`（EventLoop 延迟编码）与 `EarlyEncodeNettyStreamSender`（App 线程早编码 byte[] 快照），支持数据提前冻结
- **HTTP 头零拷贝视图**：新增 `NettyHttpHeadersAdapter`，`WebHttpHeaders` 直接委托 Netty 头存储，消除请求头 O(n) 拷贝
- **Body 内容协商重写**：`HttpBodyCodecRegistry` 写路径协商逻辑重构，新增写协商专项测试
- **Arg 模块优化**：`SpringHandlerMethodArgumentResolverAdapter` 重构为 `Provider` 形式；validator 在方法参数上下文缓存
- **Retval 模块优化**：`ReturnValueResolverRegistry` / `MethodReturnValueContext` 重构
- **404/405 免栈异常**：改用 `StacklessResponseStatusException`，降低请求路径异常构造开销
- **线程池安全替换**：`BizPoolRegistry` 支持安全替换旧线程池

### 优化

- **Jackson converter**：序列化路径优化
- **CORS 异常映射**：`CorsRegistry` / 异常解析器异常映射精简

### 修复

- **SSE 单条消息溢出**：`EarlyEncodeNettyStreamSender` 单条消息超过 `maxFlushBytes` 时直接独立写入，不再抛 `IndexOutOfBoundsException`
- **`sendError()` JSON 转义**：错误响应 message 转义引号 / 反斜杠 / 控制字符，避免畸形 JSON
- **`NettyServerHttpResponse.flush()` 失败后 buffer 置空**：防止返回已释放的 ByteBuf
- **路由匹配数组越界**：`NameValueExpressionSupport` / `ParamOrHeaderMatcher` / `SuffixPathRouterOptimizer` 修复
- **Async 状态校验**：`AsyncSupportUtils` / `PerfAsyncWebRequest` 状态校验逻辑修正
- **错误信息修正**：`AbstractFastFailHttpServletRequest` 10 处复制粘贴错误信息改为对应方法名
- **Actuator 管理端口 Bean**：补 `@ConditionalOnMissingBean`
- **Batch 优雅停机**：`DisruptorQueue` / `NettyHttpServer` 停机流程完善

### 构建

- **`spring-boot-maven-plugin` 版本纳入根 pom `pluginManagement`**：子模块插件版本与 `${spring-boot.version}` 对齐
- **JDK 25 构建支持**：Lombok 升级至 1.18.46、Mockito 升级至 5.19.0、ByteBuddy 升级至 1.18.11，修复 JDK 25 下 Lombok 注解处理崩溃（`TypeTag::UNKNOWN`）及测试期兼容性

### 文档

- **README 默认语言改为英文**，新增 `README_CN.md`
- **`module.md` 等 AI 上下文文档更新**

## [3.2.3] - 20260710

### 新增

- **AI/LLM 集成示例**：新增 `spring-web-example-ai` 示例子模块，演示 Spring AI（OpenAI 兼容 API）的同步对话与 SSE 流式交互
- **Metrics SPI 机制**：新增 `WebMetrics` / `NoOpWebMetrics` 核心指标 SPI，在请求路径零开销（NoOp 通过 JIT 常量折叠消除计时路径）
  - `MicrometerWebMetrics` — 基于 Micrometer 的实现，记录 `dispatcher.request.duration`（Timer，按 method/path/status 标签）、`dispatcher.exception`（Counter，按 type/resolved 标签）
  - `BatchMetrics` / `NoOpBatchMetrics` — 批处理模块指标 SPI
  - `MicrometerBatchMetrics` — 基于 Micrometer 的批处理指标实现，记录入队/丢弃/溢出/处理耗时/批次大小/队列容量等指标
  - 自动装配：`spring-boot-starter-web` 引入 Micrometer 时自动激活，无需额外配置
- **BizPoolRegistry 增强**：自动发现 Spring 容器中的 `ExecutorService` Bean 并注册为线程池；`@RunInPool("beanName")` 未命中本地 pools 时兜底到 Spring 容器按名称查找
- **BizPoolRegistry Micrometer Gauges**：自动为已注册的线程池注册 active threads / queue size / completed tasks 三个 Gauge
- **Netty 层指标**：新增 `NettyMetricsHandler`（Sharable ChannelHandler），通过 `channelActive`/`channelInactive` 追踪活跃 TCP 连接数
  - 自动注册 `netty.connections.active` Gauge（当前活跃 TCP 连接数）
  - 自动注册 `netty.eventloop.pending.tasks` Gauge（所有 EventLoop 待处理任务总数）
  - 暴露 `NettyHttpServer.getWorkerGroup()` / `getActiveConnectionCount()` 用于指标注册

### 重构

- **Benchmark 模块重构**：消除冗余的 `*-filter` 独立子模块（perf-filter / tomcat-filter / undertow-filter / webflux-filter），将 filter 场景合并到对应主模块中，Maven profiles 从 10 个精简到 5 个；删除 Windows 批处理脚本，统一为 shell 脚本
- **Benchmark 场景重命名**：`validatePost` → `valid`、`sseStream` → `sse`、`jsonEchoLarge` / `largeResponse` → `bytesLarge`，语义更清晰
- **Benchmark DTO**：新增 `UserReq` / `UserResp` DTO 类，统一请求/响应模型
- **报告生成器增强**：`ReportGenerator` 重构，`GcMetrics` / `Jdk8GcLogParser` / `Jdk11GcLogParser` 增强 GC 日志解析

### 安全

- **转发头保护**：`Forwarded` / `X-Forwarded-Proto` 头默认不信任，新增 `server.use-forwarded-headers=false` 配置开关；部署在反向代理后方时需显式开启，防止客户端伪造 scheme
- **CRLF 注入防护**：`NettyMultipartFile.buildContentDisposition()` 对 name/filename 进行 `\r` / `\n` 清洗，防止 multipart 文件上传时 HTTP 响应头分裂攻击
- **HTTP 解析器限制**：新增 `server.http.max-initial-line-length`（默认 4KB）、`server.http.max-header-size`（默认 8KB）、`server.http.max-chunk-size`（默认 8KB）配置项，防止超大请求行/头部/chunk DoS 攻击
- **读超时保护**：新增 `server.http.read-timeout`（默认 30s）配置项，请求体聚合前防止慢速客户端无限期占用连接
- **WebSocket Origin 校验增强**：`setAllowedOrigins` 传入空列表时拒绝所有带 Origin 头的跨域请求（之前空列表等同于未配置）
- **异常信息收敛**：`ExceptionRegistry.handle()` 未处理异常时返回通用 `Internal Server Error` 而非将异常类名/消息暴露给客户端

### 优化

- **访问日志**：新增 `AccessLogWebFilter`，通过 `server.accesslog.enabled=true` 开启，以最早 Order 在 Filter 链最外层统计完整请求耗时；日志格式：`remoteAddr method uri statusCode elapsedMs "user-agent"`
- **日志参数化**：全局使用 SLF4J `{}` 占位符替代字符串拼接（InterceptorRegistry、PathPatternRouter、NettyServerHttpResponse、BackpressureHandler 等），消除日志关闭时的字符串构造开销
- **异常日志增强**：关键路径异常日志包含完整堆栈（`NettyStreamSender`、SSE 超时清理异常等），便于问题定位
- **`BizPoolRegistry.register()`**：接受 `ExecutorService` 而非 `ThreadPoolExecutor`，支持更广泛的线程池类型
- **优雅关闭**：新增 `NettyHttpHandler.setShuttingDown()`，关闭时新到达请求直接返回 503 `Service Unavailable`；`ManagementNettyHttpServer.stop()` 先拒绝新请求再关闭 EventLoopGroup，避免优雅关闭过程中处理新请求
- **应用层背压**：`DispatcherHandler.handleWithMappingResult()` 捕获 `RejectedExecutionException`，业务线程池满载时返回 503 + `Retry-After: 5` 头，避免阻塞 EventLoop 线程；优雅关闭中线程池已关闭时回退到 EventLoop 兜底执行

### 修复

- **SSE 超时监测任务未清除**：`NettyStreamSender.onCompleteSuccess()` / `onCompleteError()` 中调用 `resp.setTimeout(null, -1)` 取消超时调度任务，防止流结束后超时任务残留导致连接泄漏

### 文档

- **README / CONTRIBUTING / SECURITY 文档更新**
- **文档内容纠正**

## [2.7.3] - 20260710

### 新增

- **Metrics SPI 机制**：新增 `WebMetrics` / `NoOpWebMetrics` 核心指标 SPI，在请求路径零开销（NoOp 通过 JIT 常量折叠消除计时路径）
  - `MicrometerWebMetrics` — 基于 Micrometer 的实现，记录 `dispatcher.request.duration`（Timer，按 method/path/status 标签）、`dispatcher.exception`（Counter，按 type/resolved 标签）
  - `BatchMetrics` / `NoOpBatchMetrics` — 批处理模块指标 SPI
  - `MicrometerBatchMetrics` — 基于 Micrometer 的批处理指标实现，记录入队/丢弃/溢出/处理耗时/批次大小/队列容量等指标
  - 自动装配：`spring-boot-starter-web` 引入 Micrometer 时自动激活，无需额外配置
- **BizPoolRegistry 增强**：自动发现 Spring 容器中的 `ThreadPoolExecutor` Bean 并注册为线程池；`@RunInPool("beanName")` 未命中本地 pools 时兜底到 Spring 容器按名称查找
- **BizPoolRegistry Micrometer Gauges**：自动为已注册的线程池注册 active threads / queue size / completed tasks 三个 Gauge
- **Netty 层指标**：新增 `NettyMetricsHandler`（Sharable ChannelHandler），通过 `channelActive`/`channelInactive` 追踪活跃 TCP 连接数
  - 自动注册 `netty.connections.active` Gauge（当前活跃 TCP 连接数）
  - 自动注册 `netty.eventloop.pending.tasks` Gauge（所有 EventLoop 待处理任务总数）
  - 暴露 `NettyHttpServer.getWorkerGroup()` / `getActiveConnectionCount()` 用于指标注册

### 重构

- **Benchmark 模块重构**：消除冗余的 `*-filter` 独立子模块（perf-filter / tomcat-filter / undertow-filter / webflux-filter），将 filter 场景合并到对应主模块中，Maven profiles 从 10 个精简到 5 个；删除 Windows 批处理脚本，统一为 shell 脚本
- **Benchmark 场景重命名**：`validatePost` → `valid`、`sseStream` → `sse`、`jsonEchoLarge` / `largeResponse` → `bytesLarge`，语义更清晰
- **Benchmark DTO**：新增 `UserReq` / `UserResp` DTO 类，统一请求/响应模型
- **报告生成器增强**：`ReportGenerator` 重构，`GcMetrics` / `Jdk8GcLogParser` / `Jdk11GcLogParser` 增强 GC 日志解析

### 安全

- **转发头保护**：`Forwarded` / `X-Forwarded-Proto` 头默认不信任，新增 `server.use-forwarded-headers=false` 配置开关；部署在反向代理后方时需显式开启，防止客户端伪造 scheme
- **CRLF 注入防护**：`NettyMultipartFile.buildContentDisposition()` 对 name/filename 进行 `\r` / `\n` 清洗，防止 multipart 文件上传时 HTTP 响应头分裂攻击
- **HTTP 解析器限制**：新增 `server.http.max-initial-line-length`（默认 4KB）、`server.http.max-header-size`（默认 8KB）、`server.http.max-chunk-size`（默认 8KB）配置项，防止超大请求行/头部/chunk DoS 攻击
- **读超时保护**：新增 `server.http.read-timeout`（默认 30s）配置项，请求体聚合前防止慢速客户端无限期占用连接
- **WebSocket Origin 校验增强**：`setAllowedOrigins` 传入空列表时拒绝所有带 Origin 头的跨域请求（之前空列表等同于未配置）
- **异常信息收敛**：`ExceptionRegistry.handle()` 未处理异常时返回通用 `Internal Server Error` 而非将异常类名/消息暴露给客户端

### 优化

- **访问日志**：新增 `AccessLogWebFilter`，通过 `server.accesslog.enabled=true` 开启，以最早 Order 在 Filter 链最外层统计完整请求耗时；日志格式：`remoteAddr method uri statusCode elapsedMs "user-agent"`
- **日志参数化**：全局使用 SLF4J `{}` 占位符替代字符串拼接（InterceptorRegistry、PathPatternRouter、NettyServerHttpResponse、BackpressureHandler 等），消除日志关闭时的字符串构造开销
- **异常日志增强**：关键路径异常日志包含完整堆栈（`NettyStreamSender`、SSE 超时清理异常等），便于问题定位
- **`BizPoolRegistry.register()`**：接受 `ExecutorService` 而非 `ThreadPoolExecutor`，支持更广泛的线程池类型
- **优雅关闭**：新增 `NettyHttpHandler.setShuttingDown()`，关闭时新到达请求直接返回 503 `Service Unavailable`；`ManagementNettyHttpServer.stop()` 先拒绝新请求再关闭 EventLoopGroup，避免优雅关闭过程中处理新请求
- **应用层背压**：`DispatcherHandler.handleWithMappingResult()` 捕获 `RejectedExecutionException`，业务线程池满载时返回 503 + `Retry-After: 5` 头，避免阻塞 EventLoop 线程；优雅关闭中线程池已关闭时回退到 EventLoop 兜底执行

### 修复

- **SSE 超时监测任务未清除**：`NettyStreamSender.onCompleteSuccess()` / `onCompleteError()` 中调用 `resp.setTimeout(null, -1)` 取消超时调度任务，防止流结束后超时任务残留导致连接泄漏

### 文档

- **README / CONTRIBUTING / SECURITY 文档更新**
- **文档内容纠正**

## [3.2.2] - 20260705

### 新增

- **Servlet 会话支持**：桥接模块完整实现 `jakarta.servlet.http.HttpSession` 接口
  - `PerfHttpSession` — 基于框架请求上下文的 Servlet HttpSession 实现，支持属性存取、过期跟踪和失效生命周期
  - `PerfHttpSessionManager` — 会话管理器，支持创建/获取/失效操作
  - `HttpSessionStorage` / `InMemoryHttpSessionStorage` — 可插拔的会话存储 SPI 及默认内存实现
  - `ServletAdapterContext` 增强，将会话传播到 Servlet API 包装器
- **示例模块扩展**：从 5 个增加到 12 个可运行的示例子模块
  - `spring-web-example-actuator` — Actuator 端点监控示例
  - `spring-web-example-async` — 异步请求处理（Callable/DeferredResult/SSE）示例
  - `spring-web-example-data` — Spring Data JPA 仓储集成示例
  - `spring-web-example-openapi` — OpenAPI 3.0 文档自动生成示例
  - `spring-web-example-swaggerui` — Swagger UI 静态资源服务示例
  - `spring-web-example-shiro` — Apache Shiro 认证与会话管理示例
  - `spring-web-example-spring-security` — Spring Security 认证与会话管理示例
- **英文文档**：完整翻译全部项目文档（10 篇，涵盖概述、快速开始、高级用法、性能基准、配置、扩展、模块、性能原则、兼容性），维护于 `docs/en/` 目录
- **Swagger UI 自动配置**：新增 `SwaggerUiAutoConfiguration`，当 `swagger-ui` 在 classpath 时自动提供静态资源；`SwaggerUiProperties` 配置路径和资源位置
- **OpenAPI 文档端点**：新增 `OpenApiDocController`，在可配置路径提供 OpenAPI JSON 规范

### 优化

- **`DispatcherHandler` 优化**：精简过滤器链流程和错误路径处理
- **多版本兼容性验证**：通过 Maven profiles 验证 Spring Boot 3.0.x ~ 4.1.x 兼容性

## [2.7.2] - 20260704

### 新增

- **Servlet 会话支持**：桥接模块完整实现 `javax.servlet.http.HttpSession` 接口
  - `PerfHttpSession` — 基于框架请求上下文的 Servlet HttpSession 实现，支持属性存取、过期跟踪和失效生命周期
  - `PerfHttpSessionManager` — 会话管理器，支持创建/获取/失效操作
  - `HttpSessionStorage` / `InMemoryHttpSessionStorage` — 可插拔的会话存储 SPI 及默认内存实现
  - `ServletAdapterContext` 增强，将会话传播到 Servlet API 包装器
- **示例模块扩展**：从 5 个增加到 12 个可运行的示例子模块
  - `spring-web-example-actuator` — Actuator 端点监控示例
  - `spring-web-example-async` — 异步请求处理（Callable/DeferredResult/SSE）示例
  - `spring-web-example-data` — Spring Data JPA 仓储集成示例
  - `spring-web-example-openapi` — OpenAPI 3.0 文档自动生成示例
  - `spring-web-example-swaggerui` — Swagger UI 静态资源服务示例
  - `spring-web-example-shiro` — Apache Shiro 认证与会话管理示例
  - `spring-web-example-spring-security` — Spring Security 认证与会话管理示例
- **英文文档**：完整翻译全部项目文档（10 篇，涵盖概述、快速开始、高级用法、性能基准、配置、扩展、模块、性能原则、兼容性），维护于 `docs/en/` 目录
- **Swagger UI 自动配置**：新增 `SwaggerUiAutoConfiguration`，当 `swagger-ui` 在 classpath 时自动提供静态资源；`SwaggerUiProperties` 配置路径和资源位置
- **OpenAPI 文档端点**：新增 `OpenApiDocController`，在可配置路径提供 OpenAPI JSON 规范

### 优化

- **`DispatcherHandler` 优化**：精简过滤器链流程和错误路径处理
- **多版本兼容性验证**：通过 Maven profiles 验证 Spring Boot 2.4.x ~ 2.7.x 兼容性

## [2.7.1] - 20260701

### 新增

- **Examples 模块**：新增 `spring-web-examples` 聚合模块及 5 个示例子模块：
  - `spring-web-example-rest` — REST API 综合示例（Controller/Filter/Interceptor/异常处理）
  - `spring-web-example-batch` — 批量请求处理示例（`BatchUserController` CRUD）
  - `spring-web-example-realtime` — 实时通信示例（WebSocket 聊天 + SSE 推送）
  - `spring-web-example-servlet-bridge` — Servlet 桥接示例（Filter/Interceptor 桥接）
  - `spring-web-example-upload` — 文件上传示例（单/多文件上传、存储服务）
- **批量请求处理模块**：新增 `spring-web-batch` 模块，透明聚合高并发同类型请求为批量处理；`BatchRequest` / `@BatchMapping` / `BatchRegistry` / `BatchInvoker` 等核心组件；基于 Disruptor RingBuffer 实现背压与流量控制；自动批处理无需时间窗口

### 优化

- **配置属性重构**：`ApplicationProperties` / `PropertiesConstant` 提取公共方法，重构大小/时间配置解析，新增 `WebServerProperties` 配置类

## [3.2.0] - 20260628

### 重构

- **Jakarta EE 迁移**：`javax.servlet` → `jakarta.servlet` 6.0，Servlet API 从 4.0.1 升级到 6.0.0；`javax.validation` → `jakarta.validation`；所有桥接层代码适配 Jakarta API
- **Spring Boot 3.2.12 升级**：父 POM 从 2.7.18 升级至 3.2.12，JDK 基线从 8 提升至 17；移除显式管理的 `lombok`/`junit-jupiter`/`mockito`/`byte-buddy`/`assertj`/`servlet-api` 等版本，委托给 Spring Boot BOM 管理
- **SpringDoc OpenAPI 升级**：从 `springdoc-openapi-common` 1.7.0 迁移到 `springdoc-openapi-starter-common` 2.3.0，适配 Spring Boot 3.x 包结构
- **Spring Boot Admin 升级**：Client 版本从 2.7.10 升级到 3.2.0，适配 Jakarta API

### 新增

- **GraalVM native-image 支持**：新增 `reflect-config.json` / `resource-config.json` / `proxy-config.json` native-image 预配置，支持 GraalVM 原生镜像编译；新增 `GraalVmNativeImageConfigTest` 验证配置正确性
- **虚拟线程 E2E 测试**：新增 `VirtualThreadE2ETest`，覆盖 JDK 21 虚拟线程场景下的请求处理链路

### 优化

- **代码兼容性适配**：全局调整 `javax.*` 引用为 `jakarta.*`，包括所有 Filter/Servlet/Session 相关类及测试代码
- **Fallback 响应优化**：`ReturnValueResolverRegistry` / `BaseWebServerHttpResponse` 增加 fallback 兜底处理
- **CI 构建流升级**：JDK 17 基线构建流程适配

## [2.7.0] - 20260628

### 重构

- **版本号体系重构**：主版本号从 1.x.x 升级为 2.7.x，与 Spring Boot 主版本号对齐；项目版本从 1.0.5 升级到 2.7.0；所有子模块 parent version 同步升级

### 优化

- **Spring Boot 升级到 2.7.18**：父 POM 从 2.6.15 升级至 2.7.18，同步升级 Spring Boot Admin Client 至 2.7.10
- **CI 配置更新**：适配 Spring Boot 2.7.18 构建环境

## [1.0.5] - 20260625

### 新增

- **WebSocket 模块**：新增 `spring-web-websocket` 模块，`WebSocketConfigurer` / `WebSocketHandlerRegistration` / `WebSocketHandlerRegistry` 注册 API；`NettyWebSocketSession` 全双工通信；`WebSocketRoutingHandler` 路由分发；`WebSocketAutoConfiguration` 自动配置；E2E 测试覆盖文本、二进制、路径路由场景
- **OpenAPI 集成**：新增 `OpenApiAutoConfiguration` 自动配置 + `OpenApiAdapter`，将框架端点元数据映射为 OpenAPI 3.0 规范 JSON，支持 Swagger UI 展示；E2E 测试覆盖完整请求链路
- **`PipelineCustomizer`**：Netty ChannelPipeline 自定义扩展点，允许用户在 HTTP/1.1 和 HTTP/2 的 ChannelInitializer 中注入自定义 handler
- **`ExceptionHandlerAdvice` 增强**：处理 `HttpMediaTypeNotAcceptableException` 等更多异常类型
- **`ServletFilterPatternUtils`**：统一 Servlet Filter 路径匹配工具类，支持精确/前缀/后缀/路径匹配模式
- **`pool.default-execute-mode` 配置**：无 `@RunInPool` 时方法的默认执行策略，默认 `default`（线程池），设为 `eventloop` 可全局切回 EventLoop

### 重构

- **WebFilter 架构重构**：`WebFilter` / `WebFilterRegistration` / `WebFilterRegistry` / `DefaultFilterChain` / `RuntimeMappingWebFilter` 整体迁移到 `core/filter` 包；删除旧的 `match` 包（`ExactMatch` / `PathMatch` / `PrefixMatch` / `SuffixMatch`），统一使用 `ServletFilterPatternUtils`

## [1.0.4] - 20260621

### 新增

- **Spring Cloud 服务注册发现兼容**：在 Netty 服务器启动后发射 `WebServerInitializedEvent`，使 Nacos/Eureka/Consul 等服务注册组件能正确感知服务器就绪状态；使用 JDK 动态代理包装 `WebServerApplicationContext`，无需 Servlet 容器
- **Spring Boot Admin 兼容**：新增 `PerfApplicationFactory` 替代 SBA Client 的 `DefaultApplicationFactory`，从本框架配置自动计算 serviceUrl/managementUrl/healthUrl，不依赖 `HttpServletRequest`；新增 `SpringBootAdminClientAutoConfiguration` 自动配置，仅当用户引入 `spring-boot-admin-starter-client` 时激活
- **`NettyHttpServer.getActualPort()`**：暴露实际绑定的端口号，支持随机端口场景

## [1.0.3] - 20260618

### 新增

- **HTTP/2 支持**：新增 `Http2ChannelInitializer` 统一管理 HTTP/1.1 和 HTTP/2 的 ChannelInitializer，支持 h2（TLS ALPN）和 h2c（明文 prior knowledge）自动协商；新增 `server.http2.enabled` 配置开关
- **Netty worker 线程池可配置**：新增 `server.netty.workers` 配置项，允许自定义 worker EventLoopGroup 线程数，默认 2 × CPU 核数
- **WriteBufferWaterMark 可配置**：新增 `server.netty.write-buffer-low-watermark` / `write-buffer-high-watermark` 配置项，支持背压阈值调优（默认低水位 8KB、高水位 32KB）
- **`writeBytes(byte[])` 高效写入**：`WebServerHttpResponse` 新增 `writeBytes()` 方法，Content-Length + 单次 writeAndFlush，避免 chunked 编码，对已知大小的小负载显著提升性能
- **JMH 基准测试模块**：新增 `spring-web-benchmark` 模块，覆盖 Perf/Tomcat/Undertow/WebFlux 四种服务器，含裸机/Filter/Interceptor/SSE 对比场景及自动报告生成（GC 日志解析、内存快照）
- **文档**：新增 `docs/benchmark.md` 基准测试说明文档、README 补充

### 优化

- **SSE drain() 竞态修复**：wip 归零但队列仍有数据时主动重调度 drain，防止 `NettyStreamSender` 在生产者快于 drain 时数据残留丢失；使用 `DefaultHttpContent` 包装每个 SSE 写入块
- **NettyHttpServer 绑定失败安全清理**：端口绑定失败时及时 shutdown boss/worker EventLoopGroup，防止线程残留阻止 JVM 退出
- **代码风格统一**：`BaseWebServerHttpResponse` / `NettyServerHttpResponse` 多处格式化及方法提取

## [1.0.2] - 20260608

### 修复

- `InvokableHandlerMethod.createMethodParameters()` 获取参数，当 Controller 被代理时返回代理类的桥接方法而非真实方法导致无法正确读取参数注解
- `MappingRegistry.initComponentPhase1()` 扫描 Controller 时注解被代理对象擦除导致无法读取 `@RequestMapping`
- `MappingRegistry.initComponentPhase1()` 解析类级别 `@RequestMapping` 时仅提取 `value/path` 作为路径前缀，忽略了 `method`、`params`、`headers`、`consumes`、`produces` 约束属性

### 新增

- **类级别 @RequestMapping 约束合并**：`initComponentPhase1()` 提取类级别的 `method/params/headers/consumes/produces` 约束，通过 `mergeMatchers()` / `mergeMatcherPair()` 与方法级别约束合并（同类型合并内部列表，不同类型追加）
- **占位符解析**：`initComponentPhase1()` / `initMethodMappingContext()` 中对路径、params、headers、consumes、produces 支持 `${...}` 占位符，通过 `Environment.resolvePlaceholders()` 运行时解析
- **Spring Data Web 兼容**：新增 `SpringDataWebCompatibilityAutoConfiguration`，自动注册 `PageableHandlerMethodArgumentResolver` / `SortHandlerMethodArgumentResolver`，支持 Spring Data 分页排序参数绑定
- **统一文档注释**：为 `WebComponent`、`LifecycleWebComponent`、`WebFilter`、`FilterChain`、`HandlerInterceptor`、`CorsRegistration`、`WebServerHttpRequest`、`WebServerHttpResponse`、`RequestContext`、`Matcher`、`Router` 等全部核心接口补充 JavaDoc，明确组件契约与生命周期语义
- **新增 E2E 测试套件**：Proxy 代理继承测试（~40 个文件覆盖 Controller/API 继承、条件装配、参数绑定、过滤器排序、异常处理器等场景）、CoreFeatures E2E（P0/P1/P2）、类级别约束 E2E、并发压测、管理端口冲突测试、静态资源安全测试、Netty 错误路径测试、HTTP 请求生命周期测试
- **新增单元测试**：MappingRegistryTest 新增 13 个类级别约束合并测试、InvokableHandlerMethodTest 增强代理类参数注解测试、DispatcherHandlerTest / NettyHttpHandlerTest / AsyncSupportRegistryTest 增强

## [1.0.1] - 20260606

### 重构

- 统一类命名：`Perf*` → `SpringWeb*` / `Actuator*`，消除 `perf` 前缀歧义
- Actuator 管理端口引入独立分发器 `ManagementDispatcherHandler`，切断与主 `DispatcherHandler` 的循环依赖

### 新增

- **spring-web-support bridge 层**：兼容 Spring MVC 的 `WebMvcConfigurer` 适配（`WebMvcConfigurerBridge`），支持通过 Spring MVC 配置接口注入拦截器、参数解析器、返回值处理器、异常处理器
- **资源文件处理**：`ResourceHandlerRegistry`，支持静态资源映射和 classpath 资源服务
- **WebFilter 支持**：支持响应式风格的 `WebFilter` 过滤器链
- **管理端口 SSL**：`management.server.ssl.*` 配置支持，管理端口 HTTPS 独立部署
- **拦截器生命周期**：`LifecycleInterceptor`，监控请求处理全生命周期
- **自定义类型格式化器**：`Formatter` / `Converter` 注册表，支持 `@DateTimeFormat` 等注解
- **参数绑定**：`@RequestParam` 数据绑定增强，支持 `WebDataBinder` 注册表
- **异步支持可配置**：`AsyncSupportConfigurer`，允许自定义 async timeout / task executor
- **异常解析器递归深度限制**：最大 10 层，防止无限递归
- **映射注册表优化**：单遍遍历替代三次流遍历

### 修复

- `MetaUtils.getDefaultValue` 中使用 `==` 比较 `ValueConstants.DEFAULT_NONE`，注解代理返回不同 String 引用导致比较始终为 false，`@RequestHeader(required=true)` 不生效
- `AbstractNamedValueNullableResolver.handleMissingValue` 抛出 `IllegalStateException`（500），应抛出 `ResponseStatusException(BAD_REQUEST)` 返回 400
- `WebContext` 构造函数未设置 `webContext` 自引用，导致 `WebComponentContainer.registerWebComponent(Class)` 在管理端口模式下 NPE

## [1.0.0] - 20260603

### 新增

- 基于 Netty 4.1 的 REST Web 服务器
- Spring Boot 自动配置支持（`spring-boot-starter-web`）
- 注解驱动的 Controller 映射（`@RestController`、`@RequestMapping` 等）
- 参数解析与返回值处理
- JSON 序列化（Jackson / Fastjson2 可选）
- 文件上传支持
- SSE（Server-Sent Events）支持
- 异步响应（ListenableFuture）
- 拦截器与过滤器链
- 全局异常处理（`@ExceptionHandler`、`@RestControllerAdvice`）
- CORS 配置支持
- 路径匹配（Ant 风格 + 正则）
- Actuator 集成（独立管理端口）
- Servlet API 兼容模块（`spring-web-support`）
- 管理端点独立分发器（ManagementDispatcherHandler）
- 映射注册表优化（单遍遍历替代三次流遍历）
- 异常解析器递归深度限制（最大 10 层）