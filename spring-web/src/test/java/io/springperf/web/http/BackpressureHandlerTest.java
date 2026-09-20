package io.springperf.web.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.Attribute;
import io.springperf.web.server.ChannelAttrs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BackpressureHandler} 的可写性回调。
 *
 * <p>连接上下文现由 {@link ChannelAttrs}（每连接状态持有者，单个 channel attr）持有而非独立
 * {@code CONN_CTX} attr，故夹具改为「mock Channel 的 attr(HOLDER) 返回真实持有者」——
 * channel 仍为 mock 以便控制 {@code isWritable()}（EmbeddedChannel 的可写性不可直接摆布）。</p>
 */
@ExtendWith(MockitoExtension.class)
class BackpressureHandlerTest {

    @Mock ChannelHandlerContext ctx;
    @Mock Channel channel;
    @Mock Attribute<ChannelAttrs> holderAttr;

    @Test
    void handler_isSingleton() throws Exception {
        // INSTANCE 是框架共享实例（@Sharable），且类为 final：验证返回同一实例 + final 类
        assertSame(BackpressureHandler.INSTANCE, BackpressureHandler.INSTANCE);
        assertTrue(java.lang.reflect.Modifier.isFinal(BackpressureHandler.class.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isStatic(BackpressureHandler.class.getField("INSTANCE").getModifiers()));
    }

    /** 真实持有者 + 受控 channel：{@code connCtx == null} ⟹ 视作无连接上下文。 */
    private void bindChannel(ConnectionContext conn) {
        ChannelAttrs attrs = newAttrs();
        attrs.connCtx = conn;
        when(ctx.channel()).thenReturn(channel);
        doReturn(holderAttr).when(channel).attr(any());
        when(holderAttr.get()).thenReturn(attrs);
    }

    /**
     * 构造一个真实持有者（其构造器非公开，故经一个临时 EmbeddedChannel 走 {@code of()} 正规入口）。
     * 临时 channel 随即释放；持有者本身是普通对象，仍可继续使用。
     */
    private static ChannelAttrs newAttrs() {
        EmbeddedChannel tmp = new EmbeddedChannel();
        ChannelAttrs attrs = ChannelAttrs.of(tmp);
        tmp.finishAndReleaseAll();
        return attrs;
    }

    @Test
    void channelWritabilityChanged_noConnectionContext_firesEventOnly() {
        bindChannel(null);

        BackpressureHandler.INSTANCE.channelWritabilityChanged(ctx);

        verify(ctx).fireChannelWritabilityChanged();
    }

    @Test
    void channelWritabilityChanged_falseToTrue_runsCallback() {
        ConnectionContext conn = new ConnectionContext();
        Runnable callback = mock(Runnable.class);
        conn.setOnWritable(callback);
        conn.updateWritable(false); // last = false (was not writable)
        bindChannel(conn);
        when(channel.isWritable()).thenReturn(true);

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
        bindChannel(conn);
        when(channel.isWritable()).thenReturn(true);

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
        bindChannel(conn);
        when(channel.isWritable()).thenReturn(false);

        BackpressureHandler.INSTANCE.channelWritabilityChanged(ctx);

        verify(callback, never()).run();
        verify(ctx).fireChannelWritabilityChanged();
    }

    @Test
    void channelWritabilityChanged_updatesWritableState() {
        ConnectionContext conn = new ConnectionContext();
        conn.updateWritable(false);
        bindChannel(conn);
        when(channel.isWritable()).thenReturn(true);

        BackpressureHandler.INSTANCE.channelWritabilityChanged(ctx);

        assertTrue(conn.lastWritable());
    }
}
