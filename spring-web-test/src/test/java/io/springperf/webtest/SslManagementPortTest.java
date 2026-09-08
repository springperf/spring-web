package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import io.springperf.web.autoconfigure.actuator.server.ManagementNettyHttpServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 绠＄悊绔彛 SSL 闆嗘垚娴嬭瘯銆?
 * <p>楠岃瘉 {@code management.server.ssl.*} 閰嶇疆瀵圭鐞嗙鍙ｇ敓鏁堬紝
 * 绠＄悊绔彛閫氳繃 HTTPS 鎻愪緵 Actuator 绔偣锛屼富绔彛浠嶇劧閫氳繃 HTTP 鎻愪緵涓氬姟鏈嶅姟銆?/p>
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
        "server.servlet.context-path=/api",
        "management.server.ssl.enabled=true",
        "management.server.ssl.key-store=classpath:test-keystore.p12",
        "management.server.ssl.key-store-password=changeit",
        "management.server.ssl.key-store-type=PKCS12",
        "management.endpoints.web.exposure.include=health"
})
@ContextConfiguration(initializers = SslManagementPortTest.ManagementPortInitializer.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SslManagementPortTest {

    /** 鍚姩鍓嶅垎閰嶄竴涓┖闂茬鍙ｄ綔涓虹鐞嗙鍙ｏ紝閬垮厤鍥哄畾绔彛琚崰鐢?鍐茬獊 */
    public static class ManagementPortInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            TestPropertyValues.of("management.server.port=" + freePort()).applyTo(ctx.getEnvironment());
        }
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @LocalServerPort
    private int mainPort;

    @Autowired
    private ManagementNettyHttpServer managementServer;

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

    private String mainUrl(String path) {
        return "http://localhost:" + mainPort + path;
    }

    private String managementHttpsUrl(String path) {
        return "https://localhost:" + managementServer.getActualPort() + path;
    }

    private String managementHttpUrl(String path) {
        return "http://localhost:" + managementServer.getActualPort() + path;
    }

    @Test
    void managementPortHttps_shouldServeHealth() throws Exception {
        Request req = new Request.Builder()
                .url(managementHttpsUrl("/actuator/health"))
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
        // 绠＄悊绔彛闅旂妯″紡涓嬩富绔彛涓嶆彁渚?Actuator 绔偣锛屼絾涓氬姟 HTTP 鏈嶅姟搴旀甯?
        Request req = new Request.Builder()
                .url(mainUrl("/api/core/bytes"))
                .get()
                .build();
        try (Response resp = PLAIN_CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code(), "涓荤鍙ｄ笟鍔＄鐐瑰簲閫氳繃 HTTP 姝ｅ父鍝嶅簲");
            assertEquals("Hello, Bytes!", resp.body().string());
        }
    }

    @Test
    void managementPortHttp_shouldBeRejected() {
        // 绠＄悊绔彛浠呯洃鍚?HTTPS锛氭槑鏂?HTTP 璇锋眰搴斿洜 TLS 鎻℃墜澶辫触琚嫆缁濓紙IO 灞傚紓甯革級锛岃€岄潪杩斿洖 200
        Request req = new Request.Builder()
                .url(managementHttpUrl("/actuator/health"))
                .get()
                .build();
        assertThrows(java.io.IOException.class, () -> {
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
