# 模块详解

```
spring-web-parent
├── spring-web                  核心框架
├── spring-web-support          可选 Servlet 桥接层
├── spring-boot-starter-web     Spring Boot 自动配置
├── spring-web-test             测试应用
└── spring-web-support-test     支持模块测试
```

---

## spring-web（核心框架）

### server — Netty 服务器

基于 Netty 4.1 的非阻塞传输层，请求在 EventLoop 上同步处理。

- **NettyHttpServer** 实现 `SmartLifecycle`，随 Spring 容器自动启停
- 使用 `HttpServerCodec` + `ChunkedWriteHandler` 处理 HTTP 协议
- 可选 SSL 支持
- 请求管线：`HttpServerCodec` → `ChunkedWriteHandler` → `SupportMultipartAggregator` → `BackpressureHandler` → `NettyHttpHandler`
- `NettyHttpHandler` 将 Netty 请求适配为框架的 `WebServerHttpRequest`/`WebServerHttpResponse`，然后委托给 `HttpHandler`

### filter — WebFilter 过滤器链

- `WebFilter` — SPI 接口，在请求到达 `DispatcherHandler` 之前执行
- `WebFilterRegistry` 管理过滤器列表，支持排序
- 过滤器链以 `DispatcherHandler` 作为末端处理器
- 自动注册 Spring 容器中所有 `WebFilter` 类型的 Bean

### core — 核心处理管线

#### DispatcherHandler — 中央请求分发器

请求处理流程：

```
请求进入
  ↓
WebFilterRegistry 过滤器链
  ↓
DispatcherHandler:
  1. MappingRegistry   — 路由匹配，查找 @RequestMapping 处理器
  2. CorsRegistry      — CORS 跨域检查
  3. InterceptorRegistry — preHandle 拦截前置处理
  4. ArgumentResolverRegistry — 参数解析
  5. InvokableHandlerMethod  — 处理器方法调用
  6. ReturnValueResolverRegistry — 返回值处理
  7. InterceptorRegistry — postHandle → afterCompletion
  8. ExceptionRegistry  — 任意步骤异常的统一处理
```

#### mapping — 请求映射

- `MappingRegistry` — 扫描 `@Controller`/`@RestController` 的所有 `@RequestMapping` 方法
- 支持 `@RequestMapping` 的全部属性：`value`/`path`、`method`、`params`、`headers`、`consumes`、`produces`
- 路由优化器链（`RouterOptimizer`）：通过前缀/后缀/全路径/循环优化，实现高效路由匹配
- 支持动态注册路由（`registerMappingAfterInit`），用于 Actuator 端点注册
- `Matcher` 体系：`HttpMethodMatcher`、`ParamOrHeaderMatcher`、`ConsumeOrProduceMatcher` 等

#### arg — 参数解析器

支持所有 Spring MVC 标准参数注解：

| 注解 | 解析器 | 说明 |
|------|--------|------|
| `@PathVariable` | `PathVariableResolverProvider` | URL 路径变量 |
| `@RequestParam` | `RequestParamResolverProvider` | 查询参数/表单参数 |
| `@RequestHeader` | `RequestHeaderResolverProvider` | 请求头 |
| `@RequestBody` | `RequestBodyResolverProvider` | JSON 请求体（通过 `HttpBodyCodecRegistry`） |
| `@RequestPart` | `RequestPartResolverProvider` | 文件上传（multipart） |
| `@ModelAttribute` | `ModelAttributeResolverProvider` | 数据绑定（通过 `WebDataBinder`） |

此外支持自动解析：`HttpEntity`/`RequestEntity`、`MultipartFile`、`BindingResult`/`Errors`、`Locale`/`TimeZone`、`WebServerHttpRequest`/`WebServerHttpResponse`。

数据绑定通过 `WebDataBinderRegistry` 实现，支持：
- `@InitBinder` 方法（来自 `@ControllerAdvice`）
- `ConversionService` 类型转换
- JSR-303 `Validator` 校验
- `MessageCodesResolver` 错误码解析

#### retval — 返回值解析器

支持以下返回值类型：

| 返回值类型 | 解析器 |
|-----------|--------|
| `@ResponseBody` 标注的对象 | `JsonBodyReturnValueResolver` |
| `ResponseEntity`/`HttpEntity` | `HttpEntityReturnValueResolver` |
| `DeferredResult` | `DeferredResultReturnValueResolver` |
| `Callable` | `CallableReturnValueResolver` |
| `ListenableFuture`/`CompletionStage` | 对应 Future 解析器 |
| `StreamEmitter`/`SseEmitter` | `StreamEmitterReturnValueResolver` |
| `Publisher`（Reactive） | `ReactiveReturnValueResolver` |
| `byte[]` | `ByteArrayReturnValueResolver` |
| `Resource` | `ResourceReturnValueResolver` |
| `InputStream` | `InputStreamReturnValueResolver` |
| `File` | `FileReturnValueResolver` |

#### interceptor — 处理器拦截器

- `HandlerInterceptor` 定义三个切入点：
  - `preHandle` — 处理器执行前（返回 false 则中断）
  - `postHandle` — 处理器执行后、返回值渲染前
  - `afterCompletion` — 请求完成后（无论是否异常）
- `InterceptorRegistry` 管理拦截器注册，支持路径包含/排除模式
- 自动注册 Spring 容器中 `HandlerInterceptor` 或 `InterceptorRegistration` 类型的 Bean

#### async — 异步支持

- `DeferredResult` — 在任意线程设置返回值
- `Callable` — 异步执行返回
- `StreamEmitter` — 流式写入多个数据块
- `SseEmitter` / `SseJsonEmitter` — 服务端推送事件
- 响应式支持（可选依赖 reactive-streams）：
  - `Publisher` → `DeferredResult` / `StreamEmitter` 适配
  - 支持背压配置（`@ReactiveSupport` 注解的 `highWaterMark`/`lowWaterMark`）

#### exception — 异常处理

- `ExceptionHandlerExceptionResolver` — 扫描 `@ControllerAdvice` 中的 `@ExceptionHandler` 方法
- `ResponseStatusExceptionResolver` — 处理 `@ResponseStatus` 和 `ResponseStatusException`
- 支持自定义 `HandlerExceptionResolver` 扩展

#### codec — HTTP 消息编解码

- `HttpBodyConverter` 包装 Spring 的 `HttpMessageConverter`，用于请求体读取和响应体写入
- `HttpBodyCodecRegistry` 管理所有转换器，根据 `Content-Type` 和 `Accept` 内容协商
- 自动注册 Spring 容器中所有 `HttpMessageConverter` 类型的 Bean
- `HttpBodyCodecInterceptor` 提供写入/读取拦截点

#### cors — 跨域处理

- 支持 `@CrossOrigin` 注解
- 支持编程式注册（`CorsRegistry` / `CorsRegistration`）
- `PerfCorsProcessor` 基于 Spring 的 `DefaultCorsProcessor`

#### resource — 静态资源

- `ResourceHandlerRegistry` 管理静态资源映射
- 支持路径到文件系统/classpath 的映射

### http — HTTP 请求/响应抽象

- `WebServerHttpRequest` / `WebServerHttpResponse` — 框架内统一请求/响应接口
- 提供文件上传支持：`NettyMultipartFile`、`NettyMultipartWebRequest`
- 引用计数管理（`acquire`/`release`），避免 Netty ByteBuf 泄漏

### json — JSON 抽象层

- `JsonConverter` SPI：统一 `toJson`/`fromJson` 接口
- `JacksonConverter` — 默认实现，基于 Jackson
- `FastjsonConverter` — 可选实现，基于 Fastjson 2（provided 依赖）

### context — 组件容器

- `WebComponent` — 所有框架组件的基接口（继承 `Ordered`）
- `LifecycleWebComponent` — 带生命周期方法的组件（3 阶段初始化 + 销毁）
- `BaseWebComponent` — 持有 `WebContext` 引用的抽象基类
- `WebComponentContainer` — 组件注册表基础实现
- `WebContext` — 应用上下文，实现 `InitializingBean`，驱动各组件生命周期

组件初始化阶段：
```
initWithWebContext()   → 依赖装配
initComponentPhase1()  → 扫描 Spring Bean、注册 mapping、构建策略列表
initComponentPhase2()  → 构建内部结构、路由优化
initComponentPhase3()  → 预热、fail-fast 校验
destroyComponent()     → 资源释放
```

---

## spring-web-support（Servlet 桥接层）

当需要与 Servlet API 生态集成时添加此模块。

### 功能

| 功能 | 实现 |
|------|------|
| Servlet Filter 集成 | `FilterWrapper` 将 `javax.servlet.Filter` 包装为 `WebFilter` |
| Spring MVC 拦截器桥接 | `HandlerInterceptorWrapper` 适配 Spring MVC 的 `HandlerInterceptor` |
| RequestBodyAdvice / ResponseBodyAdvice | `SupportHttpBodyCodecInterceptorRegistry` 扫描并适配 |
| Servlet API 参数解析 | `HttpServletRequestProvider` / `HttpServletResponseProvider` |
| ResponseBodyEmitter | `ResponseBodyEmitterReturnValueResolver` |

### 使用场景

- 需要复用已有的 Servlet Filter（如 Spring Security Filter Chain）
- 需要复用已有的 `RequestBodyAdvice` / `ResponseBodyAdvice`
- 需要复用已有的 Spring MVC `HandlerInterceptor`

添加依赖：

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-support</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

---

## spring-boot-starter-web（自动配置）

### 核心自动配置

`SpringWebAutoConfiguration` 自动装配：

- `ApplicationProperties` — 从 `Environment` 读取配置
- `WebContext` — 应用上下文，驱动组件生命周期
- 所有 Registry 组件（MappingRegistry、InterceptorRegistry 等）
- `NettyHttpServer` — 启动 Netty 服务器，支持 SSL

### Support 自动配置

`SpringWebSupportAutoConfiguration` 在 `spring-web-support` 存在时自动装配支持模块组件。

### Actuator 自动配置

`ActuatorEndpointAutoConfiguration` 在 Actuator 存在时自动配置端点：
- 将 `ExposableWebEndpoint` 注册为框架路由
- 支持独立管理端口（`management.server.port`）
- 独立的 Management Netty 服务器

### 应用上下文

`WebServerApplicationContextFactory` 强制使用 `AnnotationConfigApplicationContext`，替代 Spring Boot 默认的 `AnnotationConfigServletWebServerApplicationContext`。