package io.springperf.web.support.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.provider.StaticArgumentResolverProvider;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class WebRequestArgumentResolverProvider implements StaticArgumentResolverProvider {

    private final StaticArgumentResolver resolver = (request, response) -> {
        ServletWebRequest servletWebRequest = buildServletWebRequest();
        return servletWebRequest;
    };

    protected ServletWebRequest buildServletWebRequest() {
        ServletWebRequest servletWebRequest = null;
        Object attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes) {
            org.springframework.web.context.request.ServletRequestAttributes servletRequestAttributes =
                    (org.springframework.web.context.request.ServletRequestAttributes) attrs;
            HttpServletRequest httpReq = servletRequestAttributes.getRequest();
            HttpServletResponse httpResp = servletRequestAttributes.getResponse();
            if (httpResp != null) {
                servletWebRequest = new ServletWebRequest(httpReq, httpResp);
            } else {
                servletWebRequest = new ServletWebRequest(httpReq);
            }
        }
        return servletWebRequest;
    }

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        Class<?> paramType = parameter.getParameterType();
        return paramType != null && WebRequest.class.isAssignableFrom(paramType);
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return resolver;
    }
}
