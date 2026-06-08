package io.springperf.webtest;

import org.springframework.http.RequestEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

/**
 * P0 E2E 测试控制器：覆盖核心框架中遗漏的基础能力。
 */
@RestController
@RequestMapping("/p0")
public class P0TestController {

    // ============ 1. @RequestParam MultiValueMap ============

    @GetMapping("/multi-value-map")
    public String multiValueMap(@RequestParam MultiValueMap<String, String> params) {
        return params.getFirst("a") + "-" + params.getFirst("b");
    }

    // ============ 2. Locale 参数解析 ============

    @GetMapping("/locale")
    public String locale(Locale locale) {
        return locale.toLanguageTag();
    }

    // ============ 3. @RequestMapping 多路径 ============

    @GetMapping({"/multi-path-a", "/multi-path-b"})
    public String multiPath() {
        return "multi-path-ok";
    }

    // ============ 4. @RequestMapping method 数组 ============

    @RequestMapping(value = "/multi-method", method = {RequestMethod.GET, RequestMethod.POST})
    public String multiMethod() {
        return "multi-method-ok";
    }

    // ============ 5. @RequestMapping params 多条件（OR 语义） ============

    @GetMapping(value = "/multi-param", params = {"a=1", "b=2"})
    public String multiParamOr() {
        return "multi-param-matched";
    }

    // ============ 6. @RequestMapping headers 多条件（OR 语义） ============

    @GetMapping(value = "/multi-header", headers = {"X-A=1", "X-B=2"})
    public String multiHeaderOr() {
        return "multi-header-matched";
    }

    // ============ 7. RequestEntity 参数 ============

    @PostMapping("/request-entity")
    public String requestEntity(RequestEntity<String> reqEntity) {
        return "method=" + reqEntity.getMethod()
                + ",body=" + reqEntity.getBody();
    }

    // ============ 8. Interceptor return-false 测试端点 ============

    @GetMapping("/interceptor-return-false")
    public String interceptorReturnFalse() {
        return "should-not-be-reached";
    }

    // ============ 9. Interceptor return-true 测试端点（对照） ============

    @GetMapping("/interceptor-return-true")
    public String interceptorReturnTrue() {
        return "interceptor-allowed";
    }
}
