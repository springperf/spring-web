# Spring Web

基于 Netty 的高性能 Web 框架，Spring MVC 的替代方案。

[![CI](https://github.com/springperf/spring-web/actions/workflows/ci.yml/badge.svg)](https://github.com/springperf/spring-web/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.springperf/spring-web)](https://central.sonatype.com/artifact/io.github.springperf/spring-web)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE.md)

> **缘起**：一次 1c1g 环境的性能测试中，同样的业务逻辑（设备数据上报 + 校验 + Redis/ClickHouse 写入），Kafka 消费端 TPS 接近 **15,000**，而 Spring MVC 接口不到 **4,000**。CPU 热点分析显示，Spring MVC 框架自身消耗了大量 CPU——参数解析、路由匹配、反射调用……这些开销与业务无关，却吞噬了绝大部分性能。Spring WebFlux 也存在类似的框架层开销。
>
> 这引发了一个思考：如果把 Spring MVC 主流功能中那些不必要的运行时开销全部消除，性能能提升多少？
>
> **Spring Web 由此而生。** 目标：在兼容 Spring 生态的前提下，最大程度释放 Web 框架的性能。
>
> [查看 Benchmark 报告](docs/benchmark.md) · [性能原理详解](docs/performance-principles.md) · [项目缘起全文](docs/overview.md)

---

## 简介

Spring Web 是一个基于 **Netty** 构建的高性能 Web 框架，定位为 Spring MVC 的高性能替代方案。它保留了 Spring 开发者熟悉的编程模型（注解驱动、依赖注入、拦截器等），但底层使用 Netty 替代 Servlet 容器，在兼容 Spring 生态的前提下提供更高的吞吐量和更低的资源占用。

---

## 版本选择

本项目按 Spring Boot 大版本管理两个分支。`master` 为开发基线，新功能优先合入后再同步到 `3.2.x`。

| 分支 | Spring Boot | Spring Framework | JDK | Servlet API | 状态 |
|------|------------|----------------|-----|-------------|------|
| `master` / `2.7.x` | 2.7.x | 5.3.x | 8 / 11 / 17 | javax.servlet 4.0 | **开发基线**（功能迭代 + bugfix） |
| `3.2.x` | 3.2.x | 6.1.x | 17 / 21 | jakarta.servlet 6.0 | 主力版本（从 master fork，适配 Jakarta） |

> 详细兼容性信息见 [版本兼容性说明](docs/compatibility.md)。
>
> **分支选择建议**：现有 Servlet 项目、JDK 8/11 选 `master`/`2.7.x`；新项目或 JDK 17+ 选 `3.2.x`（支持虚拟线程、GraalVM native-image）。

### 核心特性

- **高性能** — 启动时预缓存全部元数据，运行时零反射零匹配；ASM 字节码生成替代反射调用；O(1) HashMap 路由；GC 友好设计
- **Netty 驱动** — 基于 Netty 4.1 事件驱动 I/O，请求默认在 EventLoop 处理，可按方法粒度通过 `@RunInPool` 调度到业务线程池
- **Spring 生态兼容** — 支持 `@RestController`、`@RequestMapping`、`@Validated`、`@ExceptionHandler`、`HandlerInterceptor` 等 Spring 注解与抽象，零侵入迁移
- **异步原生** — 内置 DeferredResult、Callable、SseEmitter、StreamEmitter、Reactive Streams 支持，SSE 吞吐达 Tomcat 的 4.51x
- **灵活扩展** — 参数解析器、返回值处理器、编解码 Advice、拦截器、过滤器等关键节点均提供 SPI
- **生态桥接** — 可通过 support 模块桥接 Servlet Filter、Spring MVC `HandlerInterceptor`、`RequestBodyAdvice` / `ResponseBodyAdvice`
- **Actuator 集成** — 支持 Spring Boot Actuator，可配置独立管理端口

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <version>${spring-web.version}</version>
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

## 基准测试

> 详细报告见 [Benchmark 文档](docs/benchmark.md)
> 性能原理分析见 [性能原理文档](docs/performance-principles.md)

基于 JDK 1.8 + G1GC (1GB heap) 的 JMH 基准测试结果：

| 场景 | perf 吞吐 | vs Tomcat | vs Undertow | vs WebFlux |
|------|-----------|-----------|-------------|-------------|
| helloGet | **30,697** ops/s | **2.05x** | **1.90x** | **1.88x** |
| jsonEcho | **28,831** ops/s | **1.80x** | **1.82x** | **1.63x** |
| bytes | **35,699** ops/s | **1.61x** | **1.59x** | **1.66x** |
| validatePost | **28,599** ops/s | **1.72x** | **1.78x** | **1.66x** |
| largeResponse | **11,796** ops/s | **2.10x** | **1.30x** | **1.32x** |
| sseStream | **1,546** ops/s | **4.51x** | **4.44x** | **1.77x** |
| jsonEchoLarge | **2,669** ops/s | **1.29x** | **1.13x** | **1.07x** |
| asyncDeferredResult | **31,659** ops/s | **2.04x** | **2.22x** | **1.51x** |

perf 框架吞吐是 Servlet 容器的 **1.3~4.5x**，p50 延迟 **0.11ms**（同类框架最低）。详情见 [完整对比报告](docs/benchmark.md)。

---

## 与 Spring MVC 对比

| 维度 | Spring Web | Spring MVC (Tomcat) |
|------|-----------|---------------------|
| 底层引擎 | Netty 4.1 | Servlet 容器（Tomcat/Jetty/Undertow） |
| 吞吐量 (helloGet) | **30,697** ops/s | 14,949 ops/s (2.05x) |
| P50 延迟 (helloGet) | **0.13ms** | 0.26ms |
| 稳态堆占用 | **15MB** | 20MB |
| I/O 模型 | Netty 非阻塞传输 + EventLoop 处理 | Servlet 阻塞 I/O + 容器线程 |
| 线程模型 | EventLoop 直接处理或 `@RunInPool` 按需切换 | 固定容器线程池 |
| 方法调用 | ASM / MethodHandle（~10-30ns） | `Method.invoke()` 反射（~200ns） |
| 参数解析 | 启动时预缓存，运行时直接调用 | 每次请求遍历 + `synchronized` 缓存 |
| 路由 | O(1) HashMap 多级优化器 | `AntPathMatcher` 线性遍历 |
| Servlet API | 通过 support 模块桥接 | 原生支持 |
| Actuator | 原生支持 | 原生支持 |

---

## 模块说明

| 模块 | 说明 | 依赖范围 |
|------|------|---------|
| `spring-web` | 核心模块：Netty 服务器、请求分发、映射注册、异常处理等 | 编译 |
| `spring-web-support` | Spring MVC 兼容模块：提供 `HandlerInterceptor`、`View` 等适配类 ¹ | provided |
| `spring-boot-starter-web` | Spring Boot Starter：自动配置、Actuator 支持 | 编译 |
| `spring-web-test` | 集成测试模块：框架功能验证 | - |
| `spring-web-support-test` | Spring MVC 兼容测试模块 | - |

> ¹ support 模块中部分类使用了 `org.springframework.web.servlet` 包路径（如 `HandlerInterceptor`），与 Spring WebMVC 官方包路径相同。这是有意为之——基于 Spring MVC 接口编写的代码可不改 import 直接运行。由于 Spring Web 不依赖 `spring-webmvc`，运行时不存在类冲突。Java 9+ 模块化系统下同时依赖二者会触发 split package 警告，建议二选一。

---

## 如何贡献

请参阅 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

## 许可证

[Apache License 2.0](LICENSE.md)