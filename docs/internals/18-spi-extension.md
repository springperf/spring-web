# 18 · 内部 SPI 发现与调用机制

> [← 返回索引](00-README.md) | 上一篇：[17 · Benchmark 数据解读](17-benchmark-data.md) | 下一篇：[19 · 关键设计决策记录](19-design-decisions.md)

---

## 引子：SPI 不是"注解扫描"，是"类型扫描 + Ordered 排序"

`extensions.md` 讲了用户视角的 12 个扩展点怎么用。本篇回答内部视角的问题：**这些 SPI 在框架里到底怎么被找到、怎么排顺序、缓存成什么、请求时从哪里调？**

写作前提是"核对各 SPI 的发现注解与排序机制"。核对后有一个必须先纠正的误解：**框架里没有 `@WebComponent` 注解**。`WebComponent` 是一个 `extends Ordered` 的接口（`WebComponent.java`），SPI 的发现全部走 Spring 原生的 Bean 类型扫描（`getBeansOfType` / `autoRegisterWebComponent` / `getWebComponentWithDefault`），排序全部走 `Ordered.getOrder()` / `@Order` 经 `AnnotationAwareOrderComparator.sort` 统一处理。这带来了一个好处：扩展方只要把实现类注册成 Spring Bean，框架自动发现，零额外注解。

更关键的是，发现不止一种模式——经核对共 **四种**，对应四类扩展需求。本篇先讲统一契约与排序基础设施，再讲四种模式，再给 12 SPI 的四元组总表，最后逐个给最小扩展示例骨架。

---

## 一、统一契约：`WebComponent` 接口

所有可插拔组件的根接口（`WebComponent.java`）：

```java
public interface WebComponent extends Ordered {              
    default String getComponentName() { return getClass().getSimpleName(); }   // :26
    default void initWithWebContext(WebContext webContext) { }                 // :39
    @Override
    default int getOrder() { return Ordered.LOWEST_PRECEDENCE - 10000; }      // :53
}
```

- **`extends Ordered`** 是排序的统一入口。默认 `getOrder()` 返回 `LOWEST_PRECEDENCE - 10000`，注释原话"placing the component before most Spring-managed beans but after framework internals"——即默认排在用户 Bean 之前、框架内置组件之后，用户不写 `@Order` 也能拿到合理位次。
- **`getComponentName()`** 默认返回类简单名，用于冲突解析与日志（见聚光灯 8 的 `FilterWrapper` 唯一命名）。
- **`initWithWebContext`** 是构造后、分阶段初始化前的依赖注入钩子，各 Registry 在此扫描 Spring 容器。

纠正点：不存在 `@WebComponent` 注解，`annotation/` 包下只有 `@RunInPool`、`@ReactiveSupport`、`@Optimize` 三个运行时注解。SPI 的"发现注解"实际是 Spring 的 `@Component`/`@Configuration`+`@Bean`（让实现成为 Bean），框架按类型取 Bean。

---

## 二、排序基础设施：`WebComponentContainer`

所有 Registry 的共同父类（`WebComponentContainer.java`），它把"扫描 + 排序 + 固化"收口在一处：

| 职责 | 方法 | 行号 | 作用 |
|------|------|------|------|
| 登记映射 | `autoRegisterWebComponent(Class)` / `(Class, Function)` |   | 仅把 `Class→Function` 存入 `autoRegisterComponentMap`，**不立即扫描** |
| 触发扫描 | `initWithWebContext` 遍历 map |  | 对每项调 `registerWebComponent(Class, Function)` → `getBeansOfType(clazz)` 取 Bean 并用 Function 转换 |
| 统一排序 | `getWebComponents` |  | `AnnotationAwareOrderComparator.sort(list)`——Spring 的比较器，识别 `@Order`/`Ordered`/`@Priority` |
| 固化 List | `initRealComponentList` |  | 清空后从 `getWebComponents()` 重填，phase2 调用一次 |
| 冲突解析 | `registerWebComponent(WebComponent)` |  | 同名冲突时 `AnnotationAwareOrderComparator.sort` 选 order 更小者为胜，败者 `destroy` |
| 动态注册 | 同上，按 state 追阶段 |  | 后置注册的组件从当前 state 起补跑未完成的 phase |
| 单实例兜底 | `getWebComponentWithDefault` |  | 先查已注册→无则扫描 Bean→仍无则用 default |
| 状态枚举 | `State` |  | `NEW/INIT_CONTEXT/PHASE1/PHASE2/PHASE3/DESTROY` |

> **排序两层**：通用层是 `AnnotationAwareOrderComparator.sort`（对 `WebComponent` 列表）。特定层是 `AnnotationAwareOrderUtils.findOrder`（框架自带类，非 Spring），用于对**未实现 Ordered 的裸 Spring Bean**（如用户原样注册的 `HandlerInterceptor`/`Filter`）在包装时单独提取 `@Order` 值传给 Registration——见 `InterceptorRegistry.java`、support 模块 `WebComponentWrapper.java`、`SupportInterceptorRegistry.java`。

---

## 三、四种发现模式

核对 12 个 SPI 后，发现机制按扩展需求分四种：

| 模式 | 机制 | 缓存形态 | 适用 SPI |
|------|------|---------|---------|
| **A 批量排序** | `autoRegisterWebComponent` + `initRealComponentList` → 排序后的 `List` | 有序 List | WebFilter、HandlerInterceptor、HandlerExceptionResolver、ReturnValueResolver、StaticArgumentResolverProvider、HttpBodyCodecInterceptor、HttpBodyConverter（7 个多实例） |
| **B 单实例兜底** | `getWebComponentWithDefault(Class, default)` | 单字段引用 | JsonConverter、WebCorsProcessor（+ 各 Registry 自身被 `DispatcherHandler` 注入） |
| **C 命名 Map** | `getBeansOfType` 直收 `Map<name, bean>` | 按名 Map（不排序） | BizPoolRegistry（业务线程池） |
| **D 硬编码继承** | `getOptimizerTemplate()` 返回固定链 + 子类重写 `protected` 方法 | 固定顺序链 | RouterOptimizer |

模式 A 占多数，因为大部分 SPI 是"多条同类型实现按序参与请求处理"。模式 B 用于"全局唯一替换点"。模式 C 用于"命名资源按名取用"。模式 D 是唯一不扫描 Bean 的——扩展靠继承 `MappingRegistry` 重写 `getOptimizerTemplate`，这是文档 19 ADR 10"显式 SPI vs Spring `@Conditional` 自动发现"的一个反例边界。

---

## 四、12 SPI 四元组总表

四元组 = **发现时机 / 排序键 / 缓存产物 / 运行时调用点**。

| # | SPI | 发现时机（file:line） | 排序键 | 缓存产物（file:line） | 运行时调用点（file:line） |
|---|----|---------------------|--------|---------------------|------------------------|
| 1 | `WebFilter` | 构造 `autoRegister`(Registration + WebFilter→wrap) `WebFilterRegistry.java`；phase3 `initAllFilters`  | `AnnotationAwareOrderComparator.sort`（Container ） | `allFilters` List ；`unmatchedChain` ；DCL `resolveFilterChain`；三段式 `initCachedFilters` | `doFilter`  → `DispatcherHandler.handleWithFilter` |
| 2 | `HandlerInterceptor` | 构造 `autoRegister`(Interceptor + Registration→wrap) `InterceptorRegistry.java` | `AnnotationAwareOrderUtils.findOrder` 提取  → `registration.order` | `runtimeMappingInterceptors` List phase2；DCL `getCachedInterceptors`；请求级 `INTERCEPTORS_ATTRIBUTE` | preHandle  / postHandle  / afterCompletion  / afterConcurrent  |
| 3 | `HandlerExceptionResolver` | `initWithWebContext` 注册 2 内置 + `registerWebComponent(scan)` `ExceptionRegistry.java` | `AnnotationAwareOrderComparator.sort` | `resolvers` List phase2 `initRealComponentList` | `doHandle`；`handle`  → `DispatcherHandler.handleException` |
| 4 | `ReturnValueResolver` | `initReturnValueResolver` 13 内置 + scan + `initRealComponentList` `ReturnValueResolverRegistry.java` | `AnnotationAwareOrderComparator.sort` | `resolvers` List；per-method `MethodReturnValueContext` `MAPPING_CACHE_KEY`（lazy `getReturnValueResolver`  + Optional 内层 + async 内层 + 兜底线性） | `resolveReturnValue`→`doResolveReturnValue` → `DispatcherHandler.doHandle` |
| 5 | `StaticArgumentResolverProvider` | `initStaticArgumentResolverProviders` 12 内置 + scan `ArgumentResolverRegistry.java` | `AnnotationAwareOrderComparator.sort` | `staticArgumentResolverProviders` List；per-method `MethodArgContext[]` `MAPPING_CACHE_KEY`。**phase3 `validateAllParametersResolvable` 只查 `supports()` 不创建**；真解析器懒创建于首请求 DCL `getMethodArgContexts`→`initStaticArgResolverSupport` | `resolveArguments` → `DispatcherHandler.doHandle` |
| 6 | `HttpBodyCodecInterceptor` | `initCodecInterceptors` `HttpBodyCodecInterceptorRegistry.java`：`ControllerAdviceBean.findAnnotatedBeans`  + `getBeansOfType`  | `AnnotationAwareOrderComparator.sort`（包 `WebComponentControllerAdviceBean`） | `codecInterceptors` List；per-method `HttpBodyCodecInterceptor[]` `MAPPING_CACHE_KEY`，`isApplicableToBeanType` 过滤  | beforeBodyRead  / afterBodyRead  / handleEmptyBodyRead  / beforeBodyWrite  |
| 7 | `HttpBodyConverter` | `initWithWebContext` 注册 Jackson + `registerWebComponent(HttpMessageConverter→wrap)` + `registerWebComponent(HttpBodyConverter, identity)` scan `HttpBodyCodecRegistry.java` | `AnnotationAwareOrderComparator.sort` | `converters` List；`allSupportedMediaTypes` phase2；`READ_BODY_CONVERTER_CACHE_KEY`；`WRITE_NEGOTIATION_CACHE_KEY`（仅 `@Optimize` 方法，每方法上限 64） | `readBody` （cached / fallback）；`writeBody` （协商 / scan） |
| 8 | `JsonConverter` | `getWebComponentWithDefault(JsonConverter, new JacksonConverter)` `AsyncSupportRegistry.java` | N/A 单实例 | `AsyncSupportRegistry.jsonConverter` 字段 ；`getJsonConverter`  | `SseJsonEmitter` / `StreamJsonEmitter` / `ReactiveReturnValueResolver` |
| 9 | `WebCorsProcessor` | `getWebComponentWithDefault(WebCorsProcessor, new PerfCorsProcessor)` `CorsRegistry.java` | N/A 单实例 | `CorsRegistry.webCorsProcessor` 字段  | `corsHandle`→`webCorsProcessor.process` `CorsRegistry.java` → `DispatcherHandler.doHandle` |
| 10 | `BizPoolRegistry`（线程池） | phase3 `getBeansOfType(ExecutorService)` 收 Map `BizPoolRegistry.java`；phase1 配置建 default 池 | N/A 命名 Map | `pools` Map ；`BIZ_POOL_KEY` per-method 懒解析；`defaultExecuteMode` 缓存 | `determinePool` → `DispatcherHandler.handleWithMappingResult` |
| 11 | `RouterOptimizer` | `getOptimizerTemplate` 硬编码 [Prefix, Suffix, Loop] `MappingRegistry.java` + FullPath 条件加入；**无 Bean 扫描，扩展靠继承重写** | N/A 固定链序 | `optimizers` List ；每优化器 `init` 后 claim 的 mappings 移出 general pool（`RouterOptimizer.init`） | `doMapping` for(optimizer) `optimizeRoute` → `DispatcherHandler.handle` |
| 12 | `WebComponent`（契约） | 非 SPI，基接口；`extends Ordered` `WebComponent.java`；`getOrder` 默认 `LOWEST_PRECEDENCE-10000`  | `AnnotationAwareOrderComparator.sort`（Container ；`initRealComponentList`；`getSortedWebComponents`） | `getWebComponents`；动态注册按 state 追 phase | 各 Registry 通用 |

---

## 五、逐 SPI 最小扩展示例骨架

每个 SPI 给一段最小骨架——用户如何注入自己的实现替换/追加默认行为。模式 A/B 是 `@Bean`，模式 C 是 `@Bean ExecutorService`，模式 D 是继承重写。

### 5.1 WebFilter（模式 A）

```java
@Component
@Order(20)                       // 排序键：@Order，值小先执行
public class MyAuthFilter implements WebFilter {
    @Override
    public void doFilter(WebServerHttpRequest req, WebServerHttpResponse resp, FilterChain chain) {
        if (!authed(req)) { resp.sendError(HttpStatus.UNAUTHORIZED); return; }
        chain.doFilter(req, resp);
    }
}
```
框架在 `WebFilterRegistry` 构造期登记 `WebFilter.class`，phase3 `initAllFilters` 扫描到此 Bean，经三段式 `ALWAYS/RUNTIME/NEVER` 缓存入链。

### 5.2 HandlerInterceptor（模式 A，裸 Bean 单独提序）

```java
@Component
@Order(10)
public class MyInterceptor implements HandlerInterceptor {   // 注意：未实现 WebComponent/Ordered
    @Override public boolean preHandle(WebServerHttpRequest req, WebServerHttpResponse resp) { return true; }
}
```
裸 `HandlerInterceptor` 不实现 `Ordered`，故 `InterceptorRegistry.java` 用 `AnnotationAwareOrderUtils.findOrder` 从 `@Order` 注解读取序号传给 Registration——这是"特定层排序"的典型场景。

### 5.3 HandlerExceptionResolver（模式 A）

```java
@Component
@Order(0)                        // 值小优先，排在 ExceptionHandlerExceptionResolver 之前
public class MyExceptionResolver implements HandlerExceptionResolver {
    @Override
    public boolean resolveException(Exception ex, WebServerHttpRequest req, WebServerHttpResponse resp) {
        if (ex instanceof BizException) { sendError(resp, HttpStatus.BAD_REQUEST); return true; }
        return false;
    }
}
```

### 5.4 ReturnValueResolver（模式 A）

```java
@Component
public class ProtobufReturnValueResolver implements ReturnValueResolver {
    @Override public boolean supportsReturnType(MethodReturnType r) { return r.isAnnotation(Protobuf.class); }
    @Override public void resolveReturnValue(Object ret, MethodReturnType r, WebServerHttpRequest req, WebServerHttpResponse resp) {
        writeProto(resp, ret);
    }
}
```
`initReturnValueResolver` 扫描后，每方法首次调用经 `MethodReturnValueContext` 懒匹配并缓存命中者。

### 5.5 StaticArgumentResolverProvider（模式 A）

```java
@Component
public class CurrentUserArgProvider implements StaticArgumentResolverProvider {
    @Override public boolean supportsParameter(MethodParameter p) { return p.hasParameterAnnotation(CurrentUser.class); }
    @Override public StaticArgumentResolver resolveArgument(MethodParameter p) {
        return ctx -> SecurityContextHolder.get();   // 返回真实解析器
    }
}
```
**注意纠偏**：phase3 `validateAllParametersResolvable` 只调 `supports()` 校验"可达"，注释明言"does NOT create or cache any resolver, keeping memory footprint zero for endpoints that are never called"。真正的 `resolveArgument` 在首请求 DCL `getMethodArgContexts`→`initStaticArgResolverSupport` 才创建并缓存。这与 [15 篇](15-performance-optimizations.md) 优化 1"启动预缓存"的差异见第六节。

### 5.6 HttpBodyCodecInterceptor（模式 A，@ControllerAdvice 扫描）

```java
@ControllerAdvice
@Order(10)
public class TrimCodecInterceptor implements HttpBodyCodecInterceptor {
    @Override public Object afterBodyRead(Object body, HttpBodyReadContext ctx) { return trim(body); }
}
```
两条扫描路径：`@ControllerAdvice` Bean 经 `ControllerAdviceBean.findAnnotatedBeans`，独立 Bean 经 `getBeansOfType`，都包成 `WebComponentControllerAdviceBean` 参与排序。

### 5.7 HttpBodyConverter（模式 A，多来源适配）

```java
@Component
@Order(0)                        // 值小优先协商
public class ProtobufHttpBodyConverter implements HttpBodyConverter {
    @Override public List<MediaType> getSupportedMediaTypes() { return List.of(MEDIA_TYPE_PROTO); }
    @Override public Object read(InputStream in, Class<?> type) { return parseProto(in); }
    @Override public void write(Object body, OutputStream out) { writeProto(body, out); }
}
```
原生 `HttpBodyConverter` Bean 经 `Function.identity()` 注册；Spring `HttpMessageConverter` Bean 经 `toHttpBodyConverter` 包装。converter 集变更时 `registerConverter` 清所有 `liveNegotiationCache`。

### 5.8 JsonConverter（模式 B，单实例替换）

```java
@Bean
JsonConverter fastjsonConverter() {
    return new FastjsonConverter();   // 替换默认 JacksonConverter
}
```
`AsyncSupportRegistry.java` 的 `getWebComponentWithDefault` 发现此 Bean，替换默认 `new JacksonConverter(objectMapper)`。SSE/Stream JSON 场景（`SseJsonEmitter`/`StreamJsonEmitter`）随之切换。

### 5.9 WebCorsProcessor（模式 B，单实例替换）

```java
@Bean
WebCorsProcessor myCorsProcessor() {
    return new MyCorsProcessor();    // 替换默认 PerfCorsProcessor
}
```
`CorsRegistry.java` 同样走 `getWebComponentWithDefault`，无 Bean 时用默认 `PerfCorsProcessor`（`PerfCorsProcessor.java`）。

### 5.10 BizPoolRegistry / 业务线程池（模式 C）

```java
@Bean("orderPool")
ExecutorService orderPool() {
    return new ThreadPoolExecutor(20, 100, 60, SECONDS, new LinkedBlockingQueue<>(1000));
}

// 使用：
@RunInPool("orderPool")         // 注解指定池名
@GetMapping("/order/{id}")
public Order get(@PathVariable String id) { ... }
```
phase3 `getBeansOfType(ExecutorService)` 按 beanName 收进 `pools` Map。首请求经 `BIZ_POOL_KEY` 懒解析 `@RunInPool` 注解并缓存。池名不存在则 `resolvePool` fail-fast 抛 `IllegalStateException`——这是文档 19 ADR 9"fail-fast 启动校验 vs 运行时降级"的运行时兜底。

### 5.11 RouterOptimizer（模式 D，继承重写）

```java
public class MyMappingRegistry extends MappingRegistry {
    @Override
    protected List<RouterOptimizer> getOptimizerTemplate() {   // 重写 
        List<RouterOptimizer> t = super.getOptimizerTemplate();
        t.add(0, new RadixTreeRouterOptimizer());  // 在 Prefix 之前插入自定义层
        return t;
    }
}
// 再把 MyMappingRegistry 作为 Bean 覆盖默认 MappingRegistry
```
注意：`RouterOptimizer` **不扫描 Bean**，链序由 `getOptimizerTemplate` 固定。这是 12 SPI 中唯一不靠类型发现的扩展点——因为路由优化器是"框架内部路由结构的选择策略"，暴露给用户会破坏 `PathMappingContext` 的内聚。

### 5.12 WebComponent（契约，非直接扩展）

`WebComponent` 本身不是被扩展的 SPI，是上述 11 个 SPI 的统一契约。用户扩展任何一个 SPI，都隐式实现了 `WebComponent`，自动获得 `getOrder` 默认值与排序资格。

---

## 六、纠偏：启动期"校验可达"≠"预创建实例"

第五节 5.5 暴露一个容易误读的点。`module.md` 归纳"启动时预创建解析器实例"，但源码显示 `StaticArgumentResolverProvider` 的解析器**启动期不预创建**：

| 阶段 | `ArgumentResolverRegistry` 实际做的事 | 行号 |
|------|-------------------------------------|------|
| phase3 `validateAllParametersResolvable` | 遍历 provider 调 `supports()`，仅校验"有 provider 可解析该参数"，不创建 resolver |  |
| 首请求 DCL `getMethodArgContexts` | 命中 provider 才 `resolveArgument` 创建 `StaticArgumentResolver`，存入 `methodArgContext.defaultArgumentResolver` |  |

注释原话："does NOT create or cache any resolver, keeping memory footprint zero for endpoints that are never called"——对从未被调用的端点保持零内存。这是"启动期确定性"与"零无用内存"的折中：排序/校验在启动期确定（满足 fail-fast），实例化延迟到首请求（满足零闲置内存）。

对比之下，`ReturnValueResolver` 与 `HttpBodyCodecInterceptor` 的 per-method 缓存（`MethodReturnValueContext` / `HttpBodyCodecInterceptor[]`）才是真正的"首请求预缓存命中项"——它们缓存的是"匹配结果"而非"实例本身"。三者语义不同，文档 19 ADR 2"默认业务池 vs 全 EventLoop"与 ADR 9"fail-fast vs 运行时降级"共同解释了这条折中。

---

## 七、动态注册：按 state 追阶段

`WebComponentContainer.registerWebComponent(WebComponent)` 不仅处理启动期冲突，还支持初始化完成后的动态注册（如 Actuator 端点后置注册 mapping/filter/cors）。其生命周期追跑逻辑：新组件注册时，从当前容器的 state 起补跑所有未完成的 phase，确保动态组件与启动期组件经历相同的初始化路径。这是 [16 篇](16-code-spotlights.md) 聚光灯 7 `WebMvcConfigurerBridge` 在 `initComponentPhase1` 内完成收集与翻译、能安全后置执行的基础。

同名冲突时 `AnnotationAwareOrderComparator.sort` 选 order 更小者为胜、败者 `destroy`——动态注册高优先级组件会顶替低优先级旧组件并触发其销毁，避免双实例。

---

## 八、小结：四种模式对应四类扩展需求

| 扩展需求 | 模式 | 代表 SPI | 用户侧动作 |
|---------|------|---------|-----------|
| 多条实现按序参与处理 | A 批量排序 | Filter/Interceptor/Converter/Resolver… | `@Component`+`@Order` |
| 全局唯一替换点 | B 单实例兜底 | JsonConverter/WebCorsProcessor | `@Bean` 覆盖默认 |
| 命名资源按名取用 | C 命名 Map | BizPoolRegistry | `@Bean("name")`+`@RunInPool` |
| 内部路由结构策略 | D 硬编码继承 | RouterOptimizer | 继承重写 `protected` |

核对结论三条：① **没有 `@WebComponent` 注解**，SPI 靠 Spring Bean 类型扫描发现，排序靠 `Ordered`/`@Order` 经 `AnnotationAwareOrderComparator.sort` 统一——这是对 Spring 原生发现机制的复用，扩展方零学习成本。② 发现分四种模式，对应四类扩展需求，不是"一种注解打天下"。③ `StaticArgumentResolverProvider` 启动期只校验可达、实例化延迟到首请求，是 fail-fast 与零闲置内存的折中，而非简单的"启动预创建"。

这一层的克制体现在：**不发明新的发现注解，不发明新的排序注解，直接复用 Spring 的 `Ordered`/`@Order` 与 `AnnotationAwareOrderComparator`**——扩展方写的就是普通 Spring Bean，框架在内部用 `WebComponentContainer` 把"扫描+排序+固化"收口。12 个 SPI 看似各异，发现与排序却收敛到同一套基础设施，每个的"个性"只在缓存形态（List/单字段/Map/链）与运行时调用点上。

---

> **下一篇**：[19 · 关键设计决策记录](19-design-decisions.md)——用 ADR 风格沉淀"为什么这么设计"的取舍。
