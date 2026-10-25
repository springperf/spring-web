package io.springperf.webtest.dynamic;

import io.springperf.webtest.BaseE2ETest;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态注册（{@code ServletContext#addServlet/addFilter}）的端到端验证。
 * <p>
 * {@link DynamicRegistrationInitializer} 在 {@code contextInitialized} 里登记， 请求路径证明：路由真的注册了、filter 真的进了链、
 * filter 的 init-param 真的生效。
 * </p>
 */
class DynamicRegistrationE2eTest extends BaseE2ETest {

    @Test
    void dynamicallyRegisteredServlet_isRouted() throws IOException {
        Request request = new Request.Builder().url(url("/api/dyn/hello")).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            String body = response.body().string();
            assertTrue(response.isSuccessful(), "status=" + response.code() + " body=" + body);
            assertEquals("dynamic-servlet", body.trim());
        }
    }

    @Test
    void dynamicallyRegisteredFilter_appliesWithInitParams() throws IOException {
        Request request = new Request.Builder().url(url("/api/dyn/hello")).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            assertEquals("on", response.header("X-Dyn-Filter"),
                    "动态注册 filter 的 setInitParameter 必须经 init() 传到 filter");
        }
    }

    @Test
    void dynamicFilter_isScopedToItsUrlPattern() throws IOException {
        // 只映射了 /dyn/*：其他路径不得出现该响应头（否则等于被静默升格为全局 filter）
        Request request = new Request.Builder().url(url("/api/no-such-path")).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            assertNull(response.header("X-Dyn-Filter"), "动态 filter 不应作用于 /dyn/* 之外的路径");
        }
    }

    @Test
    void dynamicServlet_doesNotCatchOtherPaths() throws IOException {
        Request request = new Request.Builder().url(url("/api/dyn/other")).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            assertEquals(404, response.code(), "未映射路径必须 404，不能因为动态注册而兜底");
        }
    }
}
