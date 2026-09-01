package io.springperf.webtest;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mTLS（双向 TLS）真实握手集成测试。
 * <p>验证 {@code client-auth=need} + {@code trust-store} 配置生效：
 * <ul>
 *   <li>携带受信客户端证书 → 握手成功，返回 200</li>
 *   <li>不携带客户端证书 → 服务端要求客户端认证，握手被拒（IO 异常）</li>
 * </ul>
 * 使用同一 {@code test-keystore.p12} 同时作为服务端身份与信任锚（自签场景）。
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.context-path=/api",
        "server.ssl.enabled=true",
        "server.ssl.key-store=classpath:test-keystore.p12",
        "server.ssl.key-store-password=changeit",
        "server.ssl.key-store-type=PKCS12",
        "server.ssl.client-auth=need",
        "server.ssl.trust-store=classpath:test-keystore.p12",
        "server.ssl.trust-store-password=changeit",
        "server.ssl.trust-store-type=PKCS12"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
public class SslMutualTlsTest {

    @LocalServerPort
    private int serverPort;

    private String httpsUrl(String path) {
        return "https://localhost:" + serverPort + path;
    }

    /** 带客户端证书的 client：信任所有服务端证书，且用 test-keystore 提供客户端证书 */
    private static OkHttpClient clientWithCert() throws Exception {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(10))
                .sslSocketFactory(sslContext(true).getSocketFactory(), trustAllManager())
                .hostnameVerifier((hostname, session) -> true)
                .build();
    }

    /** 无客户端证书的 client：信任所有服务端证书，但不提供客户端证书 */
    private static OkHttpClient clientWithoutCert() throws Exception {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(10))
                .sslSocketFactory(sslContext(false).getSocketFactory(), trustAllManager())
                .hostnameVerifier((hostname, session) -> true)
                .build();
    }

    /** 构造 SSLContext：provideClientCert=true 时从 test-keystore 加载 KeyManager（携带客户端证书） */
    private static SSLContext sslContext(boolean provideClientCert) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        javax.net.ssl.KeyManager[] kms = null;
        if (provideClientCert) {
            KeyStore ks = loadKeyStore();
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "changeit".toCharArray());
            kms = kmf.getKeyManagers();
        }
        sslContext.init(kms, new TrustManager[]{trustAllManager()}, null);
        return sslContext;
    }

    private static KeyStore loadKeyStore() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = SslMutualTlsTest.class.getResourceAsStream("/test-keystore.p12")) {
            ks.load(in, "changeit".toCharArray());
        }
        return ks;
    }

    private static X509TrustManager trustAllManager() {
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

    @Test
    void clientWithCertificate_handshakeSucceeds() throws Exception {
        Request req = new Request.Builder()
                .url(httpsUrl("/api/core/bytes"))
                .get()
                .build();
        try (Response resp = clientWithCert().newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("Hello, Bytes!", resp.body().string());
        }
    }

    @Test
    void clientWithoutCertificate_handshakeRejected() throws Exception {
        Request req = new Request.Builder()
                .url(httpsUrl("/api/core/bytes"))
                .get()
                .build();
        // client-auth=need 下，缺少客户端证书会触发 TLS 握手失败（IO 层异常）
        assertThrows(java.io.IOException.class, () -> {
            try (Response resp = clientWithoutCert().newCall(req).execute()) {
                assertTrue(resp.code() >= 400, "缺证书不应返回 2xx，实际 " + resp.code());
            }
        });
    }
}