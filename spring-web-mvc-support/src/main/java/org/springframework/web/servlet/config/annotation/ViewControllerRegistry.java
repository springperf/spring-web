package org.springframework.web.servlet.config.annotation;

import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shim of Spring MVC's {@code ViewControllerRegistry}.
 * <p>
 * 收集用户 {@code WebMvcConfigurer#addViewControllers} 的注册项，由 {@code WebMvcConfigurerBridge}
 * 桥接为框架原生路由（{@code PathMappingContext}）。
 * </p>
 */
public class ViewControllerRegistry {

    private final List<ViewControllerRegistration> registrations = new ArrayList<>();

    /** 映射 {@code urlPath} → 视图名（经 ViewResolver 渲染）。 */
    public ViewControllerRegistration addViewController(String urlPath) {
        ViewControllerRegistration registration = new ViewControllerRegistration(urlPath);
        registrations.add(registration);
        return registration;
    }

    /** 映射 {@code urlPath} → 重定向（302 + Location）。 */
    public ViewControllerRegistration addRedirectViewController(String urlPath, String redirectUrl) {
        ViewControllerRegistration registration = new ViewControllerRegistration(urlPath);
        registration.setViewName("redirect:" + redirectUrl);
        registrations.add(registration);
        return registration;
    }

    /** 映射 {@code urlPath} → 仅返回状态码（如 204/错误占位页）。 */
    public ViewControllerRegistration addStatusController(String urlPath, HttpStatus statusCode) {
        ViewControllerRegistration registration = new ViewControllerRegistration(urlPath);
        registration.setStatusCode(statusCode);
        registrations.add(registration);
        return registration;
    }

    public List<ViewControllerRegistration> getRegistrations() {
        return Collections.unmodifiableList(registrations);
    }
}
