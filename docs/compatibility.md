> [English](en/compatibility.md) | 中文

# 版本兼容性说明

本项目维护两个主线分支，分别对应 Spring Boot 2.x 和 Spring Boot 3.x。

---

## 分支概览

| 分支 | 对应 Spring Boot | 状态 | 维护策略 |
|------|-----------------|------|---------|
| `2.7.x` | 2.4.x ~ 2.7.x | 维护分支 | 功能迭代 + bugfix，基于 javax.servlet |
| `master` | 3.0.x ~ 3.5.x / 4.0.x ~ 4.1.x | **开发基线** | 新功能优先合入此处，通过 Maven Profile 多版本兼容 |

---

## 版本下限说明

本项目曾尝试兼容 Spring Boot 2.3.x（Spring Framework 5.2.x），但 Spring 5.2 年代久远，缺少 `MultiValueMapAdapter`、`getSupportedMediaTypes(Class)` 等 API，且 `MethodHandles.lookup()` 对包私有方法的访问受限难以绕过。这些限制使得兼容维护成本远高于收益，故 **2.3.x 及以下版本不再支持**，曾有兼容代码已清理退回。

---

## 2.7.x 分支

### 版本矩阵

| 依赖 | 当前版本 | 已验证兼容范围 | 说明 |
|------|---------|---------------|------|
| Spring Boot | **2.7.18** | 2.4.x ~ 2.7.x | 通过 Maven Profile 切换（`-Pspring-boot-2.4` ~ `-Pspring-boot-2.7`） |
| Spring Framework | **5.3.x** | 随 Spring Boot 管理 |  |
| JDK | **8、11、17** | 8、11、17 已验证 | 编译目标 `java.version=8` |
| Servlet API | **javax.servlet 4.0.1** | 4.0.x |  |
| Netty | **4.1.110.Final** | 4.1.x（当前为手动覆盖版本） | Spring Boot 2.7.x 默认管理更低版本 |
| Jackson | **2.17.2** | 2.17.x（当前为手动覆盖版本） | Spring Boot 2.7.x 默认管理 2.13.x |
| Lombok | **1.18.24** | 1.18.x |  |
| JMH | **1.37** | 1.37 | 仅 benchmark 模块使用 |

### 关键限制

- **不支持 `jakarta.servlet`**：2.7.x 基于 `javax.servlet`，与 Jakarta EE 不兼容
- **不支持虚拟线程**：JDK 8/11 无虚拟线程能力
- **不支持 GraalVM native-image**：AOT 编译为 master 分支特性

---

## master 分支

### 版本矩阵

| 依赖 | 当前版本 | 已验证兼容范围 | 说明 |
|------|---------|---------------|------|
| Spring Boot | **3.5.16** | 3.0.x ~ 3.5.x / 4.0.x ~ 4.1.x | 通过 Maven Profile 切换（`-Pspring-boot-3.0` ~ `-Pspring-boot-4.1`） |
| Spring Framework | **6.2.x** | 6.0.x ~ 6.2.x / 7.0.x | 随 Spring Boot 管理 |
| JDK | **17、21** | 17、21 已验证 | 编译目标 `java.version=17`，21 提供虚拟线程支持 |
| Servlet API | **jakarta.servlet 6.0** | 6.0.x | javax.servlet 不兼容 |
| Netty | **4.1.137.Final** | 4.1.x |  |
| Jackson | **2.17.2** | 2.17.x |  |
| Lombok | **1.18.46** | 1.18.30+ | JDK 17+ 兼容性要求高版本 |
| JMH | **1.37** | 1.37 | 仅 benchmark 模块使用 |

### 额外特性

- **虚拟线程**：JDK 21+ 下 `spring.threads.virtual.enabled=true` 启用，业务线程池自动切换为虚拟线程
- **GraalVM native-image**：`FastInvokerGenerator` 在 native-image 下自动降级为 `MethodHandle` 调用；`SpringWebRuntimeHints` 注册事件路径/资源/异步回调可达性提示；`ControllerBeanFactoryInitializationAotProcessor`（经 `META-INF/spring/aot.factories`）在 AOT 构建期自动为 `@Controller` 处理方法与 DTO 注册反射/序列化 hints
- **WebSocket**：基于 Jakarta WebSocket 的自动配置

### GraalVM native-image 支持矩阵

| 场景 | 状态 | 说明 |
|------|------|------|
| SB3（master 默认 3.5.x） | ✅ 可用级 | `SpringWebRuntimeHints` + `ControllerBeanFactoryInitializationAotProcessor`，用户 `@Controller`/DTO 无需手工 hints；示例 `spring-web-example-rest` 已绑定 `process-aot`；**Windows GraalVM 21.0.2 + MSVC 原生构建与请求链路端到端验证通过** |
| SB4（4.0.x/4.1.x） | ❌ 不支持 | `Boot4WebServerInitializedEventBridge` 需运行时 `defineClass`（封闭世界禁止），显式文档化排除 |
| epoll transport | ⚠️ 待 Linux 验证 | Netty epoll `.so` 需在 Linux 原生构建环境验证；NIO transport 已在 Windows native 验证通过 |
| WebSocket `@ServerEndpoint` | ⚠️ 需注册为 Bean | native 下 classpath 扫描不可用，`JsrEndpointScanner` 自动降级为 Bean 发现，端点需显式注册为 Spring Bean |
| 原生构建验证 | ✅ Windows 已验 / 🔧 Linux 待验 | Windows：`mvn -Pnative package`（GraalVM + MSVC，`vcvars64` 环境 + `-H:-CheckToolchain`）；Linux：`scripts/native-smoke-test.sh` |

---

## 版本选择建议

| 你的场景 | 推荐分支 |
|---------|---------|
| 现有项目基于 Servlet 容器，JDK 8/11 | `2.7.x` |
| 新项目或已迁移到 JDK 17+ | `master` |
| 需要使用虚拟线程（JDK 21） | `master` |
| 需要 GraalVM native-image 编译 | `master` |

> **分支选择建议**：JDK 8/11 现有项目选 `2.7.x`，使用 `-Pspring-boot-2.6` / `-Pspring-boot-2.5` / `-Pspring-boot-2.4` 切换目标版本；JDK 17+ 新项目选 `master`（支持虚拟线程、GraalVM native-image）。

---

## 分支差异摘要

| 维度 | `2.7.x` | `master` |
|------|---------|----------|
| JDK 最低要求 | 8 | 17 |
| Servlet API | `javax.servlet` | `jakarta.servlet` |
| 虚拟线程 | 不支持 | 支持（JDK 21+） |
| GraalVM native-image | 不支持 | 支持 |
| `ModelMap` 与 `Model` 接口 | `ModelMap implements Model` | **`ModelMap` 不实现 `Model`**（需 `ExtendedModelMap`） |
| `spring-web-view` | 视 Backport 情况 | 新功能基线 |

---

## spring-web-view 与 Model 类型差异

视图渲染模块依赖 Spring 的 `org.springframework.ui` 类型。由于 Spring Framework 6.2（master 分支）与 5.3（2.7.x 分支）的类设计不同，**Model 参数注入的实现存在差异**：

| 分支 | Spring Framework | `ModelMap` 是否实现 `Model` | 框架注入实现 | 备注 |
|------|----------------|---------------------------|--------------|------|
| `master` | 6.2.x | ❌ 否 | `ExtendedModelMap`（`ModelMap` 子类且实现 `Model`） | 统一注入 `ExtendedModelMap` 以兼容 `Model`/`ModelMap`/`ExtendedModelMap` 三种参数声明 |
| `2.7.x` | 5.3.x | ✅ 是 | `ExtendedModelMap`（backport 时可简化） | `ModelMap` 本身即可 cast 到 `Model` |

> **Backport 提示**：`spring-web-view` 的 `ModelSupport.getOrCreate()` 在 2.7.x 分支无需特别处理——Spring 5.3 的 `ModelMap` 已实现 `Model`，可复用同一实现。

### Thymeleaf / FreeMarker 版本矩阵

| 分支 | Thymeleaf（BOM 管理） | FreeMarker（BOM 管理） |
|------|----------------------|------------------------|
| `3.5.16`（master） | 3.1.5.RELEASE | 2.3.34 |
| `2.7.18`（2.7.x） | 3.0.15.RELEASE | 2.3.32 |

`spring-web-view` 对引擎为 `provided` 依赖，具体版本由用户侧 Spring Boot BOM 管理，无需模块内指定。