# 08 · 返回值解析

> [← 返回索引](00-README.md) | 上一篇：[07 · 参数解析体系](07-argument-resolution.md) | 下一篇：[09 · 调用器与字节码优化](09-invoker-bytecode.md)

---

## 引子：返回值解析是"内容协商"的最后一公里

[07 篇](07-argument-resolution.md) 讲完参数怎么注入业务方法，本篇讲业务方法执行完后，返回值怎么变成 HTTP 响应。路径是：`DispatcherHandler` 调用业务方法拿到返回值 → `ReturnValueResolverRegistry.resolveReturnValue` → 匹配一个 Resolver → 写入响应。

Spring MVC 的做法是：运行时遍历 `HandlerMethodReturnValueHandlerComposite` 的 `returnValueHandlers` 列表，逐个调用 `supportsReturnType` 直到命中，再调用 `handleReturnValue`。**每次请求都走一遍遍历**。

本框架的做法是：**启动期记录返回类型信息，运行时首次请求缓存 Resolver 实例，后续请求两次虚方法调用直取。** 核心是三级缓存策略（fast path + async inner + 线性扫描降级），兼顾最优性能与通用性。

---

## 一、整体架构：14 个内置 Resolver

### 1.1 注册顺序

`ReturnValueResolverRegistry.initReturnValueResolver()`按分组注册 14 个内置 Resolver：

```java
// ReturnValueResolverRegistry.java
public void initReturnValueResolver() {
    // 异步处理
    registerWebComponent(new DeferredResultReturnValueResolver());
    registerWebComponent(new ListenableFutureReturnValueResolver());
    registerWebComponent(new CompletionStageReturnValueResolver());
    registerWebComponent(new AsyncTaskReturnValueResolver());
    registerWebComponent(new CallableReturnValueResolver());
    registerWebComponent(new StreamEmitterReturnValueResolver());
    registerWebComponent(new ReactiveReturnValueResolver());
    // 流式处理相关
    registerWebComponent(new ByteArrayReturnValueResolver());
    registerWebComponent(new ResourceReturnValueResolver());
    registerWebComponent(new InputStreamReturnValueResolver());
    registerWebComponent(new FileReturnValueResolver());
    // 通用实体处理
    registerWebComponent(new HttpEntityReturnValueResolver());
    registerWebComponent(new JsonBodyReturnValueResolver());
    // 拓展处理
    registerWebComponent(ReturnValueResolver.class);
    initRealComponentList(resolvers, ReturnValueResolver.class);
}
```

按分组分类：

| 分组 | Resolver | 匹配条件 | 写出方式 |
|------|----------|---------|---------|
| 异步 | `DeferredResultReturnValueResolver` | `DeferredResult` 类型 | `AsyncSupportRegistry.startDeferredResultProcessing` |
| 异步 | `ListenableFutureReturnValueResolver` | `ListenableFuture` 类型 | 同上 |
| 异步 | `CompletionStageReturnValueResolver` | `CompletionStage` 类型 | 同上 |
| 异步 | `AsyncTaskReturnValueResolver` | `AsyncTask` 类型 | 同上 |
| 异步 | `CallableReturnValueResolver` | `Callable` 类型 | `AsyncSupportRegistry.startCallableProcessing` |
| 流式 | `StreamEmitterReturnValueResolver` | `StreamEmitter` 类型 | `NettyStreamSender` 流式输出 |
| 响应式 | `ReactiveReturnValueResolver` | `Publisher` 类型 | `PublisherToStreamEmitterAdapter` |
| 字节流 | `ByteArrayReturnValueResolver` | `byte[]` 类型 | `resp.writeBytes` |
| 资源 | `ResourceReturnValueResolver` | `Resource` 类型 | `resp.writeFile`（文件）或 `resp.writeStream`（流） |
| 输入流 | `InputStreamReturnValueResolver` | `InputStream` 类型 | `resp.writeStream` |
| 文件 | `FileReturnValueResolver` | `File` / `Path` 类型 | `resp.writeFile` |
| 通用实体 | `HttpEntityReturnValueResolver` | `HttpEntity` / `ResponseEntity` 类型 | 写状态码 + headers + body |
| JSON | `JsonBodyReturnValueResolver` | `@ResponseBody` 注解 | `HttpBodyCodecRegistry.writeBody` |
| 拓展 | `WrapMessageConverterReturnValueResolver` | 用户注册的 `HttpMessageConverter` | `messageConverter.write` |

**注册顺序 = 线性扫描的匹配顺序**。异步分组排在最前，确保 `DeferredResult` 等异步类型在同步类型之前被匹配——否则 `JsonBodyReturnValueResolver`（`supportsReturnValue` 始终返回 true）会"吃掉"所有异步返回值。

### 1.2 `ReturnValueResolver` 接口

```java
// ReturnValueResolver.java
public interface ReturnValueResolver extends WebComponent {
    boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext);
    boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp);
    void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception;
}
```

**为什么有 `supportsReturnType` 和 `supportsReturnValue` 两个检查？**

- `supportsReturnType`（类型检查，静态）：用于启动期和异步内联类型预匹配。只看方法签名，不依赖具体返回值实例。
- `supportsReturnValue`（值检查，运行时）：查看具体的返回值对象。例如 `HttpEntityReturnValueResolver.supportsReturnValue`检查 `returnValue instanceof HttpEntity`——运行时才能确定。
- `JsonBodyReturnValueResolver.supportsReturnValue` 始终返回 `true`——这是兜底行为：`@ResponseBody` 注解的方法，任何返回值都交给 `HttpBodyCodecRegistry.writeBody` 处理。

---

## 二、三级缓存：`MethodReturnValueContext`

### 2.1 方法级上下文

`MethodReturnValueContext`是每个业务方法的返回值元数据容器，通过 `MappingCacheKey` 缓存在 `MappingHandlerMethod` 中：

```java
// MethodReturnValueContext.java
public class MethodReturnValueContext {
    protected MethodParameter returnType;                          // 方法返回类型
    protected ReturnValueResolver returnValueResolver;             // 缓存的主解析器
    protected MethodParameter innerReturnType;                     // 异步泛型参数类型
    protected ReturnValueResolver innerReturnValueResolver;        // 匹配 innerReturnType 的解析器
    protected boolean asyncType;                                   // 是否为异步返回类型
    protected boolean optionalType;                                // 是否为 Optional 返回类型
    protected MethodParameter optionalInnerReturnType;             // Optional 内联类型
    protected ReturnValueResolver optionalInnerReturnValueResolver;// 匹配 optionalInnerReturnType 的解析器
}
```

### 2.2 三级缓存策略

`doResolveReturnValue`的运行时分发逻辑：

```java
// ReturnValueResolverRegistry.java
protected boolean doResolveReturnValue(Object returnValue, MappingHandlerMethod mappingContext, ...) throws Exception {
    // Optional 解包
    if (returnValue instanceof Optional<?> opt) {
        if (opt.isEmpty()) return true;
        returnValue = opt.get();
        // 声明式 Optional：尝试内联解析器缓存
        if (returnValueContext != null && returnValueContext.isOptionalType()) {
            returnType = returnValueContext.getOptionalInnerReturnType();
            ReturnValueResolver innerResolver = returnValueContext.getOptionalInnerReturnValueResolver();
            if (innerResolver != null && innerResolver.supportsReturnValue(returnValue, req, resp)) {
                innerResolver.resolveReturnValue(returnValue, returnType, req, resp);
                return true;
            }
        }
    }

    // Fast path 1: 缓存的主解析器
    ReturnValueResolver resolver = returnValueContext.getReturnValueResolver();
    if (resolver != null && resolver.supportsReturnValue(returnValue, req, resp)) {
        resolver.resolveReturnValue(returnValue, returnType, req, resp);
        return true;
    }
    // Fast path 2: 异步 dispatch 后，内联泛型解析器
    if (returnValueContext.isAsyncType()) {
        ReturnValueResolver innerResolver = returnValueContext.getInnerReturnValueResolver();
        if (innerResolver != null && innerResolver.supportsReturnValue(returnValue, req, resp)) {
            innerResolver.resolveReturnValue(returnValue, returnValueContext.getInnerReturnType(), req, resp);
            return true;
        }
    }
    // 线性扫描（兜底）
    for (ReturnValueResolver resolver : resolvers) {
        if (resolver.supportsReturnValue(returnValue, req, resp)) {
            resolver.resolveReturnValue(returnValue, returnType, req, resp);
            // 懒缓存
            if (returnValueContext != null) {
                if (returnValueContext.isOptionalType()) {
                    returnValueContext.setOptionalInnerReturnValueResolver(resolver);
                } else {
                    returnValueContext.setReturnValueResolver(resolver);
                }
                if (resolver instanceof BaseAsyncReturnValueResolver) {
                    resolveInnerReturnValueContext(returnValueContext, mappingContext);
                }
            }
            return true;
        }
    }
    return false;
}
```

三级缓存策略：

| 级别 | 条件 | 时机 | 命中率 |
|------|------|------|--------|
| **Fast path 1** | `returnValueContext.returnValueResolver != null` + `supportsReturnValue` | 第二次请求起 | 绝大多数同步方法 |
| **Fast path 2** | `asyncType == true` + `innerReturnValueResolver != null` + `supportsReturnValue` | 异步 dispatch 完成后 | 异步方法的内联类型 |
| **线性扫描** | 遍历 `resolvers` 列表逐个 `supportsReturnValue` | 首次请求 + 缓存不匹配时 | 兜底 |

**Fast path 1 覆盖 >95% 的请求**。典型场景：`@ResponseBody` 方法返回 `User` 对象，首次请求线性扫描命中 `JsonBodyReturnValueResolver`，缓存到 `returnValueResolver`。第二次请求起，`supportsReturnValue` 检查一次（`JsonBodyReturnValueResolver` 始终返回 true），写入响应。

### 2.3 异步内联类型解析

`resolveInnerReturnValueContext`在异步解析器触发时，从泛型中提取内联类型并缓存对应的 Resolver：

```java
// ReturnValueResolverRegistry.java
protected void resolveInnerReturnValueContext(MethodReturnValueContext context, MappingHandlerMethod mappingContext) {
    context.setAsyncType(true);
    MethodParameter effectiveReturnType = mappingContext.getEffectiveReturnType();
    ResolvableType rt = effectiveReturnType != null
            ? ResolvableType.forType(effectiveReturnType.getGenericParameterType())
            : ResolvableType.forMethodReturnType(mappingContext.getMethod());
    ResolvableType generic = rt.getGeneric(0);
    Class<?> innerClass = generic.resolve();
    if (innerClass == null || innerClass == Object.class) return; // 无法确定泛型，跳过缓存

    MethodParameter innerReturnType = new MethodParameter(mappingContext.getMethod(), -1) {
        private final ResolvableType genericRt = rt.getGeneric(0);
        @Override public Class<?> getParameterType() { return genericRt.resolve(); }
        @Override public Type getGenericParameterType() { return genericRt.getType(); }
    };
    context.setInnerReturnType(innerReturnType);
    for (ReturnValueResolver resolver : resolvers) {
        if (resolver.supportsReturnType(innerReturnType, mappingContext)) {
            context.setInnerReturnValueResolver(resolver);
            break;
        }
    }
}
```

例如 `DeferredResult<User>`：首次请求时，`DeferredResultReturnValueResolver` 匹配并触发 `resolveInnerReturnValueContext`，提取 `User` 类型，找到 `JsonBodyReturnValueResolver` 缓存为 `innerReturnValueResolver`。异步 dispatch 完成后，`User` 对象回到 `doResolveReturnValue`，命中 Fast path 2，直接写 JSON。

**如果泛型无法确定（如 `DeferredResult<?>` 或 `DeferredResult<Object>`）**，跳过缓存，运行时走线性扫描兜底——`supportsReturnValue` 逐个检查。

---

## 三、`skipResolve`：null 与 void 处理

`skipResolve`在解析前决定是否跳过：

```java
// ReturnValueResolverRegistry.java
protected boolean skipResolve(Object returnValue, MappingHandlerMethod mappingContext, WebServerHttpRequest req, WebServerHttpResponse resp) {
    if (returnValue == null) {
        if (mappingContext != null && mappingContext.getMethod().getReturnType() == void.class && !resp.isHandled()) {
            resp.setHandled();    // void 方法返回 null → 标记已处理
        }
        return true;
    }
    return resp.isHandled();      // 已被 Filter/Interceptor 等先行处理，跳过
}
```

两个分支：

- **`returnValue == null` + `void` 方法**：`void` 方法返回 null 是正常行为，`setHandled()` 标记已处理，框架不再写 body。
- **`resp.isHandled()` 为 true**：响应已被 Filter/Interceptor 或异常处理器提前写出，跳过返回值解析。

---

## 四、核心 Resolver 详解

### 4.1 `JsonBodyReturnValueResolver`：`@ResponseBody`

```java
// JsonBodyReturnValueResolver.java
public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
    return (AnnotatedElementUtils.hasAnnotation(returnType.getContainingClass(), ResponseBody.class) ||
            returnType.hasMethodAnnotation(ResponseBody.class));
}
public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
    return true;   // 始终返回 true——@ResponseBody 方法的所有返回值都走 HttpBodyCodecRegistry
}
public void resolveReturnValue(Object returnValue, MethodParameter returnType, ...) throws Exception {
    httpBodyCodecRegistry.writeBody(returnValue, returnType, req, resp);
}
```

`supportsReturnType` 检查类级别或方法级别的 `@ResponseBody` 注解。`supportsReturnValue` 始终返回 true——因为 `@ResponseBody` 的语义是"所有返回值都写到响应体"，不需要按值做二次判断。

`getOrder()` 返回 `Integer.MAX_VALUE - 100`，确保在 `initRealComponentList` 排序时排在后位——这样 `HttpEntityReturnValueResolver` 等更具体的 Resolver 在线性扫描中优先匹配。

### 4.2 `HttpEntityReturnValueResolver`：`ResponseEntity`

```java
// HttpEntityReturnValueResolver.java
public void resolveReturnValue(Object returnValue, MethodParameter returnType, ...) throws Exception {
    HttpEntity httpEntity = (HttpEntity) returnValue;
    if (httpEntity instanceof ResponseEntity) {
        ResponseEntity<?> responseEntity = (ResponseEntity<?>) httpEntity;
        if (responseEntity.getStatusCode() != null) {
            resp.setStatusCode(responseEntity.getStatusCode());  // ① 状态码
        }
    }
    HttpHeaders entityHeaders = httpEntity.getHeaders();
    if (entityHeaders != null && !entityHeaders.isEmpty()) {
        resp.getHeaders().putAll(entityHeaders);                  // ② headers
    }
    Object body = httpEntity.getBody();
    if (body != null) {
        httpBodyCodecRegistry.writeBody(body, returnType, req, resp);  // ③ body
    }
}
```

三步写出：状态码 → headers → body。注释标注了性能细节——**"空 headers 时跳过 putAll，避免空 map 的无谓遍历"**。`HttpEntity.getHeaders()` 即使没有设置 headers 也返回一个空 `HttpHeaders` 实例，`putAll` 空 map 的遍历开销虽小，但每请求累积不可忽略。

### 4.3 `ResourceReturnValueResolver`：文件下载

```java
// ResourceReturnValueResolver.java
public void resolveReturnValue(Object returnValue, MethodParameter returnType, ...) throws Exception {
    Resource resource = (Resource) returnValue;
    if (resource.isFile()) {
        resp.writeFile(resource.getFile());     // 走 DefaultFileRegion sendfile 零拷贝
    } else {
        resp.writeStream(resource.getInputStream());  // 走 ChunkedStream 流式
    }
}
```

`ResourceReturnValueResolver` 根据 `Resource` 的实现类型选择写出路径：`FileUrlResource`/`ClassPathResource`（文件）走 `writeFile` 零拷贝，`InputStreamResource`（流）走 `writeStream` chunked 传输。这个分流在 `ResourceReturnValueResolver` 层面完成，不需要业务方关心底层传输机制。

### 4.4 `ByteArrayReturnValueResolver`：字节数组

```java
// ByteArrayReturnValueResolver.java
public void resolveReturnValue(Object returnValue, MethodParameter returnType, ...) throws Exception {
    byte[] bytes = (byte[]) returnValue;
    resp.writeBytes(bytes);    // 走 Unpooled.wrappedBuffer + DefaultFullHttpResponse
}
```

最简 Resolver。`byte[]` 直接写 `writeBytes`，走 [05 篇](05-server-and-http.md) §6.4 的 `Unpooled.wrappedBuffer` 单次写出路径。

### 4.5 `FileReturnValueResolver`：File / Path

`FileReturnValueResolver` 支持 `File` 和 `Path` 两种类型，`Path` 先 `toFile()` 再走 `writeFile`。与 `ResourceReturnValueResolver` 的区别在于：`ResourceReturnValueResolver` 处理 Spring 的 `Resource` 抽象，`FileReturnValueResolver` 处理 Java 标准 `File`/`Path`。两者最终都走 `resp.writeFile`——`DefaultFileRegion` sendfile 零拷贝。

### 4.6 `WrapMessageConverterReturnValueResolver`：SPI 兜底

```java
// WrapMessageConverterReturnValueResolver.java
public class WrapMessageConverterReturnValueResolver implements ReturnValueResolver {
    private final HttpMessageConverter messageConverter;

    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return messageConverter.canWrite(returnType.getParameterType(), null);
    }
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        return messageConverter.canWrite(returnValue.getClass(), null);
    }
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, ...) throws Exception {
        messageConverter.write(returnValue, null, resp);
    }
}
```

这是 `HttpBodyCodecRegistry` 在注册 Spring `HttpMessageConverter` 时创建的适配器——每个 `HttpMessageConverter` 被包装为一个 `WrapMessageConverterReturnValueResolver`，加入 `ReturnValueResolverRegistry` 的 Resolver 列表。当框架内置的 13 个 Resolver 都不匹配时，由 Spring 生态的 `HttpMessageConverter` 兜底。

---

## 五、`Optional` 解包处理

`doResolveReturnValue` 对 `Optional` 返回值有专门处理：

```java
if (returnValue instanceof Optional<?> opt) {
    if (opt.isEmpty()) {
        return true;  // Optional.empty() → 无响应体，空 200
    }
    returnValue = opt.get();
    if (returnValueContext != null && returnValueContext.isOptionalType()) {
        returnType = returnValueContext.getOptionalInnerReturnType();
        ReturnValueResolver innerResolver = returnValueContext.getOptionalInnerReturnValueResolver();
        if (innerResolver != null && innerResolver.supportsReturnValue(returnValue, req, resp)) {
            innerResolver.resolveReturnValue(returnValue, returnType, req, resp);
            return true;
        }
    }
    // 运行时 Optional（方法签名未声明 Optional）：returnType 保持原样，走正常流程
}
```

两种场景：

- **声明式 Optional**：方法签名 `Optional<User> getUser()`，`MethodReturnValueContext` 在 `getMethodReturnValueContext`中检测到 `Optional` 类型，设置 `optionalType = true`，提取 `OptionalInnerReturnType`（`User`）。运行时 `Optional` 解包后，命中 Optional fast path，用内联类型找解析器。
- **运行时 Optional**：方法签名 `Object getUser()` 但返回 `Optional.of(user)`，`optionalType` 为 false，解包后走正常流程——`returnType` 保持原样（`Object`），线性扫描兜底。

---

## 六、`resolveReturnValue` 入口

`resolveReturnValue`是 `DispatcherHandler` 调用的入口：

```java
// ReturnValueResolverRegistry.java
public void resolveReturnValue(Object returnValue, MappingHandlerMethod mappingContext, 
                                WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
    if (skipResolve(returnValue, mappingContext, req, resp)) {
        return;
    }
    if (doResolveReturnValue(returnValue, mappingContext, req, resp)) {
        resp.setHandled();  // 解析成功 → 标记已处理
    }
}
```

`doResolveReturnValue` 返回 `true` 表示"找到了匹配的 Resolver 并成功写出"——此时 `setHandled()` 标记响应已处理。如果返回 `false`（无 Resolver 匹配），`resp.setHandled()` 不会被调用，`DispatcherHandler` 的后续处理会感知到"返回值未处理"并触发异常处理流程。

---

## 七、对比 Spring MVC 返回值解析

| 维度 | 本框架 | Spring MVC |
|------|--------|-----------|
| 运行时匹配 | 三级缓存：fast path → async inner → 线性扫描 | `HandlerMethodReturnValueHandlerComposite` 遍历 `supportsReturnType` |
| 缓存粒度 | 每方法 `MethodReturnValueContext`，含主解析器 + 异步内联 + Optional 内联 | 全局 `HandlerMethodReturnValueHandler` 列表，无方法级缓存 |
| 异步内联 | `resolveInnerReturnValueContext` 提取泛型并缓存内联解析器 | 无等效机制，异步 dispatch 回来后重新遍历 |
| Optional 解包 | `Optional<T>` 声明式 → 内联类型缓存；运行时 → 正常流程 | `Optional` 由 `ModelAttributeMethodProcessor` 处理 |
| 兜底策略 | `WrapMessageConverterReturnValueResolver` 适配 Spring `HttpMessageConverter` | `RequestResponseBodyMethodProcessor` 等显式注册 |
| 空值处理 | `skipResolve`：null 对应 void 方法标记 handled | `void` 方法返回 `ModelAndView`（null 时走默认 view） |
| 启动期校验 | 无显式 check-on-startup（返回值类型运行时才能确定） | 无 |

**核心差异**：Spring MVC 的返回值解析每次请求都遍历 `returnValueHandlers` 列表，`supportsReturnType` 逐个检查。本框架把"查找"的代价从每次请求降到首次请求（线性扫描后缓存），后续请求走 Fast path 1 两次虚方法调用（`supportsReturnValue` + `resolveReturnValue`）。

---

## 八、小结：返回值解析的克制在哪里

回到引子的问题：业务方法的返回值怎么变成 HTTP 响应？

1. **14 个内置 Resolver 按优先级注册** → 异步 > 流式 > 字节流 > 资源 > 实体 > JSON > 兜底。
2. **`MethodReturnValueContext` 按方法缓存** → 首次请求线性扫描命中后，主解析器或异步内联解析器被缓存。
3. **三级缓存策略** → Fast path 1（主解析器，>95% 请求）、Fast path 2（异步内联，<5% 请求）、线性扫描（首次请求或缓存不匹配）。
4. **`Optional` 解包** → 声明式 `Optional<T>` 优先缓存内联解析器，运行时 `Optional` 走正常流程。
5. **`skipResolve` 前置检查** → void 返回 null 或已被提前处理的响应，不进入解析链。
6. **`WrapMessageConverterReturnValueResolver` 兜底** → 用户注册的 Spring `HttpMessageConverter` 通过适配器加入解析链。

这一层的克制体现在：**不把"遍历查找"留给运行时，也不为"缓存"提前做复杂启动期计算。** 返回值类型不像参数类型那样在启动期可穷举（返回值具体实例的运行时类型可能与签名不同），因此本框架选择了"首次请求缓存 + 后续直取"的组合策略——既避免了每次请求都遍历，又保留了运行时多态的灵活性。

---

> **下一篇**：[09 · 调用器与字节码优化](09-invoker-bytecode.md)——参数解析完、返回值解析前，业务方法怎么被调用？反射？MethodHandle？还是 ASM 生成的字节码？