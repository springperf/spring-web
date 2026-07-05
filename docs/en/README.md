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
- **Async Native** — Built-in support for DeferredResult, Callable, SseEmitter, StreamEmitter, Reactive Streams; SSE throughput reaches 4.51x of Tomcat
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

JMH benchmark results on JDK 1.8 + G1GC (1GB heap):

| Scenario | perf throughput | vs Tomcat | vs Undertow | vs WebFlux |
|----------|---------------|-----------|-------------|-------------|
| helloGet | **30,697** ops/s | **2.05x** | **1.90x** | **1.88x** |
| jsonEcho | **28,831** ops/s | **1.80x** | **1.82x** | **1.63x** |
| bytes | **35,699** ops/s | **1.61x** | **1.59x** | **1.66x** |
| validatePost | **28,599** ops/s | **1.72x** | **1.78x** | **1.66x** |
| largeResponse | **11,796** ops/s | **2.10x** | **1.30x** | **1.32x** |
| sseStream | **1,546** ops/s | **4.51x** | **4.44x** | **1.77x** |
| jsonEchoLarge | **2,669** ops/s | **1.29x** | **1.13x** | **1.07x** |
| asyncDeferredResult | **31,659** ops/s | **2.04x** | **2.22x** | **1.51x** |

The perf framework delivers **1.3~4.5x** throughput over Servlet containers, with **0.11ms** p50 latency (lowest among peers). See [full comparison report](benchmark.md).

---

## Comparison with Spring MVC

| Dimension | Spring Web | Spring MVC (Tomcat) |
|-----------|-----------|---------------------|
| Engine | Netty 4.1 | Servlet container (Tomcat/Jetty/Undertow) |
| Throughput (helloGet) | **30,697** ops/s | 14,949 ops/s (2.05x) |
| P50 Latency (helloGet) | **0.13ms** | 0.26ms |
| Steady-state heap | **15MB** | 20MB |
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

> ¹ Some classes in the support module use `org.springframework.web.servlet` package paths (e.g., `HandlerInterceptor`), intentionally matching Spring WebMVC's official package paths — code written against Spring MVC interfaces can run without import changes. Since Spring Web does not depend on `spring-webmvc`, there is no classpath conflict at runtime. Under Java 9+ module system, depending on both will trigger split package warnings; choose one.

---

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md).

---

## License

[Apache License 2.0](../LICENSE.md)