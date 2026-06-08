package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 父 Controller，定义 /proxy-parent/** 路径的路由方法。
 * 子类继承后验证 CGLIB 代理能正确继承路由注解。
 */
@RestController
@RequestMapping("/proxy-parent")
public class ParentController {

    @GetMapping("/greet")
    public String greet(@RequestParam("name") String name) {
        return "hello-" + name;
    }
}