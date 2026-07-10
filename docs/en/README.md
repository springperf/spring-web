> English | [中文](../../README.md)

# Spring Web

A high-performance Netty-based web framework, designed as a drop-in replacement for Spring MVC.

[![CI](https://github.com/springperf/spring-web/actions/workflows/ci.yml/badge.svg)](https://github.com/springperf/spring-web/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.springperf/spring-web)](https://central.sonatype.com/artifact/io.github.springperf/spring-web)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](../LICENSE.md)

> **Origin**: During performance testing in a 1c1g environment, the same business logic (device data ingestion + validation + Redis/ClickHouse writes) achieved ~**15,000** TPS on the Kafka consumer side, but less than **4,000** TPS on the Spring MVC endpoint. CPU hotspot analysis revealed that the Spring MVC framework itself consumed the majority of CPU cycles — parameter resolution, route matching, reflective invocation — overhead unrelated to business logic yet dominating performance. Spring WebFlux exhibited similar framework-level costs.
>
> This raised a question: what if we eliminated all unnecessary runtime overhead from the mainstream Spring MVC feature set? How much could performance improve?
>
> **Spring Web was born from this question.** Goal: maximize web framework performance while remaining fully compatible with the Spring ecosystem.
>
> [View Benchmark Report](benchmark.md) · [Performance Principles](performance-principles.md) · [Full Origin Story](overview.md)

---

## Introduction

Spring Web is a high-performance web framework built on **Netty 4.1**, designed as a high-performance alternative to Spring MVC. It preserves the familiar Spring programming model (annotation-driven, dependency injection, interceptors, etc.), but replaces the Servlet container with Netty under the hood, delivering higher throughput and lower resource consumption while staying compatible with the Spring ecosystem.

---

## Key Features

- **High Performance** — Pre-caches all metadata at startup, zero reflection and zero matching at runtime; ASM bytecode generation replaces reflective invocation; O(1) HashMap routing; GC-friendly design
- **Netty-Driven** — Built on Netty 4.1 event-driven I/O; requests execute on EventLoop by default, with method-level `@RunInPool` scheduling to business thread pools as needed
- **Spring Ecosystem Compatible** — Supports `@RestController`, `@RequestMapping`, `@Validated`, `@ExceptionHandler`, `HandlerInterceptor`, and other Spring annotations and abstractions — zero-code migration
- **Async Native** — Built-in support for DeferredResult, Callable, SseEmitter, StreamEmitter, Reactive Streams; SSE throughput reaches 3.89x of Spring MVC, scaling to 6.37x under high concurrency
- **Batch Processing** — Disruptor-based request aggregation that transparently merges concurrent requests into batch operations, boosting throughput by multiple times; supports backpressure strategies, wait strategies, and thread pool isolation
- **Extensible** — SPI at every key juncture: argument resolvers, return value handlers, codec interceptors, filters, interceptors
- **Ecosystem Bridge** — The `support` module bridges Servlet Filters, Spring MVC `HandlerInterceptor`, `RequestBodyAdvice` / `ResponseBodyAdvice`
- **Actuator Integration** — Supports Spring Boot Actuator with optional standalone management port

---

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

### 2. Write a Controller

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

### 3. Start

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 4. Configuration

```yaml
server:
  port: 8080
  servlet:
    context-path: /api               # Application context path (optional)
  http:
    max-content-length: 5242880      # Max request body, default 1MB
    timeout: 15000                   # Request timeout, default 60s (milliseconds)
management:
  server:
    port: 8081                       # Actuator standalone management port (optional)
```

> See [Configuration Reference](configuration.md) for the full list.
>
> Migrating from Spring MVC? See the [Migration Guide](quickstart.md).

---

## Version Selection

This project manages two branches aligned with Spring Boot major versions. Minimum supported: **Spring Boot 2.4.x**.

| Branch | Spring Boot | Spring Framework | JDK | Servlet API | Status |
|--------|------------|----------------|-----|-------------|--------|
| `2.7.x` | 2.4.x ~ 2.7.x | 5.3.x | 8 / 11 / 17 | javax.servlet 4.0 | Maintenance branch (features + bugfix) |
| `master` | 3.0.x ~ 3.5.x / 4.0.x ~ 4.1.x | 6.0.x ~ 6.2.x / 7.0.x | 17 / 21 | jakarta.servlet 6.0 | **Development baseline** (multi-version via profiles) |

> See [Version Compatibility](compatibility.md) for version floor notes, branch recommendations, and detailed compatibility information.

---

## Benchmark

> Full report: [Benchmark Document](benchmark.md)
> Performance analysis: [Performance Principles](performance-principles.md)

JMH benchmark results on JDK 1.8 + G1GC (1GB heap, 4 threads):

| API | perf throughput | vs Spring MVC (Tomcat) | vs Spring MVC (Undertow) | vs WebFlux |
|-----|---------------|-----------|-------------|-------------|
| json | **26,718** ops/s | **1.90x** | **2.59x** | **1.73x** |
| get | **27,398** ops/s | **2.19x** | **2.04x** | **2.08x** |
| bytes | **34,232** ops/s | **1.71x** | **2.92x** | **1.99x** |
| valid | **26,706** ops/s | **1.84x** | **1.95x** | **1.71x** |
| async | **28,354** ops/s | **2.11x** | **2.79x** | **1.55x** |
| bytesLarge | **11,508** ops/s | **2.31x** | **1.48x** | **1.49x** |
| sse | **1,226** ops/s | **3.89x** | FAIL¹ | **1.30x** |

The perf framework delivers **1.7~3.9x** throughput over Servlet containers, with **0.12~0.15ms** p50 latency (lowest among peers). SSE scales to **6.37x** Spring MVC under high concurrency. See [full comparison report](benchmark.md).

> ¹ Undertow SSE implementation limitation.

---

## Comparison with Spring MVC

| Dimension | Spring Web | Spring MVC (Tomcat) |
|-----------|-----------|---------------------|
| Engine | Netty 4.1 | Servlet container (Tomcat/Jetty/Undertow) |
| Throughput (json 4t) | **26,718** ops/s | 14,061 ops/s (1.90x) |
| P50 Latency (bytes 4t) | **0.12ms** | 0.19ms |
| Steady-state heap (4t) | **20MB** | 23MB |
| I/O model | Netty non-blocking transport + EventLoop | Servlet blocking I/O + container threads |
| Thread model | EventLoop direct or `@RunInPool` on-demand | Fixed container thread pool |
| Method invocation | ASM / MethodHandle (~10-30ns) | `Method.invoke()` reflection (~200ns) |
| Argument resolution | Pre-cached at startup, direct call at runtime | Per-request iteration + `synchronized` cache |
| Routing | O(1) HashMap multi-level optimizer | `AntPathMatcher` linear traversal |
| Servlet API | Bridged via support module | Native |
| Actuator | Native | Native |

---

## Modules

| Module | Description |
|--------|-------------|
| `spring-web` | Core: Netty server, request dispatch, mapping registration, exception handling |
| `spring-web-support` | Spring MVC compatibility: `HandlerInterceptor`, `View` adapters, etc. ¹ |
| `spring-web-websocket` | WebSocket support: Spring WebSocket + Netty |
| `spring-web-batch` | Batch processing: high-performance message aggregation via Disruptor |
| `spring-boot-starter-web` | Spring Boot Starter: auto-configuration, Actuator support |
| `spring-web-test` | Integration tests |
| `spring-web-support-test` | Spring MVC compatibility tests |
| `spring-web-examples` | Usage examples for various scenarios |

> ¹ Some classes in the support module use `org.springframework.web.servlet` package paths (e.g., `HandlerInterceptor`), intentionally matching Spring WebMVC's official package paths — code written against Spring MVC interfaces can run without import changes. However, this means the support module and `spring-webmvc` **cannot coexist** — having both on the classpath will cause class conflicts at runtime. Under Java 9+ module system this also triggers split package errors. Choose one or the other.
>
> Further reading: [Modules](modules.md) · [Extension Points](extensions.md) · [Advanced Topics](advanced.md)

---

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md).

---

## License

[Apache License 2.0](../LICENSE.md)