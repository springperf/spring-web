package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * @RequestMapping 负向条件 + CGLIB 代理测试。
 * 覆盖 headers / params / consumes 的否定式条件（! 前缀）。
 */
@RestController
@RequestMapping("/proxy-cond-extra")
public class NegativeCondController {

    @GetMapping(value = "/no-block-header", headers = "!X-Block")
    public Map<String, Object> noBlockHeader() {
        Map<String, Object> m = new HashMap<>();
        m.put("passed", true);
        return m;
    }

    @GetMapping(value = "/no-skip-param", params = "!skip")
    public Map<String, Object> noSkipParam() {
        Map<String, Object> m = new HashMap<>();
        m.put("passed", true);
        return m;
    }

    @PostMapping(value = "/not-xml", consumes = "!application/xml")
    public Map<String, Object> notXml(@RequestBody(required = false) String body) {
        Map<String, Object> m = new HashMap<>();
        m.put("accepted", true);
        return m;
    }

    @GetMapping(value = "/with-header", headers = "X-Required=present")
    public Map<String, Object> withRequiredHeader() {
        Map<String, Object> m = new HashMap<>();
        m.put("matched", true);
        return m;
    }
}