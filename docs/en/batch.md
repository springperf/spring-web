> English | [中文](../batch.md)

# Batch Processing Module

`spring-web-batch` transparently aggregates individual requests into batches, processed by a batch handler method at once. Ideal for IO-intensive scenarios (batch DB queries, batch RPC calls).

Built on **LMAX Disruptor** with lock-free ring buffer, backpressure strategies, wait strategies, and thread pool isolation.

---

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-batch</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

`spring-web-batch` is declared as `<scope>provided</scope>` in `spring-boot-starter-web` — you must add it explicitly even when using the starter.

### 2. Write a Controller

**Step 1: Define the single-request endpoint**

Change the return type to `BatchRequest<R>` (a subclass of `DeferredResult`). The method body won't execute:

```java
@RestController
@RequestMapping("/api")
public class UserController {

    @GetMapping("/users/{id}")
    public BatchRequest<UserResp> getUser(@PathVariable Long id,
                                          @RequestParam(defaultValue = "zh") String lang) {
        // Method body won't execute — BatchInvoker handles it
        return null;
    }
}
```

**Step 2: Define a BatchRequest subclass**

Constructor parameter types must match the single-request method parameters **in order**:

```java
public class GetUserRequest extends BatchRequest<UserResp> {

    private final Long id;
    private final String lang;

    public GetUserRequest(Long id, String lang) {
        this.id = id;
        this.lang = lang;
    }

    public Long getId() { return id; }
    public String getLang() { return lang; }
}
```

**Step 3: Write the batch handler**

Add a `@BatchMapping` method in the same Controller:

```java
@RestController
@RequestMapping("/api")
public class UserController {

    // ... single-request endpoint

    @BatchMapping(method = "getUser")
    public void batchGetUser(List<GetUserRequest> batch) {
        Map<Long, UserResp> dbResult = userService.batchQuery(batch);
        for (GetUserRequest req : batch) {
            UserResp resp = dbResult.get(req.getId());
            req.setResult(resp != null ? resp : new UserResp());
        }
    }
}
```

- `@BatchMapping(method = "getUser")` links to the single-request endpoint
- The **only parameter** must be `List<? extends BatchRequest<?>>`
- Call `setResult()` on each request to complete its HTTP response

---

## @BatchMapping Annotation

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BatchMapping {

    int ringBufferSize() default 4096;
    WaitStrategy waitStrategy() default WaitStrategy.BLOCKING;
    Backpressure backpressure() default Backpressure.BLOCK;
    String method() default "";
    int maxBatchSize() default 100;
    int consumerSize() default -1;

    enum WaitStrategy { YIELDING, BLOCKING, SLEEPING, BUSY_SPIN }
    enum Backpressure { BLOCK, DROP, THROW }
}
```

### Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `ringBufferSize` | 4096 | Ring buffer capacity, auto-normalized to power of 2 |
| `waitStrategy` | BLOCKING | Consumer wait strategy (see table) |
| `backpressure` | BLOCK | Backpressure strategy (see table) |
| `method` | "" | Single-request method name to intercept; defaults to the batch method name |
| `maxBatchSize` | 100 | Max requests per batch before flushing |
| `consumerSize` | -1 | Max concurrent processing threads; defaults to available processors |

### Backpressure Strategies

| Strategy | Behavior | Client Response |
|----------|----------|-----------------|
| `BLOCK` | Producer thread blocks until space is available | Connection suspended |
| `DROP` | Request is dropped with `BatchOverflowException` set | **429 Too Many Requests** |
| `THROW` | `BatchOverflowException` thrown synchronously | **429 Too Many Requests** |

### Wait Strategies

| Strategy | Use Case | CPU | Latency |
|----------|----------|-----|---------|
| `BLOCKING` | Idle periods, CPU-conservative | Low | High |
| `YIELDING` | Low latency, medium throughput | Medium | Medium |
| `SLEEPING` | CPU-conservative with responsiveness | Low | Medium-high |
| `BUSY_SPIN` | Ultra-low latency, high throughput | High (busy-waits) | Low |

### Thread Model

Each `@BatchMapping` method gets its own Disruptor queue:

```
EventLoop (producer)
    ↓ enqueue
RingBuffer
    ↓
Disruptor consumer (1 thread)
    ↓ accumulate → submit
bizExecutor pool (0 ~ consumerSize threads)
    ↓ execute
@BatchMapping method
```

- **Producer**: EventLoop enqueues directly via `BatchInvoker`
- **Consumer**: Single Disruptor consumer thread polls the ring buffer
- **Business pool**: 0 core threads, `SynchronousQueue`, `CallerRunsPolicy`. Zero threads at idle; consumer self-executes at capacity (built-in backpressure)

---

## Architecture

### Request Flow

```
HTTP Request
  ↓
EventLoop → DispatcherHandler route matching
  ↓
BatchInvoker creates BatchRequest instance (constructor arg injection)
  ↓
DisruptorQueue.enqueue() → RingBuffer
  ↓  Returns BatchRequest (DeferredResult)
HTTP response suspended
  ↓
Disruptor consumer:
  ├── onEvent() accumulates to buffer
  ├── endOfBatch or buffer full → submit to bizExecutor
  ↓
bizExecutor thread executes @BatchMapping method
  ↓
Iterate batch, call req.setResult() → DeferredResult completes
  ↓
EventLoop writes HTTP response
```

### Core Components

| Class | Responsibility |
|-------|---------------|
| `BatchRequest<R>` | Extends `DeferredResult<R>`, user-extended request base class |
| `@BatchMapping` | Marks batch handler methods, links to single-request method |
| `BatchRegistry` | Manages ring buffer lifecycle, installs `BatchInvoker` |
| `BatchInvoker` | Replaces controller method invocation, creates and enqueues `BatchRequest` |
| `DisruptorQueue` | Wraps LMAX Disruptor, provides enqueue/backpressure/shutdown |
| `BufferingBatchHandler` | Disruptor consumer, accumulates and submits batches |

---

### Why Disruptor

- **Lock-free concurrency**: `ProducerType.MULTI` supports multiple EventLoop threads enqueuing concurrently with no lock contention
- **Pre-allocated event slots**: `BatchEvent` instances are pre-created in the RingBuffer, reducing GC pressure
- **endOfBatch signal**: Disruptor notifies the consumer when a batch of events is complete, minimizing batching latency
- **Natural backpressure**: When the RingBuffer is full, the producer blocks → EventLoop backpressures to the TCP layer

---

## Observability

### Metrics

When Micrometer is on the classpath (via `spring-boot-starter-actuator`), the following metrics are automatically registered:

| Metric | Type | Tag | Description |
|--------|------|-----|-------------|
| `batch.enqueue.total` | Counter | `queue` | Total enqueue attempts |
| `batch.enqueue.dropped` | Counter | `queue` | Requests dropped by backpressure |
| `batch.enqueue.overflow` | Counter | `queue` | Overflow exceptions thrown |
| `batch.process.duration` | Timer | `queue` | Batch processing duration |
| `batch.process.batch.size` | DistributionSummary | `queue` | Batch size distribution |
| `batch.process.requests` | Counter | `queue` | Requests completed via batch |
| `batch.queue.remaining` | Gauge | `queue` | Ring buffer remaining capacity |
| `batch.queue.capacity` | Gauge | `queue` | Ring buffer total capacity |

All metrics are tagged with `queue=<batch:ClassName.methodName>`.

### Monitoring Tips

- **`batch.queue.remaining`**: Alert when below 10% of buffer size — backpressure is near limit
- **`batch.process.duration`**: Watch p99 — sustained increase indicates worker pool saturation
- **`batch.enqueue.dropped`**: Drops indicate insufficient buffer or consumer speed; adjust `ringBufferSize` or `consumerSize`

---

## Configuration Guide

### Tuning Recommendations

| Scenario | Recommended Configuration | Notes |
|----------|--------------------------|-------|
| High throughput, latency-tolerant | `ringBufferSize=16384, maxBatchSize=500` | Larger buffer, larger batches |
| Latency-sensitive | `ringBufferSize=1024, maxBatchSize=50, waitStrategy=BUSY_SPIN` | Small buffer, aggressive wait |
| Resource-constrained (1c1g) | `ringBufferSize=4096, consumerSize=1` | Single consumer thread |
| Batch DB queries | `maxBatchSize=200` | Most DB IN queries perform best at ~200 |
| Batch RPC calls | `maxBatchSize=50, consumerSize=4` | Parallel RPC, smaller batches |

### Consumer Thread Pool

`consumerSize` controls max concurrent processing threads. Default `-1` = available processors.

The pool uses `SynchronousQueue` + `CallerRunsPolicy`:
- Zero threads at idle
- Consumer self-executes at capacity (backpressure)
- No manual core thread tuning needed

---

## Important Notes

### Method Body Replacement

When `@BatchMapping` is installed, the linked `@RequestMapping` method body **will NOT execute**. `BatchInvoker` directly constructs `BatchRequest` instances and enqueues them. Any logic in the single-request method (logging, validation, etc.) is skipped — handle these in the `@BatchMapping` method instead.

### Timeout

`BatchRequest` defaults to a 30-second timeout. The `DeferredResult` triggers on timeout. Use the parameterized constructor for custom timeouts:

```java
public class GetUserRequest extends BatchRequest<UserResp> {
    public GetUserRequest(Long id, String lang) {
        super(10000L); // 10-second timeout
        this.id = id;
        this.lang = lang;
    }
}
```

### Error Handling

| Scenario | Handling | Client Response |
|----------|----------|-----------------|
| RingBuffer full, backpressure BLOCK | EventLoop blocks | Connection suspended |
| RingBuffer full, backpressure DROP | `BatchOverflowException` set on request | 429 |
| RingBuffer full, backpressure THROW | Synchronous exception thrown | 429 |
| Batch processing exception | `setError(e)` on all pending requests | 500 |
| Request timeout | `DeferredResult` timeout callback | 503 |

### Debugging

- Enable DEBUG logging for `io.springperf.web.batch` to observe enqueue and batch processing
- Use Micrometer metrics to monitor queue depth and processing latency
- Adjust `ringBufferSize` and `consumerSize` based on observed metrics