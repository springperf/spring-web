package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟 CGLIB 代理 Controller 中抛异常的测试场景。
 * Controller 会被 CGLIB 代理，异常从代理方法传播出去，
 * 验证框架能正确捕获并交由 @ExceptionHandler 处理。
 */
@RestController
@RequestMapping("/proxy-api")
public class ProxyExceptionController {

    @GetMapping("/trigger-error")
    public String triggerError() {
        throw new IllegalStateException("test-proxy-error");
    }
}