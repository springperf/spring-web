package io.springperf.web.http.support;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link SupportMultipartAggregator} 对 multipart/form-data 请求的流式聚合。
 * <p>注意：Netty 4.1.137 {@code MessageAggregator.acceptInboundMessage} 会拒绝
 * {@code FullHttpMessage}（已聚合），故测试必须按真实管线（HttpServerCodec 解码产物）
 * 分块喂入 {@code HttpRequest} + {@code HttpContent} + {@code LastHttpContent}。</p>
 */
class SupportMultipartAggregatorTest {

    private static String multipartBody() {
        return "--boundary\r\n"
                + "Content-Disposition: form-data; name=\"field1\"\r\n\r\n"
                + "value1\r\n"
                + "--boundary\r\n"
                + "Content-Disposition: form-data; name=\"file1\"; filename=\"test.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "file-content\r\n"
                + "--boundary--\r\n";
    }

    private static DefaultHttpRequest multipartHead() {
        DefaultHttpRequest head = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        head.headers().set("Content-Type", "multipart/form-data; boundary=boundary");
        head.headers().set("Transfer-Encoding", "chunked");
        return head;
    }

    @Test
    void nonMultipartRequest_chunked_usesDefaultAggregation() {
        EmbeddedChannel channel = new EmbeddedChannel(new SupportMultipartAggregator(1024 * 1024));
        DefaultHttpRequest head = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api");
        head.headers().set("Content-Type", "application/x-www-form-urlencoded");
        head.headers().set("Transfer-Encoding", "chunked");

        channel.writeInbound(head);
        channel.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer("a=1&b=2", StandardCharsets.UTF_8)));
        channel.writeInbound(new DefaultLastHttpContent());

        Object out = channel.readInbound();
        assertTrue(out instanceof FullHttpMessage);
        FullHttpMessage aggregated = (FullHttpMessage) out;
        assertEquals("a=1&b=2", aggregated.content().toString(StandardCharsets.UTF_8));
        aggregated.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void multipartRequest_chunked_aggregatesToMultipartWebRequest() {
        EmbeddedChannel channel = new EmbeddedChannel(new SupportMultipartAggregator(1024 * 1024));
        channel.writeInbound(multipartHead());
        channel.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer(multipartBody(), StandardCharsets.UTF_8)));
        channel.writeInbound(new DefaultLastHttpContent());

        Object out = channel.readInbound();
        assertTrue(out instanceof NettyMultipartWebRequest, "multipart 请求应聚合为 NettyMultipartWebRequest，实际: "
                + (out == null ? "null" : out.getClass().getName()));
        NettyMultipartWebRequest multipart = (NettyMultipartWebRequest) out;
        assertTrue(multipart.getParameters().containsKey("field1"));
        assertTrue(multipart.getFiles().containsKey("file1"));
        multipart.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void multipartRequest_contentSplitAcrossChunks_aggregates() {
        EmbeddedChannel channel = new EmbeddedChannel(new SupportMultipartAggregator(1024 * 1024));
        channel.writeInbound(multipartHead());

        String body = multipartBody();
        int mid = body.length() / 2;
        channel.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer(body.substring(0, mid), StandardCharsets.UTF_8)));
        channel.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer(body.substring(mid), StandardCharsets.UTF_8)));
        channel.writeInbound(new DefaultLastHttpContent());

        Object out = channel.readInbound();
        assertTrue(out instanceof NettyMultipartWebRequest);
        NettyMultipartWebRequest multipart = (NettyMultipartWebRequest) out;
        assertTrue(multipart.getParameters().containsKey("field1"));
        assertTrue(multipart.getFiles().containsKey("file1"));
        multipart.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void multipartMode_acceptsHttpContentWhileActive() throws Exception {
        SupportMultipartResolver resolver = new SupportMultipartResolver();
        SupportMultipartAggregator aggregator = new SupportMultipartAggregator(1024 * 1024, resolver);
        EmbeddedChannel channel = new EmbeddedChannel(aggregator);

        channel.writeInbound(multipartHead());
        assertTrue(resolver.isMultipartMode(), "multipart 头到达后应进入 multipart 模式");
        assertTrue(aggregator.acceptInboundMessage(
                        new DefaultHttpContent(Unpooled.copiedBuffer("x", StandardCharsets.UTF_8))),
                "multipart 模式期间 HttpContent 应被接受");
        channel.finishAndReleaseAll();
    }

    @Test
    void handlerRemoved_releasesMultipartState() {
        SupportMultipartResolver resolver = new SupportMultipartResolver();
        SupportMultipartAggregator aggregator = new SupportMultipartAggregator(1024 * 1024, resolver);
        EmbeddedChannel channel = new EmbeddedChannel(aggregator);

        channel.writeInbound(multipartHead());
        assertTrue(resolver.isMultipartMode());

        channel.finishAndReleaseAll();
        assertFalse(resolver.isMultipartMode(), "pipeline 关闭后 multipart 状态应复位");
    }

    @Test
    void exceptionCaught_releasesMultipartState() {
        SupportMultipartResolver resolver = new SupportMultipartResolver();
        SupportMultipartAggregator aggregator = new SupportMultipartAggregator(1024 * 1024, resolver);
        // 追加吞异常的 tail handler，避免 EmbeddedChannel 将异常向上重抛
        EmbeddedChannel channel = new EmbeddedChannel(aggregator,
                new io.netty.channel.ChannelInboundHandlerAdapter() {
                    @Override
                    public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
                        // swallow
                    }
                });

        channel.writeInbound(multipartHead());
        assertTrue(resolver.isMultipartMode());
        channel.pipeline().fireExceptionCaught(new RuntimeException("test"));
        assertFalse(resolver.isMultipartMode(), "异常后 multipart 状态应复位");
        channel.finishAndReleaseAll();
    }
}
