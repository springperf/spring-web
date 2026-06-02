package io.springperf.webtest;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RunInPoolTest extends BaseE2ETest {

    private final String baseUrl = "http://localhost:9090/api/core";

    @Test
    void testEventLoopThread() throws Exception {
        Request req = new Request.Builder().url(baseUrl + "/pool/event-loop").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void testBizPoolThread() throws Exception {
        Request req = new Request.Builder().url(baseUrl + "/pool/biz-pool").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }
}