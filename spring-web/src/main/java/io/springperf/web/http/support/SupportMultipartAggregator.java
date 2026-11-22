package io.springperf.web.http.support;

import java.util.List;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;

public class SupportMultipartAggregator extends HttpObjectAggregator {

    // multipart 状态
    private SupportMultipartResolver multipart;

    /**
     * 超限/畸形后进入丢弃态：连接关闭前客户端仍在途的 HttpContent 必须就地释放。
     * <p>
     * 此时 {@code resolver.currentMultipart} 已被 abort() 置 false，而父类 {@code MessageAggregator.acceptInboundMessage} 在没有
     * start message 的 聚合上下文时会返回 false —— {@code MessageToMessageDecoder} 遂把这些 content 原样 fire 到下游，无人消费，池化 direct buffer
     * 泄漏（CI paranoid 报 20 处 content 泄漏）。此标志让在途消息仍被本 handler 接收，由解码框架自动释放。
     * </p>
     */
    private boolean discarding;

    public SupportMultipartAggregator(int maxContentLength) {
        this(maxContentLength, new SupportMultipartResolver(maxContentLength));
    }

    public SupportMultipartAggregator(int maxContentLength, int maxPartCount) {
        this(maxContentLength, new SupportMultipartResolver(maxContentLength, maxPartCount));
    }

    public SupportMultipartAggregator(int maxContentLength, int maxPartCount, int maxPartHeaderSize) {
        this(maxContentLength, new SupportMultipartResolver(maxContentLength, maxPartCount, maxPartHeaderSize));
    }

    public SupportMultipartAggregator(int maxContentLength, int maxPartCount, int maxPartHeaderSize, long maxFileSize) {
        this(maxContentLength,
                new SupportMultipartResolver(maxContentLength, maxPartCount, maxPartHeaderSize, maxFileSize));
    }

    public SupportMultipartAggregator(int maxContentLength, int maxPartCount, int maxPartHeaderSize, long maxFileSize,
            long fileSizeThreshold, String location) {
        this(maxContentLength, new SupportMultipartResolver(maxContentLength, maxPartCount, maxPartHeaderSize,
                maxFileSize, fileSizeThreshold, location));
    }

    public SupportMultipartAggregator(int maxContentLength, SupportMultipartResolver multipart) {
        super(maxContentLength);
        this.multipart = multipart;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, HttpObject msg, List<Object> out) throws Exception {
        // 丢弃态：本请求已回 413/400 并关闭连接，关闭前在途的 HttpContent 不再处理。
        // 不调用 super.decode()：父类在没有聚合上下文时会把孤立 content fire 到下游，无人消费即泄漏。
        // 这里不手动 release —— acceptInboundMessage 已对在途消息返回 true，MessageToMessageDecoder
        // 在 decode() 正常返回后会自动 release 入参，重复释放会抛 IllegalReferenceCountException。
        if (discarding) {
            return;
        }
        // 0. 解码器失败拦截：请求头超过 server.max-http-request-header-size 等场景下，
        // Netty 不抛异常而是产出 decodeResult=failure 的 HttpRequest（如 TooLongHttpHeaderException）。
        // 若不拦截，坏请求会被当正常请求交付业务处理——绕过全部请求头/请求行防护。
        // 对齐 HttpObjectAggregator 语义：400 优雅关闭。
        if (msg instanceof HttpRequest && ((HttpRequest) msg).decoderResult().isFailure()) {
            rejectAndDiscard(ctx, (HttpRequest) msg, false);
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
                rejectAndDiscard(ctx, currentReq, true);
                return;
            } catch (DecoderException e) {
                // 畸形 multipart 报文：返回 400 优雅关闭，而非裸异常关闭连接（TooLongFrameException
                // 是 DecoderException 子类，必须在前先捕获，否则被此处吞掉）
                rejectAndDiscard(ctx, currentReq, false);
                return;
            }
            if (content instanceof LastHttpContent) {
                // finish() 可能因 max-file-size 超限抛 TooLongFrameException（→413），
                // 或因 part 数超限抛 DecoderException（→400）
                try {
                    out.add(multipart.finish());
                } catch (TooLongFrameException e) {
                    rejectAndDiscard(ctx, currentReq, true);
                } catch (DecoderException e) {
                    rejectAndDiscard(ctx, currentReq, false);
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
                    rejectAndDiscard(ctx, req, true);
                    return;
                }
                if (msg instanceof HttpContent) {
                    try {
                        multipart.consume((HttpContent) msg);
                    } catch (TooLongFrameException e) {
                        rejectAndDiscard(ctx, req, true);
                        return;
                    } catch (DecoderException e) {
                        // 首段即畸形（如首 HttpContent 解码失败）：同样返回 400
                        rejectAndDiscard(ctx, req, false);
                        return;
                    }
                    if (msg instanceof LastHttpContent) {
                        try {
                            out.add(multipart.finish());
                        } catch (TooLongFrameException e) {
                            rejectAndDiscard(ctx, req, true);
                        } catch (DecoderException e) {
                            rejectAndDiscard(ctx, req, false);
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
        // 丢弃态：接住在途消息交给 decode() 就地释放；若返回 false，MessageToMessageDecoder
        // 会把 content fire 到下游，无人消费即泄漏。
        // 必须保持 HttpObject 类型约束：MessageToMessageDecoder 会把接受的入参强转为 HttpObject。
        // HTTP/2 stream 复位等非 HttpObject 消息（如 RST 帧）在丢弃态到达时若也被接受，会直接抛
        // ClassCastException；这类消息本就不持有池化 buffer，交给下游与既有行为一致。
        if (discarding) {
            return msg instanceof HttpObject;
        }
        if (multipart.isMultipartMode() && msg instanceof HttpContent) {
            return true;
        }
        return super.acceptInboundMessage(msg);
    }

    /** 回写拒绝响应（413/400）并进入丢弃态，随后关闭连接。 */
    private void rejectAndDiscard(ChannelHandlerContext ctx, HttpRequest req, boolean oversized) throws Exception {
        discarding = true;
        if (oversized) {
            handleOversizedMessage(ctx, req);
        } else {
            multipart.abort();
            handleBadRequest(ctx, req);
        }
    }

    protected void releaseMultipart() {
        multipart.abort();
        // discarding 不能在此复位：413/400 写出后立即 CLOSE，Linux(epoll) 上 channelInactive
        // 往往先于在途 HttpContent 到达；若在 inactive 时退回普通模式，这些 content 会被
        // 父类按「无聚合上下文」转发到下游而无人释放（CI paranoid 的 20 处泄漏）。
        // 只在 handler 移除（连接生命周期真正结束）时复位，见 handlerRemoved()。
    }

    /**
     * 畸形 multipart 报文：直接回写 HTTP 400 并关闭连接（对齐对端 fail-closed 语义）。 与父类 {@code handleOversizedMessage}（413）一致，绕开
     * {@link NettyHttpHandler} 的 业务处理——此时请求尚未聚合完成，无 FullHttpRequest 交付，只能直写通道。
     */
    private void handleBadRequest(ChannelHandlerContext ctx, HttpRequest req) {
        FullHttpResponse response = new DefaultFullHttpResponse(req.protocolVersion(), HttpResponseStatus.BAD_REQUEST,
                Unpooled.EMPTY_BUFFER);
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
            // 连接生命周期真正结束，handler 不再接收消息，此时复位丢弃态供实例复用。
            discarding = false;
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
