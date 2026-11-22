package io.springperf.web.http.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;

/**
 * {@link MultipartDecoderReleaser} 的边界契约：空值、非首次销毁都必须自行消化异常，且重试次数固定为 2（首次中断后只补一次）。
 * <p>
 * 后者的意义：Netty 的 {@code destroy()} 不是幂等实现（已销毁时 {@code cleanFiles()} 走 {@code checkDestroyed()} 抛
 * {@code IllegalStateException}），调用方不应为此写 try/catch。
 * </p>
 */
class MultipartDecoderReleaserTest {

    @Test
    void destroy_null_isNoOp() {
        assertDoesNotThrow(() -> MultipartDecoderReleaser.destroy(null));
    }

    @Test
    void destroy_retriesOnceWhenFirstAttemptFails() {
        HttpPostRequestDecoder decoder = mock(HttpPostRequestDecoder.class);
        doThrow(new IllegalStateException("interrupted in cleanFiles")).doNothing().when(decoder).destroy();

        MultipartDecoderReleaser.destroy(decoder);

        // 首次中断后必须再试一次；上限固定为 2，避免对不可恢复状态反复重试
        verify(decoder, times(2)).destroy();
    }

    @Test
    void destroy_givesUpAfterTwoAttempts() {
        HttpPostRequestDecoder decoder = mock(HttpPostRequestDecoder.class);
        doThrow(new IllegalStateException("always fails")).when(decoder).destroy();

        assertDoesNotThrow(() -> MultipartDecoderReleaser.destroy(decoder));

        verify(decoder, times(2)).destroy();
    }

    @Test
    void destroy_alreadyDestroyed_swallowsNettyException() {
        String boundary = "----R1";
        String body = "--" + boundary + "\r\n" + "Content-Disposition: form-data; name=\"a\"\r\n\r\n" + "b\r\n" + "--"
                + boundary + "--\r\n";
        ByteBuf content = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", content);
        try {
            SupportMultipartResolver resolver = new SupportMultipartResolver();
            resolver.start(req);
            resolver.consume(req);
            HttpPostRequestDecoder decoder = resolver.decoder;
            decoder.destroy();

            // 第二次销毁：Netty 抛 IllegalStateException，入口须重试后放弃并告警，而不是把异常抛给调用方
            assertDoesNotThrow(() -> MultipartDecoderReleaser.destroy(decoder));
        } finally {
            if (content.refCnt() > 0) {
                content.release();
            }
        }
    }
}
