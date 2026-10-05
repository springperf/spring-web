> English | [中文](../compatibility.md)

# Version Compatibility

This project maintains three branches: one mainline (Spring Boot 3.5.x) and two downstream
adaptation branches (2.7.x / 4.1.x).

---

## Branch Overview

| Branch | Spring Boot | Status | Maintenance Strategy |
|--------|-------------|--------|---------------------|
| `2.7.x` | 2.4.x ~ 2.7.x | Downstream adaptation | Synced from master + downgrade adaptation; based on javax.servlet (see [2.7.x migration checklist](../../.agent/context/2.7.x-migration-checklist.md)) |
| `master` | **3.5.x** | **Development baseline** | New features merged here first; **contains no Spring Boot 4 / Spring Framework 7 compatibility code** |
| `4.1.x` | 4.0.x ~ 4.1.x | Downstream adaptation | Synced from master + upgrade adaptation; **defaults to Spring Boot 4.1, with no 3.5.x compatibility path retained** (see [4.1.x adaptation guide](../../.agent/context/4.1.x-adaptation-checklist.md)) |

Sync direction is always `master → 4.1.x` / `master → 2.7.x`; version adaptation code lives only
in the downstream branches.

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
| Netty | **4.1.115.Final** | 4.1.x (manually overridden) | Spring Boot 2.7.x manages a lower version by default |
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
| Spring Boot | **3.5.16** | 3.0.x ~ 3.5.x | Switch via Maven profile (`-Pspring-boot-3.0` ~ `-Pspring-boot-3.5`); for 4.x see the `4.1.x` branch |
| Spring Framework | **6.2.x** | 6.0.x ~ 6.2.x | Managed by Spring Boot |
| JDK | **17** | 17 / 21 / 25 (CI matrix, see [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)) | Compile target `java.version=17`; JDK 21+ supports virtual threads (see below) |
| Servlet API | **jakarta.servlet 6.0** | 6.0.x | javax.servlet incompatible |
| Netty | **4.1.137.Final** | 4.1.x | |
| Jackson | **2.17.2** | 2.17.x | |
| Lombok | **1.18.46** | 1.18.30+ | Higher version needed for JDK 17+ compatibility |
| JMH | **1.37** | 1.37 | Benchmark module only |

### Additional Features

- **Virtual threads**: enable with `spring.threads.virtual.enabled=true` on JDK 21+ and both the `default` business pool and batch methods (`@BatchMapping`) switch to virtual threads (only the thread type changes: `pool.*` bounds/queueing and batch's `consumerSize` cap + backpressure semantics are all unchanged) — via reflection on `Thread.ofVirtual`, so it compiles on JDK 17; below JDK 21 it warns and falls back to platform threads. Implemented in [`VirtualThreadSupport`](../../spring-web/src/main/java/io/springperf/web/core/pool/VirtualThreadSupport.java) / [`BizPoolRegistry`](../../spring-web/src/main/java/io/springperf/web/core/pool/BizPoolRegistry.java). E2E coverage: [`VirtualThreadE2ETest`](../../spring-web-test/src/test/java/io/springperf/webtest/VirtualThreadE2ETest.java) and [`BatchVirtualThreadE2ETest`](../../spring-web-support-test/src/test/java/io/springperf/webtest/batch/BatchVirtualThreadE2ETest.java)
- **GraalVM native-image**: `FastInvokerGenerator` degrades to `MethodHandle` calls under native-image; `SpringWebRuntimeHints` registers event-path/resource/async-callback reachability hints; `ControllerBeanFactoryInitializationAotProcessor` (via `META-INF/spring/aot.factories`) auto-registers reflection/serialization hints for user `@Controller` methods and DTOs at AOT build time
- **WebSocket**: auto-configuration based on Jakarta WebSocket

### GraalVM native-image Support Matrix

| Scenario | Status | Notes |
|----------|--------|-------|
| SB3 (master default 3.5.x) | ✅ Usable | `SpringWebRuntimeHints` + `ControllerBeanFactoryInitializationAotProcessor`; no manual hints needed for user `@Controller`/DTO; example `spring-web-example-rest` binds `process-aot`; **Windows GraalVM 21.0.2 + MSVC native build & request flow verified end-to-end** |
| SB4 (4.0.x/4.1.x, `4.1.x` branch) | ❌ Not supported | The 4.x event bridge needs runtime `defineClass` (forbidden in closed world); explicitly documented as excluded on the `4.1.x` branch |
| epoll transport | ✅ Verified | Netty epoll `.so` verified in a Linux native build environment (NIO transport also verified on Windows native) |
| WebSocket `@ServerEndpoint` | ⚠️ Register as Bean | classpath scanning unavailable under native; `JsrEndpointScanner` auto-degrades to Bean discovery |
| Native build verification | ✅ Windows & Linux verified | Windows: `mvn -Pnative package` (GraalVM + MSVC, `vcvars64` env + `-H:-CheckToolchain`); Linux: CI `ubuntu-latest` runs `scripts/native-smoke-test.sh` (build + launch + request-flow assertions) |

---

## 4.1.x Branch

### Version Matrix

| Dependency | Current | Verified Range | Notes |
|-----------|---------|----------------|-------|
| Spring Boot | **4.1.0** | 4.0.x ~ 4.1.x | **4.1 is the default — no `-P` needed**; `-Pspring-boot-4.0` switches to 4.0 |
| Spring Framework | **7.0.x** | 7.0.x | Managed by Spring Boot |
| JDK | **17** | 17 / 21 / 25 (CI matrix) | Compile target `java.version=17` |
| Servlet API | **jakarta.servlet 6.0** | 6.0.x | Same as master |
| Netty | **4.1.137.Final** | 4.1.x | |
| Jackson | **3.1.4** (`tools.jackson`) + annotations 2.21 | Managed by the Boot 4 BOM | **Not the same lineage as master's Jackson 2** — see below |
| Lombok | **1.18.46** | 1.18.30+ | |
| JMH | **1.37** | 1.37 | Benchmark module only |

### Key Differences from master

This branch is **dedicated to Spring Boot 4** and no longer aims for "one codebase running on both
3.5.x and 4.x" — that goal is served by the division between master (pure 3.5.x) and this branch.

| Dimension | `master` | `4.1.x` |
|-----------|----------|---------|
| Maven profiles | `spring-boot-3.0` ~ `3.5` | `spring-boot-4.0` / `4.1` (**4.1 by default**) |
| Jackson | **2.17.2** (`com.fasterxml.jackson.databind`) | **3.1.4** (`tools.jackson.databind`); annotations still `com.fasterxml.jackson.annotation` |
| `WebHttpHeaders` | Single implementation, direct `super.*` calls | Single implementation; methods absent from the superclass delegate to the `asMultiValueMap()` view (no MethodHandle version branch) |
| Container event adaptation | Constructs the SB3 event directly | Constructs the SB4 event directly (`boot.web.server.context.*`; no ASM runtime bridge) |
| `ResponseStatusException` headers | Reflective bridge over `getResponseHeaders()` / `getHeaders()` | Calls `getHeaders()` directly |
| `ListenableFuture` return support | Supported (Spring 6 has the type) | **Removed** (Spring 7 dropped `ListenableFuture`; the old implementation was permanently dead code) |
| GraalVM native-image | Supported | ❌ Not supported (see the support matrix in the master section) |

> **Jackson 3 behaviour difference**: Jackson 3 flips the default of `FAIL_ON_NULL_FOR_PRIMITIVES`
> from `false` to `true` (a JSON `null` bound to an `int`/`boolean` field used to silently become
> `0`/`false`, now it throws). The framework explicitly disables this feature via
> `JacksonMappers.defaultMapper()` to keep behaviour aligned with master.

---

## Version Selection Guide

| Your Scenario | Recommended Branch |
|--------------|-------------------|
| Existing project on Servlet container, JDK 8/11 | `2.7.x` |
| New project or already migrated to JDK 17+ | `master` |
| Need virtual threads (JDK 21) | `master` |
| Need GraalVM native-image | `master` |
| Already on Spring Boot 4.0.x / 4.1.x | `4.1.x` |

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
| `spring-web-view` | Per backport status | New feature baseline |

---

## spring-web-view and Model Type Differences

The view rendering module depends on Spring's `org.springframework.ui` types. Because the class designs differ between Spring Framework 6.2 (master) and 5.3 (2.7.x), the **Model parameter injection implementation differs**:

| Branch | Spring Framework | `ModelMap` implements `Model`? | Framework injection type | Notes |
|--------|-----------------|-------------------------------|--------------------------|-------|
| `master` | 6.2.x | ❌ No | `ExtendedModelMap` (subclass of `ModelMap` and implements `Model`) | Always injects `ExtendedModelMap` to support `Model`/`ModelMap`/`ExtendedModelMap` declarations |
| `2.7.x` | 5.3.x | ✅ Yes | `ExtendedModelMap` (simpler on backport) | `ModelMap` itself is castable to `Model` |

> **Backport note**: `ModelContext.getOrCreate()` in `spring-web` core needs no special handling on 2.7.x — Spring 5.3's `ModelMap` already implements `Model`, so the same implementation can be reused.

### Thymeleaf / FreeMarker Version Matrix

| Branch | Thymeleaf (BOM-managed) | FreeMarker (BOM-managed) |
|--------|-------------------------|--------------------------|
| `3.5.16` (master) | 3.1.5.RELEASE | 2.3.34 |
| `2.7.18` (2.7.x) | 3.0.15.RELEASE | 2.3.32 |

`spring-web-view` declares engines as `provided`; exact versions are managed by the user's Spring Boot BOM.
