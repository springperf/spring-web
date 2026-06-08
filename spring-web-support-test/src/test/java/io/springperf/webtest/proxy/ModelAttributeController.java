package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实现 ModelAttributeApi 接口，方法上有 @PostMapping 路由注解，
 * 但参数上无 @ModelAttribute —— 该注解在接口 ModelAttributeApi 上。
 * <p>
 * 配合 @EnableAspectJAutoProxy(proxyTargetClass = true)，
 * Spring 会为此 Controller 创建 CGLIB 代理，验证框架的代理类 @ModelAttribute 解析能力。
 */
@RestController
@RequestMapping("/proxy-api")
public class ModelAttributeController implements ModelAttributeApi {

    @PostMapping("/model-attr")
    @Override
    public String createUser(UserForm user) {
        return user.getName() + "-" + user.getAge();
    }
}