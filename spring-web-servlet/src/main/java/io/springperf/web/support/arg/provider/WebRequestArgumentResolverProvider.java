package io.springperf.web.support.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.provider.StaticArgumentResolverProvider;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

public class WebRequestArgumentResolverProvider implements StaticArgumentResolverProvider {

    private final StaticArgumentResolver resolver = (request, response) -> {
        ServletAdapterContext adapterContext = ServletAttribute.getAdapterContext(request, response);
        return new ServletWebRequest(adapterContext.getRequest(), adapterContext.getResponse());
    };

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
