package io.springperf.web.core.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;

/**
 * 取 {@link ResponseStatusException} 的响应头，并补齐 {@code MultiValueMap} 类型缺口。
 * <p>
 * 两个 Spring 7（SB 4.x）相关的点：
 * <ul>
 * <li>方法名：Spring 6.2 之前是 {@code getResponseHeaders()}，Spring 7 只有 {@code getHeaders()}。
 * 本分支（4.1.x）专用 Spring 7，故直接调 {@code getHeaders()}，不再做方法名探测。</li>
 * <li>类型：Spring 7 的 {@code HttpHeaders} 不再实现 {@code MultiValueMap}，故经
 * {@code asMultiValueMap()} 取内部 map（零拷贝视图），避免调用点做 O(n) 复制。</li>
 * </ul>
 * 说明：本类不是跨版本兼容层——早期曾用反射 + {@code MethodHandle} 同时兼容
 * Spring 6.2 的两种方法名，专用化后那层探测已移除。
 */
public final class ResponseStatusExceptionAdapter {

    private ResponseStatusExceptionAdapter() {
    }

    /**
     * 取 {@code ex} 的响应头，以 {@link MultiValueMap} 视图返回。
     */
    public static MultiValueMap<String, String> getHeaders(ResponseStatusException ex) {
        HttpHeaders headers = ex.getHeaders();
        return headers.asMultiValueMap();
    }
}
