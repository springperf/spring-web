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
 * 反向代理场景 E2E（{@code server.forward-headers-strategy=FRAMEWORK}）：
 * 应用位于 TLS 终结代理之后时，重定向与 {@code getRequestURL()} 必须反映**外部** scheme/host/port，
 * 而不是内部监听地址——否则 Location 会带上内部端口或内网主机名，客户端直接跳错。
 *
 * <p>同时覆盖 {@code Forwarded} / {@code X-Forwarded-Proto}（scheme）与
 * {@code X-Forwarded-Host} / {@code X-Forwarded-Port}（host/port）。</p>
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, ForwardedRedirectE2eTest.ForwardedConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "server.forward-headers-strategy=FRAMEWORK"
        })
class ForwardedRedirectE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .followRedirects(false)
            .build();

    @LocalServerPort
    int port;

    private Response get(String path, String... headers) throws Exception {
        Request.Builder b = new Request.Builder().url("http://localhost:" + port + path).get();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return CLIENT.newCall(b.build()).execute();
    }

    @Test
    void redirect_behindTlsProxy_usesExternalSchemeHostAndPort() throws Exception {
        Response resp = get("/e2e-fwd/redirect",
                "Host", "internal-app:8080",
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "public.example.com",
                "X-Forwarded-Port", "443");
        try {
            assertEquals(302, resp.code());
            assertEquals("https://public.example.com/cb", resp.header("Location"),
                    "代理后重定向应使用外部 scheme/host/port，实际 " + resp.header("Location"));
        } finally {
            resp.close();
        }
    }

    @Test
    void redirect_behindTlsProxy_nonDefaultPortKeptInLocation() throws Exception {
        Response resp = get("/e2e-fwd/redirect",
                "Host", "internal-app:8080",
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "public.example.com",
                "X-Forwarded-Port", "8443");
        try {
            assertEquals(302, resp.code());
            assertEquals("https://public.example.com:8443/cb", resp.header("Location"),
                    "非默认外部端口必须保留，实际 " + resp.header("Location"));
        } finally {
            resp.close();
        }
    }

    @Test
    void serverName_usesForwardedHost() throws Exception {
        Response resp = get("/e2e-fwd/info",
                "Host", "internal-app:8080",
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "public.example.com");
        try {
            assertEquals(200, resp.code());
            assertEquals("public.example.com", field(resp.body().string(), "serverName"),
                    "getServerName() 应反映外部主机名而非内网 Host");
        } finally {
            resp.close();
        }
    }

    @Test
    void serverPort_usesForwardedPort() throws Exception {
        Response resp = get("/e2e-fwd/info",
                "Host", "internal-app:8080",
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Port", "443");
        try {
            assertEquals(200, resp.code());
            assertEquals("443", field(resp.body().string(), "serverPort"),
                    "getServerPort() 应反映外部端口而非内部监听端口");
        } finally {
            resp.close();
        }
    }

    @Test
    void requestUrl_usesExternalAuthority() throws Exception {
        Response resp = get("/e2e-fwd/info",
                "Host", "internal-app:8080",
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "public.example.com",
                "X-Forwarded-Port", "443");
        try {
            assertEquals(200, resp.code());
            assertEquals("https://public.example.com/e2e-fwd/info",
                    field(resp.body().string(), "requestUrl"),
                    "getRequestURL() 应反映外部权威（scheme+host+端口省略规则）");
        } finally {
            resp.close();
        }
    }

    @Test
    void rfc7239ForwardedHostAndProto_spelledOut() throws Exception {
        // RFC 7239 同时给出 proto 与 host：两者都应被采用（host 覆盖内网 Host 头）
        Response resp = get("/e2e-fwd/redirect",
                "Host", "internal-app:8080",
                "Forwarded", "for=192.0.2.60;proto=https;host=public.example.com");
        try {
            assertEquals(302, resp.code());
            assertEquals("https://public.example.com/cb", resp.header("Location"),
                    "RFC 7239 Forwarded 的 host/proto 都应生效，实际 " + resp.header("Location"));
        } finally {
            resp.close();
        }
    }

    @Test
    void noForwardedHeaders_fallsBackToHostHeaderAndLocalPort() throws Exception {
        Response resp = get("/e2e-fwd/redirect", "Host", "localhost:" + port);
        try {
            assertEquals(302, resp.code());
            assertEquals("http://localhost:" + port + "/cb", resp.header("Location"),
                    "无转发头时应基于 Host 与本地端口构造绝对 URL，实际 " + resp.header("Location"));
        } finally {
            resp.close();
        }
    }

    private static String field(String json, String name) {
        int idx = json.indexOf("\"" + name + "\":\"");
        if (idx < 0) {
            return null;
        }
        int start = idx + name.length() + 4;
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    @TestConfiguration
    static class ForwardedConfig {
        @Bean
        ForwardedController forwardedController() {
            return new ForwardedController();
        }
    }

    @RestController
    static class ForwardedController {

        @GetMapping("/e2e-fwd/redirect")
        public String redirect(HttpServletResponse response) throws IOException {
            response.sendRedirect("/cb");
            return null;
        }

        @GetMapping("/e2e-fwd/info")
        public Map<String, String> info(HttpServletRequest request) {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("serverName", request.getServerName());
            out.put("serverPort", String.valueOf(request.getServerPort()));
            out.put("scheme", request.getScheme());
            out.put("requestUrl", request.getRequestURL().toString());
            return out;
        }
    }
}
