package io.springperf.web.server;

import java.util.ArrayDeque;

import org.springframework.http.HttpStatus;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.http.NettyServerHttpRequest;
import io.springperf.web.http.NettyServerHttpResponse;
import io.springperf.web.http.ParameterLimitExceededException;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.WriteRespEventListener;
import lombok.extern.slf4j.Slf4j;

/**
 * Netty 入站处理器。职责仅限于：
 * <ol>
 * <li>解析 URI、校验 contextPath</li>
 * <li>创建 request/response 对象（msg.retain() + 构造 req，finally 中 req.release()）</li>
 * <li>委托 {@link HttpHandler#httpHandle} 执行实际处理</li>
 * <li>异常兜底（ResponseStatusException → 特定状态码，其余 → 500）</li>
 * </ol>
 * <p>
 * contextPath 为空字符串时不执行前缀校验。
 * </p>
 * <p>
 * 继承 {@link ChannelInboundHandlerAdapter} 手动管理消息释放， 配合 {@link ChannelHandler.Sharable} 在多 pipeline 中安全共享。
 * </p>
 */
@Slf4j
@ChannelHandler.Sharable
public class NettyHttpHandler extends ChannelInboundHandlerAdapter {

    private final WebContext webContext;
    private final String contextPath;
    private final HttpHandler handler;
    /** 是否启用响应 gzip 压缩：关闭时跳过每请求 UA 属性写入（压缩器未注入管线，写了也无人读）。 */
    private final boolean compressionEnabled;
    /** 响应写出层限制（swallow-size / 响应头大小），启动期预解析。 */
    private final ResponseLimitConfig responseLimitConfig;
    /** 业务线程池注册表（可为 null：未注册时按「非 EventLoop 默认模式」处理，即保持原有装配行为）。 */
    private final BizPoolRegistry bizPoolRegistry;
    private volatile boolean shuttingDown;

    public NettyHttpHandler(WebContext webContext, String contextPath, HttpHandler handler) {
        this(webContext, contextPath, handler, false);
    }

    public NettyHttpHandler(WebContext webContext, String contextPath, HttpHandler handler,
            boolean compressionEnabled) {
        this(webContext, contextPath, handler, compressionEnabled, ResponseLimitConfig.DEFAULT);
    }

    public NettyHttpHandler(WebContext webContext, String contextPath, HttpHandler handler, boolean compressionEnabled,
            ResponseLimitConfig responseLimitConfig) {
        this.webContext = webContext;
        this.contextPath = contextPath;
        this.handler = handler;
        this.compressionEnabled = compressionEnabled;
        this.responseLimitConfig = responseLimitConfig;
        // 构造期取一次注册表引用（不在此处读 isDefaultEventLoop：注册表可能在 handler 构造后才完成
        // Phase1 初始化；每请求读一次 volatile 布尔代价可忽略，且避免启动顺序耦合）。
        this.bizPoolRegistry = webContext == null ? null : webContext.getWebComponent(BizPoolRegistry.class);
    }

    /**
     * 是否需要在请求开始时装配响应超时（{@code server.http.timeout}）。
     * <p>
     * {@code pool.default-execute-mode=eventloop}（或未配置）时为 {@code false}：该模式下处理器在 EventLoop 上同步执行，而响应超时任务也调度在同一个
     * EventLoop（{@code ctx.executor().schedule}）， <b>处理器执行期间定时器不可能触发</b>；凡是能让它执行的时刻，响应要么已提交（被 {@code setCommitted}
     * 取消）、要么请求已交棒（由 {@link io.springperf.web.core.DispatcherHandler} 池分支补装配、 或异步开始时按
     * {@code spring.mvc.async.request-timeout} 重装配）。故该模式下请求开始的装配 是纯开销（每请求 1 次 schedule + 1 次 cancel + 1 个
     * ScheduledFutureTask）。
     * </p>
     */
    private boolean armTimeoutOnRequestStart() {
        BizPoolRegistry registry = this.bizPoolRegistry;
        return registry == null || !registry.isDefaultEventLoop();
    }

    /**
     * 标记服务器进入关闭状态。此后新到达的请求直接返回 503 Service Unavailable。
     */
    public void setShuttingDown() {
        this.shuttingDown = true;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /** 该连接是否有请求在途（读空闲超时据此豁免处理中的请求）。 */
    static boolean isRequestInFlight(io.netty.channel.Channel channel) {
        // 不创建持有者：空闲探测只读（不存在 ⟺ 本连接从未执行过请求 ⟺ 无在途请求）
        ChannelAttrs attrs = ChannelAttrs.ofIfPresent(channel);
        return attrs != null && attrs.pipeliningInFlight;
    }

    /**
     * 入队后是否应暂停读取：达到上限才暂停；上限 {@code <=0} 表示不限制。 独立成包级方法便于单测直接锁定边界（0 / 恰好等于 / 超一 / 负值）。
     */
    static boolean shouldPauseReads(int queued, int maxPipelined) {
        return maxPipelined > 0 && queued >= maxPipelined;
    }

    /**
     * 同连接排队上限（{@code server.http.max-pipelined-requests}）。
     * <p>
     * 只在**真的出现排队**时读取（普通请求零成本）；无 WebContext（单测替身）或取值非法时 回退默认上限——配置错误不得放大防护。
     * </p>
     */
    private int maxPipelinedRequests() {
        try {
            if (webContext == null || webContext.getProps() == null) {
                return PropertiesConstant.HTTP_MAX_PIPELINED_REQUESTS_DEFAULT;
            }
            String raw = webContext.getProps().get(PropertiesConstant.HTTP_MAX_PIPELINED_REQUESTS, null);
            if (raw == null || raw.trim().isEmpty()) {
                return PropertiesConstant.HTTP_MAX_PIPELINED_REQUESTS_DEFAULT;
            }
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return PropertiesConstant.HTTP_MAX_PIPELINED_REQUESTS_DEFAULT;
        }
    }

    /** 队列排空后恢复读取：仅当本处理器暂停过（不覆盖用户自行设置的 autoRead）。 */
    private static void resumeReadsIfPaused(ChannelHandlerContext ctx, ChannelAttrs attrs) {
        if (attrs.pipeliningReadPaused) {
            attrs.pipeliningReadPaused = false;
            ctx.channel().config().setAutoRead(true);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest)) {
            ctx.fireChannelRead(msg);
            return;
        }
        FullHttpRequest request = (FullHttpRequest) msg;
        // 本连接状态持有者：每请求取一次并沿调用链传递（见 ChannelAttrs：避免每处 attr(key) 线性扫描）
        ChannelAttrs attrs = ChannelAttrs.of(ctx.channel());
        // HTTP/1.1 pipelining / 同段多请求：前一个请求的响应尚未写完时，后续请求必须排队——
        // 否则默认业务池并发执行会让后到请求的响应先写出（若其带 Connection: close 还会
        // 在连接关闭时丢弃先前响应，客户端拿到错序/缺失响应）。响应完成后由 drain 顺序处理。
        if (attrs.pipeliningInFlight) {
            ArrayDeque<FullHttpRequest> queue = attrs.pipeliningPending;
            if (queue == null) {
                queue = new ArrayDeque<>(2);
                attrs.pipeliningPending = queue;
            }
            queue.add(request.retain());
            // 入站引用必须在此归还：本次提前 return 不会走到下方 finally 的 release，
            // 而队列持有的是上面 retain 出的**独立**引用（由 drainPipelined 消费后释放）。
            // 漏掉这一步 → 每个被排队的 pipelined 请求都会多留 1 个引用 → 聚合消息的
            // 复合 ByteBuf refCnt 永不归零（paranoid 实测：refCnt 3 进 2 出，GC 时残留 1）。
            ReferenceCountUtil.release(request);
            // 队列达上限即暂停读取（不关连接、不拒绝：pipelining 要求响应保序，无法只拒后到者）。
            // 未读字节留在 socket 缓冲 → 由 TCP 窗口形成背压；队列排空后由 drainPipelined 恢复。
            if (!attrs.pipeliningReadPaused && shouldPauseReads(queue.size(), maxPipelinedRequests())) {
                attrs.pipeliningReadPaused = true;
                ctx.channel().config().setAutoRead(false);
            }
            return;
        }
        attrs.pipeliningInFlight = true;
        try {
            handleRequest(ctx, request, attrs);
        } finally {
            // 入站 FullHttpRequest 的 ByteBuf 在此被真正释放到 refCnt 0（见 handleRequest 内引用计数说明）。
            // 注意：异步请求并不在此前 acquire 增加引用，本释放与异步生命周期解耦（详见 retain 处注释）。
            ReferenceCountUtil.release(request);
        }
    }

    /**
     * 注册「响应写入完成」回调，用于解除连接的处理中标记并按序处理已排队的 pipelined 请求。
     */
    private void registerPipeliningCompletion(ChannelHandlerContext ctx, WebServerHttpResponse resp,
            ChannelAttrs attrs) {
        resp.addWriteRespEventListener(new WriteRespEventListener() {
            @Override
            public void completeSuccessCallback() {
                drainPipelined(ctx, attrs);
            }

            @Override
            public void completeErrorCallback(Throwable throwable) {
                drainPipelined(ctx, attrs);
            }
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 连接关闭：释放尚未处理的排队请求缓冲（避免 ByteBuf 泄漏），并复位处理中标记
        // 不创建持有者：不存在 ⟺ 本连接从未执行过请求 ⟺ 无排队/在途状态可清理
        ChannelAttrs attrs = ChannelAttrs.ofIfPresent(ctx.channel());
        if (attrs == null) {
            super.channelInactive(ctx);
            return;
        }
        ArrayDeque<FullHttpRequest> queue = attrs.pipeliningPending;
        attrs.pipeliningPending = null;
        if (queue != null) {
            for (FullHttpRequest pending = queue.poll(); pending != null; pending = queue.poll()) {
                ReferenceCountUtil.release(pending);
            }
        }
        // 在途响应：连接已断，响应不会再被写出 → 释放其未提交 buf（release 幂等，已提交时为空操作）
        WebServerHttpResponse inFlight = attrs.inFlightResponse;
        attrs.inFlightResponse = null;
        if (inFlight != null) {
            inFlight.release();
        }
        // 在途请求的异步持有者：连接已断，既不会写 LastHttpContent、也可能没有 chunk 写失败
        // （空闲 SSE / 未完成异步被断连）→ 在此退场，否则入站 buf 要等 GC 才释放
        NettyServerHttpRequest inFlightReq = attrs.inFlightRequest;
        attrs.inFlightRequest = null;
        if (inFlightReq != null) {
            io.springperf.web.core.async.PerfAsyncWebRequest async = inFlightReq.getRequestContext()
                    .getAttribute(io.springperf.web.core.async.AsyncSupportUtils.WEB_ASYNC_REQUEST_ATTRIBUTE);
            if (async != null) {
                async.releaseOnConnectionClose();
            }
        }
        attrs.pipeliningInFlight = false;
        super.channelInactive(ctx);
    }

    /** 顺序处理同连接排队请求；队列空则解除处理中标记。 */
    private void drainPipelined(ChannelHandlerContext ctx, ChannelAttrs attrs) {
        // 请求登记：本方法由写终结回调触发，异步持有者此时已通过终结回调退场（releaseRequestOnce），
        // 故可清除，避免 keep-alive 连接长期持有请求对象（含已物化 body）。
        attrs.inFlightRequest = null;
        // 响应登记【不清除】：写完成 ≠ 该响应不再被使用 —— 迟到异常/迟到结果仍可能在写完成之后
        // 才去写错误体（paranoid 实测：已提交的 SSE + 迟到业务异常 → sendError → 此时才
        // getBuf() 分配响应 buf）。若登记已失效，channelInactive 就够不到这块 buf → 池化泄漏。
        // 保留到连接关闭，由 channelInactive 统一兜底释放（release 幂等，重复调用无害）。
        ArrayDeque<FullHttpRequest> queue = attrs.pipeliningPending;
        if (queue == null || queue.isEmpty()) {
            attrs.pipeliningInFlight = false;
            resumeReadsIfPaused(ctx, attrs);
            return;
        }
        FullHttpRequest next = queue.poll();
        try {
            handleRequest(ctx, next, attrs);
        } finally {
            ReferenceCountUtil.release(next);
        }
    }

    private void handleRequest(ChannelHandlerContext ctxNetty, FullHttpRequest msg, ChannelAttrs attrs) {
        // 关闭中：拒绝新请求
        if (shuttingDown) {
            NettyServerHttpResponse resp = new NettyServerHttpResponse(webContext, ctxNetty, false, responseLimitConfig,
                    0L);
            resp.setRequestAcceptHeader(msg.headers().get(HttpHeaderNames.ACCEPT));
            registerPipeliningCompletion(ctxNetty, resp, attrs);
            try {
                resp.sendError(HttpStatus.SERVICE_UNAVAILABLE, "Server is shutting down");
            } catch (Exception ignored) {
                log.debug("sendError 503 failed", ignored);
            }
            return;
        }

        NettyServerHttpResponse resp = new NettyServerHttpResponse(webContext, ctxNetty, HttpUtil.isKeepAlive(msg),
                responseLimitConfig, msg.content().readableBytes());
        // 错误体内容协商：显式 JSON 客户端应得 JSON 错误体（见 ErrorPageRenderer.build）
        resp.setRequestAcceptHeader(msg.headers().get(HttpHeaderNames.ACCEPT));
        // pipelining 串行化：响应写完后再处理同连接排队请求
        registerPipeliningCompletion(ctxNetty, resp, attrs);
        // 记录在途响应：连接在写出前关闭时由 channelInactive 兜底释放其未提交 buf
        attrs.inFlightResponse = resp;
        // HEAD 请求：标记响应以抑制 body（RFC 7231 §4.3.2，与 Spring MVC 行为一致）
        if (msg.method() == io.netty.handler.codec.http.HttpMethod.HEAD) {
            resp.markAsHeadRequest();
        }
        // HTTP/1.0 请求：渐进式输出不能发 chunked 帧（1.0 无此分帧），响应侧退化为 close-delimited
        if (msg.protocolVersion() == HttpVersion.HTTP_1_0) {
            resp.markHttp10();
        }
        try {
            // 1. 解析 URI，提取路径（去掉 query string）
            String rawUri = msg.uri();
            String requestPath = rawUri.contains("?") ? rawUri.substring(0, rawUri.indexOf('?')) : rawUri;

            // 2. contextPath 校验：若配置了 contextPath，请求路径必须以 contextPath 开头
            String resolvedPath;
            if (contextPath.isEmpty()) {
                resolvedPath = requestPath;
            } else {
                if (requestPath.equals(contextPath)) {
                    resolvedPath = "/";
                } else if (requestPath.startsWith(contextPath + "/")) {
                    resolvedPath = requestPath.substring(contextPath.length());
                } else {
                    resp.sendError(HttpStatus.NOT_FOUND, "Not Found");
                    return;
                }
            }

            // 2.5 矩阵参数（path parameter）剥离：/foo;jsessionid=ABC → /foo，仅用于路由匹配。
            // 语义对齐 Spring 的 UrlPathHelper.removeSemicolonContent=true：路径段内 ";" 之后的内容
            // 不参与映射（否则 URL 重写后的 ;jsessionid= 会让路径匹配失败，返回 404 —— 服务端
            // 自己写出的 URL 自己打不开）。原始 URI 原样保留在 uriStr 中，servlet 层据此回读
            // 会话 id（Servlet 规范 §7.1 URL 重写），getRequestURI() 仍返回未剥离的 URI。
            resolvedPath = stripPathParams(resolvedPath);

            // 3. 用预解析的 path 创建请求，跳过 BaseWebServerHttpRequest 中的 contextPath 校验
            //
            // == 引用计数与异步生命周期的设计说明（#5，已知取舍，未改动逻辑） ==
            // 入站 FullHttpRequest 的 ByteBuf 引用计数轨迹：
            // (a) 进入本 handler 前管线已用 HttpObjectAggregator/SupportMultipartAggregator
            // 把整个 body 聚合成一个 FullHttpRequest，故 handler 跑起来时完整 body 已在内存；
            // (b) 下面 msg.retain() → refCnt 1→2（pipeline 占 1，此处多持 1）；
            // (c) 同步末尾 req.release() → refCnt 2→1，并级联 response.release()；
            // (d) channelRead.finally 的 ReferenceCountUtil.release(request) → refCnt 1→0，
            // 这是把入站 buf 真正打 0 的唯一位置（不在 req.release() 内）。
            //
            // 【结论已更新】异步路径现由 PerfAsyncWebRequest 在 startAsync() 时 acquire、写终结时
            // release（见 AsyncSupportUtils 设计说明）。以下分析仅保留以说明「已物化 body / ctx 与
            // 入站 buf 解耦」这一层面，其「不必 acquire」的结论已不再适用：
            // 1) 请求体在 handler 运行前已完整聚合，NettyServerHttpRequest.getBodyBytes() 在同步阶段
            // 已物化为 byte[]（小 body）或 duplicate() 视图（大 body）；异步业务读的是已物化的
            // body/largeBodyBuf，不再触碰原始 channel buf，故 buf 在 (d) 被释放到 0 对异步无影响。
            // 2) 响应写出全部走 ctx.writeAndFlush(...)，ctx 归 channel 所有，与 FullHttpRequest 的
            // refCnt 完全解耦——即使请求已彻底释放，异步阶段照样能把响应写到线上。
            // 3) 同步末尾那次 response.release() 释放的是"尚未 commit 的临时 buf"，异步后续
            // getBody()→getBuf() 会懒重分配新 buf 再 flush 提交，所以无 UAF、无泄漏。
            //
            // 剩余边缘（纯理论，框架常规异步路径已被覆盖，故暂不修）：
            // - 若 startAsync 后用 getBody() 缓冲、既未 flush 也未走结果序列化就 complete，
            // 重分配的 buf 不会被释放（级联已在同步末尾跑过）→ 单个 ByteBuf 泄漏。
            // 但 DeferredResult/Callable 结果会被 dispatcher 序列化并 flush，StreamEmitter 也显式
            // flush，实际不会触发。
            // - （已实施）异步开始时 req.acquire()、写终结时 req.release()：即上面那条不变式，
            // 由 PerfAsyncWebRequest 持有与归还，取代早期「不 acquire」的取舍。
            msg.retain();
            NettyServerHttpRequest req = null;
            try {
                req = new NettyServerHttpRequest(webContext, ctxNetty, msg, resolvedPath);
                // 绑定响应：未提交 buf 的生命周期随请求 release() 级联释放（同步/异步统一，无需异步特例守卫）
                req.setResponse(resp);
                attrs.inFlightRequest = req;
                if (armTimeoutOnRequestStart()) {
                    resp.setTimeout();
                }
                // 将请求 User-Agent 带入响应侧：压缩器据此做 excluded-user-agents 排除（响应头不含 UA）。
                // 仅在启用压缩时写入——关闭时管线无压缩器，写了也永不被读，纯属浪费。
                if (compressionEnabled) {
                    attrs.compressionReqUa = msg.headers().get(HttpHeaderNames.USER_AGENT);
                }
                // 4. 委托给实际处理逻辑
                handler.httpHandle(req, resp);
                // 兜底装配：同步段结束仍未提交 ⇒ 请求已进入异步/流式等待（此处不枚举具体类型），
                // 此时才需要响应超时。已在异步开始时装配（PerfAsyncWebRequest.scheduleTimeoutIfNeeded，
                // 按 spring.mvc.async.request-timeout）或池分支补装配过时为 no-op；已提交的同步请求
                // 不装配（EventLoop 模式下定时器对它们本就不可能生效，见 armTimeoutOnRequestStart）。
                if (!resp.isCommitted() && !resp.hasTimeoutArmed()) {
                    resp.setTimeout();
                }
            } finally {
                if (req != null) {
                    req.release();
                } else {
                    // 构造 req / setTimeout 阶段抛异常：释放 retain 的引用，避免 ByteBuf 泄漏
                    ReferenceCountUtil.release(msg);
                }
            }
        } catch (Throwable e) {
            // 参数数超限（hash DoS 防护）转 400；其余 Throwable 转 500
            if (e instanceof ParameterLimitExceededException
                    || (e.getCause() instanceof ParameterLimitExceededException)) {
                try {
                    resp.sendError(HttpStatus.BAD_REQUEST, e.getMessage());
                } catch (Exception ignored) {
                    log.debug("sendError 400 failed", ignored);
                }
                return;
            }
            log.error("NH EXCEPTION", e);
            try {
                resp.sendError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
            } catch (Exception ignored) {
                log.debug("sendError 500 failed", ignored);
            }
        }
    }

    /**
     * 剥离路径中的矩阵参数（path parameter）内容：每个路径段内第一个 {@code ';'} 之后的字符 不参与路由匹配（{@code /foo;a=b/c;jsessionid=X} →
     * {@code /foo/c}）。
     * <p>
     * 对齐 Spring {@code UrlPathHelper.removeSemicolonContent=true}。热路径零分配：无 {@code ';'} 时直接返回原串（绝大多数请求）。
     * </p>
     */
    static String stripPathParams(String path) {
        if (path == null || path.indexOf(';') < 0) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path.length());
        int segmentStart = 0;
        while (true) {
            int semi = path.indexOf(';', segmentStart);
            if (semi < 0) {
                sb.append(path, segmentStart, path.length());
                break;
            }
            sb.append(path, segmentStart, semi);
            int slash = path.indexOf('/', semi);
            if (slash < 0) {
                // 末段带参数：丢弃该段 ';' 之后的内容
                break;
            }
            segmentStart = slash;
        }
        return sb.toString();
    }
}
