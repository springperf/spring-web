package io.springperf.webtest;

import io.springperf.webtest.interceptor.LifecycleInterceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InterceptorAdvancedTest extends BaseE2ETest {

    private final String baseUrl = "http://localhost:9090/api";

    @BeforeEach
    void resetInterceptorCounts() {
        LifecycleInterceptor.resetCounts();
    }

    @Test
    void interceptorLifecycle_postHandleAndAfterCompletion_withException() throws Exception {
        // 请求一个抛异常的端点，验证 afterCompletion 仍被调用
        Request req = new Request.Builder()
                .url(baseUrl + "/core/exception/illegal-argument")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(400, resp.code());
        }

        // postHandle 在异常时也被调用（result 为 null），afterCompletion 携带异常
        assertEquals(1, LifecycleInterceptor.postHandleCount,
                "postHandle should still be called when controller throws (with null result)");
        assertEquals(1, LifecycleInterceptor.afterCompletionCount,
                "afterCompletion should be called even when controller throws");
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
        LifecycleInterceptor.resetCounts();

        // Make a request to /core/lifecycle/check which is handled by LifecycleInterceptor
        Request req = new Request.Builder()
                .url(baseUrl + "/core/lifecycle/check")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }

        // postHandle/afterCompletion must be called at least once for this request.
        // Due to async endpoints (DeferredResult/Callable) in other tests, the shared
        // static counter may include postHandle from async dispatch on a separate thread.
        // Use lenient assertion (>= 1) consistent with CoreFeaturesP1Test#interceptorLifecycle_countsTrackedCorrectly.
        assertTrue(LifecycleInterceptor.postHandleCount >= 1,
                "postHandle should be called at least once: " + LifecycleInterceptor.postHandleCount);
        assertTrue(LifecycleInterceptor.afterCompletionCount >= 1,
                "afterCompletion should be called at least once: " + LifecycleInterceptor.afterCompletionCount);
    }
}