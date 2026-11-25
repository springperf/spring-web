# Changelog

> [中文版本](../../CHANGELOG.md)

This project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> This changelog is maintained in sync across `2.7.x`, `master` and `4.1.x`: every branch keeps the full history of released versions for cross-branch reference, while each section retains the context of its own branch.

## [2.7.8] - 20261126
> ⚠️ **This release contains breaking changes**: Servlet specification semantics, view error
> and content-negotiation behaviour, and the validation of several configuration values have
> changed. **Read the 2.7.8 section of the [breaking changes guide](breaking-changes.md) before upgrading.**

### Added

- **Dynamic Servlet / Filter registration**: `ServletContext.addServlet()` / `addFilter()` now return the spec-defined `ServletRegistration.Dynamic` / `FilterRegistration.Dynamic`, with configurable URL mappings, init parameters, `load-on-startup` and `async-supported`. Registration is open only during `ServletContextListener#contextInitialized()`; writes after that window throw `IllegalStateException`, and duplicate names or clashing URL patterns are reported instead of silently overridden. `addJspFile()` remains unsupported (JSPs are routed through `jsp:` views); `createServlet()` / `createFilter()` are available
- **Runtime Servlet listener registration**: `ServletContext.addListener()` now really works for `ServletContextListener`, `HttpSessionListener`, `HttpSessionAttributeListener` and `HttpSessionIdListener`; session-ID rotation notifies ID listeners. Listeners registered at runtime only receive events that occur afterwards — declare a Spring bean if you need full lifecycle callbacks
- **Production defaults for JSP**: new `server.servlet.jsp.development` (default `false`; JSP changes require a restart), `keep-generated`, `trim-spaces` and `init-parameters.*` (passed through to Jasper; explicit entries take precedence). Jasper instances are now Spring-managed, so JSP and tag classes get dependency injection
- **WebSocket compression extensions**: `setAllowExtensions(true)` now actually negotiates `permessage-deflate` (it used to be a no-op, so an unnegotiated compressed frame reached handlers as plain text). The negotiated result is exposed via `getExtensions()` / `Session#getNegotiatedExtensions()`, and the per-endpoint `messageSizeLimit` now takes effect
- **JSR-356 WebSocket semantics**: `RemoteEndpoint.Basic` sends synchronously; `getSendStream()` / `getSendWriter()` buffer and emit one complete message; `setBatchingAllowed()` / `flushBatch()` genuinely batch frames; `Session.addMessageHandler()` supports dynamic registration; custom `Configurator#getEndpointInstance` / `checkOrigin` are honoured; Ping / Pong frames now carry application data and reject payloads over 125 bytes
- **Batch accumulation window**: `@BatchMapping` gains `batchWindowMillis` (default `2`ms; set `0` to disable). Requests keep accumulating until `maxBatchSize` is reached or the window expires, so batch size is guaranteed by configuration rather than by accidental pool saturation. At medium-to-high concurrency this cuts IO calls several-fold while raising throughput; see the [batch guide](batch.md) for choosing a value (turn it off when in-flight requests cannot fill a batch)
- **Batch global defaults**: new `springperf.batch.*` keys (`window-millis` / `max-batch-size` / `consumer-size` / `ring-buffer-size` / `wait-strategy` / `backpressure`) tune every queue without editing each annotation. Precedence is "explicit annotation value > global key > annotation built-in default", read once at startup. Invalid values fail startup naming the key and the allowed values instead of falling back silently, and one startup line reports the effective values plus the keys that were actually set. Known boundary: an annotation that writes the built-in default cannot be told apart from an undeclared one, so the global key wins in that case. See the [batch configuration guide](batch.md#configuration-guide)
- **Runtime view cache invalidation**: new `ViewResolverRegistry.invalidateViewCaches()` makes templates deployed or replaced at runtime visible immediately, clearing the Thymeleaf / FreeMarker / Beetl internal template caches as well. View resolution results are cached by view name; `cache=false` still keeps hot reload
- **View configuration**: new `spring.{thymeleaf,freemarker,beetl}.order` and `spring.web.view.jsp.order` make engine resolution order deterministic and configurable, plus `spring.freemarker.enabled`; an optional `spring.web.view.redirect-hosts` allowlist for external redirect targets; the never-read `spring.beetl.mode` is removed
- **Auto-configuration switches**: new `springperf.openapi.enabled` and `springperf.swagger-ui.enabled` to disable the documentation endpoint and Swagger UI resources independently, and `springperf.web.mvc-conflict-check.enabled` to skip the startup conflict guard when Spring MVC is only pulled in transitively by a third-party library
- **Management port and SSL configuration**: `management.server.*` now resolves consistently as management-specific value → main `server.*` → default, so HTTP/2, request body limits and SSL can be overridden per setting (the SSL enable flag follows the same fallback). SSL settings are bound through Spring Boot's `Ssl` model, so an invalid `client-auth`, blank keystore or missing credentials fail at startup
- **Server-ready timing**: `WebServerInitializedEvent` is now published once Netty has actually bound the port (instead of at `ApplicationReadyEvent`), so service registries (Nacos / Eureka / Consul) observe readiness at the right time
- **`StreamingResponseBody` support**: returning a `StreamingResponseBody` (including `ResponseEntity<StreamingResponseBody>`) now streams for real — `writeTo` runs on the business thread pool, bytes are framed in 8KB chunks over the existing streaming channel, and the client can read while the producer is still writing; a single concrete media type declared via `produces` is written as the `Content-Type`, and HEAD sends headers only. Previously the type had no resolver and ended in a 500 content-negotiation failure

### Changed

- **Servlet specification semantics tightened**: a second `startAsync()` within one async cycle throws `IllegalStateException`; `complete()` is idempotent and `isAsyncStarted()` returns `false` after `complete()` / `dispatch()`; `DispatcherType` is `ASYNC` during `dispatch()`; `addListener(listener, req, resp)` events carry the registered req/resp; `getHeaders(name)` returns an empty collection when the header is absent; `getDateHeader()` throws `IllegalArgumentException` for a present but unparseable date; `setAttribute(name, null)` equals `removeAttribute`; `setSessionTrackingModes()` now takes effect and validates (non-empty, must contain `COOKIE` or `SSL`)
- **View errors are no longer silent**: resolver exceptions keep their cause instead of being skipped; an unresolvable `ModelAndView` throws instead of returning a blank 200; an exception mid-render no longer yields 200 plus partial HTML — the buffered output is discarded and the exception is handled (500 or a custom error page); `redirect:` no longer carries `text/html`, and a 3xx status set on `ModelAndView` is preserved
- **Content-negotiation failures are explicit**: an `@ResponseBody` return value with no matching converter now yields 500 instead of a silent 200 with an empty body, on both paths — a preset `Content-Type` and a media type negotiated from `produces`. Only the first path was covered before, so values going through `produces` were still dropped silently and not even the declared Content-Type reached the client. Matches Spring MVC semantics
- **Fewer allocations on the view render hot path**: with no session, the Thymeleaf context reads model variables directly instead of copying them into a `HashMap` on every request; application attributes allocate their map on first write; FreeMarker resolution caches are bucketed by locale, removing the per-request `viewName + locale` key; parsed Content-Type values and `redirect:` `RedirectView` instances are reused instead of being rebuilt per request
- **View output no longer allocates an 8 KB encoding buffer**: all three engines now encode in blocks straight into the response body instead of allocating a fixed 8 KB buffer per render. Measured per-request allocation: Thymeleaf 30.3 KB → 21.9 KB, FreeMarker 24.6 KB → 17.3 KB (same JFR setup, including the sample app's own logging). Encoding semantics still match `OutputStreamWriter`: unmappable characters are replaced, `close()` closes the underlying stream, and surrogate pairs split across blocks are preserved
- **External redirect allowlists are no longer parsed per request**: the `spring.web.view.redirect-hosts` value is cached per configuration string and a changed value takes effect immediately without a restart; a 3-host allowlist drops from ~660 B to ~5 B allocated per request
- **Startup validation for configuration**: unknown `spring.web.view.engine` values, `none` mixed with other engines, invalid `spring.thymeleaf.mode` / `spring.freemarker.content-type` values, and non-`true`/`false` values for engine `cache` / `enabled` now abort startup instead of being silently ignored; `engine=none` also disables built-in JSP auto-configuration (custom `ViewResolver` beans are unaffected)
- **Batching window on by default**: compared with 2.7.7, an `@BatchMapping` method that leaves `batchWindowMillis` undeclared now accumulates requests in a 2ms window (batch size guaranteed by the window, at most one extra window of per-request latency). If traffic is low and you want the old flush-as-soon-as-the-consumer-catches-up behaviour, set `batchWindowMillis = 0` explicitly; the startup warning now appears only when the window is explicitly disabled
- **API consolidation (binary incompatible)**: `SpringHeadersAdapter` (the WebSocket header adapter) is removed by the split refactor and the `NettyWebSocketSession` constructor now takes Spring's `org.springframework.http.HttpHeaders` directly; the never-read `ViewProperties.BEETL_MODE` / `BEETL_MODE_DEFAULT` constants are removed together with `spring.beetl.mode`; `WebServerInitializedEventAutoConfiguration#webServerInitializedEventPublisher(...)` changes signature (the `NettyHttpServer` parameter is dropped; the event is now carried by `NettyServerStartedEvent` once the port is bound). Migration notes are in the 2.7.8 section of the [breaking changes guide](breaking-changes.md)
- **Streaming queue capacity is configurable and lowered by default**: added `server.http.stream-max-queued-events` (default `4096`); the SSE / byte-stream sender's queue is resolved at connection setup instead of being hardcoded to `65536`. JCTools allocates the array eagerly, so per-connection heap drops from ~256KB to ~16KB (measured 251.9MB → 16.7MB for 1000 long-lived connections); A/B benchmarks show 314.7KB → 95.8KB allocated per request (−69.6%) with no throughput regression. Non power-of-two values are rounded up; values below 2 fail startup. Also fixed a deadlock (30s timeout) that appeared after lowering capacity when a synchronous Publisher's `highWaterMark` exceeded the queue room

### Fixed

- **Async timeouts now end in a stable 503**: after a `WebAsyncTask` / `Callable` timeout, the `InterruptedException` thrown by the cancelled task raced the timeout result for the response slot — clients could get a 500, and a task that ignores interrupts could even return a late 200 with the real result, making the timeout useless. Once the timeout fires, any task result arriving afterwards is now discarded, so the 503 promised since 2.7.7 is delivered consistently
- **Small batching windows now take effect as configured**: `batchWindowMillis` values such as 2~5ms were rounded up to the timer granularity, so fast-IO workloads lost throughput and low-traffic requests waited for nothing. The window now fires at its configured value, so `maxBatchSize` aggregation works as intended
- **Session cookies**: the default Servlet session cookie (`maxAge=-1`) is no longer emitted as `Max-Age=-1` / `Expires=<now>`, which made browsers expire it immediately
- **Persistent sessions**: with file-backed session storage, saving no longer runs on the network thread and stalls other connections on that thread; shutdown waits up to 5 seconds for in-flight saves
- **405 and shutdown requests**: the 405 `Allow` header now aggregates every available method; requests rejected during graceful shutdown return 503
- **Batch shutdown and completion safety nets**: the drain is bounded to 5 seconds (shutdown could previously hang indefinitely); batches rejected after the worker pool closed now fail each request instead of vanishing; requests left uncompleted when a batch method returns fail immediately instead of hanging until the 30-second timeout
- **HTTP date headers**: RFC 850 / 1036 two-digit years are now accepted (previously rejected as unparseable)
- **Forward and include**: `INCLUDE_QUERY_STRING` holds only the query string per the spec, and `INCLUDE_SERVLET_PATH` drops the query; `INCLUDE_REQUEST_URI` now reports the target resource (contextPath + target path) instead of the original request URI; the Servlet 4.0+ `INCLUDE_MAPPING` / `FORWARD_MAPPING` attributes are populated; `INCLUDE_*` attributes no longer linger after include returns (nested includes get the outer values back)
- **HTTP/2**: fixed a null pointer when the h2c preface arrives fragmented
- **Uploads and streaming responses**: concurrent multipart parsing no longer duplicates entries; closing an upload stream, HEAD requests and response-header-limit degradation no longer leak streams, file handles or buffers
- **HEAD requests against streaming endpoints**: when a `HEAD` request hit a handler returning an SSE / streaming value, the response had already been completed as headers-only per HEAD semantics, yet the sender was still started and wrote content frames to the finished response — one `EncoderException: unexpected message type: DefaultHttpContent, state: 0` ERROR per request, which also cancelled the upstream subscription. A HEAD request now starts no stream at all; the headers are still returned
- **Direct memory leak on the upload error path**: when an upload failed to parse, or was rejected for exceeding the declared length, direct memory roughly the size of the request body was never reclaimed and kept growing over time; oversized uploads could also end up as a dropped connection instead of a 413
- **Memory leak when a client disconnects early**: if the client went away before async processing (including SSE) actually started, that request's buffer and its async lifecycle were never released, so direct memory only grew in a long-running service
- **WebSocket frame handling**: fragmented frames are aggregated per RFC 6455 before reaching handlers; the close callback fires exactly once across all disconnect paths; handshake failures release the request; binary sends no longer consume the caller's `ByteBuffer`; unfragmented messages and batched sends honour size limits and the backpressure watermark
- **Redirects and encoding**: protocol-relative `//host` targets are no longer prefixed with the context path; HTTPS without an explicit port reports 443 to templates (so absolute URLs are correct); view names already carrying a suffix work in all three engines; a custom Content-Type without a charset gains the actual response charset

### Security

- **SSE protocol injection**: line breaks in `id` / `event` are rejected before writing, so upstream values cannot forge extra SSE fields (previously dependent on a Spring patch-level behaviour)
- **External redirect allowlist**: the optional `spring.web.view.redirect-hosts` restricts external redirect targets and blocks destinations with confusing userinfo

## [3.5.8] - 20261125 (expected)
> ⚠️ **This release contains breaking changes**: Servlet specification semantics, view error
> and content-negotiation behaviour, and the validation of several configuration values have
> changed. **Read the 3.5.8 section of the [breaking changes guide](breaking-changes.md) before upgrading.**

### Added

- **Dynamic Servlet / Filter registration**: `ServletContext.addServlet()` / `addFilter()` now return the spec-defined `ServletRegistration.Dynamic` / `FilterRegistration.Dynamic`, with configurable URL mappings, init parameters, `load-on-startup` and `async-supported`. Registration is open only during `ServletContextListener#contextInitialized()`; writes after that window throw `IllegalStateException`, and duplicate names or clashing URL patterns are reported instead of silently overridden. `addJspFile()` remains unsupported (JSPs are routed through `jsp:` views); `createServlet()` / `createFilter()` are available
- **Runtime Servlet listener registration**: `ServletContext.addListener()` now really works for `ServletContextListener`, `HttpSessionListener`, `HttpSessionAttributeListener` and `HttpSessionIdListener`; session-ID rotation notifies ID listeners. Listeners registered at runtime only receive events that occur afterwards — declare a Spring bean if you need full lifecycle callbacks
- **Production defaults for JSP**: new `server.servlet.jsp.development` (default `false`; JSP changes require a restart), `keep-generated`, `trim-spaces` and `init-parameters.*` (passed through to Jasper; explicit entries take precedence). Jasper instances are now Spring-managed, so JSP and tag classes get dependency injection
- **WebSocket compression extensions**: `setAllowExtensions(true)` now actually negotiates `permessage-deflate` (it used to be a no-op, so an unnegotiated compressed frame reached handlers as plain text). The negotiated result is exposed via `getExtensions()` / `Session#getNegotiatedExtensions()`, and the per-endpoint `messageSizeLimit` now takes effect
- **JSR-356 WebSocket semantics**: `RemoteEndpoint.Basic` sends synchronously; `getSendStream()` / `getSendWriter()` buffer and emit one complete message; `setBatchingAllowed()` / `flushBatch()` genuinely batch frames; `Session.addMessageHandler()` supports dynamic registration; custom `Configurator#getEndpointInstance` / `checkOrigin` are honoured; Ping / Pong frames now carry application data and reject payloads over 125 bytes
- **Batch accumulation window**: `@BatchMapping` gains `batchWindowMillis` (default `2`ms; set `0` to disable). Requests keep accumulating until `maxBatchSize` is reached or the window expires, so batch size is guaranteed by configuration rather than by accidental pool saturation. At medium-to-high concurrency this cuts IO calls several-fold while raising throughput; see the [batch guide](batch.md) for choosing a value (turn it off when in-flight requests cannot fill a batch)
- **Batch global defaults**: new `springperf.batch.*` keys (`window-millis` / `max-batch-size` / `consumer-size` / `ring-buffer-size` / `wait-strategy` / `backpressure`) tune every queue without editing each annotation. Precedence is "explicit annotation value > global key > annotation built-in default", read once at startup. Invalid values fail startup naming the key and the allowed values instead of falling back silently, and one startup line reports the effective values plus the keys that were actually set. Known boundary: an annotation that writes the built-in default cannot be told apart from an undeclared one, so the global key wins in that case. See the [batch configuration guide](batch.md#configuration-guide)
- **Runtime view cache invalidation**: new `ViewResolverRegistry.invalidateViewCaches()` makes templates deployed or replaced at runtime visible immediately, clearing the Thymeleaf / FreeMarker / Beetl internal template caches as well. View resolution results are cached by view name; `cache=false` still keeps hot reload
- **View configuration**: new `spring.{thymeleaf,freemarker,beetl}.order` and `spring.web.view.jsp.order` make engine resolution order deterministic and configurable, plus `spring.freemarker.enabled`; an optional `spring.web.view.redirect-hosts` allowlist for external redirect targets; the never-read `spring.beetl.mode` is removed
- **Auto-configuration switches**: new `springperf.openapi.enabled` and `springperf.swagger-ui.enabled` to disable the documentation endpoint and Swagger UI resources independently, and `springperf.web.mvc-conflict-check.enabled` to skip the startup conflict guard when Spring MVC is only pulled in transitively by a third-party library
- **Management port and SSL configuration**: `management.server.*` now resolves consistently as management-specific value → main `server.*` → default, so HTTP/2, request body limits and SSL can be overridden per setting (the SSL enable flag follows the same fallback). SSL settings are bound through Spring Boot's `Ssl` model, so an invalid `client-auth`, blank keystore or missing credentials fail at startup
- **Server-ready timing**: `WebServerInitializedEvent` is now published once Netty has actually bound the port (instead of at `ApplicationReadyEvent`), so service registries (Nacos / Eureka / Consul) observe readiness at the right time
- **`StreamingResponseBody` support**: returning a `StreamingResponseBody` (including `ResponseEntity<StreamingResponseBody>`) now streams for real — `writeTo` runs on the business thread pool, bytes are framed in 8KB chunks over the existing streaming channel, and the client can read while the producer is still writing; a single concrete media type declared via `produces` is written as the `Content-Type`, and HEAD sends headers only. Previously the type had no resolver and ended in a 500 content-negotiation failure

### Changed

- **Servlet specification semantics tightened**: a second `startAsync()` within one async cycle throws `IllegalStateException`; `complete()` is idempotent and `isAsyncStarted()` returns `false` after `complete()` / `dispatch()`; `DispatcherType` is `ASYNC` during `dispatch()`; `addListener(listener, req, resp)` events carry the registered req/resp; `getHeaders(name)` returns an empty collection when the header is absent; `getDateHeader()` throws `IllegalArgumentException` for a present but unparseable date; `setAttribute(name, null)` equals `removeAttribute`; `setSessionTrackingModes()` now takes effect and validates (non-empty, must contain `COOKIE` or `SSL`)
- **View errors are no longer silent**: resolver exceptions keep their cause instead of being skipped; an unresolvable `ModelAndView` throws instead of returning a blank 200; an exception mid-render no longer yields 200 plus partial HTML — the buffered output is discarded and the exception is handled (500 or a custom error page); `redirect:` no longer carries `text/html`, and a 3xx status set on `ModelAndView` is preserved
- **Content-negotiation failures are explicit**: an `@ResponseBody` return value with no matching converter now yields 500 instead of a silent 200 with an empty body, on both paths — a preset `Content-Type` and a media type negotiated from `produces`. Only the first path was covered before, so values going through `produces` were still dropped silently and not even the declared Content-Type reached the client. Matches Spring MVC semantics
- **Fewer allocations on the view render hot path**: with no session, the Thymeleaf context reads model variables directly instead of copying them into a `HashMap` on every request; application attributes allocate their map on first write; FreeMarker resolution caches are bucketed by locale, removing the per-request `viewName + locale` key; parsed Content-Type values and `redirect:` `RedirectView` instances are reused instead of being rebuilt per request
- **View output no longer allocates an 8 KB encoding buffer**: all three engines now encode in blocks straight into the response body instead of allocating a fixed 8 KB buffer per render. Measured per-request allocation: Thymeleaf 30.3 KB → 21.9 KB, FreeMarker 24.6 KB → 17.3 KB (same JFR setup, including the sample app's own logging). Encoding semantics still match `OutputStreamWriter`: unmappable characters are replaced, `close()` closes the underlying stream, and surrogate pairs split across blocks are preserved
- **External redirect allowlists are no longer parsed per request**: the `spring.web.view.redirect-hosts` value is cached per configuration string and a changed value takes effect immediately without a restart; a 3-host allowlist drops from ~660 B to ~5 B allocated per request
- **Startup validation for configuration**: unknown `spring.web.view.engine` values, `none` mixed with other engines, invalid `spring.thymeleaf.mode` / `spring.freemarker.content-type` values, and non-`true`/`false` values for engine `cache` / `enabled` now abort startup instead of being silently ignored; `engine=none` also disables built-in JSP auto-configuration (custom `ViewResolver` beans are unaffected)
- **Batching window on by default**: compared with 3.5.7, an `@BatchMapping` method that leaves `batchWindowMillis` undeclared now accumulates requests in a 2ms window (batch size guaranteed by the window, at most one extra window of per-request latency). If traffic is low and you want the old flush-as-soon-as-the-consumer-catches-up behaviour, set `batchWindowMillis = 0` explicitly; the startup warning now appears only when the window is explicitly disabled
- **API consolidation (binary incompatible)**: `SpringHeadersAdapter` (the WebSocket header adapter) is removed by the split refactor and the `NettyWebSocketSession` constructor now takes Spring's `org.springframework.http.HttpHeaders` directly; the never-read `ViewProperties.BEETL_MODE` / `BEETL_MODE_DEFAULT` constants are removed together with `spring.beetl.mode`; `WebServerInitializedEventAutoConfiguration#webServerInitializedEventPublisher(...)` changes signature (the `NettyHttpServer` parameter is dropped; the event is now carried by `NettyServerStartedEvent` once the port is bound). Migration notes are in the 3.5.8 section of the [breaking changes guide](breaking-changes.md)
- **Streaming queue capacity is configurable and lowered by default**: added `server.http.stream-max-queued-events` (default `4096`); the SSE / byte-stream sender's queue is resolved at connection setup instead of being hardcoded to `65536`. JCTools allocates the array eagerly, so per-connection heap drops from ~256KB to ~16KB (measured 251.9MB → 16.7MB for 1000 long-lived connections); A/B benchmarks show 314.7KB → 95.8KB allocated per request (−69.6%) with no throughput regression. Non power-of-two values are rounded up; values below 2 fail startup. Also fixed a deadlock (30s timeout) that appeared after lowering capacity when a synchronous Publisher's `highWaterMark` exceeded the queue room

### Fixed

- **Async timeouts now end in a stable 503**: after a `WebAsyncTask` / `Callable` timeout, the `InterruptedException` thrown by the cancelled task raced the timeout result for the response slot — clients could get a 500, and a task that ignores interrupts could even return a late 200 with the real result, making the timeout useless. Once the timeout fires, any task result arriving afterwards is now discarded, so the 503 promised since 3.5.7 is delivered consistently
- **Small batching windows now take effect as configured**: `batchWindowMillis` values such as 2~5ms were rounded up to the timer granularity, so fast-IO workloads lost throughput and low-traffic requests waited for nothing. The window now fires at its configured value, so `maxBatchSize` aggregation works as intended
- **Session cookies**: the default Servlet session cookie (`maxAge=-1`) is no longer emitted as `Max-Age=-1` / `Expires=<now>`, which made browsers expire it immediately
- **Persistent sessions**: with file-backed session storage, saving no longer runs on the network thread and stalls other connections on that thread; shutdown waits up to 5 seconds for in-flight saves
- **405 and shutdown requests**: the 405 `Allow` header now aggregates every available method; requests rejected during graceful shutdown return 503
- **Batch shutdown and completion safety nets**: the drain is bounded to 5 seconds (shutdown could previously hang indefinitely); batches rejected after the worker pool closed now fail each request instead of vanishing; requests left uncompleted when a batch method returns fail immediately instead of hanging until the 30-second timeout
- **HTTP date headers**: RFC 850 / 1036 two-digit years are now accepted (previously rejected as unparseable)
- **Forward and include**: `INCLUDE_QUERY_STRING` holds only the query string per the spec, and `INCLUDE_SERVLET_PATH` drops the query; `INCLUDE_REQUEST_URI` now reports the target resource (contextPath + target path) instead of the original request URI; the Servlet 4.0+ `INCLUDE_MAPPING` / `FORWARD_MAPPING` attributes are populated; `INCLUDE_*` attributes no longer linger after include returns (nested includes get the outer values back)
- **HTTP/2**: fixed a null pointer when the h2c preface arrives fragmented
- **Uploads and streaming responses**: concurrent multipart parsing no longer duplicates entries; closing an upload stream, HEAD requests and response-header-limit degradation no longer leak streams, file handles or buffers
- **HEAD requests against streaming endpoints**: when a `HEAD` request hit a handler returning an SSE / streaming value, the response had already been completed as headers-only per HEAD semantics, yet the sender was still started and wrote content frames to the finished response — one `EncoderException: unexpected message type: DefaultHttpContent, state: 0` ERROR per request, which also cancelled the upstream subscription. A HEAD request now starts no stream at all; the headers are still returned
- **Direct memory leak on the upload error path**: when an upload failed to parse, or was rejected for exceeding the declared length, direct memory roughly the size of the request body was never reclaimed and kept growing over time; oversized uploads could also end up as a dropped connection instead of a 413
- **Memory leak when a client disconnects early**: if the client went away before async processing (including SSE) actually started, that request's buffer and its async lifecycle were never released, so direct memory only grew in a long-running service
- **WebSocket frame handling**: fragmented frames are aggregated per RFC 6455 before reaching handlers; the close callback fires exactly once across all disconnect paths; handshake failures release the request; binary sends no longer consume the caller's `ByteBuffer`; unfragmented messages and batched sends honour size limits and the backpressure watermark
- **Redirects and encoding**: protocol-relative `//host` targets are no longer prefixed with the context path; HTTPS without an explicit port reports 443 to templates (so absolute URLs are correct); view names already carrying a suffix work in all three engines; a custom Content-Type without a charset gains the actual response charset

### Security

- **SSE protocol injection**: line breaks in `id` / `event` are rejected before writing, so upstream values cannot forge extra SSE fields (previously dependent on a Spring patch-level behaviour)
- **External redirect allowlist**: the optional `spring.web.view.redirect-hosts` restricts external redirect targets and blocks destinations with confusing userinfo

## [2.7.7] - 20261013
> ⚠️ **This release contains breaking changes**: configuration keys, response framing, Servlet
> `flush` semantics, conditional requests / Range, and extension-point signatures all changed —
> read the [breaking changes](breaking-changes.md) before upgrading.

### Added

- **Static resources and views**: new pluggable SPI `WebExchangeProvider` (`supports()` capability probe + `createExchange()` + `getOrder()`), so templates can reach the **real** session and principal; view/encoding keys unified
- **HTTP protocol semantics**: conditional requests (`If-None-Match` / `If-Modified-Since`), byte ranges (`Accept-Ranges`, single `206`, multi-part `multipart/byteranges`) and response framing tightened per RFC 7231/7232/7233
- **Server configuration aligned with Spring Boot**: connection and request limits, keep-alive timeout / request count, multipart limits, error-response policy, forwarded-header strategy and more (see the configuration manual and the 2.7.7 section of the breaking changes)
- **Session persistence**: `server.servlet.session.persistent` / `.store-dir` / `.persistent-exclude` (JDK serialization, one file per session, `transient` attributes not persisted) plus the deserialization allow-list key `server.servlet.session.persistent-deserialization-filter`
- **Configuration refresh**: listens for `EnvironmentChangeEvent` to drop the framework property caches, so config-centre changes need no restart

### Changed

- **Response framing**: `flush(true)` changed from one-shot to progressive chunked output; writing may continue after commit, with `endStream()` emitting the terminating chunk
- **Servlet `PrintWriter`**: `print` / `println` no longer auto-commit (aligned with Tomcat `autoFlush=false`); call `flush()` / `flushBuffer()` explicitly
- **Extension-point signatures changed** (source-incompatible; see "Extension-point signature changes" in the 2.7.7 section of the breaking changes)
- **Session, redirect and conditional-request error mappings** adjusted; management port and forwarding semantics adjusted (both in the 2.7.7 section of the breaking changes)

### Security

- **Request parameter cap**: new `server.max-parameter-count` (default 10000) guarding against hash-collision DoS

### Optimized

- **Hot-path allocation**: a set of JFR-driven optimizations (request dispatch, attribute lookup, read-only response-header view caching), lowering per-request allocation and CPU
- **Stream copy pool**: the fallback stream copy uses a dedicated daemon pool instead of degrading to a parallelism-limited shared pool

### Fixed

- **Session concurrency**: stale session sweeping now removes entries conditionally under the write lock, fixing "a just-swept stale session is written back concurrently and resurrects after restart"; session writes serialized, atomic invalidation via `tryInvalidate`
- **Async / SSE lifecycle**: `DeferredResult` terminal state fires exactly once, timeout tasks cleaned up promptly, `ByteBuf` reference counts paired; `markStreamCompleted` is now preemptive, ruling out double terminating chunks
- **Servlet / websocket**: `;jsessionid=` read back from the URI (cookie first), SameSite values normalised, JSR-356 endpoint lifecycle and session teardown
- **Views and bridging**: Thymeleaf session variables injected, FreeMarker adaptation, `WebMvcConfigurer#addViewControllers` bridged (unbridgeable callbacks warn instead of staying silent)
- **core / http**: error input handling, log lock, file name guard, media type order, lazy request state and head flag visibility

### Build

- **Gates aligned with master**: adopted `mvnw`, enforcer `requirePluginVersions`, javadoc doclint (JDK 8 semantics) and the `check-docs` / symbol checks, clearing the doclint findings
- **CI**: adopted master's workflow set (CI / CodeQL / dependabot / coverage-aggregate)
- **Coverage**: regenerated `coverage-report.md` with the merged unit + E2E scope

### Docs

- **Sync ledger**: the 2.7.x sync-gap and migration checklists with per-file verdicts
- **Fact fixes**: removed jakarta / Spring Boot 3 wording that leaked into this branch, plus dangling symbol references

## [4.1.7] - 20261008
> **About this version**: `4.1.x` is a downstream branch **split out of `master`** (pure Spring Boot 3.5.x)
> to release independently; its first release is **`4.1.7`** - Spring Boot 4.0 / 4.1 + Spring Framework 7 +
> Jackson 3, with no 3.5.x support. Its notes therefore cover the split and adaptation itself; the rest
> matches master's `3.5.7`, reproduced in full right after this section.

### Changed

- **Branch positioning**: this branch (`4.1.x`) is **dedicated to Spring Boot 4.1** (`-Pspring-boot-4.0` switches to 4.0) and **no longer supports 3.5.x** — the "one codebase for both" goal is served by the division between master (pure 3.5.x) and this branch. 4.1 is the default build; no `-P` needed. The differences and sync rules are in the [4.1.x adaptation guide](../../.agent/context/4.1.x-adaptation-checklist.md)
- **Compatibility layer removed wholesale**: the eight MethodHandle version branches in `WebHttpHeaders`, the ASM runtime bytecode generation in `Boot4WebServerInitializedEventBridge`, the reflective method-name probing in `ResponseStatusExceptionAdapter`, and `ListenableFutureAdapter` (Spring 7 no longer has the type; the old implementation was dead code) are all gone — the code now targets the Spring 7 / Jackson 3 APIs directly
- **JSON stack migrated to Jackson 3**: `com.fasterxml.jackson.databind` → `tools.jackson.databind` (annotations still `com.fasterxml.jackson.annotation`). Jackson 3 flips the default of `FAIL_ON_NULL_FOR_PRIMITIVES` from `false` to `true`; the framework disables it explicitly via `JacksonMappers.defaultMapper()` to keep behaviour aligned with master
- **Netty now follows the Boot 4 BOM**: `4.1.137.Final` → `4.2.15.Final` (Boot 4's baseline). The project's own pins on 14 Netty artifacts were removed
- **Spring AI upgraded to 2.x**: 1.x targets Boot 3, and its `OpenAiApi` called `HttpHeaders.addAll(MultiValueMap)`, whose signature changed in Spring 7

### Fixed

- **Forwarded-header parsing**: added support for `X-Forwarded-Host` / `X-Forwarded-Port` / `X-Forwarded-Prefix`. Previously only the scheme (proto) was parsed, with host/port relying on Boot's `ForwardedHeaderFilter` — which has nowhere to attach, since Boot 4's `spring-boot-starter-web` no longer pulls in a servlet container
- **`+json` suffix media types**: prefixed `+json` types such as `application/vnd.spring-boot.actuator.v2+json` did not match the JSON converter, so Actuator endpoints returned 200 with an empty body
- **String request bodies**: `@RequestBody String` always failed with 400 under `application/json` (the framework never registered a `StringHttpMessageConverter`); it is now registered and ordered ahead of Jackson (Spring semantics: Strings are read as-is, not parsed as JSON)

## [3.5.7] - 20261008
> ⚠️ **This release contains breaking changes**: configuration keys, response framing,
> Servlet `flush` semantics, conditional requests / ranges and extension-point signatures
> have all changed. **Read the [breaking changes guide](../breaking-changes.md) before upgrading.**

### Added

- **Static resources & views**: pluggable `WebExchangeProvider` SPI so templates can access the real session and principal; view/encoding configuration keys unified
- **HTTP protocol semantics**: conditional requests (`If-None-Match` / `If-Modified-Since`), byte ranges (including multi-range `multipart/byteranges` and `Accept-Ranges`), and response framing now follow RFC 7231/7232/7233
- **Server configuration aligned with Spring Boot**: a set of keys covering connection and request limits, keep-alive timeout/request count, multipart limits, error-response policy and i18n binding (see the configuration reference and the 3.5.7 section of the [breaking changes guide](../breaking-changes.md))
- **Batch processing**: `@BatchMapping` can run on virtual threads (JDK 21+), preserving the `consumerSize` bound and backpressure semantics
- **Configuration refresh**: property snapshots and refresh driven by Spring Cloud environment changes

### Changed

- **Branch strategy**: `master` is now **pure Spring Boot 3.5.x** — the Spring Boot 4 / Spring Framework 7 compatibility layer that had accumulated on it (the version branches and MethodHandles in `WebHttpHeaders`, `MediaTypeUtils`, `ResponseStatusExceptionAdapter`, `Boot4WebServerInitializedEventBridge`, SB4 Maven profiles, etc.) has been removed entirely. 4.x adaptation now lives on the `4.1.x` branch; see the [4.1.x adaptation guide](../../.agent/context/4.1.x-adaptation-checklist.md)
- **Response framing**: `flush(true)` changed from one-shot to progressive (chunked) output
- **Servlet `PrintWriter`**: `print` / `println` no longer auto-commit (matching Tomcat's `autoFlush=false`)
- **Extension-point signature changes** (source-level incompatible; see the extension-point signatures part of the 3.5.7 section in the [breaking changes guide](../breaking-changes.md))
- **Error mapping** for sessions, redirects and conditional requests adjusted; management-port and forwarding semantics adjusted (both in the 3.5.7 section of the [breaking changes guide](../breaking-changes.md))

### Security

- **Session fixation protection**: the session ID is rotated around login, defeating fixation attacks
- **Multipart DoS protection**: fail-fast on the declared `Content-Length` plus a streaming byte cap (covering chunked), returning 413 instead of dropping the connection
- **Fastjson deserialization hardening**: `SupportAutoType` is off by default and `ErrorOnNotSupportAutoType` on, blocking `@type` gadget chains
- **Request parameter cap**: `server.max-parameter-count` defaults to 10000, guarding against hash-collision DoS

### Performance

- **Hot-path allocations**: a set of JFR-driven optimizations (request dispatch, attribute lookup, cached read-only views in `WebHttpHeaders`, …) reducing per-request allocation and CPU
- **Gains from removing the compat layer**: the eight `MultiValueMap` methods on `WebHttpHeaders` were invoked through `MethodHandle` + varargs `invokeWithArguments` (allocating an `Object[]` per call), and `get()` / `entrySet()` / `keySet()` all sit on the per-request hot path. They now call `super.*()` directly

### Fixed

- **Session concurrency**: expired-session sweeps now remove the entry conditionally under the write lock, fixing the case where a just-swept session was written back by a concurrent save and resurrected on restart; session writes are serialised
- **Async / SSE lifecycle**: `DeferredResult` reaches its terminal state exactly once, timeout tasks are cleaned up promptly, and `ByteBuf` reference counts are paired — avoiding leaks and premature release on long-lived connections
- **Stale auto-configuration registration**: `AutoConfiguration.imports` still registered the removed `Boot4WebServerInitializedEventAutoConfiguration`, which caused Spring context startup failures
- A batch of long-standing defects around **404/405 shared-state pollution**, the **filter-chain exception path**, **`WebContext` lifecycle** and **routing boundaries**

## [2.7.6] - 20260910
### Added

- **View rendering module `spring-web-view`**: Model/View promoted to first-class citizens in the request pipeline (`ModelContext` request-scoped container + `View`/`ViewResolver` SPI), with `@ControllerAdvice @ModelAttribute` pre-population, local `@ModelAttribute` methods, `@PathVariable`/`BindingResult` merging; multi-engine (Thymeleaf / FreeMarker / Beetl) coexistence, `spring.web.view.engine` selection; `redirect:` prefix views and `ViewReturnValueResolver` (Thymeleaf 3.0 adapted to plain `IContext`, FreeMarker 2.3.32)
- **JSP rendering**: Apache Jasper + JSTL integration (`JasperJspServlet`), `JspViewResolver` provides `jsp:` prefix / `.jsp` suffix view names and auto-registers `/**/*.jsp` routes; `spring-web-mvc-support` bridges `ModelAndView` / JSP views (javax.servlet + JSTL 1.2 adaptation)
- **Servlet object routing**: `SupportServletRegistry` scans Servlet beans / `@WebServlet` `url-pattern` annotations and registers them as framework routes; `ServletInvoker`, `PerfServletConfig`
- **JSR-356 `@ServerEndpoint` bridge** (`spring-web-websocket`): standard WebSocket endpoint annotation support covering `@OnOpen/@OnMessage/@OnClose/@OnError`, `@PathParam`, Decoder/Encoder, custom `Configurator`, `JsrWebSocketContainer` endpoint lifecycle (javax.websocket adaptation)
- **Method-level interceptors and `@SessionAttributes`**: `@ControllerAdvice` interceptors match by handler controller type; `@SessionAttributes` syncs Model attributes across requests with `SessionStatus` cleanup, `@SessionAttribute`/`SessionStatus` parameter auto-wiring
- **HEAD semantics (RFC 7231 §4.3.2)**: auto-maps to GET when no explicit HEAD handler, suppresses body but keeps real `Content-Length` (resource/stream/byte paths covered)
- **Linux native transport**: new `server.netty.transport=auto/nio/epoll` (default auto), auto-enables native epoll on Linux
- **Production Netty defaults**: `SO_BACKLOG` 1024, `SO_KEEPALIVE` true, `max-content-length` 4MB; business thread pool default bounded queue of 100 so `maxPoolSize` scaling and 503 fast-fail actually take effect

### Changed

- **`spring-web-support` module split**: split into `spring-web-servlet` (pure Servlet bridge: Filter/Servlet/HttpSession/JSP/`@SessionAttribute` etc. with official spring-web semantics, zero SpringMVC dependency) and `spring-web-mvc-support` (SpringMVC compatibility bridge: `org.springframework.web.servlet.*` rewritten classes, `WebMvcConfigurer`/`HandlerInterceptor`/`RequestBodyAdvice` bridging). Users needing only Servlet compatibility depend solely on `spring-web-servlet`; `spring-web-mvc-support` depends on `spring-web-servlet`. Starter auto-config split into `SpringWebServletAutoConfiguration` + `SpringWebMvcSupportAutoConfiguration` (activated per-module by class conditions)

### Security

- **Multipart DoS protection**: `SupportMultipartResolver` fail-fasts on declared `Content-Length` and enforces a streaming byte ceiling (covers chunked), returning 413 on overflow aligned with `HttpObjectAggregator` instead of closing the connection
- **Fastjson deserialization hardening**: `SupportAutoType` disabled by default with `ErrorOnNotSupportAutoType` enabled, blocking `@type` gadget attacks
- **Session Fixation protection**: session fixation attack protection and security audit fixes

### Optimized

- **Component registration concurrency**: `WebComponentContainer` now uses `ConcurrentHashMap` + atomic `compute` merge, no read-write races or leftover destroy on runtime registration
- **Connection metric isolation**: `NettyMetricsHandler` changed from shared singleton to per-server instance; main and management port connection counts tracked separately
- **Locale argument resolution**: resolves by request `Accept-Language` when no thread-bound Locale, respecting interceptor/ControllerAdvice overrides, without `ThreadLocal` (zero thread-pool residue)
- **Exception injection fix**: `@ExceptionHandler` injects the most specific cause-chain exception compatible with the parameter type, avoiding spurious `ClassCastException` 500s

### Fixed

- **404/405 shared-state pollution**: per-request `StacklessResponseStatusException` (stack trace disabled, zero overhead) so `@ExceptionHandler` header/body mutations no longer affect subsequent requests
- **Filter-chain exception path**: initializes request context and runs full exception handling and `afterCompletion`, avoiding null interceptors / leftover ThreadLocal
- **`WebContext` lifecycle**: cleans up initialized components on startup failure while keeping fail-fast retry; child component state resets after `destroy()`, supporting stop/restart re-init
- **Async fallback**: lazily creates and reuses a `SimpleAsyncTaskExecutor` when no default business pool is configured and the method specifies no executor, eliminating NPE
- **Route boundaries**: `PrefixPathRouterOptimizer` explicitly rejects when no prefix index (avoids array OOB); `SuffixPathRouterOptimizer` boundary guards
- **Async & stream edges**: asynchronous handler exception handling, `AbstractNettyStreamSender` completion boundary hardening
- **Static resource method tightening**: `ResourceRequestHandler` only allows GET (HEAD auto-mapped by the route layer returning metadata only, skipping file IO)
- **JSR-356 lifecycle hardening**: fallback cleanup of Decoder/Encoder on `@OnOpen` failure / non-standard close codes; `@OnClose` exceptions no longer skip codec cleanup
- **View resolution failure no longer silent**: unresolvable view names throw `IllegalArgumentException` (500 + error context) instead of empty 200; `ViewResolver` parse exceptions are logged
- **`redirect:` semantics alignment**: only honored for view methods, no longer hijacking `@ResponseBody`/`@RestController` `"redirect:/x"` strings into 302; appends `&` when the target URL already contains a query
- **`WebComponentContainer` name conflicts**: losing components are no longer mis-initialized / duplicate route registration, preventing side effects and resource leaks
- **Servlet bridge sessions never expire → unbounded memory growth**: `PerfHttpSessionManager.createSession()` did not apply session timeout, so `maxInactiveInterval` stayed 0 and the cleanup thread never reclaimed sessions; now sets expiry from `server.servlet.session.timeout` (Spring Boot Duration semantics: bare digits = seconds, supports `30m/1h/1d` suffixes, default 30 minutes)
- **`server.http.read-timeout` default lost**: `HTTP_READ_TIMEOUT` was missing from the default table, so read-timeout protection (documented 30s) was effectively disabled; now fixed
- **Async return-value serialization exception swallowed**: `asyncDispatch` return-value resolution exceptions were only logged with the response hanging until timeout; now routed to `ExceptionRegistry` and flushed, with `afterCompletion` carrying the real exception
- **404/405 path-level interceptor constraint ineffective**: include/exclude were still evaluated by request path when no handler existed; path-level interceptors no longer fire on arbitrary 404s
- **Overlapping wildcard routes match by specificity**: most-specific wins for overlapping wildcards like `/user/{id}` vs `/user/*` (literal > {var} > * > **), aligned with Spring MVC; `PathPatternUtils.comparePathRuleSpecificity` provides the comparison
- **Streaming error termination no longer hangs**: `AbstractNettyStreamSender.complete(.., failure)` closes the connection on error (no longer writes a normal `LastHttpContent`), so clients see the abnormal truncation

### Build

- **JaCoCo coverage gate**: total module coverage 92.1% (spring-web 92.7% / servlet 91.5% / websocket 90.5% / view 95.5%), new `scripts/coverage-report.sh/ps1` report scripts (relocated to `scripts/`)
- **E2E enhancements**: random ports to avoid conflicts, graceful-shutdown drain, TLS depth / mTLS, native degradation and perf-gate tests, connection reuse and request-thickness tests; `BaseE2ETest` read/write timeouts relaxed to 30s to reduce occasional flakiness
- **Test hardening**: `lenient()` on isomorphic stubs in `AsyncSupportRegistryTest`; order-independent sort assertions in `HttpBodyCodecRegistryCoverageTest`; explicit parameterized method in `ResponseEntityExceptionHandlerBranchesTest`; restored `placeholder.txt` test resource + `.gitignore` negate rule

### Documentation

- **View rendering docs**: `docs/view.md` (zh/en) synced with 2.7.6
- **Module docs**: with the `spring-web-support` split, `spring-web-servlet` and `spring-web-mvc-support` each have their own chapter with selection guidance

## [3.5.6] - 20260906
### Added

- **View rendering module `spring-web-view`**: Model/View are now first-class citizens of the request pipeline (`ModelContext` request-scoped container + `View`/`ViewResolver` SPI), with `@ControllerAdvice @ModelAttribute` pre-population, local `@ModelAttribute` methods, and `@PathVariable` / `BindingResult` merging; multi-engine support (Thymeleaf / FreeMarker / Beetl) selected via `spring.web.view.engine`; `redirect:` view names and `ViewReturnValueResolver`
- **JSP rendering**: Apache Jasper + JSTL integration (`JasperJspServlet`), `JspViewResolver` for `jsp:` prefix / `.jsp` suffix view names and automatic `/**/*.jsp` route registration; `spring-web-mvc-support` bridges `ModelAndView` / JSP views
- **Servlet object routing**: `SupportServletRegistry` scans Servlet beans / `@WebServlet` `url-pattern`s and registers them as framework routes; `ServletInvoker`, `PerfServletConfig`
- **JSR-356 `@ServerEndpoint` bridge** (`spring-web-websocket`): standard WebSocket endpoint annotation support — `@OnOpen`/`@OnMessage`/`@OnClose`/`@OnError`, `@PathParam`, Decoder/Encoder, custom `Configurator`, `JsrWebSocketContainer` endpoint lifecycle
- **Method-level interceptors & `@SessionAttributes`**: `@ControllerAdvice` interceptors matched by handler controller type; `@SessionAttributes` syncs Model attributes across requests with `SessionStatus` cleanup; `@SessionAttribute` / `SessionStatus` argument resolution
- **GraalVM native-image AOT support**: `ControllerBeanFactoryInitializationAotProcessor` registers reflection hints for controllers / generic DTOs, `SpringWebRuntimeHints` completes resource/reflection registration; `ServerEndpointBeanFactoryInitializationAotProcessor` covers endpoints; native-image CI workflow and `native-smoke-test.sh`
- **HEAD semantics (RFC 7231 §4.3.2)**: HEAD requests map to GET handlers when no explicit HEAD handler exists, suppressing the body while preserving the real `Content-Length` (resource/file/stream/byte paths covered)
- **Linux native transport**: new `server.netty.transport=auto/nio/epoll` (default `auto`) — native epoll enabled automatically on Linux; Netty dependencies split from `netty-all` into concrete modules with `linux-x86_64`/`aarch_64`/`riscv64` native classifiers
- **Production-oriented Netty defaults**: `SO_BACKLOG` 1024, `SO_KEEPALIVE` true, `max-content-length` 4MB; bounded default business pool queue (100) so `maxPoolSize` scaling and 503 fast-fail actually take effect
- **`server.port` isolation**: actual port published into the per-context `MapPropertySource` (`local.server.port`) so main and management ports never overwrite each other

### Changed

- **`spring-web-support` module split**: split into `spring-web-servlet` (pure Servlet bridge: Filter/Servlet/HttpSession/JSP/`@SessionAttribute` — official spring-web semantics, zero Spring MVC dependency) and `spring-web-mvc-support` (Spring MVC compatibility bridge: `org.springframework.web.servlet.*` shim classes, `WebMvcConfigurer`/`HandlerInterceptor`/`RequestBodyAdvice` adapters). Users needing only Servlet compatibility depend on `spring-web-servlet` alone, keeping `org.springframework.web.servlet.*` classes off the classpath; `spring-web-mvc-support` depends on `spring-web-servlet`. The starter auto-configuration is split into `SpringWebServletAutoConfiguration` + `SpringWebMvcSupportAutoConfiguration` (each conditional on its module's classes). The old coordinate `io.github.springperf:spring-web-support` is removed
- **Response status API**: `WebServerHttpResponse.getStatus()` return type changed from `HttpStatus` to `HttpStatusCode`, supporting non-standard status codes (e.g. 499/599/507) written as their raw values; `sendError` gains an `HttpStatusCode` overload, and the servlet bridge `setStatus`/`sendError` no longer throw for non-standard codes

### Security

- **Multipart DoS protection**: `SupportMultipartResolver` fail-fast on declared `Content-Length` plus streaming byte accounting (covers chunked); oversized requests return 413 aligned with `HttpObjectAggregator` instead of tearing down the connection
- **Fastjson deserialization hardening**: `SupportAutoType` disabled by default with `ErrorOnNotSupportAutoType` enabled, blocking `@type` gadget chains
- **Session fixation protection**: security audit fixes for session fixation and related attack vectors

### Optimized

- **Concurrent component registration**: `WebComponentContainer` now uses `ConcurrentHashMap` with atomic `compute` merging — no read/write races and no spurious destroys during dynamic registration
- **Per-server connection metrics**: `NettyMetricsHandler` switched from a shared singleton to per-server instances so main and management ports count separately
- **Locale argument resolution**: falls back to `Accept-Language` when no thread-bound Locale exists, honors interceptor/`ControllerAdvice` overrides, and never writes `ThreadLocal` (no pool residue)
- **Exception injection fix**: when `@ExceptionHandler` matches through the cause chain, the most specific cause compatible with the handler parameter type is injected, preventing spurious `ClassCastException` → 500

### Fixed

- **Stale 404/405 shared state**: a new `StacklessResponseStatusException` is created per request (stack-trace disabled, zero overhead) so `@ExceptionHandler` header/body mutations can no longer leak into later requests
- **Filter-chain exception path**: request context is initialized on exceptions thrown inside the filter chain, with full exception handling and `afterCompletion`, avoiding null/leaked `ThreadLocal` reads in interceptors
- **`WebContext` lifecycle**: failed startup now cleans up initialized child components, keeps fail-fast, and supports retry; child states reset after `destroy()` so stop/restart re-initialization works
- **Async fallback**: when no default business pool exists and no explicit executor is set, a lazily-created shared `SimpleAsyncTaskExecutor` is reused, eliminating NPEs
- **Router boundaries**: `PrefixPathRouterOptimizer` explicitly rejects when no prefix index exists (avoids `ArrayIndexOutOfBounds`); `SuffixPathRouterOptimizer` guarded
- **Async/streaming edge cases**: async exception handling and `AbstractNettyStreamSender` completion boundaries hardened
- **Static resources restricted to GET**: `ResourceRequestHandler` allows GET only (HEAD auto-mapped by the router, returning metadata without file IO)
- **JSR-356 lifecycle hardening**: Decoder/Encoder destroyed on `@OnOpen` failure and non-standard close codes; `@OnClose` exceptions no longer skip codec cleanup
- **No more silent view failures**: unresolvable view names throw `IllegalArgumentException` (500 with context) instead of returning a blank 200; `ViewResolver` exceptions are logged
- **`redirect:` alignment**: only active for view methods — `"redirect:/x"` returned from `@ResponseBody`/`@RestController` methods stays JSON instead of becoming a 302; URLs already containing a query now join with `&`
- **Component name conflicts**: a losing component is no longer initialized (prevents duplicate routes and resource leaks)
- **`Optional<Locale>` arg test**: uses a resolvable generic method carrier to verify wrapping semantics
- **Servlet-bridge sessions never expire → unbounded growth**: `PerfHttpSessionManager.createSession()` never applied the session timeout, so `maxInactiveInterval` stayed 0 and the cleanup thread never reaped sessions; sessions now expire per `server.servlet.session.timeout` (Spring Boot Duration semantics: bare numbers in seconds, `30m/1h/1d` suffixes, default 30 minutes)
- **`server.http.read-timeout` default ineffective**: `HTTP_READ_TIMEOUT` was missing from the defaults table, so the documented 30s slow-request read timeout was silently disabled when unset; fixed
- **Non-standard status code crash**: `PerfHttpServletResponse.setStatus/sendError` and core `setStatusCode` threw `IllegalArgumentException` for codes like 499/599; they are now written as their raw values
- **Async return-value serialization exceptions swallowed**: `asyncDispatch` only logged resolution failures, hanging the response until timeout; exceptions now route to `ExceptionRegistry` and flush, and `afterCompletion` receives the real exception
- **Path-level interceptors bypassed on 404/405**: include/exclude path filtering now also applies when no handler matched, so path-scoped interceptors no longer fire on arbitrary 404s
- **Overlapping wildcard routes match by specificity**: `/user/{id}` vs `/user/*` and similar resolve to the most specific pattern (`literal > {var} > * > **`), aligned with Spring MVC
- **Stream error completion not signaled**: `AbstractNettyStreamSender.complete(.., failure)` now closes the connection on error termination (no normal `LastHttpContent`), letting clients detect abnormal truncation

### Changed

- **Spring Boot upgraded to 3.5.16** (default profile switched to `spring-boot-3.5`; 3.2.x kept as an optional profile); **Netty upgraded to 4.1.137**
- **JaCoCo coverage gate**: total line coverage 92.09% (spring-web 91.58% / websocket 91.92% / support 93.44% / batch 91.96%) with new `coverage-report.sh/ps1` scripts
- **E2E expansion**: random ports, graceful-shutdown drain, TLS depth / mTLS, native degradation and performance-gate tests, keep-alive reuse and request thickness tests
- **GraalVM native-image CI**: AOT-build tests for `ControllerBeanFactoryInitializationAotProcessor`

### Documentation

- **View rendering**: `docs/view.md` (CN/EN) synced with 3.5.6
- **Compatibility / configuration / modules docs**: updated for Spring Boot 3.5.16, Netty 4.1.137, and AOT support (CN/EN)

## [3.2.5] - 20260824
### Added

- **Servlet spec bridge**: `spring-web-support` now ships a full Servlet adaptation layer — `PerfServletContext`, `PerfAsyncContext`, `PerfRequestDispatcher`, `PerfWebConnection`, `PerfHttpPrincipal`, `ServletPartAdapter`, `PerfFilterConfig`, `ForwardWebServerHttpRequest`, `NettyHttpServletRequest`, etc. — enabling Servlet-spec `Filter` chains, `AsyncContext`, `RequestDispatcher` forwards, `HttpSession`, and `Part` on top of Netty; plus the new `SupportDispatcherHandler` bridge dispatcher
- **`@RunInEventloop` meta-annotation**: `@Optimize` / `@ReactiveSupport` are now annotated with `@RunInEventloop`, letting business methods run directly on the EventLoop and avoid thread hops
- **Exposed Netty / Body / AccessLog config**: new keys in `PropertiesConstant` so `NettyHttpServer` / `AccessLogWebFilter` support externalized configuration
- **One-click WSL benchmark**: new `wsl-run-all.sh` / `wsl-benchmark.sh` / `wsl-server.sh` / `wsl-convert-cp.sh` scripts, `WSL_SETUP.md`, and `no-sleep.ps1`, with optional JFR profiling
- **Report generator improvements**: `ReportGenerator` skips corrupt result JSON and renders a FAIL column with hints for missing containers
- **Internals documentation**: full `docs/internals/00~19` architecture, request-pipeline, and design-decision docs

### Optimized

- **Hot-path allocation trimming**: reduced intermediate object allocations in request parameter parsing, `InMemoryHttpSessionStorage`, and `MicrometerWebMetrics`
- **Batched SSE sends**: `AbstractNettyStreamSender` gains `sendAll()` to batch writes instead of flushing per item, plus a drain re-entry fix
- **Large body release**: large request bodies now use a `duplicate` ref instead of refcount locking, removing lock overhead

### Fixed

- **Multipart**: `destroy()` releases the decoder to return pooled memory; fixed destroy order, `undecodedChunk`, and refcount leaks; missing `RequestPart` now returns 400 instead of 500
- **WebSocket**: rejected upgrades release the request; fixed h2c fragment buffer and WS upgrade leaks
- **Async**: lazy timeout scheduling with corrected metrics timing; fixed SSE emitter `select`, `ReactiveSupport` first-request NPE, and backpressure refill for cold publishers
- **Routing & argument binding**: fixed `SuffixPathRouterOptimizer` off-by-one; fail-fast startup validation for mapping and argument resolvers; `AbstractNamedValueResolver` now converts generic collection element types; `getReader` falls back to UTF-8
- **Exception handling**: `ResponseStatusException` headers are now copied via the cross-version adapter
- **File download**: uses `Content-Length` framing instead of chunked transfer
- **Large bodies**: async read and release are synchronized to prevent accessing released `ByteBuf`
- **Management port isolation**: the management dispatcher no longer participates in main-port routing lookups
- **Interceptor**: `afterCompletion` semantics corrected
- **Netty layer**: `@Sharable` handler lifecycle and h2c buffer leak guard; fixed `NettyHttpHandler` request-path NPE
- **Batch queue**: `DisruptorQueue` RingBuffer size clamp and overflow protection; `BatchScanner` generic type resolution fix
- **SSL PEM parsing**: `SslContextFactory` private key / certificate parsing fixes
- **FilterWrapper**: stable `id` assignment
- **Access log**: corrected error-status recording and format in `AccessLogWebFilter`

### Documentation

- **AI context migration**: `CLAUDE.md` / `.claude` moved to `AGENTS.md` / `.agent`
- **Benchmark data refresh**: WSL / multi-instance trend data updated
- **Internals**: servlet bridge / async streaming / batch module docs completed

## [2.7.5] - 20260824
### Added

- **Servlet spec bridge**: full Servlet adaptation layer — `PerfServletContext`, `PerfAsyncContext`, `PerfRequestDispatcher`, `PerfWebConnection`, `PerfHttpPrincipal`, `ServletPartAdapter`, `PerfFilterConfig`, `ForwardWebServerHttpRequest`, `NettyHttpServletRequest`, etc. — enabling Servlet-spec `Filter` chains, `AsyncContext`, `RequestDispatcher` forwards, `HttpSession`, and `Part` on top of Netty (adapted to `javax.servlet`)
- **`@RunInEventloop` meta-annotation**: `@Optimize` / `@ReactiveSupport` are now annotated with `@RunInEventloop`, letting business methods run directly on the EventLoop and avoid thread hops
- **Exposed Netty / Body / AccessLog config**: new keys in `PropertiesConstant` so `NettyHttpServer` / `AccessLogWebFilter` support externalized configuration
- **One-click WSL benchmark**: new `wsl-run-all.sh` / `wsl-benchmark.sh` / `wsl-server.sh` / `wsl-convert-cp.sh` scripts, `WSL_SETUP.md`, and `no-sleep.ps1`, with optional JFR profiling
- **Report generator improvements**: `ReportGenerator` skips corrupt result JSON and renders a FAIL column with hints for missing containers
- **Internals documentation**: full `docs/internals/00~19` architecture, request-pipeline, and design-decision docs

### Changed

- **Servlet session semantics**: `PerfHttpSession` now fires `HttpSessionBindingListener` value bind / unbind events correctly

### Optimized

- **Hot-path allocation trimming**: reduced intermediate object allocations in request parameter parsing, `InMemoryHttpSessionStorage`, and `MicrometerWebMetrics`
- **Batched SSE sends**: `AbstractNettyStreamSender` gains `sendAll()` to batch writes instead of flushing per item, plus a drain re-entry fix
- **Large body release**: large request bodies now use a `duplicate` ref instead of refcount locking, removing lock overhead

### Fixed

- **Multipart**: `destroy()` releases the decoder to return pooled memory; fixed destroy order, `undecodedChunk`, and refcount leaks; missing `RequestPart` now returns 400 instead of 500
- **WebSocket**: rejected upgrades release the request; fixed h2c fragment buffer and WS upgrade leaks
- **Async**: lazy timeout scheduling with corrected metrics timing; fixed SSE emitter `select`, `ReactiveSupport` first-request NPE, and backpressure refill for cold publishers
- **Routing & argument binding**: fixed `SuffixPathRouterOptimizer` off-by-one; fail-fast startup validation for mapping and argument resolvers; generic collection element type conversion in `AbstractNamedValueResolver`; `getReader` falls back to UTF-8
- **File download**: uses `Content-Length` framing instead of chunked transfer
- **Large bodies**: async read and release are synchronized to prevent accessing released `ByteBuf`
- **Management port isolation**: the management dispatcher no longer participates in main-port routing lookups
- **Interceptor**: `afterCompletion` semantics corrected
- **Netty layer**: `@Sharable` handler lifecycle and h2c buffer leak guard; fixed `NettyHttpHandler` request-path NPE
- **Batch queue**: `DisruptorQueue` RingBuffer size clamp and overflow protection; `BatchScanner` generic type resolution fix
- **SSL PEM parsing**: `SslContextFactory` supports `classpath:` prefixes with stream-based loading
- **FilterWrapper**: instance-level unique `id` so same-class multi-instance filters are not deduplicated by class name
- **Access log**: corrected error-status recording and format in `AccessLogWebFilter`

### Build

- **`.gitattributes`**: shell scripts forced to LF line endings
- **Benchmark module**: optional JFR profiling dependency

### Documentation

- **Benchmark data refresh**: WSL / multi-instance trend data updated
- **Internals**: servlet bridge / async streaming / batch module docs completed

## [2.7.4] - 20260806
### Added

- **`JsonConverter.toJsonBytes()` API**: New `toJsonBytes()` method on `JsonConverter`, implemented by Jackson / Fastjson converters to emit `byte[]` directly, skipping intermediate `String` copies and reducing serialization overhead
- **SSE test coverage expanded**: `DefaultNettyStreamSenderTest`, `EarlyEncodeNettyStreamSenderTest`, `HttpBodyCodecRegistryWriteNegotiationTest`, `NettyHttpHeadersAdapterTest`, etc.
- **One-click JFR flame graph scripts**: `spring-web-benchmark/jfr-hotspot.sh` / `analyze-jfr2.sh` for batch CPU hotspot flame graphs; `analyze_jfr.py` hotspot analyzer now tracked in version control

### Changed

- **SSE streaming refactor**: `NettyStreamSender` split into `DefaultNettyStreamSender` (EventLoop lazy encoding) and `EarlyEncodeNettyStreamSender` (App-thread early-encoded `byte[]` snapshots) so data can be frozen in advance
- **Zero-copy HTTP header view**: New `NettyHttpHeadersAdapter` — `WebHttpHeaders` delegates directly to Netty header storage, eliminating O(n) request header copies
- **Body content negotiation rewritten**: `HttpBodyCodecRegistry` write-path negotiation refactored with dedicated negotiation tests
- **Arg module optimized**: `SpringHandlerMethodArgumentResolverAdapter` refactored into a `Provider`; validator cached in method-argument context
- **Retval module optimized**: `ReturnValueResolverRegistry` / `MethodReturnValueContext` refactored
- **Stackless 404/405**: `StacklessResponseStatusException` used to reduce exception construction overhead on the request path
- **Safe pool replacement**: `BizPoolRegistry` supports safe replacement of existing pools

### Optimized

- **Jackson converter**: serialization path optimization
- **CORS exception mapping**: streamlined `CorsRegistry` / exception-resolver mapping

### Fixed

- **SSE single-message overflow**: `EarlyEncodeNettyStreamSender` writes oversized messages independently instead of throwing `IndexOutOfBoundsException`
- **`sendError()` JSON escaping**: error messages now escape quotes / backslashes / control chars to avoid malformed JSON
- **`NettyServerHttpResponse.flush()` buffer nulling**: prevents returning a released `ByteBuf` after flush failure
- **Routing array out-of-bounds**: `NameValueExpressionSupport` / `ParamOrHeaderMatcher` / `SuffixPathRouterOptimizer` fixes
- **Async state validation**: corrected `AsyncSupportUtils` / `PerfAsyncWebRequest` state checks
- **Error message fixes**: 10 copy-paste error messages in `AbstractFastFailHttpServletRequest` corrected to their method names
- **Actuator management beans**: added `@ConditionalOnMissingBean`
- **Batch graceful shutdown**: refined `DisruptorQueue` / `NettyHttpServer` shutdown flow

### Build

- **`spring-boot-maven-plugin` version managed** in root pom `pluginManagement`, aligned with `${spring-boot.version}`

### Documentation

- **README default language switched to English**, added `README_CN.md`
- **Synced the `2.7.x-migration-checklist.md`** backport downgrade guide from master

## [3.2.4] - 20260805
### Added

- **`JsonConverter.toJsonBytes()` API**: New `toJsonBytes()` method on `JsonConverter`, implemented by Jackson / Fastjson converters to emit `byte[]` directly, skipping intermediate `String` copies and reducing serialization overhead
- **Spring Boot 4.x event adapter**: New `Boot4WebServerInitializedEventBridge` and `Boot4WebServerInitializedEventAutoConfiguration` to fire `WebServerInitializedEvent` correctly under SB4
- **GraalVM native-image runtime hints**: New `SpringWebRuntimeHints` for reflection registration in SB4 / native-image scenarios
- **SSE test coverage expanded**: `DefaultNettyStreamSenderTest`, `EarlyEncodeNettyStreamSenderTest`, `HttpBodyCodecRegistryWriteNegotiationTest`, `NettyHttpHeadersAdapterTest`, etc.
- **One-click JFR flame graph scripts**: `spring-web-benchmark/jfr-hotspot.sh` / `analyze-jfr2.sh` for batch CPU hotspot flame graphs; `analyze_jfr.py` hotspot analyzer now tracked in version control

### Changed

- **SSE streaming refactor**: `NettyStreamSender` split into `DefaultNettyStreamSender` (EventLoop lazy encoding) and `EarlyEncodeNettyStreamSender` (App-thread early-encoded `byte[]` snapshots) so data can be frozen in advance
- **Zero-copy HTTP header view**: New `NettyHttpHeadersAdapter` — `WebHttpHeaders` delegates directly to Netty header storage, eliminating O(n) request header copies
- **Body content negotiation rewritten**: `HttpBodyCodecRegistry` write-path negotiation refactored with dedicated negotiation tests
- **Arg module optimized**: `SpringHandlerMethodArgumentResolverAdapter` refactored into a `Provider`; validator cached in method-argument context
- **Retval module optimized**: `ReturnValueResolverRegistry` / `MethodReturnValueContext` refactored
- **Stackless 404/405**: `StacklessResponseStatusException` used to reduce exception construction overhead on the request path
- **Safe pool replacement**: `BizPoolRegistry` supports safe replacement of existing pools

### Optimized

- **Jackson converter**: serialization path optimization
- **CORS exception mapping**: streamlined `CorsRegistry` / exception-resolver mapping

### Fixed

- **SSE single-message overflow**: `EarlyEncodeNettyStreamSender` writes oversized messages independently instead of throwing `IndexOutOfBoundsException`
- **`sendError()` JSON escaping**: error messages now escape quotes / backslashes / control chars to avoid malformed JSON
- **`NettyServerHttpResponse.flush()` buffer nulling**: prevents returning a released `ByteBuf` after flush failure
- **Routing array out-of-bounds**: `NameValueExpressionSupport` / `ParamOrHeaderMatcher` / `SuffixPathRouterOptimizer` fixes
- **Async state validation**: corrected `AsyncSupportUtils` / `PerfAsyncWebRequest` state checks
- **Error message fixes**: 10 copy-paste error messages in `AbstractFastFailHttpServletRequest` corrected to their method names
- **Actuator management beans**: added `@ConditionalOnMissingBean`
- **Batch graceful shutdown**: refined `DisruptorQueue` / `NettyHttpServer` shutdown flow

### Build

- **`spring-boot-maven-plugin` version managed** in root pom `pluginManagement`, aligned with `${spring-boot.version}`
- **JDK 25 build support**: Lombok upgraded to 1.18.46, Mockito to 5.19.0, ByteBuddy to 1.18.11 to fix the Lombok annotation-processing crash (`TypeTag::UNKNOWN`) and test-phase compatibility on JDK 25

### Documentation

- **README default language switched to English**, added `README_CN.md`
- **`module.md` and other AI context docs updated**

## [3.2.3] - 20260710
### Added

- **AI/LLM integration example**: New `spring-web-example-ai` example submodule demonstrating synchronous chat and SSE streaming with Spring AI (OpenAI-compatible API)
- **Metrics SPI**: New `WebMetrics` / `NoOpWebMetrics` core metrics SPI with zero overhead on the request path (NoOp eliminates timing via JIT constant folding and dead-code elimination)
  - `MicrometerWebMetrics` — Micrometer-based implementation recording `dispatcher.request.duration` (Timer, tagged by method/path/status), `dispatcher.exception` (Counter, tagged by type/resolved)
  - `BatchMetrics` / `NoOpBatchMetrics` — Batch processing metrics SPI
  - `MicrometerBatchMetrics` — Micrometer-based batch metrics recording enqueue/drop/overflow counts, process duration, batch size distribution, and queue capacity
  - Auto-configuration: automatically activated when Micrometer is on the classpath via `spring-boot-starter-web`
- **BizPoolRegistry enhancements**: Auto-discovers `ThreadPoolExecutor` beans from Spring context and registers them as pools; `@RunInPool("beanName")` falls back to Spring context bean lookup when pool name is not found locally
- **BizPoolRegistry Micrometer Gauges**: Automatically registers active threads / queue size / completed tasks gauges for each registered thread pool
- **Netty-level metrics**: New `NettyMetricsHandler` (Sharable ChannelHandler) tracking active TCP connections via `channelActive`/`channelInactive`
  - Auto-registers `netty.connections.active` Gauge (active TCP connection count)
  - Auto-registers `netty.eventloop.pending.tasks` Gauge (pending tasks across all EventLoops)
  - Exposes `NettyHttpServer.getWorkerGroup()` / `getActiveConnectionCount()` for metrics registration

### Changed

- **Benchmark module refactored**: Eliminated redundant `*-filter` submodules (perf-filter / tomcat-filter / undertow-filter / webflux-filter), merged filter scenarios into corresponding main modules; Maven profiles reduced from 10 to 5; removed Windows batch script, unified to shell script
- **Benchmark scenarios renamed**: `validatePost` → `valid`, `sseStream` → `sse`, `jsonEchoLarge` / `largeResponse` → `bytesLarge` for clearer semantics
- **Benchmark DTOs**: Added `UserReq` / `UserResp` DTO classes for unified request/response models
- **Report generator enhanced**: `ReportGenerator` refactored, `GcMetrics` / `Jdk8GcLogParser` / `Jdk11GcLogParser` improved GC log parsing

### Security

- **Forwarded headers protection**: `Forwarded` / `X-Forwarded-Proto` headers are now untrusted by default; added `server.use-forwarded-headers=false` toggle. Must be explicitly enabled when deployed behind a reverse proxy to prevent client-side scheme spoofing
- **CRLF injection protection**: `NettyMultipartFile.buildContentDisposition()` sanitizes `\r` / `\n` from name/filename values to prevent HTTP response splitting attacks during multipart file upload
- **HTTP parser limits**: Added `server.http.max-initial-line-length` (default 4KB), `server.http.max-header-size` (default 8KB), `server.http.max-chunk-size` (default 8KB) to prevent DoS attacks with oversized request lines/headers/chunks
- **Read timeout protection**: Added `server.http.read-timeout` (default 30s) to prevent slow clients from holding connections indefinitely before request body aggregation
- **WebSocket Origin validation**: Empty `setAllowedOrigins` list now rejects all cross-origin requests with an Origin header (previously treated the same as unconfigured, allowing all)
- **Exception info sanitization**: `ExceptionRegistry.handle()` returns generic `Internal Server Error` for unhandled exceptions instead of exposing exception class name and message to the client

### Optimized

- **Access log**: New `AccessLogWebFilter` enabled via `server.accesslog.enabled=true`, wrapping at the outermost filter layer (lowest Order) for full request lifecycle timing; log format: `remoteAddr method uri statusCode elapsedMs "user-agent"`
- **Parameterized logging**: Global migration from string concatenation to SLF4J `{}` placeholders (InterceptorRegistry, PathPatternRouter, NettyServerHttpResponse, BackpressureHandler, etc.), eliminating string construction overhead when logging is disabled
- **Exception logging enhanced**: Critical-path exception logs include full stack traces (`NettyStreamSender`, SSE timeout cleanup exceptions, etc.) for easier troubleshooting
- **`BizPoolRegistry.register()`**: Accepts `ExecutorService` instead of `ThreadPoolExecutor`, supporting a wider range of executor types
- **Graceful shutdown**: Added `NettyHttpHandler.setShuttingDown()` — new requests during shutdown return 503 `Service Unavailable`; `ManagementNettyHttpServer.stop()` rejects new requests before closing EventLoopGroup, preventing new requests from being processed during graceful shutdown
- **Application-level backpressure**: `DispatcherHandler.handleWithMappingResult()` catches `RejectedExecutionException` — returns 503 with `Retry-After: 5` header when thread pool is overloaded, avoiding EventLoop blocking; falls back to EventLoop execution when pool is shutting down

### Fixed

- **SSE timeout task not cleaned up**: `NettyStreamSender.onCompleteSuccess()` / `onCompleteError()` now calls `resp.setTimeout(null, -1)` to cancel the scheduled timeout task, preventing connection leaks after stream completion

### Documentation

- **README / CONTRIBUTING / SECURITY updated**
- **Documentation content corrections**

## [2.7.3] - 20260710
### Added

- **AI/LLM integration example**: New `spring-web-example-ai` example submodule demonstrating synchronous chat and SSE streaming with Spring AI (OpenAI-compatible API)
- **Metrics SPI**: New `WebMetrics` / `NoOpWebMetrics` core metrics SPI with zero overhead on the request path (NoOp eliminates timing via JIT constant folding and dead-code elimination)
  - `MicrometerWebMetrics` — Micrometer-based implementation recording `dispatcher.request.duration` (Timer, tagged by method/path/status), `dispatcher.exception` (Counter, tagged by type/resolved)
  - `BatchMetrics` / `NoOpBatchMetrics` — Batch processing metrics SPI
  - `MicrometerBatchMetrics` — Micrometer-based batch metrics recording enqueue/drop/overflow counts, process duration, batch size distribution, and queue capacity
  - Auto-configuration: automatically activated when Micrometer is on the classpath via `spring-boot-starter-web`
- **BizPoolRegistry enhancements**: Auto-discovers `ThreadPoolExecutor` beans from Spring context and registers them as pools; `@RunInPool("beanName")` falls back to Spring context bean lookup when pool name is not found locally
- **BizPoolRegistry Micrometer Gauges**: Automatically registers active threads / queue size / completed tasks gauges for each registered thread pool
- **Netty-level metrics**: New `NettyMetricsHandler` (Sharable ChannelHandler) tracking active TCP connections via `channelActive`/`channelInactive`
  - Auto-registers `netty.connections.active` Gauge (active TCP connection count)
  - Auto-registers `netty.eventloop.pending.tasks` Gauge (pending tasks across all EventLoops)
  - Exposes `NettyHttpServer.getWorkerGroup()` / `getActiveConnectionCount()` for metrics registration

### Changed

- **Benchmark module refactored**: Eliminated redundant `*-filter` submodules (perf-filter / tomcat-filter / undertow-filter / webflux-filter), merged filter scenarios into corresponding main modules; Maven profiles reduced from 10 to 5; removed Windows batch script, unified to shell script
- **Benchmark scenarios renamed**: `validatePost` → `valid`, `sseStream` → `sse`, `jsonEchoLarge` / `largeResponse` → `bytesLarge` for clearer semantics
- **Benchmark DTOs**: Added `UserReq` / `UserResp` DTO classes for unified request/response models
- **Report generator enhanced**: `ReportGenerator` refactored, `GcMetrics` / `Jdk8GcLogParser` / `Jdk11GcLogParser` improved GC log parsing

### Security

- **Forwarded headers protection**: `Forwarded` / `X-Forwarded-Proto` headers are now untrusted by default; added `server.use-forwarded-headers=false` toggle. Must be explicitly enabled when deployed behind a reverse proxy to prevent client-side scheme spoofing
- **CRLF injection protection**: `NettyMultipartFile.buildContentDisposition()` sanitizes `\r` / `\n` from name/filename values to prevent HTTP response splitting attacks during multipart file upload
- **HTTP parser limits**: Added `server.http.max-initial-line-length` (default 4KB), `server.http.max-header-size` (default 8KB), `server.http.max-chunk-size` (default 8KB) to prevent DoS attacks with oversized request lines/headers/chunks
- **Read timeout protection**: Added `server.http.read-timeout` (default 30s) to prevent slow clients from holding connections indefinitely before request body aggregation
- **WebSocket Origin validation**: Empty `setAllowedOrigins` list now rejects all cross-origin requests with an Origin header (previously treated the same as unconfigured, allowing all)
- **Exception info sanitization**: `ExceptionRegistry.handle()` returns generic `Internal Server Error` for unhandled exceptions instead of exposing exception class name and message to the client

### Optimized

- **Access log**: New `AccessLogWebFilter` enabled via `server.accesslog.enabled=true`, wrapping at the outermost filter layer (lowest Order) for full request lifecycle timing; log format: `remoteAddr method uri statusCode elapsedMs "user-agent"`
- **Parameterized logging**: Global migration from string concatenation to SLF4J `{}` placeholders (InterceptorRegistry, PathPatternRouter, NettyServerHttpResponse, BackpressureHandler, etc.), eliminating string construction overhead when logging is disabled
- **Exception logging enhanced**: Critical-path exception logs include full stack traces (`NettyStreamSender`, SSE timeout cleanup exceptions, etc.) for easier troubleshooting
- **`BizPoolRegistry.register()`**: Accepts `ExecutorService` instead of `ThreadPoolExecutor`, supporting a wider range of executor types
- **Graceful shutdown**: Added `NettyHttpHandler.setShuttingDown()` — new requests during shutdown return 503 `Service Unavailable`; `ManagementNettyHttpServer.stop()` rejects new requests before closing EventLoopGroup, preventing new requests from being processed during graceful shutdown
- **Application-level backpressure**: `DispatcherHandler.handleWithMappingResult()` catches `RejectedExecutionException` — returns 503 with `Retry-After: 5` header when thread pool is overloaded, avoiding EventLoop blocking; falls back to EventLoop execution when pool is shutting down

### Fixed

- **SSE timeout task not cleaned up**: `NettyStreamSender.onCompleteSuccess()` / `onCompleteError()` now calls `resp.setTimeout(null, -1)` to cancel the scheduled timeout task, preventing connection leaks after stream completion

### Documentation

- **README / CONTRIBUTING / SECURITY updated**
- **Documentation content corrections**

## [3.2.2] - 20260705
### Added

- **Session support**: Full `jakarta.servlet.http.HttpSession` implementation in servlet bridge module
  - `PerfHttpSession` — Servlet HttpSession implementation backed by framework request context; supports attributes, expiration tracking, and invalidate lifecycle
  - `PerfHttpSessionManager` — Session lifecycle manager with create/get/invalidate operations
  - `HttpSessionStorage` / `InMemoryHttpSessionStorage` — Pluggable session storage SPI with default in-memory implementation
  - `ServletAdapterContext` enhanced to propagate session to Servlet API wrappers
- **Example modules expanded**: From 5 to 12 runnable example submodules
  - `spring-web-example-actuator` — Actuator endpoint monitoring example
  - `spring-web-example-async` — Async request processing (Callable/DeferredResult/SSE) example
  - `spring-web-example-data` — Spring Data JPA repository integration example
  - `spring-web-example-openapi` — OpenAPI 3.0 documentation auto-generation example
  - `spring-web-example-swaggerui` — Swagger UI static resource serving example
  - `spring-web-example-shiro` — Apache Shiro authentication and session management example
  - `spring-web-example-spring-security` — Spring Security authentication and session management example
- **English documentation**: Full English translation of all project documentation (10 documents covering overview, quickstart, advanced usage, benchmarking, configuration, extensions, modules, performance principles, compatibility), maintained under `docs/en/`
- **Swagger UI auto-configuration**: New `SwaggerUiAutoConfiguration` serving Swagger UI static resources when `swagger-ui` is on classpath; `SwaggerUiProperties` for path and resource location configuration
- **OpenAPI doc endpoint**: New `OpenApiDocController` serving the OpenAPI JSON specification at a configurable path

### Optimized

- **`DispatcherHandler` optimized**: Refined filter chain flow and error path handling
- **Multi-version compatibility verified**: Confirmed compatibility across Spring Boot 3.0.x through 4.1.x via Maven profiles

## [2.7.2] - 20260704
### Added

- **Session support**: Full `javax.servlet.http.HttpSession` implementation in servlet bridge module
  - `PerfHttpSession` — Servlet HttpSession implementation backed by framework request context; supports attributes, expiration tracking, and invalidate lifecycle
  - `PerfHttpSessionManager` — Session lifecycle manager with create/get/invalidate operations
  - `HttpSessionStorage` / `InMemoryHttpSessionStorage` — Pluggable session storage SPI with default in-memory implementation
  - `ServletAdapterContext` enhanced to propagate session to Servlet API wrappers
- **Example modules expanded**: From 5 to 12 runnable example submodules
  - `spring-web-example-actuator` — Actuator endpoint monitoring example
  - `spring-web-example-async` — Async request processing (Callable/DeferredResult/SSE) example
  - `spring-web-example-data` — Spring Data JPA repository integration example
  - `spring-web-example-openapi` — OpenAPI 3.0 documentation auto-generation example
  - `spring-web-example-swaggerui` — Swagger UI static resource serving example
  - `spring-web-example-shiro` — Apache Shiro authentication and session management example
  - `spring-web-example-spring-security` — Spring Security authentication and session management example
- **English documentation**: Full English translation of all project documentation (10 documents covering overview, quickstart, advanced usage, benchmarking, configuration, extensions, modules, performance principles, compatibility), maintained under `docs/en/`
- **Swagger UI auto-configuration**: New `SwaggerUiAutoConfiguration` serving Swagger UI static resources when `swagger-ui` is on classpath; `SwaggerUiProperties` for path and resource location configuration
- **OpenAPI doc endpoint**: New `OpenApiDocController` serving the OpenAPI JSON specification at a configurable path

### Optimized

- **`DispatcherHandler` optimized**: Refined filter chain flow and error path handling
- **Multi-version compatibility verified**: Confirmed compatibility across Spring Boot 2.4.x, 2.5.x, 2.6.x, and 2.7.x via Maven profiles

## [2.7.1] - 20260701
### Added

- **Examples module**: New `spring-web-examples` aggregate module with 5 example submodules:
  - `spring-web-example-rest` — REST API comprehensive example (Controller/Filter/Interceptor/Exception handling)
  - `spring-web-example-batch` — Batch request processing example (`BatchUserController` CRUD)
  - `spring-web-example-realtime` — Real-time communication example (WebSocket chat + SSE push)
  - `spring-web-example-servlet-bridge` — Servlet bridge example (Filter/Interceptor bridging)
  - `spring-web-example-upload` — File upload example (single/multi-file upload, storage service)
- **Batch request processing module**: New `spring-web-batch` module, transparently aggregates high-concurrency same-type requests into batch processing; core components include `BatchRequest` / `@BatchMapping` / `BatchRegistry` / `BatchInvoker`; backpressure and flow control based on Disruptor RingBuffer; automatic batching without time windows

### Optimized

- **Configuration properties refactoring**: Extracted common methods from `ApplicationProperties` / `PropertiesConstant`, refactored size/time configuration parsing, added `WebServerProperties` configuration class

## [3.2.0] - 20260628
### Changed

- **Jakarta EE migration**: `javax.servlet` → `jakarta.servlet` 6.0, Servlet API upgraded from 4.0.1 to 6.0.0; `javax.validation` → `jakarta.validation`; all bridge layer code adapted to Jakarta API
- **Spring Boot 3.2.12 upgrade**: Parent POM upgraded from 2.7.18 to 3.2.12, JDK baseline raised from 8 to 17; removed explicit management of `lombok`/`junit-jupiter`/`mockito`/`byte-buddy`/`assertj`/`servlet-api` versions, delegated to Spring Boot BOM
- **SpringDoc OpenAPI upgrade**: Migrated from `springdoc-openapi-common` 1.7.0 to `springdoc-openapi-starter-common` 2.3.0, adapted to Spring Boot 3.x package structure
- **Spring Boot Admin upgrade**: Client version from 2.7.10 to 3.2.0, adapted to Jakarta API

### Added

- **GraalVM native-image support**: Added `reflect-config.json` / `resource-config.json` / `proxy-config.json` native-image pre-configuration, supporting GraalVM native image compilation; added `GraalVmNativeImageConfigTest` to verify configuration correctness
- **Virtual thread E2E test**: Added `VirtualThreadE2ETest`, covering request processing under JDK 21 virtual thread scenarios

### Optimized

- **Code compatibility adaptation**: Globally adjusted `javax.*` references to `jakarta.*`, including all Filter/Servlet/Session related classes and test code
- **Fallback response optimization**: Added fallback handling in `ReturnValueResolverRegistry` / `BaseWebServerHttpResponse`
- **CI build pipeline upgrade**: Adapted CI for JDK 17 baseline

## [2.7.0] - 20260628
### Changed

- **Version scheme refactoring**: Major version changed from 1.x.x to 2.7.x, aligned with Spring Boot major version; project version upgraded from 1.0.5 to 2.7.0; all submodule parent versions updated accordingly

### Optimized

- **Spring Boot upgraded to 2.7.18**: Parent POM from 2.6.15 to 2.7.18, synchronized Spring Boot Admin Client upgrade to 2.7.10
- **CI configuration update**: Adapted to Spring Boot 2.7.18 build environment

## [1.0.5] - 20260625
### Added

- **WebSocket module**: New `spring-web-websocket` module with `WebSocketConfigurer` / `WebSocketHandlerRegistration` / `WebSocketHandlerRegistry` registration API; `NettyWebSocketSession` full-duplex communication; `WebSocketRoutingHandler` route dispatch; `WebSocketAutoConfiguration` auto-configuration; E2E tests covering text, binary, and path routing scenarios
- **OpenAPI integration**: Added `OpenApiAutoConfiguration` + `OpenApiAdapter`, mapping framework endpoint metadata to OpenAPI 3.0 JSON specification, supporting Swagger UI display; E2E tests covering full request lifecycle
- **`PipelineCustomizer`**: Netty ChannelPipeline extension point, allowing custom handler injection into HTTP/1.1 and HTTP/2 ChannelInitializers
- **`ExceptionHandlerAdvice` enhancement**: Handles additional exception types such as `HttpMediaTypeNotAcceptableException`
- **`ServletFilterPatternUtils`**: Unified Servlet Filter path matching utility, supporting exact/prefix/suffix/path pattern matching
- **`pool.default-execute-mode` configuration**: Default execution strategy for methods without `@RunInPool`; default is `default` (thread pool), setting to `eventloop` globally switches to EventLoop execution

### Changed

- **WebFilter architecture refactoring**: `WebFilter` / `WebFilterRegistration` / `WebFilterRegistry` / `DefaultFilterChain` / `RuntimeMappingWebFilter` migrated to `core/filter` package; deleted old `match` package (`ExactMatch` / `PathMatch` / `PrefixMatch` / `SuffixMatch`), unified to `ServletFilterPatternUtils`

## [1.0.4] - 20260621
### Added

- **Spring Cloud service registration discovery compatibility**: Emits `WebServerInitializedEvent` after Netty server starts, enabling Nacos/Eureka/Consul service registration components to detect server readiness; uses JDK dynamic proxy to wrap `WebServerApplicationContext` without requiring Servlet container
- **Spring Boot Admin compatibility**: Added `PerfApplicationFactory` to replace SBA Client's `DefaultApplicationFactory`, automatically computing serviceUrl/managementUrl/healthUrl from framework config without relying on `HttpServletRequest`; added `SpringBootAdminClientAutoConfiguration` auto-configuration, activated only when `spring-boot-admin-starter-client` is on classpath
- **`NettyHttpServer.getActualPort()`**: Exposes the actual bound port number, supporting random port scenarios

## [1.0.3] - 20260618
### Added

- **HTTP/2 support**: Added `Http2ChannelInitializer` managing both HTTP/1.1 and HTTP/2 ChannelInitializers, supporting h2 (TLS ALPN) and h2c (cleartext prior knowledge) auto-negotiation; added `server.http2.enabled` configuration toggle
- **Configurable Netty worker threads**: Added `server.netty.workers` config property for customizing worker EventLoopGroup thread count (default: 2 × CPU cores)
- **Configurable WriteBufferWaterMark**: Added `server.netty.write-buffer-low-watermark` / `write-buffer-high-watermark` config properties for backpressure threshold tuning (default low: 8KB, high: 32KB)
- **`writeBytes(byte[])` efficient writing**: Added `writeBytes()` method to `WebServerHttpResponse` — Content-Length + single writeAndFlush, avoiding chunked encoding, significantly improving performance for small payloads of known size
- **JMH benchmark module**: New `spring-web-benchmark` module covering Perf/Tomcat/Undertow/WebFlux four server types, including bare/Filter/Interceptor/SSE comparison scenarios and automated report generation (GC log parsing, heap snapshots)
- **Documentation**: Added `docs/benchmark.md` benchmark document, README updates

### Optimized

- **SSE drain() race condition fix**: Re-schedules drain when wip reaches zero but queue still has data, preventing data loss in `NettyStreamSender` when producer outpaces drain; wraps each SSE write chunk with `DefaultHttpContent`
- **NettyHttpServer bind failure safe cleanup**: Properly shuts down boss/worker EventLoopGroup on bind failure, preventing thread leaks from blocking JVM exit
- **Code style unification**: Formatting and method extraction in `BaseWebServerHttpResponse` / `NettyServerHttpResponse`

## [1.0.2] - 20260608
### Fixed

- `InvokableHandlerMethod.createMethodParameters()` returned bridge methods instead of actual methods when Controller was proxied, causing failure to read parameter annotations
- `MappingRegistry.initComponentPhase1()` annotations erased on proxied objects during Controller scanning, preventing `@RequestMapping` from being read
- `MappingRegistry.initComponentPhase1()` only extracted `value/path` as path prefix when parsing class-level `@RequestMapping`, ignoring `method`, `params`, `headers`, `consumes`, `produces` constraint attributes

### Added

- **Class-level @RequestMapping constraint merging**: `initComponentPhase1()` extracts class-level `method/params/headers/consumes/produces` constraints and merges them with method-level constraints via `mergeMatchers()` / `mergeMatcherPair()` (same types merge internal lists, different types append)
- **Placeholder resolution**: Supports `${...}` placeholders in path, params, headers, consumes, produces via `Environment.resolvePlaceholders()` in `initComponentPhase1()` / `initMethodMappingContext()`
- **Spring Data Web compatibility**: Added `SpringDataWebCompatibilityAutoConfiguration`, auto-registers `PageableHandlerMethodArgumentResolver` / `SortHandlerMethodArgumentResolver` for Spring Data pagination and sorting parameter binding
- **Unified documentation**: Added JavaDoc for all core interfaces including `WebComponent`, `LifecycleWebComponent`, `WebFilter`, `FilterChain`, `HandlerInterceptor`, `CorsRegistration`, `WebServerHttpRequest`, `WebServerHttpResponse`, `RequestContext`, `Matcher`, `Router` — clarifying component contracts and lifecycle semantics
- **New E2E test suite**: Proxy inheritance tests (~40 files covering Controller/API inheritance, conditional assembly, parameter binding, filter ordering, exception handlers, etc.), CoreFeatures E2E (P0/P1/P2), class-level constraint E2E, concurrency stress tests, management port conflict tests, static resource security tests, Netty error path tests, HTTP request lifecycle tests
- **New unit tests**: 13 class-level constraint merging tests in MappingRegistryTest, enhanced proxy class parameter annotation tests in InvokableHandlerMethodTest, enhanced DispatcherHandlerTest / NettyHttpHandlerTest / AsyncSupportRegistryTest

## [1.0.1] - 20260606
### Changed

- Unified class naming: `Perf*` → `SpringWeb*` / `Actuator*`, eliminating `perf` prefix ambiguity
- Actuator management port introduced `ManagementDispatcherHandler`, breaking circular dependency with main `DispatcherHandler`

### Added

- **spring-web-support bridge layer**: `WebMvcConfigurer` adapter (`WebMvcConfigurerBridge`) compatible with Spring MVC, enabling injection of interceptors, argument resolvers, return value handlers, and exception handlers through Spring MVC configuration interfaces
- **Static resource handling**: `ResourceHandlerRegistry` supporting static resource mapping and classpath resource serving
- **WebFilter support**: Reactive-style `WebFilter` filter chain
- **Management port SSL**: `management.server.ssl.*` configuration support for standalone HTTPS deployment on management port
- **Interceptor lifecycle**: `LifecycleInterceptor` for monitoring full request processing lifecycle
- **Custom type formatters**: `Formatter` / `Converter` registry supporting `@DateTimeFormat` and other annotations
- **Parameter binding**: Enhanced `@RequestParam` data binding with `WebDataBinder` registry
- **Configurable async support**: `AsyncSupportConfigurer` allowing custom async timeout / task executor
- **Exception resolver recursion limit**: Maximum 10 levels to prevent infinite recursion
- **Mapping registry optimization**: Single-pass iteration replacing triple stream traversal

### Fixed

- `MetaUtils.getDefaultValue` used `==` to compare against `ValueConstants.DEFAULT_NONE`; annotation proxies return different String references, causing the comparison to always fail — `@RequestHeader(required=true)` did not take effect
- `AbstractNamedValueNullableResolver.handleMissingValue` threw `IllegalStateException` (500); should throw `ResponseStatusException(BAD_REQUEST)` returning 400
- `WebContext` constructor did not set the `webContext` self-reference, causing NPE in `WebComponentContainer.registerWebComponent(Class)` under management port mode

## [1.0.0] - 20260603
### Added

- Netty 4.1 based REST web server
- Spring Boot auto-configuration support (`spring-boot-starter-web`)
- Annotation-driven Controller mapping (`@RestController`, `@RequestMapping`, etc.)
- Argument resolution and return value handling
- JSON serialization (Jackson / Fastjson2 optional)
- File upload support
- SSE (Server-Sent Events) support
- Async response (`ListenableFuture`)
- Interceptor and filter chain
- Global exception handling (`@ExceptionHandler`, `@RestControllerAdvice`)
- CORS configuration support
- Path matching (Ant-style + regex)
- Actuator integration (standalone management port)
- Servlet API compatibility module (`spring-web-support`)
- Management endpoint standalone dispatcher (`ManagementDispatcherHandler`)
- Mapping registry optimization (single-pass iteration replacing triple stream traversal)
- Exception resolver recursion limit (maximum 10 levels)
