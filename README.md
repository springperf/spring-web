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

以下是 Spring Web（自定义 Netty 框架）与主流 Web 服务器的 JMH 基准测试对比结果。

### 测试环境

| 项目 | 配置 |
|------|------|
| **CPU** | Intel(R) Core(TM) i7-10750H (12 核) |
| **内存** | 32 GB |
| **JDK** | OpenJDK 17.0.9 (LTS) |
| **JVM 参数** | -Xms1g -Xmx1g -XX:+UseG1GC |
| **JMH 版本** | 1.37 |
| **测试模式** | Throughput (ops/s) |
| **Warmup** | 5 轮 × 10 秒 |
| **Measurement** | 10 轮 × 10 秒 |
| **线程数** | 8 并发线程 |
| **操作系统** | Windows 11 |

### 测试结果

| 基准测试方法 | Perf (Netty) | Perf-Support | Tomcat | Undertow | WebFlux |
|---|---|---|---|---|---|
| **asyncDeferredResult** | **45112.39** ± 4658.30 | - | 72.66 ± 0.12 ⚠ | 72.68 ± 0.15 ⚠ | 7526.95 ± 523.65 ⚠ |
| **bytes** | 29436.49 ± 1774.92 | - | 36907.89 ± 1045.75 | 35677.17 ± 1327.08 | 27499.06 ± 3099.43 |
| **helloGet** | **44209.57** ± 1439.76 | - | 25881.04 ± 458.73 | 25204.16 ± 1122.10 | 24312.66 ± 4540.15 |
| **jsonEcho** | **39837.99** ± 3329.42 | - | 23174.73 ± 803.03 | 23281.38 ± 458.02 | 22561.51 ± 4440.82 |
| **validatePost** | **39317.89** ± 2760.81 | - | 24556.71 ± 221.39 | 23693.45 ± 597.76 | 24870.08 ± 2191.92 |

### Filter/Interceptor 测试结果

| 基准测试方法 | Perf-Filter | Perf-Support-Filter | Tomcat-Filter | Undertow-Filter | WebFlux-Filter |
|---|---|---|---|---|---|
| **asyncDeferredResult** | - | - | - | - | - |
| **bytes** | - | - | - | - | - |
| **helloGet** | - | - | - | - | - |
| **jsonEcho** | - | - | - | - | - |
| **validatePost** | - | - | - | - | - |

> 单位：ops/s（每秒操作数），数值越高越好。`±` 为 99.9% 置信区间。粗体为每行最优值。`-` 表示尚未运行基准测试。
> ⚠ 标记的列为旧数据（端点在修复前包含 `Thread.sleep(100)` 人为延迟），需要重新跑基准测试刷新。

### 结果分析

1. **helloGet / jsonEcho**：Perf（Netty）领先于所有 Servlet 容器，吞吐量约为 Servlet 容器的 **1.5-1.7x**，体现了 Netty 事件驱动模型在简单请求处理上的优势。

2. **bytes（字节数组响应）**：Tomcat 表现最佳（36907 ops/s）。字节响应的序列化开销较低，容器间的差异主要来自 I/O 调度策略。

3. **asyncDeferredResult**：修复了 `Thread.sleep(100)` 人为瓶颈后，Perf 的异步吞吐达到 **45112 ops/s**，与同步 helloGet 持平，说明异步调度的开销极低。Servlet 容器的数据仍为修复前的旧值（~72 ops/s），需刷新。

4. **validatePost**：Perf 表现优异（39317 ops/s），领先于所有 Servlet 容器。参数校验的开销在各容器间差异不大。

5. **WebFlux**：在响应式异步场景下优势明显。当前数据为旧值，需重新跑基准测试刷新。

### 运行方式

> **注意**：不同 profile 间需要用 `mvn clean` 清理，防止类文件污染导致结果混淆。使用 `-Dbenchmark.include` 指定当前 profile 的基准测试类，确保只运行目标测试。

```bash
# 1. 编译当前 profile
mvn clean install -pl spring-web-benchmark -am -Pbenchmark-perf -DskipTests -q

# ==================== 基线 Profiles ====================

# Perf (Netty)
mvn exec:java -pl spring-web-benchmark -Pbenchmark-perf \
  -Dexec.mainClass="io.springperf.benchmark.BenchmarkRunner" \
  -Dexec.classpathScope="runtime" \
  -Dbenchmark.include=".*PerfBenchmark"

# Perf + Support（验证 support 模块引入的影响）
mvn exec:java -pl spring-web-benchmark -Pbenchmark-perf-support \
  -Dexec.mainClass="io.springperf.benchmark.BenchmarkRunner" \
  -Dexec.classpathScope="runtime" \
  -Dbenchmark.include=".*PerfSupportBenchmark" \
  -Dbenchmark.port=9093

# Tomcat (需先 mvn clean install -Pbenchmark-tomcat)
mvn exec:java -pl spring-web-benchmark -Pbenchmark-tomcat \
  -Dexec.mainClass="io.springperf.benchmark.BenchmarkRunner" \
  -Dexec.classpathScope="runtime" \
  -Dbenchmark.include=".*TomcatBenchmark" \
  -Dbenchmark.port=9091

# Undertow (需先 mvn clean install -Pbenchmark-undertow)
mvn exec:java -pl spring-web-benchmark -Pbenchmark-undertow \
  -Dexec.mainClass="io.springperf.benchmark.BenchmarkRunner" \
  -Dexec.classpathScope="runtime" \
  -Dbenchmark.include=".*UndertowBenchmark" \
  -Dbenchmark.port=9092

# WebFlux (需先 mvn clean install -Pbenchmark-webflux)
mvn exec:java -pl spring-web-benchmark -Pbenchmark-webflux \
  -Dexec.mainClass="io.springperf.benchmark.BenchmarkRunner" \
  -Dexec.classpathScope="runtime" \
  -Dbenchmark.include=".*WebFluxBenchmark" \
  -Dbenchmark.port=9099

# ==================== Filter/Interceptor Profiles ====================

# Perf + Filter（5 Perf WebFilter + 3 Perf HandlerInterceptor）
mvn exec:java -pl spring-web-benchmark -Pbenchmark-perf-filter \
  -Dexec.mainClass="io.springperf.benchmark.BenchmarkRunner" \
  -Dexec.classpathScope="runtime" \
  -Dbenchmark.include=".*PerfFilterBenchmark" \
  -Dbenchmark.port=9095

# Perf + Support + Filter（5 Servlet Filter + 3 Spring MVC Interceptor，通过 support 桥接）
mvn exec:java -pl spring-web-benchmark -Pbenchmark-perf-support-filter \
  -Dexec.mainClass="io.springperf.benchmark.BenchmarkRunner" \
  -Dexec.classpathScope="runtime" \
  -Dbenchmark.include=".*PerfSupportFilterBenchmark" \
  -Dbenchmark.port=9094

# Tomcat + Filter（5 Servlet Filter + 3 Spring MVC Interceptor）
mvn exec:java -pl spring-web-benchmark -Pbenchmark-tomcat-filter \
  -Dexec.mainClass="io.springperf.benchmark.BenchmarkRunner" \
  -Dexec.classpathScope="runtime" \
  -Dbenchmark.include=".*TomcatFilterBenchmark" \
  -Dbenchmark.port=9096

# Undertow + Filter（5 Servlet Filter + 3 Spring MVC Interceptor）
mvn exec:java -pl spring-web-benchmark -Pbenchmark-undertow-filter \
  -Dexec.mainClass="io.springperf.benchmark.BenchmarkRunner" \
  -Dexec.classpathScope="runtime" \
  -Dbenchmark.include=".*UndertowFilterBenchmark" \
  -Dbenchmark.port=9097

# WebFlux + Filter（8 WebFlux WebFilter）
mvn exec:java -pl spring-web-benchmark -Pbenchmark-webflux-filter \
  -Dexec.mainClass="io.springperf.benchmark.BenchmarkRunner" \
  -Dexec.classpathScope="runtime" \
  -Dbenchmark.include=".*WebFluxFilterBenchmark" \
  -Dbenchmark.port=9098
```

---

## 如何贡献

请参阅 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

## 许可证

[Apache License 2.0](LICENSE.md)
