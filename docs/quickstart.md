# 快速上手

## 环境要求

- JDK 8+
- Spring Boot 2.6.x
- Maven 3.5+

## 添加依赖

在 `pom.xml` 中替换 Spring MVC 依赖：

```xml
<!-- 移除 spring-boot-starter-web（内嵌 Tomcat） -->
<!--
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
-->

<!-- 添加 spring-boot-starter-web -->
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

框架会自动装配，无需额外配置类。

## 编写控制器

完全兼容 Spring MVC 的注解风格：

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

## 配置

在 `application.properties` 中配置：

```properties
# 基本配置
server.port=8080
server.servlet.context-path=/api

# 请求体大小限制（默认 1MB）
server.http.max-content-length=2097152

# 请求超时（毫秒，默认无超时）
server.http.timeout=30000

# 业务线程池
pool.core-pool-size=50
pool.max-pool-size=200
pool.keep-alive-time=60
```

完整配置项见[配置参考](configuration.md)。

## 启动应用

与普通 Spring Boot 应用完全相同：

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

框架自动使用 `AnnotationConfigApplicationContext`（非 Servlet 容器），启动后可看到：

```
NettyHttpServer  - Netty started on port(s): 8080
```

## 使用拦截器

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

## 使用过滤器

```java
@Component
public class AccessLogFilter implements WebFilter {

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response,
                         FilterChain chain) {
        log.info("→ {} {}", request.getMethod(), request.getURI());
        chain.doFilter(request, response);
        log.info("← {} {}", response.getStatusCode(), request.getURI());
    }
}
```

## 异常处理

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

## 下一步

- 阅读[模块详解](modules.md)了解框架架构
- 阅读[扩展点指南](extensions.md)了解如何自定义
- 阅读[高级主题](advanced.md)了解异步和流式支持