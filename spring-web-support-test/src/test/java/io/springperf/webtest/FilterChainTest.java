package io.springperf.webtest;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FilterChainTest extends BaseE2ETest {

    private final String baseUrl = "http://localhost:9090/api";

    @Test
    void testHealthFilter() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/health")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void testNotFound() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/nonexistent")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }
}