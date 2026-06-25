package io.springperf.web.websocket;

/**
 * 用户通过实现此接口注册 WebSocket 端点。
 *
 * <p>示例：
 * <pre>{@code
 * @Configuration
 * public class MyWebSocketConfig implements WebSocketConfigurer {
 *     public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
 *         registry.addHandler(myHandler(), "/ws/echo");
 *     }
 * }
 * }</pre>
 *
 * @author huangcanda
 * @since 1.0.4
 */
@FunctionalInterface
public interface WebSocketConfigurer {

    /**
     * 注册 WebSocket 处理器到指定路径。
     */
    void registerWebSocketHandlers(WebSocketHandlerRegistry registry);
}
