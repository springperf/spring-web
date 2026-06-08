package io.springperf.webtest;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void mixedFilter_servletAndWebFilter_bothExecuted() throws Exception {
        // 验证 Servlet Filter 和 WebFilter 在同一个请求中都被执行
        Request req = new Request.Builder()
                .url(baseUrl + "/bridge/ping")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("called", resp.header("X-Web-Filter"),
                    "WebFilter (OrderedWebTestFilter) should add X-Web-Filter header");
            assertEquals("called", resp.header("X-Servlet-Filter"),
                    "Servlet Filter (OrderedServletTestFilter) should add X-Servlet-Filter header");
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