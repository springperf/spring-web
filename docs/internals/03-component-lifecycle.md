# 03 · 三阶段组件生命周期与 Registry 体系

> [← 返回索引](00-README.md) | 上一篇：[02 · 模块拓扑与启动期全景](02-architecture-overview.md) | 下一篇：[04 · 请求处理管线全链路](04-request-pipeline.md)

---

## 引子：生命周期是"启动时确定性"的执行骨架

[01 篇](01-design-philosophy.md) 的总纲"启动时把所有能确定的事都确定好，运行时只做查表"是一句方法论。它落到代码里需要一个**执行骨架**：谁在启动时跑？分几步跑？每一步做什么？跑错了怎么 fail-fast？这套骨架就是本篇的主题——**三阶段组件生命周期 + 13 个 Registry 构成的组件体系**。

[02 篇](02-architecture-overview.md) 给了启动时序的宏观图，本篇把它拆到 `file:line` 粒度。读完本篇你应能回答：

1. 为什么 `WebContext` 实现了 `InitializingBean` 却把 `afterPropertiesSet()` 留空？真正的三阶段编排由谁触发？
2. 三阶段（Phase1/2/3）各自的职责边界在哪？为什么是这个顺序？
3. 13 个 Registry 怎么进入容器树？哪些共享、哪些私有？
4. "启动时确定"为什么不会让启动变慢、内存变大？——急切 vs 懒缓存的混合模式是什么？
5. `MappingCacheKey` 这个"整型索引"到底索引到哪？消费侧的 `MappingHandlerMethod` 如何做到 `get` 全程无锁、`set` 仅扩容时上锁？

---

## 一、组件层级体系

框架的组件体系是一个四级继承链，从根接口到容器实现逐层叠加能力：

```
WebComponent (interface, extends Ordered)                    ← 根接口
    │  getComponentName() default = getClass().getSimpleName()
    │  initWithWebContext(WebContext) default 空
    │  getOrder() default = LOWEST_PRECEDENCE - 10000
    ▼
LifecycleWebComponent (interface, extends WebComponent)      ← 三阶段契约
    │  initComponentPhase1/2/3() default 空
    │  destroyComponent() default 空（幂等）
    ▼
BaseWebComponent (abstract, implements LifecycleWebComponent) ← 叶子基类
    │  protected WebContext webContext                         （持有上下文引用）
    │  initWithWebContext() = this.webContext = webContext
    ▼
WebComponentContainer extends BaseWebComponent                ← 容器基类
    │  AtomicReference<State> state                           （CAS 状态机）
    │  Map<String, WebComponent> webComponents                （子组件表）
    │  Map<Class, Function> autoRegisterComponentMap          （自动注册声明）
    │  registerWebComponent(...): 冲突解决 + 生命周期重放
    │  initWithWebContext/Phase1/2/3: CAS 守卫 + 递归子组件
    ▼
WebContext extends WebComponentContainer                      ← 唯一根容器
       implements InitializingBean, DisposableBean, ApplicationContextAware
       getOrder() = Integer.MAX_VALUE                        （最后启动）
```

**根接口 `WebComponent`**（[`WebComponent.java`](../../spring-web/src/main/java/io/springperf/web/context/WebComponent.java)）只定义三样东西：`getComponentName()`（默认取简单类名，作为冲突解决的身份标识 ）、`initWithWebContext()`（注入上下文钩子，默认空 ）、`getOrder()`（默认 `LOWEST_PRECEDENCE - 10000`，让组件排在多数 Spring bean 之前但晚于框架内部 ）。它继承 `Ordered`——**排序是组件体系的一等公民**，后文的冲突解决、递归顺序都依赖它。

**三阶段契约 `LifecycleWebComponent`**（[`LifecycleWebComponent.java`](../../spring-web/src/main/java/io/springperf/web/context/LifecycleWebComponent.java)）在根接口上叠加四个 `default` 空方法，把"初始化"拆成三阶段 + 销毁。接口注释  明确三阶段语义：

- **Phase 1** — bean scanning, metadata collection, data structure construction（`scan and collect metadata`）
- **Phase 2** — cross-component wiring, index building- **Phase 3** — optimization, caching, final preparation for request handling- **destroyComponent** — idempotent cleanup
关键设计：三个 Phase 都是 `default` 空——**组件可以只 override 它需要的阶段，不需要的阶段零成本跳过**。这解释了为什么有的 Registry 只有 Phase2 override、有的只有 Phase3——它们各自声明自己关心的事，不被迫实现空壳。

**叶子基类 `BaseWebComponent`**（[`BaseWebComponent.java`](../../spring-web/src/main/java/io/springperf/web/context/BaseWebComponent.java)）极简：一个 `protected WebContext webContext` 字段+ 把 `initWithWebContext` 实现为赋值。它**不持有子组件表、不管理状态机**——是"原子组件"的落脚点。两个叶子型 Registry（`BizPoolRegistry`、`WebDataBinderRegistry`）直接继承它。

**容器基类 `WebComponentContainer`**（[`WebComponentContainer.java`](../../spring-web/src/main/java/io/springperf/web/context/WebComponentContainer.java)）是体系的核心：它叠加状态机、子组件表、自动注册声明表、冲突解决 + 生命周期重放、CAS 守卫的递归初始化。11 个容器型 Registry 继承它。它的全部机制后面分节展开。

**根容器 `WebContext`**（[`WebContext.java`](../../spring-web/src/main/java/io/springperf/web/context/WebContext.java)）是整个框架唯一的顶层容器，`getOrder() = Integer.MAX_VALUE`——作为 `SmartLifecycle` 链上优先级最低者最后启动。它在构造器里就把自己注册为 `webContext` 字段（`this.webContext = this`），并把 `dispatcherHandler` 注册为第一个子组件。

### 11 容器型 vs 2 叶子型

13 个 Registry 按 `extends` 声明分为两类（Grep 全量确认）：

| 类型 | 数量 | Registry | 特征 |
|------|------|----------|------|
| 容器型（`extends WebComponentContainer`） | 11 | MappingRegistry / ExceptionRegistry / ArgumentResolverRegistry / ReturnValueResolverRegistry / CorsRegistry / InterceptorRegistry / AsyncSupportRegistry / WebFilterRegistry / HttpBodyCodecRegistry / HttpBodyCodecInterceptorRegistry / ResourceHandlerRegistry | 有状态机 + 子组件表，可管理子组件并递归生命周期 |
| 叶子型（`extends BaseWebComponent`） | 2 | **BizPoolRegistry** / **WebDataBinderRegistry** | 无子组件表，是某个容器的私有子组件，自身只做业务逻辑 |

叶子型 Registry 的存在有深意：`BizPoolRegistry`（线程池）和 `WebDataBinderRegistry`（数据绑定）都是"被某个容器独占使用、不与别人共享"的能力提供者，不需要管理子组件，于是用更轻的 `BaseWebComponent`——**省掉一个 `HashMap` + 一个 `AtomicReference` + 一套递归逻辑**。这是"启动时确定"在组件设计上的体现：组件类型按需裁剪，不为用不到的能力付内存。

---

## 二、State 六态与 CAS 守卫

`WebComponentContainer` 用一个 `AtomicReference<State>`把容器状态锁定为单调推进的六态枚举：

```
NEW → INIT_CONTEXT → PHASE1 → PHASE2 → PHASE3 → DESTROY
```

每次阶段推进用 `compareAndSet` 守卫，**失败即 return**——这让每个方法幂等且线程安全：

```java
// WebComponentContainer.java（initWithWebContext）
public void initWithWebContext(WebContext webContext) {
    if (!state.compareAndSet(State.NEW, State.INIT_CONTEXT)) {
        return;                       // 已初始化过，幂等返回
    }
    super.initWithWebContext(webContext);
    for (Map.Entry<Class, Function> entry : autoRegisterComponentMap.entrySet()) {
        registerWebComponent(entry.getKey(), entry.getValue());   // 执行自动注册声明
    }
    for (WebComponent component : getSortedWebComponents()) {
        component.initWithWebContext(webContext);                 // 递归子组件
    }
}
```

四个阶段方法  结构同构——CAS 推进 + `super` 调用 + 递归子组件：

| 方法 | CAS 转换 | 行号 | 递归调用 |
|------|----------|------|----------|
| `initWithWebContext` | `NEW → INIT_CONTEXT` |  | `component.initWithWebContext`  |
| `initComponentPhase1` | `INIT_CONTEXT → PHASE1` |  | `initComponentPhase1(component)`  |
| `initComponentPhase2` | `PHASE1 → PHASE2` |  | `initComponentPhase2(component)`  |
| `initComponentPhase3` | `PHASE2 → PHASE3` |  | `initComponentPhase3(component)`  |
| `destroyComponent` | `PHASE3 → DESTROY` |  | `destroyComponent(component)`  |

递归调度器对每个子组件做 `instanceof LifecycleWebComponent` 检查——只对实现了三阶段契约的组件调对应方法，纯 `WebComponent` 被跳过。这意味着**容器树里可以混入非生命周期组件**（如某些叶子 Bean），递归不会对它们误调 Phase。

**CAS 守卫的两个收益**：

1. **幂等**：`startLifecycle()` 被调两次、或组件在多线程下被并发初始化，CAS 保证只推进一次，其余线程直接 `return`。`WebContext` 自己还多一层 `AtomicBoolean lifecycleStarted`（`WebContext.java`/）做顶层幂等。
2. **单调推进**：状态只能前进不能回退，从结构上杜绝"Phase2 跑了但 Phase1 没跑"的乱序。`registerWebComponent` 的生命周期重放（见第九节）正是靠读取当前 `state` 决定补跑哪些阶段。

---

## 三、三阶段语义

把 `LifecycleWebComponent` 的接口注释和各 Registry 的实际 Phase override 对照，三阶段语义落到具体职责：

### Phase 1 · 扫描收集元数据

> 接口注释：bean scanning, metadata collection, data structure construction
这一阶段从 Spring 容器扫描注解、构建内部数据结构、收集配置。**只读 + 自建结构，不跨组件引用**——因为此时别的组件可能还没完成 Phase1，跨组件引用会读到不完整数据。

只有 2 个 Registry override Phase1：

- **`MappingRegistry.initComponentPhase1`**（摘要）：用 `LinkedHashMap` 遍历 `@Controller` bean，`ClassUtils.getUserClass` 去代理类，`ReflectionUtils.getUniqueDeclaredMethods` 扫描方法，构建路由元数据。这里是 [D4 修复](../00-README.md) 落地点——重复映射不再启动期拒绝，先注册者胜，保序用 `LinkedHashMap`。
- **`WebDataBinderRegistry.initComponentPhase1`**（摘要）：`ControllerAdviceBean.findAnnotatedBeans` 收集 `@InitBinder` 方法到 `initBinderAdviceCache`（`LinkedHashMap<ControllerAdviceBean, Set<Method>>`），构造 `defaultConversionService`（`DefaultFormattingConversionService`）、`defaultValidator`、`messageCodesResolver`。

### Phase 2 · 跨组件连接建索引

> 接口注释：cross-component wiring, index building
此时 Phase1 已全部完成，所有元数据就绪，组件可以安全地解析对其他组件的引用、构建索引、建立跨结构连接。这一阶段的标志动作是 `initRealComponentList`（`WebComponentContainer.java`）——把自动注册进来的子组件按 `@Order` 排序后固化成运行时列表。

7 个 Registry override Phase2：InterceptorRegistry、HttpBodyCodecRegistry、CorsRegistry、ExceptionRegistry、AsyncSupportRegistry、ResourceHandlerRegistry、HttpBodyCodecInterceptorRegistry。它们的 Phase2 多是 `initRealComponentList` + 排序构建不可变列表（如 `HttpBodyCodecRegistry` 的 `allSupportedMediaTypes` 排序后不可变 list，摘要 ）。

### Phase 3 · 优化、缓存、fail-fast 校验

> 接口注释：optimization, caching, final preparation for request handling
这是"启动时确定"的决战阶段：路由树压缩、模式预编译、缓存预计算、**fail-fast 校验**。4 个 Registry override Phase3：

- **`MappingRegistry.initComponentPhase3`**（摘要  → `optimizeMapping `）：分桶（精确/单级通配/全通配）+ `FullPathRouterOptimizer` 等多级优化器链构建。这是 [03 篇 §性能原理 3](../performance-principles.md) 的 O(1) 路由落地。
- **`ArgumentResolverRegistry.initComponentPhase3`**（摘要）：`if (props.getBoolean(CHECK_ON_STARTUP, true)) validateAllParametersResolvable()`。**只校验不创建解析器**——这是混合缓存模式的关键，见第七节。
- **`WebFilterRegistry.initComponentPhase3`**（摘要）：`initRealComponentList` + `initAllFilters`，预构建 `allFilters` 与 `unmatchedChain`。
- **`BizPoolRegistry.initComponentPhase3`**（摘要）：`ctx.getBeansOfType(ExecutorService.class)` 自动发现业务线程池 Bean，注册到 `pools` Map，运行时 `determinePool` 按名取用。

**1 个 Registry 无任何 Phase override**（`ReturnValueResolverRegistry`）——它只靠 `initWithWebContext` + 自己的 init 方法完成启动期工作，三阶段对它是 no-op。这是 `default` 空方法的设计收益：不需要就不用实现。

### 阶段顺序的依赖逻辑

```
Phase1（自建）→ Phase2（互连）→ Phase3（优化+校验）
   ↑                ↑                ↑
只读自建         可跨组件引用     所有结构就绪，可做全局校验/压缩
```

`WebContext.startLifecycle()`（`WebContext.java`）严格按此顺序串行调用三个方法，每个方法的 CAS 守卫保证全局所有容器同步推进到同一阶段后才开始下一阶段——**这是一道全局屏障**，杜绝了"A 容器已进 Phase3 而 B 容器还在 Phase1"的竞态。

---

## 四、生命周期触发链

### afterPropertiesSet 是 no-op

`WebContext` 实现了 `InitializingBean`，却把 `afterPropertiesSet()` 留空：

```java
// WebContext.java
/**
 * No-op: lifecycle is now deferred to startLifecycle(),
 * triggered by NettyHttpServer#start().
 */
@Override
public void afterPropertiesSet() {
}
```

这是容易误读的点：很多人会以为 `InitializingBean` 的 `afterPropertiesSet()` 是 Spring bean 初始化的"标准入口"，这里却主动放弃。真正触发三阶段的是 `startLifecycle()`：

```java
// WebContext.java
public void startLifecycle() {
    if (!lifecycleStarted.compareAndSet(false, true)) {   // 顶层幂等
        return;
    }
    try {
        this.initWithWebContext(this);   // 注入上下文 + 递归子组件 initWithWebContext
        this.initComponentPhase1();      // Phase1
        this.initComponentPhase2();      // Phase2
        this.initComponentPhase3();      // Phase3
    } catch (Exception e) {
        throw new RuntimeException("Failed to start WebContext lifecycle", e);
    }
}
```

### 触发链全景

`startLifecycle()` 由 `NettyHttpServer.start()` 调用，而 `NettyHttpServer` 是 `SmartLifecycle`（phase = `Integer.MAX_VALUE`，最后启动）。完整链路：

```
Spring Boot 启动
    │
    ▼ context refresh
注册所有 bean（含 WebContext、dispatcherHandler）
    │
    ▼ refresh 结束
Spring LifecycleProcessor 调 SmartLifecycle.start()（按 phase 升序，WebContext phase=MAX 最后）
    │
    ▼ NettyHttpServer.start()
WebContext.startLifecycle()                                    [WebContext.java]
    │  ① CAS lifecycleStarted                                   
    ▼
this.initWithWebContext(this)                                  
    │  ② WebContext CAS NEW→INIT_CONTEXT                        [WebComponentContainer.java]
    │  ③ 遍历 autoRegisterComponentMap 执行自动注册             
    │  ④ 递归现有子组件（此时仅 dispatcherHandler）initWithWebContext  
    │         │
    │         ▼ dispatcherHandler.initWithWebContext
    │           super.initWithWebContext（设 webContext 字段）   [DispatcherHandler.java]
    │           webContext.getWebComponentWithDefault × 9         ← 9 个 Registry 注册进根容器
    │              └─ 每个新 Registry 经 registerWebComponent 重放
    │                 立即执行 initWithWebContext（state=INIT_CONTEXT）[WebComponentContainer.java]
    ▼
this.initComponentPhase1()                                     
    │  ⑤ WebContext CAS INIT_CONTEXT→PHASE1                     
    │  ⑥ getSortedWebComponents（此时含 dispatcherHandler + 9 Registry）
    │     按 @Order 排序后递归 initComponentPhase1             
    │         └─ MappingRegistry Phase1 扫描 @Controller
    │         └─ WebDataBinderRegistry Phase1 收集 @InitBinder
    ▼
this.initComponentPhase2()                                    
    │  ⑦ CAS PHASE1→PHASE2 + 递归                              
    │         └─ 7 个 Registry 的 Phase2（initRealComponentList + 索引构建）
    ▼
this.initComponentPhase3()                                    
    │  ⑧ CAS PHASE2→PHASE3 + 递归                              
    │         └─ MappingRegistry.optimizeMapping（路由分桶+优化器）
    │         └─ ArgumentResolverRegistry.validateAllParametersResolvable（fail-fast）
    │         └─ WebFilterRegistry.initAllFilters
    ▼
NettyHttpServer 绑定端口 → 就绪
```

**为什么推迟到 `startLifecycle()` 而不在 `afterPropertiesSet()`？** Web 组件初始化应在 Spring 基础容器初始化完成之后。`afterPropertiesSet()` 触发时点取决于 bean 自身的依赖图位置，无法保证此时容器已就绪；而 `SmartLifecycle.start()` 在 context refresh **完全结束**后才被 `LifecycleProcessor` 调用，此时所有 bean 必然就绪。把生命周期锚定在 `startLifecycle()` 是为了让"启动时确定"拿到的输入完整——[01 篇](01-design-philosophy.md) 总纲"确定性首先要求输入完整"的代码注脚。

---

## 五、递归容器树与依赖拉取模式

### dispatcherHandler 是叶子协调者，不是容器

`DispatcherHandler extends BaseWebComponent implements HttpHandler`（[`DispatcherHandler.java`](../../spring-web/src/main/java/io/springperf/web/core/DispatcherHandler.java)）——叶子型，**没有自己的 `webComponents` 表**。它持有 9 个 Registry 的引用，但获取方式是经 `webContext.getWebComponentWithDefault`：

```java
// DispatcherHandler.java
public void initWithWebContext(WebContext webContext) {
    super.initWithWebContext(webContext);
    this.mappingRegistry = webContext.getWebComponentWithDefault(MappingRegistry.class, new MappingRegistry());
    this.exceptionRegistry = webContext.getWebComponentWithDefault(ExceptionRegistry.class, new ExceptionRegistry());
    this.argumentResolverRegistry = webContext.getWebComponentWithDefault(ArgumentResolverRegistry.class, new ArgumentResolverRegistry());
    this.returnValueResolverRegistry = webContext.getWebComponentWithDefault(ReturnValueResolverRegistry.class, new ReturnValueResolverRegistry());
    this.corsRegistry = webContext.getWebComponentWithDefault(CorsRegistry.class, new CorsRegistry());
    this.interceptorRegistry = webContext.getWebComponentWithDefault(InterceptorRegistry.class, new InterceptorRegistry());
    this.asyncSupportRegistry = webContext.getWebComponentWithDefault(AsyncSupportRegistry.class, new AsyncSupportRegistry());
    this.bizPoolRegistry = webContext.getWebComponentWithDefault(BizPoolRegistry.class, new BizPoolRegistry());
    this.webFilterRegistry = webContext.getWebComponentWithDefault(WebFilterRegistry.class, new WebFilterRegistry(this));
    this.metrics = webContext.getWebComponentWithDefault(WebMetrics.class, NoOpWebMetrics.INSTANCE);
}
```

注意前缀是 **`webContext.`** 而非 `this.`——因为 `dispatcherHandler` 是叶子，没有 `getWebComponentWithDefault` 方法。所以这 9 个 Registry 全部注册进 **`WebContext.webComponents`**（根容器平铺），而不是 dispatcherHandler 的子树。`dispatcherHandler` 只持有引用，**不管理它们的生命周期**——生命周期由 `WebContext` 的 Phase1/2/3 递归覆盖。

### 依赖拉取：共享型 vs 私有型

容器型 Registry 在各自 `initWithWebContext` 里也会 `getWebComponentWithDefault` 拉取依赖。Grep 全部 13 个 Registry 的拉取语句，发现一个**刻意的架构不对称性**：

| 拉取者 | 拉取的依赖 | 前缀 | 落点 |
|--------|-----------|------|------|
| `MappingRegistry` | `ResourceHandlerRegistry` | `webContext.` | **根容器**（共享） |
| `ExceptionRegistry` | `WebMetrics` | `webContext.` | 根容器 |
| `CorsRegistry` | `WebCorsProcessor` | `webContext.` | 根容器 |
| `AsyncSupportRegistry` | `JsonConverter` | `webContext.` | 根容器 |
| `AsyncSupportRegistry` | `BizPoolRegistry`（读） | `webContext.` | 根容器 |
| `BizPoolRegistry` | `WebMetrics` | `webContext.` | 根容器 |
| `HttpBodyCodecRegistry` | `HttpBodyCodecInterceptorRegistry` | `webContext.` | 根容器 |
| `ResourceHandlerRegistry` | `MappingRegistry` | `webContext.` | 根容器 |
| `ReturnValueResolverRegistry` | `HttpBodyCodecRegistry` | `webContext.` | 根容器（共享） |
| `ArgumentResolverRegistry` | `HttpBodyCodecRegistry` | `webContext.` | 根容器（共享） |
| **`ArgumentResolverRegistry`** | `WebDataBinderRegistry` | **`this.`** | **ArgumentResolverRegistry 子树**（私有） |
| **`ArgumentResolverRegistry`** | `RequestParamResolverProvider` | **`this.`** | ArgumentResolverRegistry 子树 |
| **`ArgumentResolverRegistry`** | `ModelAttributeResolverProvider` | **`this.`** | ArgumentResolverRegistry 子树 |

绝大多数用 `webContext.` 前缀——依赖落根容器，**全局共享**。`HttpBodyCodecRegistry` 被 `ArgumentResolverRegistry` 和 `ReturnValueResolverRegistry` 共享拉取，因为它落根容器，两者 `getWebComponentWithDefault` 第二次调用时命中已注册实例，不会重复创建。

**唯一的例外是 `ArgumentResolverRegistry`** 用无前缀 `this.getWebComponent(WithDefault)`——把 `WebDataBinderRegistry`、`RequestParamResolverProvider`、`ModelAttributeResolverProvider` 注册进**自己的 `webComponents`**。这三个是参数解析的私有能力，不被别的 Registry 共享，于是落拥有者子树。

这解释了前文"为什么 `WebDataBinderRegistry` 是叶子型"——它是 `ArgumentResolverRegistry` 的私有子组件，注册在 `ArgumentResolverRegistry.webComponents` 里，由 `ArgumentResolverRegistry` 的 Phase1/2/3 递归覆盖（`ArgumentResolverRegistry` 自己 `extends WebComponentContainer` 有递归能力）。共享型依赖落根容器、私有型依赖落拥有者容器——**共享边界决定注册落点**，这是这套组件体系最有洞察力的一条规则。

### 容器树全景

```
WebContext（根容器，getOrder=MAX_VALUE 最后启动）
  │
  ├─ dispatcherHandler（叶子，extends BaseWebComponent）
  │     └─ 持有 9 个 Registry 引用（不管理其生命周期）
  │
  ├─ [根容器平铺的 9 个 dispatcherRegistry + 间接拉取的共享型]
  │   ├─ MappingRegistry（容器）─┐
  │   ├─ ExceptionRegistry（容器）│
  │   ├─ ArgumentResolverRegistry（容器）──┐
  │   ├─ ReturnValueResolverRegistry（容器）│  ← 共享 HttpBodyCodecRegistry（落根）
  │   ├─ CorsRegistry（容器）              │
  │   ├─ InterceptorRegistry（容器）       │  autoRegister: InterceptorRegistration / HandlerInterceptor
  │   ├─ AsyncSupportRegistry（容器）       │  autoRegister: Callable/DeferredResultProcessingInterceptor
  │   ├─ BizPoolRegistry（叶子）           │
  │   ├─ WebFilterRegistry（容器）         │  autoRegister: WebFilterRegistration / WebFilter
  │   ├─ HttpBodyCodecRegistry（容器，共享）┘  autoRegister: HttpBodyConverter
  │   ├─ HttpBodyCodecInterceptorRegistry（容器）
  │   └─ ResourceHandlerRegistry（容器）
  │
  └─ [ArgumentResolverRegistry 私有子树]
      └─ ArgumentResolverRegistry.webComponents
          ├─ WebDataBinderRegistry（叶子，私有）
          ├─ RequestParamResolverProvider
          └─ ModelAttributeResolverProvider
```

容器型 Registry 各自的 `autoRegisterComponentMap`（在 ctor 里 `autoRegisterWebComponent` 声明）在 `initWithWebContext` 时执行，从 Spring ctx 拉 bean 包装成 WebComponent 注册到**自己**的 `webComponents`——构成第三级子树（如 `InterceptorRegistry` 管理 `InterceptorRegistration`/`HandlerInterceptor` wrapper）。

---

## 六、13 个 Registry 体系一览

下表是本篇的纲目表，每列都来自实读源码核对：

| # | Registry | 继承 | Phase override | MAPPING_CACHE_KEY | 急切产物（启动期） | 懒缓存产物（首次请求） | 运行时入口 |
|---|----------|------|---------------|-------------------|-------------------|----------------------|-----------|
| 1 | MappingRegistry | 容器 | P1+P3 | — | 路由元数据 + 分桶 + 优化器链 | — | `mapping(req)` |
| 2 | InterceptorRegistry | 容器 | P2 | — | `runtimeMappingInterceptors` 列表 | per-ctx 拦截器数组（DCL ，PathMappingContext 实例字段 `cachedInterceptors`） | `preHandle`/`postHandle`/`afterCompletion` |
| 3 | ArgumentResolverRegistry | 容器 | P3 | method（`MethodArgContext[]` ） | 12 个 StaticArgumentResolverProvider + Phase3 **只校验** | per-method `MethodArgContext[]`（get-null-check ） | `resolveArguments` |
| 4 | ReturnValueResolverRegistry | 容器 | 无 | method（`MethodReturnValueContext` ） | 13 个内置 ReturnValueResolver + SPI 扩展 | per-method 主 resolver + async/Optional 内联 resolver（两层懒缓存 ） | `resolveReturnValue` |
| 5 | HttpBodyCodecRegistry | 容器 | P2 | — | `allSupportedMediaTypes` 不可变 list + 1 个内置 converter + SPI 扩展 | per-ctx READ/WRITE converter + write 协商表（cap 64/method） | `readBody`/`writeBody` |
| 6 | HttpBodyCodecInterceptorRegistry | 容器 | P2 | **class**（`HttpBodyCodecInterceptor[]` ） | ControllerAdvice 收集 | per-ctx `HttpBodyCodecInterceptor[]`（get-null-check ） | `beforeBodyRead`/`afterBodyRead`/`beforeBodyWrite` |
| 7 | CorsRegistry | 容器 | P2 | — | `WebCorsProcessor` default | per-ctx `CorsConfigurationProvider`（get-null-check ） | `corsHandle`/`getCorsConfiguration` |
| 8 | ExceptionRegistry | 容器 | P2 | — | 2 个内置 resolver + SPI 扩展 | **无懒缓存**（每请求线性扫描 ） | `handle`/`doHandle` |
| 9 | AsyncSupportRegistry | 容器 | P2 | — | `defaultTaskExecutor`（from BizPoolRegistry） | 无（interceptor chain per-call） | `startCallableProcessing`/`startDeferredResultProcessing` |
| 10 | ResourceHandlerRegistry | 容器 | P2 | — | `PathMappingContext` 构建并注册进 MappingRegistry | 无（仅启动期） | 无（委托 MappingRegistry） |
| 11 | WebFilterRegistry | 容器 | **P3** | — | `allFilters` + `unmatchedChain` 预构建 | per-ctx `DefaultFilterChain`（DCL ） | `doFilter`/`resolveFilterChain` |
| 12 | **BizPoolRegistry** | **叶子** | **P3** | method（`Object.class`） | `default` 池（`initDefaultPoolFromConfig` ）+ 自动发现 `ExecutorService` Bean | per-method 池决策（NO_POOL 哨兵等） | `determinePool` |
| 13 | **WebDataBinderRegistry** | **叶子** | P1 | **class** ×3（`CONVERSION_SERVICE`/`VALIDATOR`/`BINDER_FACTORY` ） | `initBinderAdviceCache` + default services | per-class ConversionService/Validator/BinderFactory（get-null-check ） | 经 ArgumentResolverRegistry 调用 |

**关键观察**：

1. **MAPPING_CACHE_KEY 的两种类型**：`method`（每方法共享数组）用于 ArgumentResolverRegistry/ReturnValueResolverRegistry/BizPoolRegistry；`class`（每控制器类共享数组）用于 HttpBodyCodecInterceptorRegistry/WebDataBinderRegistry。两池索引独立、绝不碰撞——机制见第八节。**例外**：InterceptorRegistry/CorsRegistry/WebFilterRegistry 不走 MappingCacheKey，而是直接操作 PathMappingContext 的实例字段（`cachedInterceptors`/`corsConfigurationProvider`/`cachedFilterChain`，见 `PathMappingContext.java`/ 等），三者存的是 `List`/`Provider`/`FilterChain` 等有类型的领域对象，不需要整型索引寻址——get-null-check 或 DCL 模式与 MappingCacheKey 消费者相同，只是存储位置在实例字段而非 `Object[]` 槽位。
2. **3 个 Registry 无懒缓存**：`ExceptionRegistry`、`AsyncSupportRegistry`、`ResourceHandlerRegistry`。前两者每请求线性扫描 resolver/构建 interceptor chain，后者仅启动期工作。它们的选择各有理由：异常处理低频、异步 interceptor chain 因异步语义难预缓存、资源处理器纯启动期。
3. **`WebFilterRegistry` 是唯一 override Phase3 的容器型**（`BizPoolRegistry` 是叶子型，也 override Phase3）；它也是唯一有显式 ctor 的 Registry（auto-register `WebFilterRegistration` + `WebFilter` + pre-build `unmatchedChain`）。

---

## 七、急切 vs 懒缓存的混合模式

这张表回答了引子的第 4 个问题：**"启动时确定"为什么不会让启动变慢、内存变大？** 答案是混合模式——**启动期只急切分配"地址"和做"校验/路由优化"，真正的解析器/处理器匹配懒到首次请求**。

### 急切层（启动期一次性）

1. **`MappingCacheKey.index` 整型槽位分配**：每个 Registry 在类初始化时 `static` 字段 `MappingCacheKey.createMethodCacheKey(...)` / `createClassCacheKey(...)`，用 `AtomicInteger.getAndIncrement()` 分配全局唯一索引（[01 篇原则 1](01-design-philosophy.md#原则-1--避免隐式开销运行时零反射零匹配零类型推断)）。这是"地址"——运行时 `array[index]` 的 index。分配本身只是一次 `getAndIncrement`，零成本。
2. **Phase1 元数据扫描**：MappingRegistry 扫 `@Controller`、WebDataBinderRegistry 收集 `@InitBinder`。
3. **Phase2 跨组件连接 + 列表固化**：`initRealComponentList` 把子组件按 `@Order` 排序成运行时列表；`HttpBodyCodecRegistry` 构建 `allSupportedMediaTypes` 不可变 list；`WebFilterRegistry` 预构建 `allFilters` + `unmatchedChain`。
4. **Phase3 优化 + fail-fast 校验**：MappingRegistry 路由分桶 + 优化器链；ArgumentResolverRegistry 校验所有参数可解析；WebFilterRegistry `initAllFilters`；BizPoolRegistry 自动发现业务线程池 Bean。

### 懒层（首次请求才建，之后缓存）

| Registry | 懒缓存对象 | 时机 | 守卫方式 |
|----------|-----------|------|---------|
| ArgumentResolverRegistry | `MethodArgContext[]`（含每参数选定的 resolver） | 首次解析该方法 | get-null-check（，**无 synchronized**） |
| ReturnValueResolverRegistry | 主 resolver + async/Optional 内联 resolver | 首次处理该返回值 | get-null-check + 两层 |
| InterceptorRegistry | per-ctx 拦截器数组 | 首次匹配该方法 | **DCL synchronized** |
| WebFilterRegistry | per-ctx `DefaultFilterChain` | 首次过滤该路径 | **DCL synchronized** |
| HttpBodyCodecRegistry | per-ctx READ/WRITE converter + write 协商表 | 首次读/写该类型 | get-null-check |
| HttpBodyCodecInterceptorRegistry | per-ctx `HttpBodyCodecInterceptor[]` | 首次处理该类 | get-null-check |
| CorsRegistry | per-ctx `CorsConfigurationProvider` | 首次 CORS 请求 | get-null-check |
| WebDataBinderRegistry | per-class ConversionService/Validator/BinderFactory | 首次绑定该类 | get-null-check |
| BizPoolRegistry | per-method 池决策 | 首次调度该方法 | 缓存 NO_POOL 哨兵等 |

### 为什么 ArgumentResolverRegistry 的 Phase3 只校验不创建

这是混合模式最精妙的一处。`ArgumentResolverRegistry.initComponentPhase3`（摘要）：

```java
if (webContext.getProps().getBoolean(PropertiesConstant.CHECK_ON_STARTUP, true)) {
    validateAllParametersResolvable();
}
```

`validateAllParametersResolvable`（摘要）遍历 `mappingRegistry.getMappingContextList()`，对每个参数调 `isParameterResolvable` + `validateModelAttributeConstructor`——**只判断"能不能解析"，不创建解析器实例存进缓存**。源码注释  明示："keeping memory footprint zero for endpoints that are never called"。

真正的解析器选择懒到 `getMethodArgContexts`——首次请求某方法时，`mappingContext.get(MAPPING_CACHE_KEY)` 命中 null → 创建 `MethodArgContext[]` → `initStaticArgResolverSupport`为每参数跑 `providers.supports` + fallback 选 resolver 存入 `defaultArgumentResolver` → `set` 写进数组。之后同方法请求直接 `array[index]` 取已选定的 resolver。

**收益**：假设有 500 个端点，只有 50 个被实际调用。Phase3 校验确保全部 500 个"能解析"（fail-fast），但只为被调用的 50 个分配 `MethodArgContext[]`——**零调用端点保持零内存**。这是"启动时确定"与"运行时懒加载"的精确平衡：**确定性校验急切、确定性数据懒加载**。

### DCL vs get-null-check 的选择

懒缓存的守卫有两种：

- **get-null-check（无锁）**：ArgumentResolverRegistry/ReturnValueResolverRegistry/HttpBodyCodecRegistry/CorsRegistry/WebDataBinderRegistry。`get` 为 null → 构建 → `set`。无 `synchronized`，**允许并发首次请求重复构建**——但 `set` 是 `array[index] = value` 幂等写，重复构建只是多一次计算，结果一致，无正确性问题。
- **DCL（synchronized）**：InterceptorRegistry/WebFilterRegistry。`get` 为 null → `synchronized` 块内再 `get` 仍 null → 构建 → `set`。用于**构建有副作用或需唯一性**的场景（如拦截器链需确定性顺序）。

这个选择本身就是"避免锁"原则（[01 篇原则 1](01-design-philosophy.md#原则-1--避免隐式开销运行时零反射零匹配零类型推断)）的体现：能无锁就无锁，只有需要确定性时才上 DCL，且 DCL 只在首次请求命中一次，之后全走无锁的 `array[index]`。

---

## 八、MappingCacheKey 深潜

[01 篇原则 1](01-design-philosophy.md#原则-1--避免隐式开销运行时零反射零匹配零类型推断) 展示了 `MappingCacheKey` 的"地址"角色，但只到 `final int index`。本节把消费侧机制彻底拆开——**这是整套预缓存机制能无锁运行的根**。

### 生产侧：两个独立的索引池

```java
// MappingCacheKey.java（精简）
public final class MappingCacheKey<T> {
    private static final AtomicInteger METHOD_CACHE_SEQ = new AtomicInteger();
    private static final AtomicInteger CLASS_CACHE_SEQ   = new AtomicInteger();

    final int index;
    final Class<T> type;
    final boolean classCache;                          // 选数组池的标志

    public static <T> MappingCacheKey<T> createMethodCacheKey(Class<T> type) {
        return new MappingCacheKey<>(METHOD_CACHE_SEQ.getAndIncrement(), type, false);
    }
    public static <T> MappingCacheKey<T> createClassCacheKey(Class<T> type) {
        return new MappingCacheKey<>(CLASS_CACHE_SEQ.getAndIncrement(), type, true);
    }
}
```

两个 `static AtomicInteger` 各自独立递增——**method 池和 class 池的 index 空间独立**，所以 `ArgumentResolverRegistry` 的 method key（index=0）和 `WebDataBinderRegistry` 的 class key（index=0）绝不碰撞。`boolean classCache` 标志在消费侧决定走哪个数组。

### 消费侧：MappingHandlerMethod 的双重存储

`MappingHandlerMethod`（extends `InvokableHandlerMethod`）是消费侧——它同时持有一个**类级共享数组**和一个**方法级共享数组**，外加两个免 map 查找的读缓存指针：

```java
// MappingHandlerMethod.java（字段，精简）
private static final Map<Class<?>, Object[]> classCacheInstanceMap = new ConcurrentHashMap<>();   // 每控制器类共享
private static final Map<Method, Object[]>  methodCacheInstanceMap = new ConcurrentHashMap<>();  // 每方法共享
protected final Method userMethod;          // AopUtils.getMostSpecificMethod(bridged, userClass)
protected final Class<?> userClass;         // ClassUtils.getUserClass(ultimateTargetClass)
private volatile Object[] methodCache;      // 实例读缓存（免 map 查找）
private volatile Object[] classCache;      // 实例读缓存（免 map 查找）
```

两个 `static ConcurrentHashMap` 是**真正的存储**：`classCacheInstanceMap` 以 `userClass` 为 key 存"该控制器类的共享 Object[]"，`methodCacheInstanceMap` 以 `userMethod` 为 key 存"该方法的共享 Object[]"。两个 `volatile` 实例字段是**读缓存指针**——首次访问后把 map 里的数组引用缓存到实例字段，后续读跳过 map 查找。

构造器（摘要）用 `AopProxyUtils.ultimateTargetClass(bean)` 拿目标类、`ClassUtils.getUserClass` 去代理、`AopUtils.getMostSpecificMethod` 拿用户方法——保证 key 跨 AOP 代理稳定。

### get：全程无锁

```java
// MappingHandlerMethod.java
public <T> T get(MappingCacheKey<T> key) {
    Object[] cache = getCache(key);              // 选数组（classCache 标志）
    if (key.index >= cache.length) return null; // 槽位未分配 → 未缓存
    return (T) cache[key.index];                // 一条 aaload，无哈希无锁
}
```

`getCache(key)`按 `key.classCache` 选数组：

```java
protected Object[] getCache(MappingCacheKey key) {
    if (key.classCache) {
        if (classCache == null)
            classCache = classCacheInstanceMap.computeIfAbsent(userClass, k -> new Object[key.index + 1]);
        return classCache;
    } else {
        if (methodCache == null)
            methodCache = methodCacheInstanceMap.computeIfAbsent(userMethod, k -> new Object[key.index + 1]);
        return methodCache;
    }
}
```

- **首次访问**：`computeIfAbsent` 在 map 里建数组（长度 `key.index + 1`），存实例字段。
- **后续访问**：实例字段非 null，直接返回——**跳过 map 查找**，只有一次 volatile 读。
- **`cache[key.index]`**：一条 `aaload` 指令，无哈希、无比较、无锁。

对比 `ConcurrentHashMap.get()`：4 次 volatile 读（table/Node/value/modCount）+ hashCode 计算 + 可能的桶遍历。在每请求都触发的热路径上，`aaload` vs `ConcurrentHashMap.get()` 的差距被并发放大——这是 [01 篇原则 1](01-design-philosophy.md#原则-1--避免隐式开销运行时零反射零匹配零类型推断) "为什么不用 ConcurrentHashMap"的精确答案。

### set：仅扩容时 DCL 上锁

```java
// MappingHandlerMethod.java（精简）
public <T> void set(MappingCacheKey<T> key, T value) {
    int index = key.index;
    Object[] cache = getCache(key);
    if (index >= cache.length) {                          // 需要扩容
        synchronized (this) {
            cache = getCache(key);                        // DCL：重读，防并发扩容
            if (index >= cache.length) {
                cache = Arrays.copyOf(cache, index + 1);  // 扩容 + 复制
                setCache(key, cache);                     // 回填 map + 实例字段
            }
        }
    }
    cache[index] = value;                                 // 写入，无锁
}
```

**唯一上锁的点是扩容**——且用 DCL（double-checked locking）：先无锁检查 `index >= cache.length`，只有需要扩容才进 `synchronized` 块，块内重读数组防并发扩容，`Arrays.copyOf` 扩容后回填 map（`setCache` ）。**正常写入（`index < cache.length`）完全无锁**——`cache[index] = value` 一条 `astore` 指令。

为什么写入可以无锁？因为 `index` 在启动期已由 `MappingCacheKey` 的 `AtomicInteger` 全局分配且**永不复用**——每个 key 对应固定槽位，不同 key 写不同 index，无竞争。同一 index 的并发写（首次请求重复构建，见第七节）结果幂等——多写一次相同的 resolver 实例，无正确性问题。`volatile Object[]` 保证数组引用的可见性，`aaload`/`astore` 对引用类型读写本身原子。

### 存储全景图

```
MappingCacheKey（生产侧，每 Registry 一个 static 实例）
   ├─ METHOD_CACHE_SEQ ─┐ 两个独立 AtomicInteger
   └─ CLASS_CACHE_SEQ ─┘ index 空间独立，绝不碰撞

MappingHandlerMethod（消费侧，每 PathMappingContext 一个实例）
   │
   ├─ static methodCacheInstanceMap: ConcurrentHashMap<Method, Object[]>
   │      key=userMethod → 共享数组（跨同类同方法的所有 PathMappingContext 实例共享）
   │      槽位: [argCtx0, argCtx1, retCtx, pool, ...]  ← 各 Registry 的 method key 写各自的 index
   │
   ├─ static classCacheInstanceMap: ConcurrentHashMap<Class, Object[]>
   │      key=userClass → 共享数组（跨同类所有方法的所有实例共享）
   │      槽位: [convertSvc, validator, binderFactory, codecInterceptor[]]
   │
   ├─ volatile Object[] methodCache  ←─ 实例读缓存（免 map 查找）
   └─ volatile Object[] classCache  ←─ 实例读缓存（免 map 查找）

运行时取用：
   mappingContext.get(ARG_KEY)  → getCache(METHOD) → methodCache[ARG_KEY.index]   // aaload
   mappingContext.get(BINDER_KEY) → getCache(CLASS) → classCache[BINDER_KEY.index] // aaload
```

### 语义总结

| key 类型 | 生产者 | 数组 key | 共享范围 |
|---------|--------|---------|---------|
| `createMethodCacheKey` | ArgumentResolverRegistry/ReturnValueResolverRegistry/BizPoolRegistry | `userMethod` | 跨**同方法**的所有 PathMappingContext 实例 |
| `createClassCacheKey` | HttpBodyCodecInterceptorRegistry/WebDataBinderRegistry | `userClass` | 跨**同类所有方法**的所有 PathMappingContext 实例 |

method key 共享到方法粒度（同方法的所有实例共享一份参数/返回值/池缓存），class key 共享到类粒度（同类的所有方法共享一份 binder/codec interceptor 缓存——因为 `@InitBinder`/`@ControllerAdvice` 是类级的）。**共享粒度匹配数据本身的归属粒度**，这是这套机制在"内存占用最小"与"查表最快"间的精确取舍。

---

## 九、生命周期重放：支持后注册组件

`registerWebComponent(WebComponent)`（[`WebComponentContainer.java`](../../spring-web/src/main/java/io/springperf/web/context/WebComponentContainer.java)）不只做注册，还承担**生命周期重放**——让在生命周期进行中或之后才注册的组件补跑它错过的阶段：

```java
// WebComponentContainer.java（重放逻辑）
if (state.get() == State.INIT_CONTEXT || state.get() == State.PHASE1
        || state.get() == State.PHASE2 || state.get() == State.PHASE3) {
    webComponent.initWithWebContext(webContext);              // 已过 initWithWebContext → 补跑
}
if (state.get() == State.PHASE1 || state.get() == State.PHASE2
        || state.get() == State.PHASE3) {
    initComponentPhase1(webComponent);                       // 已过 Phase1 → 补跑
}
if (state.get() == State.PHASE2 || state.get() == State.PHASE3) {
    initComponentPhase2(webComponent);                       // 已过 Phase2 → 补跑
}
if (state.get() == State.PHASE3) {
    initComponentPhase3(webComponent);                       // 已过 Phase3 → 补跑
}
if (state.get() == State.DESTROY) {
    destroyComponent(webComponent);                          // 容器已销毁 → 直接 destroy 新组件
}
```

重放按当前 `state` 补跑所有"已过的阶段"：若容器已进 Phase3，新注册组件会被依次补跑 `initWithWebContext` → Phase1 → Phase2 → Phase3，追平到当前阶段。

这条机制支撑两个真实场景：

1. **Registry 的依赖拉取**（第五节）：`dispatcherHandler.initWithWebContext` 在 `WebContext` 已是 `INIT_CONTEXT` 状态时注册 9 个 Registry，每个 Registry 立即被重放 `initWithWebContext`，从而能继续拉取自己的依赖。若没有重放，这些 Registry 的 `initWithWebContext` 要等到下一阶段递归才跑——但下一阶段递归的 `getSortedWebComponents()` 快照在阶段开始时已固定，后注册的 Registry 可能漏跑。
2. **延迟注册的组件**：某些 Adapter/Wrapper 可能在 Phase2/3 才被注册，重放保证它们补跑到当前阶段，与原生组件行为一致。

`DESTROY` 分支是优雅降级——容器已在关闭，新注册组件直接 `destroyComponent`，不补跑初始化。

---

## 十、组件冲突解决：显式不静默

同名组件注册时，`registerWebComponent` 不静默覆盖，而是按 `@Order` 明确取舍并告警：

```java
// WebComponentContainer.java
if (webComponents.containsKey(webComponent.getComponentName())) {
    WebComponent oldComponent = webComponents.get(webComponent.getComponentName());
    List<WebComponent> list = Arrays.asList(webComponent, oldComponent);
    AnnotationAwareOrderComparator.sort(list);               // 按 @Order 排序
    WebComponent newComponent = list.get(0);                  // 取优先级最高（order 值最小）
    webComponents.put(webComponent.getComponentName(), newComponent);
    log.warn("{} components have conflicts. Use {} and deprecate {}",
            webComponent.getComponentName(), newComponent, list.get(1));  // 显式告警
    if (newComponent == webComponent) {
        destroyComponent(oldComponent);                      // 新者胜 → destroy 旧者
    } else {
        return;                                              // 旧者胜 → 不动，新者被丢弃
    }
} else {
    webComponents.put(webComponent.getComponentName(), webComponent);
}
```

逻辑要点：

1. **`getComponentName()` 默认取简单类名**（`WebComponent.java`）——所以"同名"是"同类名"。当新注册组件与已有组件产生同类名冲突时，正是走这条路径。
2. **`AnnotationAwareOrderComparator.sort`** 按 `@Order`/`Ordered` 排序，`list.get(0)` 取优先级最高（order 值最小）者保留。
3. **`log.warn` 显式告知"用了谁、废弃了谁"**——业务方能在启动日志看到覆盖关系，而非在某个请求出诡异行为后才发现某组件被悄悄换掉。这是 [01 篇原则 6](01-design-philosophy.md#原则-6--避免魔法行为显式-spifail-fast不靠隐式猜测) "避免魔法行为"的代码落地。
4. **败者被 `destroyComponent`**：新者胜则旧者销毁（释放线程池等资源）；旧者胜则新者直接 return 被丢弃（新者从未初始化，无需 destroy）。资源不泄漏、状态不残留。

> 注意冲突解决发生在重放逻辑**之前**（ 在  前）——即先决定"留谁"，再对留存的组件补跑生命周期。

---

## 十一、fail-fast 设计

Phase3 的职责注释明确写着 "final preparation for request handling"（`LifecycleWebComponent.java`）——这意味着"运行时才会暴问题"的情况要在 Phase3 就 fail-fast。`ArgumentResolverRegistry` 是最典型的 fail-fast 落地点。

### validateAllParametersResolvable

```java
// ArgumentResolverRegistry.java（Phase3）
@Override
public void initComponentPhase3() throws Exception {
    if (webContext.getProps().getBoolean(PropertiesConstant.CHECK_ON_STARTUP, true)) {
        validateAllParametersResolvable();
    }
}
```

`validateAllParametersResolvable`（摘要）遍历 `mappingRegistry.getMappingContextList()` 的每一个 `PathMappingContext`，对每个参数：

1. **`isParameterResolvable`**：遍历 12 个 `StaticArgumentResolverProvider` 的 `supports`，命中则通过；不命中走 fallback——简单类型 → `requestParamResolverProvider`，否则 → `modelAttributeResolverProvider`。全不匹配则抛异常。
2. **`validateModelAttributeConstructor`**（D3 fail-fast）：标注 `@ModelAttribute` 且无默认构造器的类，**启动直接失败**——而不是等到运行时第一次绑定才发现 `InstantiationException`。

校验开关 `CHECK_ON_STARTUP` 默认 `true`，可通过配置关闭（用于某些启动速度敏感、愿意接受运行时降级的场景）。这是显式的"校验可配"而非"魔法默认"——又是 [01 篇原则 6](01-design-philosophy.md#原则-6--避免魔法行为显式-spifail-fast不靠隐式猜测) 的体现。

### fail-fast 的哲学

> **宁可启动失败，不要运行时降级。**

这条原则的依据是：生产环境的请求处理必须行为确定。一个参数解析器匹配不上的端点，如果在运行时才暴露，可能表现为：部分请求 500、参数静默为 null、绑定行为非预期——这些都是难排查的"魔法行为"。在 Phase3 fail-fast，把"配置错误"转化为"启动失败"，让开发者在部署前就发现——这是"启动时确定"在可靠性维度的延伸。

除 ArgumentResolverRegistry 外，MappingRegistry 的 Phase1 也有隐式 fail-fast：`ReflectionUtils.getUniqueDeclaredMethods` 对非法映射会抛异常（D4 修复后重复映射容忍，但非法映射仍 fail-fast）；`WebDataBinderRegistry` 的 Phase1 对无默认构造器的 `@ModelAttribute` 经 `validateModelAttributeConstructor` 间接 fail-fast。

---

## 十二、小结：生命周期作为确定性的执行契约

回到引子的五个问题：

1. **`afterPropertiesSet()` 留空**——因为 Spring bean 初始化时点无法保证基础容器已就绪；真正触发三阶段的是 `NettyHttpServer.start()` → `WebContext.startLifecycle()`，锚定在 context refresh 完毕后的 `SmartLifecycle` 链最末尾。
2. **三阶段顺序**——Phase1 自建元数据（不跨引用）→ Phase2 跨组件连接建索引（引用已就绪）→ Phase3 优化 + fail-fast（全局结构就绪可做压缩/校验）。顺序由"数据依赖链"决定，CAS 守卫保证全局同步推进。
3. **13 个 Registry 的容器树**——`dispatcherHandler` 是叶子协调者，9 个 Registry 平铺注册到 `WebContext` 根容器；共享型依赖落根容器，私有型依赖（`WebDataBinderRegistry` 等）落拥有者 `ArgumentResolverRegistry` 子树。共享边界决定注册落点。
4. **混合模式不让启动变慢/内存变大**——急切层只分配 index 槽位 + 做校验/路由优化；懒层把解析器/处理器匹配推迟到首次请求，零调用端点保持零内存。`ArgumentResolverRegistry` Phase3 只校验不创建是典型。
5. **`MappingCacheKey` 的无锁根**——生产侧两个独立 `AtomicInteger` 池（method/class 索引空间隔离）；消费侧 `MappingHandlerMethod` 两个 `static ConcurrentHashMap`（per-Class/per-Method 共享数组）+ 两个 `volatile` 实例字段读缓存（免 map 查找）+ `boolean classCache` 选数组；`get` 全程无锁 `aaload`，`set` 仅 DCL 扩容上锁、正常写入无锁。

这套生命周期体系是 [01 篇](01-design-philosophy.md) 六大原则的**执行契约**：原则 1（零匹配）靠 MappingCacheKey 的整型索引 + 无锁数组落地；原则 2（零分配）靠混合缓存模式让零调用端点零内存；原则 5（零反射）靠 Phase1 一次性解析注解/泛型固化；原则 6（显式 SPI + fail-fast）靠 `registerWebComponent` 的冲突告警 + Phase3 `validateAllParametersResolvable` 落地。生命周期不是"初始化框架"，而是"把运行时该做的事前移到启动时"的机械装置——后续各篇会在这套骨架上展开每个 Registry 的内部机制。

---

> **下一篇**：[04 · 请求处理管线全链路](04-request-pipeline.md)——把这套生命周期产出的缓存放进一次真实请求的完整路径：路由匹配 → 线程池调度 → Filter 链 → 参数解析 → 方法调用 → 返回值处理 → 异常处理，逐段标注用了哪个 Registry 的哪份缓存。
