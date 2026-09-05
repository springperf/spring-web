# 14 · starter 自动配置

> [← 返回索引](00-README.md) | 上一篇：[13 · batch 模块](13-batch-module.md) | 下一篇：[15 · 性能优化全览](15-performance-optimizations.md)

---

## 引子：让框架像 Spring Boot 应用一样"开箱即用"

前 13 篇的组件——`WebContext`、`MappingRegistry`、`DispatcherHandler`、`NettyHttpServer`——它们如何被 Spring Boot 容器发现、装配、启动？

`spring-boot-starter-web` 模块的职责是**把框架的 40+ 个核心组件通过 Spring Boot 的自动配置机制（条件注解 + `AutoConfiguration.imports`）组装成一个开箱即用的 Web 服务器**。用户只需引入一个 starter 依赖，所有组件自动就位，配置通过 `application.properties` 覆盖。

**核心问题**：10 个 `AutoConfiguration` 类如何分工？条件装配链如何编排？Actuator、OpenAPI、SBA、Spring Data 等生态组件如何桥接？SB3 与 SB4 的事件适配如何兼容？

---

## 一、自动配置体系总览

### 1.1 注册入口

`spring-boot-starter-web` 在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 中注册了 10 个自动配置类（Spring Boot 3.x 标准格式，取代了旧版 `spring.factories`）：

```properties
io.springperf.web.autoconfigure.SpringWebAutoConfiguration
io.springperf.web.autoconfigure.WebServerInitializedEventAutoConfiguration
io.springperf.web.autoconfigure.Boot4WebServerInitializedEventAutoConfiguration
io.springperf.web.autoconfigure.SpringWebSupportAutoConfiguration
io.springperf.web.autoconfigure.ActuatorEndpointAutoConfiguration
io.springperf.web.autoconfigure.SpringDataWebCompatibilityAutoConfiguration
io.springperf.web.autoconfigure.SpringBootAdminClientAutoConfiguration
io.springperf.web.autoconfigure.OpenApiAutoConfiguration
io.springperf.web.autoconfigure.SwaggerUiAutoConfiguration
io.springperf.web.autoconfigure.SpringWebBatchAutoConfiguration
```

额外在 `spring.factories` 中注册了一个 `ApplicationContextFactory`：

```properties
org.springframework.boot.ApplicationContextFactory=\
io.springperf.web.autoconfigure.support.WebServerApplicationContextFactory
```

`WebServerApplicationContextFactory`（`@Order(-10000)`）用 `AotDetector.useGeneratedArtifacts()` 区分上下文类型：AOT/native 下返回 `GenericApplicationContext`（AOT 初始化器直接注册 bean 定义，无需运行时注解扫描），否则返回 `AnnotationConfigApplicationContext`。**对齐 Spring Boot 官方行为**——若一律返回注解驱动上下文，native 下 refresh 时反射实例化 `ConfigurationClassPostProcessor` 会因缺 hints 崩溃（`NoSuchMethodException`）。

### 1.2 10 个配置类的职责分工

| # | 配置类 | 条件 | 职责 |
|---|--------|------|------|
| 1 | `SpringWebAutoConfiguration` | `DispatcherHandler` 在类路径 | 核心装配：WebContext、NettyHttpServer、Validator、AccessLog、Micrometer 指标、Spring MVC 冲突检测 |
| 2 | `WebServerInitializedEventAutoConfiguration` | SB3 事件类存在 | SB3 专属：Netty 启动后发射 `WebServerInitializedEvent` |
| 3 | `Boot4WebServerInitializedEventAutoConfiguration` | SB4 事件类存在 | SB4 专属：运行时 ASM 生成事件子类并发射 |
| 4 | `SpringWebSupportAutoConfiguration` | `ServletAdapterContext` 在类路径 | Support 桥接层：DispatcherHandler、InterceptorRegistry、FilterWrapper、WebMvcConfigurerBridge、Session 管理 |
| 5 | `ActuatorEndpointAutoConfiguration` | `ExposableWebEndpoint` 在类路径 | Actuator 端点：扫描、注册、管理端口 |
| 6 | `SpringDataWebCompatibilityAutoConfiguration` | `ProjectingArgumentResolverRegistrar` 存在，`RequestMappingHandlerAdapter` 不存在 | Spring Data 兼容：移除冲突的 BPP |
| 7 | `SpringBootAdminClientAutoConfiguration` | SBA `ApplicationFactory` 在类路径 | SBA 客户端：框架感知的 `ApplicationFactory` |
| 8 | `OpenApiAutoConfiguration` | `OpenApiCustomizer` 在类路径 | OpenAPI 文档：路由暴露 |
| 9 | `SwaggerUiAutoConfiguration` | `OpenApiCustomizer` 在类路径 | Swagger UI：端点 + 静态资源 |
| 10 | `SpringWebBatchAutoConfiguration` | `BatchMapping` 在类路径 | Batch 模块：`BatchRegistry` + Micrometer 指标 |

---

## 二、核心自动配置：`SpringWebAutoConfiguration`

### 2.1 条件守卫

`SpringWebAutoConfiguration` 使用 `@ConditionalOnClass(DispatcherHandler.class)`作为入口条件。由于 `DispatcherHandler` 是 `spring-web`（核心模块）的类，只要框架在类路径上，条件就满足。

### 2.2 Bean 定义

配置类定义了 5 个核心 Bean：

| Bean | 方法 | 条件 | 说明 |
|------|------|------|------|
| `DispatcherHandler` | `dispatcherHandler()` | `@ConditionalOnMissingBean` | 中央分发器，可被用户自定义覆盖 |
| `ApplicationProperties` | `applicationProperties()` | `@ConditionalOnMissingBean` | 类型安全的配置属性 |
| `WebContext` | `webContext()` | `@ConditionalOnMissingBean` | 顶层组件容器，驱动全部生命周期 |
| `NettyHttpServer` | `nettyHttpServer()` | `@ConditionalOnMissingBean` | Netty 服务器，`SmartLifecycle` 启停 |
| `Validator` | `validator()` | `@ConditionalOnMissingBean` + `@ConditionalOnClass(name = "jakarta.validation.Validator")` | 可选 Bean Validation |
| `AccessLogWebFilter` | `accessLogWebFilter()` | `@ConditionalOnProperty(name = "server.accesslog.enabled", havingValue = "true")` | 可选访问日志 |

### 2.3 `WebContext` 的初始化链

`WebContext` 的构造方法是整个框架的启动入口：

```java
// SpringWebAutoConfiguration.java
@Bean @ConditionalOnMissingBean
public WebContext webContext(List<DispatcherHandler> dispatcherHandlers, ApplicationProperties props) {
    return new WebContext(dispatcherHandlers.get(0), props);
}
```

`WebContext` 虽实现 `InitializingBean`，但 `afterPropertiesSet()` 是 no-op。真正的三阶段生命周期由 `NettyHttpServer.start()` 触发（`NettyHttpServer` 是 `SmartLifecycle`，`phase=Integer.MAX_VALUE` 最后启动），详见 [03 篇](03-component-lifecycle.md) 四节：

```
Spring 容器 refresh 完毕，所有 Bean 就绪
  ↓
LifecycleProcessor 按 phase 升序调 SmartLifecycle.start()
  ↓
NettyHttpServer.start()                    (phase=MAX_VALUE 最后启动)
  ├─ webContext.startLifecycle()
  │    ├── 1. initWithWebContext()     → 依赖装配，Registry 间引用注入
  │    ├── 2. initComponentPhase1()    → 扫描 Spring Bean，注册 Mapping
  │    ├── 3. initComponentPhase2()    → 构建内部数据结构，Batch 安装
  │    └── 4. initComponentPhase3()    → 预热，fail-fast 校验
  │
  └─ bootstrap.bind(port)              → Netty 端口绑定（在生命周期之后）
```

### 2.4 `NettyHttpServer` 的 SSL 支持

`nettyHttpServer()` 方法通过 `SslContextFactory.createServerSslContext` 读取 `server.ssl.*` 配置，有条件地创建 SSL 上下文：

```java
// SpringWebAutoConfiguration.java
public NettyHttpServer nettyHttpServer(WebContext webContext, Environment environment,
                                       ObjectProvider<PipelineCustomizer> pipelineCustomizerProvider) {
    boolean http2Enabled = environment.getProperty("server.http2.enabled", boolean.class, false);
    SslContext sslContext = SslContextFactory.createServerSslContext(environment, "server.ssl.", http2Enabled);
    NettyHttpServer server = new NettyHttpServer(webContext, sslContext, pipelineCustomizerProvider.getIfAvailable());
    webContext.registerWebComponent(server);
    return server;
}
```

`PipelineCustomizer` 通过 `ObjectProvider` 注入（可选），允许用户在 Netty pipeline 中插入自定义 handler。

### 2.5 Micrometer 指标集成

`MicrometerWebMetricsConfiguration`是 `SpringWebAutoConfiguration` 的内部配置类，在 `MeterRegistry` 在类路径时生效：

```java
// SpringWebAutoConfiguration.java
@Bean @ConditionalOnMissingBean
public WebMetrics micrometerWebMetrics(io.micrometer.core.instrument.MeterRegistry meterRegistry,
                                        NettyHttpServer nettyHttpServer) {
    // Netty 层 Gauge
    Gauge.builder("netty.connections.active",
            NettyMetricsHandler.INSTANCE, NettyMetricsHandler::getActiveConnectionCount)
            .description("Active TCP connections")
            .register(meterRegistry);

    Gauge.builder("netty.eventloop.pending.tasks",
            nettyHttpServer, server -> { ... })
            .description("Pending tasks across all EventLoops")
            .register(meterRegistry);

    return new MicrometerWebMetrics(meterRegistry);
}
```

注册的指标：

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `netty.connections.active` | Gauge | 当前活跃 TCP 连接数 |
| `netty.eventloop.pending.tasks` | Gauge | 所有 EventLoop 的待处理任务总数 |
| `dispatcher.request.duration` | Timer | 请求处理耗时，tag: `method`, `path`, `status` |
| `dispatcher.exception` | Counter | 异常计数，tag: `type`, `resolved` |
| `pool.{name}.active.threads` | Gauge | 业务线程池活跃线程数 |
| `pool.{name}.queue.size` | Gauge | 业务线程池队列大小 |
| `pool.{name}.completed.tasks` | Gauge | 业务线程池已完成任务数 |

### 2.6 Spring MVC 冲突检测

`springMvcConflictGuard()`使用 `BeanFactoryPostProcessor` 在容器初始化早期检测 `spring-boot-starter-web`（Tomcat）与框架的冲突：

```java
// SpringWebAutoConfiguration.java
@Bean
public static BeanFactoryPostProcessor springMvcConflictGuard() {
    return beanFactory -> assertNoSpringMvcConflict();
}

private static void assertNoSpringMvcConflict() {
    try { Class.forName("org.springframework.web.servlet.DispatcherServlet"); }
    catch (ClassNotFoundException e) { return; }
    throw new IllegalStateException(
            "Detected spring-boot-starter-web on the classpath. ...");
}
```

`BeanFactoryPostProcessor` 在 bean 定义加载后、实例化前执行（ 注释 D9），比原 `webContext` bean 方法（实例化阶段）更早暴露问题。用户如果同时引入了 `spring-boot-starter-web`（Tomcat）和本框架的 starter，会在启动初期立即收到 `IllegalStateException`，避免两个 Web 服务器冲突。

---

## 三、Support 桥接自动配置：`SpringWebSupportAutoConfiguration`

### 3.1 条件与入口

`SpringWebSupportAutoConfiguration` 使用 `@ConditionalOnClass(name = "io.springperf.web.support.servlet.context.ServletAdapterContext")`，仅在 `spring-web-support` 在类路径时生效。它实现了 `ApplicationContextAware`，在初始化时持有 Spring `ApplicationContext` 引用，用于 `FilterWrapper` 中的 `DelegatingFilterProxy` 解析。

### 3.2 Bean 定义

配置类定义了 10 个 Bean：

| Bean | 说明 |
|------|------|
| `supportDispatcherHandler` | 扩展 `DispatcherHandler`，增加 `RequestContextHolder` 初始化 |
| `supportInterceptorRegistry` | 自动扫描 Spring MVC `HandlerInterceptor` Bean |
| `supportHttpBodyCodecInterceptorRegistry` | 自动扫描 `@ControllerAdvice` 中的 `RequestBodyAdvice`/`ResponseBodyAdvice` |
| `httpServletRequestProvider` | 解析 `HttpServletRequest` 参数 |
| `httpServletResponseProvider` | 解析 `HttpServletResponse` 参数 |
| `webRequestArgumentResolverProvider` | 解析 `WebRequest`/`NativeWebRequest` 参数 |
| `supportWebFilterRegistry` | 自动注册 `jakarta.servlet.Filter` Bean 为 `WebFilter` |
| `responseBodyEmitterReturnValueResolver` | 支持 `ResponseBodyEmitter`/`SseEmitter` |
| `webMvcConfigurerBridge` | 桥接 `WebMvcConfigurer` 实现 |
| `perfHttpSessionManager` | 会话管理 |

### 3.3 `FilterWrapper` 转换

`supportWebFilterRegistry`创建 `SupportWebFilterRegistry`，并注册 `AbstractFilterRegistrationBean` 的工厂方法 `createFilterWrapper`：

```java
// SpringWebSupportAutoConfiguration.java
protected WebFilterRegistration createFilterWrapper(AbstractFilterRegistrationBean<?> filterRegistrationBean) {
    jakarta.servlet.Filter filter;
    try {
        filter = filterRegistrationBean.getFilter();
    } catch (IllegalArgumentException e) {
        if (filterRegistrationBean instanceof DelegatingFilterProxyRegistrationBean) {
            // 通过反射调用 getTargetBeanName() 获取代理目标 Bean 名
            String targetBeanName = resolveTargetBeanName(...);
            filter = applicationContext.getBean(targetBeanName, jakarta.servlet.Filter.class);
        }
    }
    FilterWrapper wrapper = new FilterWrapper(filter);
    // ... 读取 order 和 urlPatterns
}
```

关键点：`DelegatingFilterProxyRegistrationBean.getTargetBeanName()` 是 `protected` 方法，通过 `ReflectionUtils.findMethod` + `ReflectionUtils.makeAccessible` 反射调用。

---

## 四、Actuator 端点集成：`ActuatorEndpointAutoConfiguration`

### 4.1 条件与配置

`ActuatorEndpointAutoConfiguration` 使用 `@ConditionalOnClass({ExposableWebEndpoint.class, ApiVersion.class})`，仅在 `spring-boot-actuator` 在类路径时生效。同时通过 `@EnableConfigurationProperties`加载 `WebEndpointProperties` 和 `CorsEndpointProperties`。

### 4.2 组件定义

配置类定义了 6 个 Bean：

| Bean | 说明 |
|------|------|
| `perfMappingDescriptionProvider` | 将框架路由暴露到 Actuator 的 `/actuator/mappings` |
| `endpointMediaTypes` | Actuator 端点媒体类型配置 |
| `webEndpointDiscoverer` | Spring Boot 标准的端点发现器，扫描 `@Endpoint`/`@WebEndpoint` |
| `perfExposeExcludePropertyEndpointFilter` | 读取 `management.endpoints.web.exposure` 配置 |
| `perfEndpointHandlerMapping` | 核心：将 Actuator 端点注册为框架路由 |
| `managementServerInfrastructure` | 管理端口基础设施（`management.server.port` 配置时创建） |
| `managementNettyHttpServer` | 独立管理端口的 Netty 服务器 |

### 4.3 端点注册流程

`ActuatorEndpointHandlerMapping.initComponentPhase1()`是 Actuator 端点的注册入口：

1. 获取 `MappingRegistry`，读取 `WebEndpointProperties.getBasePath()`（默认 `/actuator`）。
2. 注册 CORS 配置（从 `CorsEndpointProperties` 读取）。
3. 注册 `LinksOperationInvoker`（`/actuator` 根路径，返回端点链接列表）。
4. 遍历 `WebEndpointsSupplier.getEndpoints()`，对每个端点的每个 `WebOperation`：
   - 构建 `OperationHandlerInvoker`（，适配 `WebOperation` 调用为 `Invoker` 接口）。
   - 创建 `ActuatorPathMappingContext`（，继承 `PathMappingContext`，含 `WebServerNamespace`）。
   - 调用 `registerRoute`，根据是否有管理端口注册到主 `MappingRegistry` 或管理端口 `MappingRegistry`。

### 4.4 管理端口隔离

`management.server.port` 配置时，`ManagementServerInfrastructure`创建独立的组件容器（`WebContext` 的副本），包含独立的 `DispatcherHandler`、`MappingRegistry` 和 `NettyHttpServer`。管理端口只处理 Actuator 端点，业务请求不进入管理端口，管理端口的负载不影响业务端口。

`management.server.port` 与 `server.port` 必须不同（，`IllegalStateException` 校验）。

---

## 五、多版本兼容性：SB3 / SB4 事件适配

### 5.1 为什么需要适配

Spring Boot 3 的事件类在 `org.springframework.boot.web.context` 包，Spring Boot 4 移到了 `org.springframework.boot.web.server.context` 包，且事件从具体类变为抽象类。框架需要在不破坏编译一次（"compile once, run anywhere"）的前提下兼容两个版本。

### 5.2 策略：字符串条件守卫

两个配置类使用字符串形式的 `@ConditionalOnClass`，不触发类加载，仅按名称探测 classpath：

```java
// SB3 配置
@ConditionalOnClass(name = "org.springframework.boot.web.context.WebServerInitializedEvent")
public class WebServerInitializedEventAutoConfiguration { ... }

// SB4 配置
@ConditionalOnClass(name = "org.springframework.boot.web.server.context.WebServerInitializedEvent")
public class Boot4WebServerInitializedEventAutoConfiguration { ... }
```

SB3 下只有第一个配置类加载，SB4 下只有第二个配置类加载，互斥。

### 5.3 SB3 方案：`PerfWebServerInitializedEvent`

`WebServerInitializedEventAutoConfiguration`在 `ApplicationReadyEvent` 事件中发射 `PerfWebServerInitializedEvent`：

```java
// WebServerInitializedEventAutoConfiguration.java
public ApplicationListener<ApplicationReadyEvent> webServerInitializedEventPublisher(
        NettyHttpServer nettyHttpServer, ApplicationContext applicationContext) {
    return event -> {
        if (nettyHttpServer.isRunning()) {
            WebServer webServer = new PerfWebServer(nettyHttpServer.getActualPort(), nettyHttpServer);
            applicationContext.publishEvent(new PerfWebServerInitializedEvent(webServer, applicationContext));
        }
    };
}
```

`PerfWebServerInitializedEvent`继承 SB3 的 `WebServerInitializedEvent`，通过 JDK 动态代理将 `AnnotationConfigApplicationContext`（非 `WebServerApplicationContext`）包装为 `WebServerApplicationContext` 接口——仅覆盖 `getWebServer()` 返回本框架的 `PerfWebServer`，其他方法委托给真实上下文。

### 5.4 SB4 方案：`Boot4WebServerInitializedEventBridge`

SB4 的 `WebServerInitializedEvent` 变为抽象类，只有 `ServletWebServerInitializedEvent`/`ReactiveWebServerInitializedEvent` 两个具体子类，构造参数绑定 servlet/reactive 上下文，无法直接实例化。

`Boot4WebServerInitializedEventBridge`使用 Spring 内嵌 ASM 在运行时生成事件的具体子类字节码：

1. **`generateEventSubclass()`**：用 `ClassWriter` 生成一个继承抽象类的子类，包含 `applicationContext` 字段和 `getApplicationContext()` 方法。
2. **`createContextProxy()`**：JDK 动态代理将真实 `ApplicationContext` 包装为 SB4 的 `WebServerApplicationContext` 接口。
3. **`createEvent()`**：`MethodHandles.lookup().defineClass(bytes)` 定义生成的子类，反射实例化，C6 双检锁保护 `defineClass` 幂等性。

```java
// Boot4WebServerInitializedEventBridge.java
private static Object createEvent(...) throws Exception {
    // C6: defineClass 幂等瓶颈——同一名称的类只能定义一次
    Class<?> generated = generatedEventClass;
    if (generated == null) {
        synchronized (Boot4WebServerInitializedEventBridge.class) {
            generated = generatedEventClass;
            if (generated == null) {
                byte[] bytes = generatedBytes;
                if (bytes == null) {
                    bytes = generateEventSubclass();
                    generatedBytes = bytes;
                }
                generated = MethodHandles.lookup().defineClass(bytes);
                generatedEventClass = generated;
            }
        }
    }
    Constructor<?> constructor = generated.getDeclaredConstructor(webServerInterface, contextInterface);
    return constructor.newInstance(webServer, contextProxy);
}
```

全程零新增依赖（ASM 由 spring-core 提供），`Boot4WebServerInitializedEventAutoConfiguration` 的 `ApplicationListener` 捕获 `Throwable` 降级，桥接失败仅告警，不影响应用启动。

### 5.5 GraalVM 可达性提示

`SpringWebRuntimeHints`实现 `RuntimeHintsRegistrar`，为 SB3 事件路径所需的 JDK 动态代理和反射注册可达性提示：

```java
// SpringWebRuntimeHints.java
public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // SB3 事件路径：JDK 动态代理包装 WebServerApplicationContext
    hints.proxies().registerJdkProxy(WebServerApplicationContext.class);
    hints.reflection().registerType(PerfWebServer.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
    // SB4 类型按名条件注册（仅 classpath 存在时注册）
    hints.reflection().registerTypeIfPresent(classLoader, SB4_EVENT_CLASS);
    // ...
}
```

`@ImportRuntimeHints` 挂在 SB3 专属配置上，SB3 下 AOT 构建期执行，SB4 下配置类不加载、registrar 不执行，避免编译期引用 SB3 类型在 SB4 classpath 缺失时引发类解析失败。

**补齐内容（3.2.6+）**：除事件路径外，`SpringWebRuntimeHints` 还注册：
- `ListenableFutureCallback` JDK 代理 + `ListenableFuture#addCallback` 反射（异步返回路径，`ListenableFutureAdapter` 需要）；
- 框架强依赖资源：`additional-spring-configuration-metadata.json`、`templates/`/`static/`/`META-INF/resources/`/`public/`（经 `FilePatternResourceHintsRegistrar` 按实际存在文件注册）。

### 5.6 用户控制器 AOT hints：`ControllerBeanFactoryInitializationAotProcessor`

框架用自有 `MappingRegistry`（`getBeansWithAnnotation(Controller.class)` + `getUniqueDeclaredMethods`）扫描 `@Controller`，Spring Boot AOT 只为 Spring MVC 的 `RequestMappingHandlerMapping` 自动生成 hints——**感知不到本框架控制器**。因此在 `META-INF/spring/aot.factories` 注册了 `BeanFactoryInitializationAotProcessor`：

```java
// ControllerBeanFactoryInitializationAotProcessor.processAheadOfTime(beanFactory)
// 1. beanFactory.getBeanNamesForAnnotation(Controller.class) → 目标类
// 2. beanFactory.getBeanNamesForAnnotation(ControllerAdvice.class) → @ControllerAdvice
// 3. getUniqueDeclaredMethods + @RequestMapping 过滤 → 处理方法
//    @ControllerAdvice: @ExceptionHandler / @InitBinder / @ModelAttribute 反射调用方法
// 4. applyTo: registerMethod(method, INVOKE) + 控制器/advice 类 INVOKE_*
//              + BindingReflectionHintsRegistrar + DTO DECLARED_FIELDS
//              + 泛型参数递归展开（ResponseEntity<Map<String, User>> → User）
```

`process-aot` 端到端效果（`spring-web-example-rest`）：`HealthController`/`UserController` 全部方法进入 `reflect-config.json` 的 `methods`（INVOKE）段，`User`/`ApiResult` DTO 注册字段/构造器 hints；`GlobalExceptionHandler`（`@RestControllerAdvice`）的 `handleValidation`/`handleIllegalArg`/`handleUnknown` 同样进入 `methods` 段。示例模块默认构建绑定 `process-aot`，原生编译用 `-Pnative`（`scripts/native-smoke-test.sh`，Linux + GraalVM）。

### 5.7 WebSocket 端点 AOT hints：`ServerEndpointBeanFactoryInitializationAotProcessor`

`spring-web-websocket` 模块经自身的 `aot.factories` 注册 `BeanFactoryInitializationAotProcessor`，为 Bean 发现的 `@ServerEndpoint` 端点注册反射 hints：

- 无参构造器（`INVOKE_DECLARED_CONSTRUCTORS`）——`JsrEndpointWebSocketHandler.getDeclaredConstructor().newInstance()`；
- `@OnOpen`/`@OnMessage`/`@OnClose`/`@OnError` 回调方法（`INVOKE`）——`setAccessible + invoke`。

与运行时 `JsrEndpointScanner` 的 native 守卫配套：native 下跳过 classpath 扫描、只走 Bean 发现，故 `@ServerEndpoint` 端点需显式注册为 Spring Bean。

---

## 六、生态集成

### 6.1 Spring Data 兼容

`SpringDataWebCompatibilityAutoConfiguration`解决 Spring Data Common 的 `ProjectingArgumentResolverRegistrar` 与框架的冲突。

问题根因：Spring Data 2.7.x 通过 `@ConditionalOnClass(WebMvcConfigurer.class)` 检测 Web 环境，框架的 `spring-web-support` 提供了 `WebMvcConfigurer` 同名 shim 接口，导致 `@EnableSpringDataWebSupport` 被触发，其内部 `BeanPostProcessor` 引用了 `RequestMappingHandlerAdapter.class`（Spring MVC 类），而框架不包含该类，导致 `NoClassDefFoundError`。

解决方案：`BeanDefinitionRegistryPostProcessor` 在 bean 定义阶段（实例化前）移除冲突的 `BeanPostProcessor`：

```java
// SpringDataWebCompatibilityAutoConfiguration.java
public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
    if (registry.containsBeanDefinition(BPP_BEAN_NAME)) {
        registry.removeBeanDefinition(BPP_BEAN_NAME);
        log.info("Removed ProjectingArgumentResolverRegistrar's BeanPostProcessor ...");
    }
}
```

Spring Data 的投影解析仍通过 `WebMvcConfigurerBridge` 桥接——`SpringDataWebConfiguration` 实现 `WebMvcConfigurer.addArgumentResolvers()`，与框架的 `WebMvcConfigurerBridge` 一起工作。

### 6.2 Spring Boot Admin (SBA)

`SpringBootAdminClientAutoConfiguration`在 SBA Client 在类路径时提供框架感知的 `PerfApplicationFactory`，替换 SBA 默认的 `DefaultApplicationFactory`/`ServletApplicationFactory`：

```java
// SpringBootAdminClientAutoConfiguration.java
@Bean @ConditionalOnMissingBean(ApplicationFactory.class)
public PerfApplicationFactory perfApplicationFactory(
        InstanceProperties instanceProperties,
        ManagementServerProperties managementServerProperties,
        ServerProperties serverProperties,
        WebEndpointProperties webEndpointProperties,
        Environment environment) { ... }
```

`PerfApplicationFactory` 从框架配置中自动计算 `serviceUrl`/`managementUrl`/`healthUrl`，无需依赖 Servlet 容器。`@AutoConfigureBefore` 确保本配置在 SBA 的 `AdminClientAutoConfiguration` 之前执行，优先使用框架的 `ApplicationFactory`。

### 6.3 OpenAPI / Swagger UI

`OpenApiAutoConfiguration`在 `springdoc-openapi` 在类路径时，注册 `OpenApiCustomizer` Bean，调用 `OpenApiAdapter` 将框架 `MappingRegistry` 中的路由写入 OpenAPI 文档。

`SwaggerUiAutoConfiguration`提供三个端点：

| 端点 | 说明 |
|------|------|
| `OpenApiDocController` | `/v3/api-docs`（OpenAPI 规范）、`/v3/api-docs/swagger-config`（Swagger UI 配置）、`/swagger-ui.html`（重定向到 `/swagger-ui/index.html`） |
| `swaggerUiResourceHandler` | 从 Swagger UI webjar 中提供静态资源（`/swagger-ui/**`） |

`OpenApiAdapter`遍历 `PathMappingContext` 列表，提取 HTTP 方法、路径参数、查询参数、返回值类型，构建 Swagger `PathItem`/`Operation` 对象。路径清理（`cleanPathForOpenApi`）处理框架特有的路径模式：`{name:regex}` → `{name}`、尾部 `**` → `/{any}`。

### 6.4 Batch 模块

`SpringWebBatchAutoConfiguration`在 `@BatchMapping` 在类路径时创建 `BatchRegistry` 并注册到 `WebContext`。内嵌 `MicrometerBatchMetricsConfiguration` 在 `MeterRegistry` 存在时提供 Micrometer 指标实现。

`MicrometerBatchMetrics`缓存 8 个指标（Couter/Timer/DistributionSummary/Gauge），每个指标按 `queue` Tag 区分。`ConcurrentHashMap.computeIfAbsent` 原子性保证每个队列的指标只注册一次，避免 Micrometer 对同名同 tag 二次注册抛异常。

---

## 七、对比 Spring Boot MVC 自动配置

| 维度 | 本框架 starter | Spring Boot MVC starter |
|------|---------------|------------------------|
| 自动配置类数量 | 10 个 | 30+ 个（`spring-boot-autoconfigure`） |
| 核心 Server | `NettyHttpServer`（`SmartLifecycle`） | `TomcatServletWebServerFactory`（`WebServerFactory`） |
| 条件装配 | 全部 `@ConditionalOnClass` 字符串形式 | 同 Spring Boot 标准 |
| 应用上下文 | `AnnotationConfigApplicationContext`（强制） | `AnnotationConfigServletWebServerApplicationContext` |
| 生命周期驱动 | `NettyHttpServer.start()` → `WebContext.startLifecycle()` 三阶段（`afterPropertiesSet` 是 no-op） | `DispatcherServlet.onRefresh()` → `initStrategies()` |
| Filter 发现 | `SupportWebFilterRegistry` 自动注册 `jakarta.servlet.Filter` Bean | `FilterRegistrationBean` + `@Component`/`@WebFilter` |
| 拦截器发现 | `SupportInterceptorRegistry` 自动注册 `HandlerInterceptor` Bean | `WebMvcConfigurer.addInterceptors()` |
| 线程池 | 默认 EventLoop 执行，`@RunInPool` 切换 | 默认 `Tomcat` 线程池 |
| Actuator 集成 | 自定义 `ActuatorEndpointHandlerMapping`（WebComponent 生命周期） | `WebMvcEndpointHandlerMapping`（Spring MVC） |
| 管理端口 | 独立 `NettyHttpServer` + 独立 `WebContext` | 独立 `Tomcat` + `WebMvcEndpointHandlerMapping` |
| OpenAPI 集成 | 自定义 `OpenApiAdapter` 遍历 `MappingRegistry` | SpringDoc 自动扫描 `RequestMappingHandlerMapping` |
| SBA 集成 | 自定义 `PerfApplicationFactory` | `ServletApplicationFactory` |
| Spring Data 兼容 | `BeanDefinitionRegistryPostProcessor` 移除冲突 BPP | 原生支持（`RequestMappingHandlerAdapter` 存在） |
| SB3/SB4 兼容 | 字符串条件 + ASM 运行时生成（SB4 事件） | 两版本分别发布 |
| GraalVM 支持 | `RuntimeHintsRegistrar` 条件注册 | `RuntimeHintsRegistrar` + AOT 构建期 |
| 冲突检测 | `BeanFactoryPostProcessor` 早期检测 `DispatcherServlet` | 无（Tomcat 是默认） |

**核心差异**：Spring Boot MVC 的自动配置围绕 `DispatcherServlet` 和 Servlet 容器（Tomcat/Undertow/Jetty）展开，30+ 个配置类覆盖了数据源、事务、安全、验证等企业级功能。本框架的 starter 只有 10 个配置类，聚焦于**让 Netty 框架在 Spring Boot 容器中"开箱即用"**，以及**与 Spring 生态主要组件（Actuator、SBA、OpenAPI、Spring Data）的桥接**。框架的模块化设计（`spring-web` / `spring-web-support` / `spring-web-batch`）通过条件注解自然映射到自动配置的开关——用户只需要引入对应模块的依赖，相关配置自动激活。

---

## 八、小结：starter 模块的克制在哪里

回到引子的问题：40+ 个核心组件如何被 Spring Boot 自动装配？

1. **10 个 `AutoConfiguration` 类分工明确** → 核心（`SpringWebAutoConfiguration`）、桥接（`SpringWebSupportAutoConfiguration`）、Actuator（`ActuatorEndpointAutoConfiguration`）、生态集成（OpenAPI/SBA/Spring Data/Batch），各司其职，条件注解自然隔离。
2. **`WebContext` 驱动生命周期** → `NettyHttpServer.start()`（`SmartLifecycle` 最后启动）触发 `WebContext.startLifecycle()` 三阶段，`afterPropertiesSet()` 是 no-op。
3. **`ActuatorEndpointHandlerMapping` 通过 `WebComponent` 生命周期注册路由** → Phase 1 扫描 Actuator 端点，Phase 2 触发路由优化器，与管理端口基础设施无缝集成。
4. **SB3/SB4 事件适配** → 字符串条件守卫互斥加载，SB3 走 JDK 动态代理包装 `WebServerApplicationContext`，SB4 走 ASM 运行时生成事件子类，`RuntimeHintsRegistrar` 条件注册 GraalVM 提示。
5. **生态桥接** → `FilterWrapper` 转换 `jakarta.servlet.Filter`，`WebMvcConfigurerBridge` 桥接 `WebMvcConfigurer`，`OpenApiAdapter` 遍历 `MappingRegistry`，`PerfApplicationFactory` 替换 SBA 默认工厂。
6. **冲突检测** → `BeanFactoryPostProcessor` 在容器初始化早期检测 `spring-boot-starter-web` 冲突，`BeanDefinitionRegistryPostProcessor` 移除 Spring Data 的冲突 BPP。

这一层的克制体现在：**不重复造 Spring Boot 的轮子，也不为了"自动配置"引入复杂的条件逻辑。** 10 个配置类每个都只做一件事——创建核心 Bean、桥接 Support 模块、集成 Actuator、适配生态组件。条件注解用的都是 `@ConditionalOnClass` 字符串形式（不触发类加载），`@ConditionalOnMissingBean` 允许用户覆盖。`PerfWebServer` 只有 3 个方法（`start`/`stop`/`getPort`），`WebServerApplicationContextFactory` 只有 4 行——"足够简单到不需要文档"的接口，是本框架的设计哲学在自动配置层的体现。

---

> **下一篇**：[15 · 性能优化全览](15-performance-optimizations.md)——零反射、零分配、零锁、线程切换优化：从 JSON 序列化到 EventLoop 绑定的完整性能策略。