# 变更日志

本项目遵循 [语义化版本控制](https://semver.org/lang/zh-CN/)。

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
