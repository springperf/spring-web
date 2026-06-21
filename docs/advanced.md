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

## 流式响应

### StreamEmitter

逐块发送数据：

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

### 自定义流式 JSON

```java
@GetMapping("/stream-json")
public StreamJsonEmitter streamJson() {
    return (StreamJsonEmitter) new StreamJsonEmitter()
            .onStarted((emitter, request, response) -> {
                response.getHeaders().setContentType(MediaType.APPLICATION_NDJSON);
            });
    // 返回 StreamJsonEmitter 后框架会自动处理流式写入
}
```

### SSE（服务端推送）

```java
@GetMapping("/events")
public SseEmitter sseEvents() {
    SseEmitter emitter = new SseEmitter(60000L); // 1min 超时

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

### 文本流

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

- 没有 `@RunInPool` 注解时，处理器方法在 Netty EventLoop 线程中执行
- **警告**：不要在 EventLoop 线程中执行阻塞操作（DB 查询、RPC 调用、文件 IO、Thread.sleep）

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