package io.springperf.web.http.support;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;

import java.util.List;

public class SupportMultipartAggregator extends HttpObjectAggregator {

    // multipart 状态
    private SupportMultipartResolver multipart;

    public SupportMultipartAggregator(int maxContentLength) {
        this(maxContentLength, new SupportMultipartResolver(maxContentLength));
    }

    public SupportMultipartAggregator(int maxContentLength, int maxPartCount) {
        this(maxContentLength, new SupportMultipartResolver(maxContentLength, maxPartCount));
    }

    public SupportMultipartAggregator(int maxContentLength, int maxPartCount, int maxPartHeaderSize) {
        this(maxContentLength, new SupportMultipartResolver(maxContentLength, maxPartCount, maxPartHeaderSize));
    }

    public SupportMultipartAggregator(int maxContentLength, int maxPartCount, int maxPartHeaderSize,
                                      long maxFileSize) {
        this(maxContentLength,
                new SupportMultipartResolver(maxContentLength, maxPartCount, maxPartHeaderSize, maxFileSize));
    }

    public SupportMultipartAggregator(int maxContentLength, int maxPartCount, int maxPartHeaderSize,
                                      long maxFileSize, long fileSizeThreshold, String location) {
        this(maxContentLength, new SupportMultipartResolver(maxContentLength, maxPartCount, maxPartHeaderSize,
                maxFileSize, fileSizeThreshold, location));
    }

    public SupportMultipartAggregator(int maxContentLength, SupportMultipartResolver multipart) {
        super(maxContentLength);
        this.multipart = multipart;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, HttpObject msg, List<Object> out) throws Exception {
        // 0. 解码器失败拦截：请求头超过 server.max-http-request-header-size 等场景下，
        // Netty 不抛异常而是产出 decodeResult=failure 的 HttpRequest（如 TooLongHttpHeaderException）。
        // 若不拦截，坏请求会被当正常请求交付业务处理——绕过全部请求头/请求行防护。
        // 对齐 HttpObjectAggregator 语义：400 优雅关闭。
        if (msg instanceof HttpRequest && ((HttpRequest) msg).decoderResult().isFailure()) {
            handleBadRequest(ctx, (HttpRequest) msg);
            return;
        }
        // 1. 如果 multipart 已经开始，直接 consume
        if (multipart.isMultipartMode()) {
            HttpContent content = (HttpContent) msg;
            // 提前捕获当前请求引用：consume 超限时 abort() 会清空 resolver 的 request，需用副本构造 413
            HttpRequest currentReq = multipart.getRequest();
            try {
                multipart.consume(content);
            } catch (TooLongFrameException e) {
                // 对齐 HttpObjectAggregator 行为：超限返回 413，而非裸异常关闭连接
                handleOversizedMessage(ctx, currentReq);
                return;
            } catch (DecoderException e) {
                // 畸形 multipart 报文：返回 400 优雅关闭，而非裸异常关闭连接（TooLongFrameException
                // 是 DecoderException 子类，必须在前先捕获，否则被此处吞掉）
                multipart.abort();
                handleBadRequest(ctx, currentReq);
                return;
            }
            if (content instanceof LastHttpContent) {
                // finish() 可能因 max-file-size 超限抛 TooLongFrameException（→413），
                // 或因 part 数超限抛 DecoderException（→400）
                try {
                    out.add(multipart.finish());
                } catch (TooLongFrameException e) {
                    handleOversizedMessage(ctx, currentReq);
                } catch (DecoderException e) {
                    multipart.abort();
                    handleBadRequest(ctx, currentReq);
                }
            }
            return;
        }
        // 2. 新请求，判断是否 multipart
        if (msg instanceof HttpRequest) {
            HttpRequest req = (HttpRequest) msg;
            if (multipart.isMultipart(req)) {
                try {
                    multipart.start(req);
                } catch (TooLongFrameException e) {
                    handleOversizedMessage(ctx, req);
                    return;
                }
                if (msg instanceof HttpContent) {
                    try {
                        multipart.consume((HttpContent) msg);
                    } catch (TooLongFrameException e) {
                        handleOversizedMessage(ctx, req);
                        return;
                    } catch (DecoderException e) {
                        // 首段即畸形（如首 HttpContent 解码失败）：同样返回 400
                        multipart.abort();
                        handleBadRequest(ctx, req);
                        return;
                    }
                    if (msg instanceof LastHttpContent) {
                        try {
                            out.add(multipart.finish());
                        } catch (TooLongFrameException e) {
                            handleOversizedMessage(ctx, req);
                        } catch (DecoderException e) {
                            multipart.abort();
                            handleBadRequest(ctx, req);
                        }
                    }
                }
                return;
            }
        }
        // 3. 非 multipart，沿用 HttpObjectAggregator
        super.decode(ctx, msg, out);
    }

    @Override
    public boolean acceptInboundMessage(Object msg) throws Exception {
        if (multipart.isMultipartMode() && msg instanceof HttpContent) {
            return true;
        }
        return super.acceptInboundMessage(msg);
    }

    protected void releaseMultipart() {
        multipart.abort();
    }

    /**
     * 畸形 multipart 报文：直接回写 HTTP 400 并关闭连接（对齐对端 fail-closed 语义）。
     * 与父类 {@code handleOversizedMessage}（413）一致，绕开 {@link NettyHttpHandler} 的
     * 业务处理——此时请求尚未聚合完成，无 FullHttpRequest 交付，只能直写通道。
     */
    private void handleBadRequest(ChannelHandlerContext ctx, HttpRequest req) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                req.protocolVersion(), HttpResponseStatus.BAD_REQUEST, Unpooled.EMPTY_BUFFER);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        try {
            super.handlerRemoved(ctx);
        } finally {
            releaseMultipart();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            super.channelInactive(ctx);
        } finally {
            releaseMultipart();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        try {
            super.exceptionCaught(ctx, cause);
        } finally {
            releaseMultipart();
        }
    }

}
