> English | [中文](../extensions.md)

# Extension Points Guide

The framework provides SPI interfaces at every key point in the request processing pipeline, allowing users to extend functionality without modifying framework code.

---

## 1. WebFilter — Filters

Execute common logic before the request reaches the controller / after the response returns.

```java
@Component
public class CustomFilter implements WebFilter {

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response,
                         FilterChain chain) {
        // Pre-processing
        chain.doFilter(request, response);
        // Post-processing
    }

    @Override
    public int getOrder() {
        return 0; // Controls execution order
    }
}
```

**Auto-registration**: implement `WebFilter` and declare it as a Spring Bean.

---

## 2. HandlerInterceptor — Interceptors

Intercept around handler method execution with path matching support.

```java
@Component
public class CustomInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(WebServerHttpRequest request, WebServerHttpResponse response,
                             Object handler) {
        // Before handler execution
        return true; // return false to interrupt the request
    }

    @Override
    public void postHandle(WebServerHttpRequest request, WebServerHttpResponse response,
                           Object handler, Object result) {
        // After handler execution, before return value writing
    }

    @Override
    public void afterCompletion(WebServerHttpRequest request, WebServerHttpResponse response,
                                Object handler, Throwable ex) {
        // After request completion (regardless of exception)
    }

    @Override
    public void afterConcurrentHandlingStarted(WebServerHttpRequest request, WebServerHttpResponse response,
                                               Object handler) {
        // Called when async processing starts
    }
}
```

**Registration with path matching**:

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

**Auto-registration**: both `HandlerInterceptor` and `InterceptorRegistration` Spring Beans are automatically registered.

---

## 3. HttpBodyCodecInterceptor — Request/Response Body Interception

Intercept during request body reading and response body writing, suitable for encryption, decryption, logging, etc.

```java
@Component
public class CryptoInterceptor implements HttpBodyCodecInterceptor {

    @Override
    public boolean supportBodyRead(MethodParameter methodParameter, Type targetType,
                                    HttpBodyConverter converter) {
        return true; // Can specify which parameters to affect
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter,
                                           Type targetType, HttpBodyConverter converter) {
        // Decrypt or process the input message
        return inputMessage;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage,
                                MethodParameter parameter, Type targetType, HttpBodyConverter converter) {
        // Decrypt or process the read body
        return body;
    }

    @Override
    public Object handleEmptyBodyRead(Object body, HttpInputMessage inputMessage,
                                       MethodParameter parameter, Type targetType, HttpBodyConverter converter) {
        return body;
    }

    @Override
    public boolean supportBodyWrite(MethodParameter returnType, HttpBodyConverter converter) {
        return true; // Can specify which return types to affect
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType, HttpBodyConverter converter,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        // Encrypt or process the response body
        return body;
    }
}
```

**Associating with `@ControllerAdvice`**: interceptors can be associated with `@ControllerAdvice` for targeted controller interception.

---

## 4. HandlerExceptionResolver — Exception Resolver

Unified handling of exceptions thrown by controllers.

```java
@Component
public class CustomExceptionResolver implements HandlerExceptionResolver {

    @Override
    public boolean resolveException(WebServerHttpRequest request,
                                    WebServerHttpResponse response,
                                    HandlerMethod handler, Throwable ex) {
        if (ex instanceof BusinessException) {
            // Custom error response
            response.setHandled(true);
            // Write error JSON
            return true; // Return true to indicate handled
        }
        return false; // Return false to pass to next resolver
    }
}
```

Built-in exception resolvers:
- `ExceptionHandlerExceptionResolver` — Handles `@ExceptionHandler` annotations
- `ResponseStatusExceptionResolver` — Handles `@ResponseStatus` and `ResponseStatusException`

---

## 5. ReturnValueResolver — Return Value Resolver

Customize how controller method return values are handled.

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
        // Custom write logic
        resp.getBody().write(JsonUtils.toJsonBytes(result));
    }
}
```

**Auto-registration**: implement `ReturnValueResolver` and declare it as a Spring Bean.

---

## 6. RuntimeArgumentResolver — Runtime Argument Resolver

Resolve custom controller method parameters (dynamic per-request decision).

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
        return parseToken(token); // Parse current user from Token
    }
}
```

**Auto-registration**: implement `RuntimeArgumentResolver` and declare it as a Spring Bean.

---

## 7. StaticArgumentResolverProvider — Static Argument Resolver Provider

Provide resolvers for annotated parameters (determined at initialization for better runtime performance).

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

## 8. HttpBodyConverter — HTTP Message Converter

Extend the supported HTTP message formats.

```java
// Extend WrappedHttpBodyConverter, wrapping a Spring GenericHttpMessageConverter
public class XmlHttpBodyConverter extends WrappedHttpBodyConverter<Object> {

    public XmlHttpBodyConverter() {
        super(new XmlSpringConverter()); // Wrap Spring converter
    }
}
```

**Auto-registration**: both `HttpBodyConverter` and Spring `HttpMessageConverter` beans are automatically registered.

---

## 9. JsonConverter — JSON Converter

Replace the JSON serialization/deserialization implementation.

```java
@Component
public class CustomJsonConverter implements JsonConverter {

    @Override
    public String toJson(Object obj) {
        // Custom serialization
    }

    @Override
    public void toJson(OutputStream outputStream, Object obj) {
        // Custom serialization to output stream
    }

    @Override
    public Object fromJson(String json, Type type) {
        // Custom deserialization
    }

    @Override
    public Object fromJson(byte[] json, Type type) {
        return fromJson(new String(json, StandardCharsets.UTF_8), type);
    }

    @Override
    public Object fromJson(InputStream json, Type type) {
        // Deserialize from input stream
    }
}
```

**Note**: when replacing the default JSON implementation, ensure your custom `JsonConverter` bean has higher priority than the default `JacksonConverter` (use `@Primary` or `@Order`).

---

## 10. WebComponent — Framework Component

Implement custom framework-level components that participate in the framework lifecycle.

```java
@Component
public class CustomComponent extends BaseWebComponent {

    @Override
    public void initComponentPhase1() {
        // Phase 1: Scanning, registration
    }

    @Override
    public void initComponentPhase2() {
        // Phase 2: Building internal structures
    }

    @Override
    public void initComponentPhase3() {
        // Phase 3: Warmup, validation
    }

    @Override
    public void destroyComponent() {
        // Resource cleanup
    }
}
```

---

## 11. BizPoolRegistry — Custom Business Thread Pool

Schedule handler methods to specific thread pools via `@RunInPool`. The framework auto-discovers `ThreadPoolExecutor` beans from the Spring container — the bean name is used as the pool name.

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

The default thread pool can also be configured via properties:

```yaml
pool:
  core-pool-size: 50
  max-pool-size: 200
  keep-alive-time: 60
  queue-capacity: 500
  default-execute-mode: default # "eventloop" to use EventLoop directly
```

Usage in a controller:

```java
@RestController
public class HeavyController {

    @GetMapping("/heavy-report")
    @RunInPool("heavy-io") // Schedule to heavy-io thread pool
    public Result<Report> generateReport() {
        // Time-consuming operation, won't block Netty EventLoop
    }
}
```

---

## 12. RouterOptimizer — Route Optimizer

Implement custom route optimization strategies (advanced).

```java
@Component
public class CustomRouterOptimizer implements RouterOptimizer {

    @Override
    public boolean support(List<PathMappingContext> list) {
        return true;
    }

    @Override
    public Router optimizeRoute(WebServerHttpRequest req) {
        // Select the best route for the request
        return null;
    }
}
```

---

## Integration Example: SpringDoc OpenAPI

This framework does not use Spring MVC, so SpringDoc cannot discover routes through `RequestMappingHandlerMapping` by default. The framework implements the `OpenApiCustomizer` SPI to build OpenAPI documentation from `MappingRegistry`.

### Usage

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-ui</artifactId>
    <version>1.7.0</version>
</dependency>
```

After adding the dependency, `OpenApiAutoConfiguration` activates automatically (`@ConditionalOnClass(OpenApiCustomiser.class)`), no manual configuration required.

### Architecture

```
SpringDoc starts → scans RequestMappingHandlerMapping → none (this framework has no Spring MVC)
           ↓
OpenApiAdapter.customize(OpenAPI)
           ↓
Iterates MappingRegistry → builds PathItem → openApi.path(path, pathItem)
           ↓
Swagger UI displays complete API documentation
```

Swagger annotations (`@Tag`, `@Operation`, `@Schema`) work directly.

### Limitations

| Feature | Status |
|---------|--------|
| Route discovery | ✅ Auto-scanned |
| Path/Query/Header parameters | ✅ Auto-resolved |
| RequestBody | ✅ application/json |
| Response types | ✅ Basic type mapping |
| Generic types | ⚠️ `ApiResult<T>` maps as object |
| Swagger annotations | ✅ Native support |
| Model Schema | ⚠️ Complex objects need `@Schema` |

---

## Summary: SPI Interface Overview

| Interface | Registration | Purpose |
|-----------|-------------|---------|
| `WebFilter` | Spring Bean | Request filter |
| `HandlerInterceptor` | Spring Bean | Handler interceptor |
| `InterceptorRegistration` | Spring Bean | Path-matched interceptor registration |
| `HttpBodyCodecInterceptor` | Spring Bean | Request/response body read/write interception |
| `HandlerExceptionResolver` | Spring Bean | Exception resolver |
| `ReturnValueResolver` | Spring Bean | Return value resolver |
| `RuntimeArgumentResolver` | Spring Bean | Runtime argument resolver |
| `StaticArgumentResolverProvider` | Spring Bean | Static argument resolver provider |
| `HttpBodyConverter` | Spring Bean | HTTP message format conversion |
| `JsonConverter` | Spring Bean | JSON serialization implementation |
| `WebComponent` / `BaseWebComponent` | Spring Bean | Framework-level component (participates in lifecycle) |
| `RouterOptimizer` | Spring Bean | Route optimization strategy |
| `WebCorsProcessor` | Spring Bean | CORS processing strategy |