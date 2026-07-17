package io.springperf.web.support.mvc.arg;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.provider.StaticArgumentResolverProvider;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderUtils;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;

/**
 * Adapts a Spring MVC {@link HandlerMethodArgumentResolver} to the framework's
 * {@link StaticArgumentResolverProvider} SPI.
 *
 * <p>During initialization, the provider calls the Spring resolver's
 * {@link HandlerMethodArgumentResolver#supportsParameter(MethodParameter)}
 * for each handler method parameter. On match, a {@link StaticArgumentResolver}
 * is created that bridges to the Spring resolver at runtime.
 *
 * <p>This replaces the old {@code RuntimeArgumentResolver} approach with
 * init-time resolution, eliminating per-request dispatch overhead.
 */
public class SpringHandlerMethodArgumentResolverProvider implements StaticArgumentResolverProvider {

    private final HandlerMethodArgumentResolver resolver;

    private final String componentName;

    public SpringHandlerMethodArgumentResolverProvider(HandlerMethodArgumentResolver resolver) {
        this.resolver = resolver;
        this.componentName = "springArgResolverProvider_" + resolver.getClass().getName();
    }

    @Override
    public String getComponentName() {
        return componentName;
    }

    @Override
    public int getOrder() {
        Integer order = AnnotationAwareOrderUtils.findOrder(resolver);
        return order != null ? order : Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return resolver.supportsParameter(parameter);
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return new SpringHandlerMethodArgumentResolver(parameter);
    }

    /**
     * Runtime resolver that bridges to the Spring {@link HandlerMethodArgumentResolver}.
     * Captures the {@link MethodParameter} at construction time to avoid re-looking it up.
     */
    private class SpringHandlerMethodArgumentResolver implements StaticArgumentResolver {

        private final MethodParameter methodParameter;

        SpringHandlerMethodArgumentResolver(MethodParameter methodParameter) {
            this.methodParameter = methodParameter;
        }

        @Override
        public Object resolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
            HttpServletRequest servletRequest = ServletAttribute.getRequest(request.getRequestContext());
            HttpServletResponse servletResponse = ServletAttribute.getResponse(request.getRequestContext());
            if (servletRequest == null) {
                servletRequest = new PerfHttpServletRequest(request);
                servletResponse = new PerfHttpServletResponse(response);
            }
            NativeWebRequest webRequest = new ServletWebRequest(servletRequest, servletResponse);
            return resolver.resolveArgument(methodParameter, null, webRequest, null);
        }
    }
}