package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * P1 参数边界场景接口：声明 @RequestBody/@PathVariable/@RequestParam/@RequestHeader 参数注解。
 * 实现类不重复参数注解，验证 CGLIB 代理场景下 findAnnotatedMethod 能正确解析所有注解类型。
 */
@RequestMapping("/proxy-p1")
public interface ParamTestApi {

    String mixedParams(@RequestBody String body, @PathVariable("id") String id,
                       @RequestParam("key") String key, @RequestHeader("X-Custom") String header,
                       @RequestParam(value = "opt", required = false) String optional,
                       @RequestParam(value = "def", defaultValue = "fallback") String withDefault);

    String emptyBody(@RequestBody String body);

    String multiHeader(@RequestHeader("X-Multi") List<String> values,
                       @RequestHeader(value = "X-Optional", required = false) String opt);

    String requiredHeader(@RequestHeader("X-Required") String value);
}