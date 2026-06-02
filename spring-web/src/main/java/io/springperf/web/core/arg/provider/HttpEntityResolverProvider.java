package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.resolver.HttpEntityArgResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.http.RequestEntity;

public class HttpEntityResolverProvider implements StaticArgumentResolverProvider {
    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return (HttpEntity.class == parameter.getParameterType() || RequestEntity.class == parameter.getParameterType());
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return new HttpEntityArgResolver(webContext, parameter);
    }
}
