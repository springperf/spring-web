# 11 · 异步与流式：DeferredResult / SSE / 响应式 / 无锁 Drain Loop

> [← 返回索引](00-README.md) | 上一篇：[10 · 横切关注点：拦截器、Filter、CORS、异常](10-cross-cutting.md) | 下一篇：[12 · spring-web-servlet 与 spring-web-mvc-support 桥接](12-support-bridge.md)

---

## 引子：异步与流式——从"挂起-恢复"到"无锁 Drain Loop"

[04 篇](04-request-pipeline.md) 的请求管线在 `ReturnValueResolverRegistry.resolveReturnValue` 之后分叉：

| 返回值类型 | 处理路径 | 线程模型 |
|-----------|---------|---------|
| `DeferredResult` / `Callable` | 挂起请求 → 工作线程计算 → 异步 dispatch → 重入返回值解析 | 业务线程池执行，EventLoop dispatch |
| `StreamEmitter` / SSE | 流式发送器 → `MpscArrayQueue` 入队 → drain loop 写出 | 业务线程入队，EventLoop 单线程排空 |
| `Publisher`（响应式） | 同 `StreamEmitter`（多值）或 `DeferredResult`（单值） | 按背压水位请求，EventLoop 驱动 |
| 同步返回值 | 直接写响应 | EventLoop 同步写 |

Spring MVC 的做法是：`DeferredResult` 和 `Callable` 通过 `WebAsyncManager` 的 `startCallableProcessing`/`startDeferredResultProcessing` 挂起请求，`setConcurrentResultAndDispatch` 恢复。SSE 通过 `ResponseBodyEmitter` 的 `BlockingQueue` + `SseEmitter` 发送数据，每个连接一个线程阻塞写。

本框架的做法是：**`DeferredResult`/`Callable`/`StreamEmitter`/`Publisher` 四类异步返回值统一通过 `AsyncSupportRegistry` 挂起，SSE 和流式通过 `MpscArrayQueue` + `AtomicInteger wip` 排空，所有写入统一在 EventLoop 线程完成，不加锁、不阻塞。**

---

## 一、`AsyncSupportRegistry`：统一挂起-恢复模型

### 1.1 四类异步返回值入口

`AsyncSupportRegistry`是异步处理的统一入口，管理两类拦截器链：

```java
// AsyncSupportRegistry.java
public class AsyncSupportRegistry extends WebComponentContainer {
    private final List<CallableProcessingInterceptor> callableInterceptors = new ArrayList<>();
    private final List<DeferredResultProcessingInterceptor> deferredResultInterceptors = new ArrayList<>();
```

所有异步返回值解析器都继承 `BaseAsyncReturnValueResolver`，持有 `AsyncSupportRegistry` 引用：

| 返回值解析器 | 适配类型 | 入口方式 |
|-------------|---------|---------|
| `DeferredResultReturnValueResolver` | `DeferredResult` | `startDeferredResultProcessing` |
| `CallableReturnValueResolver` | `Callable` | `startCallableProcessing`（包装为 `WebAsyncTask`） |
| `AsyncTaskReturnValueResolver` | `WebAsyncTask` | `startCallableProcessing` |
| `CompletionStageReturnValueResolver` | `CompletionStage` | 适配为 `DeferredResult` → `startDeferredResultProcessing` |
| `ListenableFutureReturnValueResolver` | `ListenableFuture` | 适配为 `DeferredResult` → `startDeferredResultProcessing` |
| `StreamEmitterReturnValueResolver` | `StreamEmitter` | `startDeferredResultProcessing`（绑定 `DeferredResult` 生命周期） |
| `ReactiveReturnValueResolver` | `Publisher` | 多值→`StreamEmitter`，单值→`DeferredResult` |

### 1.2 `startCallableProcessing`：工作线程执行

`startCallableProcessing`将 `Callable` 提交到 `AsyncTaskExecutor`：

```java
// AsyncSupportRegistry.java
public void startCallableProcessing(PerfAsyncWebRequest asyncWebRequest, WebAsyncTask<?> webAsyncTask) throws Exception {
    // ① 设置超时
    asyncWebRequest.setTimeout(timeout);

    // ② 构建拦截器链
    CallableInterceptorChainAdapter interceptorChain = WebAsyncSupportUtils.newCallableInterceptorChain(webAsyncTask, callableInterceptors);

    // ③ 注册超时/错误/完成处理器
    asyncWebRequest.addTimeoutHandler(() -> { ... });
    asyncWebRequest.addErrorHandler(ex -> { ... });
    asyncWebRequest.addCompletionHandler(() -> interceptorChain.triggerAfterCompletion(asyncWebRequest, callable));

    // ④ 提交到业务线程池（延迟到 asyncReadyCallback 执行）
    interceptorChain.applyBeforeConcurrentHandling(asyncWebRequest, callable);
    asyncWebRequest.startAsyncProcessing();
    asyncWebRequest.setAsyncReadyCallback(() -> {
        Future<?> future = executor.submit(() -> {
            interceptorChain.applyPreProcess(asyncWebRequest, callable);
            result = callable.call();                         // 业务线程执行
            result = interceptorChain.applyPostProcess(asyncWebRequest, callable, result);
            asyncWebRequest.setConcurrentResultAndDispatch(result);  // 恢复
        });
        asyncWebRequest.scheduleTimeoutIfNeeded();            // 延迟调度超时
    });
}
```

关键流程：业务线程池执行 `callable.call()` → `setConcurrentResultAndDispatch` → `dispatch()` → `DispatcherHandler.asyncDispatch`→ 重新进入 `ReturnValueResolverRegistry.resolveReturnValue`。

### 1.3 `startDeferredResultProcessing`：结果驱动恢复

`startDeferredResultProcessing`与 `Callable` 路径不同——计算在其他线程，框架只负责挂起和恢复：

```java
// AsyncSupportRegistry.java
public void startDeferredResultProcessing(PerfAsyncWebRequest asyncWebRequest, DeferredResult<?> deferredResult) throws Exception {
    // ① 设置超时
    asyncWebRequest.setTimeout(timeout);

    // ② 构建拦截器链
    DeferredResultInterceptorChainAdapter interceptorChain = ...;

    // ③ 注册超时/错误/完成处理器
    asyncWebRequest.addTimeoutHandler(() -> interceptorChain.triggerAfterTimeout(...));
    asyncWebRequest.addErrorHandler(ex -> { ... });
    asyncWebRequest.addCompletionHandler(() -> interceptorChain.triggerAfterCompletion(...));

    // ④ 挂起请求（延迟到 asyncReadyCallback 执行）
    interceptorChain.applyBeforeConcurrentHandling(asyncWebRequest, deferredResult);
    asyncWebRequest.startAsyncProcessing();
    asyncWebRequest.setAsyncReadyCallback(() -> {
        deferredResult.setResultHandler(result -> {
            result = interceptorChain.applyPostProcess(asyncWebRequest, deferredResult, result);
            asyncWebRequest.setConcurrentResultAndDispatch(result);  // 外部线程 setResult 触发恢复
        });
        asyncWebRequest.scheduleTimeoutIfNeeded();            // 延迟调度超时
    });
}
```

`DeferredResult.setResultHandler` 是关键——`DeferredResult.setResult(value)` 被外部线程调用时，触发 `setConcurrentResultAndDispatch` → `dispatch()` → `DispatcherHandler.asyncDispatch`。

### 1.4 `PerfAsyncWebRequest` 状态机

`PerfAsyncWebRequest`实现 `AsyncWebRequest` + `WriteRespEventListener` + `ServerHttpAsyncRequestControl`，使用四态原子状态机：

```
NEW ──startAsync()──→ ASYNC_STARTED ──dispatch()──→ DISPATCHED ──→ COMPLETED
                              ↑                              │
                              └── 超时/错误也回到 COMPLETED ──┘
```

`dispatch()`用 `compareAndSet(ASYNC_STARTED, DISPATCHED)` 确保业务线程只触发一次 dispatch：

```java
// PerfAsyncWebRequest.java
public void dispatch() {
    if (!state.compareAndSet(State.ASYNC_STARTED, State.DISPATCHED)) {
        return;  // 已 dispatch 或超时/错误已触发，忽略
    }
    DispatcherHandler dispatcherHandler = request.getWebContext().getDispatcherHandler();
    dispatcherHandler.asyncDispatch(request, response, concurrentResult);
}
```

`setConcurrentResultAndDispatch`使用 `synchronized` + `RESULT_NONE` 哨兵防重复设置：

```java
// PerfAsyncWebRequest.java
public void setConcurrentResultAndDispatch(Object result) {
    synchronized (this) {
        if (this.concurrentResult != RESULT_NONE) return;  // 仅第一次设置生效
        this.concurrentResult = result;
        this.errorHandlingInProgress = (result instanceof Throwable);
    }
    if (this.isAsyncComplete()) return;
    this.dispatch();
}
```

### 1.5 超时调度：`scheduleTimeoutIfNeeded`（延迟调度）

`scheduleTimeoutIfNeeded`不再在 `startAsync` 时立即调度，而是在 `setAsyncReadyCallback` 末尾延迟调度：

```java
// PerfAsyncWebRequest.java
public void scheduleTimeoutIfNeeded() {
    if (timeoutMillis <= 0 || timeoutHandler == null) return;
    if (state.get() != State.ASYNC_STARTED) return;
    response.setTimeout(() -> {
        if (state.get() != State.ASYNC_STARTED) return;
        if (timeoutHandler != null) timeoutHandler.run();
    }, timeoutMillis);
}
```

`state` 的双重检查确保超时只在 `ASYNC_STARTED` 状态触发，与 `setConcurrentResultAndDispatch` 的 `concurrentResult` 栅栏 + `dispatch` 的 CAS 共同保证超时结果与业务线程完成之间的线性化。

### 1.6 `asyncDispatch` 恢复流程

`DispatcherHandler.asyncDispatch`是异步恢复的入口：

```java
// DispatcherHandler.java
public void asyncDispatch(WebServerHttpRequest req, WebServerHttpResponse resp, Object concurrentResult) {
    // 重置 handled 标记——首次请求已标记为 handled，但实际 body 尚未写出
    if (resp instanceof BaseWebServerHttpResponse) {
        ((BaseWebServerHttpResponse) resp).resetHandled();
    }
    try {
        if (concurrentResult instanceof Throwable) {
            exceptionRegistry.handle(exception, req, resp);
        } else {
            PathMappingContext ctx = PathMappingContext.get(req);
            if (ctx != null) {
                returnValueResolverRegistry.resolveReturnValue(concurrentResult, ctx, req, resp);
            }
        }
    } finally {
        invokeWithRealResult(req, resp, result, exception);  // postHandle → afterCompletion
    }
}
```

`resetHandled()` 是关键——首次请求时 `DeferredResultReturnValueResolver.resolveReturnValue` 调用了 `resp.setHandled()`，但实际 body 尚未写出。异步 dispatch 回来后需要重置 handled 标记，让返回值解析器重新写入真正的 body。

---

## 二、`StreamEmitter`：流式基类

### 2.1 编码模式选择

`StreamEmitter`是流式输出的基类，支持两种编码模式：

| 模式 | `earlyEncode` | 编码时机 | 编码线程 | 适用场景 |
|------|--------------|---------|---------|---------|
| 延迟编码 | `false`（默认） | drain 时编码 | EventLoop 线程 | 默认，避免 App 线程竞争 PoolArena 锁 |
| 早编码 | `true` | `send()` 时编码 | App 线程 | 数据量大、编码计算密集，EventLoop 只做拷贝 |

### 2.2 `send()`：双缓冲交付

`send()`使用 DCL（Double-Checked Locking）实现无锁快路径 + 有锁慢路径：

```java
// StreamEmitter.java
public void send(T data) throws IOException {
    Object payload = data;
    if (earlyEncode) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        encode(data, baos);
        payload = baos.toByteArray();        // 早编码：App 线程冻结数据
    }
    StreamSender s = this.streamSender;
    if (s != null) {
        s.send(payload);                     // 快路径：已初始化，直接入队
        return;
    }
    synchronized (this) {
        if (this.streamSender != null) {
            this.streamSender.send(payload); // 有锁路径：初始化后重试
        } else {
            earlySendDataList.add(payload);  // 未初始化：缓冲到列表
        }
    }
}
```

`earlySendDataList` 是 `ArrayList`，在 `initialize()`中通过 `streamSender.sendAll()` 批量交付：

```java
// StreamEmitter.java
protected synchronized void initialize(StreamSender streamSender) throws IOException {
    this.streamSender = streamSender;
    try {
        streamSender.sendAll(earlySendDataList);  // 批量入队，仅调度一次 drain
    } finally {
        earlySendDataList.clear();
    }
    if (complete.get() && this.streamSender != null) {
        deferredResult.setResult(null);
        this.streamSender.complete(false, null);  // 已在 initialize 前 complete
    }
}
```

注释解释了 `sendAll` 的重要性：逐条 `send()` 在 EventLoop 上会每条触发一次同步 drain 并单独 flush，破坏 `drain()` 的 `batchBuf` + `maxFlushBytes` 批量编码设计。`sendAll` 入队后仅调度一次 drain。

### 2.3 `complete()` / `completeWithError()`：CAS 保护

`complete()`和 `completeWithError()`使用 `AtomicBoolean complete` 确保只触发一次：

```java
// StreamEmitter.java
public synchronized void complete() {
    if (complete.compareAndSet(false, true)) {
        StreamSender s = this.streamSender;
        if (s != null) {
            deferredResult.setResult(null);
            s.complete(false, null);
        }
    }
}
```

`complete` 标记在 `initialize()` 中也被检查：如果数据在 `initialize` 之前就送完了（`complete=true`），`initialize` 直接触发 `deferredResult.setResult(null)` 和 `s.complete()`。

---

## 三、`StreamSender` 体系：EventLoop 编码 vs App 线程编码

### 3.1 `StreamSender` 接口

`StreamSender`定义流式发送器的四个方法：

```java
// StreamSender.java
public interface StreamSender {
    void send(Object data) throws IOException;                       // 单条入队
    void sendAll(Collection<?> data) throws IOException;             // 批量入队
    void complete(boolean closeChannelOnComplete, Throwable failure); // 完成
    int queueSize();                                                  // 队列深度
}
```

`StreamSenderFactory`是创建 `StreamSender` 的 SPI：

```java
// DefaultStreamSenderFactory.java
public class DefaultStreamSenderFactory implements StreamSenderFactory {
    public StreamSender create(StreamEmitter streamEmitter, PerfAsyncWebRequest asyncWebRequest) {
        if (streamEmitter.isEarlyEncode()) {
            return new EarlyEncodeNettyStreamSender(streamEmitter, asyncWebRequest);
        }
        return new DefaultNettyStreamSender(streamEmitter, asyncWebRequest);
    }
}
```

两路实现：`DefaultNettyStreamSender`（延迟编码）和 `EarlyEncodeNettyStreamSender`（早编码）。

### 3.2 `StreamEmitterUtil`：初始化起点

`StreamEmitterUtil`封装了流式初始化的三个步骤：

```java
// StreamEmitterUtil.java
public static StreamSender initStreamSenderAndStartAsync(StreamEmitter emitter, ...) throws Exception {
    PerfAsyncWebRequest asyncWebRequest = AsyncSupportUtils.getAsyncWebRequest(req, resp);
    asyncSupportRegistry.startDeferredResultProcessing(asyncWebRequest, emitter.getDeferredResult());
    // ↑ 先挂起请求（绑定 DeferredResult 生命周期）
    sender = streamSenderFactory.create(emitter, asyncWebRequest);
    // ↑ 再创建 StreamSender
    return sender;
}
```

`StreamEmitterReturnValueResolver.resolveReturnValue`依次调用：
1. `preInitializeEmitter` → `extendResponseAndFlush`（设置响应头 + 预刷响应）
2. `initStreamSenderAndStartAsync`（挂起请求 + 创建 `StreamSender`）
3. `initializeWithStreamSender`（`emitter.initialize(sender)` → 交付缓冲数据）

---

## 四、核心：`AbstractNettyStreamSender` 无锁 Drain Loop

### 4.1 构造与队列

`AbstractNettyStreamSender`是所有流式发送器的抽象基类：

```java
// AbstractNettyStreamSender.java
protected static final int MAX_QUEUED_EVENTS = 65536;

protected final MpscArrayQueue<Object> queue;     // 多生产者单消费者无锁队列
protected final AtomicInteger wip = new AtomicInteger(0);  // 排空计数器
protected volatile boolean completed;              // 完成标记

public AbstractNettyStreamSender(StreamEmitter emitter, PerfAsyncWebRequest asyncWebRequest) {
    this.queue = new MpscArrayQueue<>(MAX_QUEUED_EVENTS);
    this.resp.setWritableCallback(this::scheduleDrain);  // 注册背压回调
}
```

| 组件 | 角色 | 说明 |
|------|------|------|
| `MpscArrayQueue<Object>` | 数据缓冲区 | 固定容量 65536，多生产者（业务线程）单消费者（EventLoop） |
| `AtomicInteger wip` | 排空计数器 | 跟踪"有多少线程需要 drain 执行" |
| `volatile boolean completed` | 完成标记 | `complete()` 设置，`afterDrain()` 检查 |
| `maxFlushBytes` | 批大小 | 默认 4KB（SSE）~ 32KB（TextStream），`emitter.getMaxFlushBytes()` |

### 4.2 `scheduleDrain()`：无锁调度

`scheduleDrain()`是整个 drain loop 的入口，使用 `wip.getAndIncrement()` 实现无锁调度：

```java
// AbstractNettyStreamSender.java
protected void scheduleDrain() {
    // 统一走 wip 计数：仅当无 drain 在执行（wip 从 0 递增）才真正调用/调度 drain。
    // 修复前 inEventLoop 分支无条件直接 drain()——drain 执行中 flushContent 的写完成
    // 回调（同步 Publisher → request → onNext → send）重入时又直接调 drain()，
    // 递归深度随同步元素数增长，Flux.range(1, N) 长流 StackOverflowError。
    // 现在重入请求只递增 wip，由当前 drain 末尾 missed 循环消化，继续排空新数据。
    if (wip.getAndIncrement() == 0) {
        if (eventLoop.inEventLoop()) {
            drain();                        // 已在 EventLoop：同步执行
        } else {
            eventLoop.execute(this::drain); // 在业务线程：投递到 EventLoop
        }
    }
}
```

`wip.getAndIncrement()` 的语义：
- **返回 0**：当前无 drain 在执行，本线程负责执行 drain。
- **返回 >0**：已有 drain 在执行，只需递增 wip 计数，当前 drain 完成后通过 `missed` 循环会看到新入队的数据。

**关键修复**：修复前 `inEventLoop` 分支无条件直接 `drain()`。当 `flushContent` 的写完成回调（同步 `Publisher` 的 `request` → `onNext` → `send`）重入时，再次直接调 `drain()`，递归深度随同步元素数增长——`Flux.range(1, N)` 长流 `StackOverflowError`。现在重入请求只递增 wip，由当前 drain 末尾的 `missed` 循环消化。

### 4.3 `enqueueWithBackpressure()`：自旋等待

`enqueueWithBackpressure()`在队列满时自旋等待：

```java
// AbstractNettyStreamSender.java
private void enqueueWithBackpressure(Object data) throws IOException {
    for (int spins = 0; ; spins++) {
        if (queue.offer(data)) return;          // 成功入队
        preSendCheck();                          // 检查 channel 是否关闭
        if (spins < 10) {
            Thread.yield();                     // 前 10 次 yield
        } else {
            LockSupport.parkNanos(1000);        // 之后 park 1μs
        }
    }
}
```

队列满时（生产者快于消费者），`LockSupport.parkNanos(1000)` 让出 CPU 等待 drain 消费腾出空间。自旋中重复 `preSendCheck()` 以在 channel 关闭或流完成时立即失败，避免无限自旋。

### 4.4 `drain()` + `afterDrain()`：排空与重检查

`drain()` 是抽象方法，由子类实现。`afterDrain()`是 drain 末尾的 re-drain 检查：

```java
// AbstractNettyStreamSender.java
protected void afterDrain() {
    if (!queue.isEmpty()) {
        // 队列仍有数据：channel 可写则继续 drain。
        // channel 不可写或 reschedule 失败（wip 已被其他线程设置）时什么都不做：
        // 依赖 BackpressureHandler 的 writable callback 恢复排空。
        if (channel.isWritable() && wip.compareAndSet(0, 1)) {
            eventLoop.execute(this::drain);
        }
        return;
    }
    if (completed && !lastHttpContentWritten) {
        lastHttpContentWritten = true;
        onAllDataWritten();                     // 写入 LastHttpContent.EMPTY_LAST_CONTENT
    }
}
```

**wip 残留修复**：`afterDrain` 的 `queue.isEmpty()` 检查 + `wip.compareAndSet(0, 1)` 是最后的防线。当生产者远快于 drain 时，`wip.addAndGet(-missed)` 可能将 wip 归零而队列仍有残留数据。`afterDrain` 的 re-drain 检查确保残留数据不被丢弃。

### 4.5 `onAllDataWritten()`：终止帧

`onAllDataWritten()`写入 `LastHttpContent.EMPTY_LAST_CONTENT` 终止帧：

```java
// AbstractNettyStreamSender.java
protected void onAllDataWritten() {
    this.resp.setWritableCallback(null);                    // 清除背压回调
    ChannelFuture f = channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    resp.addRespEventListener(f, true);                     // 触发 complete 回调
    f.addListener(completeListener);                        // 成功/失败生命周期
}
```

`addRespEventListener(f, true)` 在写完成时触发 `WriteRespEventListener.completeSuccessCallback()` → `PerfAsyncWebRequest.completeSuccessCallback()` → `state.set(COMPLETED)` + `completionHandler.run()`。

### 4.6 `flushContent()`：写出 + 监听器

`flushContent()`写出 `HttpContent` 帧：

```java
// AbstractNettyStreamSender.java
protected void flushContent(ByteBuf buf) {
    ChannelFuture f = channel.writeAndFlush(new DefaultHttpContent(buf));
    resp.addRespEventListener(f, false);  // 触发 writeStreamSuccessCallback
}
```

`addRespEventListener(f, false)` 在写成功时触发 `writeStreamSuccessCallback()` → `PerfAsyncWebRequest.writeStreamSuccessCallback()` → `writeCallbackHandler.accept(null)`。这个回调对响应式流至关重要——`PublisherToStreamEmitterAdapter` 的 `tryRequest` 在每次写完成后检查队列深度，触发背压补充请求。

### 4.7 `channel inactive` 分支：wip 复位

`DefaultNettyStreamSender.drain()` 和 `EarlyEncodeNettyStreamSender.drain()` 在 `channel.isActive()` 失败时，都执行 `wip.set(0)` 并检查 `completed`：

```java
// DefaultNettyStreamSender.java
if (!channel.isActive()) {
    queue.clear();
    wip.set(0);  // 修复前不递减 wip（残留非 0），后续 complete() 的
                 // scheduleDrain getAndIncrement 返回非 0 被吞，onAllDataWritten 永不执行
    if (completed && !lastHttpContentWritten) {
        lastHttpContentWritten = true;
        onAllDataWritten();
    }
    return;
}
```

注释记录了修复历史：修复前不递减 wip（残留非 0），后续 `complete()` 的 `scheduleDrain` 的 `getAndIncrement` 返回非 0 被吞，`onAllDataWritten` 永不执行，异步请求挂起。`wip.set(0)` 必须在 `completed` 检查**之前**复位：跨线程 `complete()` 的 `getAndIncrement` 与 `set(0)` 在同一 `AtomicInteger` 上构成全序，无论先后，都能保证最终有一次 drain 观察到 `completed=true` 完成流。

---

## 五、`DefaultNettyStreamSender`：EventLoop 延迟编码

`DefaultNettyStreamSender`是默认的流式发送器，`drain()` 在 EventLoop 线程编码：

```java
// DefaultNettyStreamSender.java
protected void drain() {
    // ① channel inactive 处理（见 §4.7）
    int missed = 1;
    for (;;) {
        ByteBuf batchBuf = null;
        ByteBufOutputStream batchOut = null;
        while (channel.isWritable()) {
            Object data = queue.poll();
            if (data == null) break;
            if (batchBuf == null) {
                batchBuf = bufAllocator.buffer(maxFlushBytes);
                batchOut = new ByteBufOutputStream(batchBuf);
            }
            int before = batchBuf.writerIndex();
            try {
                emitter.encode(data, batchOut);  // EventLoop 线程编码
            } catch (Exception e) {
                batchBuf.writerIndex(before);     // 编码失败：回退写指针
                emitter.onEncodeError(data, e);
            }
            if (batchBuf.writerIndex() >= maxFlushBytes) {
                flushContent(batchBuf);           // 达到批大小：flush
                batchBuf = null;
                batchOut = null;
            }
        }
        if (batchBuf != null) {
            if (batchBuf.readableBytes() > 0) {
                flushContent(batchBuf);
            } else {
                batchBuf.release();
            }
        }
        missed = wip.addAndGet(-missed);          // ③ 递减 missed
        if (missed == 0) break;                   // ④ 无新数据：退出
    }
    afterDrain();                                 // ⑤ re-drain 检查
}
```

**missed 循环**：`missed = wip.addAndGet(-missed)` 递减当前排空周期捕获的 `wip` 增量。如果 `missed == 0`，说明 drain 期间没有新的 `scheduleDrain()` 调用，退出。如果 `missed > 0`，说明有数据在 drain 执行期间入队，继续循环。

**编码错误处理**：`emitter.encode()` 抛出异常时，`batchBuf.writerIndex(before)` 回退写指针，丢弃损坏的数据，调用 `emitter.onEncodeError(data, e)` 让子类决定如何处理。不中断 drain 循环。

---

## 六、`EarlyEncodeNettyStreamSender`：App 线程早编码

`EarlyEncodeNettyStreamSender`接收已编码的 `byte[]`，drain 中直接拷贝：

```java
// EarlyEncodeNettyStreamSender.java
protected void drain() {
    // ① channel inactive 处理（同 DefaultNettyStreamSender）
    int missed = 1;
    for (;;) {
        ByteBuf batchBuf = null;
        while (channel.isWritable()) {
            Object data = queue.poll();
            if (data == null) break;
            byte[] bytes = (byte[]) data;
            if (bytes.length == 0) continue;
            if (bytes.length > maxFlushBytes) {
                // 单条超长消息：先刷已有批数据，再独立写入
                if (batchBuf != null) { flushContent(batchBuf); batchBuf = null; }
                flushContent(Unpooled.wrappedBuffer(bytes));  // 零拷贝包装
                continue;
            }
            if (batchBuf == null) batchBuf = bufAllocator.buffer(maxFlushBytes);
            if (batchBuf.writableBytes() < bytes.length) {
                flushContent(batchBuf);
                batchBuf = bufAllocator.buffer(maxFlushBytes);
            }
            batchBuf.writeBytes(bytes);  // 拷贝 byte[] 到 batchBuf
        }
        // ② 剩余批数据 flush
        // ③ missed 循环
        // ④ afterDrain()
    }
}
```

与 `DefaultNettyStreamSender` 的核心区别：

| 维度 | `DefaultNettyStreamSender` | `EarlyEncodeNettyStreamSender` |
|------|---------------------------|-------------------------------|
| 队列数据类型 | 原始数据（`Object`） | `byte[]`（已编码） |
| 编码线程 | EventLoop 线程 | App 线程（`send()` 时） |
| 队列中的对象 | 编码前原始数据 | 编码后 byte[] |
| 超长消息处理 | 持续编码到 batchBuf 直到 flush | `Unpooled.wrappedBuffer` 零拷贝包装 |
| ByteBuf 竞争 | 无（单线程） | 无（App 线程已编码，EventLoop 只拷贝） |

---

## 七、SSE 协议实现

### 7.1 `SseEmitter`：SSE 编码

`SseEmitter`继承 `StreamEmitter<Object>`，实现 SSE 协议编码：

```java
// SseEmitter.java
private static final byte[] DATA_PREFIX = "data:".getBytes(StandardCharsets.UTF_8);
private static final byte[] NEWLINE_DATA = "\ndata:".getBytes(StandardCharsets.UTF_8);
private static final byte[] TERMINATOR = "\n\n".getBytes(StandardCharsets.UTF_8);
private static final byte[] FIELD_ID = "id:".getBytes(StandardCharsets.UTF_8);
private static final byte[] FIELD_EVENT = "event:".getBytes(StandardCharsets.UTF_8);
private static final byte[] FIELD_RETRY = "retry:".getBytes(StandardCharsets.UTF_8);
```

`encode()`两种分支：

```java
// SseEmitter.java
public void encode(Object data, OutputStream out) throws IOException {
    if (data instanceof ServerSentEvent sse) {
        encodeServerSentEvent(sse, out);       // Spring 的 ServerSentEvent 结构
    } else {
        encodeData(data, out);                 // 原始数据
    }
}
```

`encodeData`处理 **`\n` 续行**——SSE 协议的 `data` 字段中裸 `\n` 必须用 `\ndata:` 续行：

```java
// SseEmitter.java
protected void encodeData(Object data, OutputStream out) throws IOException {
    out.write(DATA_PREFIX);  // "data:"
    if (data instanceof CharSequence) {
        byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
        writeBytesWithNewline(out, bytes, NEWLINE_DATA);  // 扫描 \n，续行
    } else {
        encodeEventDataAsBytes(data, out);  // 非 CharSequence：不做 \n 扫描
    }
    out.write(TERMINATOR);  // "\n\n"
}
```

`writeBytesWithNewline`扫描 `\n` 并用 `\ndata:` 续行：

```java
// SseEmitter.java
private static void writeBytesWithNewline(OutputStream out, byte[] bytes, byte[] continuation) {
    int start = 0;
    for (int i = 0; i < bytes.length; i++) {
        if (bytes[i] == '\n') {
            out.write(bytes, start, i - start);
            out.write(continuation);   // \ndata:
            start = i + 1;
        }
    }
    out.write(bytes, start, bytes.length - start);
}
```

`encodeServerSentEvent`编码 SSE 标准字段：`id`、`event`、`retry`、`comment`、`data`。

`extendResponse`设置响应头：

```java
// SseEmitter.java
protected void extendResponse(ServerHttpResponse response) {
    if (headers.getContentType() == null) {
        headers.setContentType(MediaType.TEXT_EVENT_STREAM);
    }
    headers.setCacheControl("no-cache");
}
```

### 7.2 `SseJsonEmitter`：JSON 序列化的 SSE

`SseJsonEmitter`继承 `SseEmitter`，重写 `encodeEventDataAsBytes` 使用 `JsonConverter` 序列化数据：

```java
// SseJsonEmitter.java
protected void encodeEventDataAsBytes(Object data, OutputStream out) throws IOException {
    jsonConverter.toJson(out, data);
}
```

这样 SSE 的 `data:` 字段包含 JSON 字符串，同时保留 SSE 协议的其他字段（`id`、`event`、`retry`）。

---

## 八、其他流式格式

### 8.1 `StreamJsonEmitter`：`application/stream+json`

`StreamJsonEmitter`用于 `application/stream+json` 格式，每个元素写为一行 JSON：

```java
// StreamJsonEmitter.java
public void encode(Object data, OutputStream out) throws IOException {
    if (data == null) { out.write('\n'); return; }
    jsonConverter.toJson(out, data);
    out.write('\n');
}
```

### 8.2 `TextStreamEmitter`：`text/plain`

`TextStreamEmitter`用于纯文本流，每个元素写为一行文本：

```java
// TextStreamEmitter.java
public void encode(Object data, OutputStream out) throws IOException {
    if (data == null) { out.write('\n'); return; }
    IoUtils.writeCharSequence(out, (CharSequence) data, StandardCharsets.UTF_8);
    out.write('\n');
}
```

`getMaxFlushBytes()` 返回 32KB，比 SSE 的 4KB 大——文本流批量更大，单条消息更小。

---

## 九、响应式支持

### 9.1 `ReactiveReturnValueResolver`：`Publisher` 适配

`ReactiveReturnValueResolver`是 `Publisher` 返回值的入口，在 `resolveReturnValue`中决定适配路径：

```java
// ReactiveReturnValueResolver.java
public void resolveReturnValue(Object returnValue, ...) throws Exception {
    ReactiveAdapter adapter = this.adapterRegistry.getAdapter(returnValue.getClass());
    ReactiveConfig reactiveConfig = getReactiveConfig(req);
    StreamEmitter emitter = createStreamEmitter(reactiveConfig, adapter, elementClass, req, resp);
    if (emitter != null) {
        // 多值 Publisher → 流式输出
        StreamEmitterUtil.extendResponseAndFlush(emitter, resp, true);
        StreamSender sender = StreamEmitterUtil.initStreamSenderAndStartAsync(...);
        PublisherToStreamEmitterAdapter streamEmitterAdapter =
            new PublisherToStreamEmitterAdapter(emitter, sender, reactiveConfig, asyncWebRequest);
        streamEmitterAdapter.subscribe(adapter, returnValue);
        StreamEmitterUtil.initializeWithStreamSender(emitter, sender);
    } else {
        // 单值 Publisher → DeferredResult
        DeferredResult deferredResult = new DeferredResult(reactiveConfig.getTimeout());
        PublisherToDeferredResultAdapter deferredResultAdapter =
            new PublisherToDeferredResultAdapter(deferredResult, adapter);
        deferredResultAdapter.subscribe(adapter, returnValue);
        asyncSupportRegistry.startDeferredResultProcessing(req, resp, deferredResult);
    }
}
```

`createStreamEmitter`按 MediaType 自动选择 Emitter 类型：

| 条件 | Emitter 类型 |
|------|-------------|
| `@ReactiveSupport(streamEmitterType=...)` 自定义 | 指定的 `StreamEmitter` 子类 |
| ServerSentEvent 或 `text/event-stream` | `SseJsonEmitter` |
| `CharSequence` 元素类型 | `TextStreamEmitter` |
| `application/stream+json` | `StreamJsonEmitter` |
| 单值或无法匹配 | `null` → 回退到 `DeferredResult` |

### 9.2 `ReactiveConfig`：背压水位 fail-fast

`ReactiveConfig`封装背压参数，在构造期 fail-fast 校验：

```java
// ReactiveConfig.java
public ReactiveConfig(Class<? extends StreamEmitter> streamEmitterType, ..., int highWaterMark, int lowWaterMark, long timeout) {
    if (lowWaterMark <= 0) {
        throw new IllegalArgumentException("lowWaterMark must be > 0, got " + lowWaterMark);
    }
    if (highWaterMark <= lowWaterMark) {
        throw new IllegalArgumentException("highWaterMark must be > lowWaterMark, got highWaterMark="
                + highWaterMark + ", lowWaterMark=" + lowWaterMark);
    }
    // ...
}
```

注释说明了校验原因：`lowWaterMark≤0` 时 `tryRequest` 的补充请求永远不触发，遵守背压的冷 Publisher 在 `highWaterMark` 条后流停滞；`highWaterMark≤lowWaterMark` 时背压区间为空。默认值 `(150, 50)` 合法。

### 9.3 `@ReactiveSupport` 注解

`@ReactiveSupport`可在类或方法上标注，覆盖背压参数：

```java
// ReactiveSupport.java
@Target({TYPE, METHOD})
@Retention(RUNTIME)
public @interface ReactiveSupport {
    int highWaterMark() default 150;
    int lowWaterMark() default 50;
    Class<? extends StreamEmitter> streamEmitterType();  // 自定义 Emitter 类型
    long timeout() default -1;
}
```

`getReactiveConfig`使用 `MappingCacheKey` 缓存每方法的 `ReactiveConfig`：

```java
// ReactiveReturnValueResolver.java
protected ReactiveConfig getReactiveConfig(WebServerHttpRequest request) {
    MappingHandlerMethod handlerMethod = PathMappingContext.get(request);
    if (handlerMethod != null) {
        ReactiveConfig reactiveConfig = handlerMethod.get(MAPPING_CACHE_KEY);
        if (reactiveConfig == null) {
            ReactiveSupport reactiveSupport = handlerMethod.getMethodAndClassAnnotation(ReactiveSupport.class);
            if (reactiveSupport != null) {
                reactiveConfig = new ReactiveConfig(reactiveSupport.streamEmitterType(), ...);
            } else {
                reactiveConfig = ReactiveConfig.DEFAULT;
            }
        }
        return reactiveConfig;
    }
    return ReactiveConfig.DEFAULT;
}
```

注释记录了修复历史：修复前误用仍为 null 的 `reactiveConfig` 取 `streamEmitterType`，首次请求必 NPE。

### 9.4 `PublisherToStreamEmitterAdapter`：订阅者 + 背压

`PublisherToStreamEmitterAdapter`实现 `Subscriber<Object>`，将响应式 `Publisher` 适配为流式输出：

```java
// PublisherToStreamEmitterAdapter.java
public void onSubscribe(Subscription s) {
    this.subscription = s;
    emitter.onTimeout(() -> tryCancel(new AsyncRequestTimeoutException()));
    Consumer<Throwable> writeCallback = t -> {
        if (t == null) {
            tryRequest();          // 写完成 → 补充请求
        } else {
            tryCancel((Throwable) t);
        }
    };
    emitter.onWriteCallback(writeCallback);
    // 修复前在 subscribe() 返回后事后读取 emitter.getWriteCallbackHandler()，
    // 对 onSubscribe 异步投递的 Publisher 拿到 null → 流停滞
    if (asyncWebRequest != null) {
        asyncWebRequest.addWriteCallbackHandler(writeCallback);
    }
    subscription.request(config.getHighWaterMark());  // 初始化请求 N 条
}
```

`tryRequest`是背压补充请求的核心：

```java
// PublisherToStreamEmitterAdapter.java
protected void tryRequest() {
    if (terminated) return;
    if (sender.queueSize() < config.getLowWaterMark()) {
        subscription.request(config.getHighWaterMark() - sender.queueSize());
    }
}
```

当队列深度低于 `lowWaterMark`（默认 50）时，请求 `highWaterMark - queueSize` 条新元素，将队列填充到 `highWaterMark`（默认 150）。

`onNext`保护终止后的迟到元素：

```java
// PublisherToStreamEmitterAdapter.java
public void onNext(Object o) {
    if (terminated) return;  // RS 规范：终止信号后不得再投递 onNext
    try {
        sender.send(o);
    } catch (IOException e) {
        tryCancel(e);
    }
}
```

### 9.5 `PublisherToDeferredResultAdapter`：单值回退

`PublisherToDeferredResultAdapter`用于单值 `Publisher`，收集所有元素后设置为 `DeferredResult` 的结果：

```java
// PublisherToDeferredResultAdapter.java
public void onComplete() {
    if (this.valueList.size() > 1 || this.multiValueSource) {
        this.result.setResult(this.valueList);        // 多元素 → List
    } else if (this.valueList.size() == 1) {
        this.result.setResult(this.valueList.get(0)); // 单元素 → 直接值
    } else {
        this.result.setResult(null);                  // 空 → null
    }
}
```

---

## 十、背压机制

### 10.1 `BackpressureHandler`：Netty 背压监听器

`BackpressureHandler`是 `@Sharable` 单例，监听 `channelWritabilityChanged` 事件：

```java
// BackpressureHandler.java
@ChannelHandler.Sharable
public final class BackpressureHandler extends ChannelInboundHandlerAdapter {
    public static final BackpressureHandler INSTANCE = new BackpressureHandler();

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        Channel ch = ctx.channel();
        boolean writable = ch.isWritable();
        ConnectionContext conn = ch.attr(NettyServerHttpResponse.CONN_CTX).get();
        if (conn == null) { ctx.fireChannelWritabilityChanged(); return; }

        boolean last = conn.lastWritable();
        if (!last && writable) {  // 只处理 false → true
            Runnable cb = conn.getOnWritable();
            if (cb != null) {
                cb.run();         // 触发 scheduleDrain()
            }
        }
        conn.updateWritable(writable);
        ctx.fireChannelWritabilityChanged();
    }
}
```

只处理 `false → true` 的转换（不可写→可写），触发 `ConnectionContext` 中的 `onWritable` 回调。这个回调在 `AbstractNettyStreamSender` 构造器中设置为 `this::scheduleDrain`。

### 10.2 背压全链路

```
Netty WriteBufferWaterMark 满 (high → 不可写)
  ↓
channel.isWritable() == false
  ↓
drain() 的 while(channel.isWritable()) 退出
  ↓
afterDrain() 的 wip.compareAndSet(0, 1) 可能失败 → 不调度 drain
  ↓
Netty 写出数据到 Wire (水位降回 low)
  ↓
ChannelWritabilityChanged (false → true)
  ↓
BackpressureHandler 触发 onWritable → scheduleDrain()
  ↓
drain() 恢复：wip 从 0 递增，继续排空
  ↓
[响应式] flushContent 写完成回调 → tryRequest → subscription.request(N)
```

### 10.3 `setWritableCallback`

`NettyServerHttpResponse.setWritableCallback`在 EventLoop 上设置 `ConnectionContext.onWritable`：

```java
// NettyServerHttpResponse.java
public void setWritableCallback(Runnable callback) {
    runOnEventLoop(() -> {
        ConnectionContext conn = ctx.attr(NettyServerHttpResponse.CONN_CTX).get();
        if (conn == null) {
            conn = new ConnectionContext();
            ctx.attr(NettyServerHttpResponse.CONN_CTX).set(conn);
        }
        conn.setOnWritable(callback);
    });
}
```

---

## 十一、线程模型

| 角色 | 线程 | 说明 |
|------|------|------|
| 数据生产 | 业务线程 (`@RunInPool`) | 控制器写入 `StreamEmitter.send()`，或 `Publisher` 的 `onNext` |
| 数据入队 | 业务线程 | `MpscArrayQueue.offer()` 无锁入队 |
| 数据编码 | EventLoop（默认）或 App 线程（earlyEncode） | `DefaultNettyStreamSender` 在 EventLoop 编码；`EarlyEncodeNettyStreamSender` 在 App 线程编码 |
| 数据写出 | EventLoop | `flushContent()` → `channel.writeAndFlush()` |
| 背压恢复 | EventLoop | `BackpressureHandler.channelWritabilityChanged` → `scheduleDrain()` |
| 异步 dispatch | EventLoop | `DispatcherHandler.asyncDispatch` |
| `Callable` 执行 | 业务线程池 | `AsyncTaskExecutor.submit()` |

**SSE 写入统一在 EventLoop**，线程数 = CPU 核，不随连接增长。每个连接不独占线程，`MpscArrayQueue` 允许多线程生产者，`AtomicInteger wip` 确保 EventLoop 单消费者。对比 Spring MVC（Tomcat NIO 模式）的 `ResponseBodyEmitter` 使用 `BlockingQueue`，写入阻塞在业务线程上。

---

## 十二、对比 Spring MVC 异步流式

| 维度 | 本框架 | Spring MVC |
|------|--------|-----------|
| 异步挂起 | `PerfAsyncWebRequest` 四态原子状态机 | `WebAsyncManager` 基于 `AsyncWebRequest` |
| Callable 执行 | `AsyncTaskExecutor`（默认业务线程池） | `AsyncTaskExecutor`（默认 `SimpleAsyncTaskExecutor`） |
| DeferredResult 恢复 | `setConcurrentResultAndDispatch` → `asyncDispatch` | `WebAsyncManager.setConcurrentResultAndDispatch` |
| 流式发送器 | `MpscArrayQueue` + `AtomicInteger wip` drain loop | `BlockingQueue` + 单线程阻塞写 |
| SSE 编码 | `SseEmitter.encode()` 在 drain 中编码 | `SseEmitter.send()` 在业务线程编码 |
| 响应式背压 | `highWaterMark/lowWaterMark` + `tryRequest` | `ReactiveAdapterRegistry` + `request(N)` |
| 背压分发 | `BackpressureHandler` Netty ChannelHandler | 无等效机制（依赖 Servlet 3.1 非阻塞 IO） |
| 写完成回调 | `WriteRespEventListener` → `writeStreamSuccessCallback` | 无等效机制 |
| 线程模型 | EventLoop 单线程写所有连接 | 每连接一线程（Tomcat NIO 模式） |
| 队列类型 | `MpscArrayQueue` 有界队列（65536） | `BlockingQueue` JDK 阻塞队列 |
| 编码错误处理 | `writerIndex` 回退 + `onEncodeError` 回调 | 抛出异常中断流 |
| 超长消息 | `Unpooled.wrappedBuffer` 零拷贝 | 写 ByteBuf 后 flush |

**核心差异**：Spring MVC 的异步流式依赖 `BlockingQueue` + 业务线程阻塞写，每个连接消耗一个线程。本框架的 `MpscArrayQueue` + `AtomicInteger wip` drain loop 将所有写入统一在 EventLoop 线程，线程数 = CPU 核，不随连接增长。`wip` 抵御重入（修复前 `StackOverflowError`），`afterDrain` 的 re-drain 检查防止残留数据丢失（修复前 `wip` 残留导致数据丢失），`channel inactive` 分支的 `wip.set(0)` 确保跨线程最终完成。

---

## 十三、小结：异步流式的克制在哪里

回到引子的问题：四类异步返回值如何挂起与恢复？SSE 如何用无锁 Drain Loop 实现高性能流式输出？

1. **`AsyncSupportRegistry` 统一挂起** → `DeferredResult`/`Callable`/`StreamEmitter`/`Publisher` 四类异步返回值通过 `startDeferredResultProcessing` 或 `startCallableProcessing` 挂起，`PerfAsyncWebRequest` 四态原子状态机确保线程安全。
2. **`StreamEmitter` 双缓冲交付** → `earlySendDataList` 缓冲初始化前的数据，`initialize()` 时 `sendAll` 批量交付，避免逐条 drain 破坏批量 flush。
3. **`MpscArrayQueue` + `AtomicInteger wip` 无锁 drain loop** → `scheduleDrain()` 一次调度，`drain()` 批量编码 + `missed` 循环消化重入，`afterDrain()` re-drain 检查残留。三处修复（wip 重入 `StackOverflowError`、wip 残留数据丢失、channel inactive 流挂起）记录了 drain loop 的演进。
4. **`DefaultNettyStreamSender` / `EarlyEncodeNettyStreamSender` 两路实现** → 延迟编码在 EventLoop 编码，避免 App 线程竞争 PoolArena 锁；早编码在 App 线程编码，EventLoop 只做拷贝。
5. **SSE 协议完整编码** → `SseEmitter` 的 `\n` 续行处理、`ServerSentEvent` 字段编码、`SseJsonEmitter` JSON 序列化。
6. **响应式背压** → `highWaterMark/lowWaterMark` 水位控制，`tryRequest` 按队列深度补充请求，`@ReactiveSupport` 注解自定义参数。
7. **`BackpressureHandler` 写水位联动** → `@Sharable` 单例监听 `channelWritabilityChanged`，`false→true` 触发 `scheduleDrain()` 恢复排空。

这一层的克制体现在：**SSE 和流式不依赖锁、不依赖阻塞队列。** 整个流式写入路径是纯 `MpscArrayQueue.offer()` + `AtomicInteger.getAndIncrement()` + `channel.writeAndFlush()`，没有 `synchronized`、`ReentrantLock`、`BlockingQueue.put()`。`wip` 计数器抵御重入、`afterDrain` 检查残留、`channel inactive` 复位确保完成——三处修复都是对 drain loop 边界的完美诠释，而非对"加锁"的让步。

---

> **下一篇**：[12 · spring-web-servlet 与 spring-web-mvc-support 桥接](12-support-bridge.md)——Servlet API 桥接与 WebMvcConfigurer 翻译中枢。