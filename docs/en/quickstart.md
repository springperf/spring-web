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

### 1.2 Choose a Compatibility Bridge Module (Two Migration Paths)

After replacing the starter, choose which compatibility module(s) to add based on your business code's dependency on Spring ecosystem APIs. **The two paths can be switched at any time** — start with Path 1, and add Path 2's module only if you hit `org.springframework.web.servlet.*` compile errors.

**Path 1: add only `spring-web-servlet` (pure Servlet compatibility)**

Best for: business code that depends only on **standard annotations + Servlet API** (`HttpServletRequest` / `HttpServletResponse` / `Filter` / `HttpSession` / JSP), and does not use Spring MVC ecosystem APIs (`WebMvcConfigurer`, `org.springframework.web.servlet.HandlerInterceptor`, `RequestBodyAdvice`, etc.).

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-servlet</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

- ✅ **Pros**: a clean classpath — **no rewritten `org.springframework.web.servlet.*` classes**, no naming conflict with the official Spring MVC ecosystem; minimal dependencies and footprint.
- ⚠️ **Cons**: Spring MVC high-level APIs (`WebMvcConfigurer`, Spring MVC `HandlerInterceptor`, `RequestBodyAdvice` / `ResponseBodyAdvice`, `ModelAndView`, `HandlerMethodArgumentResolver`) are **unavailable**; business code depending on them must switch to the framework's native SPI (`io.springperf.web.core.interceptor.HandlerInterceptor`, `WebFilter`, `ReturnValueResolver`, `StaticArgumentResolverProvider`).

**Path 2: add `spring-web-servlet` + `spring-web-mvc-support` (Servlet + Spring MVC compatibility)**

Best for: business code that heavily uses Spring MVC ecosystem APIs and wants the **minimum migration effort**.

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-mvc-support</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```
(`spring-web-mvc-support` transitively depends on `spring-web-servlet`; no explicit addition needed.)

- ✅ **Pros**: `WebMvcConfigurer`, Spring MVC `HandlerInterceptor`, `RequestBodyAdvice` / `ResponseBodyAdvice`, `ModelAndView`, `HandlerMethodArgumentResolver` keep working (via adapters or rewritten classes), so migration changes are minimal.
- ⚠️ **Cons**: rewritten `org.springframework.web.servlet.*` classes are packaged (same package/name, loaded in preference on the classpath), so it **cannot coexist with the official `spring-webmvc`** — the framework detects the conflict at startup and throws `IllegalStateException` (this is why `spring-boot-starter-web` must be removed); larger dependency and footprint.

**How to decide**

- Most pure-REST projects (annotation controllers + standard argument binding + custom Filters) go with **Path 1**.
- Only add **Path 2** when business code actually references types under `org.springframework.web.servlet.*` (e.g., `HandlerInterceptor`, `WebMvcConfigurer`, `RequestBodyAdvice`).

---

### 1.3 Exclude Conflicting Dependencies (if any)

If your project explicitly depends on Tomcat (e.g., `spring-boot-starter-tomcat`), remove it as well.

### 1.4 Verify Startup

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

After adding `spring-web-servlet`, `jakarta.servlet.Filter` is automatically adapted via bridging.

---

## 3. Parts Requiring Adjustment

### Servlet API Dependencies

If your controller methods directly use `HttpServletRequest` / `HttpServletResponse`:

**Option 1: Add the support module (recommended, gradual migration)**

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-servlet</artifactId>
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

Requires the `spring-web-mvc-support` module. The framework automatically scans and adapts them.

### Static Resource Paths

Spring MVC's static resource configuration (`WebMvcConfigurer.addResourceHandlers`) continues to work. Alternatively, use the `ResourceHandlerRegistration` Bean approach:

```java
@Bean
public ResourceHandlerRegistration resourceHandlerRegistration() {
    return new ResourceHandlerRegistration("/static/**")
            .addResourceLocations("classpath:/public/");
}
```

### View Rendering (Thymeleaf / FreeMarker page-oriented projects)

Page-oriented (server-side rendered) projects need the `spring-web-view` module:

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-view</artifactId>
    <version>${spring-web.version}</version>
</dependency>
<dependency>
    <groupId>org.thymeleaf</groupId>
    <artifactId>thymeleaf</artifactId>
</dependency>
```

**Migrates without changes:**

- `@Controller` methods returning `String` without `@ResponseBody` → view names
- `Model` / `ModelMap` parameter injection, `ModelAndView` returns
- `redirect:` prefix
- `@ExceptionHandler` returning view names to render error pages
- Thymeleaf `${...}` / `#{...}` / `@{...}` / `th:*` expressions

**Requires adaptation:**

| Spring MVC pattern | This framework behavior | Mitigation |
|--------------------|-------------------------|------------|
| `forward:` prefix | Not supported | Use `redirect:` or return the view directly |
| Thymeleaf `#session` / `#request` | Unavailable | Use model attributes |
| `redirect:/orders/{id}` path-variable template | Not supported | Concatenate into query explicitly |

**Already aligned, no changes needed**: `@ModelAttribute` parameter auto-merged into model, `@ControllerAdvice @ModelAttribute` providers, `@PathVariable` auto-merge, `BindingResult` auto-merge, local `@ModelAttribute` methods, `redirect:` prefix.

> Full difference list: [View Rendering](view.md#5-differences-from-spring-mvc).

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

Yes. Spring Security's Filter Chain is bridged via the `spring-web-servlet` module. `SecurityFilterChain` works normally.

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