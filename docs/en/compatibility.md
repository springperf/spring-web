> English | [中文](../compatibility.md)

# Version Compatibility

This project maintains two main branches corresponding to Spring Boot 2.x and Spring Boot 3.x.

---

## Branch Overview

| Branch | Spring Boot | Status | Maintenance Strategy |
|--------|-------------|--------|---------------------|
| `2.7.x` | 2.4.x ~ 2.7.x | Maintenance branch | Features + bugfixes; based on javax.servlet |
| `master` | 3.0.x ~ 3.5.x / 4.0.x ~ 4.1.x | **Development baseline** | New features merged here first; multi-version via Maven profiles |

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
| Netty | **4.1.110.Final** | 4.1.x (manually overridden) | Spring Boot 2.7.x manages a lower version by default |
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
| Spring Boot | **3.2.12** | 3.0.x ~ 3.5.x / 4.0.x ~ 4.1.x | Switch via Maven profile (`-Pspring-boot-3.0` ~ `-Pspring-boot-4.1`) |
| Spring Framework | **6.1.x** | 6.0.x ~ 6.2.x / 7.0.x | Managed by Spring Boot |
| JDK | **17, 21** | 17, 21 verified | Compile target `java.version=17`; JDK 21 provides virtual thread support |
| Servlet API | **jakarta.servlet 6.0** | 6.0.x | javax.servlet incompatible |
| Netty | **4.1.110.Final** | 4.1.x | |
| Jackson | **2.17.2** | 2.17.x | |
| Lombok | **1.18.36** | 1.18.30+ | Higher version needed for JDK 17+ compatibility |
| JMH | **1.37** | 1.37 | Benchmark module only |

### Additional Features

- **Virtual threads**: enable with `spring.threads.virtual.enabled=true` on JDK 21+, business thread pool auto-switches to virtual threads
- **GraalVM native-image**: `FastInvokerGenerator` degrades to `MethodHandle` calls under native-image; `reflect-config.json` is pre-configured
- **WebSocket**: auto-configuration based on Jakarta WebSocket

---

## Version Selection Guide

| Your Scenario | Recommended Branch |
|--------------|-------------------|
| Existing project on Servlet container, JDK 8/11 | `2.7.x` |
| New project or already migrated to JDK 17+ | `master` |
| Need virtual threads (JDK 21) | `master` |
| Need GraalVM native-image | `master` |

> **Branch recommendation**: For JDK 8/11 existing projects, choose `master`/`2.7.x` and use `-Pspring-boot-2.6` / `-Pspring-boot-2.5` / `-Pspring-boot-2.4` to switch target versions. For JDK 17+ new projects, choose `master` (supports virtual threads, GraalVM native-image).

---

## Branch Differences at a Glance

| Dimension | `2.7.x` | `master` |
|-----------|---------|----------|
| Minimum JDK | 8 | 17 |
| Servlet API | `javax.servlet` | `jakarta.servlet` |
| Virtual threads | Not supported | Supported (JDK 21+) |
| GraalVM native-image | Not supported | Supported |
| Development baseline | Maintenance branch (bugfix + features) | **Development baseline** (new features first) |