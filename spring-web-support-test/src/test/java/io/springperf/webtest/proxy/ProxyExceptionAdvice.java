package io.springperf.webtest.proxy;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局 @ExceptionHandler，用于捕获 ProxyExceptionController 抛出的异常。
 * <p>
 * 验证 CGLIB 代理 Controller 抛出异常后，框架的异常处理链路能正常工作：
 * 异常从代理方法传播 → ExceptionRegistry → ExceptionHandlerExceptionResolver
 * → @ExceptionHandler 方法。
 */
@RestControllerAdvice
public class ProxyExceptionAdvice {

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleIllegalState(IllegalStateException e) {
        return "handled:" + e.getMessage();
    }
}