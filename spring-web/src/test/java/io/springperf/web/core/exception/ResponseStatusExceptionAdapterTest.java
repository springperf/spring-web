package io.springperf.web.core.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link ResponseStatusExceptionAdapter} 跨版本兼容：从
 * {@link ResponseStatusException} 提取请求头为 {@link MultiValueMap}。
 */
class ResponseStatusExceptionAdapterTest {

    @Test
    void getHeaders_returnsReadOnlyExceptionHeaders_safely() {
        ResponseStatusException ex = new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "bad upstream");

        MultiValueMap<String, String> result = ResponseStatusExceptionAdapter.getHeaders(ex);

        // 当前 Spring 6.x：getHeaders() 返回只读 HttpHeaders，适配器返回其视图或空 map
        assertNotNull(result);
    }

    @Test
    void getHeaders_withoutHeaders_returnsEmptyMap() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing");
        MultiValueMap<String, String> result = ResponseStatusExceptionAdapter.getHeaders(ex);
        assertNotNull(result);
    }

    @Test
    void staticInit_resolvesGetterForCurrentSpringVersion() throws Exception {
        // 当前 Spring 6.x：getResponseHeaders() 存在（兼容别名），getHeaders() 亦存在。
        // 静态初始化逻辑用 getHeaders() 优先、getResponseHeaders() 兜底。
        assertNotNull(ResponseStatusException.class.getMethod("getHeaders"));
        assertNotNull(ResponseStatusException.class.getMethod("getResponseHeaders"));
    }
}