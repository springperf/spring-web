package io.springperf.webtest;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
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
 * mTLS锛堝弻鍚?TLS锛夌湡瀹炴彙鎵嬮泦鎴愭祴璇曘€?
 * <p>楠岃瘉 {@code client-auth=need} + {@code trust-store} 閰嶇疆鐢熸晥锛?
 * <ul>
 *   <li>鎼哄甫鍙椾俊瀹㈡埛绔瘉涔?鈫?鎻℃墜鎴愬姛锛岃繑鍥?200</li>
 *   <li>涓嶆惡甯﹀鎴风璇佷功 鈫?鏈嶅姟绔姹傚鎴风璁よ瘉锛屾彙鎵嬭鎷掞紙IO 寮傚父锛?/li>
 * </ul>
 * 浣跨敤鍚屼竴 {@code test-keystore.p12} 鍚屾椂浣滀负鏈嶅姟绔韩浠戒笌淇′换閿氾紙鑷鍦烘櫙锛夈€?
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

    /** 甯﹀鎴风璇佷功鐨?client锛氫俊浠绘墍鏈夋湇鍔＄璇佷功锛屼笖鐢?test-keystore 鎻愪緵瀹㈡埛绔瘉涔?*/
    private static OkHttpClient clientWithCert() throws Exception {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(10))
                .sslSocketFactory(sslContext(true).getSocketFactory(), trustAllManager())
                .hostnameVerifier((hostname, session) -> true)
                .build();
    }

    /** 鏃犲鎴风璇佷功鐨?client锛氫俊浠绘墍鏈夋湇鍔＄璇佷功锛屼絾涓嶆彁渚涘鎴风璇佷功 */
    private static OkHttpClient clientWithoutCert() throws Exception {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(10))
                .sslSocketFactory(sslContext(false).getSocketFactory(), trustAllManager())
                .hostnameVerifier((hostname, session) -> true)
                .build();
    }

    /** 鏋勯€?SSLContext锛歱rovideClientCert=true 鏃朵粠 test-keystore 鍔犺浇 KeyManager锛堟惡甯﹀鎴风璇佷功锛?*/
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
        // client-auth=need 涓嬶紝缂哄皯瀹㈡埛绔瘉涔︿細瑙﹀彂 TLS 鎻℃墜澶辫触锛圛O 灞傚紓甯革級
        assertThrows(java.io.IOException.class, () -> {
            try (Response resp = clientWithoutCert().newCall(req).execute()) {
                assertTrue(resp.code() >= 400, "缂鸿瘉涔︿笉搴旇繑鍥?2xx锛屽疄闄?" + resp.code());
            }
        });
    }
}