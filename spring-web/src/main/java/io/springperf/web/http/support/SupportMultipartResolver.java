package io.springperf.web.http.support;

import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SupportMultipartResolver {

    /** 单 part 尺寸上限：超过则落盘到临时文件（默认 Netty MINSIZE）。 */
    protected HttpDataFactory factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);

    /** 整个 multipart 请求体的最大字节数（-1 表示不限制）。 */
    private final long maxContentLength;
    /** 已消费的字节数（流式累计，用于限制 chunked / 无 Content-Length 的请求）。 */
    private long consumedBytes;

    protected HttpRequest request;

    protected HttpHeaders trailingHeader = EmptyHttpHeaders.INSTANCE;
    protected HttpPostRequestDecoder decoder;
    boolean currentMultipart = false;

    public SupportMultipartResolver() {
        this(-1);
    }

    public SupportMultipartResolver(long maxContentLength) {
        this.maxContentLength = maxContentLength;
    }

    public boolean isMultipartMode() {
        return currentMultipart;
    }

    public boolean isMultipart(HttpRequest request) {
        return HttpPostRequestDecoder.isMultipart(request);
    }

    /** 当前正在处理的 multipart 请求头（供超限时构造 413 响应）。 */
    public HttpRequest getRequest() {
        return request;
    }

    public void start(HttpRequest request) {
        currentMultipart = true;
        this.request = request;
        this.consumedBytes = 0;
        // Content-Length 提前 fail-fast：声明长度超限直接拒绝，不进入流式消费
        long declared = HttpUtil.getContentLength(request, -1L);
        if (maxContentLength > 0 && declared > maxContentLength) {
            abort();
            throw new TooLongFrameException(
                    "multipart content length " + declared + " exceeds limit " + maxContentLength);
        }
        this.decoder = new HttpPostRequestDecoder(factory, request);
    }

    public void consume(HttpContent content) {
        // 流式累计字节数（覆盖 chunked / 无 Content-Length 的请求），超限抛异常拒绝
        if (maxContentLength > 0) {
            consumedBytes += content.content().readableBytes();
            if (consumedBytes > maxContentLength) {
                abort();
                throw new TooLongFrameException(
                        "multipart content exceeds limit " + maxContentLength);
            }
        }
        if (content instanceof LastHttpContent) {
            trailingHeader = ((LastHttpContent) content).trailingHeaders();
        }
        try {
            decoder.offer(content);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public NettyMultipartWebRequest finish() {
        NettyMultipartWebRequest multipart = new NettyMultipartWebRequest(request, decoder, trailingHeader);
        currentMultipart = false;
        return multipart;
    }

    public void abort() {
        if (currentMultipart) {
            currentMultipart = false;
            trailingHeader = EmptyHttpHeaders.INSTANCE;
            request = null;
            if (decoder != null) {
                decoder.destroy();
                decoder = null;
            }
        }
    }
}
