package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实现 ProxyApi 接口，方法上有 @GetMapping/@PostMapping 路由注解，
 * 但参数上无 @RequestBody/@RequestParam/@PathVariable —— 这些在接口 ProxyApi 上。
 * <p>
 * 配合 {@link ProxyE2eApp} 的 {@code @EnableAspectJAutoProxy(proxyTargetClass = true)}，
 * Spring 会为此 Controller 创建 CGLIB 代理，验证框架的代理类注解解析能力。
 */
@RestController
@RequestMapping("/proxy-api")
public class ProxyController implements ProxyApi {

    @PostMapping("/save")
    @Override
    public String save(String body, String id) {
        return body + "-" + id;
    }

    @GetMapping("/get")
    @Override
    public String get(String name) {
        return "hello-" + name;
    }

    @GetMapping("/path/{id}")
    @Override
    public String path(String id) {
        return "id-" + id;
    }

    @PostMapping("/mixed/{tag}")
    @Override
    public String mixed(String body, String key, String tag) {
        return body + "-" + key + "-" + tag;
    }

    @GetMapping("/echo")
    @Override
    public String echo(String msg) {
        return msg;
    }
}