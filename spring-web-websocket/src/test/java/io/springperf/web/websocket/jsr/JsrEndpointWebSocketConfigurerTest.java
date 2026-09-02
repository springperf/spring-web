package io.springperf.web.websocket.jsr;

import io.springperf.web.websocket.WebSocketHandlerRegistry;
import jakarta.websocket.server.ServerEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;

import static org.junit.jupiter.api.Assertions.*;

class JsrEndpointWebSocketConfigurerTest {

    @ServerEndpoint("/ws/echo")
    public static class EchoEndpoint {}

    @Configuration
    static class TestConfig {
        @Bean
        public EchoEndpoint echoEndpoint() {
            return new EchoEndpoint();
        }
    }

    @Test
    void registerWebSocketHandlers_registersScannedEndpoints() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        JsrEndpointWebSocketConfigurer configurer = new JsrEndpointWebSocketConfigurer(ctx);
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        configurer.registerWebSocketHandlers(registry);
        assertFalse(registry.isEmpty());
        WebSocketHandler handler = registry.getRegistration("/ws/echo").getHandler();
        assertNotNull(handler);
        assertTrue(handler instanceof JsrEndpointWebSocketHandler);
        assertTrue(configurer.getRegisteredPaths().contains("/ws/echo"));
        ctx.close();
    }

    @Test
    void getRegisteredPaths_beforeRegister_empty() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        JsrEndpointWebSocketConfigurer configurer = new JsrEndpointWebSocketConfigurer(ctx);
        assertTrue(configurer.getRegisteredPaths().isEmpty());
        ctx.close();
    }

    @Test
    void register_noEndpoints_keepsRegistryEmpty() {
        // 无 @ServerEndpoint Bean 的上下文
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.refresh();
        JsrEndpointWebSocketConfigurer configurer = new JsrEndpointWebSocketConfigurer(ctx);
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        configurer.registerWebSocketHandlers(registry);
        assertTrue(registry.isEmpty());
        ctx.close();
    }
}
