> [English](en/quickstart.md) | 中文

# 从 Spring MVC 迁移

本文面向已有 Spring MVC 项目的团队，说明如何将底层 Web 框架替换为本框架。

---

## 一、替换依赖

### 1. 修改 pom.xml

```xml
<!-- 移除 spring-boot-starter-web（内嵌 Tomcat） -->
<!--
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
-->

<!-- 添加本框架的 Starter -->
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

### 2. 排除冲突依赖（如有）

如果项目显式依赖了 Tomcat（如 `spring-boot-starter-tomcat`），也需要移除。

### 3. 验证启动

启动项目，看到以下日志说明迁移成功：

```
NettyHttpServer  - Netty started on port(s): 8080
```

框架自动使用 `AnnotationConfigApplicationContext`，无需手动配置。

---

## 二、兼容的部分（无需改动）

以下代码在迁移中**不需要做任何修改**：

### 控制器

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

### 异常处理

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

### 拦截器

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

### 过滤器（需 support 模块）

需要引入 `spring-web-support` 后，`javax.servlet.Filter` 通过桥接自动适配。

---

## 三、需要调整的部分

### Servlet API 依赖

如果控制器方法中直接使用了 `HttpServletRequest` / `HttpServletResponse`，需要：

**方法一：引入 support 模块（推荐，渐进迁移）**

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-support</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

然后 `HttpServletRequest` / `HttpServletResponse` 依然可以作为参数注入。

**方法二：改用框架原生接口**

```java
// 之前
public Result<User> getUser(HttpServletRequest req) {
    String token = req.getHeader("Authorization");
    // ...
}

// 之后
public Result<User> getUser(WebServerHttpRequest request) {
    String token = request.getHeaders().getFirst("Authorization");
    // ...
}
```

### RequestBodyAdvice / ResponseBodyAdvice

需引入 support 模块，框架自动扫描并适配。

### 静态资源路径

Spring MVC 的静态资源配置（`WebMvcConfigurer.addResourceHandlers`）可继续使用。也可以改用 `ResourceHandlerRegistration` Bean 方式，效果相同：

```java
@Bean
public ResourceHandlerRegistration resourceHandlerRegistration() {
    return new ResourceHandlerRegistration("/static/**")
            .addResourceLocations("classpath:/public/");
}
```

---

## 四、配置迁移

### application.properties

大部分 Spring Boot 配置项不变，仅 Web 容器相关配置需调整：

| 配置项 | 说明 | 变化 |
|--------|------|------|
| `server.port` | 监听端口 | 不变 |
| `server.servlet.context-path` | 上下文路径 | 不变 |
| `server.ssl.*` | SSL 配置 | 不变 |
| `server.http.max-content-length` | 最大请求体 | 不变 |
| `management.*` | Actuator 配置 | 不变 |

新增的本框架特有配置：

```properties
# 业务线程池
pool.core-pool-size=50
pool.max-pool-size=200
pool.keep-alive-time=60
# 无 @RunInPool 时的默认执行位置：eventloop 或线程池名称（默认 default）
pool.default-execute-mode=default

# 启动时校验所有 Mapping（fail-fast）
server.check-on-startup=true
```

完整配置项见[配置参考](configuration.md)。

---

## 五、常见问题

### Q: 启动报错与 Servlet 容器相关的 ClassNotFoundException

确认 `pom.xml` 中已移除 `spring-boot-starter-web` 或 `spring-boot-starter-tomcat`。

### Q: Spring Security 还能用吗？

能。Spring Security 的 Filter Chain 通过 `spring-web-support` 模块桥接，`SecurityFilterChain` 可正常工作。

### Q: 性能提升在什么场景最明显？

- 小请求场景（JSON 回显、参数查询）：1.6~2.1x
- SSE / 流式推送：3.8x
- 资源受限环境（1c1g、2c2g）：差距更大
- 大请求体 / 大响应场景：2.3x（100KB 响应体仍达 2.31x）

### Q: 迁移后有风险吗？

框架在启动时会做 fail-fast 校验（`server.check-on-startup=true`），Mapping 配置错误会在启动阶段发现而非运行时。建议先在非核心业务上灰度验证。

---

## 下一步

- [模块详解](modules.md) — 了解框架内部架构
- [扩展点指南](extensions.md) — 自定义过滤器、拦截器、参数解析器等
- [高级主题](advanced.md) — 异步处理、流式响应、响应式支持
- [性能原理](performance-principles.md) — 了解性能优化背后的技术