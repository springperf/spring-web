package io.springperf.web.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BackpressureHandlerTest {

    @Mock ChannelHandlerContext ctx;
    @Mock Channel channel;
    @Mock Attribute<ConnectionContext> attribute;

    @Test
    void handler_isSingleton() throws Exception {
        // INSTANCE 是框架共享实例（@Sharable），且类为 final：验证返回同一实例 + final 类
        assertSame(BackpressureHandler.INSTANCE, BackpressureHandler.INSTANCE);
        assertTrue(java.lang.reflect.Modifier.isFinal(BackpressureHandler.class.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isStatic(BackpressureHandler.class.getField("INSTANCE").getModifiers()));
    }

    @Test
    void channelWritabilityChanged_noConnectionContext_firesEventOnly() {
        when(ctx.channel()).thenReturn(channel);
        when(channel.attr(NettyServerHttpResponse.CONN_CTX)).thenReturn(attribute);
        when(attribute.get()).thenReturn(null);

        BackpressureHandler.INSTANCE.channelWritabilityChanged(ctx);

        verify(ctx).fireChannelWritabilityChanged();
    }

    @Test
    void channelWritabilityChanged_falseToTrue_runsCallback() {
        ConnectionContext conn = new ConnectionContext();
        Runnable callback = mock(Runnable.class);
        conn.setOnWritable(callback);
        conn.updateWritable(false); // last = false (was not writable)

        when(ctx.channel()).thenReturn(channel);
        when(channel.isWritable()).thenReturn(true);
        when(channel.attr(NettyServerHttpResponse.CONN_CTX)).thenReturn(attribute);
        when(attribute.get()).thenReturn(conn);

        BackpressureHandler.INSTANCE.channelWritabilityChanged(ctx);

        verify(callback).run();
        verify(ctx).fireChannelWritabilityChanged();
    }

    @Test
    void channelWritabilityChanged_alreadyWritable_noCallback() {
        ConnectionContext conn = new ConnectionContext();
        Runnable callback = mock(Runnable.class);
        conn.setOnWritable(callback);
        // lastWritable defaults to true

        when(ctx.channel()).thenReturn(channel);
        when(channel.isWritable()).thenReturn(true);
        when(channel.attr(NettyServerHttpResponse.CONN_CTX)).thenReturn(attribute);
        when(attribute.get()).thenReturn(conn);

        BackpressureHandler.INSTANCE.channelWritabilityChanged(ctx);

        verify(callback, never()).run();
        verify(ctx).fireChannelWritabilityChanged();
    }

    @Test
    void channelWritabilityChanged_trueToFalse_noCallback() {
        ConnectionContext conn = new ConnectionContext();
        Runnable callback = mock(Runnable.class);
        conn.setOnWritable(callback);
        // lastWritable defaults to true

        when(ctx.channel()).thenReturn(channel);
        when(channel.isWritable()).thenReturn(false);
        when(channel.attr(NettyServerHttpResponse.CONN_CTX)).thenReturn(attribute);
        when(attribute.get()).thenReturn(conn);

        BackpressureHandler.INSTANCE.channelWritabilityChanged(ctx);

        verify(callback, never()).run();
        verify(ctx).fireChannelWritabilityChanged();
    }

    @Test
    void channelWritabilityChanged_updatesWritableState() {
        ConnectionContext conn = new ConnectionContext();
        conn.updateWritable(false);

        when(ctx.channel()).thenReturn(channel);
        when(channel.isWritable()).thenReturn(true);
        when(channel.attr(NettyServerHttpResponse.CONN_CTX)).thenReturn(attribute);
        when(attribute.get()).thenReturn(conn);

        BackpressureHandler.INSTANCE.channelWritabilityChanged(ctx);

        assertTrue(conn.lastWritable());
    }
}