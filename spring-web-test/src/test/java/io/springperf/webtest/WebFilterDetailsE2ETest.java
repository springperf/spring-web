package io.springperf.webtest;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: WebFilter behavior details — filter headers still applied on non-200 paths
 * (404), and ordered filters both execute with distinct headers.
 */
public class WebFilterDetailsE2ETest extends BaseE2ETest {

    private String baseUrl() {
        return url("/api");
    }

    @Test
    void filterApplied_evenWhen404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/core/does-not-exist")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
            assertNotNull(resp.header("X-Test-Filter"),
                    "即使 404，filter 也应执行并添加响应头");
        }
    }

    @Test
    void filterApplied_onExceptionResponse() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/core/exception/controller-handler")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(500, resp.code());
            assertNotNull(resp.header("X-Test-Filter"),
                    "异常响应也应经过 filter 链");
        }
    }

    @Test
    void orderedFilters_bothHeadersPresent() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/core/bytes")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("executed", resp.header("X-Order-Low"));
            assertEquals("executed", resp.header("X-Order-High"));
        }
    }

    @Test
    void blockingFilter_returns403WithHeader() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/p2/blocked")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(403, resp.code());
            assertEquals("blocked", resp.header("X-Blocking-Filter"));
        }
    }
}