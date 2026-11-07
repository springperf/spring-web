> [English](en/compatibility.md) | 中文

# 版本兼容性说明

本项目维护三个分支：一条主线（Spring Boot 3.5.x）与两条下游适配分支（2.7.x / 4.1.x）。

---

## 分支概览

| 分支 | 对应 Spring Boot | 状态 | 维护策略 |
|------|-----------------|------|---------|
| `2.7.x` | **2.7.x**（基线 2.7.18） | 下游适配分支 | 从 master 同步 + 降级适配，基于 javax.servlet（见 [2.7.x 迁移清单](../.agent/context/2.7.x-migration-checklist.md)） |
| `master` | **3.5.x** | **开发基线** | 新功能优先合入此处；**不含任何 Spring Boot 4 / Spring Framework 7 兼容代码** |
| `4.1.x` | 4.0.x ~ 4.1.x | 下游适配分支 | 从 master 同步 + 升级适配；**默认即 Spring Boot 4.1，不再保留 3.5.x 兼容路径**（见 [4.1.x 适配指南](../.agent/context/4.1.x-adaptation-checklist.md)） |

同步方向始终是 `master → 4.1.x` / `master → 2.7.x`；版本适配代码只存在于下游分支。

`4.1.x` 的**项目版本号与主线独立**，已发布首个版本 **`4.1.7`**（分支内 `spring-boot.version` = Boot 4.1.0，与项目版本号无关）。

---

## 版本下限说明

本项目三个分支中最低的支持线是 **Spring Boot 2.7.x**（验证基线 2.7.18），即 `2.7.x` 分支；各分支的版本矩阵与边界见下文。

---

## 2.7.x 分支

### 版本矩阵

| 依赖 | 当前版本 | 已验证兼容范围 | 说明 |
|------|---------|---------------|------|
| Spring Boot | **2.7.18** | **2.7.x** | 分支父 POM 即 2.7.18；`spring-boot-2.7` Profile 默认激活并锁定该版本 |
| Spring Framework | **5.3.x** | 随 Spring Boot 管理 |  |
| JDK | **8、11、17** | 8、11、17 已验证 | 编译目标 `java.version=8` |
| Servlet API | **javax.servlet 4.0.1** | 4.0.x |  |
| Netty | **4.1.115.Final** | 4.1.x（当前为手动覆盖版本） | Spring Boot 2.7.x 默认管理更低版本 |
| Jackson | **2.17.2** | 2.17.x（当前为手动覆盖版本） | Spring Boot 2.7.x 默认管理 2.13.x |
| Lombok | **1.18.24** | 1.18.x |  |
| JMH | **1.37** | 1.37 | 仅 benchmark 模块使用 |

### 支持范围

`2.7.x` 的目标版本与验证基线都是 **Spring Boot 2.7.x**（当前 2.7.18）。

- 父 POM 与 `spring-boot-2.7` Profile 均锁定 2.7.18，该 Profile 默认激活，无需显式传参；
- CI 的 `spring-boot-versions` 矩阵以 `2.7` 为唯一入口，在该版本线上跑完整构建与测试；
- 若传入不存在的 `-Pspring-boot-*`，Maven 只打一条 `The requested profile ... does not exist` 警告并**继续按 2.7.18 构建**——产物仍是 2.7.x，请以默认构建为准。

**使用前提**：应用需运行在 Spring Boot 2.7.x（Spring Framework 5.3 / `javax.servlet`）。可用 `mvn dependency:tree -Dincludes=org.springframework.boot:spring-boot` 确认实际版本。

### 关键限制

- **不支持 `jakarta.servlet`**：2.7.x 基于 `javax.servlet`，与 Jakarta EE 不兼容
- **不支持虚拟线程**：JDK 8/11 无虚拟线程能力
- **不支持 GraalVM native-image**：AOT 编译为 master 分支特性

---

## master 分支

### 版本矩阵

| 依赖 | 当前版本 | 已验证兼容范围 | 说明 |
|------|---------|---------------|------|
| Spring Boot | **4.1.0** | 4.0.x ~ 4.1.x | 通过 Maven Profile 切换（`-Pspring-boot-4.0` / `-Pspring-boot-4.1`，默认 4.1）；3.5.x 见 `master` 分支 |
| Spring Framework | **6.2.x** | 6.1.x ~ 6.2.x | 随 Spring Boot 管理 |
| JDK | **17** | 17 / 21 / 25（CI 矩阵，见 [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)） | 编译目标 `java.version=17`；JDK 21 起支持虚拟线程（见下） |
| Servlet API | **jakarta.servlet 6.0** | 6.0.x | javax.servlet 不兼容 |
| Netty | **4.1.137.Final** | 4.1.x |  |
| Jackson | **2.17.2** | 2.17.x |  |
| Lombok | **1.18.46** | 1.18.30+ | JDK 17+ 兼容性要求高版本 |
| JMH | **1.37** | 1.37 | 仅 benchmark 模块使用 |

> 兼容性由 `ci.yml` 的 `spring-boot-versions` 矩阵（4.0 / 4.1）跑核心模块 + starter **单测**验证——只验证
> `compile` 证明不了运行期符号存在。将来支持 4.2 时会在矩阵中追加 4.2 并保留 4.1。

### 跨版本敏感点

master 的跨版本风险集中在 starter 自动配置层，`spring-boot-versions` job 正是因为覆盖到
`-pl spring-boot-starter-web -am test` 才能证明这些点在 3.2 / 3.3 / 3.4 上可用。

| 敏感点 | 风险 | 覆盖用例 | 降级策略 |
|--------|------|---------|---------|
| `ApiVersion`（actuator 内部枚举 `V2/V3/LATEST`） | 3.x 小版本改名/改值 | starter 单测 + `ActuatorEndpointAutoConfiguration` 条件装配 | 编译期即暴露，无静默降级 |
| `DelegatingFilterProxyRegistrationBean#getTargetBeanName()`（protected 反射） | Boot 调整签名后 filter 静默消失 | `SpringWebServletAutoConfigurationCreateFilterWrapperTest` | 返回 null 跳过并打 WARN，不吞异常 |
| `ConfigurationPropertySources` + Boot `Ssl` 的 `Binder` 绑定 | 聚合属性源语义变化会让管理端口 SSL / 覆盖失效 | `ManagementServerSettingsTest`、`SslContextFactoryTest` | 绑定失败 fail-fast |
| `@AutoConfiguration` 排序与条件注解 | 低版本排序不同会让条件装配退让 | `AutoConfigurationContractTest`、`AutoConfigurationConditionTest` | 显式 `@AutoConfigureAfter` 固定顺序 |

> **E2E 覆盖口径**：3.5 跑全量 `@SpringBootTest`（`ci.yml` 的 JDK 矩阵）；3.2 / 3.3 / 3.4 / 3.5 由
> `spring-boot-e2e-matrix.yml` 每周（或手动 `workflow_dispatch`）跑最小冒烟集
> （`AdditionalEndpointsE2ETest` / `ManagementPortConflictTest` / `WebServerInitializedEventOrderTest`）。
> 因此「3.2.x ~ 3.5.x」指核心 + starter 单测已验证，E2E 全量仅 3.5。

### 额外特性

- **虚拟线程**：JDK 21+ 下 `spring.threads.virtual.enabled=true` 启用，`default` 业务线程池与 batch 的批量方法（`@BatchMapping`）均改用虚拟线程执行（只替换线程类型：`pool.*` 上限/队列语义与 batch 的 `consumerSize` 上限、背压语义均保持不变）——反射调用 `Thread.ofVirtual`，兼容 JDK 17 编译，JDK < 21 时告警并回落平台线程。实现见 [`VirtualThreadSupport`](../spring-web/src/main/java/io/springperf/web/core/pool/VirtualThreadSupport.java) / [`BizPoolRegistry`](../spring-web/src/main/java/io/springperf/web/core/pool/BizPoolRegistry.java)；E2E 覆盖见 [`VirtualThreadE2ETest`](../spring-web-test/src/test/java/io/springperf/webtest/VirtualThreadE2ETest.java) 与 [`BatchVirtualThreadE2ETest`](../spring-web-support-test/src/test/java/io/springperf/webtest/batch/BatchVirtualThreadE2ETest.java)
- **GraalVM native-image**：`FastInvokerGenerator` 在 native-image 下自动降级为 `MethodHandle` 调用；`SpringWebRuntimeHints` 注册事件路径/资源/异步回调可达性提示；`ControllerBeanFactoryInitializationAotProcessor`（经 `META-INF/spring/aot.factories`）在 AOT 构建期自动为 `@Controller` 处理方法与 DTO 注册反射/序列化 hints。静态 `META-INF/native-image/.../{reflect,proxy,resource}-config.json` 与 AOT 动态 hints **互补**：前者提供 AOT 无法自动发现的框架内部类/SPI 接口/资源基线，后者补 AOT 构建期才确定的部分；静态 `reflect-config.json` 不再登记自动配置类（构造提示由 `process-aot` 生成），两轨有意重叠的代理/资源条目保留为双保险，关系由 `NativeImageHintsConsistencyTest` 锁定
- **WebSocket**：基于 Jakarta WebSocket 的自动配置

### GraalVM native-image 支持矩阵

| 场景 | 状态 | 说明 |
|------|------|------|
| SB3（master 默认 3.5.x） | ✅ 可用级 | `SpringWebRuntimeHints` + `ControllerBeanFactoryInitializationAotProcessor`，用户 `@Controller`/DTO 无需手工 hints；示例 `spring-web-example-rest` 已绑定 `process-aot`；**Windows GraalVM 21.0.2 + MSVC 原生构建与请求链路端到端验证通过** |
| SB4（4.0.x/4.1.x，`4.1.x` 分支） | ❌ 不支持 | 4.x 的事件桥接需运行时 `defineClass`（native 封闭世界禁止）；`4.1.x` 分支上显式文档化排除 |
| epoll transport | ✅ 已验证 | Netty epoll `.so` 已在 Linux 原生构建环境验证通过（NIO transport 亦已在 Windows native 验证） |
| WebSocket `@ServerEndpoint` | ⚠️ 需注册为 Bean | native 下 classpath 扫描不可用，`JsrEndpointScanner` 自动降级为 Bean 发现，端点需显式注册为 Spring Bean |
| 原生构建验证 | ✅ Windows / Linux 均已验 | Windows：`mvn -Pnative package`（GraalVM + MSVC，`vcvars64` 环境 + `-H:-CheckToolchain`）；Linux：CI `ubuntu-latest` 以 `scripts/native-smoke-test.sh` 作为门禁（构建 + 启动 + 8 条请求链路断言） |

> **CI 冒烟覆盖边界**：`scripts/native-smoke-test.sh` 针对 `spring-web-example-rest` 逐条断言 `/health` 的 JSON 序列化、`GET/POST/PUT/DELETE /api/users` 的控制器方法反射与 `@PathVariable` / `@RequestBody` 绑定、业务错误分支以及框架 404 分支，用于验证 `ControllerBeanFactoryInitializationAotProcessor` 生成的反射/DTO hints 在原生镜像中确实可用；脚本、控制器路由与 `native-image.yml` 的对应关系由 `NativeSmokeContractTest` 锁定。视图模板、异步返回值与独立管理端口不在此覆盖范围内：示例模块不含模板或 `management.server.port`，且只有 `spring-web-example-rest` 绑定了 `process-aot` 与 `native` profile。

---

## 4.1.x 分支

### 版本矩阵

| 依赖 | 当前版本 | 已验证兼容范围 | 说明 |
|------|---------|---------------|------|
| Spring Boot | **4.1.0** | 4.0.x ~ 4.1.x | **默认即 4.1，无需 `-P` 切换**；`-Pspring-boot-4.0` 可切 4.0 |
| Spring Framework | **7.0.x** | 7.0.x | 随 Spring Boot 管理 |
| JDK | **17** | 17 / 21 / 25（CI 矩阵） | 编译目标 `java.version=17` |
| Servlet API | **jakarta.servlet 6.0** | 6.0.x | 同 master |
| Netty | **4.2.15.Final** | 由 Boot 4 BOM 管理 | Boot 4 的基线即 4.2；**不要在本项目钉版**，否则与经 reactor-netty 引入的 4.2 混用 |
| Jackson | **3.1.4**（`tools.jackson`）+ 注解 2.21 | 由 Boot 4 BOM 管理 | **与 master 的 Jackson 2 不同源**，见下 |
| Lombok | **1.18.46** | 1.18.30+ |  |
| JMH | **1.37** | 1.37 | 仅 benchmark 模块使用 |

### 与 master 的关键差异

本分支**专用 Spring Boot 4**，不再追求「一份代码同时兼容 3.5.x 与 4.x」——该目标由 master（纯 3.5.x）与本分支的分工取代。

| 维度 | `master` | `4.1.x` |
|------|----------|---------|
| Maven Profile | `spring-boot-3.0` ~ `3.5` | `spring-boot-4.0` / `4.1`（**默认 4.1**） |
| Jackson | **2.17.2**（`com.fasterxml.jackson.databind`） | **3.1.4**（`tools.jackson.databind`）；注解仍用 `com.fasterxml.jackson.annotation` |
| Netty | **4.1.137.Final** | **4.2.15.Final**（随 Boot 4 BOM；Boot 3.5 的基线才是 4.1） |
| `WebHttpHeaders` | 单实现，`super.*` 直调 | 单实现，父类没有的方法委派 `asMultiValueMap()` 视图（不再有 MethodHandle 版本分支） |
| 容器事件适配 | 直接构造 SB3 事件 | 直接构造 SB4 事件（`boot.web.server.context.*`，不再有 ASM 运行时桥接） |
| `ResponseStatusException` header | 反射桥接 `getResponseHeaders()` / `getHeaders()` | 直接调 `getHeaders()` |
| `ListenableFuture` 返回支持 | 支持（Spring 6 有该类型） | **已移除**（Spring 7 已删 `ListenableFuture`，原实现是恒 false 的死代码） |
| GraalVM native-image | 支持 | ❌ 不支持（见 master 章节的支持矩阵） |

> **Jackson 3 的行为差异**：Jackson 3 把 `FAIL_ON_NULL_FOR_PRIMITIVES` 的默认值由 `false` 改为 `true`（JSON 的 `null` 赋给 `int`/`boolean` 等基本类型字段，以前静默得 `0`/`false`，现在抛异常）。本框架经 `JacksonMappers.defaultMapper()` 显式关闭该 feature，保持与 master 一致的行为。

---

## 版本选择建议

| 你的场景 | 推荐分支 |
|---------|---------|
| 现有项目基于 Servlet 容器，Spring Boot 2.7.x，JDK 8/11 | `2.7.x` |
| 新项目或已迁移到 JDK 17+ | `master` |
| 需要使用虚拟线程（JDK 21） | `master` |
| 需要使用 GraalVM native-image 编译 | `master` |
| 已使用 Spring Boot 4.0.x / 4.1.x | `4.1.x` |

> **分支选择建议**：JDK 8/11 且使用 Spring Boot 2.7.x 的现有项目选 `2.7.x`；JDK 17+ 新项目选 `master`（支持虚拟线程、GraalVM native-image）。

---

## 分支差异摘要

| 维度 | `2.7.x` | `master` |
|------|---------|----------|
| JDK 最低要求 | 8 | 17 |
| Servlet API | `javax.servlet` | `jakarta.servlet` |
| 虚拟线程 | 不支持 | 支持（JDK 21+） |
| GraalVM native-image | 不支持 | 支持 |
| `ModelMap` 与 `Model` 接口 | `ModelMap implements Model` | **`ModelMap` 不实现 `Model`**（需 `ExtendedModelMap`） |
| `spring-web-view` | 已包含（`2.7.x` 分支 pom 即有此模块） | 新功能基线 |

---

## spring-web-view 与 Model 类型差异

视图渲染模块依赖 Spring 的 `org.springframework.ui` 类型。由于 Spring Framework 6.2（master 分支）与 5.3（2.7.x 分支）的类设计不同，**Model 参数注入的实现存在差异**：

| 分支 | Spring Framework | `ModelMap` 是否实现 `Model` | 框架注入实现 | 备注 |
|------|----------------|---------------------------|--------------|------|
| `master` | 6.2.x | ❌ 否 | `ExtendedModelMap`（`ModelMap` 子类且实现 `Model`） | 统一注入 `ExtendedModelMap` 以兼容 `Model`/`ModelMap`/`ExtendedModelMap` 三种参数声明 |
| `2.7.x` | 5.3.x | ✅ 是 | `ExtendedModelMap`（backport 时可简化） | `ModelMap` 本身即可 cast 到 `Model` |

> **Backport 提示**：`spring-web` core 的 `ModelContext.getOrCreate()` 在 2.7.x 分支无需特别处理——Spring 5.3 的 `ModelMap` 已实现 `Model`，可复用同一实现。

### Thymeleaf / FreeMarker 版本矩阵

| 分支 | Thymeleaf（BOM 管理） | FreeMarker（BOM 管理） |
|------|----------------------|------------------------|
| `3.5.16`（master） | 3.1.5.RELEASE | 2.3.34 |
| `2.7.18`（2.7.x） | 3.0.15.RELEASE | 2.3.32 |

`spring-web-view` 对引擎为 `provided` 依赖，具体版本由用户侧 Spring Boot BOM 管理，无需模块内指定。
