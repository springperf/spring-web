# Breaking Changes

> [中文版](../breaking-changes.md)

> This is a **cumulative, cross-version and cross-branch ledger**; it belongs to no single version or branch.
> `2.7.x`, `master` and `4.1.x` maintain the same content in sync. Whenever a release introduces a breaking
> change, **add a section at the end** and register a row in the version index above. The **complete** item
> list for a release (new features and fixes included) lives in `CHANGELOG.md`.
>
> - **Scope**: items that force the user to act when upgrading - removals, renames, signature or visibility
>   changes, tightened semantics. New features and ordinary fixes do not belong here.
> - **Recording rule**: every item states either the **migration action** or **why none is needed**. Intentional
>   changes reported as breaking by the binary-compatibility gate must be matched one by one here; gate
>   configuration and report conventions are in each branch's `CONTRIBUTING.md` and root `pom.xml`
>   (2.7.x does not currently run that gate).
> - **Version numbers and dates** follow `CHANGELOG.md`; section headings carry the version, so reference a
>   section by its version, not by the numbered heading (numbers shift as versions are added).

## Version Index

| Version | Date | Subject |
|---------|------|---------|
| **2.7.8** | 2026-11-26 | Supported Spring Boot narrowed to 2.7.x; a preset `Content-Type` that does not match the return type now fails explicitly |
| **3.5.8** | 2026-11-25 (expected) | Servlet specification semantics tightened and dynamic registration actually implemented; batching window on by default (2ms); repeated `startAsync` and unparseable date headers throw |
| **2.7.7** | 2026-10-13 | Configuration keys aligned with Spring Boot (old keys removed / renamed), response framing and Servlet `flush` semantics, conditional requests and Range, extension-point signatures |
| **3.5.7** | 2026-10-08 | Configuration keys aligned with Spring Boot / Tomcat: old key aliases removed, extension-point signatures and compat-layer classes adjusted |
| **2.7.6** | 2026-09-10 | Module split (`spring-web-support` → `spring-web-servlet` + `spring-web-mvc-support`), tightened `redirect:` semantics, view resolution no longer silent, production-grade Netty / pool defaults |
| **2.7.4** | 2026-08-06 | `JsonConverter` gained the abstract method `toJsonBytes(Object)` |

---
## [2.7.8] - 2026-11-26
| Item | Old behaviour | New behaviour |
|---|---|---|
| Supported Spring Boot range for `2.7.x` | `-Pspring-boot-*` could switch the build to a specific Boot version | **Spring Boot 2.7.x** only (validated on 2.7.18); the `spring-boot-2.7` profile is active by default and pins that version. **Migration**: use the default build; a non-existent `-Pspring-boot-*` only triggers a Maven warning and the build still uses 2.7.18, so the artifact is not the version you named |
| `ServletContext.addServlet` / `addFilter` | returned `null` or did nothing, so callers could believe registration succeeded | now return the spec-defined `ServletRegistration.Dynamic` / `FilterRegistration.Dynamic` with URL mappings, init parameters, `load-on-startup` and `async-supported`. Registration is open only during `ServletContextListener#contextInitialized()`; later writes throw `IllegalStateException`, and duplicate names or clashing URL patterns are reported. `addJspFile()` remains unsupported (JSPs are routed through `jsp:` views); `createServlet()` / `createFilter()` are available. **Migration**: compatibility code that checked for a `null` return should use the returned Dynamic object instead |
| `HttpServletRequest.startAsync()` called again within the same async cycle | returned the existing `AsyncContext` | throws `IllegalStateException`. **Migration**: drop the duplicate call; start a new cycle only after `dispatch` / `complete` |
| `HttpServletRequest.getDateHeader(name)` with a present but unparseable date | returned `-1`, indistinguishable from "header absent" | throws `IllegalArgumentException`. **Migration**: handle the exception as a parse failure; do not test for `-1` |
| `ServletContext.setSessionTrackingModes()` | empty implementation: both valid and invalid arguments were silently ignored | takes effect and validates (non-empty, must contain `COOKIE` or `SSL`); invalid arguments throw `IllegalArgumentException`. **Migration**: pass a set that includes `COOKIE`/`SSL`; URL-only configurations that used to be ignored now fail |
| `HttpServletResponse.getHeaders(name)` when the header is absent | returned `null` | returns an empty collection (Servlet 6.0 contract). **No migration needed**; removes an NPE risk |
| `HttpSession.setAttribute(name, null)` | threw `NullPointerException` (the underlying `ConcurrentHashMap` rejects null values) | equivalent to `removeAttribute(name)` (spec §7.5). **No migration needed**; code that relied on the exception should call `removeAttribute` instead |
| An exception thrown by a `ViewResolver` inside `ViewResolverRegistry.resolve()` | logged a WARN, skipped that resolver, and hid the root cause behind "unable to resolve view name" | throws `IllegalStateException` with the original exception as cause. **Migration**: fix the template or configuration error; do not rely on the "skip" behaviour |
| `ModelAndView` pointing at an unresolvable view name | silently returned 200 with an empty body | throws `IllegalArgumentException`, matching the String view-name path. **Migration**: make the view name resolvable, or handle it in an `@ExceptionHandler` |
| View response `Content-Type` and encoding | set independently by each engine; FreeMarker's `content-type` could disagree with the response byte encoding, and `redirect:` carried `text/html` | decided in one place by `ViewReturnValueResolver.applyContentType()`: a `charset` in the Content-Type is applied to the response encoding, and `redirect:` sets no Content-Type. **Migration**: implement `View.getContentType()` for custom headers |
| `RedirectView` status code | always set 302 | preserves a 3xx status specified by `ModelAndView`. **No migration needed** |
| `spring.beetl.mode` | declared but never read | removed. **No migration needed** |
| View-name resolution results | probed template existence and created a View on every request | cached by view name when `cache=true`, including unresolved results. **Migration**: after adding or replacing a template at runtime, call `ViewResolverRegistry.invalidateViewCaches()`, or disable template caching |
| An exception thrown mid-render | the response was already marked handled, so the 500 error response was rejected and the client received 200 plus a partial HTML page; an error view from an exception handler was appended to that partial page | the partial content is discarded and the exception is handled by `ExceptionRegistry` (a 500 error page or an exception-handler view). **No migration needed** |
| `spring.web.view.engine` values | unknown values and `none` mixed with other engines were silently ignored, potentially deferring failure to the first view request | startup throws `IllegalStateException`; `none` must be used alone. **Migration**: fix the value and use `none` on its own |
| Built-in JSP with `engine=none` | `JspViewResolver` was still auto-configured | built-in JSP is no longer auto-configured; custom `ViewResolver` beans are unaffected. **Migration**: do not set `none` when JSP is needed |
| `ViewControllerInvoker` `redirect:` response | carried a `text/html` Content-Type | sets no Content-Type, matching the other two paths. **No migration needed** |
| `spring.{thymeleaf,freemarker,beetl}.{cache,enabled}` values | values other than true/false were silently treated as false | startup throws `IllegalStateException` with the property name and actual value. **Migration**: use true/false |
| Custom Content-Type without a charset | the header was passed through unchanged while the Writer used the response charset, so the two could disagree | the header gains `;charset=<response charset>`. **Migration**: declare the charset explicitly in the property if precise control is needed |
| View names that already include the suffix (`hello.html` / `hello.btl`) | supported only by FreeMarker; Thymeleaf / Beetl produced `hello.html.html` / `hello.btl.btl` | all three built-in engines support them. **No migration needed** |
| Protocol-relative `redirect://host` target | prefixed with a non-empty context path, producing `/app//host` | used as an external target as-is. **No migration needed** |
| `IWebRequest#getServerPort()` for HTTPS without an explicit port | always returned 80 | returns 443. **No migration needed** |
| `@ResponseBody` return value with no matching `HttpBodyConverter` (preset `Content-Type`, or a media type negotiated from `produces` that nobody can write) | silently returned 200 with an empty body and dropped the return value; on the `produces` path not even the declared Content-Type was written | throws `HttpMessageNotWritableException`, mapped to 500 by `ExceptionRegistry`. **Migration**: make the target Content-Type match the return type (use `application/json` for objects), or write the response on the Servlet side and return `void`. Other return types this framework does not implement fail loudly the same way instead of returning an empty response (`StreamingResponseBody` has been supported since 2.7.8 and no longer takes this path) |
| `@BatchMapping.batchWindowMillis` default | 2.7.7 had no batching window: `endOfBatch` / `maxBatchSize` decided when to flush | A 2ms window is on by default, so the batch size is guaranteed by the window and a request may wait up to one extra window. **Migration**: set `batchWindowMillis=0` explicitly for low traffic (in-flight requests cannot fill a batch), ultra-low latency, or no-window benchmark baselines |

---

## [3.5.8] - 2026-11-25 (expected)
| Item | Old behaviour | New behaviour |
|---|---|---|
| `ServletContext.addServlet` / `addFilter` | returned `null` or did nothing, so callers could believe registration succeeded | now return the spec-defined `ServletRegistration.Dynamic` / `FilterRegistration.Dynamic` with URL mappings, init parameters, `load-on-startup` and `async-supported`. Registration is open only during `ServletContextListener#contextInitialized()`; later writes throw `IllegalStateException`, and duplicate names or clashing URL patterns are reported. `addJspFile()` remains unsupported (JSPs are routed through `jsp:` views); `createServlet()` / `createFilter()` are available. **Migration**: compatibility code that checked for a `null` return should use the returned Dynamic object instead |
| `HttpServletRequest.startAsync()` called again within the same async cycle | returned the existing `AsyncContext` | throws `IllegalStateException`. **Migration**: drop the duplicate call; start a new cycle only after `dispatch` / `complete` |
| `HttpServletRequest.getDateHeader(name)` with a present but unparseable date | returned `-1`, indistinguishable from "header absent" | throws `IllegalArgumentException`. **Migration**: handle the exception as a parse failure; do not test for `-1` |
| `ServletContext.setSessionTrackingModes()` | empty implementation: both valid and invalid arguments were silently ignored | takes effect and validates (non-empty, must contain `COOKIE` or `SSL`); invalid arguments throw `IllegalArgumentException`. **Migration**: pass a set that includes `COOKIE`/`SSL`; URL-only configurations that used to be ignored now fail |
| `HttpServletResponse.getHeaders(name)` when the header is absent | returned `null` | returns an empty collection (Servlet 6.0 contract). **No migration needed**; removes an NPE risk |
| `HttpSession.setAttribute(name, null)` | threw `NullPointerException` (the underlying `ConcurrentHashMap` rejects null values) | equivalent to `removeAttribute(name)` (spec §7.5). **No migration needed**; code that relied on the exception should call `removeAttribute` instead |
| An exception thrown by a `ViewResolver` inside `ViewResolverRegistry.resolve()` | logged a WARN, skipped that resolver, and hid the root cause behind "unable to resolve view name" | throws `IllegalStateException` with the original exception as cause. **Migration**: fix the template or configuration error; do not rely on the "skip" behaviour |
| `ModelAndView` pointing at an unresolvable view name | silently returned 200 with an empty body | throws `IllegalArgumentException`, matching the String view-name path. **Migration**: make the view name resolvable, or handle it in an `@ExceptionHandler` |
| View response `Content-Type` and encoding | set independently by each engine; FreeMarker's `content-type` could disagree with the response byte encoding, and `redirect:` carried `text/html` | decided in one place by `ViewReturnValueResolver.applyContentType()`: a `charset` in the Content-Type is applied to the response encoding, and `redirect:` sets no Content-Type. **Migration**: implement `View.getContentType()` for custom headers |
| `RedirectView` status code | always set 302 | preserves a 3xx status specified by `ModelAndView`. **No migration needed** |
| `spring.beetl.mode` | declared but never read | removed. **No migration needed** |
| View-name resolution results | probed template existence and created a View on every request | cached by view name when `cache=true`, including unresolved results. **Migration**: after adding or replacing a template at runtime, call `ViewResolverRegistry.invalidateViewCaches()`, or disable template caching |
| An exception thrown mid-render | the response was already marked handled, so the 500 error response was rejected and the client received 200 plus a partial HTML page; an error view from an exception handler was appended to that partial page | the partial content is discarded and the exception is handled by `ExceptionRegistry` (a 500 error page or an exception-handler view). **No migration needed** |
| `spring.web.view.engine` values | unknown values and `none` mixed with other engines were silently ignored, potentially deferring failure to the first view request | startup throws `IllegalStateException`; `none` must be used alone. **Migration**: fix the value and use `none` on its own |
| Built-in JSP with `engine=none` | `JspViewResolver` was still auto-configured | built-in JSP is no longer auto-configured; custom `ViewResolver` beans are unaffected. **Migration**: do not set `none` when JSP is needed |
| `ViewControllerInvoker` `redirect:` response | carried a `text/html` Content-Type | sets no Content-Type, matching the other two paths. **No migration needed** |
| `spring.{thymeleaf,freemarker,beetl}.{cache,enabled}` values | values other than true/false were silently treated as false | startup throws `IllegalStateException` with the property name and actual value. **Migration**: use true/false |
| Custom Content-Type without a charset | the header was passed through unchanged while the Writer used the response charset, so the two could disagree | the header gains `;charset=<response charset>`. **Migration**: declare the charset explicitly in the property if precise control is needed |
| View names that already include the suffix (`hello.html` / `hello.btl`) | supported only by FreeMarker; Thymeleaf / Beetl produced `hello.html.html` / `hello.btl.btl` | all three built-in engines support them. **No migration needed** |
| Protocol-relative `redirect://host` target | prefixed with a non-empty context path, producing `/app//host` | used as an external target as-is. **No migration needed** |
| `IWebRequest#getServerPort()` for HTTPS without an explicit port | always returned 80 | returns 443. **No migration needed** |
| `@ResponseBody` return value with no matching `HttpBodyConverter` (preset `Content-Type`, or a media type negotiated from `produces` that nobody can write) | silently returned 200 with an empty body and dropped the return value; on the `produces` path not even the declared Content-Type was written | throws `HttpMessageNotWritableException`, mapped to 500 by `ExceptionRegistry`. **Migration**: make the target Content-Type match the return type (use `application/json` for objects), or write the response on the Servlet side and return `void`. Other return types this framework does not implement fail loudly the same way instead of returning an empty response (`StreamingResponseBody` has been supported since 3.5.8 and no longer takes this path) |
| `@BatchMapping.batchWindowMillis` default | 3.5.7 had no batching window: `endOfBatch` / `maxBatchSize` decided when to flush | A 2ms window is on by default, so the batch size is guaranteed by the window and a request may wait up to one extra window. **Migration**: set `batchWindowMillis=0` explicitly for low traffic (in-flight requests cannot fill a batch), ultra-low latency, or no-window benchmark baselines |
| `io.springperf.web.websocket.server.SpringHeadersAdapter` | public class adapting Netty `HttpHeaders` to Spring `HttpHeaders`; taken by the `NettyWebSocketSession` constructor | removed (the adaptation is inlined) and the constructor's third parameter is now Spring's `org.springframework.http.HttpHeaders`. **Migration**: do not instantiate `NettyWebSocketSession` yourself (the framework creates sessions); build a Spring `HttpHeaders` and copy the Netty headers across if you need one |
| `ViewProperties.BEETL_MODE` / `BEETL_MODE_DEFAULT` | public constants for `spring.beetl.mode`, which was never read | removed together with the property. **Migration**: drop the constant references; the property never took effect |
| `WebServerInitializedEventAutoConfiguration#webServerInitializedEventPublisher(NettyHttpServer, ApplicationContext)` | public factory method building an event publisher that listened for `ApplicationReadyEvent` | the method remains, but its signature is now `(ApplicationContext)` and the returned listener listens for `NettyServerStartedEvent` (the server is taken from the event), so publication moves earlier to port binding. **Migration**: drop the `NettyHttpServer` argument at direct call sites; listening for `WebServerInitializedEvent` is enough, and it is now published earlier and more accurately |

---

## [2.7.7] - 2026-10-13
### 1. P0 removals

| Removed old key | Replacement | Old semantics | New semantics / migration |
|---|---|---|---|
| `server.use-forwarded-headers` (boolean) | `server.forward-headers-strategy` (enum) | `true` = trust forwarded headers | Enum: `NONE`/`FALSE` = do not trust; `FRAMEWORK`/`NATIVE` = trust (parse `Forwarded` / `X-Forwarded-*`).<br>Migration: `server.use-forwarded-headers=true` → `server.forward-headers-strategy=FRAMEWORK` |

---

### 2. P1 renames (old keys removed)

| Removed old key | Replacement | Migration |
|---|---|---|
| `server.use-forwarded-headers` (boolean) | `server.forward-headers-strategy` (enum) | `true` = trust forwarded headers → enum `NONE`/`FALSE` = untrusted, `FRAMEWORK`/`NATIVE` = trusted (parses `Forwarded` / `X-Forwarded-*`).<br>Migration: `server.use-forwarded-headers=true` → `server.forward-headers-strategy=FRAMEWORK` |
| `server.shutdown.timeout` (ms) | `server.shutdown.grace-period` (Duration) | `server.shutdown.timeout=30000` → `server.shutdown.grace-period=30s` |
| `server.servlet.application-name` | `server.servlet.application-display-name` | Rename only |
| `server.servlet.encoding.request` | `server.servlet.encoding.charset` | One unified charset applied to both request and response |
| `server.servlet.encoding.response` | `server.servlet.encoding.charset` | Ditto (the two keys merged) |
| `spring.web.view.thymeleaf.*` | `spring.thymeleaf.*` | Rename only (`prefix` / `suffix` / `cache` / `mode` / `cache-ttl` / `enabled` / `encoding`) |
| `spring.web.view.freemarker.*` | `spring.freemarker.*` | Rename only (`prefix` / `suffix` / `cache` / `template-loader-path` / `settings` / `content-type`) |
| `spring.web.view.beetl.*` | `spring.beetl.*` | Rename only (`prefix` / `suffix` / `cache`) |
| `spring.web.view.encoding` (global view encoding) | Removed | Thymeleaf uses `spring.thymeleaf.encoding`; FreeMarker / Beetl default to UTF-8 (Boot behaviour, no dedicated key) |

> Note: `spring.web.view.engine` (engine selection) is **not** removed — Boot has no equivalent key, so the name stays.

> **Java constants removed at the same time** (no aliases; direct references must move to the new constant or the
> new key name): `PropertiesConstant.SHUTDOWN_TIMEOUT`, `PropertiesConstant.USE_FORWARDED_HEADERS`.

---

### 2. New limits and defaults

Most protective additions in this batch are **on by default** — they do not change the semantics of
already-accepted requests, but they **reject requests/responses that used to pass**:

| Key | Default | Behaviour when exceeded (upgrade risk) |
|---|---|---|
| `server.max-parameter-count` | `10000` | Throws `ParameterLimitExceededException` → **400** (hash-collision DoS guard). Previously unlimited: requests with very many parameters (bulk forms) start failing with 400 |
| `server.http.multipart.max-part-header-size` | `8192` | A single **part header block** over the limit → **400**. Previously unlimited: uploads with long file names / custom headers start failing; raise it |
| `server.http.multipart.max-part-count` | `-1` (unlimited) | Total part count over the limit → **400**. Unlimited by default → no behaviour change by default |
| `server.max-http-response-header-size` | `8192` | Response headers over the byte limit → **body dropped, degraded minimal 500**. Previously unlimited: endpoints with many `Set-Cookie` / custom headers start returning 500 |
| `server.max-swallow-size` | `2MB` | After an error response (4xx/5xx) a larger request body is **no longer swallowed**; the connection is closed instead (affects keep-alive reuse only) |
| `server.keep-alive-timeout` / `server.max-keep-alive-requests` | both `0` (both unlimited) | **Both 0 ⇒ `KeepAliveHandler` is not installed**: no implicit connection-level cap by default; set explicitly if you need idle reaping / request caps |
| `server.max-ranges` | see 4.5 | Used to **tighten** the Range segment cap below 100 (effective cap = `min(value, 100)`); `0` = no multi-range, negative = no extra limit at this layer |
| `server.compression.*` | `false` (master switch) | **No compression by default** (existing behaviour kept); when enabled, gzip applies only to whitelisted content types, above `min-response-size`, and not to zero-copy file responses |
| `server.servlet.session.persistent*` | `false` (master switch) | When enabled, JDK serialization writes one file per session; `transient` attributes are not persisted and deserialization is constrained by the `persistent-deserialization-filter` allow-list |

> Projects that do not migrate old keys **will not fail to start, but the old configuration silently stops
> working**; confirm the configuration took effect via logs/tests after upgrading. For framework-vs-Boot default
> differences (static-resource mapping, `throw-exception-if-no-handler-found`, `publish-request-handled-events`,
> keep-alive caps, `read-timeout`, …) see `docs/en/configuration.md` and configure explicitly where needed.

---

### 3. Migration checklist

- [ ] `server.use-forwarded-headers` → `server.forward-headers-strategy`
- [ ] `server.shutdown.timeout`(ms) → `server.shutdown.grace-period`(Duration)
- [ ] `server.servlet.application-name` → `server.servlet.application-display-name`
- [ ] `server.servlet.encoding.request` / `.response` → `server.servlet.encoding.charset`
- [ ] All `spring.web.view.thymeleaf.*` → `spring.thymeleaf.*`
- [ ] All `spring.web.view.freemarker.*` → `spring.freemarker.*`
- [ ] All `spring.web.view.beetl.*` → `spring.beetl.*`
- [ ] `spring.web.view.encoding` → use `spring.thymeleaf.encoding` (Thymeleaf) or delete (FreeMarker/Beetl default to UTF-8)
- [ ] References to `PropertiesConstant.SHUTDOWN_TIMEOUT` / `USE_FORWARDED_HEADERS` → new constant or new key name
- [ ] Review `flush(true)` usage: use `flush(false)` for one-shot `Content-Length`; ensure the terminating chunk is written for progressive output
- [ ] Review Servlet code relying on `println` to commit → call `flush()` / `flushBuffer()` explicitly
- [ ] Code overriding `DispatcherHandler.flushResponse(...)` or calling `ErrorResponseConfig.includeBindingErrors()` must follow the new signatures
- [ ] Confirm the new conditional-request and Range semantics client/CDN side (304 with cache headers, `Vary: Accept-Encoding`, multi-part `multipart/byteranges`)

---

### 4. Behaviour and API changes

#### 4.1 Response framing: `flush(true)` changed from one-shot to progressive chunked

| Item | Old behaviour | New behaviour |
|---|---|---|
| `WebServerHttpResponse.flush(true)` | One-shot commit (`Content-Length` frame, commit ends the response) | The first call commits `Transfer-Encoding: chunked` headers and flushes buffered content; **writing may continue after commit**; `endStream()` writes the terminating chunk (the framework adds it during teardown) |

- Migration: use `flush(false)` for a one-shot commit; use `flushChunked()` for progressive output of unknown length and let the framework's `endStream()` finish.

#### 4.2 Servlet `PrintWriter`: `print` / `println` no longer auto-commit

- Old: `getWriter().println(...)` committed the response immediately (`autoFlush=true`).
- New: `println` **does not commit**; only an explicit `flush()` / `flushBuffer()` / `getOutputStream().flush()` commits. The first write still marks the response as "taken over by the application", and the framework still commits during teardown, so content is not lost.
- Migration: code relying on `println` to drive commits (hand-rolled streaming loops) should call `flush()` explicitly.

#### 4.3 Extension-point signature changes (source-incompatible)

| Old signature | New signature | Notes |
|---|---|---|
| `protected void DispatcherHandler.flushResponse(WebServerHttpResponse)` | `protected void flushResponse(WebServerHttpRequest, WebServerHttpResponse)` | Stream teardown must know whether async is still pending, hence the request parameter; the in-repo subclass `ManagementDispatcherHandler` was updated |
| `public boolean ErrorResponseConfig.includeBindingErrors()` | `public boolean includeBindingErrors(boolean onParam)` | `on-param` must genuinely gate on the parameter (the old implementation was always true under `ON_PARAM`, leaking field-level validation details) |
| Three `@Bean` methods: `SpringWebAutoConfiguration.applicationProperties()`, `accessLogWebFilter(Environment)`, `JspViewAutoConfiguration.jspViewResolver()` | Now `applicationProperties(Environment)`, `accessLogWebFilter(Environment, ApplicationProperties)`, `jspViewResolver(ApplicationProperties)` | The beans are still registered with unchanged types; only code that **overrides or calls** these methods needs the new parameters |

> Note: the methods added to `WebServerHttpResponse` (`flushChunked` / `endStream` / `isStreaming` / `markStreamCompleted` / `setBeforeCommit`) are all `default` and **do not require** changes in existing implementations. **Exception**: `markStreamCompleted` changed from `void` to `boolean` (preemptive "terminating-chunk write right", see 4.10) — overrides must follow (`true` means this call won the write right).

#### 4.4 Conditional requests and tightened error-body exposure

| Item | Old behaviour | New behaviour |
|---|---|---|
| `If-None-Match` comparison | Strong (a `W/"x"` mismatch kept returning 200) | **Weak** (ignores the `W/` prefix, RFC 9110 §13.1.2) → 304 is hit more often |
| `If-None-Match` + `If-Modified-Since` together | Both evaluated (could misjudge 304) | `If-Modified-Since` is **ignored** when `If-None-Match` is present (§13.1.3) |
| Cache headers on `304` | No `Cache-Control` | `Cache-Control` emitted consistently with the matching `200` (§15.4.5) |
| Pre-compressed (`.gz`) variants | No `Vary` | Emits `Vary: Accept-Encoding` (shared-cache poisoning guard) |
| `server.error.include-*` `on-param` | `include-binding-errors` behaved like `always` under `ON_PARAM`; the Servlet `sendError` path treated the parameter as never matching; `?x=false` also counted as a hit | Parameter names `trace`/`message`/`errors`, value `false` means explicitly off; the one-shot-commit, error and adapter paths are gated uniformly; with `include-message=always` the error body carries the root cause |
| `If-Range` mismatch | — | `Range` is ignored and the full entity returned |

#### 4.5 New: `Accept-Ranges` and multi-part Range (`multipart/byteranges`)

- Static resources now advertise `Accept-Ranges: bytes`; a single range → `206` + `Content-Range` (unsatisfiable → `416` + `Content-Range: bytes */len`); multiple ranges → `206` + `Content-Type: multipart/byteranges; boundary=...` (previously fell back to the full entity); `If-Range` gating (strong entity-tag comparison / HTTP date).
- Streaming responses of known length now use a `Content-Length` frame (previously always chunked, which made Netty silently drop a preset `Content-Length`).
- New guard key `server.max-ranges`: **effective cap = min(value, 100)** — the `Range` header is parsed by Spring `HttpRange`, which throws `Too many ranges` beyond 100 (the framework then treats Range as unusable), so this key **tightens** the cap below 100; setting it above 100 does not loosen it. `0` = no multi-range, negative = no extra limit at this layer. Over the cap, `Range` is ignored per RFC 9110 §14.2 and the full entity returned, **without truncating segments** and without a `416`.

#### 4.6 Timeouts, error-body negotiation and same-connection serialization

| Item | Old behaviour | New behaviour |
|---|---|---|
| `server.http.timeout=0` | Every request got an immediate 504 (delay 0 fires at once — effectively a global outage) | `<=0` means **no limit** (aligned with Tomcat `connectionTimeout=0`) |
| Async request timeout status | 500 (no resolver mapped `AsyncRequestTimeoutException`) | **503** (aligned with Spring `DefaultHandlerExceptionResolver`); not rewritten once committed |
| Error-body negotiation | HTML error page whenever whitelabel was enabled | Whitelabel only when the client accepts HTML (no `Accept`, `*/*` or `text/html`); explicit JSON clients get a JSON body |
| `server.http.read-timeout` | Netty `ReadTimeoutHandler`: no read event while processing closed the connection → slow handlers lost responses with no client-visible error | Read **idle** timeout: rescheduled while a request is in flight, reaping only truly idle connections and half-requests |
| Pipelined requests on one connection | Dispatched independently → responses could be out of order; a later response with `Connection: close` could even close the connection before an earlier one was written | **Serialized** per connection: queued requests are processed in order after the current response is written; queued buffers are released on connection close (ref counts balanced) |
| Compressed responses | No `Vary` | `Vary: Accept-Encoding` appended when actually compressing (idempotent) |
| `ParameterLimitExceededException` | Extended `RuntimeException` | Extends `ResponseStatusException(BAD_REQUEST)`: lazily triggered during MVC argument resolution it now maps to **400** rather than 500 |
| Form-body decoding | Netty decoder used a fixed charset (container encoding had no effect) | Decoded per the request `characterEncoding` (`server.servlet.encoding.*`) |
| Decode failures such as over-limit headers | Requests with `decoderResult=failure` were delivered to the application as normal | **400** with a graceful close (aligned with `HttpObjectAggregator`) |
| `spring.servlet.multipart.enabled=false` | The multipart aggregator was installed anyway | Not installed (`getParts` reports a non-multipart request per spec) |
| `*.ms`/`*.us`/`*.ns` duration syntax | The `s` branch matched first, so `500ms` parsed as `500m` and failed | Multi-character suffixes match first; `ms`/`us`/`ns` work |

#### 4.7 Session, redirect and conditional-request error mapping

| Item | Old behaviour | New behaviour |
|---|---|---|
| Path matched but condition failed | Always **404** | Mapped by cause (aligned with Spring MVC): **405** (with an `Allow` header) / **415** (`consumes` mismatch) / **406** (`produces` mismatch) |
| Relative `sendRedirect` target | Location written as-is (e.g. `next`) | Converted to an **absolute URL** per the Servlet spec: resolved against the current request URI's directory, `./`/`..` normalised, query/fragment preserved; scheme-bearing and `//host/...` targets pass through; `/`-prefixed still gets the context path |
| Buffer already written before `sendRedirect` | The 302 could carry previously written page bytes | Buffered content is discarded (aligned with Tomcat `clearBuffer=true`); calling it after commit still throws `IllegalStateException` |
| `tracking-modes=URL` | Session cookie still issued (dual track); the `;jsessionid=` written by `encodeURL` was never read back → following the rewritten URL gave **404 and lost the session** | No session cookie when the effective tracking modes exclude `COOKIE`; the server reads `;jsessionid=` back from the request URI (cookie first); matrix parameters stripped before routing with the raw URI preserved |
| `server.servlet.session.cookie.same-site` | The value was upper-cased (`STRICT`) and handed to the Netty enum → **`IllegalArgumentException`** | Normalised to `Lax`/`Strict`/`None` (case-insensitive; unknown values warn and are ignored) |
| `server.servlet.encoding.charset` | Only protected against application overrides; not used as the container default | Used as the **default encoding** of the request/response adapters (re-applied after a Filter wrapper rebinds) |
| `isRequestedSessionIdFromCookie()` / `isRequestedSessionIdFromURL()` | The former decided on "id non-null" (counting a fallback new session id); the latter was always `false` | Both reflect the id's **actual origin** (cookie / URI) |

#### 4.8 Management port, forwarding semantics and other behaviour adjustments

| Item | Old behaviour | New behaviour |
|---|---|---|
| HTTP/2 and request-body limits on the management port | reused main-server keys (`server.http2.enabled`, `server.http.max-content-length`) | dedicated keys `management.server.http2.enabled`, `management.server.max-content-length` (the prefix no longer borrows the main server's) |
| management port equal to the main port | treated as a conflict - **blocking startup** - even when either was the random port (`0`) | conflicts only when both are explicitly configured to the **same non-zero** port |
| `RequestDispatcher.forward` | cleared written response headers and **forced status 200** | only resets the buffer (discarding the uncommitted body): written headers and status are **kept** (as the Servlet spec requires); also fixes the swapped `FORWARD_SERVLET_PATH`/`FORWARD_PATH_INFO`, strips the leading `?` from `FORWARD_QUERY_STRING`, and adds `FORWARD_MAPPING` (Servlet 4.0+, an `HttpServletMapping`) |
| `RequestDispatcher.include` | `INCLUDE_REQUEST_URI` stored the **original request** URI (inconsistent with the target `INCLUDE_SERVLET_PATH`); `INCLUDE_QUERY_STRING` stored the full URI (e.g. `/original?page=1`, including the path and `?`); `INCLUDE_SERVLET_PATH` kept `?` when the path had a query; `INCLUDE_MAPPING` was never set; `INCLUDE_*` attributes lingered after include returned | `INCLUDE_REQUEST_URI` = contextPath + target path; `INCLUDE_SERVLET_PATH` uses the path without the query; `INCLUDE_QUERY_STRING` stores only the query string (the target path's query takes precedence, otherwise the original request's; the attribute is removed when absent); adds `INCLUDE_MAPPING` (Servlet 4.0+, an `HttpServletMapping`); include now restores all six `INCLUDE_*` values on exit (including the exception path), so nested includes get the outer values back and nothing lingers |
| non-`void` method returning `null` | not marked handled → the response was never flushed (the client waited until timeout) | treated as a completed response with no body and marked handled (`void` methods unchanged) |
| plain `HandlerInterceptor` bean (`spring-web-mvc-support`) | registered globally automatically | **no longer** registered automatically (matching Spring MVC): register through `addInterceptors` or declare a `MappedInterceptor` (which also avoids the same instance being registered twice and running twice) |
| `postHandle` / `afterCompletion` traversal order | reverse | **same (forward) order** as `preHandle`; when `preHandle` returns early, the interceptors that did not pass still get `afterCompletion` in reverse |
| malformed multipart message | raw exception, connection closed | **400** and a graceful close (`TooLongFrameException` stays **413**) |
| streaming responses (`writeStream`) | read the `InputStream` on the event loop → a slow source (DB cursor, network stream) caused **head-of-line blocking** on I/O | the read is offloaded to the business pool (the default pool; ForkJoin when absent; **never** falling back to the event loop), written in 8KB chunks with writability-based backpressure |
| `Content-Type` for `writeFile` / `writeStream` | always `application/octet-stream` | respects the value the caller already set, otherwise derives it from the file extension (`writeFile`); `octet-stream` remains the last resort |
| `getOutputStream()` | returned a new instance every call | the same response returns **the same instance** (Servlet spec); invalidated and recreated after `rebind` |
| JSR-356 `Session.setMaxIdleTimeout` | only assigned a field (the idle timeout had no effect) | propagated to the Netty pipeline's `IdleStateHandler` (`<=0` removes idle detection) |
| per-frame WebSocket limit | fixed text 8KB / binary 64KB | new `WebSocketHandlerRegistration#setMessageSizeLimit` overrides it per path (defaults unchanged) |
| `WebSocketSession#getHandshakeHeaders()` | returned a custom `SpringHeadersAdapter` (case-sensitive; writes such as `add` were silently dropped) | returns a standard `HttpHeaders` (case-insensitive, multi-value writes work); `SpringHeadersAdapter` removed |
| `@WebFilter`'s `urlPatterns` | read without proxy/inherited sources → silently promoted to a global filter | looked up with `findMergedAnnotation` along superclasses and interfaces; `urlPatterns` really restricts the path |

#### 4.9 Framing and Servlet reset semantics

| Item | Old behaviour | New behaviour |
|---|---|---|
| Progressive output for **HTTP/1.0** requests | Sent chunked frames (1.0 has no such framing; clients took the chunk markers as body — a protocol violation) | Degrades to **close-delimited**: no `Content-Length` / `Transfer-Encoding` in the headers, explicit `Connection: close` |
| `resetBuffer()` / `reset()` on a committed response | Silently cleared the buffer, leaving callers believing already-sent content could be recalled | Throws `IllegalStateException` (Servlet spec §5.6) |
| `resetBuffer()` vs the Writer's encoding buffer | Only the body buffer was cleared, so unflushed content stayed in the encoding buffer and was re-emitted by a pre-commit callback — the discard semantics failed | The encoding buffer is flushed into the body buffer first, so discard genuinely takes effect |

#### 4.10 Async / SSE lifecycle and reference counting

| Item | Old behaviour | New behaviour |
|---|---|---|
| Inbound buffer lifetime during async | Not kept alive by `startAsync()`; reading `content()` during the async phase could see 0/stale values or even throw `IllegalReferenceCountException` | The async holder **acquires/releases explicitly**: exactly one `+1` at `startAsync()` and one `-1` when the response **finishes writing**; body length is **cached at construction** and `content()` is no longer touched during async |
| Cascading response-buffer release on request release | **Every** `release()` cascaded → could release a response buffer the **business thread was writing** (cross-thread early-release race) | Cascades only when **refCnt reaches zero**; the response side also adds backstops: the write-completion callback and releasing in-flight responses on connection close |
| Client disconnect (idle SSE / unfinished async) | The async holder's inbound reference was **never returned** | The connection-close teardown path `releaseOnConnectionClose()` (triggered by `channelInactive`) performs lifecycle cleanup only and does **not** fire business error handlers |
| Reactive upstream subscription cancellation | Relied on "the next delivery fails" → a source that stops delivering (hung long-poll) was **never cancelled** | New "client disconnected" hook cancels the upstream subscription immediately |
| Terminating-chunk (`LastHttpContent`) write right | `markStreamCompleted()` was `void`, whoever wrote marked it → possible **double terminating chunk** (`EncoderException`; observed with HEAD + SSE) | `markStreamCompleted()` is now **preemptive `boolean`**; HEAD requests (committed but never progressive) **no longer** get a terminating chunk |
| `StreamEmitter.send()` after completion | Silently queued and delivered in bulk at `initialize()` → **data written to an already-terminated stream** | Throws `IllegalStateException` (aligned with Spring `ResponseBodyEmitter`) |
| Writing a body after the exchange ended | Kept allocating pooled buffers whose content could never be sent → ByteBuf leak | `getBuf()` logs **ERROR** and returns an **unpooled** discard buffer; once committed, `flushChunked`/`writeAndFlush` refuse to write and **release in place** (warn) |
| Reactive return value with no claiming resolver | Silent "200 + empty body" | **WARN** about the missing `ReactiveAdapter` |

#### 4.11 Observable changes from the JFR-driven optimizations (mostly equivalences)

| Item | Old behaviour | New behaviour |
|---|---|---|
| **Invalid configuration values** | Hot-path keys were **parsed lazily**: the first use failed | `ApplicationProperties` parses **eagerly once the Environment is ready** (aligned with Boot `@ConfigurationProperties`): a parse failure **fails startup** (fail-fast). Runtime configuration refresh is per-field tolerant: a field that fails to parse **keeps its old value** and warns |
| Servlet-bridge `AsyncContext.start` hand-off | Bound by `server.http.timeout` | With `pool.default-execute-mode=eventloop` set **explicitly**, it is **no longer** bound by it and follows Servlet async timeout semantics instead |
| Response-timeout installation timing | A timer was installed at the start of every request | Installed **on demand**: requests that complete synchronously on the EventLoop no longer schedule/cancel; streaming responses longer than `server.http.timeout` no longer log the "committed, refusing to write" WARN |
| `spring.mvc.publish-request-handled-events` default | `true` | **`false`** (deliberately off here; Boot's default is the opposite). Enable it explicitly for monitoring/audit |

#### 4.12 View layer: template-visible changes and new extension points

| Item | Old behaviour | New behaviour |
|---|---|---|
| Templates reading session data | Nothing available (`IWebSession` returned `null`) | In servlet scenarios `ServletWebExchangeProvider` supplies the **real** session / principal / cookies; session attributes are **injected as context variables** (**model wins** on name clashes) → write `${user}` instead of `${session.user}` |
| Source of the template `IWebExchange` | Hard-coded construction internally | New SPI **`WebExchangeProvider`**: `supports()` + `createExchange()` + `getOrder()`, taking the first `supports()==true` in ascending order with a built-in default as fallback. Wired through the Spring component container (**not** JDK `ServiceLoader`, keeping native-image/AOT compatibility) |
| `WebMvcConfigurer#addViewControllers` | **Silently ignored** | Bridged by `WebMvcConfigurerBridge` (`ViewControllerRegistry` / `ViewControllerRegistration` + `ViewControllerInvoker`); callbacks that cannot be bridged log a **WARN** |
| `spring.mvc.view.prefix` / `.suffix` | — | **New**, Boot-aligned: prefix/suffix for **JSP views only**; template engines keep their own `spring.{engine}.prefix/suffix` |
| Session persistence and encoding forcing | — | `server.servlet.session.persistent` / `.store-dir` / `.persistent-exclude` (JDK serialization, one file per session, `transient` attributes not persisted); `server.servlet.encoding.force` / `.force-request` / `.force-response` |
| Dynamic config-centre refresh | A change required a restart | Listens for `EnvironmentChangeEvent` (**by class name**, adding no compile-time Spring Cloud dependency) → clears the framework property caches; `WebContext.refreshProperties()` can also be called manually |

---

## [3.5.7] - 2026-10-08

### 1. P0 removals

| Removed old key | Replacement | Old semantics | New semantics / migration |
|---|---|---|---|
| `server.use-forwarded-headers` (boolean) | `server.forward-headers-strategy` (enum) | `true` = trust forwarded headers | Enum: `NONE`/`FALSE` = do not trust; `FRAMEWORK`/`NATIVE` = trust (parse `Forwarded` / `X-Forwarded-*`).<br>Migration: `server.use-forwarded-headers=true` → `server.forward-headers-strategy=FRAMEWORK` |

---

### 2. P1 renames (old keys removed)

| Removed old key | Replacement | Migration |
|---|---|---|
| `server.http.max-header-size` (int, bytes) | `server.max-http-request-header-size` (int, bytes) | Rename only, same unit (default 8KB) |
| `server.async.timeout` (ms) | `spring.mvc.async.request-timeout` (Duration) | `server.async.timeout=30000` → `spring.mvc.async.request-timeout=30s` (a plain number of milliseconds is still accepted) |
| `server.shutdown.timeout` (ms) | `server.shutdown.grace-period` (Duration) | `server.shutdown.timeout=30000` → `server.shutdown.grace-period=30s` |
| `server.servlet.application-name` | `server.servlet.application-display-name` | Rename only |
| `server.servlet.encoding.request` | `server.servlet.encoding.charset` | One charset, applied to both request and response |
| `server.servlet.encoding.response` | `server.servlet.encoding.charset` | Same as above (the two keys are merged) |
| `server.virtual-host` | `server.servlet.virtual-server-name` | Rename only (default localhost), Boot-aligned naming |
| `server.tomcat.max-part-count` | `server.http.multipart.max-part-count` | Rename only, same semantics (default -1, unlimited). This is not Tomcat, so the key joins the HTTP protocol family |
| `server.tomcat.max-part-header-size` | `server.http.multipart.max-part-header-size` | Rename only, same semantics (default 8192) |

> **Java constants removed as well** (7 of the 10 removals in japicmp's measured report against the **released
> 3.5.6** baseline; the other 3 are the extension-point items below):
> `PropertiesConstant.ASYNC_TIMEOUT` / `ASYNC_TIMEOUT_DEFAULT`, `HTTP_MAX_HEADER_SIZE` / `HTTP_MAX_HEADER_SIZE_DEFAULT`,
> `SERVER_SHUTDOWN_TIMEOUT` / `SERVER_SHUTDOWN_TIMEOUT_DEFAULT`, `USE_FORWARDED_HEADERS`.
> Key-level migration is the table above; **the constant identifiers have no compatible alias** - code that
> references `PropertiesConstant.XXX` directly must switch to the new constant or to the new key name.

---

### 3. P1 view-engine internal keys (`spring.web.view.*` removed after Boot alignment)

The view resolvers used framework-internal keys `spring.web.view.{engine}.*`; they are now the Spring Boot style
`spring.{engine}.*`. **Every `spring.web.view.*` key is removed**, only the `spring.*` naming remains.

| Removed old key (`spring.web.view.*`) | Replacement (`spring.*`) |
|---|---|
| `spring.web.view.thymeleaf.prefix` | `spring.thymeleaf.prefix` |
| `spring.web.view.thymeleaf.suffix` | `spring.thymeleaf.suffix` |
| `spring.web.view.thymeleaf.cache` | `spring.thymeleaf.cache` |
| `spring.web.view.thymeleaf.mode` | `spring.thymeleaf.mode` |
| `spring.web.view.thymeleaf.cache-ttl` | `spring.thymeleaf.cache-ttl` |
| `spring.web.view.thymeleaf.enabled` | `spring.thymeleaf.enabled` |
| `spring.web.view.thymeleaf.encoding` | `spring.thymeleaf.encoding` |
| `spring.web.view.freemarker.prefix` | `spring.freemarker.prefix` |
| `spring.web.view.freemarker.suffix` | `spring.freemarker.suffix` |
| `spring.web.view.freemarker.cache` | `spring.freemarker.cache` |
| `spring.web.view.freemarker.template-loader-path` | `spring.freemarker.template-loader-path` |
| `spring.web.view.freemarker.settings` | `spring.freemarker.settings` |
| `spring.web.view.freemarker.content-type` | `spring.freemarker.content-type` |
| `spring.web.view.beetl.prefix` | `spring.beetl.prefix` |
| `spring.web.view.beetl.suffix` | `spring.beetl.suffix` |
| `spring.web.view.beetl.cache` | `spring.beetl.cache` |

#### Global view encoding key removed

| Removed old key | Handling |
|---|---|
| `spring.web.view.encoding` (global view encoding) | Removed. Thymeleaf uses `spring.thymeleaf.encoding`; Freemarker / Beetl are UTF-8 (matching Boot, which has no separate encoding key). |

> Note: `spring.web.view.engine` (view-engine selection) is **not** removed - Boot has no counterpart key, so it stays.

---

### 4. Migration checklist

- [ ] `server.use-forwarded-headers` → `server.forward-headers-strategy`
- [ ] `server.http.max-header-size` → `server.max-http-request-header-size`
- [ ] `server.async.timeout`(ms) → `spring.mvc.async.request-timeout`(Duration)
- [ ] `server.shutdown.timeout`(ms) → `server.shutdown.grace-period`(Duration)
- [ ] `server.servlet.application-name` → `server.servlet.application-display-name`
- [ ] `server.servlet.encoding.request` / `.response` → `server.servlet.encoding.charset`
- [ ] every `spring.web.view.thymeleaf.*` → `spring.thymeleaf.*`
- [ ] every `spring.web.view.freemarker.*` → `spring.freemarker.*`
- [ ] every `spring.web.view.beetl.*` → `spring.beetl.*`
- [ ] `spring.web.view.encoding` → use `spring.thymeleaf.encoding` (Thymeleaf) or drop it (Freemarker/Beetl are UTF-8)
- [ ] check `flush(true)` usage: for a one-shot `Content-Length` use `flush(false)`; for progressive output make sure the terminator is written
- [ ] check Servlet code that relies on `println` to commit → switch to an explicit `flush()` / `flushBuffer()`
- [ ] code that overrides `DispatcherHandler.flushResponse(...)` or calls `ErrorResponseConfig.includeBindingErrors()` follows the new signatures
- [ ] on the client / CDN side, confirm the new conditional-request and Range semantics (304 carries cache headers, `Vary: Accept-Encoding`, multi-range `multipart/byteranges`)

---

### 5. Impact

- The renames in sections 1-4 leave **defaults and main behaviour semantics unchanged**, with two exceptions:
  1. `server.servlet.encoding.request` / `.response` merge into the single `server.servlet.encoding.charset`
     (applied to both request and response) - if the old configuration used different charsets for request and
     response, the merge cannot keep that difference;
  2. `spring.web.view.encoding` (global view encoding) is removed - Thymeleaf uses `spring.thymeleaf.encoding`,
     Freemarker / Beetl are fixed to UTF-8 (matching Boot, which has no separate encoding key) - a previously
     configured global non-UTF-8 encoding no longer applies to those two engines.
- The **new protective limits in this batch are active by default** (section 6); check them against your real
  request / response sizes before deploying.

---

### 6. New limits and defaults introduced by the alignment (read before upgrading)

Most of the protective items added in this batch (`config-alignment`) are **on by default** - they do not change
the semantics of requests that already passed, but they **reject requests/responses that used to pass**, which is
an observable behaviour change:

| Key | Default | Behaviour when exceeded (upgrade risk) |
|---|---|---|
| `server.max-parameter-count` | `10000` | More parameters than the limit throws `ParameterLimitExceededException` → **400** (hash-collision DoS defence). Was unlimited: requests with very many parameters (large forms) now start to 400 |
| `server.max-connections` | `0` (unlimited) | Beyond the connection limit the connection is dropped outright (no `fireChannelActive`, so no 503 can be sent). Unlimited by default → no behaviour change by default; in production the gateway normally carries the concurrency and connection cap |
| `server.http.multipart.max-part-header-size` | `8192` | A single **part's header block** over the limit → **400**. Was unlimited: uploads with long filenames or custom part headers start to fail and need a higher value |
| `server.http.multipart.max-part-count` | `-1` (unlimited) | More parts than the limit → **400**. Unlimited by default → no behaviour change by default |
| `server.max-http-response-header-size` | `8192` | Over the total response-header size the body is **dropped and degraded to a minimal 500**. Was unlimited: endpoints with large response headers (many `Set-Cookie` / custom headers) start to 500 |
| `server.max-swallow-size` | `2MB` | After an error response (4xx/5xx) a body larger than this is **no longer drained**; the connection is closed instead (the only downside is keep-alive reuse) |
| `server.keep-alive-timeout` / `server.max-keep-alive-requests` | both `0` (both unlimited) | **Both 0 ⇒ `KeepAliveHandler` is not installed at all**: no implicit connection-level limit by default; set one explicitly if you want idle reaping or a request cap (in production the proxy / gateway normally carries this - see the note under the server table in `docs/configuration.md` for the edges it cannot cover) |
| `server.compression.*` | `false` (master switch) | **No compression** by default (existing behaviour kept); once enabled, gzip applies only to whitelisted Content-Types larger than `min-response-size` and never to zero-copy file responses |

**Different from Spring Boot's defaults (configure explicitly when migrating from Boot)**:

| Key | This framework | Spring Boot | Migration impact |
|---|---|---|---|
| `spring.web.resources.add-mappings` | `false` | `true` | Boot projects map static resources automatically; here the default static-resource mapping is registered only when set to `true` |
| `spring.mvc.throw-exception-if-no-handler-found` | `true` | `false` | This framework throws by default (catchable by `@ControllerAdvice`) instead of returning 404/405 directly; set `false` for Boot behaviour |
| `spring.mvc.publish-request-handled-events` | `false` | `true` | Enable explicitly if you need `ServletRequestHandledEvent` (monitoring / auditing) |
| `server.max-keep-alive-requests` | `0` (unlimited) | `100` | The per-connection request count is no longer limited; set `100` explicitly for Tomcat behaviour |
| `server.http.read-timeout` | `0` (disabled) | Tomcat `connectionTimeout` = `20s` | No read-idle protection by default: without it a slow client can hold a connection indefinitely; in production the **proxy / gateway normally reclaims idle connections**, and the edges it cannot cover (direct / east-west traffic, the app-to-gateway hop when the peer dies) need an explicit value (e.g. `30s`). Semantics: only genuinely idle connections and stalled half-requests are reclaimed, in-flight requests are never killed |
| `server.error.include-message` / `.include-binding-errors` | `never` | `never` | identical |
- **Section 7 covers behaviour and API changes** (response framing, Servlet flush semantics, conditional requests/Range,
  extension-point signatures); they ship with the same release as the configuration migration and need to be assessed together.
- A project that did not migrate the old keys starts with the defaults (no error, the old configuration silently
  stops applying); after upgrading, confirm through logs or tests that the configuration took effect.
- If a code example (`spring-web-examples`) still uses an old key, it needs updating too (see the corresponding PR).

---
### 7. Behaviour and API changes (not config keys, same release)

> Not directly related to the key migration, but to be assessed together with it. Everything here landed in the
> "E2E-driven protocol alignment" batch; the commit hashes are in the listed "related commits".

#### 7.1 Response framing: `flush(true)` became progressive chunked output

| Item | Old behaviour | New behaviour |
|---|---|---|
| `WebServerHttpResponse.flush(true)` | One-shot commit (a `Content-Length` frame; committing ends the response) | The first call commits a `Transfer-Encoding: chunked` header frame and sends whatever is buffered; **writing may continue after the commit**. The terminator is written by `endStream()` (the framework adds it while finishing) |

- Impact: code and load scripts that rely on the old semantics (a `Content-Length` after committing, or "no more writes after commit") need adjusting.
- Migration: use `flush(false)` for a one-shot commit; use `flushChunked()` for progressive output of unknown length, with the framework's `endStream()` finishing it.
- Related commits: `41f4e785`

#### 7.2 Servlet `PrintWriter`: `print/println` no longer auto-commit (matching Tomcat `autoFlush=false`)

- Old behaviour: `getWriter().println(...)` committed the response immediately (`autoFlush=true`).
- New behaviour: `println` **does not commit**; only an explicit `flush()` / `flushBuffer()` / `getOutputStream().flush()` commits. The first write marks the response as taken over by the application; the framework still commits while finishing, so nothing is lost.
- Migration: code that relied on `println` to drive the commit (hand-rolled streaming loops) must call `flush()` explicitly.
- Related commits: `10a0f9d0`

#### 7.3 Extension-point signature changes (source-level incompatible)

| Old signature | New signature | Notes |
|---|---|---|
| `protected void DispatcherHandler.flushResponse(WebServerHttpResponse)` | `protected void flushResponse(WebServerHttpRequest, WebServerHttpResponse)` | Finishing a stream needs to know whether an async request is still pending, hence the request parameter; the in-repo subclass `ManagementDispatcherHandler` was updated with it |
| `public boolean ErrorResponseConfig.includeBindingErrors()` | `public boolean includeBindingErrors(boolean onParam)` | `on-param` must really be gated by the parameter (the old implementation was always true under `ON_PARAM`, leaking field-level validation details unconditionally) |
| three `@Bean` methods: `SpringWebAutoConfiguration.applicationProperties()`, `accessLogWebFilter(Environment)`, `JspViewAutoConfiguration.jspViewResolver()` | become `applicationProperties(Environment)`, `accessLogWebFilter(Environment, ApplicationProperties)`, `jspViewResolver(ApplicationProperties)` (and `jspViewResolver` moves from the main auto-configuration into `JspViewAutoConfiguration`) | the beans are still registered with unchanged types; only code that **overrides or calls** these methods (e.g. `super.xxx()` in a `@Configuration` subclass) needs the new parameters. Reported by japicmp against the **released 3.5.6** |
| `public static final AttributeKey<ConnectionContext> NettyServerHttpResponse.CONN_CTX` | removed: the connection context now lives in the per-connection state holder `ChannelAttrs.connCtx` (read via `ChannelAttrs.of(ch)` / `ofIfPresent(ch)`) | the whole connection converges onto a **single** channel attr, removing 8-10 `attr(key)` linear scans per request (JFR measured `searchAttributeByKey` at ~1.6% of leaf frames). Usage: `docs/internals/05-server-and-http.md` section 7 |
| `Http2ChannelInitializer`'s 11-argument constructor `(boolean, SslContext, int, long, boolean, NettyHttpHandler, List<ChannelHandler>, List<ChannelHandler>, int, int, int)` | removed, replaced by a 15-argument form (after the original three `int`s: `maxPartCount`, `maxPartHeaderSize`, `CompressionConfig`, `KeepAliveConfig`); `multipartConfig(MultipartConfig)` is new | code that does `new` on this class needs the new parameters. Both signatures above are taken from japicmp's **measured output** (baseline 3.2.4): `mvn -Pcompat -Dcompat.oldVersion=<released version> verify`, report under `<module>/target/japicmp/` |

| Item | Old behaviour | New behaviour |
|---|---|---|
| `WebServerHttpRequest` gained `getURI()` / `getRemoteAddress()` / `getLocalAddress()` | - | **Added (abstract methods, not `default`)**. ⚠️ Code that implements this interface outside the framework **must implement** all three or it will not compile (source-level incompatible); `BaseWebServerHttpRequest` also narrows the visibility of its `attributes` field. Reported by japicmp against the released 3.5.6. Related commit: `553bd0ee` |
| `ListenableFutureAdapter`: `public` no-arg constructor, `isAvailable()`, extendability | constructor made `private`, `isAvailable()` removed, class marked `final` | the class is demoted from a public extension point to a **framework-internal helper**: code that extends it or calls `new` on it is out of luck. **`ListenableFuture` return-value support itself remains** (through `ListenableFutureReturnValueResolver` + `isAssignableFrom`/`isInstance`). Part of the Spring Boot 4 compat-layer cleanup. Related commit: `8f51de19` |
| compat-layer classes `ResponseStatusExceptionAdapter` (with `getHeaders(ResponseStatusException)`, its constructor and superclass) and `MediaTypeUtils` (with `compareSpecificity` / `sortBySpecificity`, its constructor, `APPLICATION_STREAM_JSON` and the two `COMPARATOR` constants) | - | **Removed entirely**. `master` converged on pure Spring Boot 3.5.x, so the Spring Boot 4 / Spring Framework 7 compatibility layer is gone (the `WebHttpHeaders` version branches, the MethodHandle calls and so on with it). Code that needs MediaType ordering should use the comparator built into `org.springframework.http.MediaType`. Related commit: `8f51de19` |

- Note: the methods `WebServerHttpResponse` gained (`flushChunked`/`endStream`/`isStreaming`/`markStreamCompleted`/`setBeforeCommit`) are all `default` and require **no** change to existing implementations. **Exception**: `markStreamCompleted` later changed from `void` to `boolean` (preemptive ownership of the terminating chunk, see 7.10) - implementations that override it must follow the signature (returning `true` means this call won the write).
- Migration: code that overrides or calls the methods above follows the new signatures.
- Related commits: `41f4e785`, `e6cdab8d`

#### 7.4 Conditional requests and error-body exposure tightened

| Item | Old behaviour | New behaviour |
|---|---|---|
| `If-None-Match` comparison | strong comparison (`W/"x"` did not match → stayed 200) | **weak comparison** (the `W/` prefix is ignored, RFC 9110 §13.1.2) → 304 is hit more often |
| `If-None-Match` together with `If-Modified-Since` | both were evaluated (could wrongly return 304) | when `If-None-Match` is present, `If-Modified-Since` is **ignored** (§13.1.3) |
| cache headers on a `304` | no `Cache-Control` | `Cache-Control` is emitted just like the matching `200` (§15.4.5) |
| pre-compressed (`.gz`) variants | no `Vary` | `Vary: Accept-Encoding` is emitted (prevents shared-cache poisoning) |
| `on-param` for `server.error.include-*` | `include-binding-errors` behaved as `always` under `ON_PARAM`; the Servlet `sendError` path always treated the parameter as absent; `?x=false` counted as present | parameter names are `trace`/`message`/`errors`, the value `false` means explicitly off; the one-shot commit, error and adapter paths are gated consistently; with `include-message=always` the error body carries the root-cause message |
| `If-Range` mismatch | - | the `Range` is ignored and the full entity returned (never a fragment inconsistent with the new entity) |

- Impact: client / CDN cache behaviour and error bodies change (more standards-conformant).
- Related commits: `f1bba5f9`, `e6cdab8d`

#### 7.5 New: `Accept-Ranges` and multi-range requests (`multipart/byteranges`)

- Static resources now advertise `Accept-Ranges: bytes`; a single range → `206` + `Content-Range` (unsatisfiable → `416` + `Content-Range: bytes */len`); multiple ranges → `206` + `Content-Type: multipart/byteranges; boundary=...` (previously multi-range fell back to the whole entity); gated by `If-Range` (strong entity-tag comparison / HTTP date).
- Streaming responses of known length now use a `Content-Length` frame (previously always chunked, which made Netty silently drop a preset `Content-Length`).
- New protective key `server.http.max-ranges`: the **effective limit is min(this value, 100)** - the `Range` header is parsed by Spring's `HttpRange`, which throws `Too many ranges` above 100 ranges (and the framework then ignores the header as "Range unavailable"), so this key is for **tightening below 100** (e.g. allowing only `2` ranges); setting it above 100 does not relax anything. `0` = no multi-range, a negative value = this layer adds no limit. Over the limit the `Range` header is ignored per RFC 9110 §14.2 and the whole entity is returned - the range count is **not** truncated (that would hand the client a representation it did not ask for), and `416` is **not** returned (the request itself is valid).
- Related commits: `e6cdab8d`, `f1bba5f9`, `ead49073` (the limit was added in this batch)

#### 7.6 Timeouts, error-body negotiation and same-connection serialisation

| Item | Old behaviour | New behaviour |
|---|---|---|
| `server.http.timeout=0` | every request got an immediate 504 (a timer with delay 0 fires at once, i.e. the whole service is unusable) | `<=0` means **no limit** (matching Tomcat's `connectionTimeout=0`) |
| async-request timeout status | 500 (no resolver mapped `AsyncRequestTimeoutException`) | **503** (matching Spring's `DefaultHandlerExceptionResolver`); not rewritten once the response is committed |
| error-body content negotiation | with whitelabel enabled, HTML error pages were returned unconditionally | whitelabel is used only when the client accepts HTML (no `Accept`, `*/*` or `text/html`); explicit JSON clients (RestTemplate / service-to-service) get a JSON error body |
| `server.http.read-timeout` | Netty's `ReadTimeoutHandler`: no read event during processing closed the connection → a slow handler's response was dropped with nothing to see on the client | changed to a read-**idle** timeout: with a request in flight the deadline is rescheduled, so only genuinely idle connections and stalled half-requests are reclaimed (matching Tomcat's `connectionTimeout`) |
| pipelined requests on one connection | dispatched independently → responses could come back out of order; a later response with `Connection: close` could even close the connection before an earlier response was written, losing it | same-connection **serialisation**: queued requests are processed in order after the response is written; the queue buffer is released when the connection closes (reference counts balance) |
| compressed responses | no `Vary` | when compression actually applies, `Vary: Accept-Encoding` is added (idempotent; stops a shared cache handing a gzip representation to a client that cannot read it) |
| `ParameterLimitExceededException` | extended `RuntimeException` | extends `ResponseStatusException(BAD_REQUEST)`: even when thrown lazily during MVC argument resolution it maps to **400** instead of 500 (existing code catching `RuntimeException` still works) |
| form-body decoding | Netty's decoder used a fixed charset (the container encoding had no effect) | decodes with the request's `characterEncoding` (`server.servlet.encoding.*`) |
| decode failures such as oversized headers | Netty requests with `decoderResult=failure` were delivered to the application as normal requests | **400** and a graceful close (matching `HttpObjectAggregator`); the header/request-line defences are no longer bypassed |
| `spring.servlet.multipart.enabled=false` | the multipart aggregator was installed anyway | not installed (`getParts` reports "not a multipart request" as the spec says) |
| `*.ms` / `*.us` / `*.ns` duration suffixes | `parseDurationMillis` matched the `s` branch first, so `500ms` parsed as `500m` and threw | multi-character suffixes match first; `ms` / `us` / `ns` work |

- Related commits: `efba12d7`, `b246675f`, `d1c30851`, `267359b7`, `2f505e2f`

#### 7.7 Error mapping for sessions, redirects and conditional requests

| Item | Old behaviour | New behaviour |
|---|---|---|
| path matched but a condition failed | always **404** | mapped by cause (matching Spring MVC): **405** (with an `Allow` header listing the methods the path supports) / **415** (`consumes` mismatch) / **406** (`produces` mismatch); with `throwExceptionIfNoHandlerFound=true` a `ResponseStatusException` carries the status and `Allow` is written straight to the response |
| relative path in `sendRedirect` | the location was written as-is (e.g. `next`) | converted to an **absolute URL** as the Servlet spec requires: resolved against the directory of the current request URI, with `./`/`..` normalised and query/fragment preserved; values with a scheme (`http:`/`mailto:`) and network-path references `//host/...` pass through untouched; a leading `/` still gets the context path |
| buffer already written before `sendRedirect` | the 302 could carry earlier page bytes | buffered content is discarded (matching Tomcat's `clearBuffer=true`); calling it after commit still throws `IllegalStateException` (the response is already on the wire, so the client keeps what was committed) |
| `tracking-modes=URL` | the session Cookie was still sent (dual track); the `;jsessionid=` written by `encodeURL` was never read back → following the rewritten URL gave **404 and lost the session** | no session Cookie is sent when the effective tracking modes exclude `COOKIE`; the server reads `;jsessionid=` back from the request URI (Cookie first, Servlet §7.1); matrix parameters are stripped from every path segment before routing (matching Spring's `removeSemicolonContent=true`; the raw URI is preserved) |
| `server.servlet.session.cookie.same-site` | the configured value was upper-cased (`STRICT`) and handed to a Netty enum → **`IllegalArgumentException`** | normalised to `Lax`/`Strict`/`None` (case-insensitive; unknown values warn and are ignored) |
| `server.servlet.encoding.charset` | only used to "prevent the application from overriding it", never as the container default | becomes the **default encoding** of the request/response adapters (re-applied after a Filter wrapper rebinds them; `force-*` semantics unchanged) |
| `isRequestedSessionIdFromCookie()` / `isRequestedSessionIdFromURL()` | the former decided from "id is non-null" (counting a fallback newly created session as cookie-sourced); the latter was always `false` | both reflect the id's **real origin** (Cookie / URI) |

- Related commits: `56b16d40`, `85b98205`, `64de3eef`, `ff33fe73` (`9b1d361a` locks them with E2E, 47 cases)

#### 7.8 Management port, forwarding semantics and other behaviour adjustments

| Item | Old behaviour | New behaviour |
|---|---|---|
| HTTP/2 and request-body limits on the management port | reused main-server keys (`server.http2.enabled`, `server.http.max-content-length`) | dedicated keys `management.server.http2.enabled`, `management.server.max-content-length` (the prefix no longer borrows the main server's) |
| management port equal to the main port | treated as a conflict - **blocking startup** - even when either was the random port (`0`) | conflicts only when both are explicitly configured to the **same non-zero** port |
| `RequestDispatcher.forward` | cleared written response headers and **forced status 200** | only resets the buffer (discarding the uncommitted body): written headers and status are **kept** (as the Servlet spec requires); also fixes the swapped `FORWARD_SERVLET_PATH`/`FORWARD_PATH_INFO` and strips the leading `?` from `FORWARD_QUERY_STRING` |
| `RequestDispatcher.include` | `INCLUDE_REQUEST_URI` stored the **original request** URI (inconsistent with the target `INCLUDE_SERVLET_PATH`); `INCLUDE_QUERY_STRING` stored the full URI (e.g. `/original?page=1`, including the path and `?`); `INCLUDE_SERVLET_PATH` kept `?` when the path had a query; `INCLUDE_MAPPING` was never set; `INCLUDE_*` attributes lingered after include returned | `INCLUDE_REQUEST_URI` = contextPath + target path; `INCLUDE_SERVLET_PATH` uses the path without the query; `INCLUDE_QUERY_STRING` stores only the query string (the target path's query takes precedence, otherwise the original request's; the attribute is removed when absent); adds `INCLUDE_MAPPING` (Servlet 4.0+, an `HttpServletMapping`); include now restores all six `INCLUDE_*` values on exit (including the exception path), so nested includes get the outer values back and nothing lingers |
| non-`void` method returning `null` | not marked handled → the response was never flushed (the client waited until timeout) | treated as a completed response with no body and marked handled (`void` methods unchanged) |
| plain `HandlerInterceptor` bean (`spring-web-mvc-support`) | registered globally automatically | **no longer** registered automatically (matching Spring MVC): register through `addInterceptors` or declare a `MappedInterceptor` (which also avoids the same instance being registered twice and running twice) |
| `postHandle` / `afterCompletion` traversal order | reverse | **same (forward) order** as `preHandle`; when `preHandle` returns early, the interceptors that did not pass still get `afterCompletion` in reverse |
| malformed multipart message | raw exception, connection closed | **400** and a graceful close (`TooLongFrameException` stays **413**) |
| streaming responses (`writeStream`) | read the `InputStream` on the event loop → a slow source (DB cursor, network stream) caused **head-of-line blocking** on I/O | the read is offloaded to the business pool (the default pool; ForkJoin when absent; **never** falling back to the event loop), written in 8KB chunks with writability-based backpressure |
| `Content-Type` for `writeFile` / `writeStream` | always `application/octet-stream` | respects the value the caller already set, otherwise derives it from the file extension (`writeFile`); `octet-stream` remains the last resort |
| `getOutputStream()` | returned a new instance every call | the same response returns **the same instance** (Servlet spec); invalidated and recreated after `rebind` |
| JSR-356 `Session.setMaxIdleTimeout` | only assigned a field (the idle timeout had no effect) | propagated to the Netty pipeline's `IdleStateHandler` (`<=0` removes idle detection) |
| per-frame WebSocket limit | fixed text 8KB / binary 64KB | new `WebSocketHandlerRegistration#setMessageSizeLimit` overrides it per path (defaults unchanged) |
| `WebSocketSession#getHandshakeHeaders()` | returned a custom `SpringHeadersAdapter` (case-sensitive; writes such as `add` were silently dropped) | returns a standard `HttpHeaders` (case-insensitive, multi-value writes work); `SpringHeadersAdapter` removed |
| `@WebFilter`'s `urlPatterns` | read without proxy/inherited sources → silently promoted to a global filter | looked up with `findMergedAnnotation` along superclasses and interfaces; `urlPatterns` really restricts the path |

- Related commits: `df3c238f`, `89385c3d`, `a1b0a509`, `756f31f8`, `1b1f33b3` (`54069908`, `e33d4a9b` lock them with E2E)

#### 7.9 Framing and Servlet reset semantics

| Item | Old behaviour | New behaviour |
|---|---|---|
| progressive output for an **HTTP/1.0** request | sent chunked frames (1.0 has no such framing, so the client took the chunk markers as body - a protocol violation) | degrades to **close-delimited**: no `Content-Length` / `Transfer-Encoding` in the response headers, an explicit `Connection: close`, and the body ends when the connection closes (matching Tomcat) |
| `resetBuffer()` / `reset()` on a committed response | silently cleared the buffer, so callers thought they could take back what had already been sent | throws `IllegalStateException` (Servlet spec §5.6); `forward` / `sendRedirect` check `isCommitted` first and are unaffected |
| `resetBuffer()` versus the Writer's encoding buffer | only the body buffer was cleared → content "written but not flushed" stayed in the encoding buffer and was flushed out by the before-commit callback, so the **discard semantics did not hold** | the encoding buffer is flushed into the body buffer before clearing, making the discard semantics real |

- Related commits: `e8e16f87`, `890f4345` (`09ddf4bc`, `335c8a17`, `a567726c`, `f68dd351` lock them with E2E)

#### 7.10 Async / SSE lifecycle and reference counting

| Item | Old behaviour | New behaviour |
|---|---|---|
| survival of the inbound buffer during async processing | not kept alive by `startAsync()` (relying on "the body is already materialised"); `getContentLength()` read `content()`, which during async could return 0 or a stale value, or even throw `IllegalReferenceCountException` | the async holder **acquires and releases explicitly**: exactly one `+1` in `startAsync()` and one `-1` when the response write terminates; the body length is **cached at construction**, so async phases never touch `content()` |
| cascading response-buffer release when the request is released | **every** `release()` cascaded (once after handing off to the business pool, once in `channelRead.finally`) → it could release the response buffer a business thread was writing and null it out (a cross-thread early-release race) | cascades only when the **refCnt reaches zero (the last one)**; the response side has extra fallbacks: the write-termination callback and a `release()` of in-flight responses on connection close |
| client disconnect (idle SSE / unfinished async) | no `LastHttpContent` was written and no chunk write failed, so the inbound reference held by the async holder was **never returned** (waiting for GC) | a connection-close exit path `releaseOnConnectionClose()` (triggered by `channelInactive`): lifecycle cleanup only, **without** invoking the business error handler (a client interruption is not an application error) |
| cancelling a reactive upstream subscription | relied on "the next delivery failing" → if the source stopped delivering after the disconnect (a hung long poll) it was **never cancelled**, leaving a running source behind for every disconnected client | a new "client disconnected" hook cancels the upstream subscription right away (no need to wait for the next delivery) |
| ownership of the terminating chunk (`LastHttpContent`) | `markStreamCompleted()` was `void`, so whoever wrote it also marked it → **double terminators** were possible (the encoder reset to INIT and then threw `EncoderException` on `LastHttpContent`; measured with HEAD + SSE) | `markStreamCompleted()` became a **preemptive `boolean`** (`true` = this call won the write; only one party can write); HEAD requests (committed but never in progressive output) **no longer** get a terminator |
| `StreamEmitter.send()` after completion | silently pushed to the pending list and delivered in bulk at `initialize()` → **a terminated stream still wrote data** | throws `IllegalStateException` (matching Spring's `ResponseBodyEmitter`) |
| writing a response body after the exchange ended | kept allocating pooled buffers (whose content could never be written) → ByteBuf leak | `getBuf()` logs **ERROR** and returns an **unpooled** discard buffer; when committed, `flushChunked`/`writeAndFlush` refuse to write and **release that buffer in place** (with a warning) |
| return value is a reactive type with no resolver claiming it | silently "200 + empty body" (the hardest degradation to diagnose) | logs a **WARN** about the missing `ReactiveAdapter` (most common cause: reactor not on the classpath) |

- Related commits: `fe722450`, `685eaa0c`, `61003c38`, `66204e80`, `11c3ae53` (`219f28a0`, `ebba0f80`, `03f820aa`, `4d838675`, `91d495ad`, `dad4294c`, `249efc50`, `0a346fa2` lock them with E2E / contract tests)

#### 7.11 Observable changes from the performance work (JFR-driven, equivalence first)

> This batch is mostly "equivalent transformations backed by JFR sampling"; almost all of it is invisible to
> users. The four rows below are the **observable** part.

| Item | Old behaviour | New behaviour |
|---|---|---|
| **invalid configuration values** | hot-path keys were **parsed lazily**: a bad value failed on first use (or fell back to the default), startup was unaffected | `ApplicationProperties` parses **eagerly once the Environment is ready** (matching Boot's `@ConfigurationProperties`), so a bad value **fails startup** (fail-fast). Runtime configuration refresh (Spring Cloud) stays field-tolerant: a field that fails to parse **keeps its old value** and warns, so a bad push does not interrupt the refresh |
| servlet-bridge `AsyncContext.start` hand-off | governed by `server.http.timeout` | under an **explicit** `pool.default-execute-mode=eventloop` it is **no longer** governed by `server.http.timeout`; the Servlet async-timeout semantics own it instead (marked in the code as a known remaining item). Other execution modes are unchanged.<br>Note: that key **defaults to `default` (the business pool)**, not `eventloop`, and enabling virtual threads (`spring.threads.virtual.enabled=true`) does not move execution either - it only changes the `default` pool's thread type (pool bounds, queue and rejection semantics are unchanged) |
| when the response timeout is armed | a timer was armed at the start of every request | armed **on demand** (as a fallback when a synchronous section ends uncommitted, or while waiting on async/streaming): a synchronous request that committed on the event loop no longer produces a schedule/cancel pair, and **streaming responses longer than `server.http.timeout` no longer log the "committed, refusing to write" WARN** (pure noise removal, no content rewritten) |
| `spring.mvc.publish-request-handled-events` default | `true` (publishing `ServletRequestHandledEvent` on every completed request) | **`false`** (**deliberately off in this project**, Boot's default is the opposite, `true`: the event is of no real use to most applications and publishing it per request is pure overhead). Applications that rely on it for monitoring/auditing must **enable it explicitly** (see the configuration reference) |

- Equivalence evidence (JFR numbers are in the individual commits): `@RequestParam` fast path **-21% CPU samples per request**, **-8.8%** allocations per GET; fast-path eligibility is decided **at construction** (an earlier "exceed the threshold mid-lookup and fall back" variant could be bypassed by an early return and defeat the hash-DoS defence; a regression test caught it); `canDeserialize` probing is cached per `mappingContext` (**constraint**: the default `ObjectMapper`'s capability set is treated as static, so changing it at runtime requires clearing both that key and `READ_JAVA_TYPE`/`WRITE_TYPE_SERIALIZABLE`; custom mappers are not cached, keeping the per-request switching semantics).
- Related commits: `f515ae43`, `b6091749`, `bf1b88c8`, `59dc0ecb`, `21c3f8b1`, `2ec4e7c6`, `f3f9fa43`, `1708ed48`, `392747f8`, `d8c382cd`, `26281555`, `580ac83b`, `0254200d`, `5c3a23d2`

#### 7.12 View layer: visible template changes and new extension points

| Item | Old behaviour | New behaviour |
|---|---|---|
| templates reading session data | could not read it (`IWebSession` returned `null`); and Thymeleaf 3.1 already removed the `#session` / `#request` expression objects | in a Servlet application (with `spring-web-servlet` on the classpath) `ServletWebExchangeProvider` supplies the **real** session / principal / cookie; session attributes are **injected as context variables** (**the model wins** on a name clash) → templates write `${user}` instead of `${session.user}` |
| where a template's `IWebExchange` comes from | constructed inside `ThymeleafWebContext`, hard-coded | new SPI **`WebExchangeProvider`**: `supports()` capability probe + `createExchange()` + `getOrder()`; the first `supports()==true` in ascending order wins, with a built-in default (lowest order, always supports) as the fallback. Wired through Spring's component container (**not** the JDK `ServiceLoader`, so it works with native-image/AOT) |
| `WebMvcConfigurer#addViewControllers` | **silently ignored** (no warning) | bridged by `WebMvcConfigurerBridge` (`ViewControllerRegistry` / `ViewControllerRegistration` + `ViewControllerInvoker`); callbacks that cannot be bridged log a **WARN** instead of passing silently |
| `spring.mvc.view.prefix` / `.suffix` | - | **new**, semantics matching Boot: prefix/suffix for **JSP views only**; template engines keep their own `spring.{engine}.prefix/suffix` |
| session persistence and forced encoding | - | `server.servlet.session.persistent` / `.store-dir` / `.persistent-exclude` (JDK serialization, one file per session, `transient` attributes are not written); `server.servlet.encoding.force` / `.force-request` / `.force-response` |
| configuration-centre refresh | a change needed a restart to take effect | listens for `EnvironmentChangeEvent` (**by class name**, so no compile-time Spring Cloud dependency) → clears the framework property cache; `WebContext.refreshProperties()` can also be called manually. A failed refresh keeps the old value per field (see 7.11) |

- Related commits: `e6088584`, `30d1bf12`, `6aa5bd5f`, `9b6779ac`, `4f7ebbbf`, `24d4f768`

---

## [3.5.8] - Unreleased

| Item | Old behaviour | New behaviour |
|---|---|---|
| `ServletContext.addServlet` / `addFilter` / `addJspFile` / `createServlet` / `createFilter` | returned `null` or did nothing, so callers could believe registration succeeded | throw `UnsupportedOperationException` (the signal the specification defines for a missing capability). **Migration**: register servlets/filters as Spring beans, or use `@WebServlet` / `@WebFilter` |
| `HttpServletRequest.startAsync()` called again within the same async cycle | returned the existing `AsyncContext` | throws `IllegalStateException`. **Migration**: drop the duplicate call; start a new cycle only after `dispatch` / `complete` |
| `HttpServletRequest.getDateHeader(name)` with a present but unparseable date | returned `-1`, indistinguishable from "header absent" | throws `IllegalArgumentException`. **Migration**: handle the exception as a parse failure; do not test for `-1` |
| `ServletContext.setSessionTrackingModes()` | empty implementation: both valid and invalid arguments were silently ignored | takes effect and validates (non-empty, must contain `COOKIE` or `SSL`); invalid arguments throw `IllegalArgumentException`. **Migration**: pass a set that includes `COOKIE`/`SSL`; URL-only configurations that used to be ignored now fail |
| `HttpServletResponse.getHeaders(name)` when the header is absent | returned `null` | returns an empty collection (Servlet 6.0 contract). **No migration needed**; removes an NPE risk |
| `HttpSession.setAttribute(name, null)` | threw `NullPointerException` (the underlying `ConcurrentHashMap` rejects null values) | equivalent to `removeAttribute(name)` (spec §7.5). **No migration needed**; code that relied on the exception should call `removeAttribute` instead |
| An exception thrown by a `ViewResolver` inside `ViewResolverRegistry.resolve()` | logged a WARN, skipped that resolver, and hid the root cause behind "unable to resolve view name" | throws `IllegalStateException` with the original exception as cause. **Migration**: fix the template or configuration error; do not rely on the "skip" behaviour |
| `ModelAndView` pointing at an unresolvable view name | silently returned 200 with an empty body | throws `IllegalArgumentException`, matching the String view-name path. **Migration**: make the view name resolvable, or handle it in an `@ExceptionHandler` |
| View response `Content-Type` and encoding | set independently by each engine; FreeMarker's `content-type` could disagree with the response byte encoding, and `redirect:` carried `text/html` | decided in one place by `ViewReturnValueResolver.applyContentType()`: a `charset` in the Content-Type is applied to the response encoding, and `redirect:` sets no Content-Type. **Migration**: implement `View.getContentType()` for custom headers |
| `RedirectView` status code | always set 302 | preserves a 3xx status specified by `ModelAndView`. **No migration needed** |
| `spring.beetl.mode` | declared but never read | removed. **No migration needed** |
| View-name resolution results | probed template existence and created a View on every request | cached by view name when `cache=true`, including unresolved results. **Migration**: after adding or replacing a template at runtime, call `ViewResolverRegistry.invalidateViewCaches()`, or disable template caching |
| An exception thrown mid-render | the response was already marked handled, so the 500 error response was rejected and the client received 200 plus a partial HTML page; an error view from an exception handler was appended to that partial page | the partial content is discarded and the exception is handled by `ExceptionRegistry` (a 500 error page or an exception-handler view). **No migration needed** |
| `spring.web.view.engine` values | unknown values and `none` mixed with other engines were silently ignored, potentially deferring failure to the first view request | startup throws `IllegalStateException`; `none` must be used alone. **Migration**: fix the value and use `none` on its own |
| Built-in JSP with `engine=none` | `JspViewResolver` was still auto-configured | built-in JSP is no longer auto-configured; custom `ViewResolver` beans are unaffected. **Migration**: do not set `none` when JSP is needed |
| `ViewControllerInvoker` `redirect:` response | carried a `text/html` Content-Type | sets no Content-Type, matching the other two paths. **No migration needed** |
| `spring.{thymeleaf,freemarker,beetl}.{cache,enabled}` values | values other than true/false were silently treated as false | startup throws `IllegalStateException` with the property name and actual value. **Migration**: use true/false |
| Custom Content-Type without a charset | the header was passed through unchanged while the Writer used the response charset, so the two could disagree | the header gains `;charset=<response charset>`. **Migration**: declare the charset explicitly in the property if precise control is needed |
| View names that already include the suffix (`hello.html` / `hello.btl`) | supported only by FreeMarker; Thymeleaf / Beetl produced `hello.html.html` / `hello.btl.btl` | all three built-in engines support them. **No migration needed** |
| Protocol-relative `redirect://host` target | prefixed with a non-empty context path, producing `/app//host` | used as an external target as-is. **No migration needed** |
| `IWebRequest#getServerPort()` for HTTPS without an explicit port | always returned 80 | returns 443. **No migration needed** |
| `@ResponseBody` return value with a preset `Content-Type` and no matching `HttpBodyConverter` | silently returned 200 with an empty body; the return value was dropped | throws `HttpMessageNotWritableException`, mapped to 500 by `ExceptionRegistry`. **Migration**: make the preset Content-Type match the return type (use `application/json` for objects), or write the response on the Servlet side and return `void` |


## [2.7.6] - 2026-09-10
### 1. Module split: `spring-web-support` split in two

**Change**: `spring-web-support` no longer exists; it is split into

- `spring-web-servlet`: pure Servlet bridge (Filter / Servlet / `HttpSession` / JSP / `@SessionAttribute`,
  i.e. official `spring-web` semantics), **zero SpringMVC dependency**;
- `spring-web-mvc-support`: SpringMVC compatibility bridge (`org.springframework.web.servlet.*` shims,
  `WebMvcConfigurer` / `HandlerInterceptor` / `RequestBodyAdvice` bridges), which **depends on `spring-web-servlet`**.

The starter's auto-configuration is split accordingly into `SpringWebServletAutoConfiguration` +
`SpringWebMvcSupportAutoConfiguration` (each activated by its own module's class condition).

**Impact**: projects depending on `spring-web-support` directly cannot resolve that coordinate.

**Migration**: replace the coordinate as needed — use `spring-web-servlet` when only Servlet semantics
(Filter / Session / JSP) are required, or `spring-web-mvc-support` when you keep using the
`org.springframework.web.servlet.*` shims or `WebMvcConfigurer`. Projects that only depend on
`spring-boot-starter-web` (this framework's starter) **need no change**.

### 2. Tightened `redirect:` semantics

**Change**: the `redirect:` prefix only applies to **view methods**. A `@ResponseBody` / `@RestController`
method returning the string `"redirect:/x"` is no longer hijacked into a 302 — it is serialized as a plain
string. When the target URL already carries a query string, `&` is used instead of a second `?`.

**Impact**: endpoints that relied on "return a string to get a 302" behave differently — the JSON body now
contains the string `"redirect:/x"`.

**Migration**: return the status and `Location` explicitly when you need a redirect:

```java
return ResponseEntity.status(302).location(URI.create("/target?a=1")).build();
```

### 3. View resolution failure is no longer silent

**Change**: an unresolvable view name used to produce an empty 200 response; it now throws
`IllegalArgumentException` (500 + error context), and `ViewResolver` failures are logged.

**Impact**: typos in view names, a missing template engine, or a wrong prefix used to look like an empty 200
and now surface as 500.

**Migration**: check the `spring.web.view.*` prefix/suffix and the template engine on the classpath; if you
really want "resolve failure ⇒ empty response", register a fallback `ViewResolver` explicitly.

### 4. Static resources accept GET only

**Change**: `ResourceRequestHandler` only accepts GET. HEAD is mapped to GET by the routing layer but returns
metadata only, **skipping file IO**.

**Impact**: other methods against a static-resource path are no longer served by the static handler
(they fall through to normal routing).

**Migration**: do not target static-resource paths with POST/PUT.

### 5. Production-grade Netty and pool defaults

**Change**: the following defaults moved from "developer friendly" to "production friendly":

| Key | Old default | New default | Notes |
|-----|-------------|-------------|-------|
| `server.netty.so-backlog` | 128 | **1024** | accept backlog, so bursts are not dropped |
| `server.netty.so-keepalive` | false | **true** | TCP keepalive, reclaims dead peers |
| `server.http.max-content-length` | 1MB | **4MB** | per-request body limit |
| `pool.queue-capacity` | unbounded | **100** (bounded) | the bounded queue is what makes `pool.max-pool-size` growth and "queue full → fast 503" effective |

**Impact**: the body limit is 4× larger; pool queueing changed from unbounded (unlimited backlog, rising latency)
to bounded (immediate 503 once full).

**Migration**:

- applications relying on unbounded queueing to absorb spikes should set `pool.queue-capacity` explicitly
  (or raise `pool.max-pool-size`) and accept the 503 semantics;
- set `server.http.max-content-length` explicitly if you want a tighter body limit.

---

## [2.7.4] - 2026-08-06
### `JsonConverter` gained the abstract method `toJsonBytes(Object)`

**Change**: `io.springperf.web.json.JsonConverter` gained `byte[] toJsonBytes(Object obj)` to skip the
intermediate `String` copy when writing the response. The bundled Jackson / Fastjson converters implement it.

**Impact**: external implementations of `JsonConverter` (including anonymous ones) fail to compile or hit
`AbstractMethodError` at runtime.

**Migration**: implement it by delegating to the existing serialization:

```java
@Override
public byte[] toJsonBytes(Object obj) {
    return toJson(obj).getBytes(StandardCharsets.UTF_8);
}
```
