package io.springperf.webtest;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InterceptorTest extends BaseE2ETest {

    private final String baseUrl = "http://localhost:9090/api/core";

    @Test
    void testInterceptorPass() throws Exception {
        Request req = new Request.Builder().url(baseUrl + "/interceptor/pass").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void testInterceptorBlock() throws Exception {
        Request req = new Request.Builder().url(baseUrl + "/name").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(401, resp.code());
        }
    }
}