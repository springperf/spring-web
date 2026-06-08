package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 顶级接口：声明 @RequestBody/@RequestParam 参数注解。
 * 测试多级接口继承时 findAnnotatedMethod 的层级遍历能力。
 */
@RequestMapping("/proxy-inherit")
public interface RootApi {

    String rootSave(@RequestBody String body, @RequestParam("id") String id);
}