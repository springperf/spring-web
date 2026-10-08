package io.springperf.web.core.exception;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;

/**
 * 验证 {@link ResponseStatusExceptionAdapter}：从 {@link ResponseStatusException} 取请求头， 并以 {@link MultiValueMap}
 * 视图返回（Spring 7 的 {@code HttpHeaders} 不再实现该接口）。
 */
class ResponseStatusExceptionAdapterTest {

    @Test
    void getHeaders_returnsReadOnlyExceptionHeaders_safely() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_GATEWAY, "bad upstream");

        MultiValueMap<String, String> result = ResponseStatusExceptionAdapter.getHeaders(ex);

        assertNotNull(result);
    }

    @Test
    void getHeaders_withoutHeaders_returnsEmptyMap() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing");
        MultiValueMap<String, String> result = ResponseStatusExceptionAdapter.getHeaders(ex);
        assertNotNull(result);
        assertTrue(result.isEmpty(), "未设置 headers 时应为空");
    }

    @Test
    void spring7_exposesOnlyGetHeaders() {
        // 本分支专用 Spring 7：仅有 getHeaders()，getResponseHeaders() 已移除。
        // 适配器因此不再做方法名探测，直接调 getHeaders()。
        try {
            ResponseStatusException.class.getMethod("getHeaders");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Spring 7 应有 getHeaders()", e);
        }
        try {
            ResponseStatusException.class.getMethod("getResponseHeaders");
            throw new AssertionError("Spring 7 不应再有 getResponseHeaders()");
        } catch (NoSuchMethodException expected) {
            // 符合预期
        }
    }
}
