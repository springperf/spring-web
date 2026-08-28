package io.springperf.webtest.servlet;

import io.springperf.webtest.BaseE2ETest;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ServletE2eTest extends BaseE2ETest {

    private static final String BASE = "http://localhost:9090/api";

    @Test
    void exactRoute_servesServletContent() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/e2e-servlet")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            String body = resp.body().string();
            assertTrue(resp.isSuccessful(), "status=" + resp.code() + " body=" + body);
            assertTrue(body.contains("\"uri\":\"/api/e2e-servlet\""), "body=" + body);
            assertTrue(body.contains("\"serverInfo\":\"spring-perf-web\""), "body=" + body);
        }
    }

    @Test
    void pathMappingRoute_servesNestedContent() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/e2e-servlet/sub/deep?echo=hi")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            String body = resp.body().string();
            assertTrue(resp.isSuccessful(), "status=" + resp.code() + " body=" + body);
            assertTrue(body.contains("\"uri\":\"/api/e2e-servlet/sub/deep\""), "body=" + body);
            assertTrue(body.contains("\"echo\":\"hi\""), "body=" + body);
            assertTrue(body.contains("\"ctx\":\"/api\""), "body=" + body);
        }
    }

    @Test
    void unmappedRoute_returns404() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/e2e-servlet-unknown")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            assertEquals(404, resp.code());
        }
    }
}
