package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @RequestMapping 条件限定场景：测试 params/headers 条件匹配路由。
 * <p>
 * 同一个路径 /greet 根据请求参数是否存在路由到不同方法，
 * 验证 ParamOrHeaderMatcher 在代理 Controller 场景下正常工作。
 */
@RestController
@RequestMapping("/proxy-cond")
public class ConditionalController {

    @GetMapping(value = "/greet", params = "lang")
    public String greetWithLang() {
        return "hello-lang";
    }

    @GetMapping(value = "/greet", params = "!lang")
    public String greetWithoutLang() {
        return "hello-default";
    }
}