> [English](en/modules.md) | 中文

# 模块详解

```
spring-web-parent (聚合 POM)
├── spring-web                  核心框架
├── spring-web-view             视图渲染（Thymeleaf/FreeMarker，可选）
├── spring-web-servlet          Servlet API 桥接层
├── spring-web-mvc-support     SpringMVC 兼容桥接层
├── spring-web-batch            批量请求处理（可选）
├── spring-web-websocket        WebSocket 支持（可选）
├── spring-boot-starter-web     Spring Boot 自动配置
├── spring-web-test             测试应用
├── spring-web-support-test     支持模块测试
├── spring-web-benchmark        JMH 性能基准测试
└── spring-web-examples         可运行示例应用聚合
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

- `WebFilter` — SPI 接口，路由匹配后由 handleWithFilter() 触发 Filter 链，完成后回调 handleAfterFilter()
- `WebFilterRegistry` 管理过滤器列表，支持排序
- 过滤器链完成后通过 `DefaultFilterChain` 回调 `DispatcherHandler.handleAfterFilter()`
- 自动注册 Spring 容器中所有 `WebFilter` 类型的 Bean

### core — 核心处理管线

#### DispatcherHandler — 中央请求分发器

请求处理流程：

```
请求进入
  ↓
DispatcherHandler.handle()
  ↓
MappingRegistry — 路由匹配，查找 @RequestMapping 处理器
  ↓
BizPoolRegistry — @RunInPool 线程池判断（可选）
  ↓
handleWithFilter() → WebFilterRegistry 过滤器链
  ↓  Filter 链完成后回调
handleAfterFilter() — 初始化上下文
  ↓
  ├── doHandle() (完全匹配):
  │   ├── CorsRegistry              — CORS 检查
  │   ├── InterceptorRegistry       — preHandle
  │   ├── ArgumentResolverRegistry  — 参数解析
  │   ├── InvokableHandlerMethod    — 处理器调用
  │   └── ReturnValueResolverRegistry — 返回值处理
  │   └── InterceptorRegistry       — postHandle → afterCompletion
  └── 404/405 → ExceptionRegistry + afterCompletion
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

## spring-web-servlet（Servlet API 桥接层）

**零 SpringMVC 依赖**的纯 Servlet 兼容桥接层。仅引入本模块 + `spring-web` 即可在 Netty 上运行 Servlet 规范生态（Filter、Servlet、HttpSession、JSP），classpath **不会**出现任何重写的 `org.springframework.web.servlet.*` 类。

### 功能

| 功能 | 实现 |
|------|------|
| Servlet Filter 集成 | `FilterWrapper` 将 `jakarta.servlet.Filter` 包装为 `WebFilter`，`SupportWebFilterRegistry` 自动注册 Filter Bean / `FilterRegistrationBean` |
| Servlet API 参数解析 | `HttpServletRequestProvider` / `HttpServletResponseProvider` / `ServletRequestProvider` / `ServletResponseProvider` / `WebRequestArgumentResolverProvider` |
| 会话参数 | `SessionAttributeArgumentResolverProvider`（`@SessionAttribute`）/ `SessionStatusArgumentResolverProvider`（`SessionStatus`）、`SessionAttributesInterceptor`（`@SessionAttributes`）、`SessionScopeBeanFactoryPostProcessor`（`session` 作用域 Bean） |
| HttpSession | `PerfHttpSession` / `PerfHttpSessionManager` / `HttpSessionStorage` / `InMemoryHttpSessionStorage` |
| Servlet 对象路由 | `SupportServletRegistry` 扫描 Servlet Bean / `@WebServlet` 注册为框架路由；`ServletInvoker`、`PerfServletConfig` |
| JSP 视图 | `JasperJspServlet` + `JspViewResolver` / `JspView`（Apache Jasper + JSTL，`jsp:` / `.jsp` 视图名） |
| Servlet 请求/响应包装 | `PerfHttpServletRequest` / `PerfHttpServletResponse`、`ServletAdapterContext`、`PerfRequestDispatcher`（forward/include） |
| 桥接分发器 | `SupportDispatcherHandler` 初始化 `RequestContextHolder` + 响应完成后 Session flush |

### 使用场景

- 需要复用已有的 Servlet Filter（如 Spring Security 的 `FilterChainProxy` 链）
- 需要 HttpSession / `@SessionAttribute` / `@SessionAttributes` / `session` 作用域 Bean
- 需要把已有 `Servlet` 对象注册为路由，或使用 JSP 视图
- 用不到 Spring MVC 生态组件（`WebMvcConfigurer`、`HandlerInterceptor`、`RequestBodyAdvice` 等）

### 依赖

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-servlet</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

---

## spring-web-mvc-support（SpringMVC 兼容桥接层）

桥接 Spring MVC 生态组件（`WebMvcConfigurer`、`HandlerInterceptor`、`RequestBodyAdvice` / `ResponseBodyAdvice`、`ModelAndView` 等）。**依赖 `spring-web-servlet`**——Spring MVC API 构建于 Servlet 之上。

> 注意：本模块会在编译期与框架代码一起打包重写的 `org.springframework.web.servlet.*` 类（同包同名优先于官方 `spring-webmvc`）。仅引入 `spring-web-servlet` 的用户看不到任何这类类。

### 功能

| 功能 | 实现 |
|------|------|
| `WebMvcConfigurer` 桥接 | `WebMvcConfigurerBridge` 将 `WebMvcConfigurer` 配置映射到框架各 Registry |
| Spring MVC 拦截器桥接 | `SupportInterceptorRegistry` 扫描 + `HandlerInterceptorWrapper` 适配 Spring MVC `HandlerInterceptor` |
| RequestBodyAdvice / ResponseBodyAdvice | `SupportHttpBodyCodecInterceptorRegistry` + `RequestBodyAdviceCodecInterceptor` / `ResponseBodyAdviceCodecInterceptor` |
| Spring 参数解析器 | `SpringHandlerMethodArgumentResolverProvider` 适配 `HandlerMethodArgumentResolver` |
| Spring 返回值处理器 | `SpringHandlerMethodReturnValueHandlerAdapter` 适配 `HandlerMethodReturnValueHandler`；`ModelAndViewReturnValueResolver` 桥接 `ModelAndView` |
| Spring 异常解析器 | `SpringHandlerExceptionResolverAdapter` 适配 `HandlerExceptionResolver` |
| ResponseBodyEmitter | `ResponseBodyEmitterReturnValueResolver`（含 SseEmitter / StreamingResponseBody） |
| 重写的 Spring MVC API | `org.springframework.web.servlet.*`：`HandlerInterceptor`、`ModelAndView`、`View`、`MappedInterceptor`、`WebMvcConfigurer`、`InterceptorRegistration`、`RequestBodyAdvice` / `ResponseBodyAdvice` 等 |

### 使用场景

- 需要复用 Spring MVC 生态组件：`WebMvcConfigurer`、`HandlerInterceptor`、`RequestBodyAdvice` / `ResponseBodyAdvice`、`HandlerMethodArgumentResolver`、`ModelAndView`
- 从 Spring MVC 迁移且依赖上述 API 的业务代码

### 依赖

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-mvc-support</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

### 选择建议

- **只引入 `spring-web-servlet`**：仅需 Servlet 兼容（Filter/HttpSession/JSP），classpath 干净，不出现任何 Spring MVC 重写类——与官方 `spring-webmvc` 无冲突。
- **`spring-web-servlet` + `spring-web-mvc-support`**：需要复用 Spring MVC 生态 API 时引入。代价是打包了 `org.springframework.web.servlet.*` 重写类，与官方 spring-webmvc 同包同名、classpath 优先加载，因此**不可与官方 `spring-webmvc` 共存于同一应用**。

---

## spring-web-view（视图渲染）

基于 Thymeleaf / FreeMarker 模板引擎的服务器端渲染（SSR）模块。**核心模块零改动**——完全通过核心 SPI（`ReturnValueResolver` / `StaticArgumentResolverProvider` / `ViewResolver`）接入，不注册任何视图解析器时 String 返回值保持 JSON 行为，纯 API 项目零惊扰。

> 详细使用文档：[视图渲染](view.md)

### 依赖

`spring-web-view` 对模板引擎为 `provided` 依赖，需显式引入：

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-view</artifactId>
    <version>${spring-web.version}</version>
</dependency>

<!-- Thymeleaf（默认）或 freemarker -->
<dependency>
    <groupId>org.thymeleaf</groupId>
    <artifactId>thymeleaf</artifactId>
</dependency>
```

### 核心类

| 类 | 说明 |
|------|------|
| `View` | 渲染 SPI：`render(model, req, resp)`，无 servlet 依赖 |
| `ViewResolver` | 解析 SPI：`resolveViewName(name, locale, req)`，按 `getOrder()` 排序 |
| `ViewResolverRegistry` | `ViewResolver` 注册中心，空则 String 保持 JSON |
| `RedirectView` | `redirect:` 前缀 → 302 + query 参数序列化 |
| `ModelSupport` | 请求级 Model 容器（`ExtendedModelMap`）挂 `RequestContext` |
| `ModelArgumentResolverProvider` | `Model` / `ModelMap` / `ExtendedModelMap` 参数注入 + `postProcess` 5 步 Model 初始化（`@ControllerAdvice`/局部 `@ModelAttribute` 方法、`@ModelAttribute` 参数、`@PathVariable`、`BindingResult`） |
| `ViewReturnValueResolver` | 无 `@ResponseBody` 的 String → 视图名（order=MAX-200，先于 JsonBody） |
| `ThymeleafViewResolver` | Thymeleaf 引擎适配（核心 API，零 servlet） |
| `FreemarkerViewResolver` | FreeMarker 引擎适配 |
| `ThymeleafWebContext` | 自实现 `IWebContext` / `IWebExchange`，`@{...}` URL 方言经 `transformURL` 适配 `context-path` |

### 接入机制

- `ViewReturnValueResolver` / `ModelAndViewReturnValueResolver` 为 Spring Bean，被 `ReturnValueResolverRegistry` 自动吸收
- `ModelArgumentResolverProvider` 为 Spring Bean，被 `ArgumentResolverRegistry` 自动吸收
- `ThymeleafViewResolver` / `FreemarkerViewResolver` 为 Spring Bean，被 `ViewResolverRegistry` 自动吸收
- 引擎选择由 `spring.web.view.engine` 配置项 + classpath 探测联合决定

---

## spring-boot-starter-web（自动配置）

### 核心自动配置

`SpringWebAutoConfiguration` 自动装配：

- `ApplicationProperties` — 从 `Environment` 读取配置
- `WebContext` — 应用上下文，驱动组件生命周期
- 所有 Registry 组件（MappingRegistry、InterceptorRegistry 等）
- `NettyHttpServer` — 启动 Netty 服务器，支持 SSL

### Support 自动配置

`SpringWebServletAutoConfiguration` 与 `SpringWebMvcSupportAutoConfiguration` 分别在 `spring-web-servlet` 与 `spring-web-mvc-support` 存在时自动装配对应模块组件。

### View 自动配置

`SpringWebViewAutoConfiguration` 在 `spring-web-view` 存在时自动装配：
- `ViewResolverRegistry`、`ViewReturnValueResolver`、`ModelAndViewReturnValueResolver`、`ModelArgumentResolverProvider`
- 引擎选择：`spring.web.view.engine`（默认 `thymeleaf`）+ classpath 探测 `TemplateEngine` / `freemarker.template.Configuration`

### Batch 自动配置

`SpringWebBatchAutoConfiguration` 在 `spring-web-batch` 存在时自动装配批量处理支持。

### Actuator 自动配置

`ActuatorEndpointAutoConfiguration` 在 Actuator 存在时自动配置端点：
- 将 `ExposableWebEndpoint` 注册为框架路由
- 支持独立管理端口（`management.server.port`）
- 独立的 Management Netty 服务器

### SBA 客户端自动配置

`SpringBootAdminClientAutoConfiguration` 在 SBA 客户端存在时自动发射 `WebServerInitializedEvent`，以支持 Spring Boot Admin 心跳注册。

### Metrics 自动配置

`MicrometerWebMetrics` 在 `spring-boot-starter-actuator` 存在时自动注册为 `WebMetrics` 实现，收集请求耗时、异常计数和线程池指标。详见[配置参考](configuration.md#可观测性指标)。

### OpenAPI 自动配置

`OpenApiAutoConfiguration` 在 `springdoc-openapi` 存在时自动生成 OpenAPI 文档端点。

### Swagger UI 自动配置

`SwaggerUiAutoConfiguration` 在 Swagger UI 存在时自动注册 Swagger UI 静态资源路由。

### 应用上下文

`WebServerApplicationContextFactory` 强制使用 `AnnotationConfigApplicationContext`，替代 Spring Boot 默认的 `AnnotationConfigServletWebServerApplicationContext`。

---

## spring-web-batch（批量请求处理）

透明聚合批量处理模块，将高并发同类型请求合并为一批，一次批量业务逻辑处理完所有请求，显著提升 IO 密集型场景的吞吐。

> 详细文档：[Batch 批处理模块](batch.md) — 包含完整示例、@BatchMapping 详解、可观测性指标、配置调优指南

### 核心思想

- **不改管线语义** — Filter、Interceptor 逐请求执行，不受影响
- **异步复用** — `BatchRequest<R>` 继承 `DeferredResult<R>`，利用框架已有的异步处理机制挂起/恢复请求
- **无主动攒批** — 不设时间窗口，消费者处理速度慢于生产时自然积压
- **无侵入** — 引入依赖后即可使用，无需改动现有代码结构

### 架构

```
请求 → EventLoop → BatchInvoker 构造实例入队 → RingBuffer
                                                │
                                          Disruptor 消费者
                                                │
                                          提交到业务线程池
                                                │
                                       BatchHandler 批量处理
                                                │
                     逐个 req.setResult() → asyncDispatch → 写响应
```

### 核心类

| 类 | 说明 |
|------|------|
| `BatchRequest<R>` | 继承 `DeferredResult<R>`，用户扩展的请求基类，通过构造参数位置匹配接收方法参数 |
| `@BatchMapping` | 标注在批量处理方法上，关联对应的单请求方法 |
| `BatchRegistry` | 管理所有 RingBuffer 的生命周期（初始化/销毁），安装 `BatchInvoker` |
| `BatchInvoker` | 替换原 Controller 方法调用，直接根据构造参数创建 `BatchRequest` 实例并入队 |
| `BatchRequestMetaData` | 存储单方法参数类型列表、批量方法引用、RingBuffer 配置等元数据 |

### 依赖

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-batch</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

添加依赖后，Spring Boot 自动配置会自动装配 `BatchRegistry`。

---

## spring-web-websocket（WebSocket 支持）

轻量 WebSocket 模块，基于 Spring WebSocket API + Netty，将 WebSocket 升级请求接入 Netty pipeline。

`WebSocketRoutingHandler` 作为 `ChannelInboundHandler` 注入 Netty pipeline，拦截 `Upgrade: websocket` 请求完成握手，随后将 pipeline 从 HTTP 模式切换为 WebSocket 帧模式。握手后创建 `NettyWebSocketSession`（实现 Spring 的 `WebSocketSession`），后续帧直接委托给用户定义的 `WebSocketHandler`。

支持路径模式匹配（含 `{pathVariable}`）、per-handler 覆盖的 Origin 校验/空闲超时/心跳保活、以及背压队列。

### 使用

实现 `WebSocketConfigurer` 接口注册端点：

```java
@Configuration
public class MyWsConfig implements WebSocketConfigurer {
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(myHandler(), "/ws/echo");
    }
}
```

### 依赖

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-websocket</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```