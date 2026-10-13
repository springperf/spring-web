package io.springperf.web.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.*;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.server.ResponseLimitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.io.*;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * ServerHttpResponse 实现
 */
@Slf4j
public class NettyServerHttpResponse extends BaseWebServerHttpResponse {

    public static final ChannelFutureListener LOG_ERROR_ON_FAILURE = (future) -> {
        if (future.isSuccess()) {
            return;
        }
        Throwable cause = future.cause();
        if (cause instanceof ClosedChannelException) {
            return;
        }
        log.warn("write failed", cause);
    };

    protected final ChannelHandlerContext ctx;

    /** 实际 header 存储：框架 headers 视图与 Netty 响应对象共享，commit 时零拷贝 */
    protected final io.netty.handler.codec.http.HttpHeaders nettyHeaders;

    protected volatile ByteBuf buf;

    /** HEAD 请求标志：flush/写 body 时抑制响应体（只写 headers），对齐 RFC 7231 §4.3.2 */
    private volatile boolean headRequest;

    /** 响应写出层限制配置（swallow-size / 响应头大小），启动期预解析。 */
    private final ResponseLimitConfig responseLimitConfig;

    /** 当前请求 body 字节数：用于错误响应后按 swallow-size 决定保活 / 关闭连接。 */
    private final long requestContentLength;

    /** HTTP/1.0 请求标志：渐进式输出不能用 chunked（1.0 无此分帧），退化为 close-delimited。 */
    private volatile boolean http10;

    public NettyServerHttpResponse(WebContext webContext, ChannelHandlerContext ctx, boolean keepAlive) {
        this(webContext, ctx, keepAlive, ResponseLimitConfig.DEFAULT, 0L);
    }

    public NettyServerHttpResponse(WebContext webContext, ChannelHandlerContext ctx, boolean keepAlive,
                                   ResponseLimitConfig responseLimitConfig, long requestContentLength) {
        this(webContext, ctx, keepAlive, responseLimitConfig, requestContentLength, new DefaultHttpHeaders(false));
    }

    private NettyServerHttpResponse(WebContext webContext, ChannelHandlerContext ctx, boolean keepAlive,
                                    ResponseLimitConfig responseLimitConfig, long requestContentLength,
                                    io.netty.handler.codec.http.HttpHeaders nettyHeaders) {
        super(webContext, keepAlive, new WebHttpHeaders(new NettyHttpHeadersAdapter(nettyHeaders, true)));
        this.ctx = ctx;
        this.nettyHeaders = nettyHeaders;
        this.responseLimitConfig = responseLimitConfig;
        this.requestContentLength = requestContentLength;
    }

    /** 标记本响应对应 HEAD 请求，后续 flush/write* 时抑制响应体。 */
    public void markAsHeadRequest() {
        this.headRequest = true;
    }

    /**
     * 是否为 HEAD 响应。
     *
     * <p>供流式发送器判断「本响应的终止语义已由『仅响应头』的完整响应承担」——HEAD 无响应体，
     * 不得再补写终止块（见 {@code AbstractNettyStreamSender#onAllDataWritten}）。</p>
     */
    public boolean isHeadRequest() {
        return headRequest;
    }

    /** 标记本响应对应 HTTP/1.0 请求（渐进式输出退化为 close-delimited，禁用 chunked）。 */
    public void markHttp10() {
        this.http10 = true;
    }

    protected boolean isHttp10() {
        return http10;
    }

    /**
     * 提交前按 {@link ResponseLimitConfig} 收口（仅在 {@code setCommitted()} 成功、即首次提交时调用）：
     * <ol>
     * <li>swallow-size：错误响应（4xx/5xx）且当前保活时，若请求 body 超过上限，降级为关闭连接，
     * 避免在保活通道上复用仍带超大 body 的连接（聚合模型下 body 已读完，关闭即放弃复用）。</li>
     * </ol>
     * 响应头大小限制在 {@link #responseHeadersExceedLimit()} 单独判定，超限时由
     * {@link #writeHeaderTooLarge()} 写出最小 500。
     */
    private void applySwallowLimit() {
        HttpStatus current = getStatus();
        if (current != null && current.value() >= 400 && keepAlive
                && responseLimitConfig.shouldCloseAfterError(requestContentLength)) {
            this.keepAlive = false;
        }
    }

    /** 当前响应头总字节是否超过 {@code max-http-response-header-size}（{@code <=0} 表示不限制）。 */
    private boolean responseHeadersExceedLimit() {
        int limit = responseLimitConfig.getMaxResponseHeaderSize();
        if (limit <= 0) {
            return false;
        }
        int total = 0;
        // 直接遍历条目：原实现按名字调 get(CharSequence) 会走 Netty 的通用慢路径
        // （HeadersUtils.getAsString，JFR 采样 13 样本/3205）。同名多值只计首个（与 get(name)
        // 语义一致）——DefaultHeaders 迭代按名字成组连续产出，用「与上一个名字相同则跳过」即可，
        // 无需额外集合。本方法在每个响应写出路径上都会被调用，属热路径。
        String lastName = null;
        for (java.util.Map.Entry<String, String> entry : nettyHeaders) {
            String name = entry.getKey();
            if (name.equals(lastName)) {
                continue;
            }
            lastName = name;
            String value = entry.getValue();
            total += name.length() + (value != null ? value.length() : 0) + 4;
        }
        return total > limit;
    }

    /**
     * 响应头超限制：丢弃已缓冲 body，写出最小 500 响应（Content-Length:0），按保活状态决定是否关闭连接。
     * 用于防止业务误写海量响应头（如超大 Cookie）污染连接。
     */
    private void writeHeaderTooLarge() {
        HttpResponse error = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                Unpooled.EMPTY_BUFFER);
        error.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        error.headers().set(HttpHeaderNames.CONNECTION,
                keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
        ChannelFuture f = ctx.writeAndFlush(error);
        addRespEventListener(f, true);
        if (!keepAlive) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
        ByteBuf b = this.buf;
        if (b != null && b.refCnt() > 0) {
            b.release();
        }
        this.buf = null;
    }

    /**
     * 兜底释放未被 flush / sendError 消费的响应体 ByteBuf。
     *
     * <p>正常路径下 buf 已随出站消息转移给 Netty（flush 成功即 {@code this.buf = null}），字段仍持有
     * 就说明这块缓冲从未转为出站消息，必须在此释放，否则池化 buf 泄漏。</p>
     */
    public void release() {
        ByteBuf b = this.buf;
        if (b != null) {
            this.buf = null;
            if (b.refCnt() > 0) {
                b.release();
            }
        }
    }

    public ByteBuf getBuf() {
        if (buf == null) {
            if (exchangeFinished()) {
                // 交换已结束（流式已终止 / 连接已断）仍在取缓冲写内容 = 调用方逻辑错误：
                // 这块内容不可能送达，用池化分配只会造成 ByteBuf 泄漏（paranoid 实测路径：
                // 已提交的 SSE + 迟到业务异常 → sendError → writeDataAndFlush → getBuf）。
                // 处理：响亮记 ERROR（让调用方问题显形），并返回【非池化】丢弃缓冲 ——
                // 不泄漏池内存、也不在异常处理路径上二次抛异常。
                log.error(
                        "write-after-exchange-finished ignored: status={}, streaming={}, streamCompleted={}, channelActive={}",
                        status.value(), streaming.get(), streamCompleted.get(), channelActive());
                buf = Unpooled.buffer(256);
            } else {
                buf = ctx.alloc().buffer(256);
            }
        }
        return buf;
    }

    /** 交换是否已结束：流式已终止，或连接已断开（两者均单调，不存在 check-then-act 竞态）。 */
    private boolean exchangeFinished() {
        // 判据缺失（单元测试替身 ctx 无 channel()）时按「未结束」处理：本守卫只用于抑制
        // 「肯定写不出去」的分配，不应因判据不可得而抛 NPE 改变原行为。
        return streamCompleted.get() || !channelActive();
    }

    /** 当前通道是否仍可写出；通道不可得（替身/已脱离管线）时按可用处理，避免误抑制。 */
    private boolean channelActive() {
        io.netty.channel.Channel ch = ctx == null ? null : ctx.channel();
        return ch == null || ch.isActive();
    }

    @Override
    public OutputStream getBody() {
        return new ByteBufOutputStream(getBuf());
    }

    /**
     * 清空已缓冲的响应体（resetBuffer 的真实实现）。
     * 基类实现只重置从未被写入过的 {@link ByteArrayOutputStream body}，对 Netty 响应是空操作。
     * 此处直接清空底层 {@link ByteBuf}，使异常路径能丢弃序列化中途写入的部分内容。
     */
    @Override
    public boolean resetBuffer() {
        ByteBuf current = this.buf;
        if (current == null) {
            return false;
        }
        boolean haveData = current.readableBytes() > 0;
        current.clear();
        return haveData;
    }

    @Override
    public void flush(boolean chunked) throws IOException {
        // 必须先执行提交前回调（把 servlet Writer 的编码缓冲刷入响应体）再捕获缓冲引用，
        // 否则本次刷出的内容会写进「捕获之后」才存在的新缓冲而被漏掉。
        runBeforeCommitOnce();
        if (chunked) {
            // flush(true) 语义是「渐进式刷出」：必须路由到 flushChunked()——
            // 一次性提交会立即终止消息，后续 flush 全部落空（Servlet 的 Writer.flush 即走此路径）。
            flushChunked();
            return;
        }
        // 先摘空字段再交出 buf：「字段不再持有」与「所有权已转移给 writeAndFlush/出站消息」必须同时成立。
        // 若在写出**之后**才置空，则写出完成回调（addRespEventListener → release()）可能在
        // writeAndFlush 返回前就被同步触发（EmbeddedChannel / 立即完成的 promise），此时字段仍指向
        // 已转交给出站消息的同一块 buf → release() 会造成双重释放（实测：
        // NettyHttpHandlerErrorPathTest#requestConstructionFailure_releasesRetainedBuffer 抛
        // IllegalReferenceCountException）。
        ByteBuf buf = takePendingBuf();
        try {
            // HEAD 抑制逻辑由 writeAndFlush 内部处理（保留 Content-Length，丢弃 body）
            writeAndFlush(buf, null, null, chunked);
        } catch (Exception e) {
            // make sure to release buffer on error
            if (buf != null && buf.refCnt() > 0) {
                buf.release();
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * chunked 渐进式写出（语义对齐 Tomcat 的 {@code flushBuffer()} / {@code Writer.flush()}）：
     * <ul>
     * <li>首次调用：提交响应头（{@code Transfer-Encoding: chunked}），并把已缓冲内容作为首个内容帧发出；</li>
     * <li>后续调用：把新增缓冲作为独立内容帧发出（客户端可即时收到，无需等整个响应结束）；</li>
     * <li>HEAD 请求退化为一次性「仅响应头」提交（HEAD 语义上无 body）。</li>
     * </ul>
     */
    @Override
    public void flushChunked() throws IOException {
        // 同 flush()：先让 Writer 缓冲落入响应体，再决定本次要发出的内容帧
        runBeforeCommitOnce();
        if (streaming.get()) {
            writePendingContentFrame(false);
            return;
        }
        if (!setCommitted()) {
            // 已一次性提交或流已终止：渐进写入不可再插入 → 本次待发缓冲永远不会写出，
            // 必须就地释放，否则池化 buf 泄漏（已提交响应上的错误体写入即此路径。
            releasePendingBuf(takePendingBuf());
            log.warn("flushChunked ignored: response already committed (status={})", status.value());
            return;
        }
        if (headRequest) {
            // HEAD：只发响应头（保留真实 Content-Length 元数据），不进入流式状态
            releasePendingBuf(takePendingBuf());
            addRespEventListener(ctx.writeAndFlush(initHttpResponse(null, null, null, false)), true);
            return;
        }
        streaming.set(true);
        if (http10) {
            // HTTP/1.0：chunked 分帧在 1.0 中不存在，客户端会把分块标记当 body 收下。
            // 对齐 Tomcat：退化为 close-delimited——响应头不带 Content-Length/Transfer-Encoding，
            // 连接关闭即 body 结束，故强制 Connection: close。
            // 必须用非 Full 的 DefaultHttpResponse：FullHttpResponse 自带 LastHttpContent，
            // 会让响应在首个内容帧之前就"完结"（后续内容帧被编码器以 state=INIT 拒绝）。
            keepAlive = false;
            HttpResponse headers = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(this.status.value()), nettyHeaders);
            headers.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ByteBuf pending = takePendingBuf();
            if (pending != null && pending.isReadable()) {
                ctx.write(headers);
                addRespEventListener(ctx.writeAndFlush(new DefaultHttpContent(pending)), false);
            } else {
                releasePendingBuf(pending);
                addRespEventListener(ctx.writeAndFlush(headers), false);
            }
            return;
        }
        // 非 Full 首帧：body 由后续 HttpContent 帧续写（FullHttpResponse 自带 LastHttpContent，会提前结束响应）
        HttpResponse headers = initHttpResponse(null, null, null, true);
        ByteBuf pending = takePendingBuf();
        if (pending != null && pending.isReadable()) {
            ctx.write(headers);
            addRespEventListener(ctx.writeAndFlush(new DefaultHttpContent(pending)), false);
        } else {
            releasePendingBuf(pending);
            addRespEventListener(ctx.writeAndFlush(headers), false);
        }
    }

    /**
     * 终止 chunked 流（幂等）：写出残留内容帧与 {@code LastHttpContent}。
     * 非流式响应（一次性提交或从未提交）为空操作——一次性路径自带 Content-Length，无需终止块。
     */
    @Override
    public void endStream() {
        if (!streaming.get() || !streamCompleted.compareAndSet(false, true)) {
            return;
        }
        try {
            writePendingContentFrame(true);
        } catch (Exception e) {
            log.warn("endStream failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 写出待发缓冲内容帧。
     *
     * @param terminate 是否同时写出终止块 {@code LastHttpContent}
     */
    private void writePendingContentFrame(boolean terminate) {
        ByteBuf pending = takePendingBuf();
        if (pending != null && pending.isReadable() && !headRequest) {
            if (terminate) {
                ctx.write(new DefaultHttpContent(pending));
            } else {
                addRespEventListener(ctx.writeAndFlush(new DefaultHttpContent(pending)), false);
                return;
            }
        } else {
            releasePendingBuf(pending);
        }
        if (terminate) {
            ChannelFuture last = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            addRespEventListener(last, true);
            if (!keepAlive) {
                last.addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    /** 取走待发缓冲：所有权随之转移给调用方（内容帧写出/释放由调用方负责）。 */
    private ByteBuf takePendingBuf() {
        ByteBuf current = this.buf;
        this.buf = null;
        return current;
    }

    private void releasePendingBuf(ByteBuf pending) {
        if (pending != null && pending.refCnt() > 0) {
            pending.release();
        }
    }

    private HttpResponse initHttpResponse(ByteBuf buf, String contentType, HttpStatus statusCode, boolean chunked) {
        setStatusCode(statusCode);
        // validate=false 跳过 Netty 对响应头 name/value 的逐字符校验（HttpUtil.validateToken 热点）。
        // 响应头由框架/业务内部构造，非用户输入直达，CRLF 注入面可控。
        HttpResponse response;
        if (buf != null) {
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(this.status.value()), buf, nettyHeaders, EmptyHttpHeaders.INSTANCE);
        } else if (chunked) {
            response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(this.status.value()), nettyHeaders);
        } else {
            // 无 body 分支：DefaultFullHttpResponse 无 (version,status,headers,trailingHeaders) 构造器，
            // 用空 content 补位；该分支随后设 Content-Length: 0，语义与原 null content 一致。
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(this.status.value()), Unpooled.EMPTY_BUFFER, nettyHeaders, EmptyHttpHeaders.INSTANCE);
        }
        // 零拷贝：nettyHeaders 即框架 headers 视图底层存储，commit 前所有框架写入已落到位，无需逐条拷贝。
        // 注意：直接 response.headers().set 写入不走 WebHttpHeaders.setContentType，不会清 Content-Type 缓存；
        // 但此处 contentType 参数仅 writeStream/writeFile 传入（octet-stream，已提交），commit 后无人再读 getContentType()。
        if (contentType != null) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
        if (buf != null) {
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
        } else if (chunked) {
            HttpUtil.setTransferEncodingChunked(response, true);
        } else {
            // 无 body 分支：已设置 Content-Length（如 HEAD 保留 body 长度、资源 HEAD 元数据）
            // 则保留；否则置 0。
            if (!response.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            }
        }
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }
        return response;
    }


    protected void writeAndFlush(ByteBuf buf, String contentType, HttpStatus statusCode, boolean chunked) {
        if (!setCommitted()) {
            // 已一次性提交或流已终止：本次 buf 永远不会写出，本方法对其「消费即拥有」，因此拒绝时必须就地释放。
            // 调用方（flush(boolean)）已用 takePendingBuf() 摘空字段把所有权交出来 —— 不释放则无人在持有，
            // 该池化 buf 从此不可达（paranoid 实测路径：已提交的 SSE 上 sendError → writeDataAndFlush
            // → getBody() 新分配 buf → 本分支拒写）。
            // 与 flushChunked() 的同名分支保持一致的「释放 + 告警」处理。
            if (buf != null && buf.refCnt() > 0) {
                buf.release();
            }
            log.warn("writeAndFlush ignored: response already committed (status={})", status.value());
            return;
        }
        applySwallowLimit();
        if (responseHeadersExceedLimit()) {
            // 本 buf 的所有权已由调用方（flush(boolean)）takePendingBuf() 摘出来交给本方法，
            // 而 writeHeaderTooLarge() 只释放 this.buf（此处字段已被摘空 → 为 null），
            // 故必须在此就地释放：不释放则这块池化 buf 从此不可达
            // （paranoid 实测：ResponseHeaderLimitE2eTest#oversizedResponseHeaders_degradeToMinimal500
            // 的 /e2e-hdr/big 响应体「should-not-be-written」稳定泄漏）。
            // 与上方「已提交拒写」分支、以及 HEAD 抑制分支保持一致的「消费即拥有、拒绝即释放」。
            releasePendingBuf(buf);
            writeHeaderTooLarge();
            return;
        }
        // HEAD：抑制响应体——丢弃已写 body，但 Content-Length 保留真实 body 长度（RFC 7231 §4.3.2）
        if (headRequest && buf != null) {
            int bodyLength = buf.readableBytes();
            if (!nettyHeaders.contains(HttpHeaderNames.CONTENT_LENGTH)) {
                nettyHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH, bodyLength);
            }
            buf.release();
            buf = null;
        }
        HttpResponse response = initHttpResponse(buf, contentType, statusCode, chunked);
        ChannelFuture f = ctx.writeAndFlush(response);
        addRespEventListener(f, response instanceof FullHttpResponse);
        if (!keepAlive) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    // ---------- streaming: InputStream -> chunked/content-length frames（读取卸载到业务池） ----------
    public void writeStream(InputStream input) {
        writeStream(input, -1L);
    }

    /**
     * 流式写出：{@code contentLength < 0} 时用 chunked 帧，否则用 {@code Content-Length} 帧。
     *
     * <p>长度已知时必须声明长度：chunked 不携带总长度（客户端无法显示进度、也无法预知大小），且 HTTP 消息
     * 同时带 {@code Content-Length} 与 {@code Transfer-Encoding} 违反 RFC 7230 §3.3.1（Netty 的
     * setTransferEncodingChunked 会移除 Content-Length，使调用方预设的长度静默失效）。</p>
     */
    @Override
    public void writeStream(InputStream input, long contentLength) {
        if (!setCommitted()) {
            return;
        }
        applySwallowLimit();
        if (responseHeadersExceedLimit()) {
            writeHeaderTooLarge();
            return;
        }
        // InputStream 无法推断 MIME：调用方若已预先 setContentType 则尊重之，否则回退 octet-stream
        String streamContentType = nettyHeaders.get(HttpHeaderNames.CONTENT_TYPE);
        if (streamContentType == null) {
            streamContentType = "application/octet-stream";
        }
        HttpResponse response;
        if (contentLength < 0) {
            response = initHttpResponse(null, streamContentType, null, true);
            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        } else {
            response = initContentLengthStreamResponse(streamContentType, contentLength);
        }
        try {
            if (headRequest) {
                // HEAD：不发 body——发送 headers 后立即发 LastHttpContent 收尾（Content-Length 保留真实长度）
                addRespEventListener(ctx.writeAndFlush(response), false);
                ChannelFuture lastFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                addRespEventListener(lastFuture, true);
                if (!keepAlive) {
                    lastFuture.addListener(ChannelFutureListener.CLOSE);
                }
                return;
            }
            addRespEventListener(ctx.writeAndFlush(response), false);
            offloadStreamCopy(input);
        } catch (Exception ex) {
            closeQuietly(input);
            throw new RuntimeException(ex);
        }
    }

    /**
     * 长度已知的流式响应首帧：必须是**非 Full** 的 {@link DefaultHttpResponse}——body 由后续 {@code HttpContent} 帧续写。
     * 若复用 {@link #initHttpResponse}(chunked=false)，得到的是 {@code DefaultFullHttpResponse}（自带 LastHttpContent，
     * 语义上响应已结束），其后继续写 body 帧属非法，客户端会判为协议错误并等到超时。
     */
    private HttpResponse initContentLengthStreamResponse(String contentType, long contentLength) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(this.status.value()), nettyHeaders);
        if (contentType != null) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, Long.valueOf(contentLength));
        response.headers().set(HttpHeaderNames.CONNECTION,
                keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
        return response;
    }

    /**
     * 流式写出：把「读取」卸载到业务线程池，worker 线程读后按 8KB 分块 {@code writeAndFlush}。
     *
     * <p><b>为什么不能沿用 {@code HttpChunkedInput} + 管道内 {@code ChunkedWriteHandler}</b>：那条路
     * 由 Netty 在 <b>EventLoop</b> 上回调 {@code ChunkedStream.readChunk()} → {@code InputStream.read()}，
     * 慢速源（DB 游标 / 网络流 / 大文件冷读）会把 I/O 线程阻塞在队头，同一 EventLoop 上的其它连接全部停摆。
     * {@code ChunkedWriteHandler} 只解决「出站缓冲无界增长」（背压），<b>不解决「读阻塞 I/O 线程」</b>，
     * 故此处改为显式读卸载：写出仍由 Netty 在 EventLoop 串行执行（写出本身廉价），仅「读」离开 I/O 线程；
     * 背压由 {@link #waitWritable()} 承担（与原 {@code ChunkedWriteHandler} 的 isWritable 控制等价）。</p>
     */
    private void offloadStreamCopy(InputStream input) {
        try {
            resolveStreamPool().submit(() -> streamCopy(input));
        } catch (Exception ex) {
            // ensure input closed on error（池已关闭等提交失败路径）
            closeQuietly(input);
            throw new RuntimeException(ex);
        }
    }

    /**
     * 在业务线程池内执行：读取 InputStream 并分块写入 channel（含背压控制）。
     * 仅「读取」在 worker 线程，写出仍由 Netty 在 EventLoop 串行执行，故 EventLoop 不再被慢速源阻塞。
     */
    private void streamCopy(InputStream input) {
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) != -1) {
                if (!ctx.channel().isActive()) {
                    break;
                }
                waitWritable();
                ByteBuf chunk = ctx.alloc().buffer(n, n);
                chunk.writeBytes(buf, 0, n);
                ctx.writeAndFlush(new DefaultHttpContent(chunk));
            }
            if (ctx.channel().isActive()) {
                ChannelFuture lastFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                addRespEventListener(lastFuture, true);
                if (!keepAlive) {
                    lastFuture.addListener(ChannelFutureListener.CLOSE);
                }
            }
        } catch (Exception ex) {
            log.warn("writeStream copy failed", ex);
            // 读取/写出失败：关闭连接使客户端感知异常截断（避免悬挂至超时）
            ctx.channel().close();
        } finally {
            closeQuietly(input);
        }
    }

    /**
     * 出站不可写时阻塞当前 worker 线程直至可写，防止慢速源 + 客户端零窗口导致出站缓冲无界增长。
     * 带 1s 超时重检，避免「设置回调前已可写」的竞态造成永久死锁。
     */
    private void waitWritable() throws InterruptedException {
        while (!ctx.channel().isWritable()) {
            CountDownLatch latch = new CountDownLatch(1);
            setWritableCallback(() -> latch.countDown());
            if (ctx.channel().isWritable()) {
                break;
            }
            // 忽略 await 的返回值会掩盖「1s 内没等到可写回调」这一事实：显式记录后由循环重新检查
            if (!latch.await(1, TimeUnit.SECONDS)) {
                log.debug("waitWritable: no writable callback within 1s, rechecking channel state");
            }
        }
    }

    /**
     * 兜底流式拷贝线程池：<b>不能</b>用 {@link java.util.concurrent.ForkJoinPool#commonPool()}。公共池与应用里的
     * parallel stream 共用，并行度只有 {@code CPU-1}（2 核 CI 上等于 1），而 {@link #streamCopy} 会阻塞
     * （{@code waitWritable} 与慢速 {@code read()}），排在公共池其它任务之后会让收尾块迟迟写不出 —— 实测 2 核下
     * {@code writeStream} 的 {@code LastHttpContent} 迟到超过 3s。这里给流式拷贝一个专用守护线程池：
     * 按需创建线程，不占用、也不阻塞公共池。
     */
    private static final ExecutorService FALLBACK_STREAM_POOL = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "perf-stream-copy");
        thread.setDaemon(true);
        return thread;
    });

    /** 兜底流式拷贝线程池（见 {@link #FALLBACK_STREAM_POOL}）。 */
    private static ExecutorService fallbackStreamPool() {
        return FALLBACK_STREAM_POOL;
    }

    /**
     * 解析流式读取使用的线程池：优先业务默认池；缺失时退化为专用兜底池（绝不回退 EventLoop，否则卸载读流失去意义；
     * 也绝不回退 ForkJoin 公共池，见 {@link #FALLBACK_STREAM_POOL}）。
     */
    private ExecutorService resolveStreamPool() {
        BizPoolRegistry poolRegistry = webContext.getWebComponent(BizPoolRegistry.class);
        ExecutorService pool = poolRegistry != null ? poolRegistry.getDefaultPool() : null;
        return pool != null ? pool : fallbackStreamPool();
    }

    /** 异常路径下关闭输入流：失败不影响正在抛出的异常，仅记录 debug。 */
    private void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            log.debug("input close failed", ignored);
        }
    }

    // ---------- byte array: Content-Length + single flush ----------
    @Override
    public void writeBytes(byte[] data) {
        if (!setCommitted()) {
            return;
        }
        applySwallowLimit();
        if (responseHeadersExceedLimit()) {
            writeHeaderTooLarge();
            return;
        }
        // HEAD：抑制 body——只发送 headers，Content-Length 保留真实长度
        ByteBuf body = headRequest ? null : Unpooled.wrappedBuffer(data);
        HttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(this.status.value()),
                body != null ? body : Unpooled.EMPTY_BUFFER, nettyHeaders, EmptyHttpHeaders.INSTANCE);
        // 零拷贝：nettyHeaders 即框架 headers 视图底层存储，无需逐条拷贝
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, data.length);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }
        ChannelFuture f = ctx.writeAndFlush(response);
        addRespEventListener(f, true);
        if (!keepAlive) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    // ---------- streaming: File ----------
    public void writeFile(File file) {
        if (!setCommitted()) {
            return;
        }
        applySwallowLimit();
        if (responseHeadersExceedLimit()) {
            writeHeaderTooLarge();
            return;
        }
        FileChannel fc = null;
        try {
            long fileLen = file.length();
            // 用 Content-Length 帧：文件长度已知，原始 DefaultFileRegion 零拷贝直发。
            // initHttpResponse(..., chunked=true) 会设 Transfer-Encoding: chunked，但文件体是
            // 原始字节而非 chunk 编码——双帧共存非法（RFC 7230 §3.3.2），客户端会按 chunked 解析错乱。
            // 按文件扩展名推导正确 MIME（静态资源/文件下载不失真）；调用方已预先 setContentType
            // 则尊重之；均无法定夺时回退 octet-stream。
            String fileContentType = nettyHeaders.get(HttpHeaderNames.CONTENT_TYPE);
            if (fileContentType == null) {
                org.springframework.http.MediaType mediaType = org.springframework.http.MediaTypeFactory
                        .getMediaType(new org.springframework.core.io.FileSystemResource(file)).orElse(null);
                fileContentType = mediaType != null ? mediaType.toString() : "application/octet-stream";
            }
            HttpResponse response = initHttpResponse(null, fileContentType, null, true);
            response.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLen);
            // 零拷贝文件体（DefaultFileRegion）不经 HttpObject，压缩器无法压缩；先于「首个响应头写出」置位，
            // 让压缩器在 header encode 时整响应透传。置位仅在 header 写出前发生，缩小异常窗口；
            // header future 监听兜底清除，catch 再兜底一次，确保异常路径不残留污染后续响应。
            io.springperf.web.server.ChannelAttrs attrs = io.springperf.web.server.ChannelAttrs.of(ctx.channel());
            attrs.compressionSkip = true;
            ChannelFuture headerFuture = ctx.writeAndFlush(response);
            headerFuture.addListener(f -> attrs.compressionSkip = false);
            addRespEventListener(headerFuture, false);
            if (headRequest) {
                // HEAD：不发文件体——补发 LastHttpContent 收尾（headers 已含真实 Content-Length）
                ChannelFuture lastFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                addRespEventListener(lastFuture, true);
                if (!keepAlive) {
                    lastFuture.addListener(ChannelFutureListener.CLOSE);
                }
                return;
            }
            // write file
            fc = new FileInputStream(file).getChannel();
            final FileChannel toClose = fc;
            DefaultFileRegion region =
                    new DefaultFileRegion(fc, 0, file.length());
            ChannelFuture future = ctx.write(region);
            future.addListener(f -> {
                try {
                    toClose.close();
                } catch (IOException ignored) {
                    log.debug("toClose FileChannel close failed", ignored);
                }
            });
            if (!keepAlive) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
            // FileRegion 不是 HttpObject，HttpObjectEncoder 不因它重置内部 state；
            // 必须补发 LastHttpContent 让编码器 state 从 ST_CONTENT 归位到 ST_INIT，
            // 否则 keep-alive 连接被污染——下个请求复用该连接写 DefaultHttpResponse
            // 会抛 "unexpected message type: DefaultHttpResponse, state: 1"，
            // headers 丢失、body 裸写（客户端把文件内容当状态行）。
            ChannelFuture lastFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            addRespEventListener(lastFuture, true);
        } catch (Exception ex) {
            // 异常路径兜底清除 SKIP，避免残留污染同 keep-alive 通道的后续响应
            io.springperf.web.server.ChannelAttrs.of(ctx.channel()).compressionSkip = false;
            if (fc != null) {
                try {
                    fc.close();
                } catch (IOException ignored) {
                    log.debug("fc FileChannel close failed", ignored);
                }
            }
            throw new RuntimeException(ex);
        }

    }

    public void addRespEventListener(ChannelFuture channelFuture, boolean isComplete) {
        if (writeRespEventListener != null) {
            if (isComplete) {
                channelFuture.addListener(future -> {
                    if (future.isSuccess()) {
                        writeRespEventListener.completeSuccessCallback();
                    } else {
                        writeRespEventListener.completeErrorCallback(future.cause());
                    }
                    // 写终结（成功/失败）后兜底释放未提交 buf：
                    // 成功路径 buf 已随 FullHttpResponse 转移给 Netty → release() 为空操作；
                    // 写失败路径响应不会再有提交机会 → 必须在此释放，否则池化 buf 泄漏。
                    release();
                });
            } else {
                channelFuture.addListener(future -> {
                    if (future.isSuccess()) {
                        writeRespEventListener.writeStreamSuccessCallback();
                    } else {
                        writeRespEventListener.writeStreamErrorCallback(future.cause());
                    }
                });
            }
        } else {
            channelFuture.addListener(LOG_ERROR_ON_FAILURE);
        }
    }

    @Override
    public void runOnEventLoop(Runnable task) {
        if (ctx.executor().inEventLoop()) {
            task.run();
        } else {
            ctx.executor().execute(task);
        }
    }

    @Override
    public ScheduledFuture scheduleOnEventLoop(Runnable task, long delay, TimeUnit unit) {
        return ctx.executor().schedule(task, delay, unit);
    }

    public ChannelHandlerContext getCtx() {
        return ctx;
    }

    public void setWritableCallback(Runnable callback) {
        runOnEventLoop(() -> {
            // 连接上下文收进 ChannelAttrs（每连接仅 1 个 channel attr），
            // 与 BackpressureHandler 的读取路径共用同一持有者。
            io.springperf.web.server.ChannelAttrs attrs = io.springperf.web.server.ChannelAttrs.of(ctx.channel());
            ConnectionContext conn = attrs.connCtx;
            if (conn == null) {
                conn = new ConnectionContext();
                attrs.connCtx = conn;
            }
            conn.setOnWritable(callback);
        });
    }
}

