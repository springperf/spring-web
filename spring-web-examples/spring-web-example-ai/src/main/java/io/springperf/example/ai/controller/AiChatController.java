package io.springperf.example.ai.controller;

import io.springperf.example.ai.config.AiConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/ai")
public class AiChatController {

    private final ChatClient chatClient;
    private final AiConfig aiConfig;

    public AiChatController(ChatClient.Builder chatClientBuilder, AiConfig aiConfig) {
        this.chatClient = chatClientBuilder.build();
        this.aiConfig = aiConfig;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam("message") String message) {
        return chatClient.prompt().user(message).call().content();
    }

    @GetMapping("/chat/stream")
    public Flux<String> chatStream(@RequestParam("message") String message) {
        return chatClient.prompt().user(message).stream().content();
    }

    @GetMapping("/chat/model")
    public Map<String, String> model() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("baseUrl", aiConfig.getBaseUrl());
        info.put("model", aiConfig.getChat().getOptions().getModel());
        return info;
    }
}