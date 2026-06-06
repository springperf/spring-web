package io.springperf.webtest;

import io.springperf.webtest.interceptor.LifecycleInterceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InterceptorAdvancedTest extends BaseE2ETest {

    private final String baseUrl = "http://localhost:9090/api";

    @BeforeEach
    void resetInterceptorCounts() {
        LifecycleInterceptor.resetCounts();
    }

    @Test
    void interceptorExcludedPath_shouldPassWithoutInterception() throws Exception {
        // /demo/echo is in the excludePathPatterns of LoginInterceptor
        // So it should return 302 (the echo GET response) instead of 401
        Request req = new Request.Builder()
                .url(baseUrl + "/demo/echo?received=test")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(302, resp.code(),
                    "Excluded path should be handled normally without interception");
            String body = resp.body().string();
            assertEquals("test", body);
        }
    }

    @Test
    void interceptorNonExcludedPath_shouldBeIntercepted() throws Exception {
        // /core/name is NOT excluded and its method name "name" triggers LoginInterceptor
        Request req = new Request.Builder()
                .url(baseUrl + "/core/name")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(401, resp.code(),
                    "Non-excluded path matching interceptor condition should be blocked");
        }
    }

    @Test
    void interceptorLifecycle_postHandleAndAfterCompletion_executed() throws Exception {
        // Make a request to /core/lifecycle/check which is handled by LifecycleInterceptor
        Request req = new Request.Builder()
                .url(baseUrl + "/core/lifecycle/check")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }

        // Verify postHandle and afterCompletion were called
        assertEquals(1, LifecycleInterceptor.postHandleCount,
                "postHandle should be called exactly once");
        assertEquals(1, LifecycleInterceptor.afterCompletionCount,
                "afterCompletion should be called exactly once");
    }
}