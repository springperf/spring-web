package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 主端口 SSL 集成测试。
 * <p>验证 {@code server.ssl.*} 配置对主端口生效，HTTPS 请求可达、HTTP 被拒绝。</p>
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.context-path=/api",
        "server.ssl.enabled=true",
        "server.ssl.key-store=classpath:test-keystore.p12",
        "server.ssl.key-store-password=changeit",
        "server.ssl.key-store-type=PKCS12",
        "management.endpoints.web.exposure.include=health"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
public class SslServerTest {

    @LocalServerPort
    private int serverPort;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .sslSocketFactory(trustAllSslContext().getSocketFactory(), trustAllCertManager())
            .hostnameVerifier((hostname, session) -> true)
            .build();

    private String url(String path) {
        return "http://localhost:" + serverPort + path;
    }

    private String httpsUrl(String path) {
        return "https://localhost:" + serverPort + path;
    }

    @Test
    void httpsHealthEndpoint_shouldReturnUp() throws Exception {
        Request req = new Request.Builder()
                .url(httpsUrl("/api/actuator/health"))
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("UP", body.get("status"));
        }
    }

    @Test
    void httpRequest_shouldBeRejected() {
        // 主端口仅监听 HTTPS：明文 HTTP 请求应因 TLS 握手失败被拒绝（IO 层异常），而非返回 200
        Request req = new Request.Builder()
                .url(url("/api/actuator/health"))
                .get()
                .build();
        assertThrows(java.io.IOException.class, () -> {
            try (Response resp = new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .readTimeout(Duration.ofSeconds(2))
                    .build()
                    .newCall(req).execute()) {
                // Should not reach here
            }
        });
    }

    private static SSLContext trustAllSslContext() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAllCertManager()}, null);
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static X509TrustManager trustAllCertManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
