package io.springperf.web.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedStream;
import io.netty.util.AttributeKey;
import io.springperf.web.context.WebContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.io.*;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * ServerHttpResponse 实现
 */
@Slf4j
public class NettyServerHttpResponse extends BaseWebServerHttpResponse {

    public static final AttributeKey<ConnectionContext> CONN_CTX = AttributeKey.valueOf("conn_ctx");

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

    public NettyServerHttpResponse(WebContext webContext, ChannelHandlerContext ctx, boolean keepAlive) {
        this(webContext, ctx, keepAlive, new DefaultHttpHeaders(false));
    }

    private NettyServerHttpResponse(WebContext webContext, ChannelHandlerContext ctx, boolean keepAlive,
                                    io.netty.handler.codec.http.HttpHeaders nettyHeaders) {
        super(webContext, keepAlive, new WebHttpHeaders(new NettyHttpHeadersAdapter(nettyHeaders, true)));
        this.ctx = ctx;
        this.nettyHeaders = nettyHeaders;
    }

    /** 标记本响应对应 HEAD 请求，后续 flush/write* 时抑制响应体。 */
    public void markAsHeadRequest() {
        this.headRequest = true;
    }

    protected boolean isHeadRequest() {
        return headRequest;
    }

    public ByteBuf getBuf() {
        if (buf == null) {
            buf = ctx.alloc().buffer(256);
        }
        return buf;
    }

    @Override
    public OutputStream getBody() {
        return new ByteBufOutputStream(getBuf());
    }

    @Override
    public void flush(boolean chunked) throws IOException {
        ByteBuf buf = this.buf;
        try {
            // HEAD 抑制逻辑由 writeAndFlush 内部处理（保留 Content-Length，丢弃 body）
            writeAndFlush(buf, null, null, chunked);
        } catch (Exception e) {
            // make sure to release buffer on error
            if (buf != null && buf.refCnt() > 0) {
                buf.release();
            }
            // 置空已释放的 buffer，防止后续 getBuf()/getBody() 返回已释放的 ByteBuf
            this.buf = null;
            throw new RuntimeException(e);
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

    // ---------- streaming: InputStream -> ChunkedStream ----------
    public void writeStream(InputStream input) {
        if (!setCommitted()) {
            return;
        }
        HttpResponse response = initHttpResponse(null, "application/octet-stream", null, true);
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        try {
            if (headRequest) {
                // HEAD：不发 body——发送 headers 后立即发 LastHttpContent 收尾
                addRespEventListener(ctx.writeAndFlush(response), false);
                ChannelFuture lastFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                addRespEventListener(lastFuture, true);
                if (!keepAlive) {
                    lastFuture.addListener(ChannelFutureListener.CLOSE);
                }
                return;
            }
            addRespEventListener(ctx.writeAndFlush(response), false);
            // ChunkedStream produces ByteBuf chunks wrapped in HttpContent
            final ChunkedStream cs = new ChunkedStream(input);
            // Netty provides HttpChunkedInput which writes HttpContent boundaries automatically
            final HttpChunkedInput httpChunkedInput = new HttpChunkedInput(cs);
            ChannelFuture writeFuture = ctx.writeAndFlush(httpChunkedInput);
            addRespEventListener(writeFuture, true);
            if (!keepAlive) {
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
        } catch (Exception ex) {
            // ensure input closed on error
            try {
                input.close();
            } catch (IOException ignored) {
                log.debug("input close failed", ignored);
            }
            throw new RuntimeException(ex);
        }
    }

    // ---------- byte array: Content-Length + single flush ----------
    @Override
    public void writeBytes(byte[] data) {
        if (!setCommitted()) {
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
        FileChannel fc = null;
        try {
            long fileLen = file.length();
            // 用 Content-Length 帧：文件长度已知，原始 DefaultFileRegion 零拷贝直发。
            // initHttpResponse(..., chunked=true) 会设 Transfer-Encoding: chunked，但文件体是
            // 原始字节而非 chunk 编码——双帧共存非法（RFC 7230 §3.3.2），客户端会按 chunked 解析错乱。
            HttpResponse response = initHttpResponse(null, "application/octet-stream", null, true);
            response.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLen);
            // write headers
            addRespEventListener(ctx.writeAndFlush(response), false);
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
            ConnectionContext conn = ctx.attr(NettyServerHttpResponse.CONN_CTX).get();
            if (conn == null) {
                conn = new ConnectionContext();
                ctx.attr(NettyServerHttpResponse.CONN_CTX).set(conn);
            }
            conn.setOnWritable(callback);
        });
    }
}

