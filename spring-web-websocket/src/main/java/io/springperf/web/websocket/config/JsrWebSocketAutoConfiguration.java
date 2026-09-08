package io.springperf.web.websocket.config;

import io.springperf.web.websocket.jsr.JsrEndpointWebSocketConfigurer;
import io.springperf.web.websocket.jsr.JsrEndpointWebSocketConfigurer;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.server.ServerEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;

/**
 * JSR-356锛圫ervlet 瑙勮寖 WebSocket锛寋@code @ServerEndpoint} 娉ㄨВ锛夋ˉ鎺ヨ嚜鍔ㄩ厤缃€?
 *
 * <p>褰?classpath 鍚屾椂瀛樺湪 {@link ServerEndpoint}锛坖akarta.websocket-api锛変笌
 * 妗嗘灦 WebSocket 鏀寔锛坽@link WebSocketHandler}锛夋椂婵€娲伙細</p>
 * <ol>
 *   <li>鍒涘缓 {@link JsrEndpointWebSocketConfigurer}锛堝疄鐜?{@code WebSocketConfigurer} SPI锛?/li>
 *   <li>鐢辩幇鏈?{@link WebSocketAutoConfiguration} 鏀堕泦锛屾壂鎻?{@code @ServerEndpoint} 绔偣</li>
 *   <li>灏嗙鐐圭炕璇戜负 {@code WebSocketHandler} 娉ㄥ唽杩?{@code WebSocketHandlerRegistry}锛屽鐢?Netty 绠＄嚎</li>
 * </ol>
 *
 * @author huangcanda
 * @since 3.5.6
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({ServerEndpoint.class, WebSocketHandler.class, ClientEndpointConfig.class})
public class JsrWebSocketAutoConfiguration {

    @Bean
    public JsrEndpointWebSocketConfigurer jsrEndpointWebSocketConfigurer(ApplicationContext applicationContext) {
        return new JsrEndpointWebSocketConfigurer(applicationContext);
    }
}
