package io.springperf.web.websocket.jsr;

import io.springperf.web.websocket.WebSocketConfigurer;
import io.springperf.web.websocket.WebSocketHandlerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 将扫描到的 JSR-356 {@code @ServerEndpoint} 端点注册为框架 {@link io.springperf.web.websocket.WebSocketHandlerRegistry}
 * 处理器。
 *
 * <p>实现 {@link WebSocketConfigurer} SPI，由现有
 * {@link io.springperf.web.websocket.config.WebSocketAutoConfiguration} 收集并调用，
 * 从而复用现有 Netty 握手/帧路由管线，无需改动核心框架。</p>
 *
 * @author huangcanda
 * @since 3.5.6
 */
@Slf4j
public class JsrEndpointWebSocketConfigurer implements WebSocketConfigurer {

    private final ApplicationContext applicationContext;
    private final JsrWebSocketContainer container;

    /** 已注册的端点路径（避免重复注册），与 WebSocketHandlerRegistry 一一对应。 */
    private volatile List<String> registeredPaths;

    public JsrEndpointWebSocketConfigurer(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.container = new JsrWebSocketContainer();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        List<Class<?>> endpointClasses = new JsrEndpointScanner(applicationContext).scan();
        List<String> paths = new ArrayList<>(endpointClasses.size());
        for (Class<?> endpointClass : endpointClasses) {
            JsrEndpointMetadata metadata = new JsrEndpointMetadata(endpointClass);
            String path = metadata.getPath();
            JsrEndpointWebSocketHandler handler = new JsrEndpointWebSocketHandler(metadata, container);
            registry.addHandler(handler, path);
            paths.add(path);
            log.info("Registered JSR-356 WebSocket endpoint {} -> {}", endpointClass.getName(), path);
        }
        this.registeredPaths = paths;
    }

    /** 本次扫描注册的端点路径（供测试与诊断）。 */
    public List<String> getRegisteredPaths() {
        List<String> paths = registeredPaths;
        return paths != null ? paths : java.util.Collections.emptyList();
    }
}
