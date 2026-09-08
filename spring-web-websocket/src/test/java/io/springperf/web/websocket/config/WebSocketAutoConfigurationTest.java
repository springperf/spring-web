package io.springperf.web.websocket.config;

import io.springperf.web.server.PipelineCustomizer;
import io.springperf.web.websocket.WebSocketConfigurer;
import io.springperf.web.websocket.WebSocketHandlerRegistry;
import io.springperf.web.websocket.server.WebSocketRoutingHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketAutoConfigurationTest {

    @Test
    void webSocketHandlerRegistry_beanCreated() {
        WebSocketAutoConfiguration config = new WebSocketAutoConfiguration();
        WebSocketHandlerRegistry registry = config.webSocketHandlerRegistry();
        assertNotNull(registry);
        assertTrue(registry.isEmpty());
    }

    @Test
    void pipelineCustomizer_beanCreated() {
        WebSocketAutoConfiguration config = new WebSocketAutoConfiguration();
        assertNotNull(config.pipelineCustomizer());
    }

    @Test
    void webSocketRoutingHandler_noConfigurers_noEndpoints_returnsHandler() {
        WebSocketAutoConfiguration config = new WebSocketAutoConfiguration();
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        PipelineCustomizer customizer = new PipelineCustomizer();
        WebSocketRoutingHandler handler = config.webSocketRoutingHandler(registry, customizer, Arrays.asList());
        assertNotNull(handler);
        // 鏃犵鐐规椂涓嶆敞鍏?customizer
    }

    @Test
    void webSocketRoutingHandler_withConfigurer_registersAndInjects() {
        WebSocketAutoConfiguration config = new WebSocketAutoConfiguration();
        WebSocketHandlerRegistry registry = new WebSocketHandlerRegistry();
        PipelineCustomizer customizer = new PipelineCustomizer();

        WebSocketConfigurer configurer = mock(WebSocketConfigurer.class);
        doAnswer(inv -> {
            WebSocketHandlerRegistry reg = inv.getArgument(0);
            reg.addHandler(new TextWebSocketHandler() {}, "/ws");
            return null;
        }).when(configurer).registerWebSocketHandlers(any(WebSocketHandlerRegistry.class));

        WebSocketRoutingHandler handler = config.webSocketRoutingHandler(registry, customizer, Arrays.asList(configurer));
        assertNotNull(handler);
        assertFalse(registry.isEmpty());
        verify(configurer).registerWebSocketHandlers(registry);
        assertFalse(customizer.getAfterAggregatorHandlers().isEmpty(),
                "鏈夌鐐规椂搴旀敞鍏?WebSocketRoutingHandler 鍒?afterAggregator");
    }
}
