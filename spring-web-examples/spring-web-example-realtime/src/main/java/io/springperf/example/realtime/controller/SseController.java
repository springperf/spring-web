package io.springperf.example.realtime.controller;

import io.springperf.web.core.async.stream.SseEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
public class SseController {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 客户端订阅 SSE 事件流
     * curl -N http://localhost:8082/sse/subscribe?clientId=foo
     */
    @GetMapping("/sse/subscribe")
    public SseEmitter subscribe(@RequestParam String clientId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(clientId, emitter);
        emitter.onCompletion(() -> emitters.remove(clientId));
        emitter.onTimeout(() -> emitters.remove(clientId));
        log.info("SSE client subscribed: {}", clientId);
        return emitter;
    }

    /**
     * 向指定客户端推送事件
     * curl "http://localhost:8082/sse/send?clientId=foo&data=hello"
     */
    @GetMapping("/sse/send")
    public String send(@RequestParam String clientId, @RequestParam String data) throws IOException {
        SseEmitter emitter = emitters.get(clientId);
        if (emitter == null) {
            return "client not found: " + clientId;
        }
        emitter.send(ServerSentEvent.builder().event("message").data(data).build());
        log.info("SSE sent to {}: {}", clientId, data);
        return "ok";
    }

    /**
     * 广播给所有客户端
     * curl "http://localhost:8082/sse/broadcast?data=hello"
     */
    @GetMapping("/sse/broadcast")
    public String broadcast(@RequestParam String data) throws IOException {
        ServerSentEvent<Object> event = ServerSentEvent.builder().event("broadcast").data(data).build();
        int success = 0;
        for (Map.Entry<String, SseEmitter> entry : emitters.entrySet()) {
            try {
                entry.getValue().send(event);
                success++;
            } catch (IOException e) {
                log.warn("SSE broadcast failed to client {}, removing: {}", entry.getKey(), e.getMessage());
                emitters.remove(entry.getKey());
            }
        }
        log.info("SSE broadcast to {}/{} clients: {}", success, emitters.size(), data);
        return "ok, sent to " + success + " clients";
    }
}