> English | [中文](../compatibility.md)

# Version Compatibility

This project maintains three branches: one mainline (Spring Boot 3.5.x) and two downstream
adaptation branches (2.7.x / 4.1.x).

---

## Branch Overview

| Branch | Spring Boot | Status | Maintenance Strategy |
|--------|-------------|--------|---------------------|
| `2.7.x` | 2.4.x ~ 2.7.x | Downstream adaptation | Synced from master + downgrade adaptation; based on javax.servlet |
| `master` | **3.5.x** | **Development baseline** | New features merged here first; **contains no Spring Boot 4 / Spring Framework 7 compatibility code** |
| `4.1.x` | 4.0.x ~ 4.1.x | Downstream adaptation | Synced from master + upgrade adaptation |

Sync direction is always `master → 4.1.x` / `master → 2.7.x`; version adaptation code lives only
in the downstream branches.

The `4.1.x` branch has its own project version, independent of the mainline; its first release **`4.1.7`** is
already out. Its `spring-boot.version` (Boot 4.1.0) is unrelated to the project version.

---

## Version Floor Note

The project previously attempted compatibility with Spring Boot 2.3.x (Spring Framework 5.2.x), but Spring 5.2 lacks APIs such as `MultiValueMapAdapter`, `getSupportedMediaTypes(Class)` and has restrictions around `MethodHandles.lookup()` for package-private methods. These issues made maintenance cost outweigh benefits, so **2.3.x and below are no longer supported** — the previously implemented compatibility code has been cleaned up.

---

## 2.7.x Branch

### Version Matrix

| Dependency | Current Version | Verified Range | Notes |
|------------|----------------|----------------|-------|
| Spring Boot | **2.7.18** | 2.4.x ~ 2.7.x | Switch via Maven profile (`-Pspring-boot-2.4` ~ `-Pspring-boot-2.7`) |
| Spring Framework | **5.3.x** | Managed by Spring Boot | |
| JDK | **8, 11, 17** | 8, 11, 17 verified | Compile target `java.version=8` |
| Servlet API | **javax.servlet 4.0.1** | 4.0.x | |
| Netty | **4.1.137.Final** | 4.1.x (manually overridden) | Spring Boot 2.7.x manages a lower version by default |
| Jackson | **2.17.2** | 2.17.x (manually overridden) | Spring Boot 2.7.x manages 2.13.x by default |
| Lombok | **1.18.24** | 1.18.x | |
| JMH | **1.37** | 1.37 | Benchmark module only |

### Key Limitations

- **`jakarta.servlet` not supported**: 2.7.x is based on `javax.servlet`, incompatible with Jakarta EE
- **No virtual thread support**: JDK 8/11 don't have virtual thread capabilities
- **No GraalVM native-image**: AOT compilation is a master branch feature

---

## master Branch

### Version Matrix

| Dependency | Current Version | Verified Range | Notes |
|------------|----------------|----------------|-------|
| Spring Boot | **3.5.16** | 3.2.x ~ 3.5.x (core + starter tests) | Switch via Maven profile (`-Pspring-boot-3.2` ~ `-Pspring-boot-3.5`); for 4.x see the `4.1.x` branch; E2E coverage is described under "Cross-version Sensitive Points" below |
| Spring Framework | **6.2.x** | 6.1.x ~ 6.2.x | Managed by Spring Boot |
| JDK | **17** | 17 / 21 / 25 (CI matrix, see [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)) | Compile target `java.version=17`; JDK 21+ supports virtual threads (see below) |
| Servlet API | **jakarta.servlet 6.0** | 6.0.x | javax.servlet incompatible |
| Netty | **4.1.137.Final** | 4.1.x | |
| Jackson | **2.17.2** | 2.17.x | |
| Lombok | **1.18.46** | 1.18.30+ | Higher version needed for JDK 17+ compatibility |
| JMH | **1.37** | 1.37 | Benchmark module only |

> **Why 3.0 / 3.1 are out of scope**: both map to Spring Framework 6.0, which lacks the
> `ConversionService.convert(Object, TypeDescriptor)` overload this project uses (added in 6.1). Building
> against 3.0/3.1 fails at compile time; building against 3.5 and executing on a 6.0 runtime fails at
> `@RequestParam` / `@PathVariable` conversion with `NoSuchMethodError` (an `Error`, which
> `@ExceptionHandler` cannot catch). Neither direction works, so the range starts at 3.2. Coverage is
> verified by tests on Spring Boot 3.2 / 3.3 / 3.4; a green `compile` says nothing about runtime symbol
> resolution.

### Cross-version Sensitive Points

The cross-version risk lives in the starter auto-configuration layer, so the verification covers the starter module, not just the core.

| Sensitive point | Risk | Covered by | Fallback |
|-----------------|------|------------|----------|
| `ApiVersion` (internal actuator enum `V2/V3/LATEST`) | Renamed / revalued in a 3.x minor | starter tests + condition tests | Fails at compile time, no silent degradation |
| `DelegatingFilterProxyRegistrationBean#getTargetBeanName()` (protected reflection) | A Boot signature change would silently drop filters | Filter bridge tests | Returns null, skips the filter and logs a WARN |
| `ConfigurationPropertySources` + Boot `Ssl` binding via `Binder` | Aggregated property-source semantics may break management-port SSL and overrides | management-port and SSL binding tests | Fails fast on binding errors |
| `@AutoConfiguration` ordering and conditions | Different ordering on older versions can back off conditions | auto-configuration contract tests | Explicit `@AutoConfigureAfter` fixes the order |

> **E2E coverage scope**: 3.5 runs the full `@SpringBootTest` suite; 3.2 / 3.3 / 3.4 / 3.5 also run a minimal
> smoke set. So "3.2.x ~ 3.5.x" means core + starter tests are verified; the full E2E suite is verified on 3.5
> only.

### Additional Features

- **Virtual threads**: enable with `spring.threads.virtual.enabled=true` on JDK 21+ and both the `default` business pool and batch methods (`@BatchMapping`) switch to virtual threads (only the thread type changes: `pool.*` bounds/queueing and batch's `consumerSize` cap + backpressure semantics are all unchanged) — via reflection on `Thread.ofVirtual`, so it compiles on JDK 17; below JDK 21 it warns and falls back to platform threads. Implemented in [`VirtualThreadSupport`](../../spring-web/src/main/java/io/springperf/web/core/pool/VirtualThreadSupport.java) / [`BizPoolRegistry`](../../spring-web/src/main/java/io/springperf/web/core/pool/BizPoolRegistry.java). E2E coverage: [`VirtualThreadE2ETest`](../../spring-web-test/src/test/java/io/springperf/webtest/VirtualThreadE2ETest.java) and [`BatchVirtualThreadE2ETest`](../../spring-web-support-test/src/test/java/io/springperf/webtest/batch/BatchVirtualThreadE2ETest.java)
- **GraalVM native-image**: `FastInvokerGenerator` degrades to `MethodHandle` calls under native-image; `SpringWebRuntimeHints` registers event-path/resource/async-callback reachability hints; `ControllerBeanFactoryInitializationAotProcessor` (via `META-INF/spring/aot.factories`) auto-registers reflection/serialization hints for user `@Controller` methods and DTOs at AOT build time. The static `META-INF/native-image/.../{reflect,proxy,resource}-config.json` files and the AOT-generated hints are **complementary**: the former supply the baseline of framework internals, SPI interfaces and resources that AOT cannot discover, while the latter add what is only known at AOT build time. The static `reflect-config.json` no longer registers auto-configuration classes (constructor hints come from `process-aot`); intentionally overlapping proxy/resource entries are kept as a belt-and-suspenders measure.
- **WebSocket**: auto-configuration based on Jakarta WebSocket

### GraalVM native-image Support Matrix

| Scenario | Status | Notes |
|----------|--------|-------|
| SB3 (master default 3.5.x) | ✅ Usable | `SpringWebRuntimeHints` + `ControllerBeanFactoryInitializationAotProcessor`; no manual hints needed for user `@Controller`/DTO; example `spring-web-example-rest` binds `process-aot`; **Windows GraalVM 21.0.2 + MSVC native build & request flow verified end-to-end** |
| SB4 (4.0.x/4.1.x, `4.1.x` branch) | ❌ Not supported | The 4.x event bridge needs runtime `defineClass` (forbidden in closed world); explicitly documented as excluded on the `4.1.x` branch |
| epoll transport | ✅ Verified | Netty epoll `.so` verified in a Linux native build environment (NIO transport also verified on Windows native) |
| WebSocket `@ServerEndpoint` | ⚠️ Register as Bean | classpath scanning unavailable under native; `JsrEndpointScanner` auto-degrades to Bean discovery |
| Native build verification | ✅ Windows & Linux verified | Native build, launch and 8 request-chain assertions verified on both Windows and Linux |

> **Native-image smoke coverage**: verified JSON serialization on `/health`, controller-method reflection and `@PathVariable` / `@RequestBody` binding, the business error branch and the framework 404 branch, confirming that the AOT-generated reflection/DTO hints work in the native image. View templates, async return values and a separate management port are **not** covered.

---

## Version Selection Guide

| Your Scenario | Recommended Branch |
|--------------|-------------------|
| Existing project on Servlet container, JDK 8/11 | `2.7.x` |
| New project or already migrated to JDK 17+ | `master` |
| Need virtual threads (JDK 21) | `master` |
| Need GraalVM native-image | `master` |

> **Branch recommendation**: For JDK 8/11 existing projects, choose `2.7.x` and use `-Pspring-boot-2.6` / `-Pspring-boot-2.5` / `-Pspring-boot-2.4` to switch target versions. For JDK 17+ new projects, choose `master` (supports virtual threads, GraalVM native-image).

---

## Branch Differences at a Glance

| Dimension | `2.7.x` | `master` |
|-----------|---------|----------|
| Minimum JDK | 8 | 17 |
| Servlet API | `javax.servlet` | `jakarta.servlet` |
| Virtual threads | Not supported | Supported (JDK 21+) |
| GraalVM native-image | Not supported | Supported |
| `ModelMap` vs `Model` | `ModelMap implements Model` | **`ModelMap` does NOT implement `Model`** (requires `ExtendedModelMap`) |
| `spring-web-view` | Already present (the `2.7.x` branch pom includes this module) | New feature baseline |

---

## spring-web-view and Model Type Differences

The view rendering module depends on Spring's `org.springframework.ui` types. Because the class designs differ between Spring Framework 6.2 (master) and 5.3 (2.7.x), the **Model parameter injection implementation differs**:

| Branch | Spring Framework | `ModelMap` implements `Model`? | Framework injection type | Notes |
|--------|-----------------|-------------------------------|--------------------------|-------|
| `master` | 6.2.x | ❌ No | `ExtendedModelMap` (subclass of `ModelMap` and implements `Model`) | Always injects `ExtendedModelMap` to support `Model`/`ModelMap`/`ExtendedModelMap` declarations |
| `2.7.x` | 5.3.x | ✅ Yes | `ExtendedModelMap` | `ModelMap` itself is castable to `Model` |

> On the `2.7.x` branch, Spring 5.3's `ModelMap` already implements `Model`, so the same `ModelContext.getOrCreate()` implementation can be reused.

### Thymeleaf / FreeMarker Version Matrix

| Branch | Thymeleaf (BOM-managed) | FreeMarker (BOM-managed) |
|--------|-------------------------|--------------------------|
| `3.5.16` (master) | 3.1.5.RELEASE | 2.3.34 |
| `2.7.18` (2.7.x) | 3.0.15.RELEASE | 2.3.32 |

`spring-web-view` declares engines as `provided`; exact versions are managed by the user's Spring Boot BOM.
