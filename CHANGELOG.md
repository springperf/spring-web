# 变更日志

本项目遵循 [语义化版本控制](https://semver.org/lang/zh-CN/)。

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
