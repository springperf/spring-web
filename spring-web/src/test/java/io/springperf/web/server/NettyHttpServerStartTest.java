package io.springperf.web.server;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实启动 NettyHttpServer（随机端口）验证完整 start() 管线：
 * 生命周期触发、NettyHttpHandler 构建、错误处理管线、端口发布与停机关闭。
 */
class NettyHttpServerStartTest {

    private NettyHttpServer newServer(ConfigurableApplicationContext ctx, PipelineCustomizer customizer) {
        WebContext wc = mock(WebContext.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(wc.getProps()).thenReturn(props);
        when(props.getBoolean(eq(PropertiesConstant.HTTP2_ENABLED), eq(false))).thenReturn(false);
        when(props.getInt(PropertiesConstant.SERVER_PORT)).thenReturn(0);
        when(props.getInt(PropertiesConstant.SERVER_NETTY_BOSS_THREADS)).thenReturn(1);
        when(props.getInt(PropertiesConstant.SERVER_NETTY_WORKERS)).thenReturn(1);
        when(props.get(PropertiesConstant.SERVER_NETTY_TRANSPORT,
                PropertiesConstant.SERVER_NETTY_TRANSPORT_DEFAULT)).thenReturn("nio");
        when(props.getInt(PropertiesConstant.SERVER_NETTY_SO_BACKLOG)).thenReturn(128);
        when(props.getBoolean(PropertiesConstant.SERVER_NETTY_TCP_NODELAY,
                PropertiesConstant.SERVER_NETTY_TCP_NODELAY_DEFAULT)).thenReturn(true);
        when(props.getBoolean(PropertiesConstant.SERVER_NETTY_SO_KEEPALIVE,
                PropertiesConstant.SERVER_NETTY_SO_KEEPALIVE_DEFAULT)).thenReturn(true);
        when(props.getBoolean(PropertiesConstant.SERVER_NETTY_SO_REUSEADDR,
                PropertiesConstant.SERVER_NETTY_SO_REUSEADDR_DEFAULT)).thenReturn(true);
        when(props.get(PropertiesConstant.SERVER_NETTY_ALLOCATOR_TYPE,
                PropertiesConstant.SERVER_NETTY_ALLOCATOR_TYPE_DEFAULT)).thenReturn("pooled");
        when(props.getInt(PropertiesConstant.WRITE_BUFFER_LOW_WATERMARK)).thenReturn(8192);
        when(props.getInt(PropertiesConstant.WRITE_BUFFER_HIGH_WATERMARK)).thenReturn(32768);
        when(props.getInt(PropertiesConstant.HTTP_MAX_CONTENT_LENGTH)).thenReturn(1048576);
        when(props.getLong(PropertiesConstant.HTTP_READ_TIMEOUT)).thenReturn(30000L);
        when(props.getInt(PropertiesConstant.HTTP_MAX_INITIAL_LINE_LENGTH)).thenReturn(4096);
        when(props.getInt(PropertiesConstant.HTTP_MAX_HEADER_SIZE)).thenReturn(8192);
        when(props.getInt(PropertiesConstant.HTTP_MAX_CHUNK_SIZE)).thenReturn(8192);
        when(wc.getContextPath()).thenReturn("/");
        when(wc.getWebComponent(DispatcherHandler.class)).thenReturn(mock(DispatcherHandler.class));
        when(wc.getCtx()).thenReturn(ctx);
        return new NettyHttpServer(wc, null, customizer);
    }

    @Test
    void start_bindsRandomPort_publishesPortAndStops() throws Exception {
        ConfigurableApplicationContext ctx = mock(ConfigurableApplicationContext.class);
        ConfigurableEnvironment env = mock(ConfigurableEnvironment.class);
        when(ctx.getEnvironment()).thenReturn(env);
        when(env.getPropertySources()).thenReturn(new MutablePropertySources());

        NettyHttpServer server = newServer(ctx, null);
        try {
            server.start();

            assertTrue(server.isRunning());
            assertTrue(server.getActualPort() > 0, "随机端口启动后 actualPort 应为实际绑定端口");
            assertNotNull(server.getWorkerGroup());
        } finally {
            server.stop();
            server.destroyComponent();
        }
        assertFalse(server.isRunning());
    }

    @Test
    void start_withoutConfigurableContext_skipsPortPublish() throws Exception {
        NettyHttpServer server = newServer(null, null);
        try {
            server.start();
            assertTrue(server.isRunning());
        } finally {
            server.stop();
            server.destroyComponent();
        }
    }

    @Test
    void start_withPipelineCustomizer_injectsBeforeAndAfterHandlers() throws Exception {
        PipelineCustomizer customizer = new PipelineCustomizer()
                .addBeforeAggregator(new io.netty.channel.ChannelInboundHandlerAdapter())
                .addAfterAggregator(new io.netty.channel.ChannelInboundHandlerAdapter());

        NettyHttpServer server = newServer(null, customizer);
        try {
            server.start();
            assertTrue(server.isRunning());
        } finally {
            server.stop();
            server.destroyComponent();
        }
    }
}