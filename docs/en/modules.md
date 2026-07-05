> English | [中文](../modules.md)

# Module Details

```
spring-web-parent (aggregate POM)
├── spring-web                  Core framework
├── spring-web-support          Optional Servlet bridge layer
├── spring-web-batch            Batch request processing (optional)
├── spring-web-websocket        WebSocket support (optional)
├── spring-boot-starter-web     Spring Boot auto-configuration
├── spring-web-test             Test application
├── spring-web-support-test     Support module tests
├── spring-web-benchmark        JMH performance benchmarks
└── spring-web-examples         Runnable example applications
```

---

## spring-web (Core Framework)

### server — Netty Server

Non-blocking transport layer based on Netty 4.1. Requests are processed synchronously on EventLoop.

- **NettyHttpServer** implements `SmartLifecycle`, auto-start/stop with Spring container
- Uses `HttpServerCodec` + `ChunkedWriteHandler` for HTTP protocol handling
- Optional SSL support
- Pipeline: `HttpServerCodec` → `ChunkedWriteHandler` → `SupportMultipartAggregator` → `BackpressureHandler` → `NettyHttpHandler`
- `NettyHttpHandler` adapts Netty requests to the framework's `WebServerHttpRequest`/`WebServerHttpResponse`, then delegates to `HttpHandler`

### filter — WebFilter Chain

- `WebFilter` — SPI interface, filter chain triggered by `handleWithFilter()` after route matching, then calls back `handleAfterFilter()` upon completion
- `WebFilterRegistry` manages the filter list with ordering support
- Filter chain calls back `DispatcherHandler.handleAfterFilter()` upon completion via `DefaultFilterChain`
- Auto-registers all `WebFilter` beans from the Spring container

### core — Core Processing Pipeline

#### DispatcherHandler — Central Request Dispatcher

Request processing flow:

```
Request arrives
  ↓
DispatcherHandler.handle()
  ↓
MappingRegistry — Route matching, find @RequestMapping handler
  ↓
BizPoolRegistry — @RunInPool thread pool offload (optional)
  ↓
handleWithFilter() → WebFilterRegistry filter chain
  ↓  Filter chain calls back on completion
handleAfterFilter() — Initialize context
  ↓
  ├── doHandle() (fully matched):
  │   ├── CorsRegistry              — CORS check
  │   ├── InterceptorRegistry       — preHandle
  │   ├── ArgumentResolverRegistry  — Argument resolution
  │   ├── InvokableHandlerMethod    — Handler invocation
  │   ├── ReturnValueResolverRegistry — Return value handling
  │   └── InterceptorRegistry       — postHandle → afterCompletion
  └── 404/405 → ExceptionRegistry + afterCompletion
```

#### mapping — Request Mapping

- `MappingRegistry` — Scans all `@RequestMapping` methods in `@Controller`/`@RestController`
- Supports all `@RequestMapping` attributes: `value`/`path`, `method`, `params`, `headers`, `consumes`, `produces`
- Router optimizer chain (`RouterOptimizer`): prefix/suffix/full-path/loop optimization for efficient routing
- Supports dynamic route registration (`registerMappingAfterInit`) for Actuator endpoints
- `Matcher` system: `HttpMethodMatcher`, `ParamOrHeaderMatcher`, `ConsumeOrProduceMatcher`, etc.

#### arg — Argument Resolvers

Supports all standard Spring MVC parameter annotations:

| Annotation | Resolver | Description |
|------------|----------|-------------|
| `@PathVariable` | `PathVariableResolverProvider` | URL path variable |
| `@RequestParam` | `RequestParamResolverProvider` | Query/form parameters |
| `@RequestHeader` | `RequestHeaderResolverProvider` | Request headers |
| `@RequestBody` | `RequestBodyResolverProvider` | JSON body (via `HttpBodyCodecRegistry`) |
| `@RequestPart` | `RequestPartResolverProvider` | File upload (multipart) |
| `@ModelAttribute` | `ModelAttributeResolverProvider` | Data binding (via `WebDataBinder`) |

Additionally supports automatic resolution of: `HttpEntity`/`RequestEntity`, `MultipartFile`, `BindingResult`/`Errors`, `Locale`/`TimeZone`, `WebServerHttpRequest`/`WebServerHttpResponse`.

Data binding via `WebDataBinderRegistry` supports:
- `@InitBinder` methods (from `@ControllerAdvice`)
- `ConversionService` type conversion
- JSR-303 `Validator` validation
- `MessageCodesResolver` error code resolution

#### retval — Return Value Resolvers

Supported return value types:

| Return Type | Resolver |
|-------------|----------|
| `@ResponseBody` annotated object | `JsonBodyReturnValueResolver` |
| `ResponseEntity`/`HttpEntity` | `HttpEntityReturnValueResolver` |
| `DeferredResult` | `DeferredResultReturnValueResolver` |
| `Callable` | `CallableReturnValueResolver` |
| `ListenableFuture`/`CompletionStage` | Corresponding Future resolvers |
| `StreamEmitter`/`SseEmitter` | `StreamEmitterReturnValueResolver` |
| `Publisher` (Reactive) | `ReactiveReturnValueResolver` |
| `byte[]` | `ByteArrayReturnValueResolver` |
| `Resource` | `ResourceReturnValueResolver` |
| `InputStream` | `InputStreamReturnValueResolver` |
| `File` | `FileReturnValueResolver` |

#### interceptor — Handler Interceptors

- `HandlerInterceptor` defines three extension points:
  - `preHandle` — Before handler execution (return `false` to interrupt)
  - `postHandle` — After handler execution, before return value rendering
  - `afterCompletion` — After request completion (whether or not an exception occurred)
- `InterceptorRegistry` manages interceptor registration with path include/exclude patterns
- Auto-registers `HandlerInterceptor` or `InterceptorRegistration` beans from the Spring container

#### async — Async Support

- `DeferredResult` — Set return value from any thread
- `Callable` — Async execution returning a value
- `StreamEmitter` — Stream multiple data chunks
- `SseEmitter` / `SseJsonEmitter` — Server-Sent Events push
- Reactive support (optional dependency: reactive-streams):
  - `Publisher` → `DeferredResult` / `StreamEmitter` adaptation
  - Backpressure configuration (`@ReactiveSupport` annotation's `highWaterMark`/`lowWaterMark`)

#### exception — Exception Handling

- `ExceptionHandlerExceptionResolver` — Scans `@ExceptionHandler` methods in `@ControllerAdvice`
- `ResponseStatusExceptionResolver` — Handles `@ResponseStatus` and `ResponseStatusException`
- Supports custom `HandlerExceptionResolver` extensions

#### codec — HTTP Message Codec

- `HttpBodyConverter` wraps Spring's `HttpMessageConverter` for request body reading and response body writing
- `HttpBodyCodecRegistry` manages all converters, negotiates based on `Content-Type` and `Accept`
- Auto-registers all `HttpMessageConverter` beans from the Spring container
- `HttpBodyCodecInterceptor` provides write/read interception points

#### cors — Cross-Origin Resource Sharing

- Supports `@CrossOrigin` annotation
- Supports programmatic registration (`CorsRegistry` / `CorsRegistration`)
- `PerfCorsProcessor` based on Spring's `DefaultCorsProcessor`

#### resource — Static Resources

- `ResourceHandlerRegistry` manages static resource mappings
- Supports path-to-filesystem/classpath mapping

### http — HTTP Request/Response Abstraction

- `WebServerHttpRequest` / `WebServerHttpResponse` — Unified request/response interfaces within the framework
- File upload support: `NettyMultipartFile`, `NettyMultipartWebRequest`
- Reference counting (`acquire`/`release`) to prevent Netty ByteBuf leaks

### json — JSON Abstraction Layer

- `JsonConverter` SPI: unified `toJson`/`fromJson` interface
- `JacksonConverter` — Default implementation, based on Jackson
- `FastjsonConverter` — Optional implementation, based on Fastjson 2 (provided dependency)

### context — Component Container

- `WebComponent` — Base interface for all framework components (extends `Ordered`)
- `LifecycleWebComponent` — Component with lifecycle methods (3-phase init + destroy)
- `BaseWebComponent` — Abstract base class holding `WebContext` reference
- `WebComponentContainer` — Component registry base implementation
- `WebContext` — Application context, implements `InitializingBean`, drives component lifecycle

Component initialization phases:

```
initWithWebContext()   → Dependency assembly
initComponentPhase1()  → Scan Spring beans, register mappings, build strategy lists
initComponentPhase2()  → Build internal structures, route optimization
initComponentPhase3()  → Warmup, fail-fast validation
destroyComponent()     → Resource release
```

---

## spring-web-support (Servlet Bridge Layer)

Add this module when integration with the Servlet API ecosystem is needed.

### Features

| Feature | Implementation |
|---------|---------------|
| Servlet Filter integration | `FilterWrapper` wraps `javax.servlet.Filter` as `WebFilter` |
| Spring MVC interceptor bridge | `HandlerInterceptorWrapper` adapts Spring MVC's `HandlerInterceptor` |
| RequestBodyAdvice / ResponseBodyAdvice | `SupportHttpBodyCodecInterceptorRegistry` scans and adapts |
| Servlet API argument resolution | `HttpServletRequestProvider` / `HttpServletResponseProvider` |
| ResponseBodyEmitter | `ResponseBodyEmitterReturnValueResolver` |

### Use Cases

- Reusing existing Servlet Filters (e.g., Spring Security Filter Chain)
- Reusing existing `RequestBodyAdvice` / `ResponseBodyAdvice`
- Reusing existing Spring MVC `HandlerInterceptor`

Add dependency:

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-support</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

---

## spring-boot-starter-web (Auto-Configuration)

### Core Auto-Configuration

`SpringWebAutoConfiguration` auto-assembles:

- `ApplicationProperties` — Reads configuration from `Environment`
- `WebContext` — Application context, drives component lifecycle
- All Registry components (MappingRegistry, InterceptorRegistry, etc.)
- `NettyHttpServer` — Starts Netty server with optional SSL support

### Support Auto-Configuration

`SpringWebSupportAutoConfiguration` auto-assembles support module components when `spring-web-support` is on the classpath.

### Batch Auto-Configuration

`SpringWebBatchAutoConfiguration` auto-assembles batch processing support when `spring-web-batch` is on the classpath.

### Actuator Auto-Configuration

`ActuatorEndpointAutoConfiguration` auto-configures endpoints when Actuator is present:
- Registers `ExposableWebEndpoint` as framework routes
- Supports standalone management port (`management.server.port`)
- Independent Management Netty server

### SBA Client Auto-Configuration

`SpringBootAdminClientAutoConfiguration` emits `WebServerInitializedEvent` when SBA client is present, enabling Spring Boot Admin heartbeat registration.

### OpenAPI Auto-Configuration

`OpenApiAutoConfiguration` generates OpenAPI documentation endpoints when `springdoc-openapi` is on the classpath.

### Swagger UI Auto-Configuration

`SwaggerUiAutoConfiguration` registers Swagger UI static resource routes when Swagger UI is on the classpath.

### Application Context

`WebServerApplicationContextFactory` forces the use of `AnnotationConfigApplicationContext`, replacing Spring Boot's default `AnnotationConfigServletWebServerApplicationContext`.

---

## spring-web-batch (Batch Request Processing)

Transparent aggregation batch processing module. Merges high-concurrency same-type requests into a batch, processing all requests with a single batch business logic execution, significantly improving throughput for IO-intensive scenarios.

### Core Concepts

- **Pipeline semantics unchanged** — Filters, interceptors execute per-request, unaffected
- **Async reuse** — `BatchRequest<R>` extends `DeferredResult<R>`, leveraging the framework's existing async processing for suspend/resume
- **No active batching** — No time window; naturally accumulates when consumer processing is slower than production
- **Non-intrusive** — Use after adding dependency, no existing code structure changes needed

### Architecture

```
Request → EventLoop → BatchInvoker creates instance enqueued → RingBuffer
                                                                              │
                                                                        Disruptor consumer
                                                                              │
                                                                        Submitted to business thread pool
                                                                              │
                                                                        BatchHandler batch processing
                                                                              │
                                                   Per req.setResult() → asyncDispatch → write response
```

### Core Classes

| Class | Description |
|-------|-------------|
| `BatchRequest<R>` | Extends `DeferredResult<R>`, user-extended request base class; constructor parameter position matches method arguments |
| `@BatchMapping` | Annotation on batch handler methods, associates the corresponding single-request method |
| `BatchRegistry` | Manages all RingBuffer lifecycles (init/destroy), installs `BatchInvoker` |
| `BatchInvoker` | Replaces original Controller method invocation, creates `BatchRequest` instances from constructor parameters and enqueues them |
| `BatchRequestMetaData` | Stores per-method parameter type list, batch method reference, RingBuffer configuration, etc. |

### Dependency

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-batch</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

After adding the dependency, Spring Boot auto-configuration automatically assembles `BatchRegistry`.

---

## spring-web-websocket (WebSocket Support)

Lightweight WebSocket module based on Spring WebSocket API + Netty, routing WebSocket upgrade requests through the Netty pipeline.

`WebSocketRoutingHandler`, as a `ChannelInboundHandler`, is injected into the Netty pipeline. It intercepts `Upgrade: websocket` requests, completes the handshake, and switches the pipeline from HTTP mode to WebSocket frame mode. After handshake, it creates `NettyWebSocketSession` (implementing Spring's `WebSocketSession`), and subsequent frames are delegated directly to user-defined `WebSocketHandler`.

Supports path pattern matching (including `{pathVariable}`), per-handler override of Origin validation/idle timeout/heartbeat keepalive, and backpressure queues.

### Usage

Implement `WebSocketConfigurer` to register endpoints:

```java
@Configuration
public class MyWsConfig implements WebSocketConfigurer {
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(myHandler(), "/ws/echo");
    }
}
```

### Dependency

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-websocket</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```