package io.springperf.webtest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归 #11：{@code WebServerInitializedEvent} 必须在服务器启动阶段发布，早于 {@code ApplicationReadyEvent}，
 * 且携带真实绑定端口（Spring Cloud 服务注册依赖此时机与端口）。
 */
@SpringBootTest(classes = { TestApplication.class, WebServerInitializedEventOrderTest.RecorderConfig.class },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebServerInitializedEventOrderTest {

    @Autowired
    Recorder recorder;

    @Test
    void webServerInitializedEvent_isPublishedBeforeApplicationReady() {
        List<String> events = recorder.events;
        assertEquals(2, events.size(), "应恰好记录一次初始化事件与一次 ready 事件: " + events);
        assertEquals("initialized", events.get(0), "WebServerInitializedEvent 必须先发布");
        assertEquals("ready", events.get(1));
        assertTrue(recorder.port > 0, "事件必须携带真实绑定端口");
    }

    static class Recorder {
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile int port;
    }

    @TestConfiguration
    static class RecorderConfig {

        @Bean
        Recorder webServerEventRecorder() {
            return new Recorder();
        }

        @Bean
        ApplicationListener<WebServerInitializedEvent> webServerInitializedRecorder(Recorder recorder) {
            return event -> {
                recorder.port = event.getWebServer().getPort();
                recorder.events.add("initialized");
            };
        }

        @Bean
        ApplicationListener<ApplicationReadyEvent> applicationReadyRecorder(Recorder recorder) {
            return event -> recorder.events.add("ready");
        }
    }
}
