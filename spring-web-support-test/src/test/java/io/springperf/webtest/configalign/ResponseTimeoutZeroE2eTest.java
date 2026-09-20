package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code server.http.timeout=0} 语义 E2E：按框架既有约定（{@code max-connections}、
 * {@code keep-alive-timeout} 等均以 {@code ≤0} 表示「不限制/禁用」），0 应表示**关闭响应超时**，
 * 而不是「立即超时」。否则任何把该值设为 0 的部署会全量 504。
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, ResponseTimeoutZeroE2eTest.ZeroConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "server.http.timeout=0"
        })
class ResponseTimeoutZeroE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(15))
            .build();

    @LocalServerPort
    int port;

    private Response get(String path) throws Exception {
        return CLIENT.newCall(new Request.Builder()
                .url("http://localhost:" + port + path).build()).execute();
    }

    @Test
    void zeroTimeout_fastRequest_served() throws Exception {
        Response resp = get("/e2e-timeout0/ok");
        try {
            assertEquals(200, resp.code(),
                    "server.http.timeout=0 应表示关闭响应超时（不得立即 504），实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void zeroTimeout_slowRequest_served() throws Exception {
        Response resp = get("/e2e-timeout0/slow?ms=1500");
        try {
            assertEquals(200, resp.code(),
                    "关闭响应超时后，任意耗时请求都不应被超时中断，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class ZeroConfig {
        @Bean
        ZeroController zeroController() {
            return new ZeroController();
        }
    }

    @RestController
    static class ZeroController {

        @GetMapping("/e2e-timeout0/ok")
        public String ok() {
            return "ok-body";
        }

        @GetMapping("/e2e-timeout0/slow")
        public String slow(@RequestParam long ms) throws InterruptedException {
            Thread.sleep(ms);
            return "slow-done";
        }
    }
}
