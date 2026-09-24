package org.springframework.web.servlet.config.annotation;

import org.springframework.http.HttpStatus;

/**
 * Shim of Spring MVC's {@code ViewControllerRegistration}.
 * <p>
 * 收集 {@code addViewController}/{@code addRedirectViewController}/{@code addStatusController} 的注册信息，由
 * {@code WebMvcConfigurerBridge} 桥接为框架原生路由（{@code PathMappingContext}）。
 * </p>
 */
public class ViewControllerRegistration {

    private final String urlPath;
    private String viewName;
    private HttpStatus statusCode;

    public ViewControllerRegistration(String urlPath) {
        this.urlPath = urlPath;
    }

    /** 设置视图名（支持 {@code redirect:} 前缀形式）。 */
    public ViewControllerRegistration setViewName(String viewName) {
        this.viewName = viewName;
        return this;
    }

    /** 设置响应状态码（与 viewName 组合时先设状态再渲染视图）。 */
    public ViewControllerRegistration setStatusCode(HttpStatus statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    public String getUrlPath() {
        return urlPath;
    }

    public String getViewName() {
        return viewName;
    }

    public HttpStatus getStatusCode() {
        return statusCode;
    }
}
