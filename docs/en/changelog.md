# Changelog

> [中文版本](../../CHANGELOG.md)

This project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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