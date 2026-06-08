package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试路径占位符解析：{@code ${proxy.placeholder.path:/api/placeholder}/echo}
 * 配置值在测试 properties 中定义为 /proxy/placeholder-resolved。
 */
@RestController
public class PlaceholderController {

    @GetMapping("${proxy.placeholder.path:/api/placeholder}/echo")
    public String echo(@RequestParam("msg") String msg) {
        return msg;
    }
}
