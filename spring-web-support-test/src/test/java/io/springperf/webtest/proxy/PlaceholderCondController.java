package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 在条件表达式中使用占位符 {@code ${...}}，验证 {@code MappingRegistry.initMatcher}
 * 对 headers/params/consumes/produces 的占位符解析能力。
 * <p>
 * 占位符值由测试类的 {@code @SpringBootTest(properties=...)} 提供。
 */
@RestController
@RequestMapping("/placeholder-cond")
public class PlaceholderCondController {

    // ============ headers 占位符：${test.header.cond} ============

    @GetMapping(value = "/header-check", headers = "${test.header.cond:X-Default-Never=never}")
    public Map<String, Object> headerCheck() {
        Map<String, Object> m = new HashMap<>();
        m.put("matched", "header");
        return m;
    }

    // ============ params 占位符：${test.param.cond} ============

    @GetMapping(value = "/param-check", params = "${test.param.cond:default-never}")
    public Map<String, Object> paramCheck() {
        Map<String, Object> m = new HashMap<>();
        m.put("matched", "param");
        return m;
    }

    // ============ consumes 占位符：${test.consumes.cond} ============

    @PostMapping(value = "/consume-check", consumes = "${test.consumes.cond:application/octet-stream}")
    public Map<String, Object> consumeCheck() {
        Map<String, Object> m = new HashMap<>();
        m.put("matched", "consume");
        return m;
    }

    // ============ produces 占位符：${test.produces.cond} ============

    @GetMapping(value = "/produce-check", produces = "${test.produces.cond:application/json}")
    public Map<String, Object> produceCheck() {
        Map<String, Object> m = new HashMap<>();
        m.put("matched", "produce");
        return m;
    }
}