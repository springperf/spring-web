# Breaking Changes

> [中文版](../breaking-changes.md)

> This file is a **cumulative, cross-version ledger**: it does not belong to any one version. Whenever a
> release introduces a breaking change, **add a section at the end** and register a row in the version index
> above it. The **complete** item list for a release (new features and fixes included) lives in `CHANGELOG.md`.
>
> - **Scope**: items that force the user to act when upgrading - removals, renames, signature or visibility
>   changes, tightened semantics. New features and ordinary fixes do not belong here.
> - **Recording rule**: every item states either the **migration action** or **why none is needed**. Intentional
>   changes that the binary-compatibility gate (japicmp, configured in the root `pom.xml`) reports as breaking
>   must be matched one by one in this file - japicmp's report is this ledger's machine-checkable counterpart
>   (see `CONTRIBUTING.md`).
> - **Version numbers and dates** follow `CHANGELOG.md`; section headings carry the version, so reference a
>   section by its version, not by the numbered heading (numbers shift as versions are added).

## Version Index

| Version | Date | Subject |
|---------|------|---------|
| **3.5.7** | 2026-10-08 | Configuration keys aligned with Spring Boot / Tomcat: old key aliases removed, extension-point signatures and compat-layer classes adjusted |

---

## [3.5.7] - 2026-10-08

> **Why this version carries breaking changes in a minor release**: this list was drafted for "the next major
> version", but ships as **3.5.7** (a minor) - breaking changes inside a minor release are a **deliberate
> one-time exception** (the alignment work done between 3.5.6 and 3.5.7). That exception is registered
> item-by-item as an exemption for the binary-compatibility gate (japicmp); the rationale is in the comments
> around the japicmp configuration in the root `pom.xml`. **From here on, any new incompatibility fails the gate.**
>
> Companion workstream: `docs/feature/config-alignment-springmvc-tomcat.md` (that directory is gitignored and
> kept locally only).

### Background

During the `config-alignment` workstream (P0/P1) the framework gained a set of standard configuration keys
aligned with Spring Boot / Tomcat, and for a while kept the old keys as aliases (double read: new key first,
old key as fallback).

The decision was to **stop carrying historical compatibility**: the old keys are **removed outright** in this
release, keeping only the Boot-aligned names. This file is the release's "breaking changes list" for upgraders.

---

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
| non-`void` method returning `null` | not marked handled → the response was never flushed (the client waited until timeout) | treated as a completed response with no body and marked handled (`void` methods unchanged) |
| plain `HandlerInterceptor` bean (`spring-web-mvc-support`) | registered globally automatically | **no longer** registered automatically (matching Spring MVC): register through `addInterceptors` or declare a `MappedInterceptor` (which also avoids the same instance being registered twice and running twice) |
| `postHandle` / `afterCompletion` traversal order | reverse | **same (forward) order** as `preHandle`; when `preHandle` returns early, the interceptors that did not pass still get `afterCompletion` in reverse |
| malformed multipart message | raw exception, connection closed | **400** and a graceful close (`TooLongFrameException` stays **413**) |
| streaming responses (`writeStream`) | read the `InputStream` on the event loop → a slow source (DB cursor, network stream) caused **head-of-line blocking** on I/O | the read is offloaded to the business pool (the default pool; ForkJoin when absent; **never** falling back to the event loop), written in 8KB chunks with writability-based backpressure |
| `Content-Type` for `writeFile` / `writeStream` | always `application/octet-stream` | respects the value the caller already set, otherwise derives it from the file extension (`writeFile`); `octet-stream` remains the last resort |
| `getOutputStream()` | returned a new instance every call | the same response returns **the same instance** (Servlet spec); invalidated and recreated after `rebind` |
| JSR-356 `Session.setMaxIdleTimeout` | only assigned a field (the idle timeout had no effect) | propagated to the Netty pipeline's `IdleStateHandler` (`<=0` removes idle detection) |
| per-frame WebSocket limit | fixed text 8KB / binary 64KB | new `WebSocketHandlerRegistration#setMessageSizeLimit` overrides it per path (defaults unchanged) |
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


