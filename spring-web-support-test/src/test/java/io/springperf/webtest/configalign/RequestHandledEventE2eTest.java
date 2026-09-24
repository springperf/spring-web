package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ApplicationListenerMethodAdapter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.ServletRequestHandledEvent;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code spring.mvc.publish-request-handled-events=true} E2E：请求完成后 {@link ServletRequestHandledEvent}
 * 真实发布（监听器计数与事件字段可读）。
 * <p>
 * 默认关闭（对齐 Boot）由 {@code DispatcherHandlerRequestHandledEventTest} 单元测试覆盖； 本类验证开启后的端到端发布链路。
 * </p>
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        RequestHandledEventE2eTest.EventConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "spring.mvc.publish-request-handled-events=true" })
class RequestHandledEventE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    private String get(String path) throws Exception {
        okhttp3.Response resp = CLIENT
                .newCall(new okhttp3.Request.Builder().url("http://localhost:" + port + path).build()).execute();
        try {
            assertEquals(200, resp.code());
            return resp.body().string().trim();
        } finally {
            resp.close();
        }
    }

    @Test
    void eventPublishedOnRequestCompletion() throws Exception {
        int before = Integer.parseInt(get("/e2e-event/count"));
        get("/e2e-event/hit");
        int after = Integer.parseInt(get("/e2e-event/count"));
        // 两次探测请求自身也会各产生一个事件，故至少 +2
        assertTrue(after >= before + 2,
                "开启 publish-request-handled-events 后每个请求都应发布事件（before=" + before + ", after=" + after + "）");
    }

    @Test
    void eventCarriesRequestMetadata() throws Exception {
        get("/e2e-event/hit");
        String lastSeen = get("/e2e-event/last");
        assertTrue(lastSeen.contains("GET"), "事件应记录请求方法，实际 " + lastSeen);
        assertTrue(lastSeen.contains("/e2e-event/"), "事件应记录请求 URL，实际 " + lastSeen);
    }

    @TestConfiguration
    static class EventConfig {

        /** 记录最近一次请求完成事件（Servlet 语义事件，Boot 同名属性对齐）。 */
        static final AtomicInteger COUNT = new AtomicInteger();
        static volatile String LAST_METHOD;
        static volatile String LAST_URL;

        @Bean
        ApplicationListener<ServletRequestHandledEvent> requestHandledListener() {
            return event -> {
                COUNT.incrementAndGet();
                LAST_METHOD = event.getMethod();
                LAST_URL = event.getRequestUrl();
            };
        }

        @Bean
        EventProbeController eventProbeController() {
            return new EventProbeController();
        }
    }

    @RestController
    static class EventProbeController {

        @GetMapping("/e2e-event/hit")
        public String hit() {
            return "ok";
        }

        @GetMapping("/e2e-event/count")
        public String count() {
            return String.valueOf(EventConfig.COUNT.get());
        }

        @GetMapping("/e2e-event/last")
        public String last() {
            return String.valueOf(EventConfig.LAST_METHOD) + " " + String.valueOf(EventConfig.LAST_URL);
        }
    }
}
