package io.springperf.web.websocket.config;

import io.springperf.web.websocket.WebSocketHandlerRegistry;
import io.springperf.web.websocket.jsr.JsrEndpointWebSocketConfigurer;
import javax.websocket.server.ServerEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSR-356 桥接自动配置的装配测试：这是唯一一个此前零覆盖的类，装配失败会让 {@code @ServerEndpoint} 静默不生效。
 */
class JsrWebSocketAutoConfigurationTest {

    @Test
    void jsrEndpointWebSocketConfigurer_beanUsesApplicationContext() {
        JsrWebSocketAutoConfiguration config = new JsrWebSocketAutoConfiguration();
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();

        JsrEndpointWebSocketConfigurer configurer = config.jsrEndpointWebSocketConfigurer(context);

        assertNotNull(configurer, "JSR 桥接配置必须暴露 WebSocketConfigurer");
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        configurer.registerWebSocketHandlers(registry);
        assertTrue(registry.isEmpty(), "容器里没有 @ServerEndpoint bean 时不应注册任何 handler");
        context.close();
    }

    @ServerEndpoint("/ws/auto")
    public static class AutoEndpoint {
    }

    @Test
    void jsrEndpointWebSocketConfigurer_registersServerEndpointBean() {
        JsrWebSocketAutoConfiguration config = new JsrWebSocketAutoConfiguration();
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("autoEndpoint", AutoEndpoint.class);
        context.refresh();

        JsrEndpointWebSocketConfigurer configurer = config.jsrEndpointWebSocketConfigurer(context);
        assertNotNull(configurer, "自动配置必须暴露 JSR 桥接 WebSocketConfigurer");
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        configurer.registerWebSocketHandlers(registry);

        assertFalse(registry.isEmpty(), "容器里有 @ServerEndpoint bean 时应注册 handler，否则端点会静默不生效");
        assertNotNull(registry.getRegistration("/ws/auto"), "应按 @ServerEndpoint 的 value 注册 /ws/auto 端点");
        assertNotNull(registry.getHandlerMap().get("/ws/auto"), "路径映射里应能查到 /ws/auto 对应的 handler");
        context.close();
    }
}
