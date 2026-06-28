package io.springperf.web.support.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.provider.StaticArgumentResolverProvider;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class HttpServletRequestProvider implements StaticArgumentResolverProvider {

    private final StaticArgumentResolver resolver = (request, response) -> {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes.getRequest();
    };

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return HttpServletRequest.class.equals(parameter.getParameterType());
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return resolver;
    }
}
