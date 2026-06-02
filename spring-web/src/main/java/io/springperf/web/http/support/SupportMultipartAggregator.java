package io.springperf.web.http.support;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;

import java.util.List;

public class SupportMultipartAggregator extends HttpObjectAggregator {

    // multipart 状态
    private SupportMultipartResolver multipart;

    public SupportMultipartAggregator(int maxContentLength) {
        this(maxContentLength, new SupportMultipartResolver());
    }

    public SupportMultipartAggregator(int maxContentLength, SupportMultipartResolver multipart) {
        super(maxContentLength);
        this.multipart = multipart;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, HttpObject msg, List<Object> out) throws Exception {
        // 1. 如果 multipart 已经开始，直接 consume
        if (multipart.isMultipartMode()) {
            HttpContent content = (HttpContent) msg;
            multipart.consume(content);
            if (content instanceof LastHttpContent) {
                out.add(multipart.finish());
            }
            return;
        }
        // 2. 新请求，判断是否 multipart
        if (msg instanceof HttpRequest) {
            HttpRequest req = (HttpRequest) msg;
            if (multipart.isMultipart(req)) {
                multipart.start(req);
                if (msg instanceof HttpContent) {
                    multipart.consume((HttpContent) msg);
                    if (msg instanceof LastHttpContent) {
                        out.add(multipart.finish());
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
