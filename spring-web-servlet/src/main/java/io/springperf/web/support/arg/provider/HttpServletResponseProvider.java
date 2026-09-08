package io.springperf.web.support.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.provider.StaticArgumentResolverProvider;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.support.servlet.ServletAttribute;
import org.springframework.core.MethodParameter;

import javax.servlet.http.HttpServletResponse;

public class HttpServletResponseProvider implements StaticArgumentResolverProvider {

    private final StaticArgumentResolver resolver = (request, response) ->
            ServletAttribute.getAdapterContext(request, response).getResponse();

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return HttpServletResponse.class.equals(parameter.getParameterType());
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return resolver;
    }
}
