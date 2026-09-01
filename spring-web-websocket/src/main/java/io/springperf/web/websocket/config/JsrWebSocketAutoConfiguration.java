package io.springperf.web.websocket.config;

import io.springperf.web.websocket.jsr.JsrEndpointWebSocketConfigurer;
import jakarta.websocket.server.ServerEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;

/**
 * JSR-356（Servlet 规范 WebSocket，{@code @ServerEndpoint} 注解）桥接自动配置。
 *
 * <p>当 classpath 同时存在 {@link ServerEndpoint}（jakarta.websocket-api）与
 * 框架 WebSocket 支持（{@link WebSocketHandler}）时激活：</p>
 * <ol>
 *   <li>创建 {@link JsrEndpointWebSocketConfigurer}（实现 {@code WebSocketConfigurer} SPI）</li>
 *   <li>由现有 {@link WebSocketAutoConfiguration} 收集，扫描 {@code @ServerEndpoint} 端点</li>
 *   <li>将端点翻译为 {@code WebSocketHandler} 注册进 {@code WebSocketHandlerRegistry}，复用 Netty 管线</li>
 * </ol>
 *
 * @author huangcanda
 * @since 3.2.5
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({ServerEndpoint.class, WebSocketHandler.class})
public class JsrWebSocketAutoConfiguration {

    @Bean
    public JsrEndpointWebSocketConfigurer jsrEndpointWebSocketConfigurer(ApplicationContext applicationContext) {
        return new JsrEndpointWebSocketConfigurer(applicationContext);
    }
}
