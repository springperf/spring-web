package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.resolver.RequestBodyResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;

public class RequestBodyResolverProvider implements StaticArgumentResolverProvider {
    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return parameter.hasParameterAnnotation(RequestBody.class);
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        RequestBody requestBody = parameter.getParameterAnnotation(RequestBody.class);
        return new RequestBodyResolver(webContext, mappingContext, parameter, requestBody.required());
    }
}
