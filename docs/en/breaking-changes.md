# Breaking Changes

> [中文版](../breaking-changes.md)

> This file is the **cumulative ledger for the 2.7.x line**: whenever a 2.7.x release introduces a breaking change,
> a new section is **appended** and one row is registered in the version index above.
> The **full** entry list per release (features and fixes included) lives in `CHANGELOG.md`.
>
> - **Scope**: only entries that force **user action** on upgrade — removals, renames, signature or visibility
>   changes, semantic tightening. New features and regular fixes are not listed.
> - **Requirement**: every entry states either a **migration action** or **why no migration is needed**.
> - **Versions and dates** follow `CHANGELOG.md`; section titles carry the version, so reference entries by version.

## Version Index

| Version | Date | Topic |
|---------|------|-------|
| **2.7.7** | 2026-10-13 | Configuration keys aligned with Spring Boot (old keys removed / renamed), response framing and Servlet `flush` semantics, conditional requests and Range, extension-point signatures |
| **2.7.6** | 2026-09-10 | Module split (`spring-web-support` → `spring-web-servlet` + `spring-web-mvc-support`), tightened `redirect:` semantics, view resolution no longer silent, production-grade Netty / pool defaults |
| **2.7.4** | 2026-08-06 | `JsonConverter` gained the abstract method `toJsonBytes(Object)` |

---

## [2.7.7] - 2026-10-13

### 1. Removals and renames

| Removed key | Replacement | Old → new semantics / migration |
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
| Management port HTTP/2 and body limit | Wrongly reused the main server keys | Dedicated keys `management.server.http2.enabled`, `management.server.max-content-length` |
| Management port equal to the main port | Any random port (`0`) counted as a conflict and **blocked startup** | Conflicts only when both are explicitly configured to the **same non-zero** port |
| `RequestDispatcher.forward` | Cleared written headers and **forced status 200** | Only resets the buffer: written headers and status are **preserved**; also fixes the swapped `FORWARD_SERVLET_PATH`/`FORWARD_PATH_INFO` and strips the leading `?` from `FORWARD_QUERY_STRING` |
| Non-`void` method returning `null` | Not marked handled → the response was never flushed (client hung until timeout) | Treated as "a completed response with no body" and marked handled (`void` methods unchanged) |
| Plain `HandlerInterceptor` beans (`spring-web-mvc-support`) | Auto-registered globally | **No longer** auto-registered (aligned with Spring MVC): register via `addInterceptors` or declare a `MappedInterceptor` |
| `postHandle` / `afterCompletion` traversal order | Reverse | Same (forward) direction as `preHandle`; when `preHandle` returns early, those that did not pass still get `afterCompletion` in reverse |
| Malformed multipart payloads | Closed the connection with a raw exception | **400** with a graceful close (`TooLongFrameException` remains **413**) |
| Streaming responses (`writeStream`) | Read the `InputStream` on the EventLoop → slow sources caused I/O **head-of-line blocking** | Read is offloaded to the business pool (default pool; a dedicated fallback pool when absent; **never** back to the EventLoop), written in 8KB chunks with writability backpressure |
| `Content-Type` of `writeFile` / `writeStream` | Always `application/octet-stream` | Respects a caller-set value; otherwise derived from the file extension (`writeFile`); falls back to `octet-stream` |
| `getOutputStream()` | Returned a new instance each call | Returns the **same instance** per response (Servlet spec); recreated after a `rebind` |
| JSR-356 `Session.setMaxIdleTimeout` | Assigned a field only (the idle timeout never took effect) | Propagated to the pipeline's `IdleStateHandler` (`<=0` removes idle detection) |
| WebSocket frame size limit | Fixed text 8KB / binary 64KB | New `WebSocketHandlerRegistration#setMessageSizeLimit` per path (defaults unchanged) |
| `@WebFilter` `urlPatterns` | The annotation was not read through proxies/superclasses → silently promoted to a global filter | Uses `findMergedAnnotation` across superclasses/interfaces; `urlPatterns` genuinely restricts the path |

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
