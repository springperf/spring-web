package io.springperf.web.websocket.config;

import io.springperf.web.server.PipelineCustomizer;
import io.springperf.web.websocket.WebSocketConfigurer;
import io.springperf.web.websocket.WebSocketHandlerRegistry;
import io.springperf.web.websocket.server.WebSocketRoutingHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;

import java.util.List;

/**
 * WebSocket 模块自动配置。
 *
 * <p>当 classpath 中存在 {@link WebSocketHandler} 时激活：</p>
 * <ol>
 *   <li>创建 {@link WebSocketHandlerRegistry} 供用户通过 {@link WebSocketConfigurer} 注册端点</li>
 *   <li>收集 {@link WebSocketConfigurer} 的实现，构建路径 → handler 映射</li>
 *   <li>创建 {@link PipelineCustomizer} 将 {@link WebSocketRoutingHandler} 注入 Netty pipeline</li>
 * </ol>
 *
 * @author huangcanda
 * @since 1.0.4
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({WebSocketHandler.class, PipelineCustomizer.class})
public class WebSocketAutoConfiguration {

    @Bean
    public WebSocketHandlerRegistry webSocketHandlerRegistry() {
        return new WebSocketHandlerRegistry();
    }

    @Bean
    public PipelineCustomizer pipelineCustomizer() {
        return new PipelineCustomizer();
    }

    @Bean
    public WebSocketRoutingHandler webSocketRoutingHandler(
            WebSocketHandlerRegistry registry,
            PipelineCustomizer customizer,
            List<WebSocketConfigurer> configurers) {

        // 让用户通过 WebSocketConfigurer 注册 handler
        for (WebSocketConfigurer configurer : configurers) {
            configurer.registerWebSocketHandlers(registry);
        }

        // 始终创建 handler（即使无端点也不影响正常 HTTP 处理）
        WebSocketRoutingHandler handler = new WebSocketRoutingHandler(
                registry.getHandlerMap(), registry.getSubProtocols(), registry.isAllowExtensions(),
                registry.getAllowedOrigins(), registry.getIdleTimeout(), registry.getHeartbeatInterval(),
                registry);
        if (!registry.isEmpty()) {
            customizer.addAfterAggregator(handler);
        }
        return handler;
    }
}
