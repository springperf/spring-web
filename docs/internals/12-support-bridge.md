# 12 · spring-web-support：Servlet API 桥接与 WebMvcConfigurer 翻译中枢

> [← 返回索引](00-README.md) | 上一篇：[11 · 异步流式支持](11-async-streaming.md) | 下一篇：[13 · spring-web-batch 模块](13-batch-module.md)

---

## 引子：兼容层不牺牲性能

`spring-web-support` 是框架的兼容层。它的目标是：**让 Spring MVC 生态的组件（`WebMvcConfigurer`、`HandlerInterceptor`、`jakarta.servlet.Filter`、`@ControllerAdvice`、`HttpMessageConverter`、Session 等）不修改一行代码就能无缝工作在 Netty 容器上，同时不引入每请求的反射或类型推断。**

Spring MVC 的兼容层（如 Servlet 容器 + `spring-webmvc`）依赖的天然假设是"运行在 Servlet 容器中"——`HttpServletRequest`/`HttpServletResponse`、`FilterChain`、`Session` 都是容器提供的。本框架用 Netty 替代了 Tomcat/Jetty，所以 support 的职责是：

1. **重写 `org.springframework.web.servlet.*` 部分类**（纯接口/default 方法），消除对 `spring-webmvc` 的编译期依赖。
2. **`WebMvcConfigurerBridge`** 把 10 类 `WebMvcConfigurer` 回调翻译为框架内部 Registry 的注册动作。
3. **`PerfHttpServletRequest/Response`** 包装框架的 `WebServerHttpRequest/Response` 为 `HttpServletRequest`/`HttpServletResponse`。
4. **`FilterWrapper`/`HandlerInterceptorWrapper`** 把 Servlet Filter 和 Spring MVC Interceptor 适配为框架的 `WebFilter`/`HandlerInterceptor`。
5. **Session 体系** 从零实现 `HttpSession` 接口，通过 `HttpSessionStorage` SPI 可插拔存储。
6. **Servlet 规范完整桥接** 覆盖 `ServletContext`、`RequestDispatcher`（forward/include）、`AsyncContext`、认证（`Authenticator`）、WebSocket `upgrade` 等，让依赖 Servlet API 的代码无侵入运行。详见第五节 5.6~5.10。
7. **Servlet 对象路由与 JSP** 支持把 `jakarta.servlet.Servlet` 对象注册为框架路由（`ServletInvoker` / `SupportServletRegistry` / `ServletRequest(Response)Provider`），并集成 Apache Jasper 提供 JSP / JSTL 渲染（`JasperJspServlet` / `JspViewResolver` / `JspView`）。详见第五节 5.11~5.12。

> 完整的支持矩阵、配置项与已知限制，见 `docs/feature/servlet-spec-support.md`（该目录 gitignored，仅本地维护）。

---

## 一、"同包同名覆盖"策略

### 1.1 消除 `spring-webmvc` 依赖

`spring-web` 无法引用 `spring-webmvc`（会拉入 Tomcat 依赖）。但一些 Spring MVC 接口（如 `HandlerInterceptor`、`WebMvcConfigurer`、`CorsRegistry`）是纯接口或 default 方法，不依赖 Servlet 容器。support 在 `org.springframework.web.servlet.*` 包下重写了这些类：

| 重写类 | 原归属 | 策略 |
|--------|--------|------|
| `HandlerInterceptor` | `spring-webmvc` | 接口（`preHandle`/`postHandle`/`afterCompletion`） |
| `AsyncHandlerInterceptor` | `spring-webmvc` | 接口（extends `HandlerInterceptor` + `afterConcurrentHandlingStarted`） |
| `HandlerExceptionResolver` | `spring-webmvc` | 接口（`resolveException`） |
| `ModelAndView` | `spring-webmvc` | 简单 POJO |
| `View` | `spring-webmvc` | 接口 |
| `MappedInterceptor` | `spring-webmvc` | 接口（`matches`/`getInterceptor`/`getPathPatterns`） |
| `WebRequestHandlerInterceptorAdapter` | `spring-webmvc` | 适配器（将 `WebRequestInterceptor` 包装为 `HandlerInterceptor`） |
| `LocaleResolver` / `LocaleContextResolver` | `spring-webmvc` | 接口 |
| `NoHandlerFoundException` | `spring-webmvc` | `@ResponseStatus` 注解 |
| `WebMvcConfigurer` | `spring-webmvc` | 接口（19 个 default 方法，按需覆写） |
| `InterceptorRegistration` | `spring-webmvc` | 完整实现（`addPathPatterns`/`excludePathPatterns`/`order`/`pathMatcher`） |
| `InterceptorRegistry` | `spring-webmvc` | 完整实现（管理 `InterceptorRegistration` 列表） |
| `CorsRegistry` | `spring-webmvc` | 完整实现（`addMapping` → `CorsRegistration`） |
| `CorsRegistration` | `spring-webmvc` | 完整实现（`allowedOrigins`/`allowedMethods`/...） |
| `ResourceHandlerRegistry` / `ResourceHandlerRegistration` | `spring-webmvc` | 完整实现 |
| `PathMatchConfigurer` | `spring-webmvc` | 完整实现 |
| `AsyncSupportConfigurer` | `spring-webmvc` | 完整实现（`setTimeout`/`setTaskExecutor`/`registerCallableInterceptors`/...） |
| `ContentNegotiationConfigurer` | `spring-webmvc` | 完整实现 |
| `DefaultServletHandlerConfigurer` | `spring-webmvc` | 空实现 |
| `ViewControllerRegistry` | `spring-webmvc` | 空实现 |
| `ViewResolverRegistry` | `spring-webmvc` | 空实现 |
| `ValidatorRegistration` | `spring-webmvc` | 完整实现（`getValidator`/`setValidator`） |
| `RequestBodyAdvice` / `ResponseBodyAdvice` | `spring-webmvc` | 接口 |
| `ResponseBodyEmitter` / `SseEmitter` / `StreamingResponseBody` | `spring-webmvc` | 完整实现（extends 框架 `StreamEmitter`） |
| `ResponseEntityExceptionHandler` | `spring-webmvc` | `@ControllerAdvice` 15 个标准异常处理 |
| `AdapterUtil` | `spring-webmvc` | 工具类（`setEncodeFunction`/`getEncodeFunction`） |

**关键**：这些重写类在编译期与框架代码一起打包。当用户同时依赖 `spring-webmvc` 时，Maven 的类加载顺序决定谁先被加载——框架的 `spring-web-support` 中同包同名类优先于 `spring-webmvc` 中的类（因为 support 是直接依赖，`spring-webmvc` 可能被排除）。框架 starter 的 `autoconfigure` 通过 `spring.factories` 排除 `spring-webmvc` 的自动配置类。

---

## 二、`WebMvcConfigurerBridge`：翻译中枢

### 2.1 10 类回调翻译

`WebMvcConfigurerBridge`在 `initComponentPhase1`中扫描所有 `WebMvcConfigurer` Bean，依次调用 10 个 bridge 方法：

```java
// WebMvcConfigurerBridge.java
public void initComponentPhase1() {
    Map<String, WebMvcConfigurer> configurers = webContext.getCtx().getBeansOfType(WebMvcConfigurer.class);
    // 创建 Shim 收集器（Spring MVC 原版类型）
    InterceptorRegistry shimInterceptorRegistry = new InterceptorRegistry();
    CorsRegistry shimCorsRegistry = new CorsRegistry();
    ResourceHandlerRegistry shimResourceRegistry = new ResourceHandlerRegistry();

    // ① 收集：让 WebMvcConfigurer 写入 Shim 收集器
    for (WebMvcConfigurer configurer : configurers.values()) {
        configurer.addInterceptors(shimInterceptorRegistry);
        configurer.addCorsMappings(shimCorsRegistry);
        configurer.addResourceHandlers(shimResourceRegistry);
    }

    // ② 翻译：Shim → 框架 Registry
    bridgeInterceptors(shimInterceptorRegistry);
    bridgeCorsMappings(shimCorsRegistry);
    bridgeResourceHandlers(shimResourceRegistry);
    bridgeFormatters(configurers);
    bridgeAsyncSupport(configurers);
    bridgeArgumentResolvers(configurers);
    bridgeMessageConverters(configurers);
    bridgeReturnValueHandlers(configurers);
    bridgeHandlerExceptionResolvers(configurers);
    bridgeConfigureValidator(configurers);
}
```

### 2.2 Shim 收集 → 翻译

因为 `WebMvcConfigurer` 的回调方法参数类型是 Spring MVC 原版类型（如 `org.springframework.web.servlet.config.annotation.InterceptorRegistry`），不能直接传入框架的 `io.springperf.web.core.interceptor.InterceptorRegistry`。所以 bridge 在 `initComponentPhase1` 内用**两步策略**：

**第一步：收集**——创建 Spring MVC 原版的 Shim 收集器，调用 `WebMvcConfigurer` 的各个回调写入 Shim。
**第二步：翻译**——遍历 Shim 中的注册项，逐个转换为框架的 `InterceptorRegistration`/`CorsRegistration`/`ResourceHandlerRegistration` 并注册到框架 Registry。

### 2.3 `bridgeInterceptors`：拦截器翻译

`bridgeInterceptors`是翻译的典型示例：

```java
// WebMvcConfigurerBridge.java
protected void bridgeInterceptors(InterceptorRegistry shimRegistry) {
    List<InterceptorRegistration> shimRegistrations = shimRegistry.getRegistrations();
    for (InterceptorRegistration shimReg : shimRegistrations) {
        HandlerInterceptorWrapper wrapper = new HandlerInterceptorWrapper(shimReg.getInterceptor());
        InterceptorRegistration frameworkReg = new InterceptorRegistration(wrapper);
        for (String includePattern : shimReg.getIncludePatterns()) {
            frameworkReg.addPathPatterns(includePattern);
        }
        for (String excludePattern : shimReg.getExcludePatterns()) {
            frameworkReg.excludePathPatterns(excludePattern);
        }
        frameworkReg.order(shimReg.getOrder());
        if (shimReg.getPathMatcher() != null) {
            frameworkReg.pathMatcher(shimReg.getPathMatcher());
        }
        frameworkRegistry.registerWebComponent(frameworkReg);
    }
}
```

### 2.4 其他 Bridge 方法

| Bridge 方法 | 目标 Registry | 翻译方式 |
|------------|-------------|---------|
| `bridgeCorsMappings` | `CorsRegistry` | 逐字段拷贝（`allowedOrigins`/`allowedMethods`/`allowedHeaders`/`exposedHeaders`/`allowCredentials`/`maxAge`） |
| `bridgeResourceHandlers` | `ResourceHandlerRegistry` | 路径模式 + 资源位置 + `cachePeriod`/`cacheControl` |
| `bridgeFormatters` | `FormatterRegistry`（即 `ConversionService`） | 直接调用 `configurer.addFormatters(formatterRegistry)` |
| `bridgeAsyncSupport` | `AsyncSupportRegistry` | `timeout`/`taskExecutor`/`CallableProcessingInterceptor`/`DeferredResultProcessingInterceptor` |
| `bridgeArgumentResolvers` | `ArgumentResolverRegistry` | 包装为 `SpringHandlerMethodArgumentResolverProvider` |
| `bridgeMessageConverters` | `HttpBodyCodecRegistry` | `GenericHttpMessageConverter` → `WrappedHttpBodyConverter`，其他 → `AdaptedHttpBodyConverter` |
| `bridgeReturnValueHandlers` | `ReturnValueResolverRegistry` | `SpringHandlerMethodReturnValueHandlerAdapter` |
| `bridgeHandlerExceptionResolvers` | `ExceptionRegistry` | `SpringHandlerExceptionResolverAdapter` |
| `bridgeConfigureValidator` | `WebDataBinderRegistry` | `ValidatorRegistration` → `binderRegistry.setDefaultValidator()` |

---

## 三、Filter 体系：SupportWebFilterRegistry → FilterWrapper → PerfHttpServletFilterChain

### 3.1 `SupportWebFilterRegistry`：自动注册 Filter Bean

`SupportWebFilterRegistry`继承 `WebFilterRegistry`，在构造器中注册 `jakarta.servlet.Filter` 类型的自动发现：

```java
// SupportWebFilterRegistry.java
public class SupportWebFilterRegistry extends WebFilterRegistry {
    public SupportWebFilterRegistry(DispatcherHandler dispatcherHandler) {
        super(dispatcherHandler);
        autoRegisterWebComponent(jakarta.servlet.Filter.class, filter -> wrapFilterToRegistration(new FilterWrapper(filter)));
    }
}
```

`autoRegisterWebComponent` 扫描 Spring 容器中所有 `jakarta.servlet.Filter` 类型的 Bean，对每个 Filter 创建 `FilterWrapper` → 包装为 `WebFilterRegistration` → 注册到 `WebFilterRegistry`。

### 3.2 `FilterWrapper`：实例级唯一标识

`FilterWrapper`实现 `WebFilter`，将 `jakarta.servlet.Filter` 适配为框架的 Filter 接口：

```java
// FilterWrapper.java
public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) throws Exception {
    doFilterInternal(request, response, chain);
}

protected void doFilterInternal(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) throws Exception {
    ServletAdapterContext adapterContext = ServletAttribute.getAdapterContext(request.getRequestContext());
    if (adapterContext == null) {
        adapterContext = createServletAdapterContext(request, response, chain);
        ServletAttribute.setAdapterContext(request.getRequestContext(), adapterContext);
    } else if (adapterContext.getFilterChain() == null) {
        adapterContext.setFilterChain(new PerfHttpServletFilterChain(request, response, chain));
    }
    adapterContext.rebindFrameworkRequest(request);
    adapterContext.rebindFrameworkResponse(response);
    filter.doFilter(adapterContext.getRequest(), adapterContext.getResponse(), adapterContext.getFilterChain());
}
```

**`getComponentName()` 实例级唯一标识**是 `FilterWrapper` 的关键设计：

```java
// FilterWrapper.java
private static final Map<jakarta.servlet.Filter, String> COMPONENT_NAMES =
        Collections.synchronizedMap(new IdentityHashMap<>());
private static final AtomicLong NEXT_INSTANCE_ID = new AtomicLong(1);

public String getComponentName() {
    return COMPONENT_NAMES.computeIfAbsent(filter, f ->
            f.getClass().getName() + "@" + NEXT_INSTANCE_ID.getAndIncrement());
}
```

注释记录了设计决策：`WebComponentContainer` 按 `getComponentName()` 去重。同类不同实例（不同 bean 名/order/urlPattern，如 Spring Security 同 filter 类的多实例）不得被按类名去重误杀。使用 `IdentityHashMap` 按实例身份分配唯一递增 ID——同实例同一 ID（去重语义不变），不同实例绝不碰撞。修复前用 `System.identityHashCode`，两个不同实例可能碰撞同一 hash，容器误判为同实例而静默销毁低 order 者（安全过滤器被丢 = 静默安全回归）。

### 3.3 Filter 生命周期与 `FilterConfig`

`FilterWrapper` 实现 `LifecycleWebComponent`，补齐 `jakarta.servlet.Filter` 的完整生命周期：

- **`init(FilterConfig)`**：`initWithWebContext()` 中调用，`FilterConfig` 由 `PerfFilterConfig` 提供，init-param 从 `@WebFilter(initParams=@WebInitParam(...))` 注解读取（`resolveInitParams()`）。
- **`destroy()`**：`destroyComponent()` 中调用。
- **`PerfFilterConfig`**：实现 `getFilterName()`（组件名）/`getServletContext()`/`getInitParameter()`/`getInitParameterNames()`。

### 3.4 `PerfHttpServletFilterChain`：FilterChain 桥接

`PerfHttpServletFilterChain`实现 `jakarta.servlet.FilterChain`，在 `doFilter` 尾端回调框架的 `FilterChain`：

```java
// PerfHttpServletFilterChain.java
public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
    RequestContext requestContext = request.getRequestContext();
    if (requestContext != null) {
        ServletAdapterContext adapterContext = ServletAttribute.getAdapterContext(requestContext);
        if (adapterContext != null) {
            if (servletRequest != adapterContext.getRequest()) {
                adapterContext.setRequest((HttpServletRequest) servletRequest);  // 更新 servlet 包装
            }
            if (servletResponse != adapterContext.getResponse()) {
                adapterContext.setResponse((HttpServletResponse) servletResponse);
            }
        }
    }
    filterChain.doFilter(request, response);  // 回调框架的 FilterChain
}
```

当 Servlet Filter 包装了 `HttpServletRequest`/`HttpServletResponse` 时，`PerfHttpServletFilterChain` 更新 `ServletAdapterContext` 中的引用，使后续的 Filter 和框架代码获取到正确的包装对象。

---

## 四、拦截器体系：SupportInterceptorRegistry → HandlerInterceptorWrapper

### 4.1 `SupportInterceptorRegistry`：双类型扫描

`SupportInterceptorRegistry`扩展 `InterceptorRegistry`，自动注册两类型：

```java
// SupportInterceptorRegistry.java
public SupportInterceptorRegistry() {
    super();
    autoRegisterWebComponent(InterceptorRegistration.class, this::convert);
    autoRegisterWebComponent(HandlerInterceptor.class, this::convert);
}
```

`convert(HandlerInterceptor)`处理两种子类型：

| 子类型 | 处理方式 |
|--------|---------|
| `MappedInterceptor` | 提取路径模式（`includePatterns`/`excludePatterns`）+ `pathMatcher` |
| 普通 `HandlerInterceptor` | 无路径限制 |

### 4.2 `HandlerInterceptorWrapper`：四阶段适配

`HandlerInterceptorWrapper`将 Spring MVC 的 `HandlerInterceptor` 适配为框架的 `HandlerInterceptor`：

```java
// HandlerInterceptorWrapper.java
public boolean preHandle(WebServerHttpRequest request, WebServerHttpResponse response, Object handler) throws Exception {
    HttpServletRequest servletRequest = extractRequest(request);
    HttpServletResponse servletResponse = extractResponse(request);
    return interceptor.preHandle(servletRequest, servletResponse, handler);
}

public void postHandle(WebServerHttpRequest request, WebServerHttpResponse response, Object handler, Object result) throws Exception {
    interceptor.postHandle(servletRequest, servletResponse, handler, null);  // 传 null modelAndView
}

public void afterCompletion(WebServerHttpRequest request, WebServerHttpResponse response, Object handler, Throwable ex) throws Exception {
    interceptor.afterCompletion(servletRequest, servletResponse, handler,
            ex instanceof Exception ? (Exception) ex : new NestedServletException("Handler dispatch failed", ex));
}

public void afterConcurrentHandlingStarted(WebServerHttpRequest request, ...) throws Exception {
    if (interceptor instanceof AsyncHandlerInterceptor) {
        ((AsyncHandlerInterceptor) interceptor).afterConcurrentHandlingStarted(servletRequest, servletResponse, handler);
    }
}
```

关键适配点：
- **`postHandle` 传 null `modelAndView`**：框架没有 `ModelAndView` 概念，`postHandle` 的 `modelAndView` 参数传 null。
- **`afterCompletion` 的 `Throwable` → `Exception` 包裹**：Spring MVC 的 `afterCompletion` 签名是 `Exception`，框架的 `Throwable` 需要包裹为 `NestedServletException`。
- **`extractRequest`/`extractResponse` 双路径**：优先从 `ServletAttribute`（RequestContext 的 fastAttributes 数组）获取，兜底从 `RequestContextHolder` 获取。

---

## 五、`HttpServletRequest`/`HttpServletResponse`：`PerfHttpServletRequest`/`Response`

### 5.1 `AbstractFastFailHttpServletRequest`：基类 fail-fast

`AbstractFastFailHttpServletRequest`是抽象基类，实现 `HttpServletRequest` 的 80+ 个方法。**基类默认行为是 fail-fast**（`throw unsupported` 或返回 null/-1/空集合），但所有关键方法均由 `PerfHttpServletRequest`/`NettyHttpServletRequest` 覆盖实现。基类的 fail-fast 只兜底"直接持有基类引用"的边缘场景。

> 注：`AbstractFastFailHttpServletRequest` 提供 `requestId`（`AtomicLong` 递增）、`getServletConnection()` stub、`getProtocolRequestId()`，与 `PerfHttpServletRequest` 的 `getDispatcherType()/getInputStream()/getReader()` 互斥标志配合，构成完整的 Servlet API 层。

### 5.2 `PerfHttpServletRequest`：委托实现

`PerfHttpServletRequest`继承 `AbstractFastFailHttpServletRequest`，覆盖关键方法委托给框架的 `WebServerHttpRequest`。**额外实现的规范细节**：

| 能力 | 实现 |
|------|------|
| `getRequestURL()` | 从 scheme + host + port + URI 构造（HTTP 场景） |
| `getInputStream()/getReader()` 互斥 | `volatile` 标志位，第二次调用抛 `IllegalStateException`（`NettyServletInputStream`） |
| `getServletContext()` | 从 `WebContext` 的 WebComponent 获取 |
| `getRequestDispatcher()` | 返回 `PerfRequestDispatcher` |
| `getParts()/getPart()` | 委托 `request.getPartMap()`，包装为 `ServletPartAdapter` |
| `upgrade()` | 见 5.10 WebSocket 升级 |
| `startAsync()`/`getAsyncContext()` 等 | 见 5.8 AsyncContext |
| `login()/logout()/authenticate()` | 见 5.9 认证与安全 |
| `getDispatcherType()` | 可设置字段（`forward` 时 `FORWARD`、`include` 时 `INCLUDE`） |
| `getServletPath()` / `getPathInfo()` | `getServletPath` 返回应用内路径（`request.getPath()`）；`getPathInfo` 返回空字符串（路由整路径匹配，无剩余路径）。这是 Jasper 解析 `jsp:include` / `jsp:forward` 相对路径的前提 |

**`NettyHttpServletRequest extends PerfHttpServletRequest`**：在 Netty 环境覆盖网络/安全方法——`getRemoteAddr/getRemoteHost/getRemotePort/getLocalAddr/getLocalName/getLocalPort`（真实 `InetSocketAddress`）、`getScheme()/isSecure()`（从 URI 检测 HTTPS）、`getRequestURL()`（完整 URL）。由 `ServletAttribute.createPerfRequest()` 工厂方法根据请求类型选择。

**`getDelegateRequest()`/`getDelegateResponse()`**：供子类和 dispatch 逻辑访问底层委托对象。

`rebind()` 是关键——当 `WebFilter` 包装了请求后，调用此方法使 `PerfHttpServletRequest` 指向包装后的请求，而非创建新实例。

**Cookie 解析**：使用 Netty 的 `ServerCookieDecoder.STRICT.decode(cookieHeader)` 解码，缓存到 `volatile Cookie[] cookies`。

**Session 管理**：`getSession(boolean create)` 三阶段：
1. 检查 `RequestContext` 中缓存的 `PerfHttpSession`。
2. 尝试从 `getRequestedSessionId()` 获取已有 session。
3. `create=true` 时创建新 session → `PerfHttpSessionManager.createSession()` → `setSessionCookie`。

`setSessionCookie`配置 Cookie 安全属性：
```java
// PerfHttpServletRequest.java
Cookie sessionCookie = new Cookie(manager.getCookieName(), session.getId());
sessionCookie.setPath(manager.getCookiePath());
sessionCookie.setHttpOnly(true);
boolean secure = manager.isCookieSecure();
if (!secure) {
    String forwardedProto = request.getHeaders().getFirst("X-Forwarded-Proto");
    secure = "https".equalsIgnoreCase(forwardedProto);
}
sessionCookie.setSecure(secure);
if (manager.getSameSite() != null && resp instanceof PerfHttpServletResponse) {
    ((PerfHttpServletResponse) resp).setSameSite(manager.getSameSite());
}
```

### 5.3 `PerfHttpServletResponse`：委托实现

`PerfHttpServletResponse`继承 `AbstractFastFailHttpServletResponse`，覆盖关键方法委托给框架的 `WebServerHttpResponse`。**额外实现的规范细节**：

| 能力 | 实现 |
|------|------|
| `getOutputStream()/getWriter()` 互斥 | `volatile` 标志位，第二次调用抛 `IllegalStateException` |
| `setContentType()` 提取 charset | 正则解析 `charset=xxx` 自动调用 `setCharacterEncoding()` |
| `sendRedirect()` | 检查 `isCommitted` + 构造**绝对 URL**（scheme+host+port+contextPath） |
| `sendError()` | 检查 `isCommitted`，委托框架 JSON 错误响应 |
| `reset()` | 清除 headers + 恢复 status 200 + 清空 buffer |
| `setContentLength/setContentLengthLong` | 设置 `Content-Length` 头 |
| `setLocale()/getLocale()` | 设置 `Content-Language` 头 |
| `isCommitted()/containsHeader()` | 委托 `WebServerHttpResponse` |
| `setDateHeader/addDateHeader/setIntHeader/addIntHeader` | 格式化后设置头 |
| `encodeURL/encodeRedirectURL` | session URL 重写（cookie 跟踪下恒不重写） |

**Cookie 编码**：使用 Netty 的 `ServerCookieEncoder.STRICT.encode(nettyCookie)`，设置 SameSite 属性：

```java
// PerfHttpServletResponse.java
public void addCookie(Cookie cookie) {
    DefaultCookie nettyCookie = new DefaultCookie(cookie.getName(), cookie.getValue());
    nettyCookie.setSecure(cookie.getSecure());
    nettyCookie.setHttpOnly(cookie.isHttpOnly());
    if (sameSite != null) {
        nettyCookie.setSameSite(CookieHeaderNames.SameSite.valueOf(sameSite));
    }
    response.getHeaders().add(HttpHeaders.Names.SET_COOKIE, ServerCookieEncoder.STRICT.encode(nettyCookie));
}
```

> 简要验证写法：`PerfHttpServletResponseTest` 覆盖 50 个方法、`PerfHttpServletRequestTest` 覆盖 70+ 方法。

### 5.4 `ServletAttribute`：类型安全访问器

`ServletAttribute`通过 `RequestAttribute` 在 `RequestContext` 的 `fastAttributes` 数组中存取 `ServletAdapterContext`，避免 `ConcurrentHashMap` 查找和 `ThreadLocal` 操作：

```java
// ServletAttribute.java
private static final RequestAttribute<ServletAdapterContext> ADAPTER_CTX =
        RequestAttribute.createAttribute(ServletAdapterContext.class);
```

`getAdapterContext(WebServerHttpRequest, WebServerHttpResponse)`不存在时创建并存入，已存在时调用 `rebindFrameworkRequest`/`rebindFrameworkResponse` 更新委托引用。

### 5.5 `ServletAdapterContext`：包装上下文

`ServletAdapterContext`持有 `PerfHttpServletRequest`、`PerfHttpServletResponse`、当前生效的 `HttpServletRequest`/`HttpServletResponse`（可能被 Servlet Filter 包装）、`FilterChain`。`rebindFrameworkRequest`/`rebindFrameworkResponse` 更新底层的 `PerfHttpServletRequest`/`PerfHttpServletResponse` 的委托引用，使包装后的请求/响应生效。

### 5.6 `ServletContext`：`PerfServletContext`

`PerfServletContext implements ServletContext, WebComponent`，注册为 `WebContext` 的 WebComponent：

- **启动注册**：`PerfHttpSessionManager.initWithWebContext()` 创建并 `webContext.registerWebComponent()` 注册。
- **获取方式**：`webContext.getWebComponent(PerfServletContext.class)` 直接获取，`request.getServletContext()` 优先走此路径。
- **核心能力**：
  - `getMimeType()` 内置 50+ 种扩展名映射（静态 Map）。
  - `getResource()/getResourceAsStream()` classpath 查找（`META-INF/resources/` → `static/` → `public/` → 根 classpath）。
  - `getInitParameter()` 从 `WebContext` 配置读取；`getSessionCookieConfig()` 返回可配置的 `SessionCookieConfig` 实现。
  - `getRequestDispatcher()/getNamedDispatcher()` 返回 `PerfRequestDispatcher`。
  - `TEMPDIR` 属性：构造时创建应用级临时目录（`java.io.tmpdir` 下），供 JSP 编译（Jasper scratchdir）等容器能力使用。
- **限制**：动态注册（`addServlet`/`addFilter`）返回 null、`getContext()` 返回 null（单上下文架构）。

### 5.7 `RequestDispatcher`：forward / include

`PerfRequestDispatcher implements RequestDispatcher`，配合 `SupportDispatcherHandler.forward()/include()`：

- **`forward()`**：检查 `isCommitted`（已提交抛 `IllegalStateException`）→ 设置 `DispatcherType.FORWARD` → 清除 response（headers+status+buffer）→ 通过 `ForwardWebServerHttpRequest` 包装请求（覆盖 `getPath()/getUriStr()/getURI()`，保留/替换 query string）→ `mappingRegistry.mapping()` 重映射 → `handleAfterFilter()` 重新 dispatch（**跳过 filter 链**，与 Tomcat 一致）。
- **`include()`**：设置并恢复 `DispatcherType.INCLUDE` → `IncludeResponseWrapper` 阻止 flush、隔离 status/header（`setStatusCode`/`sendError` 空操作，`getHeaders()` 返回独立实例）。`INCLUDE_SERVLET_PATH` 设置为 include 目标路径（供 Jasper 定位被包含 JSP）；`INCLUDE_PATH_INFO` 为空字符串（底层 `ConcurrentHashMap` 不允许 null value）。
- **context holders 保护**：forward/include 均在 `try/finally` 中保存恢复 `LocaleContextHolder`/`RequestContextHolder`，避免与原始请求双重初始化冲突。
- **Filter 包装兼容**：`resolveWebRequest()/resolveWebResponse()` 解开 `HttpServletRequestWrapper`/`HttpServletResponseWrapper` 找到底层请求。

### 5.8 `AsyncContext`：`PerfAsyncContext`

`PerfAsyncContext`包装框架的 `PerfAsyncWebRequest`（异步状态机 `NEW → ASYNC_STARTED → DISPATCHED → COMPLETED`）：

- **startAsync**：`PerfHttpServletRequest.startAsync()` 创建并缓存到 `RequestContext`（`RequestAttribute`），同一请求内复用。
- **dispatch()**：委托 `asyncWebRequest.dispatch()`；**dispatch(path)** 通过 `SupportDispatcherHandler.forward()` 分派到指定路径。
- **complete()**：委托 `asyncWebRequest.complete()`，触发 `onComplete`。
- **监听器回调**：`CopyOnWriteArrayList` 线程安全。`onComplete`/`onTimeout`/`onError` 均触发。**懒注册**——仅当 `addListener()` 或 `setTimeout()` 时通过 `ensureHandlersRegistered()` 桥接底层 timeout/error handler，避免覆盖框架 Callable/DeferredResult 路径的 handler。
- **setTimeout()**：委托并调用 `scheduleTimeoutIfNeeded()`。

### 5.9 认证与安全

- **`Authenticator`**（`@FunctionalInterface`）：`Principal authenticate(username, password)`。用户注入 Spring Bean 实现，`login()` 依赖它。
- **`PerfHttpPrincipal implements Principal`**：持有 `name` + `Set<String> roles`（不可变）。
- **`login()`/`logout()`**：`login` 通过 `Authenticator` 认证成功后把 `PerfHttpPrincipal` 存入 `HttpSession`（`PRINCIPAL_KEY`）；`logout` 移除。
- **`getUserPrincipal()/getRemoteUser()/isUserInRole()`**：从 session 读取 principal。
- **`authenticate()`**：已认证返回 true；否则发送 401 + `WWW-Authenticate: Basic`。
- **限定**：认证是 session 级、由上层安全框架（Spring Security/Shiro）负责实际拦截，桥接层只提供 Servlet API 语义。

### 5.10 WebSocket 升级

- **`upgrade(Class<T extends HttpUpgradeHandler>)`**：发送 101 + `Upgrade: websocket` 头 → 创建 handler + `PerfWebConnection`（包装请求输入流/响应输出流）→ 新线程运行 `handler.init()`。
- **`PerfWebConnection implements WebConnection`**：`getInputStream()/getOutputStream()/close()`。
- **推荐使用 `spring-web-websocket` 模块**：该项目在 Netty pipeline 层拦截 `Upgrade: websocket`，完整支持 Spring `WebSocketHandler`。`upgrade()` 仅适用于 servlet 层自定义协议升级，不切换 Netty 编解码器。

### 5.11 Servlet 对象路由

支持把 `jakarta.servlet.Servlet` 对象注册为框架路由（第二个消费者是 JSP 的 `JasperJspServlet`）：

| 组件 | 职责 |
|------|------|
| `ServletInvoker` | 实现 `CustomInvoker`，`getHandleMethod()` 返回 `Servlet.service`（返回 `void`）——框架的 `ReturnValueResolverRegistry.skipResolve` 对 void 方法自动 `setHandled()`，servlet 直接写入的响应体可正常 flush |
| `SupportServletRegistry` | 扫描 Spring 中 `Servlet` Bean，读 `@WebServlet` 的 urlPatterns（servlet 语义 → ant 路径，如 `*.jsp` → `/**/*.jsp`），`init()` 后包装为 `PathMappingContext` 注册 |
| `ServletRequestProvider` / `ServletResponseProvider` | 解析 `Servlet.service(ServletRequest, ServletResponse)` 参数（精确匹配父接口，与 `HttpServletRequest(Response)Provider` 精确匹配子接口互补，无冲突） |
| `PerfServletConfig` | 提供 `ServletConfig`（仿 `PerfFilterConfig`） |

`ServletInvoker.invoke()` 在 `service()` 返回后调用 `PerfHttpServletResponse.flushBuffer()`——其会先 flush `cachedWriter`（`PrintWriter` 编码缓冲）再 flush 底层 body，模拟容器在 handler 返回时的自动提交。

### 5.12 JSP 视图（Apache Jasper）

非 Tomcat 容器不会自动运行 `JasperInitializer`（ServletContainerInitializer），`JasperJspServlet.init()` 补齐三项容器职责：

| 职责 | 实现 |
|------|------|
| `JspFactory` | 显式 `setDefaultFactory(new JspFactoryImpl())` |
| `InstanceManager` | `ServletContext` 设置 `SimpleInstanceManager` |
| `TldCache` | `TldScanner` 扫描 classpath `META-INF/*.tld` → 设置到 `ServletContext` attribute（JSTL / taglib 解析，缺失时 `Options.getTldCache()` 为 null 抛 NPE） |

视图接入复用 `spring-web-view` 的 SPI：

```java
// JspViewResolver（extends BaseWebComponent implements ViewResolver）
resolveViewName("jsp:hello") → new JspView("/jsp/hello.jsp")   // jsp: 前缀 / .jsp 后缀
// JspView.render(model, req, resp)
//   model → request attribute（JSP EL 经 request.getAttribute 访问）
//   → request.getRequestDispatcher("/jsp/hello.jsp").forward(...)
//   → PerfRequestDispatcher → 重新 mapping → 命中 /**/*.jsp 路由 → Jasper 渲染
```

`JspViewAutoConfiguration` 用类级 `@ConditionalOnClass(name = {"org.apache.jasper.servlet.JspServlet", "io.springperf.web.view.View"})` 保证条件不满足时整个配置类跳过、不加载 `JspViewResolver`（其类签名依赖 view 接口，避免无 view 环境 introspect 失败）。

---

## 六、Session 体系

### 6.1 `HttpSessionStorage` SPI

`HttpSessionStorage`是 session 存储的 SPI 接口：

```java
// HttpSessionStorage.java
public interface HttpSessionStorage {
    HttpSessionData getSession(String sessionId);
    HttpSessionData createSession();
    void saveSession(HttpSessionData session);
    void removeSession(String sessionId);
    default void shutdown() {}
}
```

### 6.2 `InMemoryHttpSessionStorage`：默认实现

`InMemoryHttpSessionStorage`使用 `ConcurrentHashMap` 存储，daemon 线程定期清理过期 session：

```java
// InMemoryHttpSessionStorage.java
public InMemoryHttpSessionStorage() {
    this.cleanupThread = new Thread(this::cleanupLoop, "session-cleanup");
    this.cleanupThread.setDaemon(true);
    this.cleanupThread.start();
}

// InMemoryHttpSessionStorage.java
private void cleanupLoop() {
    while (!Thread.currentThread().isInterrupted()) {
        Thread.sleep(CLEANUP_INTERVAL_MS);  // 60s
        sessions.values().removeIf(s -> s.isExpired(now));
    }
}
```

`getSession()`在返回前检查过期：
```java
// InMemoryHttpSessionStorage.java
public HttpSessionData getSession(String sessionId) {
    HttpSessionData session = sessions.get(sessionId);
    if (session != null && session.isExpired(System.currentTimeMillis())) {
        sessions.remove(sessionId, session);
        return null;
    }
    return session;
}
```

### 6.3 `HttpSessionData`：数据容器

`HttpSessionData`是 session 数据的纯 POJO，包含 `id`、`creationTime`、`lastAccessedTime`、`maxInactiveInterval`、`attributes`（`ConcurrentHashMap`）。`isExpired`检查 `maxInactiveInterval > 0 && now - lastAccessedTime > maxInactiveInterval * 1000L`。

### 6.4 `PerfHttpSessionManager`：管理器

`PerfHttpSessionManager`是 session 生命周期管理器，继承 `BaseWebComponent`：

| 职责 | 方法 | 说明 |
|------|------|------|
| 获取 session | `getSession(sessionId)` | 从 `storage.getSession()` 获取，包装为 `PerfHttpSession`，设置 `onInvalidateCallback` 为 `storage.removeSession()` |
| 创建 session | `createSession()` | `storage.createSession()` → 包装为 `PerfHttpSession` → 触发 `HttpSessionListener.sessionCreated` |
| 保存 session | `saveSession(session)` | `storage.saveSession(session.getData())` |
| 变更 session ID | `changeSessionId(oldSession)` | 创建新 `HttpSessionData`（新 ID + 旧属性），移除旧 session |
| Cookie 配置 | `cookieName`/`cookiePath`/`sameSite`/`cookieSecure` | 从 `server.servlet.session.cookie.*` 配置读取 |

### 6.5 `PerfHttpSession`：`HttpSession` 实现

`PerfHttpSession`完整实现 `HttpSession` 接口，包含 `HttpSessionListener`/`HttpSessionAttributeListener` 事件触发。**额外实现**：

- **`HttpSessionBindingListener` 支持**：`setAttribute`（替换时对旧值 `valueUnbound`、对新值 `valueBound`）、`removeAttribute`（`valueUnbound`）、`invalidate`（遍历所有属性 `valueUnbound`）。
- **`invalidate()`**：设置 `invalid=true`、遍历属性触发 `valueUnbound`、触发 `onInvalidateCallback`（`storage.removeSession`）、触发 `sessionDestroyed` 事件。

`PerfServletContext`（原 `MinimalServletContext` 从 `PerfHttpSession` 内部类迁移出来）是实现 `ServletContext` + `WebComponent` 的独立类，注册到 `WebContext`，供 `getServletContext()` 与 `HttpSession.getServletContext()` 共用。详见 5.6 ServletContext。

---

## 七、Codec 拦截器桥接

### 7.1 `SupportHttpBodyCodecInterceptorRegistry`

`SupportHttpBodyCodecInterceptorRegistry`扩展 `HttpBodyCodecInterceptorRegistry`，扫描 `@ControllerAdvice` 中的 `RequestBodyAdvice` 和 `ResponseBodyAdvice`：

```java
// SupportHttpBodyCodecInterceptorRegistry.java
protected void initCodecInterceptors() {
    super.initCodecInterceptors();
    List<ControllerAdviceBean> adviceBeans1 = getControllerAdviceBean(RequestBodyAdvice.class);
    for (ControllerAdviceBean adviceBean : adviceBeans1) {
        registerWebComponent(new WebComponentControllerAdviceBean<>(adviceBean,
            new RequestBodyAdviceCodecInterceptor((RequestBodyAdvice) adviceBean.resolveBean())));
    }
    // 同上处理 ResponseBodyAdvice
}
```

### 7.2 `RequestBodyAdviceCodecInterceptor` / `ResponseBodyAdviceCodecInterceptor`

这两个拦截器分别适配 Spring MVC 的 `RequestBodyAdvice`（读侧）和 `ResponseBodyAdvice`（写侧）为框架的 `HttpBodyCodecInterceptor`。在 `beforeBodyRead`/`afterBodyRead`/`beforeBodyWrite` 三个切入点调用 Spring 的 `supports` → `handle` 方法。

---

## 八、参数/返回值/异常解析器桥接

### 8.1 `SpringHandlerMethodArgumentResolverProvider`

`SpringHandlerMethodArgumentResolverProvider`将 Spring MVC 的 `HandlerMethodArgumentResolver` 适配为框架的 `StaticArgumentResolverProvider`。

```java
// SpringHandlerMethodArgumentResolverProvider.java
public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
    return resolver.supportsParameter(parameter);  // 启动期决策
}

public StaticArgumentResolver getResolver(MethodParameter parameter, ...) {
    return new SpringHandlerMethodArgumentResolver(parameter);
}
```

**内部类 `SpringHandlerMethodArgumentResolver`**在运行时解析参数：

```java
// SpringHandlerMethodArgumentResolverProvider.java
public Object resolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
    HttpServletRequest servletRequest = ServletAttribute.getRequest(request.getRequestContext());
    if (servletRequest == null) {
        servletRequest = new PerfHttpServletRequest(request);
        servletResponse = new PerfHttpServletResponse(response);
    }
    NativeWebRequest webRequest = new ServletWebRequest(servletRequest, servletResponse);
    return resolver.resolveArgument(methodParameter, null, webRequest, null);
}
```

### 8.2 `SpringHandlerMethodReturnValueHandlerAdapter`

`SpringHandlerMethodReturnValueHandlerAdapter` 将 Spring MVC 的 `HandlerMethodReturnValueHandler` 适配为框架的 `ReturnValueResolver`。`supportsReturnType` 在启动期调用 `handler.supportsReturnType(returnType)` 决策。

### 8.3 `SpringHandlerExceptionResolverAdapter`

`SpringHandlerExceptionResolverAdapter` 将 Spring MVC 的 `HandlerExceptionResolver` 适配为框架的 `HandlerExceptionResolver`。`resolveException` 在运行时调用 `resolver.resolveException(request, response, handler, ex)`。

---

## 九、`ResponseBodyEmitterReturnValueResolver`：SSE 桥接

`ResponseBodyEmitterReturnValueResolver`继承框架的 `StreamEmitterReturnValueResolver`，实现 `LifecycleWebComponent`，增加 `HttpMessageConverter` 兜底：

```java
// ResponseBodyEmitterReturnValueResolver.java
protected void preInitializeEmitter(StreamEmitter emitter, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
    super.preInitializeEmitter(emitter, req, resp);
    if (emitter instanceof ResponseBodyEmitter) {
        HttpHeaders mutableHeaders = new HttpHeaders(resp.getHeaders());
        AdapterUtil.setEncodeFunction(responseBodyEmitter, (data, out) -> encodeToStream(mutableHeaders, data, out));
    }
}
```

`encodeToStream`是编码兜底逻辑：

```java
// ResponseBodyEmitterReturnValueResolver.java
protected void encodeToStream(HttpHeaders mutableHeaders, Object data, OutputStream out) throws IOException {
    // ① 尝试 HttpBodyCodecRegistry 中的 converter
    for (HttpBodyConverter converter : codecRegistry.getConverters()) {
        if (converter.canWrite(null, data.getClass(), selectedMediaType)) {
            converter.write(data, null, selectedMediaType, outputMessage);
            return;
        }
    }
    // ② 兜底：String → UTF-8 byte[]
    if (data instanceof String) { out.write(((String) data).getBytes(StandardCharsets.UTF_8)); return; }
    // ③ 兜底：byte[] 直写
    if (data instanceof byte[]) { out.write((byte[]) data); return; }
    throw new IllegalArgumentException("No suitable converter for " + data.getClass());
}
```

`StreamingHttpOutputMessage`内部类实现 `HttpOutputMessage`，将 `OutputStream` 包装为 `HttpOutputMessage` 用于 `HttpBodyConverter.write()`。

---

## 十、`SupportDispatcherHandler`：扩展分发器

`SupportDispatcherHandler`扩展 `DispatcherHandler`，增加 `RequestContextHolder` 管理和 Session 持久化：

```java
// SupportDispatcherHandler.java
protected boolean initContextHolders(WebServerHttpRequest req, WebServerHttpResponse resp) {
    boolean init = super.initContextHolders(req, resp);
    ServletRequestAttributes requestAttributes = buildRequestAttributes(req, resp);
    RequestContextHolder.setRequestAttributes(requestAttributes, this.threadContextInheritable);
    resp.addWriteRespEventListener(new SessionFlushListener(req));  // 注册 session 持久化
    return init || requestAttributes != null;
}
```

`SessionFlushListener`实现 `WriteRespEventListener`，在响应写入完成/失败时持久化 session：

```java
// SupportDispatcherHandler.java
private void flushSession() {
    PerfHttpSession session = request.getRequestContext()
            .getAttribute(PerfHttpSessionManager.SESSION_ATTR_KEY);
    if (session == null || session.isInvalid()) return;
    PerfHttpSessionManager manager = request.getWebContext()
            .getWebComponent(PerfHttpSessionManager.class);
    if (manager != null) {
        session.markAccessed();
        manager.saveSession(session);
    }
}
```

通过 `WriteRespEventListener` 接入 Netty 的 `ChannelFuture` 回调，确保在同步/异步/流式场景下均在正确的生命周期点执行 session 持久化。

---

## 十一、`ResponseEntityExceptionHandler`：标准异常处理

`ResponseEntityExceptionHandler` 是 `@ControllerAdvice` 类，通过单个 `@ExceptionHandler` 入口方法（`handleException`）分发到 15 个标准异常处理，覆盖 Spring MVC 的常见异常：

| 异常 | HTTP 状态码 |
|------|------------|
| `HttpRequestMethodNotSupportedException` | 405 Method Not Allowed |
| `HttpMediaTypeNotSupportedException` | 415 Unsupported Media Type |
| `HttpMediaTypeNotAcceptableException` | 406 Not Acceptable |
| `MissingPathVariableException` | 500 Internal Server Error |
| `MissingServletRequestParameterException` | 400 Bad Request |
| `MissingServletRequestPartException` | 400 Bad Request |
| `ServletRequestBindingException` | 400 Bad Request |
| `MethodArgumentNotValidException` | 400 Bad Request |
| `BindException` | 400 Bad Request |
| `NoHandlerFoundException` | 404 Not Found |
| `AsyncRequestTimeoutException` | 503 Service Unavailable |
| `ConversionNotSupportedException` | 500 Internal Server Error |
| `TypeMismatchException` | 400 Bad Request |
| `HttpMessageNotReadableException` | 400 Bad Request |
| `HttpMessageNotWritableException` | 500 Internal Server Error |

---

## 十二、对比 Spring MVC 兼容层

| 维度 | 本框架 support | Spring MVC |
|------|---------------|-----------|
| 容器 | Netty 4.1 | Tomcat/Jetty/Undertow |
| 请求/响应适配 | `PerfHttpServletRequest`/`Response` 包装 `WebServerHttpRequest`/`Response` | Servlet 容器原生提供 |
| Filter 桥接 | `FilterWrapper` → `WebFilter` 适配 | Servlet 原生 `FilterChain` |
| 拦截器桥接 | `HandlerInterceptorWrapper` → `HandlerInterceptor` 适配 | 原生 `HandlerInterceptor` |
| WebMvcConfigurer | `WebMvcConfigurerBridge` 10 类回调翻译 | 原生注册 |
| Session | 从零实现 `HttpSession` + `HttpSessionStorage` SPI | Servlet 容器提供 |
| Session 持久化 | `SessionFlushListener` 通过 `ChannelFuture` 回调 | `HttpSessionListener` 容器触发 |
| 参数解析器桥接 | `SpringHandlerMethodArgumentResolverProvider` 启动期 `supports` 决策 | 原生 `HandlerMethodArgumentResolver` |
| 异常解析器桥接 | `SpringHandlerExceptionResolverAdapter` | 原生 `HandlerExceptionResolver` |
| 返回值处理器桥接 | `SpringHandlerMethodReturnValueHandlerAdapter` | 原生 `HandlerMethodReturnValueHandler` |
| Codec 拦截器 | `RequestBodyAdviceCodecInterceptor`/`ResponseBodyAdviceCodecInterceptor` | 原生 `RequestBodyAdvice`/`ResponseBodyAdvice` |
| SSE 桥接 | `ResponseBodyEmitterReturnValueResolver`（`HttpMessageConverter` 兜底） | 原生 `ResponseBodyEmitter`/`SseEmitter` |
| 标准异常处理 | `ResponseEntityExceptionHandler` 15 个标准异常处理 | 原生 `ResponseEntityExceptionHandler` |
| Servlet 规范桥接 | `PerfServletContext`/`PerfRequestDispatcher`/`PerfAsyncContext`/`Authenticator`/`upgrade()` 等 | Servlet 容器原生提供 |
| 同包同名覆盖 | 重写 `org.springframework.web.servlet.*` 40+ 类 | 不适用 |

**核心差异**：Spring MVC 的兼容层依赖 Servlet 容器，所有组件（Session、Filter、`HttpServletRequest`）都是容器提供的。本框架的 support 模块在"没有 Servlet 容器"的前提下，从零实现 `HttpServletRequest`/`HttpServletResponse`/`HttpSession`/`FilterChain` 接口，通过 `WebMvcConfigurerBridge` 把 Spring MVC 的配置回调翻译为框架内部的 Registry 操作，通过 `FilterWrapper`/`HandlerInterceptorWrapper`/`SpringHandlerMethodArgumentResolverProvider` 等适配器把 Spring 生态的组件桥接到框架的 SPI 体系。

---

## 十三、小结：support 的克制在哪里

回到引子的问题：support 如何让 Spring MVC 生态组件无缝工作在 Netty 上？

1. **"同包同名覆盖"消除编译期依赖** → 重写 `org.springframework.web.servlet.*` 的 40+ 个类（纯接口/default 方法），不依赖 `spring-webmvc` 即可编译。
2. **`WebMvcConfigurerBridge` 10 类回调翻译** → 两步策略（Shim 收集 → 翻译，均在 `initComponentPhase1` 内），把 `WebMvcConfigurer` 的配置写入框架的 Registry。
3. **`FilterWrapper` 实例级唯一标识** → `IdentityHashMap` + `AtomicLong` 确保同类不同实例不被去重误杀（修复 `System.identityHashCode` 碰撞）。
4. **`HandlerInterceptorWrapper` 四阶段适配** → `preHandle`/`postHandle`(null modelAndView)/`afterCompletion`(NestedServletException)/(`AsyncHandlerInterceptor` 可选)。
5. **`PerfHttpServletRequest`/`Response` 委托 + `rebind`** → 包装框架请求/响应为 `HttpServletRequest`/`HttpServletResponse`，`rebind` 支持 Filter 包装链。
6. **Session 体系从零实现** → `HttpSessionStorage` SPI + `InMemoryHttpSessionStorage` 默认实现 + `PerfHttpSessionManager` 管理器 + `SessionFlushListener` 在 `ChannelFuture` 回调中持久化（含 `HttpSessionBindingListener` 回调）。
7. **桥接适配器启动期决策** → `SpringHandlerMethodArgumentResolverProvider` 的 `supports` 在启动期调用，消除每请求 dispatch（替代旧 `RuntimeArgumentResolver`）。
8. **Servlet 规范桥接扩展** → `PerfServletContext`/`PerfRequestDispatcher`（forward/include）/`PerfAsyncContext`/`Authenticator`/`upgrade()` 等，补齐依赖 Servlet API 的上层框架所需语义。

这一层的克制体现在：**不把"兼容性"变成"性能债务"。** `WebMvcConfigurerBridge` 在 Phase 1 一次性完成翻译，不参与运行时。`FilterWrapper` 的 `IdentityHashMap` 在启动期分配唯一 ID，运行时 `getComponentName()` 是 O(1) 查表。`PerfHttpServletRequest` 的 `getSession()` 缓存到 `RequestContext` 的 fastAttributes 数组，避免每次查找。`ResponseBodyEmitterReturnValueResolver` 的 `encodeToStream` 先尝试 `HttpBodyCodecRegistry` 的 converter 再兜底——`HttpMessageConverter` 存在时走框架的 `HttpBodyConverter` 路径，不存在时 String→UTF-8 零分配 fallback。`forward/include` 只做**重映射**（`mappingRegistry.mapping`）而非重走 filter 链，`AsyncContext` 懒注册 handler 避免覆盖框架异步路径。

---

> **下一篇**：[13 · spring-web-batch 模块](13-batch-module.md)——Disruptor 透明请求聚合内幕。