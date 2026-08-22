# 09 · 调用器与字节码优化

> [← 返回索引](00-README.md) | 上一篇：[08 · 返回值解析](08-returnvalue-resolution.md) | 下一篇：[10 · 横切关注点：拦截器、Filter、CORS、异常](10-cross-cutting.md)

---

## 引子：方法调用是"反射"的最后一个阵地

[07 篇](07-argument-resolution.md) 把参数解析好了，[08 篇](08-returnvalue-resolution.md) 等返回值来写响应。中间隔着一个问题：**参数数组怎么变成业务方法的实际调用？**

Spring MVC 的回答是 `Method.invoke`——Java 反射的标准做法，每次调用付 access check + 可变参数装箱 + 类型校验的开销，约 200ns。

本框架的回答是三条路：**`@Optimize` 标注的方法走 ASM 生成的 `INVOKEVIRTUAL` 字节码（~10ns），默认走 `MethodHandle.invokeExact`（~30ns），都不走的 Spring 原生 `HandlerMethod` 兜底（极少发生）。** 反射只在启动期出现，运行时从调用路径上彻底消除。

---

## 一、`Invoker` 接口：最简单的合同

```java
// Invoker.java
public interface Invoker {
    Object invoke(Object[] args) throws Throwable;
}
```

`Invoker` 是整个调用体系的最小公分母。`args` 是 [07 篇](07-argument-resolution.md) `resolveArguments` 产出的 `Object[]` 数组，`invoke` 返回业务方法的返回值（`void` 方法返回 `null`）。接口本身不携带任何元数据——元数据（方法签名、参数类型、注解）在 `InvokableHandlerMethod` 中，`Invoker` 只负责"调用"。

---

## 二、`InvokableHandlerMethod`：启动期决策

`InvokableHandlerMethod` 继承 Spring 的 `HandlerMethod`，是框架中所有可调用方法的基类。它在构造器中决定使用哪种 Invoker：

```java
// InvokableHandlerMethod.java
public InvokableHandlerMethod(Object bean, Method method) {
    super(bean, method);
    if (bean instanceof Invoker) {
        this.invoker = (Invoker) bean;
        this.optimized = false;
    } else {
        this.optimized = hasOptimizeAnnotation();
        this.invoker = initInvoker();
    }
    for (MethodParameter methodParameter : getMethodParameters()) {
        methodParameter.initParameterNameDiscovery(parameterNameDiscoverer);
    }
}
```

### 2.1 三路决策

```java
// InvokableHandlerMethod.java
private Invoker initInvoker() {
    if (optimized && !IN_NATIVE_IMAGE) {
        try {
            return createFastInvoker();    // ① ASM 字节码
        } catch (Throwable e) {
            logger.error("create fast invoker error", e);
        }
    }
    return createCommonInvoker();          // ② MethodHandle
}
```

| 路径 | 条件 | 实现 | 开销 |
|------|------|------|------|
| **ASM 字节码** | `@Optimize` 标注 + 非 GraalVM native-image | `FastInvokerGenerator.createInvoker` → `INVOKEVIRTUAL` | ~10ns |
| **MethodHandle** | 默认（无 `@Optimize`） | `MethodHandle.invokeExact` | ~30ns |
| **CustomInvoker** | Bean 本身实现 `Invoker` 接口（如 Actuator 端点） | 直接使用 Bean 作为 Invoker | 取决于实现 |

### 2.2 GraalVM 检测

```java
// InvokableHandlerMethod.java
private static final boolean IN_NATIVE_IMAGE =
        System.getProperty("org.graalvm.nativeimage.imagecode") != null;
```

GraalVM native-image 在封闭世界中**不允许运行时动态生成字节码**，`MethodHandles.lookup()` 也受限。`IN_NATIVE_IMAGE` 标志在启动期检测，如果检测到 native-image 环境，`initInvoker` 降级为 `createCommonInvoker`（`MethodHandle` 在 native-image 中受限时，由 `HandlerMethod` 的 `invoke` 兜底——框架未显式处理此降级，但 `MethodHandle` 的 `IllegalAccessException` 会在 `createCommonInvoker` 中抛 `RuntimeException`；实际上 native-image 场景应通过 `CustomInvoker` 显式注册）。

### 2.3 `@Optimize` 注解检测

`hasOptimizeAnnotation`检查类级别或方法级别的 `@Optimize` 注解：

```java
// InvokableHandlerMethod.java
private boolean hasOptimizeAnnotation() {
    return getMethodAndClassAnnotation(Optimize.class) != null;
}
```

`getMethodAndClassAnnotation`优先查找方法注解，再查找类注解。这意味着在类上标注 `@Optimize` 会启用该类所有方法的字节码优化，方法上的 `@Optimize` 覆盖类级别的行为。

---

## 三、`FastInvokerGenerator`：ASM 字节码生成

### 3.1 整体流程

`FastInvokerGenerator.createInvoker`是入口：

```java
// FastInvokerGenerator.java
public static Invoker createInvoker(Object controller, Class controllerClass, Method method) throws Throwable {
    if (IN_NATIVE_IMAGE) {
        throw new UnsupportedOperationException("...");
    }
    Class<?> invokerClass = invokerClassCache.computeIfAbsent(method, (m) -> generateClass(controllerClass, m));
    Constructor<?> ctor = invokerClass.getConstructor(controllerClass);
    return (Invoker) ctor.newInstance(controller);
}
```

三步：① `invokerClassCache.computeIfAbsent` → 每个方法对应一个生成的类（缓存在 `ConcurrentHashMap<Method, Class<?>>` 中）。② 反射调用构造器（`invokerClass(ControllerType)` 传入 controller 实例）。③ 返回 `Invoker` 实例。

### 3.2 字节码生成：`generateBytes`

`generateBytes`使用 Spring 的 `ClassWriter`（ASM 封装）生成一个实现 `Invoker` 接口的类：

```java
// FastInvokerGenerator.java
private static byte[] generateBytes(Class<?> controllerClass, Method method, String className) {
    String invokerInternal = className.replace(".", "/");
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, invokerInternal, null,
            "java/lang/Object", new String[]{Type.getInternalName(Invoker.class)});
    cw.visitField(ACC_PRIVATE | ACC_FINAL, "target", Type.getDescriptor(controllerClass), null, null);
    generateConstructor(cw, invokerInternal, controllerClass);
    generateInvokeMethod(cw, invokerInternal, controllerClass, method);
    cw.visitEnd();
    return cw.toByteArray();
}
```

生成的类结构：

```
class Invoker$UserController$getUser$0 implements Invoker {
    private final UserController target;          // 持有 controller 实例

    Invoker$UserController$getUser$0(UserController target) {
        this.target = target;
    }

    public Object invoke(Object[] args) {
        return this.target.getUser(                // INVOKEVIRTUAL 直接调用
            (String) args[0],                      // CHECKCAST + AALOAD
            (Integer) args[1]                      // CHECKCAST + unbox
        );
    }
}
```

### 3.3 参数拆箱与返回值装箱

`generateInvokeMethod`生成 `invoke(Object[])` 方法体。核心是三步：

1. **加载 `this.target`**：`ALOAD 0` → `GETFIELD target`
2. **加载参数**：遍历 `method.getParameterTypes()`，从 `args[]` 依次 `AALOAD` 取值，基本类型 `unbox`（拆箱），引用类型 `CHECKCAST`（转型）。
3. **调用方法 + 处理返回值**：`INVOKEVIRTUAL` 调用目标方法，`void` 返回 `ACONST_NULL`，基本类型返回 `box`（装箱）。

`unbox`和 `box`覆盖全部 8 种基本类型：`int`/`boolean`/`long`/`double`/`float`/`short`/`byte`/`char`。每个都对应一个 `CHECKCAST` + `XXXValue` 的拆箱序列，或 `valueOf` 的装箱序列。

### 3.4 类名与缓存

生成的类名格式：`Invoker$ControllerSimpleName$methodName$counter`。`CLASS_COUNTER` 是 `AtomicInteger`，确保类名唯一。

`invokerClassCache`是 `ConcurrentHashMap<Method, Class<?>>`，按方法缓存生成的类字节码。**同一个方法的所有 `InvokableHandlerMethod` 实例共享生成的类**——即使同一个方法有多个 controller 实例（极少见），也只生成一次字节码。

### 3.5 类定义

`defineClass`使用 Spring CGLIB 的 `ReflectUtils.defineClass` 加载生成的字节码：

```java
// FastInvokerGenerator.java
@SneakyThrows
public static Class defineClass(String className, byte[] bytecode, Class contextClass) {
    return ReflectUtils.defineClass(className, bytecode, contextClass.getClassLoader(), null, contextClass);
}
```

---

## 四、`MethodHandleInvoker`：默认调用策略

`MethodHandleInvoker` 是默认调用器（无 `@Optimize` 时），在 `createCommonInvoker` 中创建：

```java
// InvokableHandlerMethod.java
protected Invoker createCommonInvoker() {
    MethodHandle methodHandle = MethodHandles.lookup().unreflect(getBridgedMethod()).bindTo(getBean());
    return new MethodHandleInvoker(methodHandle);
}
```

`MethodHandles.lookup().unreflect(method)` 将反射 `Method` 转为 `MethodHandle`，`bindTo(bean)` 绑定 receiver。`MethodHandleInvoker` 的构造器做 `asSpreader` 适配：

```java
// MethodHandleInvoker.java
public MethodHandleInvoker(MethodHandle methodHandle) {
    int paramCount = methodHandle.type().parameterCount();
    if (paramCount > 0) {
        MethodHandle spread = methodHandle.asSpreader(Object[].class, paramCount);
        this.methodHandle = spread.asType(MethodType.methodType(Object.class, Object[].class));
    } else {
        MethodHandle noArg = methodHandle.asType(MethodType.methodType(Object.class));
        this.methodHandle = MethodHandles.dropArguments(noArg, 0, Object[].class);
    }
}
```

`asSpreader` 将 `Object[]` 数组展开为独立参数，`asType` 统一签名。对于无参方法，`dropArguments` 忽略传入的 `Object[]` 参数。运行时 `invokeExact`直接调用：

```java
// MethodHandleInvoker.java
public Object invoke(Object[] args) throws Throwable {
    return methodHandle.invokeExact(args);
}
```

`invokeExact` 是 `MethodHandle` 最快速的调用方式——不装箱、不类型检查、不 access check，仅做方法签名匹配。约 30ns 的开销主要来自 JIT 编译前的解释执行和参数传播。

---

## 五、`invoke` 运行时入口

`InvokableHandlerMethod.invoke`是运行时调用入口，在 `DispatcherHandler` 的 `doHandle` 中被调用：

```java
// InvokableHandlerMethod.java
public Object invoke(Object[] args, WebServerHttpRequest request, WebServerHttpResponse response) throws Throwable {
    Object value = invoker.invoke(args);
    setResponseStatus(request, response);
    return value;
}
```

`invoke` 之后调用 `setResponseStatus`，处理 `@ResponseStatus` 注解：

```java
// InvokableHandlerMethod.java
public void setResponseStatus(WebServerHttpRequest request, WebServerHttpResponse response) {
    HttpStatusCode statusCode = getResponseStatus();
    if (statusCode == null) return;
    if (response != null) {
        String reason = getResponseStatusReason();
        if (StringUtils.hasText(reason)) {
            response.sendError(HttpStatus.resolve(statusCode.value()), reason);
        } else {
            response.setStatusCode(statusCode);
        }
    }
}
```

`getResponseStatus()` 继承自 Spring 的 `HandlerMethod`，读取方法或类上的 `@ResponseStatus` 注解。如果有 `reason` 文本，调用 `sendError`（带错误消息），否则直接 `setStatusCode`。

---

## 六、调用开销对比

| 方式 | 开销 | 原因 |
|------|------|------|
| `Method.invoke`（Spring MVC） | ~200ns | access check + 可变参数装箱 + `Object[]` 创建 + 类型校验 |
| `MethodHandle.invokeExact`（默认） | ~30ns | 无 access check，无装箱，签名匹配在构造时确定 |
| ASM `INVOKEVIRTUAL`（`@Optimize`） | ~10ns | 直接字节码调用，无运行时检查，无 MethodHandle 分发 |

~20 倍的差距（`Method.invoke` 200ns vs `INVOKEVIRTUAL` 10ns）在单次调用中微不足道，但在每请求数百次调用（json 序列化、参数绑定、拦截器链）的累积下，这个差距被放大到可测量。Benchmark 数据（[17 篇](17-benchmark-data.md) 的 `invoke` 耗时对比）会给出可量化的数据。

**为什么默认不走 `INVOKEVIRTUAL`？** 因为 `@Optimize` 需要用户显式标注——ASM 生成字节码的侵入性（通过 `FastInvokerGenerator.defineClass` 动态加载类）在某些安全敏感环境（如 `SecurityManager` 限制类加载）中受限。`MethodHandle` 作为默认值是安全与性能的平衡点——无需用户配置，自动获得 ~6x 相对于反射的加速。

---

## 七、`CustomInvoker`：非控制器处理器

`CustomInvoker`是 `Invoker` 的扩展接口，用于非 `@Controller` 的处理器端点（如 Actuator 端点）：

```java
// CustomInvoker.java
public interface CustomInvoker extends Invoker {
    Method getHandleMethod();           // 底层处理方法
    default List<Matcher> getMatchers() { return Collections.emptyList(); }  // 请求预过滤条件
    String getType();                   // 类型标识（如 "Actuator"）
}
```

`CustomInvoker` 通过 `PathMappingContext` 的第二个构造器（`PathMappingContext(CustomInvoker invoker, String pathRule)`）注册到路由中，不走 `@Controller` 扫描流程。`getType()` 返回的标识符用于 `PathMappingContext.toString()` 中的类型显示（如 `Actuator:/health`）。

---

## 八、对比 Spring MVC 方法调用

| 维度 | 本框架 | Spring MVC |
|------|--------|-----------|
| 默认调用方式 | `MethodHandle.invokeExact`（~30ns） | `Method.invoke`（~200ns） |
| 优化路径 | `@Optimize` → ASM 生成 `INVOKEVIRTUAL`（~10ns） | 无等效机制 |
| 调用器缓存 | 按方法缓存生成的字节码类（`ConcurrentHashMap<Method, Class<?>>`） | 无缓存，每次调用 `Method.invoke` |
| 参数传递 | `Object[]` 数组直传 | `Object[]` 直传（同本框架） |
| 返回值处理 | `invoke` 后 inline `setResponseStatus` | `invoke` 后由 `ModelAndViewContainer` 处理 |
| GraalVM 兼容 | `IN_NATIVE_IMAGE` 检测，降级 `MethodHandle` | 原生支持（`Method.invoke` 可用） |
| 非控制器扩展 | `CustomInvoker` SPI | `HandlerMethod` 继承 + `RequestMappingInfo` |
| 安全沙箱 | `@Optimize` 需用户显式标注，默认用 `MethodHandle` | 无额外限制 |

**核心差异**：Spring MVC 的 `InvocableHandlerMethod.doInvoke` 走 `Method.invoke`，每次调用都付 access check 和可变参数装箱的开销。本框架的把调用从"反射"降级为"直接的字节码调用"或"MethodHandle 虚调用"——`MethodHandle.invokeExact` 在 JIT 内联后链式调用可以被完全优化掉，而 `Method.invoke` 的 native 方法边界阻止了 JIT 的跨方法内联。

---

## 九、小结：调用器的克制在哪里

回到引子的问题：参数数组怎么变成业务方法的实际调用？

1. **`InvokableHandlerMethod` 构造器启动期决策** → `@Optimize` 走 ASM 字节码，默认走 `MethodHandle`，`Invoker` Bean 直接使用。
2. **`FastInvokerGenerator` ASM 生成 `INVOKEVIRTUAL`** → 每个方法生成一个 `Invoker` 实现类，`invoke(Object[])` 方法体是 `CHECKCAST` + `unbox` + `INVOKEVIRTUAL` + `box`。
3. **`MethodHandleInvoker` `asSpreader` 适配** → `Object[]` 展开为独立参数，`invokeExact` 直调。
4. **`invoke` 后 `setResponseStatus`** → `@ResponseStatus` 注解处理，内联在调用链中。
5. **`CustomInvoker` SPI 扩展** → 非控制器处理器通过自定义 `Invoker` 加入路由。

这一层的克制体现在：**不把反射调用当作"不可避免的代价"。** 框架提供了三条路径（ASM / MethodHandle / 反射），每条路径的启动期决策成本和运行时性能都是显式的。`@Optimize` 标注让用户为关键路径选择最优路径，`MethodHandle` 默认提供无配置的性能提升，`Method.invoke` 只在极少数边界场景被触发。这种"不给全部用户强加同一代价"的设计，是 [01 篇](01-design-philosophy.md) 原则 5（零反射）最直接的代码证据。

---

> **下一篇**：[10 · 横切关注点：拦截器、Filter、CORS、异常](10-cross-cutting.md)——方法调用前后的横切逻辑：拦截器 preHandle/postHandle、WebFilter 链、CORS 预检、异常处理。