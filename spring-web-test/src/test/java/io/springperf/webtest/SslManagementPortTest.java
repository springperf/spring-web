package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 管理端口 SSL 集成测试。
 * <p>验证 {@code management.server.ssl.*} 配置对管理端口生效，
 * 管理端口通过 HTTPS 提供 Actuator 端点，主端口仍然通过 HTTP 提供业务服务。</p>
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "server.port=9099",
        "server.servlet.context-path=/api",
        "management.server.port=9098",
        "management.server.ssl.enabled=true",
        "management.server.ssl.key-store=classpath:test-keystore.p12",
        "management.server.ssl.key-store-password=changeit",
        "management.server.ssl.key-store-type=PKCS12",
        "management.endpoints.web.exposure.include=health"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SslManagementPortTest {

    private static final OkHttpClient SSL_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .sslSocketFactory(trustAllSslContext().getSocketFactory(), trustAllCertManager())
            .hostnameVerifier((hostname, session) -> true)
            .build();

    private static final OkHttpClient PLAIN_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void managementPortHttps_shouldServeHealth() throws Exception {
        Request req = new Request.Builder()
                .url("https://localhost:9098/actuator/health")
                .get()
                .build();
        try (Response resp = SSL_CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("UP", body.get("status"));
        }
    }

    @Test
    void mainPortHttp_shouldStillWork() throws Exception {
        // 管理端口隔离模式下主端口不提供 Actuator 端点，验证 HTTP 服务器本身可达
        Request req = new Request.Builder()
                .url("http://localhost:9099/api/core/hello")
                .get()
                .build();
        try (Response resp = PLAIN_CLIENT.newCall(req).execute()) {
            // 业务端点应正常响应
            assertTrue(resp.code() == 200 || resp.code() == 404,
                    "Main port should be reachable via HTTP, got " + resp.code());
        }
    }

    @Test
    void managementPortHttp_shouldBeRejected() {
        Request req = new Request.Builder()
                .url("http://localhost:9098/actuator/health")
                .get()
                .build();
        assertThrows(Exception.class, () -> {
            try (Response resp = new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .readTimeout(Duration.ofSeconds(2))
                    .build()
                    .newCall(req).execute()) {
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
