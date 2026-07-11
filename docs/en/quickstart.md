> English | [中文](../quickstart.md)

# Migrating from Spring MVC

This guide is for teams with existing Spring MVC projects, explaining how to replace the underlying web framework with this framework.

---

## 1. Replace Dependencies

### 1.1 Modify pom.xml

```xml
<!-- Remove spring-boot-starter-web (embedded Tomcat) -->
<!--
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
-->

<!-- Add this framework's Starter -->
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

### 1.2 Exclude Conflicting Dependencies (if any)

If your project explicitly depends on Tomcat (e.g., `spring-boot-starter-tomcat`), remove it as well.

### 1.3 Verify Startup

Start the project. The following log confirms successful migration:

```
NettyHttpServer  - Netty started on port(s): 8080
```

The framework automatically uses `AnnotationConfigApplicationContext` — no manual configuration required.

---

## 2. Compatible Parts (No Changes Needed)

The following code requires **no modifications** during migration:

### Controllers

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/{id}")
    public Result<User> getUser(@PathVariable Long id) {
        return Result.ok(userService.findById(id));
    }

    @PostMapping
    public Result<User> createUser(@RequestBody @Valid UserCreateReq req) {
        return Result.ok(userService.create(req));
    }

    @GetMapping("/search")
    public Result<List<User>> search(@RequestParam String name,
                                     @RequestParam(defaultValue = "1") Integer page) {
        return Result.ok(userService.search(name, page));
    }
}
```

### Exception Handling

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public Result<Void> handleValidation(ValidationException e) {
        return Result.fail(400, e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(NotFoundException e) {
        return Result.fail(404, e.getMessage());
    }
}
```

### Interceptors

```java
@Component
public class LogInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(WebServerHttpRequest request, WebServerHttpResponse response,
                             Object handler) {
        long start = System.currentTimeMillis();
        request.getRequestContext().setStartTime(start);
        return true;
    }

    @Override
    public void afterCompletion(WebServerHttpRequest request, WebServerHttpResponse response,
                                Object handler, Exception ex) {
        long start = request.getRequestContext().getStartTime();
        long cost = System.currentTimeMillis() - start;
        log.info("[{}] {} cost={}ms", request.getMethod(), request.getURI(), cost);
    }
}
```

### Filters (requires support module)

After adding `spring-web-support`, `javax.servlet.Filter` is automatically adapted via bridging.

---

## 3. Parts Requiring Adjustment

### Servlet API Dependencies

If your controller methods directly use `HttpServletRequest` / `HttpServletResponse`:

**Option 1: Add the support module (recommended, gradual migration)**

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-support</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

Then `HttpServletRequest` / `HttpServletResponse` can still be injected as parameters.

**Option 2: Switch to native framework interfaces**

```java
// Before
public Result<User> getUser(HttpServletRequest req) {
    String token = req.getHeader("Authorization");
    // ...
}

// After
public Result<User> getUser(WebServerHttpRequest request) {
    String token = request.getHeaders().getFirst("Authorization");
    // ...
}
```

### RequestBodyAdvice / ResponseBodyAdvice

Requires the support module. The framework automatically scans and adapts them.

### Static Resource Paths

Spring MVC's static resource configuration (`WebMvcConfigurer.addResourceHandlers`) continues to work. Alternatively, use the `ResourceHandlerRegistration` Bean approach:

```java
@Bean
public ResourceHandlerRegistration resourceHandlerRegistration() {
    return new ResourceHandlerRegistration("/static/**")
            .addResourceLocations("classpath:/public/");
}
```

---

## 4. Configuration Migration

### application.properties

Most Spring Boot configuration properties remain unchanged. Only web-container-related properties need adjustment:

| Property | Description | Change |
|----------|-------------|--------|
| `server.port` | Listening port | Unchanged |
| `server.servlet.context-path` | Context path | Unchanged |
| `server.ssl.*` | SSL configuration | Unchanged |
| `server.http.max-content-length` | Max request body | Unchanged |
| `management.*` | Actuator configuration | Unchanged |

New framework-specific properties:

```properties
# Business thread pool
pool.core-pool-size=50
pool.max-pool-size=200
pool.keep-alive-time=60
# Default execution mode when no @RunInPool: eventloop or pool name (default: default)
pool.default-execute-mode=default

# Validate all Mappings at startup (fail-fast)
server.check-on-startup=true
```

See [Configuration Reference](configuration.md) for the complete list.

---

## 5. FAQ

### Q: Startup fails with ClassNotFoundException related to Servlet container?

Verify that `spring-boot-starter-web` or `spring-boot-starter-tomcat` has been removed from `pom.xml`.

### Q: Does Spring Security still work?

Yes. Spring Security's Filter Chain is bridged via the `spring-web-support` module. `SecurityFilterChain` works normally.

### Q: Which scenarios benefit most from performance improvements?

- Small request scenarios (JSON echo, parameter queries): 1.6~2.1x
- SSE / streaming push: 3.8x
- Resource-constrained environments (1c1g, 2c2g): even larger gap
- Large request/response scenarios: 2.3x (2.31x for 100KB response body)

### Q: Are there any risks after migration?

The framework performs fail-fast validation at startup (`server.check-on-startup=true`). Mapping configuration errors are caught at startup rather than runtime. It is recommended to first validate on non-critical services.

---

## Next Steps

- [Module Details](modules.md) — Understand the framework's internal architecture
- [Extension Points Guide](extensions.md) — Customize filters, interceptors, argument resolvers, etc.
- [Advanced Topics](advanced.md) — Async processing, streaming responses, reactive support
- [Performance Principles](performance-principles.md) — Understand the technology behind the performance