# 变更日志

本项目遵循 [语义化版本控制](https://semver.org/lang/zh-CN/)。

## [3.2.1] - 20260702

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
- **Lombok 版本修复**：annotation processor 路径从硬编码 1.18.24 改为 ${lombok.version}（1.18.36），修复 JDK 21 CI 构建失败问题

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
