package io.springperf.webtest;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class CoreFeaturesWebFilterTest extends BaseE2ETest {

    private final String baseUrl = "http://localhost:9090/api/core";

    @Test
    void webFilter_addsHeaderToResponse() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/bytes")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String filterHeader = resp.header("X-Test-Filter");
            assertNotNull(filterHeader, "WebFilter should add X-Test-Filter header");
            assertEquals("applied", filterHeader);
        }
    }

    @Test
    void webFilter_headerPresentOnAllEndpoints() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/header")
                .header("X-Custom-Header", "test")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String filterHeader = resp.header("X-Test-Filter");
            assertNotNull(filterHeader, "WebFilter should apply to all endpoints");
        }
    }
}