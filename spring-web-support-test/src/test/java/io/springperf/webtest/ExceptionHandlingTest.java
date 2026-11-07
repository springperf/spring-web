package io.springperf.webtest;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExceptionHandlingTest extends BaseE2ETest {

    private String baseUrl() {
        return url("/api/demo");
    }

    @Test
    void testNotFoundEndpoint() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/nonexistent-path").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    @Test
    void testMethodNotAllowed() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/echo")
                .method("DELETE", RequestBody.create(MediaType.parse("text/plain"), "")).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            int code = resp.code();
            assertEquals(405, code, "DELETE /echo 应返回 405 Method Not Allowed, got " + code);
        }
    }
}
