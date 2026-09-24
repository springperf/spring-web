package io.springperf.web.core.arg.provider;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.resolver.RequestBodyResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;

public class RequestBodyResolverProvider implements StaticArgumentResolverProvider {
    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return parameter.hasParameterAnnotation(RequestBody.class);
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext,
            WebContext webContext) {
        // getParameterAnnotation 声明为 @Nullable（supports 已保证存在，这里仍显式兜底）
        RequestBody requestBody = parameter.getParameterAnnotation(RequestBody.class);
        if (requestBody == null) {
            return null;
        }
        return new RequestBodyResolver(webContext, mappingContext, parameter, requestBody.required());
    }
}
