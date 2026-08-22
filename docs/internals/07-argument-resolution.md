# 07 · 参数解析体系

> [← 返回索引](00-README.md) | 上一篇：[06 · 路由引擎与多级 RouterOptimizer 链](06-routing-engine.md) | 下一篇：[08 · 返回值解析](08-returnvalue-resolution.md)

---

## 引子：参数解析不是"遍历"，是"查表"

路由引擎把请求路径映射到业务方法之后（[06 篇](06-routing-engine.md)），`DispatcherHandler` 需要调用业务方法——但参数从哪来？`@PathVariable` 从路径变量取，`@RequestParam` 从查询参数取，`@RequestBody` 从请求体读 JSON，`@ModelAttribute` 从表单字段绑定到 POJO。

Spring MVC 的做法是：运行时遍历 `HandlerMethodArgumentResolverComposite` 的 `argumentResolvers` 列表，逐个调用 `supportsParameter` 直到命中，再调用 `resolveArgument`。**每次请求都走一遍"查找"**，即使同一方法同一参数。

本框架的做法是：**启动期 Phase3 一次性决定"哪个 Provider 为哪个参数创建哪个 Resolver"，把结果写进 `MappingCacheKey` 索引的 `MethodArgContext[]` 数组。运行时 `resolveArguments` 是 `array[i].defaultArgumentResolver.resolveArgument(request, response)`——一次数组访问 + 一次虚方法调用，无遍历、无匹配、无类型推断。**

这个"启动期决定、运行时查表"的设计，是 [01 篇](01-design-philosophy.md) 原则 1（零匹配）在参数解析维度的落地。

---

## 一、整体架构：Provider 与 Resolver 分离

### 1.1 分层设计

参数解析体系分为两层：

| 层 | 职责 | 执行时机 | 产物 |
|----|------|---------|------|
| `StaticArgumentResolverProvider` | 判断"这个参数归谁管" + 创建 Resolver 实例 | 启动期 Phase3 | `StaticArgumentResolver` 实例 |
| `StaticArgumentResolver` | 从请求/响应中提取具体值 | 运行时每请求 | 参数值 `Object` |

```java
// StaticArgumentResolverProvider.java  SPI 接口
public interface StaticArgumentResolverProvider extends WebComponent {
    boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext);
    StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext);
}

// StaticArgumentResolver.java  运行时执行接口
public interface StaticArgumentResolver {
    Object resolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception;
}
```

**为什么分离？** 因为"判断"和"执行"是两件不同的事。`supports` 需要方法参数元数据（注解、类型、泛型），运行时可复用这个判断结果——把判断前移到启动期，运行时只执行。分离后，`Provider` 只在启动期被调用，`Resolver` 实例被缓存到 `MappingCacheKey` 中，运行时零额外开销。

### 1.2 12 个内置 Provider

`ArgumentResolverRegistry.initStaticArgumentResolverProviders()`按固定顺序注册 12 个内置 Provider：

```java
// ArgumentResolverRegistry.java
protected void initStaticArgumentResolverProviders() {
    registerWebComponent(new MultipartFileResolverProvider());
    registerWebComponent(new RequestBodyResolverProvider());
    registerWebComponent(new RequestHeaderResolverProvider());
    registerWebComponent(new RequestParamResolverProvider());
    registerWebComponent(new RequestPartResolverProvider());
    registerWebComponent(new PathVariableResolverProvider());
    registerWebComponent(new ModelAttributeResolverProvider());
    registerWebComponent(new HttpEntityResolverProvider());
    registerWebComponent(new ErrorsResolverProvider());
    registerWebComponent(new RequestResolverProvider());
    registerWebComponent(new ResponseResolverProvider());
    registerWebComponent(new LocaleResolverProvider());
    registerWebComponent(StaticArgumentResolverProvider.class);
    initRealComponentList(staticArgumentResolverProviders, StaticArgumentResolverProvider.class);
}
```

**注册顺序 = 匹配优先级**。`initStaticArgResolverSupport`遍历 `staticArgumentResolverProviders` 列表，第一个 `supports` 返回 true 的 Provider 胜出。因此 `RequestBodyResolverProvider` 排在 `RequestParamResolverProvider` 之前——如果参数同时标注了 `@RequestBody` 和 `@RequestParam`（合理场景），`@RequestBody` 优先。

`AbstractSupportTypeResolverProvider`（`getOrder() = -10000`，:30-32）通过设置 **`@Order(-10000)`** 确保自己在 `initRealComponentList` 排序时排在前面，承担兜底类型匹配（如 `ModelAttribute` 无注解时的默认行为）。

---

## 二、Provider → Resolver 的决策链路

### 2.1 `initStaticArgResolverSupport`：遍历 Provider 链

`ArgumentResolverRegistry.initStaticArgResolverSupport`是参数解析的"决策点"：

```java
// ArgumentResolverRegistry.java
protected void initStaticArgResolverSupport(MappingHandlerMethod methodMappingContext, MethodArgContext methodArgContext) {
    MethodParameter parameter = methodArgContext.getMethodParameter();
    for (StaticArgumentResolverProvider provider : staticArgumentResolverProviders) {
        if (provider.supports(parameter, methodMappingContext)) {
            methodArgContext.defaultArgumentResolver = provider.getResolver(parameter, methodMappingContext, webContext);
            methodArgContext.isStaticArgResolved = true;
            break;
        }
    }
    if (methodArgContext.isStaticArgResolved) {
        return;
    }
    // 兜底：简单类型 → @RequestParam 语义；复杂类型 → @ModelAttribute 语义
    if (BeanUtils.isSimpleProperty(parameter.getNestedParameterType())) {
        methodArgContext.defaultArgumentResolver = requestParamResolverProvider.getResolver(parameter, methodMappingContext, webContext);
    } else {
        methodArgContext.defaultArgumentResolver = modelAttributeResolverProvider.getResolver(parameter, methodMappingContext, webContext);
    }
    methodArgContext.isStaticArgResolved = false;
}
```

决策逻辑分两段：

1. **显式匹配**：遍历所有 Provider，`supports` 命中即获取 Resolver，标记 `isStaticArgResolved = true`。
2. **兜底匹配**：无 Provider 显式支持时，简单类型走 `@RequestParam` 语义（`requestParamResolverProvider`），复杂类型走 `@ModelAttribute` 语义（`modelAttributeResolverProvider`）。**`isStaticArgResolved = false`** 标记兜底场景，保留方法签名变更时重新解析的弹性。

### 2.2 Phase3 缓存：`MethodArgContext[]` 数组

`getMethodArgContexts`是运行时的入口：

```java
// ArgumentResolverRegistry.java
protected MethodArgContext[] getMethodArgContexts(MappingHandlerMethod mappingContext) {
    MethodArgContext[] methodArgContexts = mappingContext.get(MAPPING_CACHE_KEY);
    if (methodArgContexts == null) {
        MethodParameter[] methodParameters = mappingContext.createMethodParameters();
        methodArgContexts = Arrays.stream(methodParameters).map(MethodArgContext::new).toArray(MethodArgContext[]::new);
        for (MethodArgContext methodArgContext : methodArgContexts) {
            initStaticArgResolverSupport(mappingContext, methodArgContext);
        }
        mappingContext.set(MAPPING_CACHE_KEY, methodArgContexts);
    }
    return methodArgContexts;
}
```

`MAPPING_CACHE_KEY` 是 `MappingCacheKey.createMethodCacheKey(MethodArgContext[].class)`——方法级缓存，每个业务方法一个 `MethodArgContext[]` 数组。数组长度 = 方法参数个数，每个元素预存了该参数的 Resolver 实例。**运行时是 `array[i].defaultArgumentResolver.resolveArgument()`——零查找、零匹配、零类型推断。**

注意：缓存填充发生在**首次请求**的 `getMethodArgContexts` 调用中，而非 Phase3。Phase3 的 `validateAllParametersResolvable` 只验证"每个参数都有 Provider 可匹配"（调用 `supports`），不创建 Resolver 实例。这样做的收益是——永远不会被调用的端点不分配任何 Resolver 内存。验证与缓存分离，兼顾 fail-fast 与内存效率。

---

## 三、`AbstractSupportResolverProvider` 四路分流

`AbstractSupportResolverProvider` 是 `@PathVariable`、`@RequestParam`、`@RequestHeader` 等**命名值解析**的公共基类。`getResolver`根据参数类型分四路：

```java
// AbstractSupportResolverProvider.java
public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
    if (isMultiValueMap(parameter, mappingContext, webContext)) {
        return getMultiValueMapArgumentResolver(...);
    } else if (isSimpleMap(parameter, mappingContext, webContext)) {
        return getSimpleMapArgumentResolver(...);
    } else if (isCollection(parameter, mappingContext, webContext)) {
        return getCollectionArgumentResolver(...);
    } else {
        return getSingleValueArgumentResolver(...);
    }
}
```

| 分支 | 判断条件 | 返回类型 | 运行时行为 |
|------|---------|---------|-----------|
| MultiValueMap | `MultiValueMap.isAssignableFrom(paramType)` | `MultiValueMap<String, T>` | 整体返回 `MultiValueMap`（如 `@RequestParam MultiValueMap<String, String>`） |
| SimpleMap | `Map.isAssignableFrom(paramType)` | `Map<String, T>` | `multiValueMap.toSingleValueMap()` |
| Collection | `Collection || paramType.isArray()` | `List<T>` | `multiValueMap.get(name)` |
| SingleValue | 兜底 | `T`（单个值） | `multiValueMap.getFirst(name)` |

每个子类只需实现 `getMultiValueMapResolver()` 返回一个 `MultiValueMapResolver` lambda，声明数据源。例如：

```java
// RequestParamResolverProvider.java
protected MultiValueMapResolver getMultiValueMapResolver() {
    return ((parameter, mappingContext, request, response) -> request.getParameterMap());
}

// RequestHeaderResolverProvider.java
protected MultiValueMapResolver getMultiValueMapResolver() {
    return ((parameter, mappingContext, request, response) -> request.getHeaders());
}
```

`PathVariableResolverProvider` 重写四路选择逻辑——`getMultiValueMapResolver` 返回 `null`，`isMultiValueMap` 和 `isCollection` 均返回 `false`，`getSimpleMapArgumentResolver` 返回 `PathPatternRouter.getUriVariableMap(request)`（`Map<String, String>`），`getSingleValueArgumentResolver` 返回 `getUriVariableMap(request).get(name)`。

---

## 四、`AbstractNamedValueResolver` 类型转换与空值处理

`AbstractNamedValueResolver` 继承 `AbstractSupportOptionalResolver`，在 `doResolveArgument` 中串联三步骤：

```java
// AbstractNamedValueResolver.java
protected Object doResolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
    Object arg = resolveByName(request, response);  // ① 从数据源取值
    if (arg == null) {
        arg = handleNullValue(name, paramType);      // ② null 处理（基本类型抛异常，包装类返 null）
    } else if (isEmpty(arg)) {
        arg = handleEmptyValue(arg, name, paramType);// ③ 空字符串处理
    }
    return convert(arg);                              // ④ 类型转换
}
```

### 4.1 类型转换：`convertWithGenericType`

`convert` 方法在类型不匹配时调用 `convertWithGenericType`：

```java
// AbstractNamedValueResolver.java
protected Object convertWithGenericType(Object arg) {
    TypeDescriptor targetType = new TypeDescriptor(parameter.nestedIfOptional());
    return webDataBinderRegistry.getConversionService(mappingContext).convert(arg, targetType);
}
```

`TypeDescriptor` 携带完整的泛型信息（如 `List<Integer>`），`ConversionService` 做元素级转换。注释记录了修复历史：**原本只 catch `ConversionFailedException`，漏掉 `ConverterNotFoundException`（目标类型无 String→T 转换器），导致该错误逸出为 500。修复后 catch 父类 `ConversionException`，统一收敛为 400。**

### 4.2 `isContainer` 保护

`isContainer` 方法检测转换后的值是否为 `Collection/Map/数组`——如果是容器类型，即使运行时类型与 `paramType` 兼容（如 `List → List`），泛型元素类型也可能不匹配（如 `List<String> → List<Integer>`）。`convertWithGenericType` 必须携带完整泛型信息做元素级转换，不能短路返回。

---

## 五、`RequestBodyResolver`：请求体读取

`RequestBodyResolver` 是唯一直接操作 `HttpBodyCodecRegistry` 的解析器：

```java
// RequestBodyResolver.java
protected Object doResolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
    Object body;
    try {
        body = httpBodyCodecRegistry.readBody(targetType, parameter, request, request);
    } catch (Exception e) {
        if (required) {
            throw new HttpMessageNotReadableException("Failed to parse @RequestBody data", e, request);
        } else {
            return null;
        }
    }
    if (body == null && required) {
        throw new HttpMessageNotReadableException("Required @RequestBody is missing", request);
    }
    return body;
}
```

关键设计：

- **`required` 在构造时决定**：`isOptional ? false : required`——如果参数是 `Optional<T>`，`required` 自动降为 `false`，不强制 body 存在。
- **`readBody` 返回 null 意味着无匹配 converter 或空 body**。必需 `@RequestBody` 缺失时抛 `HttpMessageNotReadableException`，对齐 Spring 语义返回 400。
- **`targetType` 是 `parameter.getGenericParameterType()`**，携带完整泛型信息供 `HttpBodyCodecRegistry` 做内容协商和反序列化。

---

## 六、`ModelAttributeResolver`：数据绑定

`ModelAttributeResolver` 是参数解析中最复杂的场景——它需要创建目标对象、绑定请求参数、执行校验：

```java
// ModelAttributeResolver.java
protected Object doResolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
    NativeWebRequest webRequest = AsyncSupportUtils.getAsyncWebRequest(request, response);
    Object attribute = constructAttribute();            // ① 创建目标对象实例
    WebDataBinder binder = binderFactory.createBinder(webRequest, attribute, name);  // ② 创建 DataBinder
    if (binder.getTarget() != null && binding) {
        PerfDataBinder.bind(request, binder);            // ③ 绑定请求参数
    }
    return attribute;
}
```

### 6.1 构造器选择

`initialConstructor`选择构造器的逻辑：

1. `BeanUtils.findPrimaryConstructor`（Spring 5.3+ 的构造器推断，优先 Kotlin 主构造器）
2. 如果只有一个构造器，使用它
3. 否则尝试 `getDeclaredConstructor()`（无参构造器）
4. 如果无参构造器不存在且参数数 > 0，抛 `IllegalStateException`——**D3 fail-fast**，在 Phase3 校验中暴露

### 6.2 `PerfDataBinder.bind`

`PerfDataBinder.bind` 从请求的 `ParameterMap` 中提取命名值，通过 `WebDataBinder` 的 `bind` 方法注入到目标对象的属性中。`binding` 字段来自 `@ModelAttribute(binding = true/false)` 的 `binding` 属性——设置为 `false` 时跳过绑定，只创建空对象。

---

## 七、`MethodArgContext` 每方法参数元数据

`MethodArgContext`是每个方法参数的元数据容器，在 Phase3 初始化时填充，运行时只读：

```java
// MethodArgContext.java
public class MethodArgContext {
    protected MethodParameter methodParameter;
    protected boolean isStaticArgResolved;          // 是否由 Provider 显式解析
    protected StaticArgumentResolver defaultArgumentResolver;  // 运行时 Resolver
    protected String paramName;
    protected boolean haveValidateAnnotation;        // 是否有 @Valid/@Validated
    protected Object[] validationHints;              // 校验分组（@Validated 的 value）
    protected boolean hasBindingResult;              // 下一个参数是 BindingResult
    protected String bindingResultAttrKey;           // BindingResult 的请求属性键
    protected Validator validator;                   // 校验器（懒加载）
}
```

### 7.1 校验注解检测

`initValidateAnnotationInfo`扫描参数注解，检测 `@Valid` 或 `@Validated`：

- `@Validated` 通过 `AnnotationUtils.getAnnotation` 递归查找——因为 `@Validated` 可能被其他注解组合。
- `@Valid` 通过 `annotationType().getSimpleName().startsWith("Valid")` 检测——兼容 `javax.validation.Valid` 和 `jakarta.validation.Valid`。
- `validationHints` 提取 `@Validated` 的 `value`（分组），供 `SmartValidator.validate` 使用。

### 7.2 BindingResult 检测

`isNextParamHasBindingResult`检查下一个参数是否为 `Errors/BindingResult` 类型：

```java
// MethodArgContext.java
private boolean isNextParamHasBindingResult(MethodParameter parameter) {
    int i = parameter.getParameterIndex();
    Class<?>[] paramTypes = parameter.getExecutable().getParameterTypes();
    return (paramTypes.length > (i + 1) && Errors.class.isAssignableFrom(paramTypes[i + 1]));
}
```

如果下一个参数是 `BindingResult`，`validator.validate` 的校验错误不会抛 `MethodArgumentNotValidException`，而是写入 `BindingResult` 对象，由业务方法自行处理。`bindingResultAttrKey` 在 `BindingResult.MODEL_KEY_PREFIX + nextIndex` 上 `intern()`，确保跨请求的字符串引用可 == 比较。

---

## 八、校验：`@Valid` / `@Validated`

`validateIfApplicable`是校验的入口：

```java
// ArgumentResolverRegistry.java
protected void validateIfApplicable(Object target, MethodArgContext methodArgContext, ...) throws MethodArgumentNotValidException {
    BindingResult bindingResult = null;
    if (methodArgContext.isHasBindingResult()) {
        bindingResult = createBindingResult(target, methodArgContext, mappingContext);
        request.getRequestContext().setAttribute(methodArgContext.getBindingResultAttrKey(), bindingResult);
    }

    if (!methodArgContext.isHaveValidateAnnotation() || target == null) {
        return;
    }
    Validator validator = methodArgContext.validator;
    if (validator == null) {
        validator = getValidator(target, mappingContext);
        methodArgContext.validator = validator;
    }
    if (validator == null) return;

    if (bindingResult == null) {
        bindingResult = createBindingResult(target, methodArgContext, mappingContext);
    }
    // ... 执行校验
    if (bindingResult.hasErrors() && !methodArgContext.isHasBindingResult()) {
        throw new MethodArgumentNotValidException(methodArgContext.getMethodParameter(), bindingResult);
    }
}
```

校验流程：

1. **`BindingResult` 预创建**：如果参数有 `BindingResult` 跟随，先创建 `BindingResult` 并写入请求属性。
2. **`Validator` 懒加载**：`validator` 字段在 `MethodArgContext` 中缓存，首次校验时通过 `getValidator`从 `WebDataBinderRegistry` 获取——遍历 `validators` 列表，找到第一个 `supports(target.getClass())` 的 Validator。
3. **`SmartValidator` 分组校验**：如果 `validationHints` 非空且 `validator instanceof SmartValidator`，调用 `validate(target, bindingResult, validationHints)` 按分组校验。
4. **错误抛出**：如果 `BindingResult` 有错误且参数本身没有 `BindingResult` 跟随，抛 `MethodArgumentNotValidException`，由 `ExceptionRegistry` 映射为 400。

---

## 九、Phase3 校验：`check-on-startup`

`ArgumentResolverRegistry.initComponentPhase3`在 Phase3 触发 `validateAllParametersResolvable`：

```java
// ArgumentResolverRegistry.java
public void initComponentPhase3() throws Exception {
    super.initComponentPhase3();
    if (webContext.getProps().getBoolean(PropertiesConstant.CHECK_ON_STARTUP, true)) {
        validateAllParametersResolvable();
    }
}
```

`validateAllParametersResolvable`遍历所有 `PathMappingContext` 的每个方法参数，检查 `isParameterResolvable`：

```java
// ArgumentResolverRegistry.java
protected boolean isParameterResolvable(MethodParameter parameter, MappingHandlerMethod mappingContext) {
    for (StaticArgumentResolverProvider provider : staticArgumentResolverProviders) {
        if (provider.supports(parameter, mappingContext)) return true;
    }
    // Fallback
    if (BeanUtils.isSimpleProperty(parameter.getNestedParameterType())) {
        return requestParamResolverProvider != null;
    }
    return modelAttributeResolverProvider != null;
}
```

如果任何参数无可匹配的 Provider 且无兜底，启动失败，抛 `IllegalStateException` 列出所有不可解析的参数（包括类名、方法名、参数名和类型）。**宁可启动失败，不要运行时 500**——这是 [01 篇](01-design-philosophy.md) 原则 6（显式 fail-fast）在参数解析维度的落地。

`validateModelAttributeConstructor`额外检查 `@ModelAttribute` 参数是否有可用的构造器——无默认构造器的 POJO 在首个请求才 500（resolver 懒创建），启动期预创建 resolver 把配置错误暴露为启动失败。

---

## 十、运行时 `resolveArguments` 完整流程

```java
// ArgumentResolverRegistry.java
public Object[] resolveArguments(MappingHandlerMethod mappingContext, WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
    MethodArgContext[] methodArgContexts = getMethodArgContexts(mappingContext);  // ① 从缓存取
    Object[] args = new Object[methodArgContexts.length];
    for (int i = 0; i < methodArgContexts.length; i++) {
        MethodArgContext methodArgContext = methodArgContexts[i];
        if (methodArgContext.defaultArgumentResolver != null) {
            args[i] = methodArgContext.defaultArgumentResolver.resolveArgument(request, response);  // ② 直调
            validateIfApplicable(args[i], methodArgContext, request, mappingContext);  // ③ 校验
        } else {
            args[i] = null;
        }
    }
    return args;
}
```

运行时的性能特征：

- **`getMethodArgContexts`**：`mappingContext.get(MAPPING_CACHE_KEY)` 是 `Object[]` 的 `aaload`（`MappingCacheKey.index` 数组索引），一次内存访问。首次请求时懒初始化（`initStaticArgResolverSupport` 遍历 Provider 链，创建 Resolver 实例并缓存），后续请求 O(1)。
- **`resolveArgument`**：每个参数一次虚方法调用（`StaticArgumentResolver.resolveArgument`），无类型推断、无注解扫描、无 `supports` 遍历。
- **`validateIfApplicable`**：只对标注 `@Valid/@Validated` 的参数执行，无注解时 `!haveValidateAnnotation` 直接返回。

---

## 十一、对比 Spring MVC 参数解析

| 维度 | 本框架 | Spring MVC |
|------|--------|-----------|
| 运行时匹配 | 启动期决定的 `MethodArgContext[].defaultArgumentResolver` 数组直取 | `HandlerMethodArgumentResolverComposite` 遍历 `supportsParameter` + `synchronized` 缓存 |
| 缓存粒度 | 每方法每参数一个 `StaticArgumentResolver` 实例 | 全局 `HandlerMethodArgumentResolver` 列表（被所有方法共享） |
| 类型转换 | `ConversionService.convert` 带 `TypeDescriptor` 泛型 | `WebDataBinder.convertIfNecessary`（同 `ConversionService`） |
| 校验集成 | `validateIfApplicable` 内联在 `resolveArguments` 中 | `InvocableHandlerMethod` 调用后由 `validateIfApplicable` 处理 |
| Phase3 校验 | `check-on-startup` + `validateModelAttributeConstructor` 双重 fail-fast | 无启动期校验，不可解析参数在首个请求 500 |
| 空值处理 | `handleNullValue` + `handleEmptyValue` 显式 | `MethodParameter.isOptional` + `@Nullable` 隐式 |
| 类型匹配 | Provider `supports` 按注解 + 类型 | `HandlerMethodArgumentResolver.supportsParameter` 按注解 + 类型 |
| 兜底策略 | 简单类型 → `@RequestParam`，复杂类型 → `@ModelAttribute` | `ModelAttributeMethodProcessor` 兜底 |

**核心差异**：Spring MVC 的运行时 `supportsParameter` 遍历是"每次请求、每个参数、遍历列表、直到命中"——即使缓存了 `HandlerMethodArgumentResolver` 实例，遍历本身（`for` 循环 + `instanceof` 检查）仍发生在每次请求上。本框架把遍历从运行时的每次请求移到启动期的一次性扫描，运行时 `array[i]` 直取。

---

## 十二、小结：参数解析的克制在哪里

回到引子的问题：业务方法的参数值从哪来？

1. **Provider 启动期决策** → 12 个内置 Provider 按优先级遍历，`supports` 命中即创建 Resolver 实例。
2. **`MethodArgContext[]` 数组缓存** → `MappingCacheKey` 方法级缓存，运行时 `array[i]` 直取。
3. **`AbstractSupportResolverProvider` 四路分流** → 一个基类覆盖四种参数类型（MultiValueMap/Map/Collection/SingleValue），子类只需声明数据源。
4. **`AbstractNamedValueResolver` 类型转换 + 空值处理** → 串联 resolveByName → handleNull/Empty → convert，统一处理命名值参数。
5. **`validateIfApplicable` 校验集成** → `@Valid/@Validated` 自动触发，`Validator` 懒加载，`BindingResult` 跟随参数自动识别。
6. **Phase3 fail-fast** → `check-on-startup` 确保无参数不可解析，`validateModelAttributeConstructor` 预防无构造器 POJO 的运行时 500。

这一层的克制体现在：**不把"遍历查找"留给运行时。** 每个参数该用哪个 Provider、该走哪个 Resolver，在 Phase3 一次性决定并缓存。运行时 `resolveArguments` 的代码量——遍历 `MethodArgContext[]` 数组、直调 `resolveArgument`、按需校验——全是"执行"没有"查找"。这个"查找前移"在 benchmark 中把参数解析的开销从 O(n) 降为 O(1)，是 [01 篇](01-design-philosophy.md) 原则 1 最彻底的落地之一。

---

> **下一篇**：[08 · 返回值解析](08-returnvalue-resolution.md)——业务方法执行完后，返回值如何写入响应。