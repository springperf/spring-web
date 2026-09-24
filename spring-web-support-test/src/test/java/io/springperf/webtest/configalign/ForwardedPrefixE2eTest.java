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
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code X-Forwarded-Prefix} 语义 E2E：网关剥离前缀转发时，应用侧 {@code getContextPath()} 应反映外部前缀（由 Spring 的
 * {@code ForwardedHeaderFilter} 提供）， 而容器级绝对 URL（如 sendRedirect 的 Location）不携带该前缀——前缀由网关补齐。
 * <p>
 * {@code forward-headers-strategy=NONE} 时该头必须被忽略（见 ForwardHeadersNoneE2eTest 的 scheme 覆盖）。
 * </p>
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        ForwardedPrefixE2eTest.PrefixConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "server.forward-headers-strategy=FRAMEWORK" })
class ForwardedPrefixE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).followRedirects(false).build();

    @LocalServerPort
    int port;

    private String field(String json, String name) {
        int idx = json.indexOf("\"" + name + "\":\"");
        if (idx < 0) {
            return null;
        }
        int start = idx + name.length() + 4;
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    private Response get(String path, String... headers) throws Exception {
        Request.Builder b = new Request.Builder().url("http://localhost:" + port + path).get();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return CLIENT.newCall(b.build()).execute();
    }

    @Test
    void contextPath_reflectsForwardedPrefix() throws Exception {
        Response resp = get("/e2e-prefix/info", "X-Forwarded-Prefix", "/gateway");
        try {
            assertEquals(200, resp.code());
            assertEquals("/gateway", field(resp.body().string(), "contextPath"),
                    "X-Forwarded-Prefix 应反映到 getContextPath()");
        } finally {
            resp.close();
        }
    }

    @Test
    void contextPath_withoutPrefixHeader_staysConfigured() throws Exception {
        Response resp = get("/e2e-prefix/info");
        try {
            assertEquals(200, resp.code());
            assertEquals("", field(resp.body().string(), "contextPath"),
                    "未带 X-Forwarded-Prefix 时保持配置的 context-path（/ 归一化为空串）");
        } finally {
            resp.close();
        }
    }

    @Test
    void redirectLocation_doesNotIncludeForwardedPrefix() throws Exception {
        // 前缀由网关补齐；容器级绝对 URL 基于配置的 context-path 构造，避免前缀重复
        Response resp = get("/e2e-prefix/redirect", "X-Forwarded-Prefix", "/gateway");
        try {
            assertEquals(302, resp.code());
            assertEquals("http://localhost:" + port + "/cb", resp.header("Location"),
                    "Location 不应重复叠加网关前缀，实际 " + resp.header("Location"));
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class PrefixConfig {
        @Bean
        PrefixController prefixController() {
            return new PrefixController();
        }
    }

    @RestController
    static class PrefixController {

        @GetMapping("/e2e-prefix/info")
        public Map<String, String> info(HttpServletRequest request) {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("contextPath", request.getContextPath());
            out.put("requestUri", request.getRequestURI());
            return out;
        }

        @GetMapping("/e2e-prefix/redirect")
        public String redirect(HttpServletResponse response) throws IOException {
            response.sendRedirect("/cb");
            return null;
        }
    }
}
