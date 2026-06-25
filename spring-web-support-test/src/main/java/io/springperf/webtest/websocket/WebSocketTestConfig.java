package io.springperf.webtest.websocket;

import io.springperf.web.websocket.WebSocketConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WebSocketTestConfig {

    @Bean
    public WebSocketEchoHandler webSocketEchoHandler() {
        return new WebSocketEchoHandler();
    }

    @Bean
    public WebSocketPathEchoHandler webSocketPathEchoHandler() {
        return new WebSocketPathEchoHandler();
    }

    @Bean
    public WebSocketConfigurer webSocketConfigurer(
            WebSocketEchoHandler echoHandler,
            WebSocketPathEchoHandler pathHandler) {
        return registry -> {
            registry.addHandler(echoHandler, "/ws/echo");
            registry.addHandler(pathHandler, "/ws/room/{roomId}");
        };
    }
}
