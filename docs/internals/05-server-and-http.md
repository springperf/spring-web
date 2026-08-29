# 05 · Netty 服务器与 HTTP 请求/响应适配

> [← 返回索引](00-README.md) | 上一篇：[04 · 请求处理主链路与线程模型](04-request-pipeline.md) | 下一篇：[06 · 路由引擎与多级 RouterOptimizer 链](06-routing-engine.md)

---

## 引子：I/O 层的"适配而非重造"

[04 篇](04-request-pipeline.md) 自顶向下讲清了请求处理主链路：`DispatcherHandler` → Filter 链 → 业务方法 → 返回值写出。本篇往下钻一层，回答一个问题：**字节流是怎么变成 `FullHttpRequest` 的，框架又怎么把 `ByteBuf`/`DefaultFileRegion` 写回 socket？** 这一层是 [01 篇](01-design-philosophy.md) 原则 4（避免阻塞 + 显式引用计数）的落地现场。

I/O 层的设计哲学是"适配而非重造"：

- **HTTP 解析不重造**。`HttpServerCodec`、`HttpObjectAggregator`、`Http2FrameCodecBuilder` 全部复用 Netty 原生组件——这一层是 Netty 的主场，自研解析器只会更慢更易错。
- **框架语义的零拷贝包装自研**。`NettyServerHttpRequest`/`NettyServerHttpResponse` 不照搬 Spring 的 `Netty4HeadersAdapter`（仅 Spring 6.1+ 且语义不契合），而是用 `NettyHttpHeadersAdapter` 直接委托 Netty `HttpHeaders`，免去 O(n) 拷入 `LinkedMultiValueMap` 的开销，并保留 Netty 大小写不敏感解析。
- **引用计数显式化**。不依赖 GC `Cleaner` 延迟回收堆外内存，用 `acquire/release` 对称把 ByteBuf 生命周期交给业务方掌控——这是[原则 4](01-design-philosophy.md#原则-4--避免阻塞非阻塞-io--显式引用计数) 换来零拷贝透传能力的代价。

读完本篇，你应能回答大纲提出的三个核心问题：服务器如何启动与配置？HTTP 请求对象如何零拷贝包装 Netty 对象？响应如何把 `ByteBuf`/`DefaultFileRegion` 写回 Channel？

---

## 一、NettyHttpServer：启动序列与生命周期

`NettyHttpServer` 是整个 I/O 层的入口，单类承担"启动 + 配置 + 优雅关闭 + 指标暴露"四职责。大纲提及的 `AbstractNettyWebServer` 在源码中不存在——这是规划阶段的命名，实际只有一个具体类：

```java
// NettyHttpServer.java
@Slf4j
public class NettyHttpServer implements SmartLifecycle, LifecycleWebComponent {
```

它同时实现 Spring 的 `SmartLifecycle`（纳入 Spring 容器生命周期，按 phase 排序启动）和框架自己的 `LifecycleWebComponent`（三阶段组件生命周期，[03 篇](03-component-lifecycle.md) 详述）。两个接口各管一件事：`SmartLifecycle` 管"什么时候启动"，`LifecycleWebComponent` 管"自身作为 WebComponent 何时 init/destroy"。

### 1.1 启动序列：`start()` 逐步分解

```java
// NettyHttpServer.java
@Override
public void start() {
    // ① 在 Netty 启动前触发 WebContext 生命周期，确保所有 WebComponent 已完成初始化
    webContext.startLifecycle();
    // ② 读 HTTP/2 开关
    this.http2Enabled = webContext.getProps().getBoolean(PropertiesConstant.HTTP2_ENABLED, false);
    // ③ 启动期（单线程、Netty 未接受连接前）获取 DispatcherHandler
    DispatcherHandler dispatcher = webContext.getWebComponent(DispatcherHandler.class);
    this.httpHandler = new NettyHttpHandler(webContext, webContext.getContextPath(), dispatcher);
    // ④ boss/worker EventLoopGroup（transport 由 server.netty.transport 决定）
    int port = webContext.getProps().getInt(PropertiesConstant.SERVER_PORT);
    int workerThreads = webContext.getProps().getInt(PropertiesConstant.SERVER_NETTY_WORKERS);
    String transportMode = webContext.getProps().get(
            PropertiesConstant.SERVER_NETTY_TRANSPORT, PropertiesConstant.SERVER_NETTY_TRANSPORT_DEFAULT);
    bossGroup = NettyTransport.newBossGroup(bossThreads, transportMode);
    workerGroup = NettyTransport.newWorkerGroup(workerThreads, transportMode);
    // ⑤ 收集 PipelineCustomizer 注入的额外 handler
    List<ChannelHandler> beforeAggHandlers = ...;
    List<ChannelHandler> afterAggHandlers = ...;
    // ⑥ ServerBootstrap + ChannelOption + ChannelInitializer
    ServerBootstrap bootstrap = new ServerBootstrap();
    bootstrap.group(bossGroup, workerGroup)
            .channel(NettyTransport.serverChannelClass(transportMode))
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                    new WriteBufferWaterMark(lowWatermark, highWatermark))
            .childHandler(new ChannelInitializer<SocketChannel>() { ... });
    // ⑦ bind + actualPort
    serverChannel = bootstrap.bind(port).sync().channel();
    this.actualPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
    // ⑧ 写 local.server.port 系统属性
}
```

每一步都不是随手写的，背后都有性能或正确性考量：

**① `webContext.startLifecycle()` 必须在 Netty bind 前。** 这是 [01 篇](01-design-philosophy.md#一总纲启动时确定性) 强调过的"确定性要求输入完整"——三阶段（Phase1 元数据收集 / Phase2 跨组件连接 / Phase3 优化缓存）跑完之前，`DispatcherHandler` 还没装配好路由表和参数索引，此时若 Netty 已开始接连接，第一个请求就会拿到半成品。把生命周期锚定在 `start()` 第一行而非 `afterPropertiesSet()`，是因为 Web 组件初始化应在基础容器初始化完成之后——`afterPropertiesSet()` 触发时点取决于 bean 依赖图位置、无法保证容器就绪，而 `SmartLifecycle.start()` 在 context refresh 完全结束后才调用。

**② HTTP/2 开关一次性读取。** `HTTP2_ENABLED` 默认 `false`（[`PropertiesConstant.java`](../../spring-web/src/main/java/io/springperf/web/context/PropertiesConstant.java) 无 DEFAULT 常量，`getBoolean` 第二参 `false`）。读到 `this.http2Enabled` 后，运行时只读这个字段，纯读线程安全——这是注释里写的"运行时只需做纯读操作"的来源。

**③ `DispatcherHandler` 在启动期单线程获取。** 注释写得清楚：在"单线程、Netty 未接受连接前"取一次，存进 `NettyHttpHandler` 字段。运行时多 EventLoop 线程并发调 `httpHandler.httpHandle(req, resp)`，只读这个引用，无需锁。如果改到每请求从 `webContext` 取，就要付 `ConcurrentHashMap.get` 的开销——又是一个"启动时确定、运行时查表"的体现。

**④ boss 线程、worker 可配、transport 自动选。** `bossGroup` 默认 1 线程：accept 线程只需 1 个，单线程 accept 对绝大多数吞吐量绰绰有余（Linux `epoll_wait` 一次能取大量就绪连接）。`workerGroup`：`SERVER_NETTY_WORKERS > 0` 用指定数，否则默认 = CPU 核数 × 2。transport 由 `server.netty.transport`（`auto`/`nio`/`epoll`）决定，经 `NettyTransport` 工厂选择 `NioEventLoopGroup`/`EpollEventLoopGroup` 及对应 Channel 实现——`auto` 在 Linux 上自动启用 native epoll（生产主场景），Windows/macOS 回退 NIO。

**⑤ `PipelineCustomizer` SPI 留两个插入点。** 在 aggregator 前后留 `beforeAggHandlers`/`afterAggHandlers`，供其他模块注入 handler。WebSocket 模块用 `addAfterAggregator` 插握手处理器，确保收到的是聚合后的 `FullHttpRequest`（见 [§3.5](#35-pipelinecustomizer-spi两插入点)）。

### 1.2 ChannelOption：六项配置驱动设置

```java
// NettyHttpServer.java
.option(ChannelOption.SO_BACKLOG,
        webContext.getProps().getInt(PropertiesConstant.SERVER_NETTY_SO_BACKLOG))
.childOption(ChannelOption.TCP_NODELAY,
        webContext.getProps().getBoolean(PropertiesConstant.SERVER_NETTY_TCP_NODELAY, ...))
.childOption(ChannelOption.SO_KEEPALIVE,
        webContext.getProps().getBoolean(PropertiesConstant.SERVER_NETTY_SO_KEEPALIVE, ...))
.childOption(ChannelOption.SO_REUSEADDR,
        webContext.getProps().getBoolean(PropertiesConstant.SERVER_NETTY_SO_REUSEADDR, ...))
.childOption(ChannelOption.ALLOCATOR,
        resolveAllocator(webContext.getProps().get(...)))
.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
        new WriteBufferWaterMark(
                webContext.getProps().getInt(PropertiesConstant.WRITE_BUFFER_LOW_WATERMARK),
                webContext.getProps().getInt(PropertiesConstant.WRITE_BUFFER_HIGH_WATERMARK)
        ))
```

这是[大纲](00-README.md) 写作前提明确要求核对的点。**核对结论**：实际设置了六项，分为 `option`（监听 socket）和 `childOption`（已接受的连接）两类：

| ChannelOption | 级别 | 默认值 | 作用 | 性能含义 |
|---------------|------|--------|------|----------|
| `SO_BACKLOG` | `option` | `128`（`server.netty.so-backlog`） | 监听 socket 的连接队列长度 | 突发连接多时需调大，默认 128 适合多数场景 |
| `TCP_NODELAY` | `childOption` | `true`（`server.netty.tcp-nodelay`） | 禁用 Nagle 算法 | 小包立即发送，降低响应延迟 |
| `SO_KEEPALIVE` | `childOption` | `false`（`server.netty.so-keepalive`） | TCP keepalive 探测 | 默认关闭，需要时开启 |
| `SO_REUSEADDR` | `childOption` | `true`（`server.netty.so-reuseaddr`） | 允许重用本地地址 | 快速重启时避免 `Address already in use` |
| `ALLOCATOR` | `childOption` | `"pooled"`（`server.netty.allocator-type`） | ByteBuf 分配器类型 | pooled 复用 ByteBuf 减少 GC |
| `WRITE_BUFFER_WATER_MARK` | `childOption` | low 8KB / high 32KB（`server.netty.write-buffer-*`） | 触发 `channelWritabilityChanged` | 背压机制的水位线，见 [§7](#七背压机制writewatermark--backpressurehandler) |

所有选项均通过 `PropertiesConstant` 配置驱动，用户可在 `application.properties` 中覆盖，非硬编码默认值。`option` 与 `childOption` 的区分有意义：`SO_BACKLOG` 作用于监听 socket（`option`），其余作用于已接受的连接（`childOption`）。

### 1.3 配置默认值速查表

写作前提要求核对的所有 I/O 层配置默认值，集中如下（均来自 [`PropertiesConstant.java`](../../spring-web/src/main/java/io/springperf/web/context/PropertiesConstant.java)）：

| 属性键 | 默认值 | 含义 | 源码行 |
|--------|--------|------|--------|
| `server.port` | `8080` | HTTP 端口 | |
| `server.netty.workers` | `0`（自动=CPU×2） | worker 线程数 | |
| `server.netty.write-buffer-low-watermark` | `8192`（8KB） | 背压低水位 | |
| `server.netty.write-buffer-high-watermark` | `32768`（32KB） | 背压高水位 | |
| `server.http.read-timeout` | `30000`（30s） | 聚合前读取超时（防慢客户端） | |
| `server.http.max-content-length` | `1048576`（1MB） | 聚合上限 | |
| `server.http.max-initial-line-length` | `4096`（4KB） | 请求行长度上限 | |
| `server.http.max-header-size` | `8192`（8KB） | headers 总大小上限 | |
| `server.http.max-chunk-size` | `8192`（8KB） | 单 chunk 上限 | |
| `server.http2.enabled` | `false` | HTTP/2 开关 | |
| `server.use-forwarded-headers` | `false` | 信任转发头 | |
| `server.shutdown.timeout` | `30000`（30s） | 优雅关闭等待 | |
| `server.netty.so-backlog` | `128` | TCP 连接队列长度 | |
| `server.netty.tcp-nodelay` | `true` | 禁用 Nagle 算法 | |
| `server.netty.so-keepalive` | `false` | TCP keepalive 探测 | |
| `server.netty.so-reuseaddr` | `true` | 重用本地地址 | |
| `server.netty.allocator-type` | `pooled` | ByteBuf 分配器类型 | | |

### 1.4 `actualPort` 写入 `local.server.port`

```java
// NettyHttpServer.java
serverChannel = bootstrap.bind(port).sync().channel();
this.actualPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
running = true;
if (webContext.getCtx() instanceof ConfigurableApplicationContext) {
    ((ConfigurableApplicationContext) webContext.getCtx())
            .getEnvironment().getSystemProperties()
            .put("local.server.port", String.valueOf(this.actualPort));
}
```

当配置 `server.port=0`（随机端口）时，实际端口要在 `bind` 后才知道。写进 `local.server.port` 系统属性，让 `@LocalServerPort` 注入、actuator、集成测试都能拿到真实端口——对齐 Spring Boot 的 `WebServerInitializedEvent` 语义（[SBA + WebServerInitializedEvent](../../) 兼容性记忆有记录）。

### 1.5 启动失败：`shutdownGracefully(0,0,SECONDS)` 防线程残留

```java
// NettyHttpServer.java
} catch (Exception e) {
    // 绑定失败时及时清理 EventLoopGroup，否则线程残留会阻止 JVM 退出
    if (bossGroup != null) {
        bossGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
    }
    if (workerGroup != null) {
        workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
    }
    throw new IllegalStateException("Failed to start Netty", e);
}
```

端口被占用时 `bootstrap.bind(port).sync()` 抛异常。`NioEventLoopGroup` 内部是守护线程但不自停，不显式关闭会阻止 JVM 退出（启动失败还挂着一堆 Netty 线程，进程僵死）。`shutdownGracefully(0, 0, SECONDS)` 即"不等、立即关"——`quietPeriod=0` 不给优雅期，`timeout=0` 不限时，立刻释放。这是"失败路径也要清理资源"的工程纪律。

### 1.6 `getPhase()=MAX_VALUE`：最后启动，最先停止

```java
// NettyHttpServer.java
@Override
public int getPhase() {
    return Integer.MAX_VALUE;
}
// NettyHttpServer.java
@Override
public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
}
```

`SmartLifecycle` 按 `getPhase()` 升序启动、降序停止。`Integer.MAX_VALUE` 让 Netty 服务器**最后启动**——确保 Spring 容器里所有其他 `SmartLifecycle` 先就绪，`startLifecycle()` 拿到的输入完整。停止时反过来最先停，先拒新连接再让其他组件排空。`getOrder()=LOWEST_PRECEDENCE` 同理用于 `LifecycleWebComponent` 的排序。

---

## 二、优雅关闭两阶段：`stop` 与 `destroyComponent`

大纲没有单列"优雅关闭"要点，但它是 I/O 层正确性的关键，且体现了[原则 4](01-design-philosophy.md#原则-4--避免阻塞非阻塞-io--显式引用计数) 对"在途请求"的尊重。关闭分两阶段，刻意把 EventLoop 关闭推迟：

```java
// NettyHttpServer.java  —— 第一阶段：stop
@Override
public void stop(Runnable callback) {
    try {
        // 1. 通知 handler 拒绝新请求（503 Service Unavailable）
        httpHandler.setShuttingDown();
        // 2. 停止接受新连接
        if (serverChannel != null) {
            serverChannel.close().sync();
        }
        // EventLoop 关闭已移至 destroyComponent()，在 BizPoolRegistry 等业务组件排空后执行
    } catch (Exception e) {
        log.error(e.getMessage(), e);
    } finally {
        running = false;
        callback.run(); // 告诉 Spring 优雅关机完成
    }
}

// NettyHttpServer.java  —— 第二阶段：destroyComponent
@Override
public void destroyComponent() throws Exception {
    if (bossGroup != null) {
        bossGroup.shutdownGracefully().sync();
    }
    if (workerGroup != null) {
        workerGroup.shutdownGracefully().sync();
    }
    log.info("Netty Server EventLoop shut down");
}
```

两阶段的分工：

| 阶段 | 动作 | 时机 | 目的 |
|------|------|------|------|
| `stop(Runnable)` | `httpHandler.setShuttingDown()` + `serverChannel.close()` | Spring 优雅关闭信号到达时 | 拒新连接（新请求 503），但**不关 EventLoop**，让在途请求继续处理 |
| `destroyComponent()` | `boss/worker.shutdownGracefully().sync()` | `WebContext` 三阶段销毁，`BizPoolRegistry` 等业务组件排空后 | 真正停 I/O 线程 |

**为什么不一次性关？** 如果 `stop` 里直接 `workerGroup.shutdownGracefully()`，在途请求（尤其 SSE 长连接）会被粗暴切断——业务线程池里还有任务没跑完，业务组件里还有未完成的工作。把 EventLoop 关闭推迟到 `destroyComponent`，让"业务组件排空"先于"I/O 线程停止"，是在途请求完整处理的保障。`setShuttingDown()` 让新请求立即拿 503（见 [§4.1](#41-channelread-与-handlerequest-入口适配)），避免关闭期间继续积累新工作。

---

## 三、Channel pipeline 装配：四协议分支

`Http2ChannelInitializer` 是 pipeline 装配的核心。它不继承任何类，是一个普通类，通过 `initChannel(ch)` 把一串 handler 装进 `ChannelPipeline`。装配逻辑按"是否 TLS × 是否 HTTP/2"分成四个分支：

### 3.1 四分支决策树

```java
// Http2ChannelInitializer.java
protected void initChannel(SocketChannel ch) {
    ChannelPipeline p = ch.pipeline();                          //
    if (sslContext != null) {
        if (http2Enabled) {
            // 分支 A：TLS + HTTP/2 → ALPN 协商（h2 或 h1.1 都可能）
            p.addLast(new Http2OrHttp1Handler(...));            //
        } else {
            // 分支 B：TLS + 仅 HTTP/1.1
            addHttp11Handlers(p);                              //
        }
    } else {
        if (http2Enabled) {
            // 分支 C：明文 + HTTP/2 → h2c prior knowledge 前缀检测
            addCleartextHttp2Handlers(p);                     //
        } else {
            // 分支 D：明文 + HTTP/1.1（最常见默认）
            addHttp11Handlers(p);                              //
        }
    }
}
```

ASCII 决策树：

```
                   sslContext != null ?
                    /               \
                 是(TLS)            否(明文)
                  /                    \
          http2Enabled?           http2Enabled?
           /        \              /        \
         是         否            是         否
         /           \            \          \
   分支A: ALPN    分支B: TLS+H1.1  分支C: h2c   分支D: 明文H1.1
   (h2/h1.1)     addHttp11Handlers  前缀检测    addHttp11Handlers
                                    (PRI magic)
```

- **分支 D 是默认**（`http2Enabled=false` 且无 TLS），绝大多数部署走这条。
- **分支 A/B 共用 `SslHandler`**：`Http2OrHttp1Handler` 和 `addHttp11Handlers`（TLS 版）都会先装 `SslContext.newHandler(ch)`。
- **分支 C 是 h2c prior knowledge**：客户端必须先发 `PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n` magic 前缀，服务器靠前缀检测分流。

### 3.2 `addHttp11Handlers`：HTTP/1.1 标准管线

```java
// Http2ChannelInitializer.java
private void addHttp11Handlers(ChannelPipeline p) {
    p.addLast(new HttpServerCodec(maxInitialLine, maxHeader, maxChunk));   //
    if (readTimeout > 0) {
        p.addLast(new ReadTimeoutHandler(readTimeout, TimeUnit.MILLISECONDS));  //
    }
    p.addLast(new ChunkedWriteHandler());                                  //
    p.addLast(beforeAggregatorHandlers);                                   // → SPI 插入点
    addAggregator(p);                                                      //
    p.addLast(afterAggregatorHandlers);                                    // → SPI 插入点
    p.addLast(BackpressureHandler.INSTANCE);                               //
    p.addLast(httpHandler);                                                //
}
```

逐个 handler 的职责：

| 位置 | Handler | 职责 | 性能/正确性含义 |
|------|---------|------|----------------|
| 1 | `HttpServerCodec` | 字节流 ↔ `HttpRequest`/`HttpContent` | Netty 原生，maxInitialLine/maxHeader/maxChunk 限制见配置表 |
| 2 | `ReadTimeoutHandler`（可选） | 聚合前读取超时 | `readTimeout>0` 才装；防慢客户端在聚合 body 前无限期占用连接（默认 30s） |
| 3 | `ChunkedWriteHandler` | 支持 `ChunkedInput` 流式写出 | 响应 `writeStream` 必需 |
| 4 | beforeAggregator handlers | SPI 插入点 | WebSocket 等模块在聚合前插自定义 handler |
| 5 | `addAggregator` | 聚合成 `FullHttpRequest` | 分流见 [§3.3](#33-addaggregatormultipart-分流) |
| 6 | afterAggregator handlers | SPI 插入点 | WebSocket 握手 handler 插这里，拿聚合后的 FullHttpRequest |
| 7 | `BackpressureHandler.INSTANCE` | 背压回调 | 见 [§7](#七背压机制writewatermark--backpressurehandler) |
| 8 | `httpHandler` | `NettyHttpHandler` 入口 | 见 [§4](#四nettyhttphandler入口适配层) |

**大纲 写的 `SslHandler` → `HttpTrafficHandler` 是规划命名**，实际 HTTP/1.1 管线里没有叫 `HttpTrafficHandler` 的类——业务 handler 就是 `NettyHttpHandler`，背压由独立的 `BackpressureHandler` 担当。`SslHandler` 只在分支 A/B（TLS）出现，分支 D（明文默认）没有。

### 3.3 `addAggregator`：multipart 分流

```java
// Http2ChannelInitializer.java
private void addAggregator(ChannelPipeline p) {
    if (supportMultipart) {
        p.addLast(new SupportMultipartAggregator(maxContentLength));   // 框架自研聚合器
    } else {
        p.addLast(new HttpObjectAggregator(maxContentLength));          // Netty 原生
    }
}
```

`supportMultipart` 在主服务器构造时固定为 `true`（[`NettyHttpServer.java`](../../spring-web/src/main/java/io/springperf/web/server/NettyHttpServer.java)）。框架自研的 `SupportMultipartAggregator` 在 Netty `HttpObjectAggregator` 基础上扩展了 multipart 解析能力（含文件上传的零拷贝处理，联动 [§5](#五nettyserverhttprequest零拷贝包装) 的 `parseParameters`）。管理端口可设 `supportMultipart=false` 走原生聚合器，省一点开销。

`maxContentLength` 默认 1MB（`HTTP_MAX_CONTENT_LENGTH`）。超过 1MB 的请求体聚合会抛 `413 Request Entity Too Large`——这是 fail-fast 在 I/O 层的体现，避免单请求 OOM。

### 3.4 分支 C：h2c prior knowledge 前缀检测

明文 HTTP/2（h2c）有两种模式：升级协商（`Upgrade: h2c`）和 prior knowledge。本框架走 **prior knowledge**——客户端必须先发 HTTP/2 连接前缀 `PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n`，服务器靠前缀字节检测分流：

```java
// Http2ChannelInitializer.java
private static final byte[] H2_PREFACE_BYTES =
        "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.UTF_8);  // 24 字节

// Http2ChannelInitializer.java  addCleartextHttp2Handlers
// 用匿名 ChannelInboundHandlerAdapter 检测前缀
```

检测逻辑处理两种到达情况：

**整包路径**（`buf.readableBytes() >= 24`）：

```
buf.startsWithPreface(H2_PREFACE_BYTES)?
   是 → addBefore h2-frame-codec(Http2FrameCodecBuilder.forServer().build())
        + h2-multiplex(Http2MultiplexHandler + Http2ChildChannelInitializer)
        → remove(this) → fireChannelRead    // 走 HTTP/2
   否 → remove(this) → fireChannelRead      // 当 HTTP/1.1 处理
```

**分片路径**（`buf.readableBytes() < 24`，前缀被 TCP 拆成多个包）：用 `accumulator`（`ctx.alloc().buffer(24)`）累积，每片写入 accumulator 后 `buf.release()`（**防泄漏**——原始 buf 是引用计数的，不释放就漏），积满 24 字节再判断。`handlerRemoved`也会 release accumulator，确保任何移除路径都不漏。

检测命中后，装配 HTTP/2 frame codec + multiplex handler，每个 HTTP/2 stream 走独立的 child channel（见 [§3.6](#36-http2-子流-pipelinehttp2childchannelinitializer)）。

### 3.5 `PipelineCustomizer` SPI：两插入点

```java
// PipelineCustomizer.java
private final List<ChannelHandler> beforeAggregatorHandlers = new ArrayList<>();
private final List<ChannelHandler> afterAggregatorHandlers = new ArrayList<>();

public void addBeforeAggregator(ChannelHandler handler) { beforeAggregatorHandlers.add(handler); }
public void addAfterAggregator(ChannelHandler handler)  { afterAggregatorHandlers.add(handler); }
public List<ChannelHandler> getBeforeAggregatorHandlers() { return Collections.unmodifiableList(beforeAggregatorHandlers); }
public List<ChannelHandler> getAfterAggregatorHandlers()  { return Collections.unmodifiableList(afterAggregatorHandlers); }
```

两个插入点（aggregator 前 / 后）的区分有意义：

- **beforeAggregator**：handler 收到的是未聚合的 `HttpContent` 片段——适合需要流式处理 body、不想等全部到达的场景。
- **afterAggregator**：handler 收到的是聚合后的 `FullHttpRequest`——适合需要完整请求再决策的场景。**WebSocket 模块用这个**：握手需要读 `Sec-WebSocket-Key` 等 header，必须等聚合完。

返回 `unmodifiableList` 防止外部误改，是[原则 6](01-design-philosophy.md#原则-6--避免魔法行为显式-sp显式-fail-fast不靠隐式猜测) "显式、可预测"的体现。

### 3.6 HTTP/2 子流 pipeline：`Http2ChildChannelInitializer`

HTTP/2 是多路复用——一条 TCP 连接上跑多个 stream，每个 stream 在框架里是一个独立 child channel，各自有完整 pipeline：

```java
// Http2ChannelInitializer.java
// 每个 HTTP/2 stream 的 child channel pipeline：
Http2StreamFrameToHttpObjectCodec(true)   // true=服务端，把 h2 frame 转 HttpObject
  → ChunkedWriteHandler
  → SupportMultipartAggregator / HttpObjectAggregator
  → BackpressureHandler.INSTANCE
  → httpHandler
```

注意子流 pipeline 与 HTTP/1.1 `addHttp11Handlers` 的结构对称：`ChunkedWriteHandler` → aggregator → `BackpressureHandler` → `httpHandler`，只是把 `HttpServerCodec` 换成 `Http2StreamFrameToHttpObjectCodec`（h2 frame ↔ HttpObject 适配）。这种对称设计的收益是：**`NettyHttpHandler` 不区分 HTTP/1.1 还是 HTTP/2**——无论哪个协议版本，到达它的都是 `FullHttpRequest`，下游 `DispatcherHandler` 完全无感。协议差异被 I/O 层完全吸收。

### 3.7 分支 A：TLS ALPN 协商

`Http2OrHttp1Handler` 继承 Netty 的 `ApplicationProtocolNegotiationHandler`，用 ALPN（Application-Layer Protocol Negotiation）在 TLS 握手期协商协议：

```java
// Http2ChannelInitializer.java
// configurePipeline
ALPN = "h2"   → remove(SslExceptionHandler) + Http2FrameCodecBuilder.forServer().build()
                + Http2MultiplexHandler(Http2ChildChannelInitializer)   // HTTP/2 over TLS
ALPN = "http/1.1" → addHttp11Handlers（等价于分支 B）                    // HTTP/1.1 over TLS
```

ALPN 协商在 TLS 握手阶段完成，**握手后就知道走 h2 还是 h1.1**，无需像 h2c 那样检测前缀字节。协商结果 `h2` 就装 frame codec + multiplex，`http/1.1` 就走标准 HTTP/1.1 管线。这让同一个 443 端口能同时服务 HTTP/2 和 HTTP/1.1 客户端——浏览器（大多支持 h2）和老旧客户端各取所需。

### 3.8 `NettyMetricsHandler`：连接计数单例

```java
// NettyMetricsHandler.java
@ChannelHandler.Sharable
public class NettyMetricsHandler extends ChannelInboundHandlerAdapter {
    public static final NettyMetricsHandler INSTANCE = new NettyMetricsHandler();
    private final AtomicInteger activeConnections = new AtomicInteger();

    @Override public void channelActive(ChannelHandlerContext ctx) { activeConnections.incrementAndGet(); ... }
    @Override public void channelInactive(ChannelHandlerContext ctx) { activeConnections.decrementAndGet(); ... }
    public int getActiveConnectionCount() { return activeConnections.get(); }
}
```

`@Sharable` 单例，装在 pipeline 头部（[`NettyHttpServer.java`](../../spring-web/src/main/java/io/springperf/web/server/NettyHttpServer.java) `addLast(NettyMetricsHandler.INSTANCE)`）。`AtomicInteger` 计活跃连接数，`channelActive`/`channelInactive` 增减，供 `NettyHttpServer.getActiveConnectionCount()`暴露给 actuator/metrics。单例 + `@Sharable` 是因为所有连接共享一个计数器——若每连接一个实例就失去聚合计数意义。

---

## 四、`NettyHttpHandler`：入口适配层

`NettyHttpHandler` 是 pipeline 末端、业务入口前最后一个 handler。它把 Netty 的 `FullHttpRequest` 包装成框架的 `NettyServerHttpRequest`/`NettyServerHttpResponse`，然后委托给 `HttpHandler`（策略接口）。

### 4.1 `channelRead` 与 `handleRequest`：入口适配

```java
// NettyHttpHandler.java  构造
public NettyHttpHandler(WebContext webContext, String contextPath, HttpHandler handler) {
    this.webContext = webContext;
    this.contextPath = contextPath;
    this.handler = handler;          // DispatcherHandler 或 ManagementDispatcherHandler
}

// NettyHttpHandler.java  channelRead
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof FullHttpRequest) {
        try {
            handleRequest(ctx, (FullHttpRequest) msg);
        } finally {
            ReferenceCountUtil.release(msg);    // 入口对称释放
        }
    } else {
        ctx.fireChannelRead(msg);
    }
}
```

`@Sharable`（[`NettyHttpHandler.java`](../../spring-web/src/main/java/io/springperf/web/server/NettyHttpHandler.java) 类注解）——所有连接共享一个实例（`NettyHttpServer` 启动期 `new` 一次，:62）。`channelRead` 的 `try/finally release(msg)` 是入口的引用计数对称：Netty 的 `FullHttpRequest` 是堆外 ByteBuf 持有的引用计数对象，进来一次引用，处理完必须在入口释放——否则每请求泄漏一个 ByteBuf。

但"处理完"的语义在这里很微妙：`handleRequest` 内部会 `msg.retain()`（见下文），因为请求对象要传给 `DispatcherHandler`，可能跨线程到业务池，生命周期超出 `channelRead` 的栈。**入口 `release` 的是自己那次引用，`handleRequest` 的 `retain` 给业务侧新加一次引用**——两者配对，引用计数最终归零由业务侧 `release` 负责（见 [§5.5](#55-acquirerelease-引用计数契约)）。

### 4.2 `handleRequest`：校验、包装、委托

```java
// NettyHttpHandler.java  handleRequest（简化）
private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest msg) {
    // ① 关闭期 503
    if (shuttingDown) {
        sendError(ctx, msg, SERVICE_UNAVAILABLE);   //
        return;
    }
    // ② 构造响应对象（先构造，便于错误也能写回）
    NettyServerHttpResponse resp = new NettyServerHttpResponse(webContext, ctx, HttpUtil.isKeepAlive(msg));
    // ③ 解析 rawUri 去 query
    String rawUri = msg.uri();
    String path = parsePath(rawUri);               //
    // ④ contextPath 三态校验
    if (!checkContextPath(path)) {                  //
        sendError(ctx, msg, NOT_FOUND);             // 404
        return;
    }
    // ⑤ retain 请求，传给业务侧
    msg.retain();                                   //
    NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, msg, resolvedPath);
    resp.setTimeout(...);                           //
    // ⑥ 委托策略接口
    try {
        handler.httpHandle(req, resp);             //
    } catch (Throwable t) {
        sendError(ctx, msg, INTERNAL_SERVER_ERROR); //  500 兜底
    } finally {
        req.release();                              //
    }
}
```

关键设计点：

**① 关闭期 503 优先。** `shuttingDown` 标志由 `NettyHttpServer.stop()` 的 `setShuttingDown()` 设置（[§2](#二优雅关闭两阶段stop-与-destroycomponent)）。设了之后，新进来的请求立即 503，不再进入 `DispatcherHandler`——这是优雅关闭"拒新"语义的 I/O 层实现。

**④ contextPath 三态校验。** `contextPath` 为空 / 等于请求 path / 是请求 path 前缀且后跟 `/`——三态匹配，不匹配直接 404。这避免把不属于本 context 的请求误送进路由引擎。

**⑤ `msg.retain()` 在构造 `req` 前。** 这是 [原则 4](01-design-philosophy.md#原则-4--避免阻塞非-blocking-io--显式引用计数) 的精确落地：请求对象即将跨线程（EventLoop → 业务池，见 [04 篇](04-request-pipeline.md) `handleWithMappingResult` 的 `acquire`），必须先 `retain` 给业务侧加一次引用，否则入口 `finally release` 后业务侧拿到的 ByteBuf 已释放。

**⑥ `catch Throwable` 500 兜底。** 任何异常不抛给 Netty（Netty 的 `ExceptionCaught` 会关闭连接），而是收敛成 500 响应写回——这是 [`development.md`](../../.agent/rule/development.md) "异常统一收敛到 ExceptionRegistry 不抛给 Netty" 在 I/O 层的兜底。

### 4.3 `HttpHandler` 策略接口：主端口 vs 管理端口

```java
// HttpHandler.java
@FunctionalInterface
public interface HttpHandler {
    void httpHandle(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception;
}
```

`@FunctionalInterface`，两个实现：

- **`DispatcherHandler`**：主端口，走完整路由 → Filter → 业务方法链路（[04 篇](04-request-pipeline.md) 详述）。
- **`ManagementDispatcherHandler`**：管理端口（actuator 端点），走精简链路。

`NettyHttpServer` 构造时由外部注入 `dispatcher`（[`NettyHttpServer.java`](../../spring-web/src/main/java/io/springperf/web/server/NettyHttpServer.java)），同一个 `NettyHttpHandler` 机制能服务两种端口——策略模式把"用哪个 handler 处理"的决定权交给构造方，I/O 层不感知。

---

## 五、`NettyServerHttpRequest`：零拷贝包装

`NettyServerHttpRequest` 把 Netty 的 `FullHttpRequest` 包装成框架契约的 `WebServerHttpRequest`。包装的核心目标是**零拷贝**——headers 和 body 都尽量不复制，直接视图化暴露。

### 5.1 headers 零拷贝视图：`NettyHttpHeadersAdapter`

```java
// NettyServerHttpRequest.java
@Override
public HttpHeaders getHeaders() {              // 声明类型是 Spring HttpHeaders（契约），
    if (headers == null) {                     // 实际返回 WebHttpHeaders 实现
        headers = new WebHttpHeaders(
                new NettyHttpHeadersAdapter(request.headers(), true));  // writable=true 写穿透
    }
    return headers;
}
```

`NettyHttpHeadersAdapter` 直接委托 Netty 的 `HttpHeaders`，免去把 header 逐条拷进 `LinkedMultiValueMap` 的 O(n) 开销：

```java
// NettyHttpHeadersAdapter.java
private final HttpHeaders headers;   // Netty 原生 headers
private final boolean writable;

// 读 API 直接委托
public String getFirst(String key) { return headers.get(key); }
public int size() { return headers.size(); }
public boolean containsKey(Object key) { return headers.contains(key); }
// ...

// 写 API 受 writable 控制
public Object put(String key, String value) {
    checkWritable();                 // 只读时抛 UnsupportedOperationException
    headers.add(key, value);         // 否则直接写穿 Netty headers
    return null;
}
```

**为什么自研而不用 Spring 的 `Netty4HeadersAdapter`？** 两个原因：

1. **版本兼容**：`Netty4HeadersAdapter` 仅 Spring 6.1+，本框架要兼容 Spring Boot 2.4.x~4.1.x（[SB 3.x 多版本兼容性验证](../../) 记忆有记录 POM parent→BOM+profiles 改造）。
2. **语义**：`writable` 标志支持"只读视图"模式——某些场景只需读 header 不应被改，`checkWritable()` 抛异常而非静默写入，符合[原则 6](01-design-philosophy.md#原则-6--避免魔法行为显式-sp显式-fail-fast不靠隐式猜测)。

**附带修复一个旧 bug**：旧实现把 header 拷进 `LinkedMultiValueMap`（大小写敏感），导致 Netty 原生的大小写不敏感解析丢失——小写 key 的 `get("content-type")` 拿不到 `Content-Type` 的值。直接委托 Netty headers 保留了原生大小写不敏感，这个问题随之消失。

### 5.2 `WebHttpHeaders`：跨版本 HttpHeaders

`WebHttpHeaders` 同时 `extends HttpHeaders`（Spring）和 `implements MultiValueMap`，靠 `static final` + `MethodHandle` 在类加载期解决版本分支：

```java
// WebHttpHeaders.java
private static final boolean HEADERS_IS_MULTI_VALUE_MAP;   // 6.x: true, 7.x: false
private static final MethodHandle AS_MULTI_VALUE_MAP;     // 7.x 反射拿 asMultiValueMap()

static {
    boolean isMVM = false;
    MethodHandle asMVM = null;
    try {
        // 探测 HttpHeaders 是否本身就是 MultiValueMap（Spring 6.x 是，7.x 否）
        isMVM = MultiValueMap.class.isAssignableFrom(HttpHeaders.class);
        if (!isMVM) {
            // 7.x 用 asMultiValueMap() 方法转
            Method m = HttpHeaders.class.getMethod("asMultiValueMap");
            asMVM = MethodHandles.lookup().unreflect(m);
        }
    } catch (...) { }
    HEADERS_IS_MULTI_VALUE_MAP = isMVM;
    AS_MULTI_VALUE_MAP = asMVM;
}
```

`static final boolean` 让 JIT 能完全消除版本分支——运行时只剩一条路径，无反射开销。`getContentType` 还缓存了 `parseMediaType` 结果，避免重复解析；`setContentType` 清缓存。

### 5.3 body 零拷贝分级：`LARGE_BODY_LIMIT = 4096`

请求体读取是零拷贝分级的关键——小 body 复制到堆 `byte[]`，大 body 共享 ByteBuf 视图：

```java
// NettyServerHttpRequest.java  字段
private static final int LARGE_BODY_LIMIT = 4096;
private static final byte[] EMPTY_BODY = new byte[0];
private volatile byte[] body;              // 无初始化，getBodyBytes 首次填充
private ByteBuf largeBodyBuf;              // 非 volatile：仅由持有 request 的线程读写

// NettyServerHttpRequest.java  getBody / getBodyBytes
@Override
public InputStream getBody() {
    getBodyBytes();                                        // 先触发 DCL 初始化
    if (largeBodyBuf != null) {
        return new ByteBufInputStream(largeBodyBuf.duplicate(), false);  // false=不 release
    }
    return new ByteArrayInputStream(body);
}

protected byte[] getBodyBytes() {           // protected，非 private
    if (body == null) {                    // DCL：外层无锁快路径
        synchronized (this) {
            if (body == null) {
                ByteBuf content = request.content();
                int size = content.readableBytes();
                if (size <= LARGE_BODY_LIMIT) {
                    body = ByteBufUtil.getBytes(content);  // 复制到堆 byte[]（含 size==0 返空数组）
                } else {
                    largeBodyBuf = content.duplicate();    // 共享视图，不 +refCnt
                    body = EMPTY_BODY;                     // 大 body 时 body 占位为单例
                }
            }
        }
    }
    return body;
}
```

分级的依据：

| body 大小 | 处理方式 | 原因 |
|----------|---------|------|
| `size <= 4096`（含 `size == 0`） | `ByteBufUtil.getBytes` 复制到堆 `byte[]`（`size==0` 返回空数组，非 `EMPTY_BODY` 单例） | 小 body 复制代价低，堆 `byte[]` 无引用计数管理负担，业务侧用完即 GC |
| `size > 4096` | `content.duplicate()` 共享视图，`body` 置 `EMPTY_BODY` 单例占位 | 大 body 复制代价高，duplicate 不拷贝字节、不 +refCnt，直接共享 ByteBuf 的可读区域 |

**大纲 写的 `retainedDuplicate` 实际是 `duplicate`**——这是规划措辞与实现的关键差异。`retainedDuplicate` 会 `+refCnt`，`duplicate` 不递增。本框架用 `duplicate`（不 +refCnt），因为大 body 的存活由**请求对象自身的 retain/release 链**保证（见 [§5.5](#55-acquirerelease-引用计数契约)），duplicate 出来的视图不独立持有引用。`ByteBufInputStream` 构造传 `false`（不 release）也对应这点——流关闭时不 release，避免重复释放。

**为什么阈值是 4096？** 这是经验值：4KB 以下的 body（绝大多数 JSON API 请求）复制到堆更划算——堆 `byte[]` 无堆外内存管理开销，GC 友好；超过 4KB 后复制代价上升，且堆外 ByteBuf 的零拷贝优势（省 heap→direct 拷贝）显现。这个阈值在 [`NettyServerHttpRequest.java`](../../spring-web/src/main/java/io/springperf/web/http/NettyServerHttpRequest.java) 硬编码，未配置化。

### 5.4 `resolveScheme`：scheme 解析四优先级

```java
// NettyServerHttpRequest.java
private String resolveScheme() {
    // 优先级 1：RFC7239 Forwarded proto（仅 use-forwarded-headers=true）
    // 优先级 2：X-Forwarded-Proto（同条件）
    // 优先级 3：pipeline 里有 SslHandler → https（TLS 在 Java 层终结）
    // 优先级 4：兜底 http
}
```

四优先级的逻辑：

1. **`Forwarded` proto**：仅当 `server.use-forwarded-headers=true`（默认 false）。RFC7239 标准转发头。
2. **`X-Forwarded-Proto`**：同条件。常见反向代理（Nginx/ALB）用的非标准头。
3. **`SslHandler` 存在**：`ctx.pipeline().get(SslHandler.class) != null` → `https`。TLS 在 Netty Java 层终结，pipeline 里有 `SslHandler` 即证明是 TLS 连接。
4. **兜底 `http`**。

**转发头默认不信任**（`USE_FORWARDED_HEADERS` 默认 false）是安全考量——转发头可被客户端伪造，仅在部署于受信反代后方时才开启。这是[原则 6](01-design-philosophy.md#原则-6--避免魔法行为显式-sp显式-fail-fast不靠隐式猜测) "不靠隐式猜测"在安全维度的延伸。

### 5.5 `acquire`/`release`：引用计数契约

```java
// NettyServerHttpRequest.java
@Override
public void acquire() {
    ReferenceCountUtil.retain(request);   // +1
}

@Override
public void release() {
    ReferenceCountUtil.release(request);  // -1，幂等
}
```

`WebServerHttpRequest` 接口契约（[:162-179](../../spring-web/src/main/java/io/springperf/web/http/WebServerHttpRequest.java)）：**跨业务线程前必须 `acquire`，处理完配对 `release`**。这个契约在 [04 篇](04-request-pipeline.md) `DispatcherHandler.handleWithMappingResult` 的 `acquire() → executor.execute → release()` 里被遵守，是 [原则 4](01-design-philosophy.md#原则-4--避免阻塞非-blocking-io--显式引用计数) 的硬约束。

`release` 幂等——`ReferenceCountUtil.release` 内部对 refCnt 归零后的再次调用会抛 `IllegalReferenceCountException`，但框架在 `NettyHttpHandler.handleRequest` 的 `finally` 与业务侧 `finally` 两处配对释放，靠"恰好一次"的对称性保证不重复。`largeBodyBuf` 是 `duplicate` 无独立引用，不需要单独 release——它的存活由 `request` 的引用计数托底。

---

## 六、`NettyServerHttpResponse`：四写出路径

`NettyServerHttpResponse` 把框架的响应语义（`WebServerHttpResponse`）适配到 Netty 的 `ByteBuf`/`DefaultFileRegion` 写出。有四条写出路径，每条针对不同响应类型。

### 6.1 延迟分配 + headers 零拷贝

```java
// NettyServerHttpResponse.java  字段
private final ChannelHandlerContext ctx;
private final HttpHeaders nettyHeaders;   // 与响应对象共享的 headers
private ByteBuf buf;                      // 延迟分配

// NettyServerHttpResponse.java  构造
public NettyServerHttpResponse(...) {
    this.nettyHeaders = new DefaultHttpHeaders(false);   // false=不校验（validate 跳过 Netty 热点）
    setHeaders(new WebHttpHeaders(new NettyHttpHeadersAdapter(nettyHeaders, true)));  // writable 写穿
}

// NettyServerHttpResponse.java  延迟分配
@Override
protected ByteBuf getBuf() {
    if (buf == null) {
        buf = ctx.alloc().buffer(256);    // 首次写才分配，初始 256 字节
    }
    return buf;
}

@Override
public OutputStream getBody() {
    return new ByteBufOutputStream(getBuf());
}
```

两个性能点：

**`buf` 延迟分配**。`ctx.alloc().buffer(256)` 只在首次 `getBody()`/`write` 时触发——若响应是 204/304 无 body，或直接 `writeFile`/`writeBytes` 不走 `getBuf`，就完全不分配这个 ByteBuf。初始 256 字节是经验值，多数 JSON 响应头 + 小 body 能装下，`buffer` 会自动扩容。

**`nettyHeaders` 与响应对象共享**。`DefaultHttpHeaders(false)`（`validate=false`，跳过 Netty header 校验热点）通过 `NettyHttpHeadersAdapter` 暴露给 `WebHttpHeaders`，**commit 时零拷贝**——`initHttpResponse` 直接把 `nettyHeaders` 传给 `DefaultFullHttpResponse`/`DefaultHttpResponse`，不复制。框架 headers 写穿透到 Netty 响应对象。

### 6.2 路径一：`flush(boolean chunked)` → `initHttpResponse` → `writeAndFlush`

这是默认写出路径（`getBody()` 写完 OutputStream 后调 `flush(false)`）：

```java
// NettyServerHttpResponse.java  initHttpResponse（三 DefaultHttpResponse 分支）
private HttpResponse initHttpResponse(ByteBuf body, String contentType, HttpHeaders headers, boolean chunked) {
    HttpResponse response;
    if (body != null) {
        response = new DefaultFullHttpResponse(HTTP_1_1, status, body, nettyHeaders, EmptyHttpHeaders.INSTANCE);
    } else if (chunked) {
        response = new DefaultHttpResponse(HTTP_1_1, status, nettyHeaders, false);
    } else {
        response = new DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.EMPTY_BUFFER, nettyHeaders, EmptyHttpHeaders.INSTANCE);
    }
    // Content-Length / Transfer-Encoding / Connection 头
    // ...
    return response;
}

// NettyServerHttpResponse.java  flush
protected void flush(boolean chunked) {
    try {
        writeAndFlush(initHttpResponse(buf, getContentType(), nettyHeaders, chunked));
    } catch (Throwable t) {
        if (buf != null) { buf.release(); buf = null; }   // 错误释放，置空防 getBuf 返已释放
    }
}
```

三分支：有 body → `DefaultFullHttpResponse(body)`；chunked 流式 → `DefaultHttpResponse`（无 body，后续分块写）；无 body → `DefaultFullHttpResponse(EMPTY_BUFFER)`。`flush` 错误时 `buf.release()` + 置空——置空是防 `getBuf()` 返回已释放的 ByteBuf 导致 use-after-release，又是 [原则 4](01-design-philosophy.md#原则-4--避免阻塞非-blocking-io--显式引用计数) 的纪律。

### 6.3 路径二：`writeStream(InputStream)` — chunked 流式

```java
// NettyServerHttpResponse.java
@Override
public void writeStream(InputStream stream) {
    initHttpResponse(null, "application/octet-stream", null, true);  // chunked=true
    // chunked + ChunkedStream + HttpChunkedInput + ctx.writeAndFlush
}
```

未知长度的 `InputStream` 用 chunked transfer encoding——`ChunkedStream` 包装 InputStream，`HttpChunkedInput` 转 HTTP chunk，`ChunkedWriteHandler`（pipeline 装的）负责分块 flush。适合动态生成、无法预知长度的响应。

### 6.4 路径三：`writeBytes(byte[])` — 单次写出小已知 size

```java
// NettyServerHttpResponse.java
@Override
public void writeBytes(byte[] data) {
    ByteBuf buf = Unpooled.wrappedBuffer(data);    // 零拷贝包装，不复制
    HttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status, buf, nettyHeaders, EmptyHttpHeaders.INSTANCE);
    writeAndFlush(response);                        // 单次 writeAndFlush
}
```

`Unpooled.wrappedBuffer(data)` 零拷贝包装 `byte[]`（不复制字节），`DefaultFullHttpResponse` 单次写出——**避免 chunked**。已知小 size 的响应走这条，一个 TCP 段发完，省 chunked 的多帧开销。这是"已知 size 用 Content-Length，未知 size 用 chunked"的 RFC7230 §3.3.2 取舍。

### 6.5 路径四：`writeFile(File)` — `DefaultFileRegion` 零拷贝 sendfile

```java
// NettyServerHttpResponse.java
@Override
public void writeFile(File file) throws IOException {
    long fileLen = file.length();
    initHttpResponse(null, contentType, null, true);   // 先 chunked
    // 然后移除 Transfer-Encoding，设 Content-Length（双帧共存非法 RFC7230 §3.3.2）
    nettyHeaders.remove(TRANSFER_ENCODING);
    nettyHeaders.setInt(CONTENT_LENGTH, fileLen);

    FileChannel fc = new FileInputStream(file).getChannel();
    DefaultFileRegion region = new DefaultFileRegion(fc, 0, fileLen);  // 零拷贝 sendfile
    ctx.write(region);
    // listener：关 FileChannel
    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);   //  补发空 LastHttpContent
}
```

`DefaultFileRegion` 触发 Linux `sendfile(2)`——内核直接从文件描述符拷到 socket，**不经过用户态**，是真正的零拷贝（省 read/write 两次用户态拷贝）。大文件下载走这条，吞吐量极高。

**为什么要补发 `LastHttpContent.EMPTY_LAST_CONTENT`？** 这是一个容易踩坑的细节：`DefaultFileRegion` **不是 `HttpObject`**，写它不会重置 `HttpObjectEncoder` 的状态机。HTTP/1.1 keep-alive 连接复用时，`HttpObjectEncoder` 靠 `LastHttpContent` 标记响应结束并重置状态——若不补发，下一个请求进来时 `HttpObjectEncoder` 状态错乱，抛 `"unexpected message type: DefaultHttpResponse, state: 1"`，连接被污染。补发一个空的 `LastHttpContent` 就是给 `HttpObjectEncoder` 发"响应结束"信号。

### 6.6 `handled`/`committed` 双 CAS + 超时取消

```java
// BaseWebServerHttpResponse.java
private final AtomicBoolean handled = new AtomicBoolean(false);
private final AtomicBoolean committed = new AtomicBoolean(false);

// BaseWebServerHttpResponse.java  setHandled
public void setHandled() { handled.compareAndSet(false, true); }

// BaseWebServerHttpResponse.java  setCommitted
public void setCommitted() {
    setHandled();                              // committed 隐含 handled
    if (committed.compareAndSet(false, true)) {
        setTimeout(null, -1);                  // 取消超时
    }
}
```

`handled`（业务侧已处理）和 `committed`（响应已写回 Channel）是两个独立 CAS 状态。`setCommitted` 隐含 `setHandled`（已写回当然算已处理），并取消超时——响应一旦 commit，超时定时器就没意义了，取消避免定时器误触发。

`setTimeout`用 `scheduleOnEventLoop(task, delay, MS)` 在 EventLoop 上调度超时任务——必须在 EventLoop 线程调度，避免跨线程定时器的锁开销。`sendError`用 `escapeJson`转义错误消息，防 XSS。

### 6.7 `WriteRespEventListener`：写出回调

```java
// NettyServerHttpResponse.java  addRespEventListener
@Override
public void addRespEventListener(WriteRespEventListener listener) {
    if (isComplete()) {
        // 已完成 → 触发 complete 回调
    } else {
        // 未完成 → 注册 writeStream 回调
    }
}
```

`WriteRespEventListener`（[](../../spring-web/src/main/java/io/springperf/web/http/WriteRespEventListener.java)）定义四个回调：`completeSuccessCallback`/`completeErrorCallback`/`writeStreamSuccessCallback`/`writeStreamErrorCallback`（后两个有 default 实现）。`CompositeWriteRespEventListener`（[](../../spring-web/src/main/java/io/springperf/web/http/BaseWebServerHttpResponse.java)）合并多监听器广播。

`NettyServerHttpResponse` 还有个静态 `LOG_ERROR_ON_FAILURE`（[:31-40](../../spring-web/src/main/java/io/springperf/web/http/NettyServerHttpResponse.java)）——默认监听器，对 `ClosedChannelException` 静默（连接已关闭时写失败是预期行为，不刷错误日志）。无显式 listener 时用这个默认，避免每连接刷一堆无意义错误日志。

---

## 七、背压机制：`WriteWaterMark` + `BackpressureHandler`

背压是 [原则 4](01-design-philosophy.md#原则-4--避免阻塞非-blocking-io--显式引用计数) "非阻塞"的关键支撑——当下游消费慢于上游生产时，必须能把压力传回上游，否则 OOM。

### 7.1 水位线触发 `channelWritabilityChanged`

```java
// NettyHttpServer.java  设置水位线
.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
        new WriteBufferWaterMark(lowWatermark, highWatermark))   // 默认 8KB / 32KB
```

Netty 的 `ChannelOutboundBuffer` 在写入字节超过 `highWatermark` 时自动把 `Channel.isWritable()` 置 `false`，低于 `lowWatermark` 时置 `true`。这个状态变化触发 `channelWritabilityChanged` 事件——`BackpressureHandler` 监听它：

```java
// BackpressureHandler.java
@ChannelHandler.Sharable
public final class BackpressureHandler extends ChannelInboundHandlerAdapter {
    public static final BackpressureHandler INSTANCE = new BackpressureHandler();

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable()) {
            ConnectionContext conn = ctx.channel().attr(CONN_CTX).get();
            if (conn != null && !conn.isLastWritable()) {
                // 只处理 false → true 转换
                conn.updateWritable(true);
                conn.getOnWritable().run();                // 触发回调
            }
        }
        ctx.fireChannelWritabilityChanged();
    }
}
```

**只处理 `false → true` 转换**（`!conn.isLastWritable() && writable`）。为什么？`true → false`（变为不可写）时，Netty 的 `Channel.isWritable()` 已经是 `false`，直接调 `ctx.write()` 会被 Netty 自动缓冲（不抛、不丢，只是堆在 outbound buffer）——框架无需干预。只有恢复可写（`false → true`）时，才需要通知上游"可以继续写了"，触发 `onWritable.run()` 回调让业务侧续写。

### 7.2 `ConnectionContext`：背压回调载体

```java
// ConnectionContext.java
public class ConnectionContext {
    private volatile boolean lastWritable = true;   // 上次状态，初始 true
    private volatile Runnable onWritable;           // 可写回调

    public boolean isLastWritable() { return lastWritable; }
    public void updateWritable(boolean w) { this.lastWritable = w; }
    public Runnable getOnWritable() { return onWritable; }
    // setter ...
}
```

`lastWritable` 记上次状态，用于检测转换边沿（`!lastWritable && writable` 就是 `false→true`）。`onWritable` 是业务侧注册的续写回调。`ConnectionContext` 通过 `CONN_CTX` AttributeKey 绑定到 Channel（[`NettyServerHttpResponse.java`](../../spring-web/src/main/java/io/springperf/web/http/NettyServerHttpResponse.java)），每个连接独立一份。

### 7.3 `setWritableCallback`：注册续写回调

```java
// NettyServerHttpResponse.java
@Override
public void setWritableCallback(Runnable callback) {
    runOnEventLoop(() -> {
        ConnectionContext conn = ctx.channel().attr(CONN_CTX).get();
        if (conn == null) {
            conn = new ConnectionContext();
            ctx.channel().attr(CONN_CTX).set(conn);
        }
        conn.setOnWritable(callback);
    });
}
```

`runOnEventLoop` 确保回调注册在 EventLoop 线程——`ConnectionContext` 的字段虽是 `volatile`，但 `attr().set()` 与 `channelWritabilityChanged` 的读必须在同一线程（EventLoop）才安全。SSE/流式响应（[11 篇](11-async-streaming.md) 详述）用这个机制：写出一批数据后若 `isWritable()=false`，注册 `onWritable` 等恢复后续写，实现非阻塞背压。

---

## 八、内存管理：引用计数 vs GC `Cleaner`

[原则 4](01-design-philosophy.md#原则-4--避免阻塞非-blocking-io--显式引用计数) 在 I/O 层的核心是**显式引用计数管理堆外 ByteBuf**。这一节把散落各处的引用计数纪律收拢成一张生命周期图。

### 8.1 一个请求的 ByteBuf 引用计数全程

```
Netty 分配 FullHttpRequest (refCnt=1，堆外 DirectByteBuf 持有 body)
        │
        ▼
NettyHttpHandler.channelRead  ← 入口
        │  try { handleRequest }
        │      │
        │      ▼
        │  handleRequest: msg.retain()  (refCnt=2)  ← 给业务侧加引用
        │      │
        │      ▼
        │  handler.httpHandle(req, resp)  → DispatcherHandler
        │      │
        │      ▼  (若跨线程到业务池)
        │  DispatcherHandler: req.acquire()  (refCnt=3)  ← 跨线程前再加
        │      │
        │      ▼  业务线程处理
        │  ... 业务方法读 body（largeBodyBuf.duplicate 不 +refCnt）...
        │      │
        │      ▼
        │  DispatcherHandler finally: req.release()  (refCnt=2)  ← 业务侧释放
        │      │
        │      ▼
        │  handleRequest finally: req.release()  (refCnt=1)  ← 业务委托完释放
        │
        ▼
channelRead finally: ReferenceCountUtil.release(msg)  (refCnt=0)  ← 入口对称释放，ByteBuf 回收
```

三次 retain（入口 `msg.retain` + 业务 `acquire` + ... 实际是两次 retain + 入口对称 release，refCnt 精确管理）配三次 release，最终归零，ByteBuf 回收到 Netty 的 `PoolChunk` / direct memory arena。**任何一处漏 release 都泄漏堆外内存**。

### 8.2 为什么不用 GC `Cleaner`？

Java 13+ 的 `Cleaner`（替代 `finalize`）能在 ByteBuf 被 GC 时回收堆外内存，但本框架显式拒绝依赖它：

| 维度 | 显式 `acquire/release` | GC `Cleaner` |
|------|------------------------|--------------|
| 回收时机 | 精确——处理完立即 release | 不确定——等 GC，可能延迟数秒到分钟 |
| 高吞吐表现 | 堆外内存即时归还，占用稳定 | GC 跟不上，堆外堆积，可能 OOM direct memory |
| 跨线程持有 | 显式 retain，安全 | 无法表达"业务线程还要用" |
| 零拷贝透传 | `duplicate` 共享视图，靠引用计数托底 | 无对应机制 |

高吞吐下 `Cleaner` 的延迟回收是致命的——每秒数万请求，每个泄漏一点堆外，几秒就撞 `-XX:MaxDirectMemorySize` 上限。显式引用计数换来的即时回收，是 [原则 4](01-design-philosophy.md#原则-4--避免-stackoverflow) 拒绝"便利的容器托管内存"的根本理由。

### 8.3 响应 buf 的延迟分配 + 错误释放

```java
// NettyServerHttpResponse.java  延迟分配
protected ByteBuf getBuf() {
    if (buf == null) {
        buf = ctx.alloc().buffer(256);
    }
    return buf;
}

// NettyServerHttpResponse.java  flush 错误释放
} catch (Throwable t) {
    if (buf != null) {
        buf.release();
        buf = null;        // 置空，防 getBuf 返已释放
    }
}
```

响应 `buf` 延迟分配（无 body 响应不分配），错误时 release + 置空——置空是防 `getBuf()` 在 release 后再次调用拿到已释放的 ByteBuf。这个"release 即置空"的模式在 I/O 层多处出现，是 use-after-release 的防御纪律。

---

## 九、与 Spring MVC / WebFlux 的 I/O 层差异

| 维度 | 本框架 | Spring MVC（Tomcat） | Spring WebFlux（Netty） |
|------|--------|----------------------|------------------------|
| HTTP 解析 | Netty `HttpServerCodec` + 自研 `SupportMultipartAggregator` | Tomcat `Http11Processor`（自研解析器） | Netty `HttpServerCodec` + `HttpObjectAggregator` |
| headers 暴露 | `NettyHttpHeadersAdapter` 零拷贝委托 Netty | `MimeHeaders` 拷贝到 `Map` | `HttpServerOperations` 包装（6.1+ 用 `Netty4HeadersAdapter`） |
| body 读取 | 分级：≤4KB 复制堆 `byte[]`，>4KB `duplicate` 共享视图 | `CoyoteInputStream` 拷贝 | `DataBuffer`（响应式，需 subscribe） |
| body 生命周期 | 显式 `acquire/release` 引用计数 | 容器托管，请求结束自动回收 | 响应式 `DataBufferUtils.release` |
| 文件下载 | `DefaultFileRegion` sendfile 零拷贝 | Tomcat `FileBuffer` + sendfile | `DataBuffer` 包装，无原生 sendfile |
| 背压 | `WriteBufferWaterMark` + `BackpressureHandler` `false→true` 回调 | Tomcat `maxConnections` + 阻塞 | `Flux.onBackpressureBuffer/Drop`（响应式） |
| 协议支持 | HTTP/1.1 + h2c prior knowledge + TLS ALPN（h2/h1.1） | HTTP/1.1（HTTP/2 需 `Http2ProtocolHandler`） | HTTP/1.1 + HTTP/2（`Http2FrameCodecBuilder`） |
| 编程模型 | 同步（请求对象可跨线程持有） | 同步阻塞（请求对象线程绑定） | 强制响应式（`Mono`/`Flux`） |

**核心差异总结**：Tomcat 用"容器托管一切"换便利（body 生命周期不用业务管，但无法零拷贝跨线程）；WebFlux 用"强制响应式"换非阻塞（性能高但业务要换范式）。本框架用**显式引用计数**换"同步编程 + 零拷贝 + 跨线程持有"三全——代价是业务方（或框架的 `DispatcherHandler`）必须遵守 `acquire/release` 契约。这个契约被框架封装在 `DispatcherHandler` 的 `try/finally` 里（[04 篇](04-request-pipeline.md)），业务方法本身不感知引用计数——便利性几乎不打折，性能不打折。

---

## 十、小结：I/O 层的克制在哪里

回到引子的问题：字节流怎么变成 `FullHttpRequest`，又怎么写回 socket？

1. **Netty `HttpServerCodec` 解析字节流为 `HttpObject`，`SupportMultipartAggregator` 聚合为 `FullHttpRequest`**——HTTP 解析复用 Netty，不重造。
2. **`NettyHttpHandler` 把 `FullHttpRequest` 包装成 `NettyServerHttpRequest`**——headers 用 `NettyHttpHeadersAdapter` 零拷贝委托，body 分级（≤4KB 复制堆 / >4KB `duplicate` 共享视图），引用计数显式 `acquire/release`。
3. **`NettyServerHttpResponse` 四写出路径**——`flush`（默认 chunked 或 Content-Length）/ `writeStream`（chunked 流式）/ `writeBytes`（单次小已知 size）/ `writeFile`（`DefaultFileRegion` sendfile 零拷贝）。
4. **背压靠 `WriteBufferWaterMark` 触发 `channelWritabilityChanged`，`BackpressureHandler` 监听 `false→true` 转换回调业务续写**。
5. **内存管理靠显式引用计数**，三次 retain 配三次 release 归零，不依赖 GC `Cleaner` 延迟回收。

这一层的克制体现在：不重造 HTTP 解析器（复用 Netty）、不自建 headers adapter（因 Spring 版本/语义不契合才自研，但仍是零拷贝委托）、不依赖 GC 管堆外（显式引用计数）。每一处"不依赖便利"都换来一份性能或能力——headers 零拷贝省 O(n) 拷贝，`duplicate` 大 body 省堆外副本，`DefaultFileRegion` 省用户态拷贝，显式引用计数换来跨线程零拷贝透传。

I/O 层是 [01 篇](01-design-philosophy.md) 六大原则的集中展演场：原则 1（零匹配，启动期取 DispatcherHandler）+ 原则 2（零分配，延迟 buf + 单例异常）+ 原则 4（避免阻塞 + 显式引用计数，本篇主旋律）+ 原则 6（显式 SPI，`PipelineCustomizer` 两插入点 + 转发头默认不信任）。把这些克制规范累积起来，就是 I/O 层在 benchmark 里高出 Tomcat/WebFlux 一截的根因——不是某个单点技巧，而是一整套相互自洽的工程纪律。

---

> **下一篇**：[06 · 路由引擎与多级 RouterOptimizer 链](06-routing-engine.md)——往上走一层，看 `DispatcherHandler` 拿到 `NettyServerHttpRequest` 后，路由引擎如何用"启动时分桶 + 多级优化器链"让 90%+ 请求一次 `HashMap.get` 命中。
