package io.springperf.webtest;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;

import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP/2 h2c (cleartext HTTP/2 with prior knowledge) integration test.
 * <p>Verifies that when {@code server.http2.enabled=true}, the server
 * accepts h2c connections and processes requests correctly.</p>
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.http2.enabled=true",
        "server.servlet.context-path="
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class Http2ServerTest {

    @LocalServerPort
    private int serverPort;

    private String url(String path) {
        return "http://localhost:" + serverPort + path;
    }

    @Test
    void testH2cRequest() throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(10))
                .protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE))
                .build();

        // Test 1: core route that is known to work
        Request request = new Request.Builder()
                .url(url("/core/text-stream"))
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println("Test1 Status: " + response.code() + " Body: " + response.body().string());
            assertTrue(response.isSuccessful());
            assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        }
    }

    @Test
    void testH2cRequestBody() throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(10))
                .protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE))
                .build();

        String jsonBody = "{\"name\":\"test\"}";
        Request request = new Request.Builder()
                .url(url("/sample-json-body"))
                .post(okhttp3.RequestBody.create(jsonBody, okhttp3.MediaType.get("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            assertTrue(response.isSuccessful(), "h2c POST 搴旀垚鍔燂紝瀹為檯鐘舵€佺爜: " + response.code());
            assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol(), "璇锋眰搴旈€氳繃 h2c 浼犺緭");
            String body = response.body().string();
            assertTrue(body.contains("test"), "鍝嶅簲搴斿洖鏄捐姹備綋 name=test锛屽疄闄? " + body);
        }
    }

    @Test
    void testH2cRequestWithContextPath() throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(10))
                .protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE))
                .build();

        Request request = new Request.Builder()
                .url(url("/hello"))
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println("GET Status: " + response.code() + " Body: " + response.body().string());
            assertTrue(response.isSuccessful());
        }
    }
}