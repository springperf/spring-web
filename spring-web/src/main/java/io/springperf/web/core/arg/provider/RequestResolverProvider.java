package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpRequest;
import org.springframework.http.server.ServerHttpRequest;

public class RequestResolverProvider implements StaticArgumentResolverProvider {

    private final StaticArgumentResolver httpRequestResolver = ((request, response) -> request);

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        Class<?> parameterType = parameter.getParameterType();
        if (WebServerHttpRequest.class.equals(parameterType) || ServerHttpRequest.class.equals(parameterType)
                || HttpRequest.class.equals(parameterType) || HttpInputMessage.class.equals(parameterType)) {
            return true;
        }
        return false;
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return httpRequestResolver;
    }
}
