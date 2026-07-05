> [English](en/advanced.md) | 中文

# 高级主题

---

## 异步处理

### DeferredResult

将请求处理推迟到其他线程完成：

```java
@GetMapping("/async-task")
public DeferredResult<Result<String>> asyncTask() {
    DeferredResult<Result<String>> result = new DeferredResult<>(5000L); // 5s 超时

    taskQueue.execute(() -> {
        try {
            Thread.sleep(2000); // 模拟耗时操作
            result.setResult(Result.ok("完成"));
        } catch (Exception e) {
            result.setErrorResult(Result.fail(500, e.getMessage()));
        }
    });

    return result;
}
```

超时或异常时的回调：

```java
DeferredResult<Result<String>> result = new DeferredResult<>(5000L);
result.onTimeout(() -> result.setResult(Result.fail(408, "请求超时")));
result.onError(ex -> result.setResult(Result.fail(500, ex.getMessage())));
result.onCompletion(() -> log.info("异步请求完成"));
```

### Callable

简单异步执行：

```java
@GetMapping("/callable")
public Callable<Result<String>> callable() {
    return () -> {
        Thread.sleep(2000);
        return Result.ok("异步结果");
    };
}
```

### 超时配置

```java
@GetMapping("/async-timeout")
public DeferredResult<Result<String>> withTimeout() {
    // 第一优先级：构造参数 5000ms
    // 第二优先级：server.http.timeout 配置
    // 第三优先级：无超时
    return new DeferredResult<>(5000L);
}
```

### 异步拦截器

```java
@Component
public class AsyncMonitorInterceptor implements DeferredResultProcessingInterceptor {

    @Override
    public <T> boolean handleTimeout(PerfNativeWebRequest request, DeferredResult<T> result) {
        log.warn("DeferredResult 超时");
        return true; // true = 继续处理
    }

    @Override
    public <T> void afterCompletion(PerfNativeWebRequest request, DeferredResult<T> result) {
        log.info("DeferredResult 完成");
    }
}

// 类似的 CallableProcessingInterceptor 用于 Callable
```

---

## 批量处理

通过 `spring-web-batch` 模块，可以将高并发的单请求透明聚合为批量，由批量处理方法一次处理完所有请求，适合 IO 密集型场景（批量 DB 查询、批量 RPC 调用）。

### 引入依赖

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-web-batch</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

### 快速上手

**第 1 步：编写单请求端点**

将普通 Controller 方法的返回值类型改为 `BatchRequest<R>`（即 `DeferredResult` 的子类），方法体不会实际执行：

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/{id}")
    public BatchRequest<UserResp> getUser(@PathVariable Long id,
                                           @RequestParam String lang) {
        return null; // 方法体不会被执行，由框架接管
    }
}
```

**第 2 步：定义 BatchRequest 子类**

构造器参数类型与单请求端点的参数按位置匹配，框架通过反射调用构造器注入：

```java
public class GetUserRequest extends BatchRequest<UserResp> {
    private final Long id;
    private final String lang;

    public GetUserRequest(Long id, String lang) {
        this.id = id;
        this.lang = lang;
    }
}
```

**第 3 步：编写批量处理方法**

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

- `@BatchMapping(method = "getUser")` 关联到单请求端点 `getUser`
- **唯一参数**必须为 `List<? extends BatchRequest<?>>`，即待处理的批量请求
- 通过 `req.fieldName` 读取已注入的参数值（`req.id`、`req.lang`）
- 逐个调用 `req.setResult()` 或 `req.setError()` 完成每个请求

### 完整请求流转

```
请求到达 EventLoop
  │
  ├── WebFilter → Cors → Interceptor.preHandle → 参数解析
  │
  ├── InvokableHandlerMethod.invoke()
  │     └── BatchInvoker 创建 GetUserRequest 实例（按构造函数参数位置注入）
  │     └── 入队 RingBuffer
  │     └── 返回 BatchRequest（即 DeferredResult）
  │
  ├── AsyncSupportRegistry 挂起请求
  │
  ════════════════════════════════════════════════
  │
  ├── 1 个 Disruptor 消费者线程积累事件
  │     ├── endOfBatch 或 buffer 满 → 提交到 bizExecutor
  │     └── bizExecutor 饱和 → CallerRunsPolicy → 消费者自执行 → RingBuffer 背压
  │
  ├── bizExecutor 线程执行 batchGetUser(List<GetUserRequest>)
  │
  └── req.setResult(resp) → asyncDispatch → 写响应
```

### @BatchMapping 注解

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BatchMapping {

    /** RingBuffer 容量，必须为 2 的幂。默认 4096。 */
    int ringBufferSize() default 4096;

    /** Disruptor 等待策略。默认 BLOCKING。 */
    WaitStrategy waitStrategy() default WaitStrategy.BLOCKING;

    /** RingBuffer 满时的背压策略。默认 BLOCK。 */
    Backpressure backpressure() default Backpressure.BLOCK;

    /** 被关联的单请求方法名。默认与批量处理方法同名。 */
    String method() default "";

    /** 单次批处理最大请求数。达到该值时触发批量处理。Disruptor endOfBatch 信号也会触发。默认 100。 */
    int maxBatchSize() default 100;

    /** 最大并发处理线程数，包括 Disruptor 消费者线程在内。默认 CPU 核数。 */
    int consumerSize() default -1;
}
```

#### 背压策略

| 策略 | 行为 |
|------|------|
| `BLOCK` | EventLoop 阻塞等待 RingBuffer 槽位 |
| `DROP` | 直接丢弃请求（无响应） |
| `THROW` | 抛出 `BatchOverflowException` → ExceptionRegistry → **429** |

#### 等待策略

| 策略 | 说明 |
|------|------|
| `BLOCKING`（默认） | 使用锁 + Condition，节省 CPU，推荐 |
| `YIELDING` | 消费者忙等 + Thread.yield()，低延迟 |
| `SLEEPING` | 忙等 + sleep，平衡延迟与 CPU |
| `BUSY_SPIN` | 纯忙等，最低延迟，最高 CPU 占用 |

#### maxBatchSize

累积一定数量的请求后再触发批处理，而非仅依赖 Disruptor 的 `endOfBatch` 信号。默认 100，`<= 0` 表示完全依赖 `endOfBatch`。

#### consumerSize 与线程模型

`@BatchMapping` 为每个方法创建独立的 Disruptor 队列，内部线程模型：

- **1 个 Disruptor 消费者线程**：负责从 RingBuffer 拉取事件、累积 buffer、触发批处理
- **ThreadPoolExecutor(0, consumerSize, SynchronousQueue + CallerRunsPolicy)**：实际执行业务方法的线程池

当 TPE 所有线程繁忙时，`CallerRunsPolicy` 使消费者线程直接执行任务，从而阻塞 RingBuffer 消费 → 生产者被背压。这是一种自动、无需配置的反压机制。

无需使用 `@RunInPool` 注解——`@BatchMapping` 拥有独立的线程池管理。

### 构造函数匹配规则

框架在运行时直接调用 `BatchRequest` 子类的构造函数，将单方法已解析的参数值按位置传递。构造函数参数类型必须与单方法参数类型**按序完全一致**：

```java
// 单方法参数
UserResp getUser(@PathVariable Long id,
                 @RequestParam String lang)

// BatchRequest 子类 — 构造参数按位置匹配
public class GetUserRequest extends BatchRequest<UserResp> {
    private final Long id;
    private final String lang;

    public GetUserRequest(Long id, String lang) {
        this.id = id;
        this.lang = lang;
    }
}
```

- 匹配方式：参数类型按位置一一对应
- 启动期严格校验构造函数的参数类型列表与单方法一致，不匹配直接抛出 `IllegalStateException`
- 无需依赖字段名、无需 `-parameters` 编译参数
- 批量处理方法中通过 `req.id`、`req.lang` 读取值

### 错误处理

| 场景 | 行为 | HTTP 状态码 |
|------|------|-------------|
| RingBuffer 满，背压 BLOCK | EventLoop 阻塞等待槽位 | - |
| RingBuffer 满，背压 DROP | 丢弃请求 | - |
| RingBuffer 满，背压 THROW | `BatchOverflowException` → ExceptionRegistry | **429** |
| 批量处理异常 | 遍历所有未完成的 request 调用 setError(e) | 500 |
| 重复调用 setResult | 静默忽略（completed 守卫） | - |
| 参数名/类型不匹配 | 启动时 IllegalStateException | - |

---

## 流式响应

### SSE（服务端推送）

```java
@GetMapping("/events")
public SseEmitter sseEvents() {
    SseEmitter emitter = new SseEmitter(60000L); // 1min 超时

    taskQueue.execute(() -> {
        try {
            for (int i = 0; i < 10; i++) {
                emitter.send(ServerSentEvent.builder()
                    .id(String.valueOf(i))
                    .event("message")
                    .data("Hello " + i)
                    .build());
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

### 文本流

```java
@GetMapping("/text-stream")
public TextStreamEmitter textStream() {
    TextStreamEmitter emitter = new TextStreamEmitter();

    taskQueue.execute(() -> {
        try {
            for (int i = 0; i < 100; i++) {
                emitter.send("chunk-" + i);
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

### 自定义流式 JSON

```java
@GetMapping("/stream-json")
public StreamJsonEmitter streamJson() {
    return new StreamJsonEmitter(jsonConverter);
    // StreamJsonEmitter 自动设置 Content-Type: application/stream+json
}
```

---

## 响应式支持

需要添加 `reactive-streams` 依赖。

### @ReactiveSupport 注解

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

| 属性 | 说明 |
|------|------|
| `highWaterMark` | 背压高水位线，超过此值暂停上游推送 |
| `lowWaterMark` | 背压低水位线，低于此值恢复上游推送 |
| `streamEmitterType` | 流式输出类型（`StreamEmitter` / `SseEmitter`） |
| `timeout` | 超时时间 |

### Publisher → DeferredResult

当 `Publisher` 返回单个元素时自动适配为 `DeferredResult`。

### Publisher → StreamEmitter

当 `Publisher` 返回多元素时自动适配为流式输出。

---

## 线程模型与 @RunInPool

### 默认行为

- 没有 `@RunInPool` 注解时，处理器方法默认在 `default` 业务线程池中执行（通过 `pool.*` 配置）
- 轻量/纯 CPU 端点可使用 `@RunInPool(RunInPool.EVENTLOOP)` 在 Netty EventLoop 中执行
- 可通过配置 `pool.default-execute-mode=eventloop` 全局切换回 EventLoop

### @RunInPool

```java
@GetMapping("/blocking-db")
@RunInPool // 使用默认业务线程池（"default"）
public Result<Data> queryDatabase() {
    // 这里是 blocking 操作，安全
    return Result.ok(dataRepository.findById(1L));
}

@GetMapping("/heavy-compute")
@RunInPool("compute") // 使用名为 "compute" 的自定义线程池
public Result<Data> heavyCompute() {
    return Result.ok(computeExpensiveResult());
}

@GetMapping("/health")
@RunInPool(RunInPool.EVENTLOOP) // 轻量端点直接在 EventLoop 执行
public String health() {
    return "OK";
}
```

### 自定义线程池

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

## CORS 配置

### 注解方式

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

### 编程方式

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

## 静态资源

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

## SSL 配置

```properties
server.ssl.enabled=true
server.ssl.key-store=classpath:keystore.p12
server.ssl.key-store-password=changeit
server.ssl.key-store-type=PKCS12
```

---

## Actuator 管理端点

支持独立的管理端口：

```properties
# 独立端口
management.server.port=9090

# 暴露端点
management.endpoints.web.exposure.include=health,info,metrics,env

# 基础路径
management.endpoints.web.base-path=/actuator
```

框架会为 Actuator 启动独立的 Netty 服务器（如果配置了独立端口），并使用独立的 `MappingRegistry` 和 `DispatcherHandler`。

---

## 性能建议

> 性能原理的详细分析见 [性能原理文档](performance-principles.md)

1. **避免在 EventLoop 中阻塞**：DB/RPC/IO 操作使用 `@RunInPool`
2. **合理配置线程池**：根据业务场景调整 `pool.core-pool-size` 和 `pool.max-pool-size`
3. **使用异步返回值**：长耗时操作优先使用 `DeferredResult` 或 `StreamEmitter`
4. **控制请求体大小**：按需调整 `server.http.max-content-length`
5. **设置超时**：为异步操作设置合理的超时时间，避免资源泄漏
6. **开启启动校验**：`server.check-on-startup=true` 可在启动时发现 Mapping 配置错误
7. **选择合适的 JSON 库**：Jackson 通用稳定，Fastjson 2 在某些场景下吞吐更高