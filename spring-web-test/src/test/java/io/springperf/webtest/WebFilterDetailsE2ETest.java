package io.springperf.webtest;

import io.springperf.webtest.filter.OrderedLowWebFilter;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * E2E: WebFilter behavior details — filter headers still applied on non-200 paths (404), and ordered filters both
 * execute with distinct headers.
 */
public class WebFilterDetailsE2ETest extends BaseE2ETest {

    private String baseUrl() {
        return url("/api");
    }

    @Test
    void filterApplied_evenWhen404() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/core/does-not-exist").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
            assertEquals("applied", resp.header("X-Test-Filter"), "即使 404，filter 也应写入精确头值");
        }
    }

    @Test
    void filterApplied_onExceptionResponse() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/core/exception/controller-handler").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(500, resp.code());
            assertEquals("applied", resp.header("X-Test-Filter"), "异常响应也应经过 filter 链并写入精确头值");
        }
    }

    @Test
    void orderedFilters_executeInAscendingOrder() throws Exception {
        // 只断言"两个 filter 都写了头"无法发现顺序颠倒：两个 filter 各自写常量 "executed"。
        // 这里让二者追加到同一个多值 header，按出现顺序判定 @Order 的升序语义（5 在 100 之前）。
        Request req = new Request.Builder().url(baseUrl() + "/core/bytes").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("executed", resp.header("X-Order-Low"));
            assertEquals("executed", resp.header("X-Order-High"));
            assertEquals(List.of("low", "high"), resp.headers(OrderedLowWebFilter.ORDER_HEADER),
                    "filter 应按 @Order 升序执行，实际: " + resp.headers(OrderedLowWebFilter.ORDER_HEADER));
        }
    }

    @Test
    void blockingFilter_returns403WithHeader() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/p2/blocked").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(403, resp.code());
            assertEquals("blocked", resp.header("X-Blocking-Filter"));
            assertFalse(resp.body().string().contains("not-reach-here"), "阻断后 handler 不得执行");
        }
    }
}
