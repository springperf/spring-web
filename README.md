# Spring Web

基于 Netty 的高性能 Web 框架，Spring MVC 的替代方案。

[![CI](https://github.com/springperf/spring-web/actions/workflows/ci.yml/badge.svg)](https://github.com/springperf/spring-web/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.springperf/spring-web)](https://central.sonatype.com/artifact/io.github.springperf/spring-web)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE.md)

---

## 简介

Spring Web 是一个基于 **Netty** 构建的高性能 Web 框架，定位为 Spring MVC 的轻量级替代方案。它保留了 Spring 开发者熟悉的编程模型（注解驱动、依赖注入、拦截器等），但底层使用 Netty 替代 Servlet 容器，提供更高的吞吐量和更低的资源占用。

### 核心特性

- **Netty 驱动**：基于 Netty 4.1 的事件驱动架构，非阻塞 I/O
- **Spring 生态兼容**：支持 Spring 注解（`@RestController`、`@RequestMapping`、`@ExceptionHandler` 等）
- **高性能**：相比传统 Servlet 容器，资源占用更低、吞吐量更高
- **独立管理端口**：内置 Actuator 支持，可配置独立的管理端口
- **灵活扩展**：支持拦截器、过滤器、异常处理器、参数解析器等扩展点

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <version>1.0.1</version>
</dependency>
```

### 2. 编写 Controller

```java
@RestController
@RequestMapping("/api")
public class HelloController {

    @GetMapping("/hello/{name}")
    public String hello(@PathVariable String name) {
        return "Hello, " + name;
    }

    @PostMapping("/echo")
    public ApiResult<?> echo(@RequestBody Map<String, Object> body) {
        return ApiResult.success(body);
    }
}
```

### 3. 启动

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 4. 配置

```yaml
  server:
    port: 8080
    boss-threads: 2
    worker-threads: 16
  management:
    server:
      port: 8081  # 独立管理端口（可选）
```

---

## 与 Spring MVC 对比

| 特性 | Spring web | Spring MVC |
|------|---------------|------------|
| 底层引擎 | Netty 4.1 | Servlet 容器（Tomcat/Jetty/Undertow） |
| I/O 模型 | 非阻塞事件驱动 | 同步请求-响应 |
| 编程模型 | 注解驱动（兼容 Spring MVC 注解） | 注解驱动 |
| 依赖注入 | Spring 容器 | Spring 容器 |
| Servlet API | 通过 support 模块兼容 | 原生支持 |
| Actuator | 支持 | 支持 |

---

## 模块说明

| 模块 | 说明 | 依赖范围 |
|------|------|---------|
| `spring-web` | 核心模块：Netty 服务器、请求分发、映射注册、异常处理等 | 编译 |
| `spring-web-support` | Servlet 兼容模块：提供 `HandlerInterceptor`、`View` 等 Servlet 适配类 | provided |
| `spring-boot-starter-web` | Spring Boot Starter：自动配置、Actuator 支持 | 编译 |
| `spring-web-test` | 集成测试模块：框架功能验证 | - |
| `spring-web-support-test` | Servlet 兼容测试模块 | - |

---

## 依赖说明

| 依赖 | 版本 | 范围 |
|------|------|------|
| Netty 4.1 | 4.1.110.Final | 编译 |
| Jackson 2.17 | 2.17.2 | 编译 |
| Lombok | 1.18.24 | provided |
| Fastjson2 | 2.0.60 | provided（可选） |
| Reactive Streams | - | provided |
| Servlet API | 4.0.1 | provided（仅在 support 模块） |

---

## 架构说明

### Split Package 说明

`spring-web-support` 模块中的部分类使用了 `org.springframework.web.servlet` 包路径（如 `HandlerInterceptor`、`ModelAndView`、`View` 等），这与 Spring WebMVC 框架的官方包路径相同。这是有意为之的设计——使得基于 Spring MVC 接口编写的代码可以在不修改 import 的情况下运行在 web 框架上。

**重要**：由于 SpringWeb 不依赖 `spring-webmvc` 模块，因此在实际运行时不存在类冲突。在 Java 9+ 模块化系统下，如果同时依赖 `spring-webmvc` 和 `spring-web-support`，可能会触发 split package 警告。建议在项目中只选择 SpringWeb 或 Spring MVC 其中之一作为 Web 框架。

```bash
mvn clean package
mvn clean test
```

---

## 基准测试

> 详细报告见 [Benchmark 文档](docs/benchmark.md)

基于 JDK 1.8 + G1GC (1GB heap) 的 JMH 基准测试结果：

| 场景 | perf 吞吐 | vs Tomcat | vs Undertow | vs WebFlux |
|------|-----------|-----------|-------------|-------------|
| helloGet | **28,237** ops/s | **1.76x** | **1.68x** | **1.59x** |
| jsonEcho | **28,053** ops/s | **1.66x** | **1.67x** | **1.48x** |
| bytes | **30,624** ops/s | **1.30x** | **1.34x** | **1.31x** |
| validatePost | **26,292** ops/s | **1.52x** | **1.49x** | **1.36x** |
| largeResponse | **11,633** ops/s | **2.01x** | **1.14x** | **1.13x** |
| sseStream | **1,432** ops/s | **3.85x** | **3.89x** | **1.43x** |
| jsonEchoLarge | **2,814** ops/s | **1.25x** | **1.12x** | **1.14x** |
| asyncDeferredResult | **29,157** ops/s | **1.78x** | **1.97x** | **1.26x** |

perf 框架吞吐是 Servlet 容器的 **1.3~3.9x**，p50 延迟 **0.13ms**（同类框架最低）。详情见 [完整对比报告](docs/benchmark.md)。


## 如何贡献

请参阅 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

## 许可证

[Apache License 2.0](LICENSE.md)
