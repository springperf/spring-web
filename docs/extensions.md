> [English](en/extensions.md) | 中文

# 扩展点指南

框架在请求处理管线的各个关键节点均提供了 SPI 接口，允许用户在不需要修改框架代码的情况下扩展功能。

---

## 1. WebFilter — 过滤器

在请求到达控制器前/响应返回后执行通用逻辑。

```java
@Component
public class CustomFilter implements WebFilter {

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response,
                         FilterChain chain) {
        // 请求前处理
        chain.doFilter(request, response);
        // 响应后处理
    }

    @Override
    public int getOrder() {
        return 0; // 控制执行顺序
    }
}
```

**自动注册**：实现 `WebFilter` 并声明为 Spring Bean 即可。

---

## 2. HandlerInterceptor — 拦截器

在处理器方法执行前后切入，支持按路径匹配。

```java
@Component
public class CustomInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(WebServerHttpRequest request, WebServerHttpResponse response,
                             Object handler) {
        // 处理器执行前
        return true; // false 则中断请求
    }

    @Override
    public void postHandle(WebServerHttpRequest request, WebServerHttpResponse response,
                           Object handler, Object result) {
        // 处理器执行后、返回值写入前
    }

    @Override
    public void afterCompletion(WebServerHttpRequest request, WebServerHttpResponse response,
                                Object handler, Throwable ex) {
        // 请求完成（无论是否异常）
    }

    @Override
    public void afterConcurrentHandlingStarted(WebServerHttpRequest request, WebServerHttpResponse response,
                                               Object handler) {
        // 异步处理开始时调用
    }
}
```

**带路径匹配的注册**：

```java
@Configuration
public class WebConfig {

    @Bean
    public InterceptorRegistration authInterceptor() {
        return new InterceptorRegistration(new AuthInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/login", "/api/register");
    }
}
```

**自动注册**：`HandlerInterceptor` 或 `InterceptorRegistration` 的 Spring Bean 均会被自动注册。

---

## 3. HttpBodyCodecInterceptor — 请求/响应体拦截

在请求体读取和响应体写入时拦截，适合做加密、解密、日志等。

```java
@Component
public class CryptoInterceptor implements HttpBodyCodecInterceptor {

    @Override
    public boolean supportBodyRead(MethodParameter methodParameter, Type targetType,
                                    HttpBodyConverter converter) {
        return true; // 可指定对哪些参数生效
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter,
                                           Type targetType, HttpBodyConverter converter) {
        // 对请求体做解密等处理
        return inputMessage;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage,
                                MethodParameter parameter, Type targetType, HttpBodyConverter converter) {
        // 对读取的请求体做解密等处理
        return body;
    }

    @Override
    public Object handleEmptyBodyRead(Object body, HttpInputMessage inputMessage,
                                       MethodParameter parameter, Type targetType, HttpBodyConverter converter) {
        return body;
    }

    @Override
    public boolean supportBodyWrite(MethodParameter returnType, HttpBodyConverter converter) {
        return true; // 可指定对哪些返回值生效
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType, HttpBodyConverter converter,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        // 对响应体做加密等处理
        return body;
    }
}
```

**关联 `@ControllerAdvice`**：可以将拦截器与 `@ControllerAdvice` 关联，实现对特定控制器的拦截。

---

## 4. HandlerExceptionResolver — 异常解析器

统一处理控制器抛出的异常。

```java
@Component
public class CustomExceptionResolver implements HandlerExceptionResolver {

    @Override
    public boolean resolveException(WebServerHttpRequest request,
                                    WebServerHttpResponse response,
                                    HandlerMethod handler, Throwable ex) {
        if (ex instanceof BusinessException) {
            // 自定义错误响应
            response.setHandled(true);
            // 写入错误 JSON
            return true; // 返回 true 表示已处理
        }
        return false; // 返回 false 表示未处理，交给下一个解析器
    }
}
```

框架内置异常解析器：
- `ExceptionHandlerExceptionResolver` — 处理 `@ExceptionHandler` 注解
- `ResponseStatusExceptionResolver` — 处理 `@ResponseStatus` 和 `ResponseStatusException`

---

## 5. ReturnValueResolver — 返回值解析器

自定义控制器方法的返回值处理方式。

```java
@Component
public class CustomReturnValueResolver implements ReturnValueResolver {

    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return CustomResult.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        return returnValue instanceof CustomResult;
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType,
                                   WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        CustomResult result = (CustomResult) returnValue;
        // 自定义写入逻辑
        resp.getBody().write(JsonUtils.toJsonBytes(result));
    }
}
```

**自动注册**：实现 `ReturnValueResolver` 并声明为 Spring Bean 即可。

---

## 6. RuntimeArgumentResolver — 运行时参数解析器

解析自定义类型的控制器方法参数（每个请求动态判断）。

```java
@Component
public class CurrentUserResolver implements RuntimeArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter,
                                     WebServerHttpRequest request,
                                     WebServerHttpResponse response) {
        return parameter.getParameterType() == CurrentUser.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  WebServerHttpRequest request,
                                  WebServerHttpResponse response) {
        String token = request.getHeaders().getFirst("Authorization");
        return parseToken(token); // 从 Token 解析当前用户
    }
}
```

**自动注册**：实现 `RuntimeArgumentResolver` 并声明为 Spring Bean 即可。

---

## 7. StaticArgumentResolverProvider — 静态参数解析器提供者

为特定注解的参数提供解析器（在初始化阶段决定，提高运行时性能）。

```java
@Component
public class CustomAnnotationResolverProvider implements StaticArgumentResolverProvider {

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return parameter.hasParameterAnnotation(CustomParam.class);
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter,
                                              MappingHandlerMethod mappingContext,
                                              WebContext webContext) {
        CustomParam annotation = parameter.getParameterAnnotation(CustomParam.class);
        return (request, response) -> {
            String value = request.getParameter(annotation.value());
            return convertToTargetType(value, parameter.getParameterType());
        };
    }
}
```

---

## 8. HttpBodyConverter — HTTP 消息转换器

扩展框架支持的 HTTP 消息格式。

```java
// 继承 WrappedHttpBodyConverter，包装 Spring 的 GenericHttpMessageConverter
public class XmlHttpBodyConverter extends WrappedHttpBodyConverter<Object> {

    public XmlHttpBodyConverter() {
        super(new XmlSpringConverter()); // 包装 Spring 转换器
    }
}
```

**自动注册**：`HttpBodyConverter` 或 Spring `HttpMessageConverter` 类型的 Bean 会被自动注册。

---

## 9. JsonConverter — JSON 转换器

替换 JSON 序列化/反序列化实现。

```java
@Component
public class CustomJsonConverter implements JsonConverter {

    @Override
    public String toJson(Object obj) {
        // 自定义序列化
    }

    @Override
    public void toJson(OutputStream outputStream, Object obj) {
        // 自定义序列化到输出流
    }

    @Override
    public Object fromJson(String json, Type type) {
        // 自定义反序列化
    }

    @Override
    public Object fromJson(byte[] json, Type type) {
        return fromJson(new String(json, StandardCharsets.UTF_8), type);
    }

    @Override
    public Object fromJson(InputStream json, Type type) {
        // 从输入流反序列化
    }
}
```

**注意**：替换默认 JSON 实现时，确保自定义 `JsonConverter` 的 Bean 优先级高于框架默认的 `JacksonConverter`（可通过 `@Primary` 或 `@Order` 控制）。

---

## 10. WebComponent — 框架组件

实现自定义框架级组件，参与框架的生命周期管理。

```java
@Component
public class CustomComponent extends BaseWebComponent {

    @Override
    public void initComponentPhase1() {
        // 阶段1：扫描、注册
    }

    @Override
    public void initComponentPhase2() {
        // 阶段2：构建内部结构
    }

    @Override
    public void initComponentPhase3() {
        // 阶段3：预热、校验
    }

    @Override
    public void destroyComponent() {
        // 资源清理
    }
}
```

---

## 11. BizPoolRegistry — 自定义业务线程池

通过 `@RunInPool` 将处理器方法调度到指定线程池。框架自动发现 Spring 容器中的 `ThreadPoolExecutor` Bean，Bean 名称即池名称。

```java
@Configuration
public class ThreadPoolConfig {

    @Bean("heavy-io")
    public ThreadPoolExecutor heavyIoPool() {
        return new ThreadPoolExecutor(
            20, 100, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(500)
        );
    }
}
```

也可通过配置文件定义默认线程池：

```yaml
pool:
  core-pool-size: 50
  max-pool-size: 200
  keep-alive-time: 60
  queue-capacity: 500
  default-execute-mode: default # "eventloop" 表示走 EventLoop
```

在控制器中使用：

```java
@RestController
public class HeavyController {

    @GetMapping("/heavy-report")
    @RunInPool("heavy-io") // 调度到 heavy-io 线程池执行
    public Result<Report> generateReport() {
        // 耗时操作，不会阻塞 Netty EventLoop
    }
}
```

---

## 12. RouterOptimizer — 路由优化器

实现自定义路由优化策略（高级）。

```java
@Component
public class CustomRouterOptimizer implements RouterOptimizer {

    @Override
    public boolean support(List<PathMappingContext> list) {
        return true;
    }

    @Override
    public Router optimizeRoute(WebServerHttpRequest req) {
        // 为请求选择最佳路由
        return null;
    }
}
```

---

## 集成案例：SpringDoc OpenAPI

本框架不使用 Spring MVC，因此 SpringDoc 默认无法通过 `RequestMappingHandlerMapping` 发现路由。框架通过实现 `OpenApiCustomizer` SPI，从 `MappingRegistry` 中构建 OpenAPI 文档。

### 使用

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-ui</artifactId>
    <version>1.7.0</version>
</dependency>
```

添加依赖后 `OpenApiAutoConfiguration` 自动激活（`@ConditionalOnClass(OpenApiCustomiser.class)`），无需手动配置。

### 架构

```
SpringDoc 启动 → 扫描 RequestMappingHandlerMapping → 无（本框架无 Spring MVC）
           ↓
OpenApiAdapter.customize(OpenAPI)
           ↓
遍历 MappingRegistry → 构建 PathItem → openApi.path(path, pathItem)
           ↓
Swagger UI 显示完整 API 文档
```

Swagger 注解（`@Tag`、`@Operation`、`@Schema`）直接可用。

### 局限性

| 功能 | 支持情况 |
|------|---------|
| 路由发现 | ✅ 全部自动扫描 |
| 路径/Query/Header 参数 | ✅ 自动解析 |
| RequestBody | ✅ application/json |
| 响应类型 | ✅ 基础类型映射 |
| 泛型类型 | ⚠️ `ApiResult<T>` 映射为 object |
| Swagger 注解 | ✅ 原生支持 |
| 模型 Schema | ⚠️ 复杂对象需补充 `@Schema` |

---

## 汇总：SPI 接口一览

| 接口 | 注册方式 | 用途 |
|------|----------|------|
| `WebFilter` | Spring Bean | 请求过滤器 |
| `HandlerInterceptor` | Spring Bean | 处理器拦截器 |
| `InterceptorRegistration` | Spring Bean | 带路径匹配的拦截器注册 |
| `HttpBodyCodecInterceptor` | Spring Bean | 请求/响应体读写拦截 |
| `HandlerExceptionResolver` | Spring Bean | 异常解析器 |
| `ReturnValueResolver` | Spring Bean | 返回值解析器 |
| `RuntimeArgumentResolver` | Spring Bean | 运行时参数解析 |
| `StaticArgumentResolverProvider` | Spring Bean | 静态参数解析器提供者 |
| `HttpBodyConverter` | Spring Bean | HTTP 消息格式转换 |
| `JsonConverter` | Spring Bean | JSON 序列化实现 |
| `WebComponent` / `BaseWebComponent` | Spring Bean | 框架级组件（参与生命周期） |
| `RouterOptimizer` | Spring Bean | 路由优化策略 |
| `WebCorsProcessor` | Spring Bean | CORS 处理策略 |