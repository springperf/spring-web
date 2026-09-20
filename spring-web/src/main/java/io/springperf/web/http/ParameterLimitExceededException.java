package io.springperf.web.http;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 请求参数总数超出 {@code server.max-parameter-count} 限制时抛出。
 * <p>对齐 Spring Boot / Tomcat 的 {@code maxParameterCount} 语义（防 hash DoS）：
 * 限制 query + form-urlencoded + multipart attribute 的参数值总个数。</p>
 *
 * <p>继承 {@link ResponseStatusException}(BAD_REQUEST)：该异常可能在 MVC 参数解析期
 * （而非管线期）懒触发，此时经 {@code ExceptionRegistry} 异常体系路由——
 * ResponseStatusExceptionResolver 按状态码渲染 400，而非兜底 500。</p>
 *
 * @since 2.7.0
 */
public class ParameterLimitExceededException extends ResponseStatusException {

    private final int limit;
    private final int actual;

    public ParameterLimitExceededException(int limit, int actual) {
        super(HttpStatus.BAD_REQUEST,
                "Number of request parameters (" + actual + ") exceeds the configured limit (" + limit + ")");
        this.limit = limit;
        this.actual = actual;
    }

    /** 配置的限制值（{@code server.max-parameter-count}）。 */
    public int getLimit() {
        return limit;
    }

    /** 实际解析出的参数值总数。 */
    public int getActual() {
        return actual;
    }
}
