package io.springperf.example.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.ai.openai")
public class AiConfig {

    private String baseUrl;
    private Chat chat = new Chat();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Chat getChat() {
        return chat;
    }

    public void setChat(Chat chat) {
        this.chat = chat;
    }

    public static class Chat {
        private Options options = new Options();

        public Options getOptions() {
            return options;
        }

        public void setOptions(Options options) {
            this.options = options;
        }
    }

    public static class Options {
        private String model;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}