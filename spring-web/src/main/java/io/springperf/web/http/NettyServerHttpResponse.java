package io.springperf.web.http;

import io.springperf.web.context.WebContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedStream;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.io.*;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.Map;
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

    protected volatile ByteBuf buf;

    public NettyServerHttpResponse(WebContext webContext, ChannelHandlerContext ctx, boolean keepAlive) {
        super(webContext, keepAlive);
        this.ctx = ctx;
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
    public void flush() throws IOException {
        ByteBuf buf = this.buf;
        try {
            writeAndFlush(buf, null, null);
        } catch (Exception e) {
            // make sure to release buffer on error
            if (buf != null && buf.refCnt() > 0) {
                buf.release();
            }
            throw new RuntimeException(e);
        }
    }

    private HttpResponse initHttpResponse(ByteBuf buf, String contentType, HttpStatus statusCode, boolean stream) {
        setStatusCode(statusCode);
        HttpResponse response;
        if (buf != null) {
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(this.status.value()), buf);
        } else if (stream) {
            response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(this.status.value()));
        } else {
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(this.status.value()));
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            for (String v : e.getValue()) {
                response.headers().add(e.getKey(), v);
            }
        }
        if (contentType != null) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
        if (buf != null) {
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
        } else if (stream) {
            HttpUtil.setTransferEncodingChunked(response, true);
        } else {
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        }
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }
        return response;
    }


    protected void writeAndFlush(ByteBuf buf, String contentType, HttpStatus statusCode) {
        boolean stream = setHandled();
        if (!setCommitted()) {
            return;
        }
        HttpResponse response = initHttpResponse(buf, contentType, statusCode, stream);
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
                // input 关闭失败无需额外处理
            }
            throw new RuntimeException(ex);
        }
    }

    // ---------- streaming: File ----------
    public void writeFile(File file) {
        if (!setCommitted()) {
            return;
        }
        FileChannel fc = null;
        try {
            HttpResponse response = initHttpResponse(null, "application/octet-stream", null, true);
            long fileLen = file.length();
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLen);
            // write headers
            addRespEventListener(ctx.writeAndFlush(response), false);
            // write file
            fc = new FileInputStream(file).getChannel();
            final FileChannel toClose = fc;
            DefaultFileRegion region =
                    new DefaultFileRegion(fc, 0, file.length());
            ChannelFuture future = ctx.writeAndFlush(region);
            future.addListener(f -> {
                try {
                    toClose.close();
                } catch (IOException ignored) {
                    // FileChannel 关闭失败无需额外处理
                }
            });
            addRespEventListener(future, true);
            if (!keepAlive) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
        } catch (Exception ex) {
            if (fc != null) {
                try {
                    fc.close();
                } catch (IOException ignored) {
                    // FileChannel 关闭失败无需额外处理
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

