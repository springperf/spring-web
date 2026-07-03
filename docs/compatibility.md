> [English](en/compatibility.md) | 中文

# 版本兼容性说明

本项目维护两个主线分支，分别对应 Spring Boot 2.x 和 Spring Boot 3.x。

---

## 分支概览

| 分支 | 对应 Spring Boot | 状态 | 维护策略 |
|------|-----------------|------|---------|
| `master` / `2.7.x` | 2.4.x ~ 2.7.x | **开发基线** | 功能迭代 + bugfix，新功能优先合入此处 |
| `3.2.x` | 3.0.x ~ 3.5.x / 4.0.x ~ 4.1.x | 主力版本 | 从 master fork，通过 Maven Profile 多版本兼容 |

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
- **不支持 GraalVM native-image**：AOT 编译为 3.2.x 分支特性

---

## 3.2.x 分支

### 版本矩阵

| 依赖 | 当前版本 | 已验证兼容范围 | 说明 |
|------|---------|---------------|------|
| Spring Boot | **3.2.12** | 3.0.x ~ 3.5.x / 4.0.x ~ 4.1.x | 通过 Maven Profile 切换（`-Pspring-boot-3.0` ~ `-Pspring-boot-4.1`） |
| Spring Framework | **6.1.x** | 6.0.x ~ 6.2.x / 7.0.x | 随 Spring Boot 管理 |
| JDK | **17、21** | 17、21 已验证 | 编译目标 `java.version=17`，21 提供虚拟线程支持 |
| Servlet API | **jakarta.servlet 6.0** | 6.0.x | javax.servlet 不兼容 |
| Netty | **4.1.110.Final** | 4.1.x |  |
| Jackson | **2.17.2** | 2.17.x |  |
| Lombok | **1.18.36** | 1.18.30+ | JDK 17+ 兼容性要求高版本 |
| JMH | **1.37** | 1.37 | 仅 benchmark 模块使用 |

### 额外特性

- **虚拟线程**：JDK 21+ 下 `spring.threads.virtual.enabled=true` 启用，业务线程池自动切换为虚拟线程
- **GraalVM native-image**：`FastInvokerGenerator` 在 native-image 下自动降级为 `MethodHandle` 调用，`reflect-config.json` 已配置
- **WebSocket**：基于 Jakarta WebSocket 的自动配置

---

## 版本选择建议

| 你的场景 | 推荐分支 |
|---------|---------|
| 现有项目基于 Servlet 容器，JDK 8/11 | `2.7.x` |
| 新项目或已迁移到 JDK 17+ | `3.2.x` |
| 需要使用虚拟线程（JDK 21） | `3.2.x` |
| 需要 GraalVM native-image 编译 | `3.2.x` |
| 需要 WebSocket 支持 | `3.2.x` |

---

## 跨分支迁移

### 从 2.7.x 迁移到 3.2.x

主要差异：

1. **JDK 要求**：8+ → 17+
2. **Servlet API**：`javax.servlet` → `jakarta.servlet`（import 需批量替换）
3. **Hibernate Validator**：`javax.validation` → `jakarta.validation`
4. **@PostConstruct 等**：`javax.annotation` → `jakarta.annotation`

### 从 3.2.x 迁移到 Spring Boot 4.x

主要差异：

1. **Spring Framework 7.x**：`MediaType.APPLICATION_STREAM_JSON` 等常量移除，使用 `MediaTypeUtils` 适配
2. **HttpHeaders 不再继承 MultiValueMap**：使用 `WebHttpHeaders` 替代，通过 `MethodHandle` 调用 `asMultiValueMap()`
3. **MediaType 比较器**：`MediaType.SPECIFICITY_COMPARATOR`、`MediaType.sortBySpecificity()` 等方法移除，使用 `MediaTypeUtils` 替代