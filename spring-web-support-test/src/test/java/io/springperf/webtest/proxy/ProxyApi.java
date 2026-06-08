package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 接口声明参数注解，实现类不重复。
 * 当 CGLIB 代理后，createMethodParameters 需通过 findAnnotatedMethod
 * 从接口方法解析 @RequestBody/@RequestParam/@PathVariable 等参数注解。
 * <p>
 * 注意：方法级 @PostMapping/@GetMapping 需放在实现类上，
 * 因为框架用 targetClass.getDeclaredMethods() 扫描路由映射。
 */
@RequestMapping("/proxy-api")
public interface ProxyApi {

    String save(@RequestBody String body, @RequestParam("id") String id);

    String get(@RequestParam("name") String name);

    String path(@PathVariable("id") String id);

    String mixed(@RequestBody String body, @RequestParam("key") String key, @PathVariable("tag") String tag);

    String echo(@RequestParam("msg") String msg);
}