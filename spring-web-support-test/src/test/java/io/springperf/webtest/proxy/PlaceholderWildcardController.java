package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 占位符高级场景：占位符 + 通配符路径、多段占位符。
 * <p>
 * 路径在 initComponentPhase1 中由 registerMapping → resolvePathPlaceholders 解析，
 * 验证 optimizeMapping 能正确将解析后的通配符路径分流到对应 RouterOptimizer。
 */
@RestController
public class PlaceholderWildcardController {

    @GetMapping("${proxy.placeholder.path:/api/placeholder}/{id}")
    public String placeholderWithPathVar(@PathVariable("id") String id) {
        return "wildcard-" + id;
    }

    @GetMapping("${proxy.placeholder.path:/api/placeholder}/${another.key:multi}/detail")
    public String multiPlaceholder(@RequestParam("q") String q) {
        return "multi-" + q;
    }
}