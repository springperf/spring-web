package io.springperf.web.http.support;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

/** 首请求同时是 HttpContent/LastHttpContent 时 multipart 聚合的异常分支。 */
class SupportMultipartAggregatorFirstChunkTest {

    private static final int MAX = 1024;

    private static class ExposedAggregator extends SupportMultipartAggregator {
        ExposedAggregator(SupportMultipartResolver resolver) {
            super(MAX, resolver);
        }

        void decodeNow(ChannelHandlerContext ctx, HttpObject msg, List<Object> out) throws Exception {
            decode(ctx, msg, out);
        }
    }

    private static DefaultFullHttpRequest request() {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
    }

    private static SupportMultipartResolver resolver(boolean multipart) {
        SupportMultipartResolver resolver = mock(SupportMultipartResolver.class);
        when(resolver.isMultipart(any(HttpRequest.class))).thenReturn(multipart);
        return resolver;
    }

    private static ChannelHandlerContext contextOf(SupportMultipartAggregator aggregator, EmbeddedChannel channel) {
        channel.pipeline().addLast(aggregator);
        return channel.pipeline().context(aggregator);
    }

    private static void assertErrorResponseWritten(EmbeddedChannel channel) {
        Object written = channel.readOutbound();
        assertTrue(written instanceof HttpResponse, "异常分支必须写出 HTTP 响应而不是裸异常关闭连接");
        HttpResponse response = (HttpResponse) written;
        assertTrue(response.status().code() == 400 || response.status().code() == 413,
                "异常分支应返回 400/413，实际 " + response.status().code());
        ReferenceCountUtil.release(written);
    }

    @Test
    void fullRequestFinishesMultipartAggregation() throws Exception {
        SupportMultipartResolver resolver = resolver(true);
        NettyMultipartWebRequest completed = mock(NettyMultipartWebRequest.class);
        when(resolver.finish()).thenReturn(completed);
        EmbeddedChannel channel = new EmbeddedChannel();
        ExposedAggregator aggregator = new ExposedAggregator(resolver);
        List<Object> out = new ArrayList<>();

        aggregator.decodeNow(contextOf(aggregator, channel), request(), out);

        verify(resolver).start(any(HttpRequest.class));
        verify(resolver).consume(any(LastHttpContent.class));
        assertEquals(List.of(completed), out);
        channel.finishAndReleaseAll();
    }

    @Test
    void startTooLongWritesErrorResponseWithoutFinishing() throws Exception {
        SupportMultipartResolver resolver = resolver(true);
        doThrow(new TooLongFrameException("too large")).when(resolver).start(any(HttpRequest.class));
        EmbeddedChannel channel = new EmbeddedChannel();
        ExposedAggregator aggregator = new ExposedAggregator(resolver);
        List<Object> out = new ArrayList<>();

        aggregator.decodeNow(contextOf(aggregator, channel), request(), out);

        assertTrue(out.isEmpty());
        assertErrorResponseWritten(channel);
        verify(resolver, never()).finish();
        channel.finishAndReleaseAll();
    }

    @Test
    void consumeDecoderExceptionAbortsBeforeBadRequest() throws Exception {
        assertConsumeFailure(new DecoderException("malformed"), true);
    }

    @Test
    void consumeTooLongTakesOversizedPath() throws Exception {
        assertConsumeFailure(new TooLongFrameException("too large"), false);
    }

    private static void assertConsumeFailure(RuntimeException failure, boolean fromDecoderException) throws Exception {
        SupportMultipartResolver resolver = resolver(true);
        doThrow(failure).when(resolver).consume(any(LastHttpContent.class));
        EmbeddedChannel channel = new EmbeddedChannel();
        ExposedAggregator aggregator = new ExposedAggregator(resolver);
        List<Object> out = new ArrayList<>();

        aggregator.decodeNow(contextOf(aggregator, channel), request(), out);

        assertTrue(out.isEmpty());
        assertErrorResponseWritten(channel);
        verify(resolver, never()).finish();
        // TooLongFrameException 与 DecoderException 两条异常路径都必须 abort，避免残留 decoder 资源
        verify(resolver, atLeastOnce()).abort();
        channel.finishAndReleaseAll();
    }

    @Test
    void finishDecoderExceptionAbortsBeforeBadRequest() throws Exception {
        assertFinishFailure(new DecoderException("bad part count"), true);
    }

    @Test
    void finishTooLongTakesOversizedPath() throws Exception {
        assertFinishFailure(new TooLongFrameException("file too large"), false);
    }

    private static void assertFinishFailure(RuntimeException failure, boolean fromDecoderException) throws Exception {
        SupportMultipartResolver resolver = resolver(true);
        when(resolver.finish()).thenThrow(failure);
        EmbeddedChannel channel = new EmbeddedChannel();
        ExposedAggregator aggregator = new ExposedAggregator(resolver);
        List<Object> out = new ArrayList<>();

        aggregator.decodeNow(contextOf(aggregator, channel), request(), out);

        assertTrue(out.isEmpty());
        assertErrorResponseWritten(channel);
        verify(resolver).finish();
        // TooLongFrameException 与 DecoderException 两条异常路径都必须 abort，避免残留 decoder 资源
        verify(resolver, atLeastOnce()).abort();
        channel.finishAndReleaseAll();
    }

    @Test
    void constructorsWithExplicitLimitsKeepHttpObjectAggregatorInboundContract() throws Exception {
        for (SupportMultipartAggregator aggregator : List.of(
                new SupportMultipartAggregator(MAX, 2),
                new SupportMultipartAggregator(MAX, 2, 512),
                new SupportMultipartAggregator(MAX, 2, 512, 4096L),
                new SupportMultipartAggregator(MAX, 2, 512, 4096L, 1024L, null))) {
            assertTrue(aggregator.acceptInboundMessage(
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload")));
            assertFalse(aggregator.acceptInboundMessage(request()),
                    "FullHttpMessage 由 HttpObjectAggregator 契约拒绝，不能直接入站");
        }
    }
}
