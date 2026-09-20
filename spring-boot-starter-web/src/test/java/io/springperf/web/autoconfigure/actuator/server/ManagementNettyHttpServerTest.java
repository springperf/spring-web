package io.springperf.web.autoconfigure.actuator.server;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.server.HttpHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagementNettyHttpServerTest {

    @Mock WebContext webContext;
    @Mock HttpHandler handler;

    private ManagementNettyHttpServer createServer(int port) {
        return new ManagementNettyHttpServer(webContext, "/actuator", handler, port, 1048576);
    }

    private WebContext stubConfig() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        lenient().when(webContext.getProps()).thenReturn(props);
        lenient().when(props.getBoolean(PropertiesConstant.HTTP2_ENABLED, false)).thenReturn(false);
        lenient().when(props.get(PropertiesConstant.SERVER_NETTY_TRANSPORT,
                PropertiesConstant.SERVER_NETTY_TRANSPORT_DEFAULT)).thenReturn("nio");
        lenient().when(props.getDurationMillis(PropertiesConstant.HTTP_READ_TIMEOUT,
                PropertiesConstant.HTTP_READ_TIMEOUT_DEFAULT)).thenReturn(30000L);
        lenient().when(props.getInt(PropertiesConstant.HTTP_MAX_INITIAL_LINE_LENGTH)).thenReturn(4096);
        lenient().when(props.getInt(PropertiesConstant.HTTP_MAX_REQUEST_HEADER_SIZE)).thenReturn(8192);
        lenient().when(props.getInt(PropertiesConstant.HTTP_MAX_CHUNK_SIZE)).thenReturn(8192);
        lenient().when(props.getMaxInMemorySize()).thenReturn(4096);
        return webContext;
    }

    @Test
    void constructor_with5Params_createsServer() {
        ManagementNettyHttpServer server = new ManagementNettyHttpServer(
                webContext, "/actuator", handler, 9090, 1048576);

        assertNotNull(server);
    }

    @Test
    void constructor_withSslContext_createsServer() {
        ManagementNettyHttpServer server = new ManagementNettyHttpServer(
                webContext, "/actuator", handler, 9090, 1048576, null);

        assertNotNull(server);
    }

    @Test
    void isRunning_initialState_returnsFalse() {
        ManagementNettyHttpServer server = new ManagementNettyHttpServer(
                webContext, "/actuator", handler, 9090, 1048576);

        assertFalse(server.isRunning());
    }

    @Test
    void getPhase_returnsMaxValue() {
        ManagementNettyHttpServer server = new ManagementNettyHttpServer(
                webContext, "/actuator", handler, 9090, 1048576);

        assertEquals(Integer.MAX_VALUE, server.getPhase());
    }

    @Test
    void stop_withoutStart_doesNotThrow() {
        ManagementNettyHttpServer server = new ManagementNettyHttpServer(
                webContext, "/actuator", handler, 9090, 1048576);

        assertDoesNotThrow(() -> server.stop());
    }

    @Test
    void stop_withoutStart_setsRunningToFalse() {
        ManagementNettyHttpServer server = new ManagementNettyHttpServer(
                webContext, "/actuator", handler, 9090, 1048576);

        server.stop();

        assertFalse(server.isRunning());
    }

    @Test
    void autoStartRequested_returnsTrue() {
        ManagementNettyHttpServer server = new ManagementNettyHttpServer(
                webContext, "/actuator", handler, 9090, 1048576);

        assertTrue(server.isAutoStartup());
    }

    @Test
    void start_usesEphemeralPort_thenStopAndDestroy() throws Exception {
        // 覆盖核心生命周期：start 绑定端口（port=0 → 系统分配）、stop 关闭 accept、destroy 回收 EventLoop
        stubConfig();
        ManagementNettyHttpServer server = createServer(0);

        server.start();
        assertTrue(server.isRunning(), "start() 后应处于运行状态");

        server.stop();
        assertFalse(server.isRunning(), "stop() 后应停止运行");

        server.destroyComponent();
        assertFalse(server.isRunning());
    }

    /**
     * 验证 {@code start()} 将 {@code server.shutdown.grace-period} 解析进 {@code shutdownGraceMillis}
     * （启动期预解析、fail-fast 点）。
     */
    @Test
    void start_resolvesConfiguredGracePeriod() throws Exception {
        ApplicationProperties props = mock(ApplicationProperties.class);
        lenient().when(webContext.getProps()).thenReturn(props);
        lenient().when(props.getBoolean(PropertiesConstant.HTTP2_ENABLED, false)).thenReturn(false);
        lenient().when(props.get(PropertiesConstant.SERVER_NETTY_TRANSPORT,
                PropertiesConstant.SERVER_NETTY_TRANSPORT_DEFAULT)).thenReturn("nio");
        lenient().when(props.getDurationMillis(PropertiesConstant.HTTP_READ_TIMEOUT,
                PropertiesConstant.HTTP_READ_TIMEOUT_DEFAULT)).thenReturn(30000L);
        lenient().when(props.getInt(PropertiesConstant.HTTP_MAX_INITIAL_LINE_LENGTH)).thenReturn(4096);
        lenient().when(props.getInt(PropertiesConstant.HTTP_MAX_REQUEST_HEADER_SIZE)).thenReturn(8192);
        lenient().when(props.getInt(PropertiesConstant.HTTP_MAX_CHUNK_SIZE)).thenReturn(8192);
        lenient().when(props.getMaxInMemorySize()).thenReturn(4096);
        lenient().when(props.getDurationMillis(PropertiesConstant.SERVER_SHUTDOWN_GRACE_PERIOD,
                PropertiesConstant.SERVER_SHUTDOWN_GRACE_PERIOD_DEFAULT)).thenReturn(4242L);

        ManagementNettyHttpServer server = createServer(0);
        server.start();
        try {
            long grace = readLongField(server, "shutdownGraceMillis");
            assertEquals(4242L, grace, "start() 应把配置的 grace-period 解析到 shutdownGraceMillis");
        } finally {
            server.destroyComponent();
        }
    }

    private static long readLongField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getLong(target);
    }
}