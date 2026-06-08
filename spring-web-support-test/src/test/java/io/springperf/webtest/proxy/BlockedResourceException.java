package io.springperf.webtest.proxy;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * P4 test: {@code @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)} 异常类。
 * 验证 CGLIB 代理 Controller 抛出此类异常时框架能正确映射状态码。
 */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class BlockedResourceException extends RuntimeException {

    public BlockedResourceException(String message) {
        super(message);
    }
}