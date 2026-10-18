package io.springperf.web.websocket.config;

import io.springperf.web.websocket.WebSocketHandlerRegistry;
import io.springperf.web.websocket.jsr.JsrEndpointWebSocketConfigurer;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

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
    }
}
