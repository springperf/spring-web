package io.springperf.web.core.async.stream;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.EventExecutor;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.NettyServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultStreamSenderFactoryTest {

    @Mock
    StreamEmitter emitter;
    @Mock
    PerfAsyncWebRequest asyncWebRequest;
    @Mock
    NettyServerHttpResponse nativeResponse;
    @Mock
    ChannelHandlerContext ctx;
    @Mock
    Channel channel;
    @Mock
    EventExecutor eventLoop;

    @Test
    void implementsStreamSenderFactory() {
        assertTrue(new DefaultStreamSenderFactory() instanceof StreamSenderFactory);
    }

    private void stubNativeChain() {
        lenient().when(asyncWebRequest.getNativeResponse()).thenReturn(nativeResponse);
        lenient().when(nativeResponse.getCtx()).thenReturn(ctx);
        lenient().when(ctx.channel()).thenReturn(channel);
        lenient().when(ctx.executor()).thenReturn(eventLoop);
        lenient().when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
        lenient().when(channel.isActive()).thenReturn(true);
        lenient().when(emitter.getMaxFlushBytes()).thenReturn(4096);
    }

    @Test
    void create_defaultEncode_returnsDefaultSender() {
        stubNativeChain();
        when(emitter.isEarlyEncode()).thenReturn(false);

        StreamSender sender = new DefaultStreamSenderFactory().create(emitter, asyncWebRequest);

        assertInstanceOf(DefaultNettyStreamSender.class, sender, "非 earlyEncode 应走 DefaultNettyStreamSender");
    }

    @Test
    void create_earlyEncode_returnsEarlyEncodeSender() {
        stubNativeChain();
        when(emitter.isEarlyEncode()).thenReturn(true);

        StreamSender sender = new DefaultStreamSenderFactory().create(emitter, asyncWebRequest);

        assertInstanceOf(EarlyEncodeNettyStreamSender.class, sender, "earlyEncode 应走 EarlyEncodeNettyStreamSender");
    }
}