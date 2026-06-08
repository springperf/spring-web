package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 接口声明 @ModelAttribute 参数注解，实现类不重复。
 * CGLIB 代理场景下，createMethodParameters 需通过 findAnnotatedMethod
 * 从接口方法解析 @ModelAttribute 注解，使 ModelAttributeResolverProvider 能正确匹配。
 */
public interface ModelAttributeApi {

    String createUser(@ModelAttribute("user") UserForm user);
}