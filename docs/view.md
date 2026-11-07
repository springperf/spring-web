> [English](en/view.md) | 中文

# 视图渲染（View Rendering）

本框架支持 Spring MVC 风格的服务器端渲染（SSR），内置 Thymeleaf / FreeMarker / Beetl 模板引擎适配，通过 `spring-web-view` 可选模块提供。未引入该模块的 API-first 项目，String 返回值继续保持 JSON 行为；**一旦引入本模块**，无 `@ResponseBody` 的 String 返回值即视为视图名；若应用中存在这类视图方法且没有任何可用的 `ViewResolver`，启动期会直接失败（设置 `spring.web.view.engine=none` 可显式只保留 `redirect:`）。纯 API 项目即使引入本模块且未配置引擎也不会启动失败。

---

## 一、依赖引入

`spring-web-view` 对引擎依赖为 `provided`，需显式引入模板引擎：

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-view</artifactId>
    <version>${spring-web.version}</version>
</dependency>

<!-- Thymeleaf（默认引擎） -->
<dependency>
    <groupId>org.thymeleaf</groupId>
    <artifactId>thymeleaf</artifactId>
</dependency>
```

如需使用 FreeMarker 或 Beetl，将 `thymeleaf` 换成：

```xml
<!-- FreeMarker -->
<dependency>
    <groupId>org.freemarker</groupId>
    <artifactId>freemarker</artifactId>
</dependency>

<!-- Beetl（需连带 antlr + ext 支持包） -->
<dependency>
    <groupId>com.ibeetl</groupId>
    <artifactId>beetl-core</artifactId>
    <version>3.21.2.RELEASE</version>
</dependency>
<dependency>
    <groupId>com.ibeetl</groupId>
    <artifactId>beetl-default-antlr4.9-support</artifactId>
    <version>3.21.2.RELEASE</version>
</dependency>
<dependency>
    <groupId>com.ibeetl</groupId>
    <artifactId>beetl-ext</artifactId>
    <version>3.21.2.RELEASE</version>
</dependency>
```

> **注意**：`spring-web-view` 与 `spring-webmvc` 不可同时存在（包路径冲突）。见 [模块说明](modules.md)。

---

## 二、配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.web.view.engine` | 无（全部可用） | 启用的模板引擎列表（逗号分隔）：`thymeleaf` / `freemarker` / `beetl`；特殊值 `none` = 不注册任何内置引擎（含 JSP，仅支持 `redirect:`，并跳过「无 ViewResolver」的启动校验），**只能单独使用，启动期强制校验**；**不配置则注册 classpath 上所有可用引擎**。未知引擎名与 `none` 混写会在启动期以明确异常中断；自定义 `ViewResolver` Bean 不受此配置影响 |
| `spring.web.view.redirect-hosts` | 无（不限制） | 允许的外部重定向 host 白名单（逗号分隔，大小写不敏感）；未配置时不限制。对 `http://`、`https://` 与 `//host` 目标生效，站内相对路径不受影响；目标 host 不在列表内时抛 `IllegalArgumentException` |
| `spring.thymeleaf.prefix` | `templates/` | Thymeleaf 模板前缀（classpath 相对路径） |
| `spring.thymeleaf.suffix` | `.html` | Thymeleaf 模板后缀 |
| `spring.thymeleaf.cache` | `true` | 模板缓存与「视图名 → View」解析结果缓存；`false` 用于开发期热改 |
| `spring.thymeleaf.enabled` | `true` | 是否启用；`false` 时该解析器不参与视图解析，也不计入启动期校验 |
| `spring.thymeleaf.order` | `Integer.MAX_VALUE - 9990` | 视图解析顺序（值小者优先） |
| `spring.freemarker.prefix` | `templates/` | FreeMarker 模板前缀（classpath 相对路径） |
| `spring.freemarker.suffix` | `.ftl` | FreeMarker 模板后缀 |
| `spring.freemarker.cache` | `true` | 模板缓存与解析结果缓存 |
| `spring.freemarker.enabled` | `true` | 是否启用；语义同 Thymeleaf |
| `spring.freemarker.order` | `Integer.MAX_VALUE - 9980` | 视图解析顺序 |
| `spring.freemarker.content-type` | `text/html;charset=UTF-8` | 响应 Content-Type；其中的 `charset` 同时决定响应字节编码；非法值启动期拦截 |
| `spring.beetl.prefix` | `templates/` | Beetl 模板前缀（classpath 相对路径） |
| `spring.beetl.suffix` | `.btl` | Beetl 模板后缀 |
| `spring.beetl.cache` | `true` | 模板缓存与解析结果缓存 |
| `spring.beetl.enabled` | `true` | 是否启用；语义同 Thymeleaf |
| `spring.beetl.order` | `Integer.MAX_VALUE - 9970` | 视图解析顺序 |
| `spring.web.view.jsp.order` | `Integer.MAX_VALUE - 9960` | JSP 视图解析顺序 |
| `spring.thymeleaf.encoding` | `UTF-8` | **模板文件的读取编码**；响应字符集由 Content-Type 决定，不受此键控制 |

模板默认从 classpath 的 `templates/` 目录加载（Spring Boot 惯例）。

> 内置引擎默认顺序为 Thymeleaf → FreeMarker → Beetl → JSP，可由各自的 `order` 键调整。解析结果（含「模板不存在」）在 `cache=true` 时缓存，容量上限 1024 且超限只淘汰最早的条目；运行期新增或替换模板后调用 `ViewResolverRegistry.invalidateViewCaches()` 立即生效（见第九节，会同时清三个引擎的内部模板缓存），或关闭模板缓存 / 重启。

---

## 三、Controller 写法

### 1. 返回视图名（String）

使用 `@Controller`（非 `@RestController`），方法返回无 `@ResponseBody` 的 `String` 即视为视图名：

```java
@Controller
@RequestMapping("/view")
public class ViewController {

    @GetMapping("/hello")
    public String hello(@RequestParam(value = "name", defaultValue = "World") String name,
                        Model model) {
        model.addAttribute("name", name);
        model.addAttribute("message", "Hello " + name + "!");
        return "hello";                     // 解析为 templates/hello.html
    }
}
```

### 2. Model 参数注入

支持 `Model` / `ModelMap` / `ExtendedModelMap` 三种类型参数注入，同一请求内为**同一个 `ExtendedModelMap` 实例**（三者均可 cast），生命周期绑定 `RequestContext`，请求结束自动释放。

```java
@GetMapping("/user/{id}")
public String user(@PathVariable Long id, Model model) {
    model.addAttribute("user", userService.findById(id));
    return "user/profile";
}
```

### 3. ModelAndView

返回 `org.springframework.web.servlet.ModelAndView`（`spring-web-mvc-support` 提供，无 servlet 依赖）：

```java
@GetMapping("/mav")
public ModelAndView modelAndView(@RequestParam(defaultValue = "MAV") String name) {
    return new ModelAndView("hello")
            .addObject("name", name)
            .addObject("message", "Hello from ModelAndView " + name + "!");
}
```

支持 `ModelAndView` 的 `@ResponseStatus` 状态码与 `wasCleared()` 语义。

### 4. redirect 重定向

视图名前缀 `redirect:` 触发 302 重定向，model 中的标量属性自动序列化为 query 参数：

> 外部目标（`http://`、`https://`、`//host`）原样使用，不会拼 context-path。配置 `spring.web.view.redirect-hosts`（逗号分隔的 host 白名单）后，仅白名单内的 host 会被放行；未配置时不限制。白名单按 URI 的真实 host 判定，`https://trusted.example.com@evil.com` 不会因 userinfo 混淆而被误放行。

```java
@GetMapping("/save")
public String save(@ModelAttribute UserForm form) {
    userService.save(form);
    return "redirect:/view/hello?name=saved";
}
```

---

## 四、模板示例

### Thymeleaf（`templates/hello.html`）

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title th:text="${message}">Title</title>
</head>
<body>
<h1 th:text="${message}">Hello</h1>
<p>Name: <span th:text="${name}">nobody</span></p>
</body>
</html>
```

支持标准 Thymeleaf 表达式：`${...}`、`#{...}`、`@{...}`（URL 方言已适配 `context-path`）、`th:*` 属性等。

### FreeMarker（`templates/hello.ftl`）

```ftl
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>${message}</title>
</head>
<body>
<h1>${message}</h1>
<p>Name: ${name}</p>
</body>
</html>
```

### Beetl（`templates/hello.btl`）

```btl
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>${message}</title>
</head>
<body>
<h1>${message}</h1>
<p>Name: ${name}</p>
</body>
</html>
```

---

## 五、多引擎共存

多个 `ViewResolver` 可同时注册，在**同一个请求管线**中按 `getOrder()` 顺序依次尝试。内置引擎有确定的默认顺序（Thymeleaf → FreeMarker → Beetl → JSP），可通过各自的 `order` 配置键调整；自定义 resolver 默认排在内置引擎之前。每个 ViewResolver 通过"模板存在性探测"决定是否接管某个视图名——自己的 `prefix + viewName + suffix` 模板存在则返回 `View`，否则返回 `null` 交给下一个 resolver。探测结果与 View 实例在 `cache=true` 时按视图名缓存（FreeMarker 的缓存键额外包含 locale）；`cache=false` 保持每次探测以支持热改。

```
/view/hello     → thymeleaf 检查 templates/hello.html  → 存在 → 命中
                 → freemarker 检查 templates/hello.ftl
                 → beetl 检查 templates/hello.btl
```

- 同名不同后缀的模板（`hello.html`/`hello.ftl`/`hello.btl`）可共存，由 order 决定优先顺序；默认值确定，也可用 `spring.{engine}.order` 调整
- 不同视图名用不同引擎：`hello` → thymeleaf，`report` → freemarker，`page-btl` → beetl，只需各自的模板文件存在
- `spring.web.view.engine` 不配置时注册全部可用引擎；也可逗号分隔指定子集（如 `thymeleaf,beetl`）

---

## 六、JSP 视图（可选）

除模板引擎外，`spring-web-servlet` 还通过集成 **Apache Jasper** 提供 JSP 渲染（依赖 `org.apache.tomcat.embed:tomcat-embed-jasper`，optional）。与模板引擎不同，JSP 是"编译成 servlet"的容器技术，必须走 servlet 桥接层，见 [support 桥接内部文档](internals/12-support-bridge.md#512-jsp-视图apache-jasper)。

### 1. 依赖

```xml
<!-- JSP 引擎（Jasper） -->
<dependency>
    <groupId>org.apache.tomcat.embed</groupId>
    <artifactId>tomcat-embed-jasper</artifactId>
</dependency>
<!-- 使用 JSTL 时再加： -->
<dependency>
    <groupId>jakarta.servlet.jsp.jstl</groupId>
    <artifactId>jakarta.servlet.jsp.jstl-api</artifactId>
    <version>3.0.0</version>
</dependency>
<dependency>
    <groupId>org.glassfish.web</groupId>
    <artifactId>jakarta.servlet.jsp.jstl</artifactId>
    <version>3.0.1</version>
</dependency>
```

同时存在 `tomcat-embed-jasper` 与 `spring-web-view` 时，`JspViewAutoConfiguration` 自动激活 `JspViewResolver` 并注册 `*.jsp` 路由。

### 2. 视图名规则

| 写法 | 解析路径 |
|------|---------|
| `jsp:hello`（前缀形式） | `/jsp/hello.jsp` |
| `hello.jsp`（后缀形式） | `/jsp/hello.jsp` |

不匹配的视图名返回 null，交其他 `ViewResolver`（Thymeleaf 等）处理。

### 3. model 传递差异（与模板引擎不同）

模板引擎把 model 直接传给渲染 API；**JSP 没有 model 概念，`JspView.render()` 会把 model 写入 request attribute**，JSP 页面用 EL（`${...}`）或 scriptlet 经 `request.getAttribute` 访问：

```java
@GetMapping("/jsp-view")
public String jspView(Model model) {
    model.addAttribute("name", "spring-perf");
    return "jsp:hello";   // → /jsp/hello.jsp
}
```

```jsp
<%-- src/main/resources/jsp/hello.jsp --%>
<p>name= ${name}</p>
```

### 4. 支持能力与局限

- **已验证**：scriptlet、EL、`jsp:include` / `jsp:forward`、静态 `<%@ include %>`、JSTL（`c:forEach` / `c:if`）、复杂 model（Map/List/Bean）
- **局限**：JSTL/自定义标签需依赖（`/WEB-INF/tld` 自定义标签扫不到，放 classpath `META-INF/`）；无 web.xml（`jsp-config`/`error-page` 不支持）；默认开发模式无预编译；仅 jakarta（Boot 3.x）

---

## 七、与 Spring MVC 的差异

迁移页面型项目前请仔细阅读。**已对齐**的能力（视图名解析、Model 注入、ModelAndView、redirect、`@ResponseBody` 隔离、异常返回视图）不做改动即可迁移，以下差异需要适配：

| 能力 | Spring MVC | 本框架 | 适配建议 |
|------|-----------|--------|---------|
| `@ModelAttribute` 绑定入 model | 自动合并到 Model | ✅ 自动合并（`postProcess` 中按 `@ModelAttribute` 注解 merge） | 无迁移成本 |
| `@ControllerAdvice @ModelAttribute` 提供者 | 请求前预置 model | ✅ 支持（`postProcess` 中执行，含返回值与 `void+Model` 两种形态） | 无迁移成本 |
| `@PathVariable` 自动入 model | 路径变量在模板中可见 | ✅ 自动合并 | 无迁移成本 |
| `BindingResult` 自动入 model | 校验错误在模板中可见 | ✅ 自动合并（`BindingResult.getTarget` 入 model） | 无迁移成本 |
| 局部 `@ModelAttribute` 方法 | 同一 Controller 内预置 model | ✅ 支持（自动排除 handler 方法） | 无迁移成本 |
| `@SessionAttributes` | 跨请求 session model | ✅ 支持（需 `spring-web-servlet` 模块） | 引入 `spring-web-servlet` |
| `redirect:` 路径变量模板 | `redirect:/orders/{id}` | ❌ 不支持（仅支持 query 拼接） | 显式拼接 |
| `redirect:` flash 属性（PRG） | `RedirectAttributes` + `FlashMap` | ❌ 不支持 | 改用 query 参数 |
| `forward:` 前缀 | `RequestDispatcher.forward` | ❌ 不支持（Netty 无 forward 语义） | 改用 `redirect:` 或直接返回视图 |
| Thymeleaf `#request` / `#response` / `#session` 表达式对象 | `WebContext` 提供 servlet 对象 | ⚠️ 这些表达式对象自 **Thymeleaf 3.1** 起被官方移除；框架改为把 **session 属性注入上下文变量**（同名时 **model 优先**），Servlet 场景（引入 `spring-web-servlet`）下由 `ServletWebExchangeProvider` 提供**真实** session / principal / cookie | 模板写 `${user}` 而非 `${session.user}`；需要 `IWebExchange` 时见[扩展点 §14](extensions.md) |
| Content Negotiation（Accept 选视图） | `ContentNegotiationManager` | ❌ 不支持（默认 `text/html`，可由 `View.getContentType()` 或 `spring.freemarker.content-type` 指定） | — |
| `WebMvcConfigurer.configureViewResolvers` | 配置视图解析器 | ❌ 不支持 | 通过 `@Bean` 注册自定义 `ViewResolver` 或用配置项 |
| `Map<String,Object>` 参数 | 视为 Model | ❌ 不支持（避免与 `@ModelAttribute` 兜底冲突） | 改用 `Model` 参数 |
| `BindingAwareModelMap` | 使用的实现类 | 使用 `ExtendedModelMap` | 行为等价，特有方法不可用 |

### 关键说明

1. **String 双语义由模块引入决定**：不引入 `spring-web-view` 时，String 返回值保持 JSON 行为；一旦引入该模块，无 `@ResponseBody` 的 String 即视为视图名。仅当应用中存在这类视图方法且没有可用 `ViewResolver` 时才在启动期 fail-fast；纯 API 项目未配置引擎不会启动失败。仅使用 `redirect:` 时设 `spring.web.view.engine=none`。
2. **线程模型**：模板渲染在 handler 当前线程执行。默认 handler 运行在 `default` 业务线程池；若配置 `pool.default-execute-mode=eventloop` 或方法标注 `@RunInPool(EVENTLOOP)`，渲染会发生在 EventLoop 上，请注意阻塞风险。
3. **Spring 6.x 类型注意**：Spring 6.x 中 `ModelMap` 不再实现 `Model` 接口，框架统一注入 `ExtendedModelMap`（`ModelMap` 子类且实现 `Model`）以兼容三种参数类型。
4. **配置刷新边界**：`WebContext.refreshProperties()` 只清属性快照，不重建 `TemplateEngine`；修改 `spring.{thymeleaf,freemarker,beetl}.*` 后需重启。运行期新增或替换模板请用 `invalidateViewCaches()`（见第九节）。
5. **非法配置启动期拦截**：`spring.web.view.engine`（未知值、`none` 混写）、`spring.thymeleaf.mode`、`spring.freemarker.content-type`、`spring.{engine}.order` 与各布尔配置（`cache` / `enabled`）均在启动期校验，非法值附带配置键与合法取值直接中断启动，不会拖到首次请求才报 500。
6. **渲染失败语义**：模板渲染中途抛异常时，已写出的部分内容会被丢弃，异常交 `ExceptionRegistry`（500 错误页或异常处理器错误页），不会返回 200 + 半截 HTML。
7. **Content-Type 与字节编码**：`applyContentType()` 统一决定三条渲染链路的响应头；自定义 Content-Type 未带 charset 时会补上响应实际编码，避免浏览器按自身默认编码解出乱码。
8. **视图名后缀与绝对 URL**：三个内置引擎都接受「已带后缀」的视图名，`spring.{engine}.suffix` 只在缺失时补齐。模板 `@{...}` 生成的绝对 URL，其 scheme 由 core 的转发头策略（`server.forward-headers-strategy`、`Forwarded` / `X-Forwarded-Proto`）与 TLS 决定，host/port 取自 `Host` 头；无显式端口时按 scheme 取 80 / 443。

---

## 八、自定义 ViewResolver

实现 `io.springperf.web.view.ViewResolver` 接口并注册为 Spring Bean，即被 `ViewResolverRegistry` 自动吸收（按 `getOrder()` 排序）：

```java
@Component
public class MyViewResolver extends BaseWebComponent implements ViewResolver {
    @Override
    public View resolveViewName(String viewName, Locale locale, WebServerHttpRequest req) {
        if (!viewName.startsWith("custom:")) return null;
        return (model, request, response) -> {
            response.getHeaders().set(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8");
            response.getBody().write(("custom: " + model.get("name")).getBytes());
        };
    }
}
```

返回 `null` 表示不处理，交给链中下一个 `ViewResolver`。

---

## 九、缓存失效与性能边界

**缓存失效**：`cache=true` 时，「视图名 → View」的解析结果与「模板不存在」负结果都会被缓存，容量上限 1024，超限只淘汰最早条目。运行期新增或替换模板后调用：

```java
@Autowired
private WebContext webContext;

public void onTemplateDeployed() {
    ViewResolverRegistry registry = webContext.getWebComponentWithDefault(
            ViewResolverRegistry.class, new ViewResolverRegistry());
    registry.invalidateViewCaches();
}
```

`ViewResolverRegistry` 由 `WebContext#getWebComponentWithDefault` 懒创建，**不是 Spring Bean**；也可直接对注入的某个 `ViewResolver` Bean 调用 `invalidateCache()`（内置实现同时清模板引擎内部缓存）。`cache=false` 完全不缓存，无需失效。配置刷新不在此列（见第七节关键说明 4）。

**模块定位**：`spring-web-view` 是 SpringMVC 视图层的**兼容适配层**，不是独立的渲染性能层。渲染在 handler 当前线程同步执行，每次渲染会构造 `IWebContext` / `IWebExchange` 适配器并复制 model 变量；未提供输出缓冲复用、片段缓存、异步渲染与内容协商。高吞吐 SSR 场景建议静态化模板输出或自行实现 `View` 复用渲染结果；配置了 `pool.default-execute-mode=eventloop` 或 `@RunInEventloop` 时，避免在视图方法中做阻塞式渲染。

**引擎依赖边界**：`WebExchangeProvider` / `PerfWebExchange` 等 exchange 适配直接实现 Thymeleaf 的 `org.thymeleaf.web.*` 接口。即使只使用 FreeMarker / Beetl / JSP，classpath 上也需要 `thymeleaf`（各引擎依赖均为 `provided`，需显式引入）；Servlet 场景的真实 session / principal 桥接同样依赖该抽象。

**解析链边界**：`ViewResolver` 列表在启动期 Phase1 固化，运行期新注册的 resolver 不参与解析（但仍会被 `invalidateViewCaches()` 覆盖）；链中任一 resolver 抛异常即终止解析并保留根因，不会继续尝试后续引擎。

---

## 十、相关文档

- [模块详解](modules.md) — `spring-web-view` 模块架构
- [配置参考](configuration.md) — 全部配置项
- [扩展点指南](extensions.md) — View / ViewResolver / Model SPI
- [迁移指南](quickstart.md) — 从 Spring MVC 迁移
- [版本兼容性](compatibility.md) — 多版本矩阵与 `ModelMap` 差异
