package io.springperf.webtest;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SessionInterceptorTest extends BaseE2ETest {

    private final String baseUrl = "http://localhost:9090/api";

    @Test
    void testProtectedEndpoint() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/demo/protected")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("authorized", resp.body().string());
        }
    }
}