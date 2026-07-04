> English | [中文](../advanced.md)

# Advanced Topics

---

## Async Processing

### DeferredResult

Defer request processing to another thread:

```java
@GetMapping("/async-task")
public DeferredResult<Result<String>> asyncTask() {
    DeferredResult<Result<String>> result = new DeferredResult<>(5000L); // 5s timeout

    taskQueue.execute(() -> {
        try {
            Thread.sleep(2000); // Simulate time-consuming operation
            result.setResult(Result.ok("Done"));
        } catch (Exception e) {
            result.setErrorResult(Result.fail(500, e.getMessage()));
        }
    });

    return result;
}
```

Timeout and error callbacks:

```java
DeferredResult<Result<String>> result = new DeferredResult<>(5000L);
result.onTimeout(() -> result.setResult(Result.fail(408, "Request timeout")));
result.onError(ex -> result.setResult(Result.fail(500, ex.getMessage())));
result.onCompletion(() -> log.info("Async request completed"));
```

### Callable

Simple async execution:

```java
@GetMapping("/callable")
public Callable<Result<String>> callable() {
    return () -> {
        Thread.sleep(2000);
        return Result.ok("Async result");
    };
}
```

### Timeout Configuration

```java
@GetMapping("/async-timeout")
public DeferredResult<Result<String>> withTimeout() {
    // First priority: constructor parameter 5000ms
    // Second priority: server.http.timeout configuration
    // Third priority: no timeout
    return new DeferredResult<>(5000L);
}
```

### Async Interceptors

```java
@Component
public class AsyncMonitorInterceptor implements DeferredResultProcessingInterceptor {

    @Override
    public <T> boolean handleTimeout(PerfNativeWebRequest request, DeferredResult<T> result) {
        log.warn("DeferredResult timeout");
        return true; // true = continue processing
    }

    @Override
    public <T> void afterCompletion(PerfNativeWebRequest request, DeferredResult<T> result) {
        log.info("DeferredResult completed");
    }
}

// Similar CallableProcessingInterceptor for Callable
```

---

## Batch Processing

Through the `spring-web-batch` module, high-concurrency single requests can be transparently aggregated into a batch, processed by a batch handler method in one go. Suitable for IO-intensive scenarios (batch DB queries, batch RPC calls).

### Add Dependency

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-batch</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

### Quick Start

**Step 1: Define a BatchRequest subclass**

```java
public class GetUserRequest extends BatchRequest<UserResp> {
    Long id;
    String lang;
}
```

Field names match single-method parameter names; the framework injects values automatically.

**Step 2: Write the single-request endpoint**

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/{id}")
    public BatchRequest<UserResp> getUser(@PathVariable Long id,
                                           @RequestParam String lang) {
        return null; // Method body won't execute — framework takes over
    }
}
```

Change the return type to `BatchRequest<R>` (a subclass of `DeferredResult`). The method body will not actually execute.

**Step 3: Write the batch handler method**

```java
@BatchMapping(method = "getUser")
public void batchGetUser(List<GetUserRequest> batch) {
    Map<Long, UserResp> dbResult = userService.batchQuery(batch);
    for (GetUserRequest req : batch) {
        UserResp resp = dbResult.get(req.id);
        if (resp != null) {
            req.setResult(resp);
        } else {
            req.setError(new NotFoundException("user not found: " + req.id));
        }
    }
}
```

- `@BatchMapping(method = "getUser")` associates with the single-request endpoint `getUser`
- The **only parameter** must be `List<? extends BatchRequest<?>>` — the batch of requests to process
- Read injected parameter values via `req.fieldName` (`req.id`, `req.lang`)
- Call `req.setResult()` or `req.setError()` for each request

### Complete Request Flow

```
Request arrives at EventLoop
  │
  ├── WebFilter → Cors → Interceptor.preHandle → argument resolution
  │
  ├── InvokableHandlerMethod.invoke()
  │     └── BatchInvoker creates GetUserRequest instance (injects by constructor parameter position)
  │     └── Enqueues to RingBuffer
  │     └── Returns BatchRequest (i.e., DeferredResult)
  │
  ├── AsyncSupportRegistry suspends request
  │
  ════════════════════════════════════════════════
  │
  ├── 1 Disruptor consumer thread accumulates events
  │     ├── endOfBatch or buffer full → submit to bizExecutor
  │     └── bizExecutor saturated → CallerRunsPolicy → consumer self-executes → RingBuffer backpressure
  │
  ├── bizExecutor thread executes batchGetUser(List<GetUserRequest>)
  │
  └── req.setResult(resp) → asyncDispatch → write response
```

### @BatchMapping Annotation

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BatchMapping {

    /** RingBuffer capacity, must be power of 2. Default 4096. */
    int ringBufferSize() default 4096;

    /** Disruptor wait strategy. Default YIELDING. */
    WaitStrategy waitStrategy() default WaitStrategy.BLOCKING;

    /** Backpressure strategy when RingBuffer is full. Default BLOCK. */
    Backpressure backpressure() default Backpressure.BLOCK;

    /** Associated single-request method name. Default: same as batch method name. */
    String method() default "";

    /** Max batch size. <= 0 means rely on Disruptor endOfBatch signal. */
    int maxBatchSize() default 0;

    /** Max concurrent processing threads, including the Disruptor consumer thread. Default: CPU cores. */
    int consumerSize() default -1;
}
```

#### Backpressure Strategies

| Strategy | Behavior |
|----------|----------|
| `BLOCK` | EventLoop blocks waiting for RingBuffer slot |
| `DROP` | Drops the request (no response) |
| `THROW` | Throws `BatchOverflowException` → ExceptionRegistry → **429** |

#### Wait Strategies

| Strategy | Description |
|----------|-------------|
| `BLOCKING` (default) | Uses lock + Condition, CPU-efficient, recommended |
| `YIELDING` | Consumer busy-waits + Thread.yield(), low latency |
| `SLEEPING` | Busy-wait + sleep, balances latency and CPU |
| `BUSY_SPIN` | Pure busy-wait, lowest latency, highest CPU usage |

#### maxBatchSize

Accumulate a certain number of requests before triggering batch processing, instead of relying solely on Disruptor's `endOfBatch` signal. `<= 0` means fully reliant on `endOfBatch`.

#### consumerSize and Thread Model

`@BatchMapping` creates an independent Disruptor queue per method:

- **1 Disruptor consumer thread**: pulls events from RingBuffer, accumulates buffer, triggers batch processing
- **ThreadPoolExecutor(0, consumerSize, SynchronousQueue + CallerRunsPolicy)**: executes the business method

When the TPE is fully saturated, `CallerRunsPolicy` causes the consumer thread to execute tasks directly, blocking RingBuffer consumption → backpressure on producers. This is an automatic, configuration-free backpressure mechanism.

No `@RunInPool` annotation needed — `@BatchMapping` has its own thread pool management.

### Constructor Matching Rules

The framework invokes the `BatchRequest` subclass constructor at runtime, passing resolved parameter values by position. Constructor parameter types must match single-method parameter types **in exact order**:

```java
// Single-method parameters
UserResp getUser(@PathVariable Long id,
                 @RequestParam String lang)

// BatchRequest subclass — constructor parameters match by position
public class GetUserRequest extends BatchRequest<UserResp> {
    private final Long id;
    private final String lang;

    public GetUserRequest(Long id, String lang) {
        this.id = id;
        this.lang = lang;
    }
}
```

- Matching: parameter types correspond by position
- Constructor parameter types are strictly validated against the single method at startup; mismatch throws `IllegalStateException`
- No dependency on field names or `-parameters` compiler flag
- Values accessed via `req.id`, `req.lang` in the batch handler

### Error Handling

| Scenario | Behavior | HTTP Status |
|----------|----------|-------------|
| RingBuffer full, backpressure BLOCK | EventLoop blocks waiting for slot | - |
| RingBuffer full, backpressure DROP | Request dropped | - |
| RingBuffer full, backpressure THROW | `BatchOverflowException` → ExceptionRegistry | **429** |
| Batch processing exception | Iterate all incomplete requests, call setError(e) | 500 |
| Duplicate setResult | Silently ignored (completed guard) | - |
| Parameter name/type mismatch | IllegalStateException at startup | - |

---

## Streaming Responses

### StreamEmitter

Send data in chunks:

```java
@GetMapping("/stream")
public StreamEmitter streamData() {
    StreamEmitter emitter = new StreamEmitter();

    taskQueue.execute(() -> {
        try {
            for (int i = 0; i < 100; i++) {
                emitter.send("chunk-" + i + "\n");
                Thread.sleep(100);
            }
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    });

    return emitter;
}
```

### Custom Streaming JSON

```java
@GetMapping("/stream-json")
public StreamJsonEmitter streamJson() {
    return (StreamJsonEmitter) new StreamJsonEmitter()
            .onStarted((emitter, request, response) -> {
                response.getHeaders().setContentType(MediaType.APPLICATION_NDJSON);
            });
    // Returning StreamJsonEmitter — framework handles streaming automatically
}
```

### SSE (Server-Sent Events)

```java
@GetMapping("/events")
public SseEmitter sseEvents() {
    SseEmitter emitter = new SseEmitter(60000L); // 1min timeout

    taskQueue.execute(() -> {
        try {
            for (int i = 0; i < 10; i++) {
                emitter.send(SseEmitter.event()
                    .id(String.valueOf(i))
                    .name("message")
                    .data("Hello " + i));
                Thread.sleep(1000);
            }
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    });

    return emitter;
}
```

### Text Stream

```java
@GetMapping("/text-stream")
public TextStreamEmitter textStream() {
    TextStreamEmitter emitter = new TextStreamEmitter();

    taskQueue.execute(() -> {
        try {
            for (int i = 0; i < 5; i++) {
                emitter.send("Line " + i + "\n");
                Thread.sleep(500);
            }
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    });

    return emitter;
}
```

---

## Reactive Support

Requires the `reactive-streams` dependency.

### @ReactiveSupport Annotation

```java
@RestController
@ReactiveSupport(highWaterMark = 256, lowWaterMark = 64)
public class ReactiveController {

    @GetMapping("/reactive")
    public Publisher<Data> reactiveEndpoint() {
        return Flux.range(1, 1000)
                .map(i -> new Data(i, "item-" + i))
                .subscribeOn(Schedulers.parallel());
    }
}
```

| Attribute | Description |
|-----------|-------------|
| `highWaterMark` | Backpressure high water mark; pauses upstream push above this |
| `lowWaterMark` | Backpressure low water mark; resumes upstream push below this |
| `streamEmitterType` | Streaming output type (`StreamEmitter` / `SseEmitter`) |
| `timeout` | Timeout duration |

### Publisher → DeferredResult

Automatically adapts to `DeferredResult` when `Publisher` returns a single element.

### Publisher → StreamEmitter

Automatically adapts to streaming output when `Publisher` returns multiple elements.

---

## Thread Model & @RunInPool

### Default Behavior

- Without `@RunInPool`, handler methods execute on the `default` business thread pool (configured via `pool.*` properties)
- Lightweight / pure CPU endpoints can use `@RunInPool(RunInPool.EVENTLOOP)` to execute on the Netty EventLoop
- Set `pool.default-execute-mode=eventloop` to globally switch back to EventLoop

### @RunInPool

```java
@GetMapping("/blocking-db")
@RunInPool // Uses default business thread pool ("default")
public Result<Data> queryDatabase() {
    // This is a blocking operation — safe here
    return Result.ok(dataRepository.findById(1L));
}

@GetMapping("/heavy-compute")
@RunInPool("compute") // Uses custom thread pool named "compute"
public Result<Data> heavyCompute() {
    return Result.ok(computeExpensiveResult());
}

@GetMapping("/health")
@RunInPool(RunInPool.EVENTLOOP) // Lightweight endpoint executes directly on EventLoop
public String health() {
    return "OK";
}
```

### Custom Thread Pool

```java
@Bean
public BizPoolRegistry bizPoolRegistry() {
    BizPoolRegistry registry = new BizPoolRegistry();
    registry.register("db-io", new ThreadPoolExecutor(
        30, 100, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000)
    ));
    registry.register("rpc-io", new ThreadPoolExecutor(
        20, 50, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(500)
    ));
    return registry;
}
```

---

## CORS Configuration

### Annotation-Based

```java
@RestController
@CrossOrigin(origins = "https://example.com")
public class CrossOriginController {

    @GetMapping("/data")
    @CrossOrigin(allowCredentials = "true")
    public Result<Data> getData() {
        return Result.ok(data);
    }
}
```

### Programmatic

```java
@Configuration
public class CorsConfig {

    @Bean
    public CorsRegistration corsRegistration() {
        CorsRegistration registration = new CorsRegistration("/api/**");
        registration.allowedOrigins("https://example.com")
                    .allowedMethods("GET", "POST")
                    .allowCredentials(true)
                    .maxAge(3600);
        return registration;
    }
}
```

---

## Static Resources

```java
@Configuration
public class ResourceConfig {

    @Bean
    public ResourceHandlerRegistration resourceHandlerRegistration() {
        return new ResourceHandlerRegistration("/static/**")
                .addResourceLocations("classpath:/public/")
                .setCachePeriod(3600);
    }
}
```

---

## SSL Configuration

```properties
server.ssl.enabled=true
server.ssl.key-store=classpath:keystore.p12
server.ssl.key-store-password=changeit
server.ssl.key-store-type=PKCS12
```

---

## Actuator Management Endpoints

Supports a standalone management port:

```properties
# Standalone port
management.server.port=9090

# Exposed endpoints
management.endpoints.web.exposure.include=health,info,metrics,env

# Base path
management.endpoints.web.base-path=/actuator
```

The framework starts an independent Netty server for Actuator (if configured with a standalone port), with its own `MappingRegistry` and `DispatcherHandler`.

---

## Performance Tips

> See [Performance Principles](performance-principles.md) for detailed analysis

1. **Avoid blocking in EventLoop**: use `@RunInPool` for DB/RPC/IO operations
2. **Configure thread pools appropriately**: tune `pool.core-pool-size` and `pool.max-pool-size` for your workload
3. **Use async return values**: prefer `DeferredResult` or `StreamEmitter` for long-running operations
4. **Control request body size**: adjust `server.http.max-content-length` as needed
5. **Set timeouts**: configure reasonable timeouts for async operations to prevent resource leaks
6. **Enable startup validation**: `server.check-on-startup=true` catches Mapping configuration errors at startup
7. **Choose the right JSON library**: Jackson is stable and general-purpose; Fastjson 2 has higher throughput in some scenarios