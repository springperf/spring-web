# 06 · 路由引擎与多级 RouterOptimizer 链

> [← 返回索引](00-README.md) | 上一篇：[05 · Netty 服务器与 HTTP 请求/响应适配](05-server-and-http.md) | 下一篇：[07 · 参数解析体系](07-argument-resolution.md)

---

## 引子：路由不是"遍历"，是"分流"

[04 篇](04-request-pipeline.md) 的请求处理管线里，`DispatcherHandler.handleWithMappingResult` 是拿到 `MappingResult` 之后的事。本篇回答一个问题：**`MappingResult` 是怎么来的？**

Spring MVC 的路由是"遍历所有 HandlerMapping→遍历所有注册路径→逐条匹配直到命中"。本框架把这条路整个翻转过来：**启动期一次性分流，运行时 O(1) HashMap.get 或极有限遍历**。

核心思路是三条：

1. **Phase3 预处理** — 把通配路径和纯路径拆成三桶，每桶走不同的优化策略。
2. **多级 RouterOptimizer 链** — 每个优化器尝试用自己的 Router 命中请求，命中即返回，不继续。
3. **Matcher 条件匹配后置** — 路径先命中，再逐条匹配 HTTP method/params/headers/consumes/produces。

这套"分流而非遍历"的设计，是 [01 篇](01-design-philosophy.md) 原则 1（零匹配）与原则 6（显式 SPI，`RouterOptimizer` 即 SPI）在路由维度的落地。

---

## 一、MappingRegistry 扫描与 Matcher 提取

### 1.1 Phase1 扫描：`@Controller` → `@RequestMapping`

`MappingRegistry.initComponentPhase1()` 是路由的起点。它扫描 `ApplicationContext` 中所有标注 `@Controller` 的 Bean，解析每个方法的 `@RequestMapping`：

```java
// MappingRegistry.java  Phase1 扫描
public void initComponentPhase1() {
    ApplicationContext ctx = getWebContext().getCtx();
    Map<String, Object> beans = new LinkedHashMap<>(ctx.getBeansWithAnnotation(Controller.class));
    for (Object bean : beans.values()) {
        Class<?> targetClass = ClassUtils.getUserClass(bean.getClass());
        String[] prefix = new String[]{""};
        List<Matcher> classMatchers = emptyList();
        RequestMapping crm = AnnotatedElementUtils.findMergedAnnotation(targetClass, RequestMapping.class);
        if (crm != null) {
            if (crm.value().length > 0) {
                prefix = crm.value();
            } else if (crm.path().length > 0) {
                prefix = crm.path();
            }
            classMatchers = initMatcher(crm);
        }
        prefix = resolvePlaceholders(prefix);
        Method[] methods = ReflectionUtils.getUniqueDeclaredMethods(targetClass);
        for (Method m : methods) {
            RequestMapping requestMapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
            if (requestMapping == null) continue;
            initMethodMappingContext(bean, m, prefix, requestMapping, classMatchers);
        }
    }
}
```

关键设计：

- **`LinkedHashMap` 保序**。`ctx.getBeansWithAnnotation(Controller.class)` 返回的 Map 在有重复路径/方法时，迭代顺序决定选择结果。`LinkedHashMap` 保证先注册的 Bean 胜出。这是此前 [D4 映射顺序修复教训](../memory/d4-mapping-order-fix.md) 的代码体现——试点 `HashMap` 的迭代顺序不确定，导致重复映射在不同启动中表现不一致。
- **`getUniqueDeclaredMethods`**。`ReflectionUtils.getUniqueDeclaredMethods` 收集继承/接口方法并按 override 去重，确保父类声明的 `@RequestMapping` 方法也能被扫描到。
- **`resolvePlaceholders`**。路径中的 `${...}` 占位符在启动期由 `Environment.resolvePlaceholders` 解析，运行时不再碰配置。

### 1.2 Matcher 提取：五类条件

`initMatcher(requestMapping)`从 `@RequestMapping` 提取五类匹配条件，每类对应一个 `Matcher` 实现：

| 条件 | 注解属性 | Matcher 实现 | 运行时行为 |
|------|---------|-------------|-----------|
| HTTP method | `method` | `HttpMethodMatcher` | `Set.contains(req.getMethod())` |
| 请求参数 | `params` | `ParamOrHeaderMatcher(false)` | 逐条检查 name/value/negated |
| 请求头 | `headers` | `ParamOrHeaderMatcher(true)` | 逐条检查 name/value/negated |
| 请求体类型 | `consumes` | `ConsumeOrProduceMatcher(false)` | MediaType 兼容性检查 |
| 响应体类型 | `produces` | `ConsumeOrProduceMatcher(true)` | Accept 头兼容性检查 |

### 1.3 类级别 + 方法级别 Matcher 合并

`mergeMatchers`把类级别 `@RequestMapping` 的条件合并到方法级别。同类型的 Matcher 合并内部约束列表（如类级别 `method=POST` + 方法级别 `method=GET` → `{POST, GET}`），不同类型则直接追加：

```java
// MappingRegistry.java
protected void mergeMatchers(List<Matcher> methodMatchers, List<Matcher> classMatchers) {
    for (Matcher classMatcher : classMatchers) {
        boolean merged = false;
        for (int i = 0; i < methodMatchers.size(); i++) {
            if (methodMatchers.get(i).isSameTypeMatcher(classMatcher)) {
                methodMatchers.set(i, mergeMatcherPair(methodMatchers.get(i), classMatcher));
                merged = true;
                break;
            }
        }
        if (!merged) {
            methodMatchers.add(classMatcher);
        }
    }
}
```

`mergeMatcherPair`对三种可合并的 Matcher 类型分别处理。`HttpMethodMatcher` 合并为 `Set<HttpMethod>` 的并集；`ParamOrHeaderMatcher` 合并 `expressionList`；`ConsumeOrProduceMatcher` 同理。**不可合并的类型（如未来可能新增的 Matcher）走兜底 `return methodMatcher`，不静默失败。**

---

## 二、Matcher 四类匹配器

### 2.1 `HttpMethodMatcher`：Set.contains

```java
// HttpMethodMatcher.java
public boolean match(WebServerHttpRequest req, PathMappingContext mappingContext) {
    return httpMethods.contains(req.getMethod());
}
```

最简匹配器。`httpMethods` 是 `HashSet<HttpMethod>`，`contains` 是 O(1)。`haveAmbiguous` 检查两个 `HttpMethodMatcher` 的集合是否存在包含关系——如果 `A.methods` 包含 `B.methods` 或反之，则歧义。

### 2.2 `ParamOrHeaderMatcher`：逐条 NameValue 检查

`ParamOrHeaderMatcher(false)` 匹配请求参数，`ParamOrHeaderMatcher(true)` 匹配请求头。`match` 方法遍历 `expressionList`，逐条 `checkExpression`：

```java
// ParamOrHeaderMatcher.java
private boolean checkExpression(NameValueExpressionSupport expressionSupport, WebServerHttpRequest req) {
    boolean isMatch;
    if (isHeader) {
        if (expressionSupport.getValue() != null) {
            isMatch = ObjectUtils.nullSafeEquals(expressionSupport.getValue(), req.getHeaders().getFirst(expressionSupport.getName()));
        } else {
            isMatch = req.getHeaders().containsKey(expressionSupport.getName());
        }
    } else {
        // ... 参数侧同理
    }
    return expressionSupport.negated != isMatch;
}
```

`NameValueExpressionSupport` 解析 `@RequestMapping(params = "!admin")` 这种带 `!` 否定前缀的表达式，`negated` 字段标记是否为"不包含"语义。

### 2.3 `ConsumeOrProduceMatcher`：MediaType 兼容性

分两路：`consumes` 匹配 `Content-Type` 头，`produces` 匹配 `Accept` 头。`match` 方法遍历 `mediaTypeRuleList`，调用 `MediaType.includes` / `isCompatibleWith` 判断兼容性。

`produces` 分支的 `getProducibleMediaTypes`提取非否定表达式中的 MediaType 列表，供 `PathMappingContext` 缓存的 `producibleMediaTypes` 字段使用——这个列表在 [08 篇](08-returnvalue-resolution.md) 的内容协商中被 `ReturnValueResolverRegistry` 查阅。

### 2.4 `haveAmbiguous`：歧义检测

每个 Matcher 都实现 `haveAmbiguous`，用于判断两个同类型 Matcher 是否可能同时命中同一请求。`MappingRegistry` 在 Phase3 的 `optimizeMapping` 中虽未显式调用歧义检测，但 `PathMappingContext` 的 `Matcher[]` 数组在 `SimpleRouter.route` 中逐条全匹配（AND 语义），`haveAmbiguous` 为扩展路径——当同一个路径下多个 Matcher 组都全匹配时，框架需要歧义检测来裁决。

---

## 三、Phase3 预处理：一次分流三桶

`MappingRegistry.optimizeMapping()`是 Phase3 的核心预处理步骤。它把 `mappingContextList` 中所有注册的路径，**一次遍历**分入三桶：

```java
// MappingRegistry.java
protected void optimizeMapping(List<PathMappingContext> mappingContextList) {
    List<PathMappingContext> simpleUrlList = new ArrayList<>();
    List<PathMappingContext> simpleWildcardList = new ArrayList<>();
    List<PathMappingContext> fullWildcardList = new ArrayList<>();
    for (PathMappingContext mc : mappingContextList) {
        if (!PathPatternUtils.pathHaveWildcard(mc.getPathRule())) {
            simpleUrlList.add(mc);
        } else if (mc.getPathRule().contains("**")) {
            fullWildcardList.add(mc);
        } else {
            simpleWildcardList.add(mc);
        }
    }

    FullPathRouterOptimizer fullPathRouterOptimizer = new FullPathRouterOptimizer();
    if (fullPathRouterOptimizer.support(simpleUrlList)) {
        fullPathRouterOptimizer.init(simpleUrlList);
        optimizers.add(fullPathRouterOptimizer);
    }
    initOptimizersFor(simpleWildcardList);
    initOptimizersFor(fullWildcardList);
}
```

三桶的分流逻辑：

| 桶 | 条件 | 举例 | 优化器策略 |
|----|------|------|-----------|
| `simpleUrlList` | 无通配符 | `/api/user`, `/health` | `FullPathRouterOptimizer` → `HashMap.get` O(1) |
| `simpleWildcardList` | 含 `*` 但不含 `**` | `/api/*/detail`, `/files/{id}` | `PrefixPathRouterOptimizer` / `SuffixPathRouterOptimizer` / `LoopPathPatternRouterOptimizer` 依次尝试 |
| `fullWildcardList` | 含 `**` | `/files/**`, `/api/**` | 同上（前缀/后缀/循环） |

**为什么一次遍历分三桶？** 注释写得很清楚"替代三次流遍历"——三桶拆分如果分三次遍历 `mappingContextList`，每次 O(n) 总共 O(3n)。一次遍历分三桶是 O(n) + 三次 `ArrayList.add`，后者是 O(1) 分摊。这个小优化是 [01 篇](01-design-philosophy.md) 原则 2（零分配）在路由预处理中的体现——不是"省了几微秒"，而是"不让编译器/CPU 缓存做无用功"。

---

## 四、FullPathRouterOptimizer：HashMap 直取

### 4.1 适用条件

`support()` 继承自 `RouterOptimizer` 接口的默认实现（`list != null && !list.isEmpty()`），只要 `simpleUrlList` 非空就启用。**它是最优先的优化器**——`optimizeMapping` 中先处理 `simpleUrlList`，再处理通配桶。

### 4.2 路由结构

```java
// FullPathRouterOptimizer.java
private final Map<String, Router> routeMap = new HashMap<>();

public boolean initAndRemove(PathMappingContext mappingContext) {
    putSimpleUrl(routeMap, mappingContext);
    return true;
}

public static void putSimpleUrl(Map<String, Router> urlMap, PathMappingContext methodMappingContext) {
    String path = methodMappingContext.getPathRule();
    if (urlMap.containsKey(path)) {
        Router router = urlMap.get(path);
        router.add(methodMappingContext);
    } else {
        urlMap.put(path, new SimpleRouter(methodMappingContext));
    }
}

public Router optimizeRoute(WebServerHttpRequest req) {
    return routeMap.get(req.getPath());   // O(1) HashMap.get
}
```

`routeMap` 是 `Map<String, Router>`，key 是路径字符串（如 `/api/user`），value 是 `SimpleRouter`。**运行时取 router 是 `HashMap.get`——一次哈希计算 + 数组访问，最坏情况 O(1)**。

### 4.3 路径冲突处理

如果两条路径完全相同的映射（如 `GET /api/user` 和 `POST /api/user`），`putSimpleUrl` 不会覆盖——`containsKey` 为 true 时调用 `router.add(methodMappingContext)`，把第二个映射追加到 `SimpleRouter` 的 `PathMappingContext[]` 数组中。`SimpleRouter.route` 遍历数组，逐条匹配 Matcher：

```java
// SimpleRouter.java
public PathMappingContext route(WebServerHttpRequest req) {
    PathMappingContext.setMatchPathMappingContexts(req, methodMappingContexts);
    for (PathMappingContext methodMappingContext : methodMappingContexts) {
        Matcher[] matchers = methodMappingContext.getMatchers();
        if (matchers.length == 0) {
            return methodMappingContext;
        }
        boolean allMatch = true;
        for (Matcher matcher : matchers) {
            if (!matcher.match(req, methodMappingContext)) {
                allMatch = false;
                break;
            }
        }
        if (allMatch) {
            return methodMappingContext;
        }
    }
    return null;
}
```

一个关键设计：**`PathMappingContext.setMatchPathMappingContexts` 在循环开始前就写入请求属性**。这样即使 `SimpleRouter.route` 返回 `null`（路径匹配但条件不匹配），`DispatcherHandler` 仍能通过 `MappingResult.isPathMatched()` 获取路径命中的上下文列表，用于 CORS 预检和 405 判定。

### 4.4 运行时性能

`FullPathRouterOptimizer` 覆盖的路径无通配符，在典型 REST API 中占比 80%+。`routeMap.get(req.getPath())` 一次调用完成路径查找，匹配到的 `SimpleRouter.route` 中 `Matcher[]` 数组通常只有 1-2 个元素（method + 可能的内容类型），遍历代价极低。**90%+ 的请求在 FullPathRouterOptimizer 一轮就命中，不进入后续优化器。**

---

## 五、PrefixPathRouterOptimizer：前缀分段

### 5.1 适用场景

`simpleWildcardList` 和 `fullWildcardList` 包含通配路径。如果通配都在路径尾部（如 `/api/*/detail`、`/files/{id}`），按前缀分段可以大幅缩小遍历范围。

### 5.2 评分算法：选择最优前缀深度

`support()` 方法计算每个前缀深度 `i` 的得分，选择最优的 `prefixPathIndex`：

```java
// PrefixPathRouterOptimizer.java  support 评分
public boolean support(List<PathMappingContext> list) {
    int[] posCount = new int[30];
    Map<Integer, Set<String>> prefixPathMap = new HashMap<>();
    for (PathMappingContext mappingContext : list) {
        // 按 "/" 分割路径，统计每个前置深度 i 的路径数
        // 以及每个深度 i 的 唯一前缀哈希键数
        // ...
    }
    int total = list.size();
    double minScore = (total - 1.5) * total + 2;
    for (int i = 0; i < posCount.length; i++) {
        int count = posCount[i];
        if (count == 0) continue;
        int hashKeyCount = prefixPathMap.get(i).size();
        double avgFindCount = (double) count / hashKeyCount;
        double score = avgFindCount * count + (total - count) * (total - count) + 0.5 * total;
        if (score < minScore) {
            minScore = score;
            prefixPathIndex = i - 1;
        }
    }
    return prefixPathIndex != -1;
}
```

评分公式的直觉是：**`avgFindCount × count`** 衡量"前缀粒度"——`avgFindCount` 越小（每个前缀下映射数越少），查找效率越高；**`(total - count)²`** 惩罚"通配段过早出现"——`count` 是全路径中在第 `i` 段有通配的路径数，`total - count` 是通配更早出现的路径数，这些路径无法被前缀优化。**`0.5 × total`** 是微调项，避免评分在边界处震荡。

最终的 `prefixPathIndex = i - 1` 是**前缀的斜杠索引**——`prefixPathIndex=0` 表示取第一段斜杠之前的路径部分（即第一个 `/` 之前，实际是空字符串，不生效），`prefixPathIndex=1` 表示取到第二个斜杠（如 `/api`）。

### 5.3 路由结构

`initAndRemove`提取每个路径的前缀部分（到 `prefixPathIndex` 位置），存入 `routeMap`。`routeMap` 的 key 是前缀字符串，value 是 `PathPatternsRouter`（通过 `FullPathRouterOptimizer.putWildcardUrl` 创建）。

运行时 `optimizeRoute`取请求路径的相同前缀位置，`routeMap.get(prefixPath)` 拿到 `PathPatternsRouter`，让后者完成后续匹配。如果前缀都不匹配，返回 `null`，优化器链继续下一环。

### 5.4 斜杠索引缓存

`getSlashIndexList`计算请求路径的斜杠位置索引数组，并缓存到 `RequestContext` 的 `SLASH_INDEX_LIST_ATTRIBUTE` 中。这个缓存被 `PrefixPathRouterOptimizer` 和 `SuffixPathRouterOptimizer` 共享——后者在 `optimizeRoute` 中调用 `PrefixPathRouterOptimizer.getSlashIndexList(req)`（`SuffixPathRouterOptimizer.java`），避免重复计算。

---

## 六、SuffixPathRouterOptimizer：后缀分段

### 6.1 适用场景

前缀优化不理想时（如路径首部就是通配 `/api/{version}/user`），后缀优化可能更有效。`SuffixPathRouterOptimizer` 从路径尾部锚定非通配段，反向计算最优后缀深度。

### 6.2 评分算法对称

`support()` 方法与 `PrefixPathRouterOptimizer` 的评分在结构上对称，但方向相反：

- 前缀用 `index` 从头部计数，后缀用 `reversedIndex` 从尾部计数。
- 前缀的 `prefixPathIndex = i - 1`，后缀的 `suffixPathIndex = i`。
- 后缀的 `suffixPathMap` 存的是尾部子串（`pathStrList.subList(pathStrList.size() - reversedIndex, ...)`）。

### 6.3 修复历史：从头数 bug

`initAndRemove` 中的注释记录了修复历史：

```java
// SuffixPathRouterOptimizer.java
// 修复前误用 slashIndexList[suffixPathIndex - 1]（从头数），
// substring 必含通配段，routeMap 永不填充。
```

修复前，`suffixPath` 用 `slashIndexList[suffixPathIndex - 1]` 从头部取索引，截取的子串包含通配段，导致 `routeMap` 中的所有 key 都含通配符，`HashMap.get` 永远匹配不上。修复后改用 `slashIndexList[slashIndexList.length - suffixPathIndex]` 从尾部锚定，`substring` 取到的后缀是纯字面路径，`HashMap.get` 才能正确命中。

### 6.4 运行时边界保护

`SuffixPathRouterOptimizer.optimizeRoute`有显式边界保护：

```java
// SuffixPathRouterOptimizer.java
if (suffixPathIndex <= 0 || suffixPathIndex >= slashIndexList.length) {
    return null;
}
```

`suffixPathIndex <= 0` 保护尾部即通配段的场景（如 `/**`），`>= slashIndexList.length` 保护请求路径段数不足以提取后缀的场景。两个边界都返回 `null`，将控制权交给优化器链的下一环。

---

## 七、LoopPathPatternRouterOptimizer：兜底遍历

当所有针对性优化都无法命中时，`LoopPathPatternRouterOptimizer` 作为兜底：

```java
// LoopPathPatternRouterOptimizer.java
public class LoopPathPatternRouterOptimizer implements RouterOptimizer {
    PathPatternsRouter router = new PathPatternsRouter();

    public boolean initAndRemove(PathMappingContext mappingContext) {
        router.add(mappingContext);
        return true;
    }

    public Router optimizeRoute(WebServerHttpRequest req) {
        return router;
    }
}
```

它把剩余的所有映射放入一个 `PathPatternsRouter`，运行时遍历所有子 `PathPatternRouter` 逐条匹配。**这个优化器没有"优化"——它是兜底**，确保前三个优化器都没覆盖的路径也能被匹配到。

`PathPatternsRouter.route`遍历内部的 `PathPatternRouter[]` 数组，逐个调用 `PathPatternRouter.route` 直到命中或遍历完。每个 `PathPatternRouter` 内部用 `RouteMatcher.matchAndExtract` 做路径变量提取。

---

## 八、Router 三层次

### 8.1 `SimpleRouter`：数组遍历 + Matcher 全匹配

已在上文 §4.3 详述。最简路由器，存 `PathMappingContext[]` 数组，遍历全匹配后返回。`add` 方法用 `Arrays.copyOf` 扩展数组。

### 8.2 `PathPatternRouter`：路径变量提取

`PathPatternRouter` 包装了一个 `SimpleRouter`，在其上叠加路径变量提取能力：

```java
// PathPatternRouter.java
public PathMappingContext route(WebServerHttpRequest req) {
    RouteMatcher.Route route = getRoute(req);
    Map<String, String> uriVariableMap = routeMatcher.matchAndExtract(pathRule, route);
    if (uriVariableMap != null) {
        req.getRequestContext().setAttribute(URI_VARIABLE_MAP_ATTRIBUTE, uriVariableMap);
        return simpleRouter.route(req);
    }
    return null;
}
```

两阶段匹配：先 `matchAndExtract` 拿路径变量，匹配成功则写到请求属性，再委托 `simpleRouter.route` 做 Matcher 条件匹配。`URI_VARIABLE_MAP_ATTRIBUTE` 是 `static final` 的 `RequestAttribute`，供后续 `@PathVariable` 等参数解析器通过 `PathPatternRouter.getUriVariableMap(req)` 读取。

`RouteMatcher` 的选择适配 Spring 版本差异：

```java
// PathPatternRouter.java
protected void initRouteMatcher() {
    String pathRule = simpleRouter.getPathRule();
    if (PathPatternUtils.supportPatternParse(pathRule)) {
        routeMatcher = PathPatternUtils.getPatternRouteMatcher();
    } else {
        log.warn("PathPatternRouter not support pathRule : {}", pathRule);
        routeMatcher = PathPatternUtils.getPathRouteMatcher();
    }
}
```

`PathPatternUtils` 包装了 Spring 5.3+ 的 `PathPatternRouteMatcher` 和旧版 `SimpleRouteMatcher` 的兼容逻辑。**这个适配对业务代码透明**——业务方写 `@GetMapping("/api/{id}")`，框架自动选最优的 `RouteMatcher` 实现。

### 8.3 `PathPatternsRouter`：多 PathPatternRouter 遍历

`PathPatternsRouter` 管理 `PathPatternRouter[]` 数组。`add(Router)` 方法实现去重逻辑：

```java
// PathPatternsRouter.java
public void add(Router router) {
    if (routers.length == 0) {
        addPathPatternRouter(new PathPatternRouter(router));
        return;
    }
    if (router instanceof PathPatternRouter) {
        router = ((PathPatternRouter) router).getSimpleRouter();
    }
    String pathRule = router.getPathRule();
    for (PathPatternRouter pathPatternRouter : routers) {
        if (pathPatternRouter.getPathRule().equals(pathRule)) {
            pathPatternRouter.add(router);    // 同一路径 → 合并到已有 PathPatternRouter
            return;
        }
    }
    addPathPatternRouter(new PathPatternRouter(router));  // 新路径 → 新建
}
```

同一路径的多个映射（如 `GET /api/{id}` 和 `POST /api/{id}`）合并到同一个 `PathPatternRouter` 中，避免重复路径变量提取。`PathPatternRouter[]` 按路径字符串去重，不同路径各自独立匹配。

---

## 九、MappingResult 三态与 405 判定

### 9.1 三态结果

`MappingResult` 取代 `PathMappingContext` 的 null 返回值，携带三种状态：

```java
// MappingResult.java
public class MappingResult {
    private final PathMappingContext matchedContext;    // 完全匹配（路径+条件）
    private final PathMappingContext[] pathMatchedContexts;  // 仅路径匹配
    private final boolean methodMismatch;               // 是否因 HTTP method 不匹配

    // 三个工厂方法：
    public static MappingResult matched(PathMappingContext ctx) { ... }
    public static MappingResult pathMatched(PathMappingContext[] contexts, boolean methodMismatch) { ... }
    public static MappingResult notFound() { ... }
}
```

- **`matched`**：路径 + 所有条件全命中。`DispatcherHandler` 走 `doHandle()` 完整管线。
- **`pathMatched`**：路径精确匹配但条件不满足。携带 `methodMismatch` 标记，供 `DispatcherHandler` 返回 405 或继续检查后续优化器。
- **`notFound`**：路径完全未匹配。走 404 流程。

`NOT_FOUND` 是 `static final` 单例，复用无堆分配——复用 [01 篇](01-design-philosophy.md) 原则 2 的异常单例模式。

### 9.2 405 判定

`MappingRegistry.doMapping` 中，优化器链遍历过程中，如果 `router.route(req)` 返回 null（路径匹配但条件不匹配），调用 `isMethodMismatch`判断：

```java
// MappingRegistry.java
private static boolean isMethodMismatch(PathMappingContext[] contexts, WebServerHttpRequest req) {
    if (contexts == null || contexts.length == 0) return false;
    boolean hasMethodMatcher = false;
    for (PathMappingContext ctx : contexts) {
        for (Matcher matcher : ctx.getMatchers()) {
            if (matcher instanceof HttpMethodMatcher) {
                hasMethodMatcher = true;
                if (((HttpMethodMatcher) matcher).getHttpMethods().contains(req.getMethod())) {
                    return false; // 方法匹配，失败由其他条件引起
                }
            }
        }
    }
    return hasMethodMatcher; // 所有 context 的方法都不匹配 → 405
}
```

核心逻辑：如果路径命中的所有 MappingContext 中，**所有** `HttpMethodMatcher` 都不匹配当前请求方法，则返回 405（`hasMethodMatcher && !任一方法匹配`）。如果没有任何 `HttpMethodMatcher`（如路径被 `@RequestMapping` 无 method 限定），则返回 404——因为方法不匹配不是导致失败的原因。

### 9.3 优化器链短路

`doMapping` 中有一个关键优化：

```java
// MappingRegistry.java
if (isMethodMismatch(pathMatchedCtxs, req)) {
    return MappingResult.pathMatched(pathMatchedCtxs, true);
}
```

当路径匹配但方法不匹配时，**立即返回 405，不继续后续优化器**。这个短路防止了 `/**` 这样的 catch-all 路径覆盖 405 语义——如果 `GET /api/user` 返回 405（只允许 POST），但 `/**` 的 catch-all 在后续优化器中匹配为 200，语义就错了。这个方法不匹配的短路确保 405 优先级高于 catch-all。

---

## 十、MappingCacheKey 与整型索引缓存

### 10.1 路由级缓存

`MappingHandlerMethod` 继承自 `InvokableHandlerMethod`，在路由层面提供了 `MappingCacheKey` 缓存机制：

```java
// MappingHandlerMethod.java
public <T> T get(MappingCacheKey<T> key) {
    Object[] cache = getCache(key);
    if (key.index >= cache.length) return null;
    return (T) cache[key.index];
}

public <T> void set(MappingCacheKey<T> key, T value) {
    int index = key.index;
    Object[] cache = getCache(key);
    if (index >= cache.length) {
        synchronized (this) {
            cache = getCache(key);
            if (index >= cache.length) {
                cache = Arrays.copyOf(cache, index + 1);
                setCache(key, cache);
            }
        }
    }
    cache[index] = value;
}
```

`get` 是 O(1) 数组直取——`Object[]` 的 `aaload` 指令，一次内存访问，无锁、无哈希、无类型推断。`set` 有 `synchronized` 保护数组扩容，但扩容只发生在启动期 Phase3，运行时 `set` 只 `cache[index] = value` 一次赋值，不触发 `synchronized`。

### 10.2 类级 vs 方法级缓存

`getCache`区分两类缓存：

```java
// MappingHandlerMethod.java
protected Object[] getCache(MappingCacheKey key) {
    if (key.classCache) {
        if (classCache == null) {
            classCache = classCacheInstanceMap.computeIfAbsent(userClass, k -> new Object[key.index + 1]);
        }
        return classCache;
    } else {
        if (methodCache == null) {
            methodCache = methodCacheInstanceMap.computeIfAbsent(userMethod, k -> new Object[key.index + 1]);
        }
        return methodCache;
    }
}
```

- **`classCacheInstanceMap`**（`ConcurrentHashMap<Class<?>, Object[]>`）：按类缓存，适用于所有方法共享的数据（如 `@ControllerAdvice` 拦截器匹配结果）。
- **`methodCacheInstanceMap`**（`ConcurrentHashMap<Method, Object[]>`）：按方法缓存，适用于方法私有的数据（如参数解析器索引、返回值解析器索引）。

两个 Map 都是 `static final`，所有 `MappingHandlerMethod` 实例共享。`MappingCacheKey` 的 `index` 是 `AtomicInteger.getAndIncrement()` 分配的全局唯一整型，跨实例不冲突。

---

## 十一、动态注册：Actuator 端点如何加入路由

标准的 `@Controller` 扫描在 Phase1 完成，但 Actuator 端点等动态注册的处理器在 Phase3 之后才出现。框架通过 `FullPathRouterOptimizer.getRouteMap()` 支持动态注册：

```java
// FullPathRouterOptimizer.java
public Map<String, Router> getRouteMap() {
    return routeMap;
}
```

`ActuatorEndpointHandlerMapping` 在初始化完成后，获取 `FullPathRouterOptimizer` 的 `routeMap`，直接向其中 `put` 新的路径映射。这个操作不经过 Phase3 的 `optimizeMapping` 流程，因此新注册的路径**只能被 `FullPathRouterOptimizer` 覆盖**（即纯路径，无通配符）。如果 Actuator 端点路径包含通配符，需要手动创建 `Router` 并加入优化器链。

**为什么动态注册不重新跑优化器链？** 因为 `optimizeMapping` 是 Phase3 的一次性流程，每次 `initOptimizersFor` 都会 `new` 优化器实例。重新跑需要暂停服务、重建所有优化器、替换 `optimizers` 列表——复杂度高且收益低（Actuator 端点极少，一次 `HashMap.get` 足够）。这个设计是"95% 场景优化 + 5% 场景可接受"的务实取舍。

---

## 十二、与 Spring MVC 路由引擎的差异

| 维度 | 本框架 | Spring MVC |
|------|--------|-----------|
| 运行时路径匹配 | `HashMap.get` 或前缀/后缀/遍历 | 逐条遍历 `AbstractHandlerMethodMapping.MappingRegistry` 的注册表 |
| 通配路径处理 | 启动期评分算法选择最优前缀/后缀分段 | 运行时 `PathMatcher.match` 或 `PathPatternRouteMatcher` 逐条匹配 |
| 三级分流 | 纯路径 + 单通配 + 双通配三桶 | 无分流，全部混在一起 |
| 匹配条件检查 | 路径命中后 Matcher 逐条 AND | 路径命中后 `RequestMappingInfo` 条件逐一检查 |
| 路径变量提取 | 启动期 `RouteMatcher` 适配，运行时 `matchAndExtract` 一次 | 运行时 `PathMatcher.extractUriTemplateVariables` |
| 405 短路 | 优化器链中方法不匹配立即返回，防止 catch-all 覆盖 | 逐条匹配直到找到路径命中者，再检查 method |
| 动态注册 | `FullPathRouterOptimizer.getRouteMap().put()` | `AbstractHandlerMethodMapping.registerMapping()` |
| 缓存 | `MappingCacheKey` 整型索引数组直取 | 无路由级缓存，每次请求重查 `MappingRegistry` |
| 歧义检测 | `Matcher.haveAmbiguous` 扩展点 | 启动期 `AmbiguousHandlerMappingException` 检测 |

**核心差异一句话**：Spring MVC 的运行时路由是"遍历直到命中"，本框架的运行时路由是"查表——表里没有才遍历"。这个"查表优先"的设计让 90%+ 请求的路由匹配时间从 O(n) 降到 O(1)，代价是通配路径的启动期预处理复杂度。

---

## 十三、小结：路由引擎的克制在哪里

回到引子的问题：`MappingResult` 是怎么来的？

1. **Phase1 扫描** `@Controller` → 解析 `@RequestMapping` → 提取五类 Matcher → 构建 `PathMappingContext` 列表。
2. **Phase3 预处理** `optimizeMapping` → 一次遍历分三桶 → 四类 `RouterOptimizer` 依次尝试接手。
3. **运行时 `doMapping`** → 优化器链逐环尝试 → `FullPathRouterOptimizer` HashMap.get O(1)（90%+ 请求命中于此）→ 未命中则前缀/后缀/兜底遍历 → 返回 `MappingResult` 三态。
4. **405 短路** → 方法不匹配时立即返回，不让 catch-all 覆盖 405 语义。

路由引擎的克制体现在：

- **不迷信"一个算法通吃"**。纯路径走 HashMap，通配路径走分段，兜底走遍历——每种路径类型选最优策略，而非"所有路径都遍历"。
- **不重复造轮子**。`RouteMatcher` 适配 Spring 的 `PathPatternRouteMatcher`/`SimpleRouteMatcher`，路径变量提取复用 Spring 实现——框架只在"Spring 做不了"的地方（启动期评分、三桶分流、O(1) 查表）才动手。
- **不把 405 当异常**。`isMethodMismatch` 把这个逻辑从 `ExceptionRegistry` 拉回路由层，用精确的短路判断避免 catch-all 污染。

这些克制把路由匹配从"每请求 O(n) 遍历"降为"95% 情况 O(1) 查表 + 5% 情况有限遍历"。在 benchmark 里，这个差距是路由维度最主要的性能来源——[17 篇](17-benchmark-data.md) 的数据会给出可量化的对比。

---

> **下一篇**：[07 · 参数解析体系](07-argument-resolution.md)——拿到 `MappingResult` 后，`DispatcherHandler` 如何把路由参数注入业务方法。