package io.springperf.webtest.proxy;

import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2 E2E 娴嬭瘯锛氬熀纭€璁炬柦杈圭晫鏉′欢銆?
 * <p>
 * 浣跨敤鐙珛 Spring 涓婁笅鏂囷紙闅忔満绔彛锛夛紝
 * 娴嬭瘯 max-content-length 瓒呴檺鎷掔粷绛夊満鏅€?
 */
@SpringBootTest(
        classes = ProxyE2eApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=",
                "server.http.max-content-length=100"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LimitsE2eTest {

    @LocalServerPort
    private int serverPort;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private String url(String path) {
        return "http://localhost:" + serverPort + path;
    }

    private String baseUrl() {
        return url("");
    }

    @Test
    void postLargeBody_exceedsMaxContentLength_returns413() throws Exception {
        // 鏋勯€犺秴杩?max-content-length 鐨勮姹備綋锛?00 瀛楄妭 > 100 闄愬埗锛?
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append('x');
        }
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-api/save?id=test")
                .post(RequestBody.create(JSON, sb.toString()))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(413, resp.code(),
                    "Request exceeding max-content-length should return 413");
        }
    }

    @Test
    void postSmallBody_withinMaxContentLength_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-api/save?id=test")
                .post(RequestBody.create(JSON, "\"small\""))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code(),
                    "Request within max-content-length should succeed");
        }
    }
}