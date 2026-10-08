> English | [中文](../configuration.md)

# Configuration Reference

All configuration properties are set in `application.properties`.

## Server Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8080` | Netty HTTP listening port |
| `server.servlet.context-path` | `/` | Application context path |
| `server.address` | none | Bind to a specific NIC address (e.g. `192.168.1.10`); when unset, binds all interfaces (0.0.0.0). Applies to both the main and management ports |

## HTTP Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `server.http.max-content-length` | `4194304` (4MB) | Maximum request body size (bytes) |
| `server.http.timeout` | `60000` (60s) | Response timeout (milliseconds; `<=0` means unlimited, aligning with Tomcat `connectionTimeout=0`): deadline from entering the pipeline to committing the response. On timeout a **504** is written; committing the response (flush / first streaming frame) cancels the timer. Async request timeouts are governed separately by `spring.mvc.async.request-timeout` and produce **503** |

## Async Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `spring.mvc.async.request-timeout` | `30s` | Async request (DeferredResult/Callable) timeout (supports 30s/1m/1h or plain number in ms) |

## File Upload (multipart)

| Property | Default | Description |
|----------|---------|-------------|
| `spring.servlet.multipart.enabled` | `true` | Whether multipart (file upload) parsing is enabled |
| `spring.servlet.multipart.max-file-size` | `-1` (unlimited) | Max size of a single uploaded file (DataSize, e.g. `10MB`); exceeding it returns **413** |
| `spring.servlet.multipart.max-request-size` | `-1` | Max size of the whole multipart request (DataSize); falls back to `server.http.max-content-length` when unset |
| `spring.servlet.multipart.file-size-threshold` | `-1` (framework default 16KB) | Size threshold above which a multipart part is written to disk (DataSize); parts below it stay in memory. Explicit `0` aligns with Boot's write-everything-to-disk semantics |
| `spring.servlet.multipart.location` | none (system temp dir) | Directory for multipart temp files. **Recommended for containerized deployments** (size-limited tmpfs fills up quickly and eats memory quota) |

> Related guards: `server.http.multipart.max-part-count` (max part count), `server.http.multipart.max-part-header-size` (max single-part header size, exceeding it returns 400), `server.http.max-ranges` (max byte ranges in a multi-range request; default `100` = no extra tightening, **effective cap = min(value, 100)** because the underlying `HttpRange` parser rejects more than 100 ranges, so this key tightens rather than raises; `0` = disable multi-range, negative = no extra limit; above the cap the `Range` header is ignored per RFC 9110 §14.2 and the full entity is returned), `server.http.max-pipelined-requests` (max same-connection requests queued while a response is in flight, default `16`; `<=0` = unlimited. Reaching the limit **pauses reads** (`autoRead=false`) rather than closing the connection or rejecting requests, because pipelining requires in-order responses; unread bytes stay in the socket buffer so TCP flow control provides backpressure, and reads resume once the queue drains).
>
> **Why multipart keys span two namespaces**: `spring.servlet.multipart.*` (Boot-aligned upload config: size limits, spilling) vs `server.http.multipart.*` (framework-native pipeline guards: part count, header size). This framework executes Servlet container semantics early in the Netty pipeline (aggregation/validation happens before the Servlet bridge), hence guard keys belong to the HTTP protocol family. See [Design Decision ADR 11](../internals/19-design-decisions.md) for the layering rationale. (Chinese only.)

## Static Resources

| Property | Default | Description |
|----------|---------|-------------|
| `spring.mvc.static-path-pattern` | `/**` | Global prefix for all resource handler path patterns |
| `spring.web.resources.add-mappings` | `false` | Whether to auto-register the default mapping (`/**` → `static-locations`). **Default false** (keeps the historical "registered by user code" behavior); when true it coexists with user-registered handlers (user patterns win by specificity, aligned with Boot) |
| `spring.web.resources.static-locations` | Boot default (4 locations) | Default static locations (comma-separated), aligned with Boot |
| `spring.web.resources.cache.period` | none | Default cache period for the auto-registered mapping (e.g. `1h`) |
| `spring.web.resources.cache.cachecontrol.max-age` | none | Cache-Control max-age (takes precedence over `cache.period`) |

## Encoding (server.servlet.encoding.*)

| Property | Default | Description |
|----------|---------|-------------|
| `server.servlet.encoding.charset` | `UTF-8` | Unified request/response character set |
| `server.servlet.encoding.force` | `false` | Force the charset on both request and response (overrides explicit `setCharacterEncoding`) |
| `server.servlet.encoding.force-request` | inherits `force` | Force the charset on the request only |
| `server.servlet.encoding.force-response` | inherits `force` | Force the charset on the response only |

## Session (server.servlet.session.*)

| Property | Default | Description |
|----------|---------|-------------|
| `server.servlet.session.tracking-modes` | `COOKIE` | Tracking modes (`COOKIE` / `URL`; `SSL` is N/A here). In `URL` mode no session cookie is issued; `encodeURL()` writes `;jsessionid=` instead and the server reads it back when the cookie is absent (Servlet spec §7.1) |
| `server.servlet.session.persistent` | `false` | Persist sessions to disk so they survive a restart (JDK serialization, one file per session) |
| `server.servlet.session.store-dir` | `.perf-sessions` | Persistence directory (effective when `persistent=true`) |
| `server.servlet.session.persistent-exclude` | none | Session attribute names excluded from persistence (comma-separated) |
| `server.servlet.session.persistent-deserialization-filter` | none (omitted means no filtering) | Deserialization class filter (`ObjectInputFilter` spec, semicolon-separated, e.g. `java.util.*;io.springperf.web.*;!*`). **Omitted keeps the historical behaviour of no filtering**, and a WARN is logged at startup; when set, only matching classes load, a rejected file is discarded and logged with the **rejected class name** (kept distinct from "corrupted"), and a malformed spec fails at startup instead of quietly degrading to no filtering. **This key has no Boot counterpart**: Boot 3.5.16 has 13 `server.servlet.session.*` keys and none of them is about deserialization filtering |

> **When to tighten `persistent-deserialization-filter`**: session files are read **only at startup**, so the risk path is "someone who can write to `store-dir` gets an arbitrary classpath class deserialized on the next restart". The default `.perf-sessions` lives under the working directory, which limits exposure; if you point it at a world-writable path such as `/tmp`, any local user can write there and tightening is recommended. Suggested starting point (append your own packages):
>
> ```properties
> server.servlet.session.persistent-deserialization-filter=java.lang.*;java.util.*;java.math.*;java.time.*;maxarray=1000;maxdepth=20;maxrefs=10000;!*
> ```
>
> Note that a JEP 290 filter inspects the **whole reference graph recursively**, so the allow-list must cover the attributes and every nested object; a rejection discards the entire session file, not just that attribute. Use `*` to explicitly allow every class (equivalent to no defence).
| `server.servlet.virtual-server-name` | `localhost` | ServletContext virtual server name |
| `server.servlet.application-display-name` | none | ServletContext application display name (`getServletContextName()`) |
| `server.servlet.context-parameters.*` | none | Explicit block of ServletContext init parameters: `server.servlet.context-parameters.<name>=bar` is exposed as `getInitParameter("foo")` |
| `server.servlet.session.cookie.name` | `JSESSIONID` | Session cookie name |
| `server.servlet.session.cookie.http-only` | `true` | Forbid script access (`HttpOnly`) |
| `server.servlet.session.cookie.secure` | `false` | Send over HTTPS only (`Secure`) |
| `server.servlet.session.cookie.max-age` | `-1` (session cookie) | Cookie lifetime in seconds (`-1` = end of browser session) |
| `server.servlet.session.cookie.domain` | none | Cookie scope (`Domain`) |
| `server.servlet.session.cookie.same-site` | none | `SameSite`: `lax` / `strict` / `none` (case-insensitive; unknown values are logged and ignored). **Historical defect**: earlier versions upper-cased the value before handing it to the Netty enum, so `strict` threw `IllegalArgumentException` (fixed) |

> **URL rewriting & session id**: when `tracking-modes` includes `URL` and the request carries no session cookie, the container passes the session as `;jsessionid=<id>` in the URL (`encodeURL`/`encodeRedirectURL` write it, the server reads it back).
>
> **Matrix (path) parameters**: content after the first `;` in each path segment is stripped before routing (aligning with Spring's `UrlPathHelper.removeSemicolonContent=true`), so `/foo;jsessionid=X` matches `/foo`; `getRequestURI()` still returns the raw URI while `getServletPath()` returns the stripped path (same split as Tomcat).

## Response Compression (server.compression.*)

| Property | Default | Description |
|----------|---------|-------------|
| `server.compression.enabled` | `false` | Enable gzip response compression (when off, no compressor is added to the pipeline — zero runtime overhead) |
| `server.compression.mime-types` | 8-item whitelist | Comma-separated whitelist of Content-Type **primary types** eligible for compression: `text/html,text/xml,text/plain,text/css,text/javascript,application/javascript,application/json,application/xml` |
| `server.compression.excluded-user-agents` | none | Comma-separated User-Agent regex list (case-insensitive); a match skips compression |
| `server.compression.min-response-size` | `2KB` | Responses smaller than this size are not compressed (DataSize syntax supported); applies to both `FullHttpResponse` and the first chunk of a chunked response |

> Handled edge cases: HEAD requests are never compressed; zero-copy file responses (`writeFile`) are passed through uncompressed (so a plaintext file body never gets a bogus `Content-Encoding`); pre-compressed static `.gz` assets (already carrying `Content-Encoding`) are not re-compressed. The gzip level is fixed at 6 (Spring exposes no such switch; this matches the Netty default).

## Error Response (server.error.*)

| Property | Default | Description |
|----------|---------|-------------|
| `server.error.include-stacktrace` | `never` | Expose stack trace: `never` / `on-param` / `always` |
| `server.error.include-message` | `never` | Expose exception message: `never` / `on-param` / `always` |
| `server.error.include-binding-errors` | `never` | Expose binding/validation errors: `never` / `on-param` / `always` |
| `server.error.whitelabel.enabled` | `true` | Whether to render the built-in whitelabel HTML error page |
| `server.error.path` | `/error` | Error page path / instance identifier |
| `spring.mvc.problemdetails.enabled` | `false` | Render error responses as RFC 7807 `application/problem+json` |

## MVC Behavior (spring.mvc.*)

| Property | Default | Description |
|----------|---------|-------------|
| `spring.mvc.throw-exception-if-no-handler-found` | `true` | Raise an exception for a missing handler (routable to `@ControllerAdvice`) instead of a direct 404/405 |
| `spring.mvc.dispatch.error` / `.options` / `.trace` | `true` | Whether ERROR / OPTIONS / TRACE requests are dispatched to handlers (CORS preflight is still handled when OPTIONS is disabled) |
| `spring.mvc.publish-request-handled-events` | `false` | Whether to publish a `ServletRequestHandledEvent` when a request completes. **Deliberately off, unlike Boot whose default is `true`**: the event is of no use to most applications, so publishing it per request is pure overhead when nothing listens; turn it on explicitly for monitoring/audit |
| `spring.mvc.message-codes-resolver-format` | `prefix_error_code` | Validation message-code format: `prefix_error_code` / `postfix_error_code` |
| `spring.mvc.format.date` / `.time` / `.datetime` | `yyyy-MM-dd` / `HH:mm:ss` / `yyyy-MM-dd'T'HH:mm:ss` | Default date/time formats when no `@DateTimeFormat` is present (all ISO in shape, but each has its own pattern) |

## Internationalization (spring.web.locale.*)

| Property | Default | Description |
|----------|---------|-------------|
| `spring.web.locale` | none | Default Locale (e.g. `zh_CN`) |
| `spring.web.locale-resolver` | `accept-header` | Locale resolution strategy: `fixed` / `accept-header` |
| `spring.web.locale-bind` | `true` | Whether to bind `LocaleContextHolder` per request, following Spring MVC's `initContextHolders`/`resetContextHolders` pattern (save the previous context, set, reset, honouring `threadContextInheritable`; implemented in `SupportDispatcherHandler`). When `false` the framework never touches the Locale context: no per-request context allocation and no ThreadLocal `set` (a `remove` still runs once on the servlet path), so `LocaleContextHolder.getLocaleContext()` returns `null` and `getLocale()` falls back to the JVM default. Useful for locale-agnostic API services (the framework does not set the holder, and whether it clears it depends on the deployment path: on the native path `initContext` is false and the holder is left alone, so a value you set yourself is yours to clear; on the `spring-web-servlet` path `SupportDispatcherHandler` returns `init || requestAttributes != null`, which is always true there, so `resetLocaleContext()` still runs once per request and a value you set yourself is cleared as well) |

## Access Log (server.accesslog.*)

| Property | Default | Description |
|----------|---------|-------------|
| `server.accesslog.enabled` | `false` | Whether the access-log filter is enabled |
| `server.accesslog.format` | see source | Log format (`%h`/`%m`/`%U`/`%T`/`%s`/`%u`) |
| `server.accesslog.directory` | none | Directory for log files; unset means SLF4J only |
| `server.accesslog.prefix` / `.suffix` | `access` / `.log` | File name prefix / suffix |
| `server.accesslog.rotate` | `true` | Daily rotation |
| `server.accesslog.max-days` | `7` | Days to retain rotated files (`<=0` unlimited) |

## Connections and Limits (server.* guards)

| Property | Default | Description |
|----------|---------|-------------|
| `server.max-connections` | `0` (unlimited) | Max connections (overload protection); beyond it the connection is dropped outright (no `fireChannelActive`, so no 503 can be sent); in production the gateway normally carries the concurrency and connection cap (e.g. Nginx `limit_conn`) - see the note under this table for what it cannot cover |
| `server.max-parameter-count` | `10000` | Max total parameter count (hash DoS guard) |
| `server.max-http-request-header-size` | `8192` | Max combined request header size |
| `server.max-http-response-header-size` | `8192` | Max response header size (degrades to a minimal 500 when exceeded) |
| `server.max-swallow-size` | `2MB` | Max request-body bytes swallowed after an error response (negative unlimited) |
| `server.keep-alive-timeout` / `server.max-keep-alive-requests` | `0` (unlimited) / `0` (unlimited) | Keep-alive idle timeout / max requests per connection. **Both default to 0, so `KeepAliveHandler` is not installed at all**; the key names match Boot but **the defaults differ** (Boot/Tomcat: 0 / 100) - this framework applies no implicit connection-level limit by default, set one explicitly if you want it; in production these are **normally carried by the proxy / gateway** (connection caps, keep-alive request count, idle disconnect) - see the note under this table for what it cannot cover |
| `server.forward-headers-strategy` | `NONE` | Forwarded-header strategy: `NONE`/`FALSE` (do not trust), `FRAMEWORK`/`NATIVE` (trust) |
| `server.http.max-in-memory-size` | `4096` | In-memory body aggregation limit (beyond it a ByteBuf duplicate is used) |
| `server.http.max-chunk-size` | `8192` | Max HTTP chunk size |
| `server.http.max-initial-line-length` | `4096` | Max request initial line length |
| `server.http.max-pipelined-requests` | `16` | Cap on queued pipelined requests per connection; reaching it **pauses reads** (`autoRead=false`) instead of closing, leaving unread bytes in the socket buffer so the TCP window applies backpressure |
| `server.http.max-ranges` | `100` | Max Range segments per request; `0` forbids multi-range (such requests fall back to the full entity), a negative value adds no extra limit |
| `server.http.multipart.max-part-count` | `-1` | Max multipart part count (`<=0` unlimited). **The default differs from Boot**: Boot's `server.tomcat.max-part-count` is `50`; the key means the same thing (a non-positive value disables the cap) |
| `server.http.multipart.max-part-header-size` | `8192` | Max header bytes per multipart part (`<=0` unlimited); exceeding it makes the incremental scan throw `DecoderException` and the aggregator answer **400**. **The default differs from Boot**: Boot/Tomcat default this key to `512B`, this framework to `8192` |
| `server.http.read-timeout` | `0` (disabled) | Read **idle** timeout (supports the `30s` form; `<=0` disables). It only bounds read inactivity: **in-flight requests are never killed** (a slow SQL call / downstream call / suspended async handler may exceed it and still deliver its response); only genuinely idle connections and stalled half-requests are reclaimed. Aligns with Tomcat `connectionTimeout` semantics. **Disabled by default** (Tomcat defaults to 20s): this framework applies no implicit connection-level limit by default, in production the proxy / gateway **normally reclaims idle connections**; the edges it cannot cover (direct / east-west traffic, the app-to-gateway hop when the peer dies) are listed in the note under this table, set it explicitly when you cannot guarantee them |

> **Full list**: the entries above are the common ones. Every supported key is maintained and validated by
> `SupportedPropertiesTest` — adding a key without registering it in
> `META-INF/additional-spring-configuration-metadata.json` fails the build. Run
> `mvn -pl spring-web test -Dtest=SupportedPropertiesTest -Dperf.props.dump=dump.txt` to export the full list.

## Graceful Shutdown

| Property | Default | Description |
|----------|---------|-------------|
| `server.shutdown.grace-period` | `30s` | Graceful shutdown max wait time (supports 30s/1m/1h or plain number in ms) |

## Startup Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `server.check-on-startup` | `true` | Validate all Mappings at startup (fail-fast) |

## Netty Tuning

| Property | Default | Description |
|----------|---------|-------------|
| `server.netty.workers` | `0` (auto) | Netty worker EventLoop thread count, 0 = auto (CPU cores × 2) |
| `server.netty.write-buffer-low-watermark` | `8192` (8KB) | Write buffer low watermark (bytes) |
| `server.netty.write-buffer-high-watermark` | `32768` (32KB) | Write buffer high watermark (bytes) |
| `server.netty.transport` | `auto` | Netty transport type: `auto` (use native epoll on Linux, fallback to NIO on other platforms), `nio` (force Java NIO), `epoll` (force native epoll, fail on unsupported platform) |
| `server.netty.so-backlog` | `1024` | TCP listen backlog (production Linux recommended) |
| `server.netty.so-keepalive` | `true` | Enable TCP keepalive (production long-connection friendly) |
| `server.netty.tcp-nodelay` | `true` | Disable Nagle's algorithm for lower latency |
| `server.netty.so-reuseaddr` | `true` | Allow port reuse |
| `server.netty.boss-threads` | `1` | boss EventLoop thread count (accepts connections) |
| `server.netty.allocator-type` | `pooled` | ByteBuf allocator type (`pooled` reuses buffers to reduce GC) |

## HTTP/2

| Property | Default | Description |
|----------|---------|-------------|
| `server.http2.enabled` | `false` | Enable HTTP/2 support (requires SSL for browser clients) |

## Business Thread Pool

| Property | Default | Description |
|----------|---------|-------------|
| `pool.core-pool-size` | `50` | Core pool size |
| `pool.max-pool-size` | `200` | Maximum pool size |
| `pool.keep-alive-time` | `60` | Idle thread keep-alive time (seconds) |
| `pool.queue-capacity` | `100` | Task queue capacity. Bounded by default so that `maxPoolSize` takes effect and overload returns 503 instead of queueing unboundedly (set to 1 when ≤ 0) |
| `pool.default-execute-mode` | `default` | Default execution mode when no `@RunInPool`. `eventloop`=EventLoop, other values = pool name |
| `spring.threads.virtual.enabled` | `false` | JDK 21+: when `true`, the framework's execution hot spots use virtual threads — both the `default` business pool and batch methods (`@BatchMapping`) run on virtual threads (only the thread type changes: `pool.*` bounds/queueing and batch's `consumerSize` cap + backpressure semantics are all unchanged); below JDK 21 it warns at startup and falls back to platform threads |

## SSL Configuration

Standard Spring Boot SSL configuration:

```properties
server.ssl.enabled=true
server.ssl.key-store=classpath:keystore.p12
server.ssl.key-store-password=changeit
server.ssl.key-store-type=PKCS12
```

The management port also supports independent SSL configuration (prefix `management.server.ssl.*`), configured the same way.

## Management Port (Actuator)

```properties
# Enable standalone management port
management.server.port=9090
# Management endpoint base path
management.endpoints.web.base-path=/actuator
# Exposed endpoints
management.endpoints.web.exposure.include=health,info,metrics
# HTTP/2 and request body limit for the management port (prefix management.server.*; defaults
# apply when unset — the main server's server.* values are no longer reused)
management.server.http2.enabled=false
management.server.max-content-length=1048576
```

## Observability Metrics

When `spring-boot-starter-actuator` is on the classpath, the framework auto-registers `MicrometerWebMetrics` and collects the following metrics:

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `dispatcher.request.duration` | Timer | `method`, `path`, `status` | Request processing duration distribution |
| `dispatcher.exception` | Counter | `type`, `resolved` | Exception count, categorized by exception type and whether handled by `@ExceptionHandler` |
| `dispatcher.async.active.lifecycles` | Gauge | — | Asynchronous dispatches still in flight; a non-zero value means an async lifecycle never terminated (the inbound buf reference was not returned, which is the precondition for a ByteBuf leak) |
| `pool.{name}.active.threads` | Gauge | — | Active thread count for the named pool |
| `pool.{name}.queue.size` | Gauge | — | Queue size for the named pool |
| `pool.{name}.completed.tasks` | Gauge | — | Completed task count for the named pool |
| `netty.connections.active` | Gauge | — | Active TCP connections |
| `netty.eventloop.pending.tasks` | Gauge | — | Pending tasks across all EventLoops |

`{name}` is the pool name, matching the name passed to `register()` or the Spring bean name.

## View Rendering (spring-web-view)

| Property | Default | Description |
|----------|---------|-------------|
| `spring.web.view.engine` | none (all available) | Enabled template engines (comma-separated): `thymeleaf` / `freemarker` / `beetl`; **if unset, all engines on the classpath are registered** |
| `spring.thymeleaf.prefix` | `templates/` | Thymeleaf template prefix (classpath-relative) |
| `spring.thymeleaf.suffix` | `.html` | Thymeleaf template suffix |
| `spring.thymeleaf.cache` | `true` | Template cache (set `false` in development for hot reload) |
| `spring.freemarker.prefix` | `templates/` | FreeMarker template prefix (classpath-relative) |
| `spring.freemarker.suffix` | `.ftl` | FreeMarker template suffix |
| `spring.freemarker.cache` | `true` | Template cache |
| `spring.beetl.prefix` | `templates/` | Beetl template prefix (classpath-relative) |
| `spring.beetl.suffix` | `.btl` | Beetl template suffix |
| `spring.beetl.cache` | `true` | Template cache |
| `spring.thymeleaf.encoding` | `UTF-8` | Rendering charset, also written to `Content-Type`; FreeMarker/Beetl default UTF-8 (no separate key, aligned with Boot) |
| `spring.mvc.view.prefix` | `/jsp/` | Prefix for **JSP views only** (aligns with Boot's JSP semantics); template engines use their own `spring.{engine}.prefix` |
| `spring.mvc.view.suffix` | `.jsp` | Suffix for **JSP views only** |

> Full usage: [View Rendering](view.md).

## OpenAPI Documentation

| Property | Default | Description |
|----------|---------|-------------|
| `springperf.openapi.title` | `Spring Perf Web API` | API document title |
| `springperf.openapi.version` | `1.0.0` | API document version |
| `springperf.openapi.description` | `Spring Perf Web API` | API document description |

## Swagger UI

| Property | Default | Description |
|----------|---------|-------------|
| `springperf.swagger-ui.webjar-version` | `5.2.0` | Swagger UI webjar version, must match `org.webjars:swagger-ui` dependency version |

## Complete Configuration Example

```properties
# Server
server.port=8080
server.servlet.context-path=/api

# HTTP
server.http.max-content-length=5242880
server.http.timeout=15000

# Async
spring.mvc.async.request-timeout=30s

# Graceful shutdown
server.shutdown.grace-period=30s

# SSL
server.ssl.enabled=false

# Netty
server.netty.workers=0
server.netty.write-buffer-low-watermark=8192
server.netty.write-buffer-high-watermark=32768

# HTTP/2
server.http2.enabled=false

# Thread pool
pool.core-pool-size=100
pool.max-pool-size=500
pool.keep-alive-time=120
pool.queue-capacity=10000
pool.default-execute-mode=eventloop  # or pool name

# Startup validation
server.check-on-startup=true

# Management port
management.server.port=9090
management.endpoints.web.exposure.include=health,info

# OpenAPI
springperf.openapi.title=My API
springperf.openapi.version=2.0.0
springperf.openapi.description=My API Description
```
