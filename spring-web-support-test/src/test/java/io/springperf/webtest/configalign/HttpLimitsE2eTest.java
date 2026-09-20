package io.springperf.webtest.configalign;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 管线防护族 E2E（真实 Netty 管线，全部在进入 Servlet 桥之前拦截）：
 * server.http.max-content-length → 413、server.max-parameter-count → 400、
 * server.http.multipart.max-part-count / max-part-header-size → 400。
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class,
                HttpLimitsE2eTest.LimitsConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "server.http.max-content-length=1024",
                "server.max-parameter-count=5",
                "server.http.multipart.max-part-count=2",
                "server.http.multipart.max-part-header-size=200"
        })
class HttpLimitsE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private String url(String path) {
        // ConfigAlignTestApp：context-path=/（隔离宿主应用的自定义异常解析器——
        // 其会吞掉 ParameterLimitExceededException 破坏 400 语义验证）
        return "http://localhost:" + port + path;
    }

    // ==================== server.http.max-content-length ====================

    @Test
    void bodyWithinLimit_ok() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-limits/body"))
                .post(okhttp3.RequestBody.create(new byte[100],
                        MediaType.parse("application/octet-stream")))
                .build()).execute();
        try {
            assertEquals(200, resp.code(), "100B < 1KB 应正常到达控制器，实际 "
                    + resp.code() + " body=" + resp.body().string());
        } finally {
            resp.close();
        }
    }

    @Test
    void paramsExceededLimit_returns400() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-limits/params?p1=1&p2=2&p3=3&p4=4&p5=5&p6=6")).build()).execute();
        try {
            assertEquals(400, resp.code(), "6 个参数 > 上限 5 应 400，实际 "
                    + resp.code() + " body=" + resp.body().string());
        } finally {
            resp.close();
        }
    }

    @Test
    void bodyExceedsLimit_returns413() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-limits/body"))
                .post(okhttp3.RequestBody.create(new byte[8 * 1024],
                        MediaType.parse("application/octet-stream")))
                .build()).execute();
        try {
            assertEquals(413, resp.code(), "8KB > 1KB 上限应管线级 413，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    // ==================== server.max-parameter-count ====================

    @Test
    void paramsWithinLimit_ok() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-limits/params?p1=1&p2=2&p3=3&p4=4")).build()).execute();
        try {
            assertEquals(200, resp.code(), "4 个参数 < 上限 5 应正常，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    // ==================== server.http.multipart.max-part-count ====================

    @Test
    void partCountWithinLimit_ok() throws Exception {
        okhttp3.Response resp = upload(2, 10);
        try {
            assertEquals(200, resp.code(), "2 个 part = 上限 2 应正常，实际 "
                    + resp.code() + " body=" + resp.body().string());
        } finally {
            resp.close();
        }
    }

    @Test
    void partCountExceededLimit_returns400() throws Exception {
        okhttp3.Response resp = upload(3, 10);
        try {
            assertEquals(400, resp.code(), "3 个 part > 上限 2 应 400，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    // ==================== server.http.multipart.max-part-header-size ====================

    @Test
    void partHeaderExceededLimit_returns400() throws Exception {
        // filename 150 字符使 part header 区超过 100 字节上限
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-limits/parts"))
                .post(new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", "f".repeat(150),
                                okhttp3.RequestBody.create(new byte[10],
                                        MediaType.parse("application/octet-stream")))
                        .build())
                .build()).execute();
        try {
            assertEquals(400, resp.code(), "part header 超 100B 上限应 400，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    private okhttp3.Response upload(int fileCount, int bytesPerFile) throws Exception {
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        for (int i = 0; i < fileCount; i++) {
            builder.addFormDataPart("file" + i, "f" + i + ".bin",
                    okhttp3.RequestBody.create(new byte[bytesPerFile],
                            MediaType.parse("application/octet-stream")));
        }
        return CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-limits/parts"))
                .post(builder.build())
                .build()).execute();
    }

    @TestConfiguration
    static class LimitsConfig {
        @Bean
        LimitsController limitsController() {
            return new LimitsController();
        }
    }

    @RestController
    static class LimitsController {

        @PostMapping("/e2e-limits/body")
        public Map<String, Object> body(HttpServletRequest request) throws Exception {
            byte[] buf = new byte[2048];
            int total = 0;
            try (java.io.InputStream in = request.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                }
            }
            return Map.of("size", total);
        }

        @GetMapping("/e2e-limits/params")
        public Map<String, Object> params(HttpServletRequest request) {
            return Map.of("count", request.getParameterMap().size());
        }

        @PostMapping("/e2e-limits/parts")
        public Map<String, Object> parts(HttpServletRequest request) throws Exception {
            return Map.of("count", request.getParts().size());
        }
    }
}
