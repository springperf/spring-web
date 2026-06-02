package io.springperf.webtest;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ExceptionHandlingTest extends BaseE2ETest {

    private final String baseUrl = "http://localhost:9090/api/demo";

    @Test
    void testNotFoundEndpoint() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/nonexistent-path")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    @Test
    void testMethodNotAllowed() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/echo")
                .method("DELETE", RequestBody.create(MediaType.parse("text/plain"), ""))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            int code = resp.code();
            assertTrue(code == 405 || code == 404, "Expected 405 or 404, got " + code);
        }
    }
}