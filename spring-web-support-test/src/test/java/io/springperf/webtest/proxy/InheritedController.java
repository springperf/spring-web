package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实现 MiddleApi（间接实现 RootApi），方法上有路由注解，参数上无注解。
 * MiddleApi → RootApi 两级接口继承，参数注解分布在两级接口中。
 * <p>
 * 验证 CGLIB 代理场景下 findAnnotatedMethod 能遍历多层接口层级。
 */
@RestController
@RequestMapping("/proxy-inherit")
public class InheritedController implements MiddleApi {

    @PostMapping("/root-save")
    @Override
    public String rootSave(String body, String id) {
        return body + "-" + id;
    }

    @GetMapping("/middle-query")
    @Override
    public String middleQuery(String name) {
        return "hello-" + name;
    }
}