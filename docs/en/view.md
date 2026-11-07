> [中文](../view.md) | English

# View Rendering

The framework supports Spring MVC-style server-side rendering (SSR) with built-in Thymeleaf / FreeMarker / Beetl template engine adapters, provided by the optional `spring-web-view` module. API-first projects that do not include this module keep their JSON behavior for String return values; **once the module is included**, a String returned without `@ResponseBody` is a view name; startup fails when such view methods exist but no `ViewResolver` is available (set `spring.web.view.engine=none` to keep only `redirect:`). A pure API project does not fail at startup just because the module is present without an engine.

---

## 1. Dependencies

`spring-web-view` declares engines as `provided`, so you must add the engine dependency explicitly:

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-view</artifactId>
    <version>${spring-web.version}</version>
</dependency>

<!-- Thymeleaf (default engine) -->
<dependency>
    <groupId>org.thymeleaf</groupId>
    <artifactId>thymeleaf</artifactId>
</dependency>
```

To use FreeMarker or Beetl instead, swap `thymeleaf` for:

```xml
<dependency>
    <groupId>org.freemarker</groupId>
    <artifactId>freemarker</artifactId>
</dependency>
```

> **Note**: `spring-web-view` and `spring-webmvc` must not coexist (package path conflicts). See [Modules](modules.md).

---

## 2. Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `spring.web.view.engine` | none (all available) | Enabled template engines (comma-separated): `thymeleaf` / `freemarker` / `beetl`; the special value `none` registers no built-in engine (including JSP; only `redirect:` works, and the "no ViewResolver" startup check is skipped) and **must be used alone** — enforced at startup; **when unset every available engine on the classpath is registered**. Unknown names and mixing `none` abort startup; custom `ViewResolver` beans are unaffected |
| `spring.web.view.redirect-hosts` | unset (unrestricted) | Allowlist of external redirect hosts (comma-separated, case-insensitive); unrestricted when unset. Applies to `http://`, `https://` and `//host` targets; internal relative paths are unaffected. A target host outside the list throws `IllegalArgumentException` |
| `spring.thymeleaf.prefix` | `templates/` | Thymeleaf template prefix (classpath-relative) |
| `spring.thymeleaf.suffix` | `.html` | Thymeleaf template suffix |
| `spring.thymeleaf.cache` | `true` | Template cache and the view-name-to-View resolution cache; `false` for hot reload |
| `spring.thymeleaf.enabled` | `true` | Whether the resolver is enabled; when `false` it is excluded from view resolution and startup validation |
| `spring.thymeleaf.order` | `Integer.MAX_VALUE - 9990` | View resolver order (lower wins) |
| `spring.freemarker.prefix` | `templates/` | FreeMarker template prefix (classpath-relative) |
| `spring.freemarker.suffix` | `.ftl` | FreeMarker template suffix |
| `spring.freemarker.cache` | `true` | Template cache and the resolution cache |
| `spring.freemarker.enabled` | `true` | Whether the resolver is enabled; same semantics as Thymeleaf |
| `spring.freemarker.order` | `Integer.MAX_VALUE - 9980` | View resolver order |
| `spring.freemarker.content-type` | `text/html;charset=UTF-8` | Response Content-Type; its `charset` also determines the response byte encoding; invalid values fail startup |
| `spring.beetl.prefix` | `templates/` | Beetl template prefix (classpath-relative) |
| `spring.beetl.suffix` | `.btl` | Beetl template suffix |
| `spring.beetl.cache` | `true` | Template cache and the resolution cache |
| `spring.beetl.enabled` | `true` | Whether the resolver is enabled; same semantics as Thymeleaf |
| `spring.beetl.order` | `Integer.MAX_VALUE - 9970` | View resolver order |
| `spring.web.view.jsp.order` | `Integer.MAX_VALUE - 9960` | JSP view resolver order |
| `spring.thymeleaf.encoding` | `UTF-8` | Character encoding used to **read template files**; the response charset comes from the Content-Type |

Templates are loaded from the classpath `templates/` directory by default (Spring Boot convention).

> Built-in engines resolve in the order Thymeleaf → FreeMarker → Beetl → JSP, adjustable via each `order` property. With `cache=true`, resolution results (including "template absent") are cached with a 1024-entry cap; exceeding it evicts the oldest entry only. Templates added or replaced at runtime become visible immediately after calling `ViewResolverRegistry.invalidateViewCaches()` (see section 9; it also clears each engine's internal template cache), or after disabling the cache / restarting.

---

## 3. Controller Usage

### 1. Returning a view name (String)

Use `@Controller` (not `@RestController`); a `String` return without `@ResponseBody` is treated as a view name:

```java
@Controller
@RequestMapping("/view")
public class ViewController {

    @GetMapping("/hello")
    public String hello(@RequestParam(value = "name", defaultValue = "World") String name,
                        Model model) {
        model.addAttribute("name", name);
        model.addAttribute("message", "Hello " + name + "!");
        return "hello";                     // resolves to templates/hello.html
    }
}
```

### 2. Model parameter injection

`Model` / `ModelMap` / `ExtendedModelMap` parameter injection is supported — the same `ExtendedModelMap` instance is shared across the request (castable to all three). The lifecycle is bound to `RequestContext` and released when the request completes.

```java
@GetMapping("/user/{id}")
public String user(@PathVariable Long id, Model model) {
    model.addAttribute("user", userService.findById(id));
    return "user/profile";
}
```

### 3. ModelAndView

Return `org.springframework.web.servlet.ModelAndView` (provided by `spring-web-mvc-support`, no servlet dependency):

```java
@GetMapping("/mav")
public ModelAndView modelAndView(@RequestParam(defaultValue = "MAV") String name) {
    return new ModelAndView("hello")
            .addObject("name", name)
            .addObject("message", "Hello from ModelAndView " + name + "!");
}
```

`@ResponseStatus` status codes and `wasCleared()` semantics are supported.

### 4. Redirect

The `redirect:` prefix triggers a 302 redirect; scalar model attributes are serialized into query parameters:

> External targets (`http://`, `https://`, `//host`) are used as-is and never get the context path prepended. Once `spring.web.view.redirect-hosts` (a comma-separated host allowlist) is configured, only listed hosts are allowed; leaving it unset imposes no restriction. The check uses the URI's real host, so `https://trusted.example.com@evil.com` cannot bypass it through userinfo confusion.

```java
@GetMapping("/save")
public String save(@ModelAttribute UserForm form) {
    userService.save(form);
    return "redirect:/view/hello?name=saved";
}
```

---

## 4. Template Examples

### Thymeleaf (`templates/hello.html`)

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

Standard Thymeleaf expressions are supported: `${...}`, `#{...}`, `@{...}` (URL dialect adapted to `context-path`), `th:*` attributes, etc.

### FreeMarker (`templates/hello.ftl`)

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

### Beetl (`templates/hello.btl`)

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

## 5. Multiple Engines Coexisting

Multiple `ViewResolver`s can be registered at once; within the **same request pipeline** they are tried in `getOrder()` order. The built-in engines have a fixed default order (Thymeleaf → FreeMarker → Beetl → JSP), adjustable via each `order` property; custom resolvers rank ahead of the built-ins by default. Each resolver decides whether it owns a view name by "template existence probing" — if its own `prefix + viewName + suffix` template exists it returns a `View`, otherwise `null` so the next resolver gets a chance. With `cache=true` both the probe result and the View are cached by view name (FreeMarker also includes the locale in its cache key); `cache=false` keeps probing on every request for hot reload.

```
/view/hello     → thymeleaf checks templates/hello.html  → exists → hit
                 → freemarker checks templates/hello.ftl
                 → beetl checks templates/hello.btl
```

- Templates with the same name but different suffixes (`hello.html` / `hello.ftl` / `hello.btl`) can coexist, with `order` deciding priority; defaults are deterministic and can be changed via `spring.{engine}.order`
- Different view names may use different engines: `hello` → thymeleaf, `report` → freemarker, `page-btl` → beetl — as long as each template file exists
- `spring.web.view.engine` registers every available engine when unset; a comma-separated subset (e.g. `thymeleaf,beetl`) narrows it

---

## 6. JSP Views (Optional)

Beyond template engines, `spring-web-servlet` renders JSP through **Apache Jasper** (dependency `org.apache.tomcat.embed:tomcat-embed-jasper`, optional). Unlike template engines, JSP is a "compile-to-servlet" container technology and must go through the servlet bridge — see the [support-bridge internals (Chinese)](../internals/12-support-bridge.md#512-jsp-视图apache-jasper).

### 1. Dependencies

```xml
<!-- JSP engine (Jasper) -->
<dependency>
    <groupId>org.apache.tomcat.embed</groupId>
    <artifactId>tomcat-embed-jasper</artifactId>
</dependency>
<!-- Add these only when using JSTL: -->
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

When both `tomcat-embed-jasper` and `spring-web-view` are present, `JspViewAutoConfiguration` activates `JspViewResolver` and registers the `*.jsp` route.

### 2. View-name rules

| Form | Resolved path |
|------|---------------|
| `jsp:hello` (prefix form) | `/jsp/hello.jsp` |
| `hello.jsp` (suffix form) | `/jsp/hello.jsp` |

Unmatched view names return `null` and are handed to other `ViewResolver`s (Thymeleaf and friends).

### 3. Model passing differs from template engines

Template engines hand the model straight to the rendering API; **JSP has no model concept — `JspView.render()` writes the model into request attributes**, and the page reads them with EL (`${...}`) or via `request.getAttribute` in a scriptlet:

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

### 4. Capabilities and limits

- **Verified**: scriptlets, EL, `jsp:include` / `jsp:forward`, static `<%@ include %>`, JSTL (`c:forEach` / `c:if`), complex models (Map/List/Bean)
- **Limits**: JSTL/custom tags need dependencies (custom tag libraries under `/WEB-INF/tld` are not scanned; put them in classpath `META-INF/`); no `web.xml` (`jsp-config` / `error-page` unsupported); no precompilation in the default development mode; Jakarta only (Boot 3.x)

---

## 7. Differences from Spring MVC

Read this before migrating a page-oriented project. **Aligned** capabilities (view-name resolution, Model injection, ModelAndView, redirect, `@ResponseBody` isolation, exception-to-view) migrate without changes; adapt the following:

| Capability | Spring MVC | This framework | Mitigation |
|------------|-----------|----------------|------------|
| `@ModelAttribute` binding into model | Auto-merged into Model | ✅ Auto-merged (via `postProcess` by `@ModelAttribute` annotation) | No migration cost |
| `@ControllerAdvice @ModelAttribute` providers | Pre-populate model per request | ✅ Supported (return value and `void+Model` patterns) | No migration cost |
| `@PathVariable` auto-merge into model | Path variables visible in templates | ✅ Auto-merged | No migration cost |
| `BindingResult` auto-merge into model | Validation errors visible in templates | ✅ Auto-merged (`BindingResult.getTarget` into model) | No migration cost |
| Local `@ModelAttribute` methods | Pre-populate model within same Controller | ✅ Supported (handler methods auto-excluded) | No migration cost |
| `@SessionAttributes` | Cross-request session model | ✅ Supported (requires `spring-web-servlet` module) | Add `spring-web-servlet` |
| `redirect:` path-variable templates | `redirect:/orders/{id}` | ❌ Not supported (query-only) | Concatenate explicitly |
| `redirect:` flash attributes (PRG) | `RedirectAttributes` + `FlashMap` | ❌ Not supported | Use query parameters |
| `forward:` prefix | `RequestDispatcher.forward` | ❌ Not supported (Netty has no forward semantics) | Use `redirect:` or return the view directly |
| Thymeleaf `#request` / `#response` / `#session` expression objects | Servlet objects via `WebContext` | ⚠️ These expression objects were removed in **Thymeleaf 3.1**; the framework instead injects **session attributes as context variables** (model wins on name clash), and in the Servlet case (with `spring-web-servlet`) `ServletWebExchangeProvider` supplies the **real** session / principal / cookies | Write `${user}` instead of `${session.user}`; for `IWebExchange` see [Extension Points §14](extensions.md) |
| Content Negotiation (Accept-based view) | `ContentNegotiationManager` | ❌ Not supported (defaults to `text/html`; can be set via `View.getContentType()` or `spring.freemarker.content-type`) | — |
| `WebMvcConfigurer.configureViewResolvers` | Configure view resolvers | ❌ Not supported | Register `ViewResolver` via `@Bean` or properties |
| `Map<String,Object>` parameter | Treated as Model | ❌ Not supported (avoids conflict with `@ModelAttribute` fallback) | Use `Model` parameter |
| `BindingAwareModelMap` | Concrete model type | Uses `ExtendedModelMap` | Equivalent behavior, some methods unavailable |

### Key notes

1. **String dual-semantics decided by module inclusion**: without `spring-web-view`, String returns keep their JSON behavior; once the module is included, a String returned without `@ResponseBody` is a view name. Startup fails only when such view methods exist and no `ViewResolver` is available; a pure API project does not fail merely because no engine is configured. Set `spring.web.view.engine=none` when only `redirect:` is used.
2. **Threading model**: template rendering runs on the handler's current thread. By default handlers run on the `default` business pool; if `pool.default-execute-mode=eventloop` or `@RunInPool(EVENTLOOP)` is used, rendering happens on the EventLoop — watch for blocking.
3. **Spring 6.x note**: in Spring 6.x `ModelMap` no longer implements `Model`; the framework always injects `ExtendedModelMap` (a `ModelMap` subclass that implements `Model`) to support all three parameter types.
4. **Property refresh boundary**: `WebContext.refreshProperties()` only clears property snapshots and does not rebuild `TemplateEngine`; changes to `spring.{thymeleaf,freemarker,beetl}.*` require a restart. Use `invalidateViewCaches()` for templates added or replaced at runtime (see section 9).
5. **Invalid config fails at startup**: `spring.web.view.engine` (unknown values, mixing `none`), `spring.thymeleaf.mode`, `spring.freemarker.content-type`, `spring.{engine}.order` and all boolean properties (`cache` / `enabled`) are validated at startup; invalid values abort with the property name and valid options instead of producing a 500 on the first request.
6. **Render failure semantics**: if rendering throws mid-way, the partial content already written is discarded and the exception is handled by `ExceptionRegistry` (a 500 error page or an exception-handler view); the client never receives 200 plus a partial HTML page.
7. **Content-Type and byte encoding**: `applyContentType()` decides the response header for all three rendering paths; a custom Content-Type without a charset gains the actual response charset, so browsers cannot decode the bytes with their own default encoding.
8. **View-name suffixes and absolute URLs**: all three built-in engines accept view names that already include the suffix; `spring.{engine}.suffix` is only appended when missing. For absolute URLs produced by `@{...}`, the scheme comes from core's forwarded-header strategy (`server.forward-headers-strategy`, `Forwarded` / `X-Forwarded-Proto`) and TLS, host/port come from the `Host` header, and the default port is 80 / 443 per scheme.

---

## 8. Custom ViewResolver

Implement `io.springperf.web.view.ViewResolver` and register it as a Spring Bean; it is automatically absorbed by `ViewResolverRegistry` (sorted by `getOrder()`):

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

Return `null` to defer to the next `ViewResolver` in the chain.

---

## 9. Cache Invalidation and Performance Boundaries

**Cache invalidation**: with `cache=true`, both view-name-to-View resolutions and "template absent" negatives are cached, capped at 1024 entries; exceeding the cap evicts only the oldest entry. After adding or replacing templates at runtime, call:

```java
@Autowired
private WebContext webContext;

public void onTemplateDeployed() {
    ViewResolverRegistry registry = webContext.getWebComponentWithDefault(
            ViewResolverRegistry.class, new ViewResolverRegistry());
    registry.invalidateViewCaches();
}
```

`ViewResolverRegistry` is created lazily by `WebContext#getWebComponentWithDefault` and is **not a Spring Bean**; you can also call `invalidateCache()` directly on an injected engine `ViewResolver` bean (built-in implementations clear the engine's internal template cache as well). With `cache=false` nothing is cached and no invalidation is needed. Property refresh is a separate matter (see key note 4 in section 7).

**Module positioning**: `spring-web-view` is a **compatibility adapter** for the Spring MVC view layer, not a standalone rendering-performance layer. Rendering runs synchronously on the handler's current thread; each render builds `IWebContext` / `IWebExchange` adapters and copies model variables. There is no output-buffer reuse, fragment cache, async rendering or content negotiation. For high-throughput SSR, prefer static pre-rendering or a custom `View` that reuses rendered output; with `pool.default-execute-mode=eventloop` or `@RunInEventloop`, avoid blocking rendering in view methods.

**Engine dependency boundary**: exchange adapters such as `WebExchangeProvider` / `PerfWebExchange` implement Thymeleaf's `org.thymeleaf.web.*` interfaces directly. Even when only FreeMarker / Beetl / JSP is used, `thymeleaf` must be on the classpath (all engine dependencies are `provided` and must be added explicitly); the real session / principal bridge in the Servlet case relies on the same abstraction.

**Resolver chain boundaries**: the `ViewResolver` list is fixed during startup Phase1, so resolvers registered at runtime do not participate in resolution (they are still covered by `invalidateViewCaches()`). Any resolver exception terminates the chain with its root cause preserved; later engines are not tried.

---

## 10. Related Documents

- [Modules](modules.md) — `spring-web-view` module architecture
- [Configuration](configuration.md) — all properties
- [Extension Points](extensions.md) — View / ViewResolver / Model SPIs
- [Migration Guide](quickstart.md) — migrating from Spring MVC
- [Compatibility](compatibility.md) — multi-version matrix and `ModelMap` differences
