package io.springperf.web.websocket.server;

import io.springperf.web.websocket.WebSocketHandlerRegistration;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.WebSocketHandler;

import java.util.Map;

/**
 * 一次升级请求的路径匹配结果：命中的 handler、其 per-path 注册配置（可能为 {@code null}）与路径变量。
 * <p>
 * 由 {@link WebSocketRoutingHandler#resolveHandler} 产出、交给 {@link WebSocketHandshakeHandler} 消费， 是「路由」与「握手」两个阶段之间唯一的交接物。
 * </p>
 *
 * @author huangcanda
 *
 * @since 3.5.8
 */
final class WebSocketPathMatch {

    /** 请求路径（已去掉 query string）。 */
    final String path;
    final WebSocketHandler handler;
    @Nullable
    final WebSocketHandlerRegistration registration;
    /** 路径变量（如 {@code {roomId}} → {@code roomId=123}）；无变量时为空 Map。 */
    final Map<String, String> uriVariables;

    WebSocketPathMatch(String path, WebSocketHandler handler, @Nullable WebSocketHandlerRegistration registration,
            Map<String, String> uriVariables) {
        this.path = path;
        this.handler = handler;
        this.registration = registration;
        this.uriVariables = uriVariables;
    }
}
