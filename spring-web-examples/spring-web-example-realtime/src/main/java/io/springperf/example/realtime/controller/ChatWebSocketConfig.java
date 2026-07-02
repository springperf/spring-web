package io.springperf.example.realtime.controller;

import io.springperf.web.websocket.WebSocketConfigurer;
import io.springperf.web.websocket.WebSocketHandlerRegistry;
import org.springframework.stereotype.Component;

@Component
public class ChatWebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new ChatWebSocketHandler(), "/ws/chat");
    }
}