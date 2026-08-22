# 10 · 横切关注点：拦截器、Filter、CORS、异常

> [← 返回索引](00-README.md) | 上一篇：[09 · 调用器与字节码优化](09-invoker-bytecode.md) | 下一篇：[11 · 异步流式支持](11-async-streaming.md)

---

## 引子：横切逻辑要"可插拔"，但不能"低性能"

[04 篇](04-request-pipeline.md) 的请求处理管线中，`DispatcherHandler.handleWithFilter` → `handleAfterFilter` 之间横跨了四类横切逻辑：**Filter**（请求前/后，可短路）、**拦截器**（preHandle/postHandle/afterCompletion）、**CORS**（预检与跨域配置）、**异常处理**（`@ExceptionHandler` 兜底）。

Spring MVC 的做法是：每类横切逻辑各有一个独立的遍历链，运行时逐个调用 `supports`/`matches`/`canHandle`。本框架的做法是：**启动期或首次请求时，为每个 `PathMappingContext` 预先计算出"哪些横切组件适用"，缓存为数组或列表，运行时直接遍历命中列表，不做匹配查找。**

这个"预计算匹配"的设计，是 [01 篇](01-design-philosophy.md) 原则 1（零匹配）在横切关注点的落地。

---

## 一、拦截器：`InterceptorRegistry`

### 1.1 `HandlerInterceptor` 三阶段

`HandlerInterceptor` 接口定义了三个拦截点：

| 阶段 | 方法 | 调用时机 | 短路行为 |
|------|------|---------|---------|
| before | `preHandle` | 参数解析前、方法调用前 | 返回 `false` 终止后续拦截器 + 方法调用 |
| after | `postHandle` | 方法调用后、返回值解析前 | 不短路，异常时跳过 |
| finally | `afterCompletion` | 请求完成后（含异常/异步） | 逆序调用，不短路 |

### 1.2 匹配缓存：`PathMappingContext.cachedInterceptors`

`InterceptorRegistry.realGetInterceptors`是拦截器匹配的入口。它使用三层缓存策略：

1. **请求属性缓存**（`getInterceptors` :140-147）：单次请求多次调用 `preHandle`/`postHandle`/`afterCompletion` 时，拦截器列表缓存在 `RequestContext` 的 `INTERCEPTORS_ATTRIBUTE` 中，避免重复计算。
2. **`PathMappingContext.cachedInterceptors`**：按方法缓存预计算的结果。`initCachedInterceptors`在首次请求时计算，DCL 保护并发。
3. **`RuntimeMappingInterceptor` 运行时路径匹配**：对包含 `includePatterns`/`excludePatterns` 的拦截器，运行时按请求路径检查 `matches`。

`initCachedInterceptors` 使用三段式推断：

```java
// InterceptorRegistry.java
protected List<HandlerInterceptor> initCachedInterceptors(PathMappingContext mappingContext) {
    String pathRule = mappingContext.getPathRule();
    List<HandlerInterceptor> interceptors = new ArrayList<>();
    for (InterceptorRegistration registration : registrations) {
        ContainmentResult result = registration.matchPathRuleToCached(pathRule);
        if (result == ContainmentResult.ALWAYS) {
            interceptors.add(registration.getInterceptor());          // 直接加入
        } else if (result == ContainmentResult.RUNTIME) {
            interceptors.add(getRuntimeMappingInterceptor(registration)); // 包装为运行时匹配
        }
        // NEVER → skip
    }
    return interceptors;
}
```

`ContainmentResult` 三段式（`ALWAYS`/`NEVER`/`RUNTIME`）与 [03 篇](03-component-lifecycle.md) 的 `ContainmentResult` 是同一套机制——通过路径模式匹配在编译期推断包含关系，避免运行时逐条字符串匹配。

### 1.3 `preHandle` 短路：`afterCompletionForPassed`

`preHandle` 方法严格对齐 Spring 的 `HandlerExecutionChain.applyPreHandle` 语义：

```java
// InterceptorRegistry.java
public boolean preHandle(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
    List<HandlerInterceptor> interceptors = getInterceptors(request);
    int passed = 0;
    try {
        for (HandlerInterceptor i : interceptors) {
            if (!i.preHandle(request, response, mappingContext)) {
                afterCompletionForPassed(request, response, null, interceptors, passed);
                return false;
            }
            passed++;
        }
    } catch (Exception e) {
        afterCompletionForPassed(request, response, e, interceptors, passed);
        throw e;
    }
    return true;
}
```

关键语义：`preHandle` 返回 `false` 时，**仅对已通过 preHandle 的拦截器**逆序调用 `afterCompletion`（`afterCompletionForPassed`）。未进入的拦截器未持有资源，不应收到回调。注释记录了修复历史——修复前对所有拦截器调用 `afterCompletion`，会误触发尚未 `preHandle` 的拦截器的回调。

### 1.4 `RuntimeMappingInterceptor`

`getRuntimeMappingInterceptor`对包含路径规则的拦截器包装为 `RuntimeMappingInterceptor`，运行时按请求路径 `matches` 决定是否应用。`RuntimeMappingInterceptor` 内部持有 `includePatterns`/`excludePatterns` 和 `PathMatcher`（可选，兼容 Spring 5.3+ 的 `PathPatternRouteMatcher` 和旧版 `AntPathMatcher`）。

---

## 二、WebFilter 链：`WebFilterRegistry`

### 2.1 Filter 与 Interceptor 的区别

`WebFilter`（doFilter 链）与 `HandlerInterceptor`（preHandle/postHandle/afterCompletion）的区别：

| 维度 | `WebFilter` | `HandlerInterceptor` |
|------|-------------|---------------------|
| 层级 | 低层级，请求进入 handler 前 | 高层级，绑定到 `PathMappingContext` |
| 短路 | `doFilter` 不调 `chain.doFilter` 即可短路 | `preHandle` 返回 false 短路 |
| 后处理 | 不提供独立的后处理回调 | `postHandle` + `afterCompletion` |
| 路径匹配 | `RuntimeMappingWebFilter` 运行时匹配 | `PathMappingContext.cachedInterceptors` 预匹配 |
| 适用场景 | 安全过滤、请求/响应转换、日志 | 业务拦截（权限校验、审计） |

### 2.2 `DefaultFilterChain` + filterIndex

`DefaultFilterChain.doFilter`使用 `filterIndex` 索引实现链式调用：

```java
// DefaultFilterChain.java
public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
    BaseWebServerHttpRequest requestContext = (BaseWebServerHttpRequest) request.getRequestContext();
    int index = requestContext.getFilterIndexAndIncrement();
    if (index < filters.size()) {
        WebFilter next = filters.get(index);
        next.doFilter(request, response, this);
    } else {
        dispatcherHandler.handleAfterFilter(request, response, MappingResult.get(request));
    }
}
```

`filterIndex` 是 `BaseWebServerHttpRequest` 上的 `int` 字段，每调一次 `getFilterIndexAndIncrement()` 自增。最后一个 Filter 调用 `chain.doFilter` 后，索引超出 `filters.size()`，终端回调 `dispatcherHandler.handleAfterFilter`。

### 2.3 三段式缓存

`WebFilterRegistry.resolveFilterChain`使用与拦截器相同的三段式推断：

```java
// WebFilterRegistry.java
protected DefaultFilterChain resolveFilterChain(WebServerHttpRequest request) {
    MappingResult mappingResult = MappingResult.get(request);
    if (!mappingResult.isMatched()) {
        return unmatchedChain;   // 无匹配路径 → 使用全量 filter 列表（含 RuntimeMappingWebFilter）
    }
    PathMappingContext mappingContext = mappingResult.getMatchedContext();
    DefaultFilterChain cached = mappingContext.getCachedFilterChain();
    if (cached != null) return cached;

    synchronized (mappingContext) {
        cached = mappingContext.getCachedFilterChain();
        if (cached == null) {
            List<WebFilter> filters = initCachedFilters(mappingContext);
            cached = new DefaultFilterChain(dispatcherHandler, filters);
            mappingContext.setCachedFilterChain(cached);
        }
    }
    return cached;
}
```

`initCachedFilters`逐 `WebFilterRegistration` 判断与 `PathMappingContext.pathRule` 的包含关系：`ALWAYS` 直接加入，`NEVER` 跳过，`RUNTIME` 包装为 `RuntimeMappingWebFilter` 按运行时路径匹配。

`unmatchedChain`是 `new DefaultFilterChain(dispatcherHandler, allFilters)`——`allFilters` 在 `initAllFilters`中预计算，包含所有无路径规则的 Filter 和包装为 `RuntimeMappingWebFilter` 的有路径规则 Filter。路径未匹配时走这个链，确保 Filter 仍有机会执行。

---

## 三、CORS：`CorsRegistry`

### 3.1 配置来源

CORS 配置有两个来源：

1. **`@CrossOrigin` 注解**（类级 + 方法级）：`createCorsConfigurationProvider`中读取 `CrossOrigin` 注解，填充 `CorsConfiguration` 对象。
2. **编程式 `CorsRegistration`**：通过 `CorsRegistry.addMapping(pathPattern)` 注册，每个 `CorsRegistration` 持有一个 `CorsConfiguration` 和路径模式。

### 3.2 配置合并

`createCorsConfigurationProvider` 的合并逻辑：

```java
// CorsRegistry.java
config.applyPermitDefaultValues();
```

`applyPermitDefaultValues()` 是 Spring 的 `CorsConfiguration` 方法，为未设置的字段填充默认值（如 `allowedOrigins = *`）。

多源合并时，`@CrossOrigin` 配置 + 编程配置通过 `combine` 合并：

```java
// CorsRegistry.java
return new SimpleCorsConfigurationProvider(alwaysRegistration.getCorsConfiguration().combine(config));
```

`combine` 是 Spring 的 `CorsConfiguration` 方法，后者的配置覆盖前者——编程配置优先于注解配置。

### 3.3 运行时选择

`getCorsConfigurationProvider`在运行时选择 `CorsConfigurationProvider`：

1. 从 `MappingResult` 获取 `PathMappingContext`。
2. 检查 `context.getCorsConfigurationProvider()` 缓存。
3. 未缓存时调用 `createCorsConfigurationProvider` 创建，并缓存到 `context`。

`CorsConfigurationProvider` 有四种实现：

| 实现 | 条件 |
|------|------|
| `NoneCorsConfigurationProvider` | 无 `@CrossOrigin` 且无注册 |
| `SimpleCorsConfigurationProvider` | 单一配置（注解或单个 ALWAYS 注册） |
| `RuntimeMappingCorsConfigurationProvider` | 多个 `RUNTIME` 注册，运行时按路径匹配 |
| （直接存储在 `PathMappingContext` 上的引用） | 已缓存 |

### 3.4 预检请求

`corsHandle`区分预检请求和实际请求：

```java
// CorsRegistry.java
public boolean corsHandle(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
    CorsConfigurationProvider provider = getCorsConfigurationProvider(request);
    CorsConfiguration config = provider.getCorsConfiguration(request, response);
    boolean preFlightRequest = CorsUtils.isPreFlightRequest(request);
    if (config == null && !preFlightRequest) return false;
    if (preFlightRequest) {
        webCorsProcessor.process(config, request, response);
        return true;       // 预检请求 → 写入响应头，返回 true 表示"已处理"
    } else {
        return !webCorsProcessor.process(config, request, response);
        // 实际请求 → process 返回 true 表示"配置不满足"，取反后 false 表示"不阻止"
    }
}
```

`PerfCorsProcessor` 是默认的 `WebCorsProcessor` 实现，基于 Spring 的 `DefaultCorsProcessor`，处理实际的 CORS 响应头写入和校验。

---

## 四、异常处理：`ExceptionRegistry`

### 4.1 解析器链

`ExceptionRegistry.initWithWebContext`注册两个内置解析器：

```java
// ExceptionRegistry.java
registerWebComponent(new ExceptionHandlerExceptionResolver());   // @ExceptionHandler 扫描
registerWebComponent(new ResponseStatusExceptionResolver());     // @ResponseStatus + ResponseStatusException
registerWebComponent(HandlerExceptionResolver.class);            // SPI 扩展
```

`handle` 方法是入口：

```java
// ExceptionRegistry.java
public void handle(Throwable ex, WebServerHttpRequest req, WebServerHttpResponse resp) {
    try {
        boolean handled = doHandle(ex, req, resp);
        metrics.recordException(ex.getClass().getName(), handled);
        if (handled) {
            resp.setHandled();
        } else {
            resp.sendError(INTERNAL_SERVER_ERROR, "Internal Server Error");  // 兜底 500
        }
    } catch (Exception e) {
        log.error("ExceptionRegistry.doHandle/sendError failed for original [{}] {}",
                ex.getClass().getSimpleName(), ex.getMessage(), e);
    }
}
```

### 4.2 `ExceptionHandlerExceptionResolver`：`@ExceptionHandler` 扫描

`ExceptionHandlerExceptionResolver` 是异常处理的核心。它扫描 `@ControllerAdvice` 中的 `@ExceptionHandler` 方法，在运行时按异常类型匹配。

#### 初始化

`initExceptionHandler`使用 Spring 的 `ControllerAdviceBean.findAnnotatedBeans` 扫描所有 `@ControllerAdvice` Bean，为每个包含 `@ExceptionHandler` 方法的 Bean 创建 `ExceptionHandlerAdvice`：

```java
// ExceptionHandlerExceptionResolver.java
protected void initExceptionHandler() {
    List<ControllerAdviceBean> adviceBeans = ControllerAdviceBean.findAnnotatedBeans(webContext.getCtx());
    for (ControllerAdviceBean adviceBean : adviceBeans) {
        Class<?> beanType = adviceBean.getBeanType();
        ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(beanType);
        if (resolver.hasExceptionMappings()) {
            registerWebComponent(new ExceptionHandlerAdvice(adviceBean, resolver));
        }
    }
}
```

#### 运行时匹配

`resolveException`是运行时异常匹配入口：

1. 从 `PathMappingContext` 获取缓存的 `ExceptionHandlerAdvice[]`（`getCachedExceptionHandlerAdvices` :156-180）。
2. 遍历 `advices`，对每个 `advice` 调用 `resolveHandlerMethod(ex)` → 找到最匹配的 `@ExceptionHandler` 方法。
3. 如果匹配且异常类型是 `ResponseStatusException`，检查 `isExplicitRseHandler`——只有显式声明 `@ExceptionHandler(ResponseStatusException.class)` 的处理器才处理，避免被 `@ExceptionHandler(Throwable.class)` 等宽泛声明意外拦截。

#### 异常参数解析

`ExceptionHandlerExceptionResolver` 内部注册了一个 `ExceptionArgumentResolverProvider`，将当前的异常对象注入 `@ExceptionHandler` 方法的 `Throwable` 参数：

```java
// ExceptionHandlerExceptionResolver.java
protected static class ExceptionArgumentResolverProvider implements StaticArgumentResolverProvider {
    private static final StaticArgumentResolver resolver = (req, resp) -> req.getRequestContext().getAttribute(EXCEPTION_OBJECT_KEY);

    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return Throwable.class.isAssignableFrom(parameter.getParameterType())
                && MappingHandlerMethod.class.equals(mappingContext.getClass());
    }
    public StaticArgumentResolver getResolver(...) { return resolver; }
}
```

`EXCEPTION_OBJECT_KEY` 在 `invokeAndWriteError`中设置到请求属性，`ExceptionArgumentResolverProvider` 从请求属性中取出。`MappingHandlerMethod.class.equals(mappingContext.getClass())` 确保这个解析器只服务于异常处理器方法，不干扰正常的 `@Controller` 方法中可能出现的 `Throwable` 参数。

### 4.3 `ResponseStatusException` 显式处理保护

`isExplicitRseHandler`使用 `MappingCacheKey` 缓存结果：

```java
// ExceptionHandlerExceptionResolver.java
static boolean isExplicitRseHandler(MappingHandlerMethod handlerMethod) {
    Boolean cached = handlerMethod.get(RSE_EXPLICIT_CACHE_KEY);
    if (cached != null) return cached;

    Method method = handlerMethod.getMethod();
    ExceptionHandler ann = method.getAnnotation(ExceptionHandler.class);
    boolean result = false;
    if (ann != null) {
        for (Class<?> type : ann.value()) {
            if (ResponseStatusException.class.isAssignableFrom(type)) {
                result = true;
                break;
            }
        }
    }
    handlerMethod.set(RSE_EXPLICIT_CACHE_KEY, result);
    return result;
}
```

`RSE_EXPLICIT_CACHE_KEY` 是方法级缓存键（`MappingCacheKey.createMethodCacheKey`），结果缓存在 `MappingHandlerMethod` 上——首次反射计算后不再重复读注解。`ResponseStatusException` 是框架内部信号异常（如 404/405 的 `StacklessResponseStatusException`），不应被宽泛的父类匹配意外拦截。

### 4.4 `ResponseStatusExceptionResolver`

`ResponseStatusExceptionResolver` 处理三类异常：`@ResponseStatus` 注解标记的异常、`ResponseStatusException`，以及参数绑定/消息体解析错误（`MethodArgumentNotValidException`、`MethodArgumentTypeMismatchException`、`HttpMessageNotReadableException`，统一返回 400）。它在 `ExceptionRegistry.initWithWebContext`中注册，晚于 `ExceptionHandlerExceptionResolver`；两者默认 `order` 相同，运行时按 `resolvers` 列表顺序遍历，首个匹配的解析器处理。对 `ResponseStatusException`，`ExceptionHandlerExceptionResolver` 通过 `isExplicitRseHandler` 保护——未显式声明 `@ExceptionHandler(ResponseStatusException.class)` 时跳过，交由 `ResponseStatusExceptionResolver` 兜底处理。

---

## 五、对比 Spring MVC 横切关注点

| 维度 | 本框架 | Spring MVC |
|------|--------|-----------|
| 拦截器匹配 | `PathMappingContext.cachedInterceptors` 三段式推断 + `RuntimeMappingInterceptor` 运行时 | `MappedInterceptor.getInterceptor` 运行时遍历路径模式 |
| Filter 链 | `DefaultFilterChain` + filterIndex + `cachedFilterChain` DCL | `FilterChain.doFilter`（Servlet 标准） |
| CORS 配置 | 三段式推断 + `CorsConfigurationProvider` 缓存 | `AbstractHandlerMapping.getCorsConfiguration` 运行时遍历 |
| 异常处理 | `ExceptionHandlerAdvice[]` 缓存 + `MappingCacheKey` | `HandlerExceptionResolverComposite` 运行时遍历 |
| `ResponseStatusException` 保护 | `isExplicitRseHandler` 显式声明检查 | 无等效机制（可被 `@ExceptionHandler` 宽泛匹配拦截） |
| 异常参数 | `ExceptionArgumentResolverProvider` 专用解析器 | 复用 `HandlerMethodArgumentResolver` 链 |
| 缓存粒度 | 按 `PathMappingContext`（方法级）缓存 | 无缓存，每次请求重新匹配 |

**核心差异**：Spring MVC 的横切关注点匹配是"每次请求、每个横切组件、逐条字符串匹配"——`MappedInterceptor` 的 `matches` 在每次请求的 `preHandle`/`postHandle`/`afterCompletion` 中都被调用。本框架把匹配从"每次请求"前移到"首次请求"（DCL 缓存到 `PathMappingContext`），`RuntimeMappingInterceptor`/`RuntimeMappingWebFilter` 只对真正需要运行时匹配的组件生效——大多数拦截器/Filer 在启动期就能确定 `ALWAYS` 或 `NEVER`。

---

## 六、小结：横切关注点的克制在哪里

回到引子的问题：横切逻辑怎么做到"可插拔"又"高性能"？

1. **拦截器三段式缓存** → `PathMappingContext.cachedInterceptors` 在首次请求时预计算，`ALWAYS`/`NEVER`/`RUNTIME` 三段式推断避免运行时逐条字符串匹配。
2. **Filter 链 DCL 缓存** → `PathMappingContext.cachedFilterChain` 同样三段式推断，`unmatchedChain` 兜底确保无路径匹配时 Filter 仍可执行。
3. **CORS 配置按需创建** → `CorsConfigurationProvider` 在首次 CORS 请求时创建并缓存到 `PathMappingContext`，`@CrossOrigin` 与编程配置 `combine` 合并。
4. **异常处理 `@ExceptionHandler` 缓存** → `ExceptionHandlerAdvice[]` 按方法缓存，`MappingCacheKey` 整型索引数组直取，`isExplicitRseHandler` 防止 `ResponseStatusException` 被宽泛匹配意外拦截。
5. **`ResponseStatusException` 显式保护** → `RSE_EXPLICIT_CACHE_KEY` 方法级缓存，只有显式声明的处理器才处理框架内部信号异常。

这一层的克制体现在：**不把"匹配查找"留给运行时，也不为"缓存"提前做复杂计算。** 三段式推断（`ALWAYS`/`NEVER`/`RUNTIME`）在启动期用路径模式匹配做一次编译期推断，运行时只遍历已经确定"该执行"的列表。`RUNTIME` 的组件被包装为 `RuntimeMapping*`，只在需要时才做运行时路径匹配——大多数横切组件（全局拦截器、无路径规则的 Filter）在启动期就能确定 `ALWAYS`，运行时零额外匹配开销。

---

> **下一篇**：[11 · 异步流式支持](11-async-streaming.md)——异步任务、流式输出、SSE：`DeferredResult`、`Callable`、`StreamEmitter`、`NettyStreamSender`。