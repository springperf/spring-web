package io.springperf.web.support.mvc.arg;

import io.springperf.web.core.arg.RuntimeArgumentResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Adapts a Spring MVC {@link HandlerMethodArgumentResolver} to the framework's
 * {@link RuntimeArgumentResolver} interface.
 *
 * <p>The adapter creates Servlet API wrappers around the framework's request/response
 * objects so that existing Spring argument resolvers can be used without modification.
 */
public class SpringHandlerMethodArgumentResolverAdapter implements RuntimeArgumentResolver {

    private final HandlerMethodArgumentResolver resolver;

    private final String componentName;

    public SpringHandlerMethodArgumentResolverAdapter(HandlerMethodArgumentResolver resolver) {
        this.resolver = resolver;
        this.componentName = "springArgResolver_" + resolver.getClass().getName();
    }

    @Override
    public String getComponentName() {
        return componentName;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter, WebServerHttpRequest request, WebServerHttpResponse response) {
        return resolver.supportsParameter(parameter);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        HttpServletRequest servletRequest = new PerfHttpServletRequest(request);
        HttpServletResponse servletResponse = new PerfHttpServletResponse(response);
        NativeWebRequest webRequest = new ServletWebRequest(servletRequest, servletResponse);
        return resolver.resolveArgument(parameter, null, webRequest, null);
    }
}
