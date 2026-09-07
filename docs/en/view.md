> [中文](../view.md) | English

# View Rendering

The framework supports Spring MVC-style server-side rendering (SSR) with built-in Thymeleaf / FreeMarker template engine adapters, provided by the optional `spring-web-view` module. API-first projects do not need to include this module — String return values keep their JSON behavior, with zero disruption.

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

To use FreeMarker instead, swap `thymeleaf` for:

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
| `spring.web.view.engine` | `thymeleaf` | Template engine: `thymeleaf` / `freemarker` |
| `spring.web.view.thymeleaf.prefix` | `templates/` | Thymeleaf template prefix (classpath-relative) |
| `spring.web.view.thymeleaf.suffix` | `.html` | Thymeleaf template suffix |
| `spring.web.view.thymeleaf.cache` | `true` | Template cache (set `false` in development for hot reload) |
| `spring.web.view.freemarker.prefix` | `templates/` | FreeMarker template prefix (classpath-relative) |
| `spring.web.view.freemarker.suffix` | `.ftl` | FreeMarker template suffix |
| `spring.web.view.freemarker.cache` | `true` | Template cache |
| `spring.web.view.encoding` | `UTF-8` | Rendering charset, also written to `Content-Type` |

Templates are loaded from the classpath `templates/` directory by default (Spring Boot convention).

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

---

## 5. Differences from Spring MVC

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
| Thymeleaf `#request` / `#response` / `#session` | Servlet objects via `WebContext` | ❌ Not supported (`IWebSession` returns null) | Avoid in templates; use model attributes |
| Content Negotiation (Accept-based view) | `ContentNegotiationManager` | ❌ Not supported (always `text/html`) | — |
| `WebMvcConfigurer.configureViewResolvers` | Configure view resolvers | ❌ Not supported | Register `ViewResolver` via `@Bean` or properties |
| `Map<String,Object>` parameter | Treated as Model | ❌ Not supported (avoids conflict with `@ModelAttribute` fallback) | Use `Model` parameter |
| `BindingAwareModelMap` | Concrete model type | Uses `ExtendedModelMap` | Equivalent behavior, some methods unavailable |

### Key notes

1. **String dual-semantics decided by registration**: view-name interpretation for String returns only activates once a `ViewResolver` is registered (an engine dependency is present); pure API projects keep their behavior.
2. **Threading model**: template rendering runs on the handler's current thread. By default handlers run on the `default` business pool; if `pool.default-execute-mode=eventloop` or `@RunInPool(EVENTLOOP)` is used, rendering happens on the EventLoop — watch for blocking.
3. **Spring 6.x note**: in Spring 6.x `ModelMap` no longer implements `Model`; the framework always injects `ExtendedModelMap` (a `ModelMap` subclass that implements `Model`) to support all three parameter types.

---

## 6. Custom ViewResolver

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

## 7. Related Documents

- [Modules](modules.md) — `spring-web-view` module architecture
- [Configuration](configuration.md) — all properties
- [Extension Points](extensions.md) — View / ViewResolver / Model SPIs
- [Migration Guide](quickstart.md) — migrating from Spring MVC
- [Compatibility](compatibility.md) — multi-version matrix and `ModelMap` differences